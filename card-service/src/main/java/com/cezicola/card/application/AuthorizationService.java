package com.cezicola.card.application;

import com.cezicola.card.adapter.out.metrics.FinancialMetrics;
import com.cezicola.card.adapter.out.persistence.WalletEntity;
import com.cezicola.card.application.port.AuthorizationRepository;
import com.cezicola.card.application.port.MerchantAuthorizationPort;
import com.cezicola.card.domain.Authorization;
import com.cezicola.card.domain.AuthorizationStateException;
import com.cezicola.card.domain.JournalEntry;
import com.cezicola.card.domain.LedgerAccount;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Authorization, capture and reversal.
 *
 * <p>This is the difference between a wallet and a card. A wallet debits when
 * you buy; a card holds the funds and waits, because the merchant does not yet
 * know what it will actually charge — an order ships in part, a booking is
 * cancelled, a reservation is never claimed.
 *
 * <p>The hold is a movement between two obligations rather than a flag on the
 * wallet: funds leave {@code CUSTOMER_WALLET} and enter {@code CUSTOMER_HELD},
 * both owed to the same customer. What the issuer owes them is therefore
 * unchanged by an authorization and by its reversal, and the ledger can prove it.
 */
@ApplicationScoped
public class AuthorizationService {

    private final EntityManager entityManager;
    private final AuthorizationRepository repository;
    private final MerchantAuthorizationPort merchantAuthorization;
    private final LedgerService ledger;
    private final OutboxRecorder outbox;
    private final FinancialMetrics metrics;
    private final Clock clock;
    private final Duration holdFor;

    @jakarta.inject.Inject
    public AuthorizationService(EntityManager entityManager,
                                AuthorizationRepository repository,
                                MerchantAuthorizationPort merchantAuthorization,
                                LedgerService ledger,
                                OutboxRecorder outbox,
                                FinancialMetrics metrics,
                                @ConfigProperty(name = "card.authorization.hold-duration") Duration holdFor) {
        this(entityManager, repository, merchantAuthorization, ledger, outbox, metrics, Clock.systemUTC(), holdFor);
    }

    AuthorizationService(EntityManager entityManager, AuthorizationRepository repository,
                         MerchantAuthorizationPort merchantAuthorization, LedgerService ledger,
                         OutboxRecorder outbox, FinancialMetrics metrics, Clock clock, Duration holdFor) {
        this.entityManager = entityManager;
        this.repository = repository;
        this.merchantAuthorization = merchantAuthorization;
        this.ledger = ledger;
        this.outbox = outbox;
        this.metrics = metrics;
        this.clock = clock;
        this.holdFor = holdFor;
    }

    /**
     * Places a hold. The merchant network is consulted first and fails closed: an
     * answer that does not arrive is not an approval.
     */
    @Transactional
    public Authorization authorize(UUID tenantId, UUID customerId, UUID cardId,
                                   String merchantCategory, BigDecimal amount) {
        var decision = merchantAuthorization.authorize(tenantId, customerId, merchantCategory, amount);
        if (decision != MerchantAuthorizationPort.AuthorizationDecision.APPROVED) {
            metrics.authorizationDecided("network_unavailable");
            metrics.refused("authorization", "network_unavailable");
            throw new MerchantAuthorizationUnavailableException();
        }

        WalletEntity wallet = lockedWallet(tenantId, customerId);
        if (wallet == null || wallet.balance.compareTo(amount) < 0) {
            metrics.authorizationDecided("insufficient_funds");
            metrics.refused("authorization", "insufficient_funds");
            throw InsufficientFundsException.wallet();
        }
        // The projection tracks spendable funds, so held money leaves it. The
        // ledger keeps both sides, which is how the two stay reconcilable.
        wallet.balance = wallet.balance.subtract(amount);

        Authorization authorization = repository.save(Authorization.approve(
                tenantId, customerId, cardId, merchantCategory, amount, clock.instant(), holdFor));

        ledger.record(tenantId, new JournalEntry(
                JournalEntry.Kind.AUTHORIZATION_HOLD,
                "Bloqueio para " + merchantCategory,
                authorization.id(),
                List.of(
                        JournalEntry.Posting.debit(LedgerAccount.CUSTOMER_WALLET, customerId, amount),
                        JournalEntry.Posting.credit(LedgerAccount.CUSTOMER_HELD, customerId, amount))));

        outbox.record(tenantId, customerId, "authorization.approved",
                json("authorizationId", authorization.id(), "customerId", customerId,
                        "merchantCategory", merchantCategory, "amount", amount,
                        "expiresAt", authorization.expiresAt()));

        metrics.authorizationDecided("approved");
        metrics.authorizationHeld(amount);
        return authorization;
    }

    /**
     * Captures up to the amount held. Whatever was held and not captured returns
     * to the wallet in the same entry, so a partial capture never leaves funds
     * stranded in the held account.
     */
    @Transactional
    public Authorization capture(UUID tenantId, UUID id, BigDecimal requested) {
        Authorization held = require(tenantId, id);
        Authorization captured = held.capture(requested, clock.instant());

        List<JournalEntry.Posting> postings = new ArrayList<>();
        postings.add(JournalEntry.Posting.debit(LedgerAccount.CUSTOMER_HELD, held.customerId(), held.amount()));
        postings.add(JournalEntry.Posting.credit(LedgerAccount.MERCHANT_PAYABLE, null, requested));

        BigDecimal released = captured.releasedAmount();
        if (released.signum() > 0) {
            WalletEntity wallet = lockedWallet(tenantId, held.customerId());
            wallet.balance = wallet.balance.add(released);
            postings.add(JournalEntry.Posting.credit(
                    LedgerAccount.CUSTOMER_WALLET, held.customerId(), released));
        }

        ledger.record(tenantId, new JournalEntry(
                JournalEntry.Kind.AUTHORIZATION_CAPTURE,
                "Captura de " + held.merchantCategory(), held.id(), postings));

        Authorization stored = repository.save(captured);
        outbox.record(tenantId, held.customerId(), "authorization.captured",
                json("authorizationId", held.id(), "customerId", held.customerId(),
                        "captured", requested, "released", released));

        metrics.authorizationSettled("captured", requested, released);
        return stored;
    }

    /** Releases the whole hold. Nothing was charged, so nothing is owed to the merchant. */
    @Transactional
    public Authorization reverse(UUID tenantId, UUID id) {
        Authorization held = require(tenantId, id);
        return release(held, held.reverse(clock.instant()),
                JournalEntry.Kind.AUTHORIZATION_REVERSAL, "authorization.reversed", "Estorno de bloqueio");
    }

    /**
     * Releases holds nobody captured.
     *
     * <p>Each is settled in its own transaction: one hold that cannot be released
     * must not keep the rest of the backlog held. A capture that wins the race
     * against the sweep is the correct outcome, not a failure.
     */
    public int expireHolds(int batchSize) {
        List<UUID> expired = QuarkusTransaction.requiringNew()
                .call(() -> repository.findExpired(clock.instant(), batchSize));

        int released = 0;
        for (UUID id : expired) {
            try {
                QuarkusTransaction.requiringNew().run(() -> expireOne(id));
                released++;
            } catch (RuntimeException alreadySettled) {
                // Captured or reversed between the read and the settlement.
            }
        }
        return released;
    }

    /**
     * Settles one expired hold. Public so a test can drive expiry directly and
     * assert what it does rather than wait for a scheduler tick.
     */
    public void expireOne(UUID id) {
        Authorization held = entityManager
                .createQuery("select a.tenantId from AuthorizationEntity a where a.id = :id", UUID.class)
                .setParameter("id", id).getResultStream().findFirst()
                .flatMap(tenantId -> repository.findById(tenantId, id))
                .orElseThrow(() -> new AuthorizationStateException("authorization " + id + " is gone"));

        release(held, held.expire(clock.instant()),
                JournalEntry.Kind.AUTHORIZATION_EXPIRY, "authorization.expired", "Expiracao de bloqueio");
    }

    private Authorization release(Authorization held, Authorization settled,
                                  JournalEntry.Kind kind, String eventType, String description) {
        WalletEntity wallet = lockedWallet(held.tenantId(), held.customerId());
        wallet.balance = wallet.balance.add(held.amount());

        ledger.record(held.tenantId(), new JournalEntry(kind, description, held.id(),
                List.of(
                        JournalEntry.Posting.debit(LedgerAccount.CUSTOMER_HELD, held.customerId(), held.amount()),
                        JournalEntry.Posting.credit(LedgerAccount.CUSTOMER_WALLET, held.customerId(), held.amount()))));

        Authorization stored = repository.save(settled);
        outbox.record(held.tenantId(), held.customerId(), eventType,
                json("authorizationId", held.id(), "customerId", held.customerId(),
                        "released", held.amount()));

        metrics.authorizationSettled(settled.status().name().toLowerCase(java.util.Locale.ROOT),
                java.math.BigDecimal.ZERO, held.amount());
        return stored;
    }

    public List<Authorization> listForCustomer(UUID tenantId, UUID customerId, int limit) {
        return repository.findByCustomer(tenantId, customerId, limit);
    }

    public Authorization get(UUID tenantId, UUID id) {
        return require(tenantId, id);
    }

    private Authorization require(UUID tenantId, UUID id) {
        return repository.findById(tenantId, id).orElseThrow(() -> new AuthorizationNotFoundException(id));
    }

    private WalletEntity lockedWallet(UUID tenantId, UUID customerId) {
        return entityManager.find(WalletEntity.class, WalletEntity.key(tenantId, customerId),
                LockModeType.PESSIMISTIC_WRITE);
    }

    /**
     * Serialised by hand for the same reason the other events are: an event is a
     * record of the past, and binding it to a class lets a future rename rewrite
     * the shape of events already published.
     */
    private static String json(Object... keysAndValues) {
        StringBuilder payload = new StringBuilder("{");
        for (int i = 0; i < keysAndValues.length; i += 2) {
            if (i > 0) {
                payload.append(',');
            }
            payload.append('"').append(keysAndValues[i]).append("\":");
            Object value = keysAndValues[i + 1];
            if (value instanceof BigDecimal || value instanceof Integer) {
                payload.append(value);
            } else {
                payload.append('"').append(value).append('"');
            }
        }
        return payload.append('}').toString();
    }
}
