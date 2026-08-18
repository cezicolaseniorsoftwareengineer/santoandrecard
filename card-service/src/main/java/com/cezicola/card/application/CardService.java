package com.cezicola.card.application;

import com.cezicola.card.adapter.out.metrics.FinancialMetrics;
import com.cezicola.card.application.port.CardRepository;
import com.cezicola.card.application.port.RateLimiter;
import com.cezicola.card.domain.Card;
import com.cezicola.card.domain.CardNumber;
import com.cezicola.card.domain.CardPin;
import com.cezicola.card.domain.CardProduct;
import com.cezicola.card.domain.CardStatus;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class CardService {
    /** The card locks after this many consecutive wrong PINs. */
    public static final int MAX_PIN_ATTEMPTS = 5;

    /** SQL state class for an integrity constraint violation. */
    private static final String INTEGRITY_VIOLATION = "23";
    private static final String UQ_CARDS_TENANT_IDEMPOTENCY = "uq_cards_tenant_idempotency";
    private static final String UQ_CARDS_NUMBER = "uq_cards_number";
    /** Bounded: an exhausted number space must fail, not hang. */
    private static final int NUMBER_COLLISION_ATTEMPTS = 5;

    /** Attempts allowed per card inside {@link #PIN_THROTTLE_WINDOW}. */
    public static final int PIN_ATTEMPTS_PER_WINDOW = 3;
    public static final Duration PIN_THROTTLE_WINDOW = Duration.ofMinutes(1);

    private final CardRepository repository;
    private final RateLimiter rateLimiter;
    private final FinancialMetrics metrics;
    private final Clock clock;
    private final BigDecimal selfServiceCreditLimit;

    @Inject
    public CardService(CardRepository repository,
                       RateLimiter rateLimiter,
                       FinancialMetrics metrics,
                       @ConfigProperty(name = "card.self-service.credit-limit") BigDecimal selfServiceCreditLimit) {
        this(repository, rateLimiter, metrics, Clock.systemUTC(), selfServiceCreditLimit);
    }

    CardService(CardRepository repository, RateLimiter rateLimiter, FinancialMetrics metrics, Clock clock,
                BigDecimal selfServiceCreditLimit) {
        this.repository = repository;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
        this.clock = clock;
        this.selfServiceCreditLimit = selfServiceCreditLimit;
    }

    /**
     * Issues the calling customer's own card, at any hour, with no operator in
     * the loop.
     *
     * <p>Two properties make this safe to expose to the cardholder. The limit
     * comes from the issuer's policy and is never read from the request, so a
     * customer cannot choose what they are worth. And the idempotency key is
     * derived from the customer rather than supplied, which turns the existing
     * unique index on (tenant, key) into the guarantee that one customer ends up
     * with one card: a second request, a double-clicked button and two
     * simultaneous requests all return the same card instead of minting another
     * limit.
     */
    public Card issueForCustomer(UUID tenantId, UUID customerId) {
        return create(new CreateCardCommand(
                tenantId, customerId, selfServiceCreditLimit, CardProduct.PLATINUM, selfServiceKeyFor(customerId)));
    }

    static String selfServiceKeyFor(UUID customerId) {
        return "self-service:" + customerId;
    }

    /**
     * Creates a card at most once per idempotency key.
     *
     * <p>Checking for an existing key and then inserting cannot be atomic on its
     * own: two concurrent callers can both find nothing and both proceed. The
     * unique index on (tenant, key) is what actually enforces the guarantee, so
     * losing that race is an expected outcome rather than a failure. The loser
     * reads the winner's card and returns it, which is exactly what the caller
     * would have received had the requests not overlapped.
     */
    public Card create(CreateCardCommand command) {
        Optional<Card> alreadyCreated = inTransaction(() -> findByKey(command));
        if (alreadyCreated.isPresent()) {
            return sameRequestOrConflict(alreadyCreated.orElseThrow(), command);
        }

        try {
            return inTransaction(() -> insert(command));
        } catch (RuntimeException failure) {
            if (!isDuplicateKey(failure)) {
                throw failure;
            }
            Card winner = inTransaction(() -> findByKey(command)).orElseThrow(() -> failure);
            return sameRequestOrConflict(winner, command);
        }
    }

    /**
     * Sets or replaces the cardholder's PIN and clears the attempt counter.
     *
     * <p>Only the owner may do this, and the check happens here rather than in
     * the resource so no future entry point can skip it.
     */
    public Card setPin(UUID tenantId, UUID customerId, UUID cardId, String plainPin) {
        return inTransaction(() -> {
            Card card = ownedCard(tenantId, customerId, cardId);
            repository.updatePin(tenantId, card.id(), CardPin.of(plainPin), 0);
            return repository.findById(tenantId, card.id()).orElseThrow(() -> new CardNotFoundException(cardId));
        });
    }

    /**
     * Reveals the number to a cardholder who proves the PIN.
     *
     * <p>Four digits is ten thousand possibilities: a caller who may guess
     * without limit will succeed. Every failure is counted and the card locks at
     * {@value #MAX_PIN_ATTEMPTS}, and the count is persisted rather than held in
     * memory so restarting the service does not hand an attacker a fresh budget.
     */
    public CardNumber revealNumber(UUID tenantId, UUID customerId, UUID cardId, String plainPin) {
        Card card = inTransaction(() -> ownedCard(tenantId, customerId, cardId));

        // The durable budget on the card is the authoritative control, but five
        // attempts is no defence if they can all be spent in a burst across
        // replicas before any row is written. This window is what makes the
        // budget cost an attacker time.
        if (!rateLimiter.tryAcquire("pin:" + card.id(), PIN_ATTEMPTS_PER_WINDOW, PIN_THROTTLE_WINDOW)) {
            metrics.pinVerification("throttled");
            throw new CardPinException(CardPinException.Reason.THROTTLED,
                    Math.max(MAX_PIN_ATTEMPTS - card.pinAttempts(), 0));
        }

        if (!card.hasPin()) {
            metrics.pinVerification("not_set");
            throw new CardPinException(CardPinException.Reason.NOT_SET, MAX_PIN_ATTEMPTS);
        }
        if (card.pinAttempts() >= MAX_PIN_ATTEMPTS) {
            metrics.pinVerification("locked");
            throw new CardPinException(CardPinException.Reason.LOCKED, 0);
        }

        if (!card.pin().matches(plainPin)) {
            int attempts = card.pinAttempts() + 1;
            // Committed on its own, before the failure is raised. Counting the
            // attempt inside the transaction that then throws would roll the
            // increment back with it, handing every attacker an unlimited budget.
            recordAttempts(tenantId, card, attempts);
            metrics.pinVerification(attempts >= MAX_PIN_ATTEMPTS ? "locked" : "incorrect");
            throw new CardPinException(
                    attempts >= MAX_PIN_ATTEMPTS ? CardPinException.Reason.LOCKED
                            : CardPinException.Reason.INCORRECT,
                    MAX_PIN_ATTEMPTS - attempts);
        }

        // A correct PIN clears the budget, so isolated mistakes never accumulate
        // into a lock for a cardholder who does know it.
        recordAttempts(tenantId, card, 0);
        metrics.pinVerification("accepted");
        return card.number();
    }

    private void recordAttempts(UUID tenantId, Card card, int attempts) {
        inTransaction(() -> {
            repository.updatePin(tenantId, card.id(), card.pin(), attempts);
            return null;
        });
    }

    /** A card that is not the caller's own is reported as absent rather than forbidden. */
    private Card ownedCard(UUID tenantId, UUID customerId, UUID cardId) {
        Card card = repository.findById(tenantId, cardId).orElseThrow(() -> new CardNotFoundException(cardId));
        if (!card.customerId().equals(customerId)) {
            throw new CardNotFoundException(cardId);
        }
        return card;
    }

    public Card get(UUID tenantId, UUID id) {
        return repository.findById(tenantId, id).orElseThrow(() -> new CardNotFoundException(id));
    }

    public List<Card> listForCustomer(UUID tenantId, UUID customerId) {
        return repository.findByCustomer(tenantId, customerId);
    }

    private Optional<Card> findByKey(CreateCardCommand command) {
        return repository.findByIdempotencyKey(command.tenantId(), command.idempotencyKey());
    }

    /**
     * Inserts the card, drawing a new number if the generated one is taken.
     *
     * <p>Card numbers are now unique in the database, and generation is random:
     * unlikely to collide is not the same as cannot. A collision is a fact about
     * the draw and not about the request, so the right answer is another draw
     * rather than an error the caller can do nothing with. The retry is bounded
     * because an unbounded one would turn an exhausted or misbehaving number
     * space into a hang instead of a failure.
     */
    private Card insert(CreateCardCommand command) {
        for (int attempt = 1; ; attempt++) {
            try {
                return repository.save(new Card(
                        UUID.randomUUID(),
                        command.tenantId(),
                        command.customerId(),
                        command.creditLimit(),
                        "BRL",
                        CardStatus.ACTIVE,
                        command.product(),
                        CardNumber.generate(),
                        null,
                        0,
                        clock.instant()), command.idempotencyKey());
            } catch (RuntimeException failure) {
                if (attempt >= NUMBER_COLLISION_ATTEMPTS || !violates(failure, UQ_CARDS_NUMBER)) {
                    throw failure;
                }
                metrics.cardNumberCollision();
            }
        }
    }

    /**
     * Replaying a key is only idempotent for the same request. Reusing it for a
     * different card is a client defect and is refused rather than silently
     * answered with someone else's card.
     */
    private static Card sameRequestOrConflict(Card existing, CreateCardCommand command) {
        if (!existing.customerId().equals(command.customerId())
                || existing.product() != command.product()
                || existing.creditLimit().compareTo(command.creditLimit()) != 0) {
            throw new IdempotencyConflictException();
        }
        return existing;
    }

    /** Each attempt commits on its own so a failed insert does not poison the retry. */
    private static <T> T inTransaction(java.util.function.Supplier<T> work) {
        return QuarkusTransaction.requiringNew().call(work::get);
    }

    private static boolean isDuplicateKey(Throwable failure) {
        // Named rather than any integrity violation. Two unique constraints now
        // guard this table, and reading a card-number collision as a replayed
        // idempotency key would send the caller looking for a card that was
        // never written.
        return violates(failure, UQ_CARDS_TENANT_IDEMPOTENCY);
    }

    /**
     * Whether the failure is the named unique constraint being violated.
     *
     * <p>The name is matched against the message because that is where both
     * Hibernate and the driver put it. Where the name cannot be recovered — H2
     * and PostgreSQL word these differently, and a wrapper may drop the message
     * entirely — an integrity violation is attributed to the constraint the
     * caller asked about only if it is the sole one that could have produced it.
     */
    private static boolean violates(Throwable failure, String constraint) {
        boolean integrityViolation = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.toLowerCase(java.util.Locale.ROOT).contains(constraint)) {
                return true;
            }
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation) {
                if (violation.getConstraintName() != null) {
                    return constraint.equalsIgnoreCase(violation.getConstraintName());
                }
                integrityViolation = true;
            }
            if (cause instanceof SQLException sql && sql.getSQLState() != null
                    && sql.getSQLState().startsWith(INTEGRITY_VIOLATION)) {
                integrityViolation = true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        // An unattributable integrity violation is treated as the idempotency
        // key, which is the overwhelmingly likely cause and the one with a safe
        // recovery: the caller is handed the winning card. Attributing it to a
        // number collision instead would retry an insert that will fail again.
        return integrityViolation && UQ_CARDS_TENANT_IDEMPOTENCY.equals(constraint);
    }
}
