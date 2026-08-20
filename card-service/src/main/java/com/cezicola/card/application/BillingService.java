package com.cezicola.card.application;

import com.cezicola.card.adapter.out.persistence.PurchaseEntity;
import com.cezicola.card.adapter.out.persistence.StatementEntity;
import com.cezicola.card.adapter.out.persistence.StatementItemEntity;
import com.cezicola.card.adapter.out.persistence.StatementPaymentEntity;
import com.cezicola.card.adapter.out.persistence.WalletEntity;
import com.cezicola.card.domain.BillingCycle;
import com.cezicola.card.domain.JournalEntry;
import com.cezicola.card.domain.LedgerAccount;
import com.cezicola.card.domain.Statement;
import com.cezicola.card.domain.StatementStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The billing cycle: what a customer owes, and what settles it.
 *
 * <p>A credit purchase is not paid for when it happens. It creates a receivable
 * that waits for the cycle it fell in to be billed, and is settled only when the
 * customer pays that statement. Three things follow from that, and each is
 * enforced rather than assumed: an item is billed exactly once, a payment never
 * exceeds the balance, and money that reduces a claim also moves in the ledger.
 */
@ApplicationScoped
public class BillingService {

    private final EntityManager entityManager;
    private final LedgerService ledger;
    private final OutboxRecorder outbox;
    private final Clock clock;

    @jakarta.inject.Inject
    public BillingService(EntityManager entityManager, LedgerService ledger, OutboxRecorder outbox) {
        this(entityManager, ledger, outbox, Clock.systemUTC());
    }

    BillingService(EntityManager entityManager, LedgerService ledger, OutboxRecorder outbox, Clock clock) {
        this.entityManager = entityManager;
        this.ledger = ledger;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * Bills a cycle for one customer.
     *
     * <p>Gathers the credit purchases that fell in the cycle and have not been
     * billed, writes them as items and fixes the total. Running it twice is safe:
     * the second run finds the statement already closed and returns it unchanged,
     * and even if it did not, the unique constraint on the item source would
     * refuse to bill a purchase a second time.
     *
     * <p>Closing an empty cycle is deliberate. A period with no record is a
     * period nobody can answer for, even when the answer is that nothing
     * happened.
     */
    @Transactional
    public StatementView close(UUID tenantId, UUID customerId, BillingCycle cycle) {
        StatementEntity existing = findStatement(tenantId, customerId, cycle, LockModeType.PESSIMISTIC_WRITE);
        if (existing != null && existing.status != StatementStatus.OPEN) {
            // Already billed. Returning it rather than refusing keeps a retried
            // close from looking like a failure to a caller that lost a response.
            return StatementView.from(existing);
        }

        Instant now = clock.instant();
        StatementEntity entity = existing != null ? existing : persistOpen(tenantId, customerId, cycle, now);
        Statement statement = toDomain(entity);

        List<PurchaseEntity> billable = unbilledCreditPurchases(tenantId, customerId, cycle);
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (PurchaseEntity purchase : billable) {
            persistItem(entity.id, tenantId, purchase);
            total = total.add(purchase.total);
        }

        statement.close(total, now);
        apply(entity, statement);

        outbox.record(tenantId, customerId, "statement.closed",
                json("statementId", entity.id, "customerId", customerId, "cycle", cycle.reference(),
                        "total", total, "dueDate", statement.dueDate(), "items", billable.size()));

        return StatementView.from(entity);
    }

    /**
     * Pays a statement from the customer's wallet.
     *
     * <p>The wallet is debited and the receivable credited in the same
     * transaction as the statement update, so a claim is never reduced without
     * the money moving. The statement row is locked first: two payments arriving
     * together would otherwise both read the same balance and both be allowed.
     */
    @Transactional
    public StatementView pay(UUID tenantId, UUID customerId, UUID statementId, BigDecimal amount) {
        StatementEntity entity = entityManager.find(StatementEntity.class, statementId, LockModeType.PESSIMISTIC_WRITE);
        if (entity == null || !entity.tenantId.equals(tenantId) || !entity.customerId.equals(customerId)) {
            // Same answer for absent and for belonging to somebody else: telling
            // a caller that a statement exists but is not theirs is itself a leak.
            throw new StatementNotFoundException(statementId);
        }

        Statement statement = toDomain(entity);
        BigDecimal applied = statement.pay(amount, today());

        // The wallet row is the projection a purchase locks, so it is what a
        // payment has to move too. Debiting the ledger and leaving this behind
        // is what the reconciliation check exists to catch, and it caught it.
        WalletEntity wallet = entityManager.find(WalletEntity.class,
                WalletEntity.key(tenantId, customerId), LockModeType.PESSIMISTIC_WRITE);
        if (wallet == null || wallet.balance.compareTo(applied) < 0) {
            throw InsufficientFundsException.wallet();
        }
        wallet.balance = wallet.balance.subtract(applied);

        Instant now = clock.instant();
        UUID transactionId = ledger.record(tenantId, new JournalEntry(
                JournalEntry.Kind.STATEMENT_PAYMENT,
                "Pagamento da fatura " + entity.cycle,
                entity.id,
                List.of(
                        JournalEntry.Posting.debit(LedgerAccount.CUSTOMER_WALLET, customerId, applied),
                        JournalEntry.Posting.credit(LedgerAccount.CUSTOMER_RECEIVABLE, customerId, applied))));

        StatementPaymentEntity payment = new StatementPaymentEntity();
        payment.id = UUID.randomUUID();
        payment.statementId = entity.id;
        payment.tenantId = tenantId;
        payment.amount = applied;
        payment.paidAt = now;
        payment.ledgerTransactionId = transactionId;
        entityManager.persist(payment);

        apply(entity, statement);

        outbox.record(tenantId, customerId, "statement.paid",
                json("statementId", entity.id, "customerId", customerId, "cycle", entity.cycle,
                        "amount", applied, "balance", statement.balance(), "status", statement.status()));

        return StatementView.from(entity);
    }

    /**
     * Marks statements overdue once their due date has passed with a balance.
     *
     * <p>Bounded, because a sweep that grows with history eventually holds a
     * transaction open longer than a payment can wait for it.
     */
    @Transactional
    public int markOverdue(int limit) {
        LocalDate today = today();
        List<StatementEntity> due = entityManager.createQuery("""
                        select s from StatementEntity s
                        where s.status in (com.cezicola.card.domain.StatementStatus.CLOSED,
                                           com.cezicola.card.domain.StatementStatus.PARTIALLY_PAID)
                          and s.dueDate < :today
                        order by s.dueDate asc
                        """, StatementEntity.class)
                .setParameter("today", today)
                .setMaxResults(Math.max(limit, 1))
                .getResultList();

        int marked = 0;
        for (StatementEntity entity : due) {
            Statement statement = toDomain(entity);
            if (statement.markOverdueIfDue(today)) {
                apply(entity, statement);
                outbox.record(entity.tenantId, entity.customerId, "statement.overdue",
                        json("statementId", entity.id, "customerId", entity.customerId,
                                "cycle", entity.cycle, "balance", statement.balance(),
                                "dueDate", statement.dueDate()));
                marked++;
            }
        }
        return marked;
    }

    /**
     * Credit still available on the card.
     *
     * <p>Computed from the ledger rather than stored, per the architecture's
     * fifth invariant: a limit kept as a number somebody has to remember to
     * update is a number that eventually disagrees with the postings.
     */
    public BigDecimal availableCredit(UUID tenantId, UUID customerId, BigDecimal creditLimit) {
        BigDecimal owed = ledger.balanceOf(tenantId, LedgerAccount.CUSTOMER_RECEIVABLE, customerId);
        BigDecimal available = creditLimit.subtract(owed);
        return available.signum() < 0 ? BigDecimal.ZERO.setScale(2) : available;
    }

    /** Statements of one customer, most recent cycle first. */
    public List<StatementView> statements(UUID tenantId, UUID customerId, int limit) {
        return entityManager.createQuery("""
                        select s from StatementEntity s
                        where s.tenantId = :tenantId and s.customerId = :customerId
                        order by s.cycle desc
                        """, StatementEntity.class)
                .setParameter("tenantId", tenantId)
                .setParameter("customerId", customerId)
                .setMaxResults(Math.min(Math.max(limit, 1), 120))
                .getResultList().stream()
                .map(StatementView::from)
                .toList();
    }

    /** The lines that made up a statement. */
    public List<ItemView> items(UUID tenantId, UUID statementId) {
        return entityManager.createQuery("""
                        select i from StatementItemEntity i
                        where i.tenantId = :tenantId and i.statementId = :statementId
                        order by i.occurredAt asc
                        """, StatementItemEntity.class)
                .setParameter("tenantId", tenantId)
                .setParameter("statementId", statementId)
                .getResultList().stream()
                .map(i -> new ItemView(i.id, i.sourceType, i.sourceId, i.description, i.amount, i.occurredAt))
                .toList();
    }

    /**
     * The credit purchases of a cycle that no statement has taken yet.
     *
     * <p>The absence of an item is what makes a purchase billable, so a purchase
     * billed by an earlier run is invisible here however many times closure is
     * retried.
     */
    private List<PurchaseEntity> unbilledCreditPurchases(UUID tenantId, UUID customerId, BillingCycle cycle) {
        return entityManager.createQuery("""
                        select p from PurchaseEntity p
                        where p.tenantId = :tenantId
                          and p.customerId = :customerId
                          and p.fundingSource = com.cezicola.card.domain.FundingSource.CREDIT
                          and p.createdAt >= :from and p.createdAt < :to
                          and not exists (
                              select 1 from StatementItemEntity i
                              where i.tenantId = p.tenantId
                                and i.sourceType = 'PURCHASE'
                                and i.sourceId = p.id)
                        order by p.createdAt asc
                        """, PurchaseEntity.class)
                .setParameter("tenantId", tenantId)
                .setParameter("customerId", customerId)
                .setParameter("from", cycle.start())
                .setParameter("to", cycle.end())
                .getResultList();
    }

    private StatementEntity persistOpen(UUID tenantId, UUID customerId, BillingCycle cycle, Instant now) {
        StatementEntity entity = new StatementEntity();
        entity.id = UUID.randomUUID();
        entity.tenantId = tenantId;
        entity.customerId = customerId;
        entity.cycle = cycle.reference();
        entity.status = StatementStatus.OPEN;
        entity.billedTotal = BigDecimal.ZERO.setScale(2);
        entity.paidTotal = BigDecimal.ZERO.setScale(2);
        entity.createdAt = now;
        entityManager.persist(entity);
        return entity;
    }

    private void persistItem(UUID statementId, UUID tenantId, PurchaseEntity purchase) {
        StatementItemEntity item = new StatementItemEntity();
        item.id = UUID.randomUUID();
        item.statementId = statementId;
        item.tenantId = tenantId;
        item.sourceType = "PURCHASE";
        item.sourceId = purchase.id;
        item.description = purchase.merchantCategory
                + (purchase.installments > 1 ? " (" + purchase.installments + "x)" : "");
        item.amount = purchase.total;
        item.occurredAt = purchase.createdAt;
        entityManager.persist(item);
    }

    private StatementEntity findStatement(UUID tenantId, UUID customerId, BillingCycle cycle, LockModeType lock) {
        return entityManager.createQuery("""
                        select s from StatementEntity s
                        where s.tenantId = :tenantId and s.customerId = :customerId and s.cycle = :cycle
                        """, StatementEntity.class)
                .setParameter("tenantId", tenantId)
                .setParameter("customerId", customerId)
                .setParameter("cycle", cycle.reference())
                .setLockMode(lock)
                .getResultStream().findFirst().orElse(null);
    }

    private Statement toDomain(StatementEntity entity) {
        return new Statement(entity.id, entity.tenantId, entity.customerId,
                BillingCycle.parse(entity.cycle), entity.status,
                entity.billedTotal, entity.paidTotal, entity.dueDate, entity.closedAt);
    }

    /** Copies the decided state back. The domain decides; the entity records. */
    private void apply(StatementEntity entity, Statement statement) {
        entity.status = statement.status();
        entity.billedTotal = statement.billedTotal();
        entity.paidTotal = statement.paidTotal();
        entity.dueDate = statement.dueDate();
        entity.closedAt = statement.closedAt();
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), BillingCycle.ZONE);
    }

    private static String json(Object... pairs) {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(pairs[i]).append("\":");
            Object value = pairs[i + 1];
            if (value instanceof Number) {
                json.append(value);
            } else {
                json.append('"').append(value).append('"');
            }
        }
        return json.append('}').toString();
    }

    public record StatementView(UUID id, UUID customerId, String cycle, StatementStatus status,
                                BigDecimal billedTotal, BigDecimal paidTotal, BigDecimal balance,
                                LocalDate dueDate, Instant closedAt) {
        static StatementView from(StatementEntity entity) {
            return new StatementView(entity.id, entity.customerId, entity.cycle, entity.status,
                    entity.billedTotal, entity.paidTotal,
                    entity.billedTotal.subtract(entity.paidTotal), entity.dueDate, entity.closedAt);
        }
    }

    public record ItemView(UUID id, String sourceType, UUID sourceId, String description,
                           BigDecimal amount, Instant occurredAt) {}

    /** A statement that does not exist, or does not belong to the caller. */
    public static class StatementNotFoundException extends RuntimeException {
        public StatementNotFoundException(UUID id) {
            super("statement " + id + " was not found");
        }
    }
}
