package com.cezicola.card.application;

import com.cezicola.card.adapter.out.persistence.LedgerPostingEntity;
import com.cezicola.card.adapter.out.persistence.LedgerTransactionEntity;
import com.cezicola.card.domain.JournalEntry;
import com.cezicola.card.domain.LedgerAccount;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The book of record.
 *
 * <p>Postings are written once and never changed. Balances are read from those
 * postings, so the ledger is the truth and any cached figure elsewhere is a
 * projection that has to prove it still agrees — see {@link #reconcileWallets}.
 */
@ApplicationScoped
public class LedgerService {
    private final EntityManager entityManager;
    private final Clock clock;

    @jakarta.inject.Inject
    public LedgerService(EntityManager entityManager) {
        this(entityManager, Clock.systemUTC());
    }

    LedgerService(EntityManager entityManager, Clock clock) {
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /**
     * Records a balanced entry. Runs inside the caller's transaction on purpose:
     * the postings and the operation that caused them must commit together, or a
     * purchase could succeed with no trace in the book.
     */
    public UUID record(UUID tenantId, JournalEntry entry) {
        Instant now = clock.instant();
        LedgerTransactionEntity transaction = new LedgerTransactionEntity();
        transaction.id = UUID.randomUUID();
        transaction.tenantId = tenantId;
        transaction.kind = entry.kind();
        transaction.description = entry.description();
        transaction.referenceId = entry.referenceId();
        transaction.occurredAt = now;
        entityManager.persist(transaction);

        for (JournalEntry.Posting posting : entry.postings()) {
            LedgerPostingEntity line = new LedgerPostingEntity();
            line.id = UUID.randomUUID();
            line.transactionId = transaction.id;
            line.tenantId = tenantId;
            line.accountCode = posting.account();
            line.customerId = posting.customerId();
            line.direction = posting.side();
            line.amount = posting.amount();
            line.occurredAt = now;
            entityManager.persist(line);
        }
        return transaction.id;
    }

    /** Balance of a customer wallet as the ledger sees it. */
    public BigDecimal walletBalance(UUID tenantId, UUID customerId) {
        Object[] sides = (Object[]) entityManager.createQuery("""
                        select
                          coalesce(sum(case when p.direction = com.cezicola.card.domain.LedgerAccount$Side.CREDIT
                                            then p.amount else 0 end), 0),
                          coalesce(sum(case when p.direction = com.cezicola.card.domain.LedgerAccount$Side.DEBIT
                                            then p.amount else 0 end), 0)
                        from LedgerPostingEntity p
                        where p.tenantId = :tenantId
                          and p.accountCode = :account
                          and p.customerId = :customerId
                        """)
                .setParameter("tenantId", tenantId)
                .setParameter("account", LedgerAccount.CUSTOMER_WALLET)
                .setParameter("customerId", customerId)
                .getSingleResult();
        // A wallet is a liability, so it grows on the credit side.
        return money(sides[0]).subtract(money(sides[1]));
    }

    /** Movements of a customer wallet, most recent first. */
    public List<WalletMovement> walletStatement(UUID tenantId, UUID customerId, int limit) {
        return entityManager.createQuery("""
                        select t.kind, t.description, p.direction, p.amount, p.occurredAt, t.referenceId
                        from LedgerPostingEntity p
                        join LedgerTransactionEntity t on t.id = p.transactionId
                        where p.tenantId = :tenantId
                          and p.accountCode = :account
                          and p.customerId = :customerId
                        order by p.occurredAt desc
                        """, Object[].class)
                .setParameter("tenantId", tenantId)
                .setParameter("account", LedgerAccount.CUSTOMER_WALLET)
                .setParameter("customerId", customerId)
                .setMaxResults(Math.min(Math.max(limit, 1), 200))
                .getResultList().stream()
                .map(row -> new WalletMovement(
                        (JournalEntry.Kind) row[0],
                        (String) row[1],
                        // Credit adds to a wallet, debit takes from it.
                        row[2] == LedgerAccount.Side.CREDIT ? money(row[3]) : money(row[3]).negate(),
                        (Instant) row[4],
                        (UUID) row[5]))
                .toList();
    }

    /**
     * Proves the book itself is coherent: every transaction must have equal debits
     * and credits. A non-empty result means money was recorded that cannot be
     * accounted for, which is a stop-the-world condition rather than a warning.
     */
    public List<UUID> unbalancedTransactions(UUID tenantId) {
        return entityManager.createQuery("""
                        select p.transactionId
                        from LedgerPostingEntity p
                        where p.tenantId = :tenantId
                        group by p.transactionId
                        having sum(case when p.direction = com.cezicola.card.domain.LedgerAccount$Side.DEBIT
                                        then p.amount else -p.amount end) <> 0
                        """, UUID.class)
                .setParameter("tenantId", tenantId)
                .getResultList();
    }

    /**
     * Compares every cached wallet balance with the ledger. The cached figure
     * exists so a purchase can lock one row instead of summing a growing table;
     * it is only legitimate while it still agrees with the book.
     */
    public List<WalletDiscrepancy> reconcileWallets(UUID tenantId) {
        List<Object[]> cached = entityManager.createQuery("""
                select w.customerId, w.balance from WalletEntity w where w.tenantId = :tenantId
                """, Object[].class).setParameter("tenantId", tenantId).getResultList();

        return cached.stream()
                .map(row -> {
                    UUID customerId = (UUID) row[0];
                    BigDecimal projection = money(row[1]);
                    BigDecimal book = walletBalance(tenantId, customerId);
                    return new WalletDiscrepancy(customerId, projection, book);
                })
                .filter(WalletDiscrepancy::diverges)
                .toList();
    }

    private static BigDecimal money(Object value) {
        return new BigDecimal(value.toString()).setScale(2);
    }

    public record WalletMovement(JournalEntry.Kind kind, String description, BigDecimal signedAmount,
                                 Instant occurredAt, UUID referenceId) {}

    public record WalletDiscrepancy(UUID customerId, BigDecimal cachedBalance, BigDecimal ledgerBalance) {
        public boolean diverges() {
            return cachedBalance.compareTo(ledgerBalance) != 0;
        }
    }
}
