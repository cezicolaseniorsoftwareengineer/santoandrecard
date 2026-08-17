package com.cezicola.card.application;

import com.cezicola.card.application.port.CardRepository;
import com.cezicola.card.domain.Card;
import com.cezicola.card.domain.CardStatus;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class CardService {
    private static final SecureRandom RANDOM = new SecureRandom();

    /** SQL state class for an integrity constraint violation. */
    private static final String INTEGRITY_VIOLATION = "23";

    private final CardRepository repository;
    private final Clock clock;

    @Inject
    public CardService(CardRepository repository) {
        this(repository, Clock.systemUTC());
    }

    CardService(CardRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
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

    public Card get(UUID tenantId, UUID id) {
        return repository.findById(tenantId, id).orElseThrow(() -> new CardNotFoundException(id));
    }

    public List<Card> listForCustomer(UUID tenantId, UUID customerId) {
        return repository.findByCustomer(tenantId, customerId);
    }

    private Optional<Card> findByKey(CreateCardCommand command) {
        return repository.findByIdempotencyKey(command.tenantId(), command.idempotencyKey());
    }

    private Card insert(CreateCardCommand command) {
        return repository.save(new Card(
                UUID.randomUUID(),
                command.tenantId(),
                command.customerId(),
                command.creditLimit(),
                "BRL",
                CardStatus.ACTIVE,
                "%04d".formatted(RANDOM.nextInt(10_000)),
                clock.instant()), command.idempotencyKey());
    }

    /**
     * Replaying a key is only idempotent for the same request. Reusing it for a
     * different card is a client defect and is refused rather than silently
     * answered with someone else's card.
     */
    private static Card sameRequestOrConflict(Card existing, CreateCardCommand command) {
        if (!existing.customerId().equals(command.customerId())
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
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException) {
                return true;
            }
            if (cause instanceof SQLException sql && sql.getSQLState() != null
                    && sql.getSQLState().startsWith(INTEGRITY_VIOLATION)) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }
}
