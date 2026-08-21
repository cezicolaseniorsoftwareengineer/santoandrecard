package com.cezicola.card.application;

import com.cezicola.card.adapter.out.persistence.InterestPolicyEntity;
import com.cezicola.card.adapter.out.persistence.PurchaseEntity;
import com.cezicola.card.adapter.out.persistence.WalletEntity;
import com.cezicola.card.domain.InterestCalculator;
import com.cezicola.card.domain.FundingSource;
import com.cezicola.card.domain.JournalEntry;
import com.cezicola.card.domain.LedgerAccount;
import com.cezicola.card.domain.PurchasePlan;
import com.cezicola.card.adapter.out.metrics.FinancialMetrics;
import com.cezicola.card.application.port.MerchantAuthorizationPort;
import com.cezicola.card.application.port.SummaryCache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class FinanceService {
    private final EntityManager entityManager;
    private final InterestCalculator calculator;
    private final MerchantAuthorizationPort merchantAuthorization;
    private final PurchaseBackpressureGuard backpressure;
    private final LedgerService ledger;
    private final OutboxRecorder outbox;
    private final SummaryCache summaryCache;
    private final FinancialMetrics metrics;
    private final Clock clock = Clock.systemUTC();

    public FinanceService(EntityManager entityManager, InterestCalculator calculator,
                          MerchantAuthorizationPort merchantAuthorization,
                          PurchaseBackpressureGuard backpressure,
                          LedgerService ledger,
                          OutboxRecorder outbox,
                          SummaryCache summaryCache,
                          FinancialMetrics metrics) {
        this.entityManager = entityManager;
        this.calculator = calculator;
        this.merchantAuthorization = merchantAuthorization;
        this.backpressure = backpressure;
        this.ledger = ledger;
        this.outbox = outbox;
        this.summaryCache = summaryCache;
        this.metrics = metrics;
    }

    @Transactional
    public WalletView topUp(UUID tenantId, UUID customerId, BigDecimal amount) {
        requireMoney(amount);
        // Locking creates the wallet when it is the customer's first, so there is
        // no absent case to handle here and no unlocked window in which to handle it.
        WalletEntity wallet = lockedWallet(tenantId, customerId);
        wallet.balance = wallet.balance.add(amount);

        // Funds enter the platform and the issuer's debt to the customer grows by
        // the same amount. Recorded in the same transaction as the balance change,
        // so the two can never disagree.
        ledger.record(tenantId, new JournalEntry(
                JournalEntry.Kind.TOP_UP,
                "Adição de saldo em carteira",
                null,
                List.of(
                        JournalEntry.Posting.debit(LedgerAccount.FUNDING, null, amount),
                        JournalEntry.Posting.credit(LedgerAccount.CUSTOMER_WALLET, customerId, amount))));

        // Recorded here, inside the same transaction: the event and the money it
        // describes commit together or neither does.
        metrics.moneyMoved("top_up", amount);
        outbox.record(tenantId, customerId, "wallet.topped-up",
                json("customerId", customerId, "amount", amount, "balance", wallet.balance));

        return new WalletView(customerId, wallet.balance, cardBalance(tenantId, customerId));
    }

    /**
     * Moves money from the wallet onto the card.
     *
     * <p>Nothing is created here: the customer is owed the same amount before and
     * after, on a different account. That is what the pair of postings proves —
     * the wallet is debited and the card credited in one balanced entry, in the
     * same transaction as the wallet projection, so the book and the projection
     * cannot drift apart.
     */
    @Transactional
    public CardBalanceView loadCard(UUID tenantId, UUID customerId, BigDecimal amount) {
        requireMoney(amount);
        WalletEntity wallet = lockedWallet(tenantId, customerId);
        if (wallet.balance.compareTo(amount) < 0) {
            metrics.refused("card_load", "insufficient_funds");
            throw InsufficientFundsException.wallet();
        }
        wallet.balance = wallet.balance.subtract(amount);
        metrics.moneyMoved("card_load", amount);

        ledger.record(tenantId, new JournalEntry(
                JournalEntry.Kind.CARD_LOAD,
                "Transferência da carteira para o cartão",
                null,
                List.of(
                        JournalEntry.Posting.debit(LedgerAccount.CUSTOMER_WALLET, customerId, amount),
                        JournalEntry.Posting.credit(LedgerAccount.CARD_PREPAID, customerId, amount))));

        BigDecimal onCard = cardBalance(tenantId, customerId);
        outbox.record(tenantId, customerId, "card.loaded",
                json("customerId", customerId, "amount", amount, "cardBalance", onCard));

        return new CardBalanceView(customerId, wallet.balance, onCard);
    }

    /** Read from the postings, never from a stored figure: the book is the truth. */
    public BigDecimal cardBalance(UUID tenantId, UUID customerId) {
        return ledger.balanceOf(tenantId, LedgerAccount.CARD_PREPAID, customerId);
    }

    public PurchasePlan quote(UUID tenantId, BigDecimal principal, int installments) {
        return calculator.calculate(principal, installments, rateFor(tenantId));
    }

    @Transactional
    public PurchaseView purchase(UUID tenantId, UUID customerId, String merchantCategory,
                                 BigDecimal principal, int installments) {
        return purchase(tenantId, customerId, merchantCategory, principal, installments, FundingSource.CARD);
    }

    /**
     * A purchase on the funding source the caller chose.
     *
     * <p>The source is fixed here and never revisited: it decides which account
     * is debited, and changing it afterwards would rewrite postings that have
     * already been made. A change of mind is a reversal and a new purchase.
     */
    @Transactional
    public PurchaseView purchase(UUID tenantId, UUID customerId, String merchantCategory,
                                 BigDecimal principal, int installments, FundingSource fundingSource) {
        return backpressure.execute(
                () -> executePurchase(tenantId, customerId, merchantCategory, principal, installments, fundingSource));
    }

    /**
     * A single-message sale: authorised and settled in one step.
     *
     * <p>Card networks carry both shapes. This is the one a point of sale uses
     * when the amount is final at the moment of purchase, and it is why the
     * instalment plan can be fixed here. When the final amount is not yet known —
     * an order that ships in part, a booking that may be cancelled — the two-step
     * flow in {@link AuthorizationService} holds the funds first and settles
     * later.
     */
    private PurchaseView executePurchase(UUID tenantId, UUID customerId, String merchantCategory,
                                          BigDecimal principal, int installments,
                                          FundingSource fundingSource) {
        if (merchantCategory == null || merchantCategory.isBlank() || merchantCategory.length() > 64) {
            throw new IllegalArgumentException("merchantCategory must contain 1 to 64 characters");
        }
        PurchasePlan plan = quote(tenantId, principal, installments);
        var decision = merchantAuthorization.authorize(tenantId, customerId, merchantCategory, plan.total());
        if (decision != MerchantAuthorizationPort.AuthorizationDecision.APPROVED) {
            metrics.refused("purchase", "network_unavailable");
            throw new MerchantAuthorizationUnavailableException();
        }

        // The wallet row is locked as the per-customer mutex whichever source
        // pays: both the prepaid balance and the outstanding receivable are sums
        // over postings, so neither has a row of its own to lock, and without
        // serialising here two concurrent purchases could both read the same
        // figure and both pass. The row is created if the customer has none, so
        // the mutex covers a credit purchase from a customer who never funded a
        // wallet — the case that previously took no lock at all.
        lockedWallet(tenantId, customerId);
        if (fundingSource == FundingSource.CARD) {
            BigDecimal available = cardBalance(tenantId, customerId);
            if (available.compareTo(plan.total()) < 0) {
                metrics.refused("purchase", "insufficient_funds");
                throw InsufficientFundsException.card();
            }
        } else {
            BigDecimal limit = creditLimitOf(tenantId, customerId);
            BigDecimal owed = ledger.balanceOf(tenantId, LedgerAccount.CUSTOMER_RECEIVABLE, customerId);
            if (limit.subtract(owed).compareTo(plan.total()) < 0) {
                metrics.refused("purchase", "credit_limit_exceeded");
                throw InsufficientFundsException.creditLimit();
            }
        }
        metrics.moneyMoved("purchase", plan.total());
        PurchaseEntity purchase = new PurchaseEntity();
        purchase.id = UUID.randomUUID();
        purchase.tenantId = tenantId;
        purchase.customerId = customerId;
        purchase.merchantCategory = merchantCategory.trim();
        purchase.principal = plan.principal();
        purchase.interest = plan.interest();
        purchase.total = plan.total();
        purchase.installments = plan.installments();
        purchase.installmentAmount = plan.installmentAmount();
        purchase.lastInstallmentAmount = plan.lastInstallmentAmount();
        purchase.monthlyRate = plan.monthlyRate();
        purchase.createdAt = clock.instant();
        purchase.fundingSource = fundingSource;
        entityManager.persist(purchase);

        // The card pays the full amount; the principal becomes a debt to the
        // merchant and the interest becomes revenue. Splitting the credit side is
        // what makes interest visible in the book instead of being buried in a
        // single net movement.
        var postings = new java.util.ArrayList<JournalEntry.Posting>();
        // Prepaid spends money the customer already handed over; credit creates a
        // debt the issuer will collect. The account debited is the whole
        // difference between the two products.
        postings.add(fundingSource == FundingSource.CARD
                ? JournalEntry.Posting.debit(LedgerAccount.CARD_PREPAID, customerId, plan.total())
                : JournalEntry.Posting.debit(LedgerAccount.CUSTOMER_RECEIVABLE, customerId, plan.total()));
        postings.add(JournalEntry.Posting.credit(LedgerAccount.MERCHANT_PAYABLE, null, plan.principal()));
        if (plan.interest().signum() > 0) {
            postings.add(JournalEntry.Posting.credit(LedgerAccount.INTEREST_REVENUE, null, plan.interest()));
        }
        ledger.record(tenantId, new JournalEntry(
                fundingSource == FundingSource.CARD
                        ? JournalEntry.Kind.PURCHASE
                        : JournalEntry.Kind.CREDIT_PURCHASE,
                "Compra em " + purchase.merchantCategory, purchase.id, postings));

        outbox.record(tenantId, customerId, "purchase.authorised",
                json("purchaseId", purchase.id, "customerId", customerId,
                        "merchantCategory", purchase.merchantCategory, "principal", plan.principal(),
                        "interest", plan.interest(), "total", plan.total(),
                        "installments", plan.installments(),
                        "monthlyRate", plan.monthlyRate()));

        return PurchaseView.from(purchase, cardBalance(tenantId, customerId));
    }

    @Transactional
    public InterestPolicyView setInterestPolicy(UUID tenantId, BigDecimal monthlyRate) {
        InterestCalculator.requireRate(monthlyRate);
        if (monthlyRate.scale() > 6) {
            throw new IllegalArgumentException("monthlyRate must carry at most 6 decimals");
        }
        InterestPolicyEntity policy = entityManager.find(InterestPolicyEntity.class, tenantId);
        if (policy == null) {
            policy = new InterestPolicyEntity();
            policy.tenantId = tenantId;
            policy.monthlyRate = monthlyRate;
            policy.updatedAt = clock.instant();
            entityManager.persist(policy);
            return new InterestPolicyView(policy.monthlyRate, policy.updatedAt);
        }
        policy.monthlyRate = monthlyRate;
        policy.updatedAt = clock.instant();
        return new InterestPolicyView(monthlyRate, policy.updatedAt);
    }

    /**
     * The rate in force. Readable by the customer as well as the administrator:
     * an interface that prices an instalment plan has to be able to state the
     * rate it was priced under, and a rate the customer cannot see is a rate the
     * customer cannot check.
     */
    public InterestPolicyView interestPolicy(UUID tenantId) {
        InterestPolicyEntity policy = entityManager.find(InterestPolicyEntity.class, tenantId);
        return policy == null
                ? new InterestPolicyView(BigDecimal.ZERO.setScale(6), null)
                : new InterestPolicyView(policy.monthlyRate, policy.updatedAt);
    }

    /** Wallet of the calling customer. A customer with no wallet yet reads zero. */
    public WalletView wallet(UUID tenantId, UUID customerId) {
        WalletEntity wallet = entityManager.find(WalletEntity.class, WalletEntity.key(tenantId, customerId));
        return new WalletView(customerId,
                wallet == null ? BigDecimal.ZERO.setScale(2) : wallet.balance,
                cardBalance(tenantId, customerId));
    }

    /** Purchase statement of the calling customer, most recent first. */
    public List<PurchaseView> statement(UUID tenantId, UUID customerId, int limit) {
        return entityManager.createQuery("""
                        select p from PurchaseEntity p
                        where p.tenantId = :tenantId and p.customerId = :customerId
                        order by p.createdAt desc
                        """, PurchaseEntity.class)
                .setParameter("tenantId", tenantId)
                .setParameter("customerId", customerId)
                .setMaxResults(Math.min(Math.max(limit, 1), 200))
                .getResultList().stream()
                // The wallet balance is a point-in-time value, not a per-purchase
                // fact, so a statement row does not restate it.
                .map(p -> PurchaseView.from(p, null))
                .toList();
    }

    /**
     * Aggregates every wallet and purchase of a tenant, so the answer is cached
     * for a few seconds: a dashboard polling it should not make the database
     * repeat that scan for a figure that barely moved. The cache is a copy the
     * ledger can always recompute, and it always expires.
     */
    public AdminSummary adminSummary(UUID tenantId) {
        var cached = summaryCache.get(tenantId.toString()).map(AdminSummary::parse);
        if (cached.isPresent()) {
            return cached.get();
        }
        AdminSummary summary = computeSummary(tenantId);
        summaryCache.put(tenantId.toString(), summary.serialise());
        return summary;
    }

    private AdminSummary computeSummary(UUID tenantId) {
        Object[] values = (Object[]) entityManager.createQuery("""
                select count(w), coalesce(sum(w.balance), 0),
                  (select coalesce(sum(p.principal), 0) from PurchaseEntity p where p.tenantId = :tenantId),
                  (select coalesce(sum(p.interest), 0) from PurchaseEntity p where p.tenantId = :tenantId)
                from WalletEntity w where w.tenantId = :tenantId
                """).setParameter("tenantId", tenantId).getSingleResult();
        return new AdminSummary((Long) values[0], money(values[1]), money(values[2]), money(values[3]));
    }

    /**
     * The issuing limit on the customer's card.
     *
     * <p>Read from the card rather than from a policy table: the limit is a
     * property of what was issued, and a purchase priced against a limit the
     * card does not carry is a purchase nobody can explain afterwards.
     */
    private BigDecimal creditLimitOf(UUID tenantId, UUID customerId) {
        return entityManager.createQuery("""
                        select c.creditLimit from CardEntity c
                        where c.tenantId = :tenantId and c.customerId = :customerId
                        order by c.createdAt asc
                        """, BigDecimal.class)
                .setParameter("tenantId", tenantId)
                .setParameter("customerId", customerId)
                .setMaxResults(1)
                .getResultStream().findFirst()
                .orElseThrow(CardNotFoundException::forCustomer);
    }

    /**
     * The per-customer mutex, guaranteed to exist before it is locked.
     *
     * <p>This used to return null when the row was absent, which made it a mutex
     * only for customers who had already topped up. The row is created by the
     * first top-up, so a customer who had never funded a wallet and bought on
     * credit acquired no lock at all: two concurrent purchases both read the
     * same outstanding receivable and both passed the limit check. Nothing in
     * the caller could have noticed — a lock that is silently absent looks
     * exactly like a lock that is held.
     *
     * <p>The insert is conditional rather than guarded by a read, because a read
     * that finds nothing is not a promise that the row will still be missing a
     * moment later. Both concurrent callers attempt it, the database keeps one
     * and discards the other without raising, and both then contend for the same
     * row. An empty wallet is also the truthful state for a customer who has one
     * and has never funded it.
     *
     * <p>The conflict is left untargeted deliberately. The table carries two
     * unique constraints over the same identity — the primary key and
     * {@code (tenant_id, customer_id)} — and naming one of them suppresses only
     * that index, so the loser of the race was still rejected by the other.
     */
    private WalletEntity lockedWallet(UUID tenantId, UUID customerId) {
        String walletKey = WalletEntity.key(tenantId, customerId);
        entityManager.createNativeQuery("""
                        insert into wallets (wallet_key, tenant_id, customer_id, balance, version)
                        values (?1, ?2, ?3, 0, 0)
                        on conflict do nothing
                        """)
                .setParameter(1, walletKey)
                .setParameter(2, tenantId)
                .setParameter(3, customerId)
                .executeUpdate();
        return entityManager.find(WalletEntity.class, walletKey, LockModeType.PESSIMISTIC_WRITE);
    }

    private BigDecimal rateFor(UUID tenantId) {
        InterestPolicyEntity policy = entityManager.find(InterestPolicyEntity.class, tenantId);
        return policy == null ? BigDecimal.ZERO : policy.monthlyRate;
    }

    private static void requireMoney(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2 || amount.precision() > 19) {
            throw new IllegalArgumentException("amount must be positive and fit NUMERIC(19,2)");
        }
    }

    /**
     * Serialises the payload by hand rather than reflecting over a class.
     *
     * <p>An event is a record of the past: binding it to a class means a future
     * rename silently rewrites the shape of events already published, and
     * consumers break at a distance. The field names here are a contract.
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

    private static BigDecimal money(Object value) {
        return new BigDecimal(value.toString()).setScale(2);
    }

    public record WalletView(UUID customerId, BigDecimal balance, BigDecimal cardBalance) {}

    public record CardBalanceView(UUID customerId, BigDecimal walletBalance, BigDecimal cardBalance) {}
    public record InterestPolicyView(BigDecimal monthlyRate, Instant updatedAt) {}
    public record AdminSummary(long customerWallets, BigDecimal totalWalletBalance,
                               BigDecimal purchasePrincipal, BigDecimal interestRevenue) {

        /**
         * A fixed, positional form written by hand.
         *
         * <p>Cached bytes outlive the deployment that wrote them. Serialising by
         * reflection would let a renamed field turn an entry written a minute ago
         * into a parse failure — or worse, into a value read into the wrong
         * column. Four amounts in a stated order cannot drift.
         */
        String serialise() {
            return customerWallets + "|" + totalWalletBalance + "|" + purchasePrincipal + "|" + interestRevenue;
        }

        static AdminSummary parse(String cached) {
            String[] parts = cached.split("\\|");
            return new AdminSummary(Long.parseLong(parts[0]), new BigDecimal(parts[1]),
                    new BigDecimal(parts[2]), new BigDecimal(parts[3]));
        }
    }
    public record PurchaseView(UUID id, UUID customerId, String merchantCategory, BigDecimal principal,
                               BigDecimal interest, BigDecimal total, int installments,
                               BigDecimal installmentAmount, BigDecimal lastInstallmentAmount,
                               BigDecimal monthlyRate, BigDecimal remainingCardBalance, Instant createdAt) {
        static PurchaseView from(PurchaseEntity p, BigDecimal cardBalance) {
            return new PurchaseView(p.id, p.customerId, p.merchantCategory, p.principal, p.interest,
                    p.total, p.installments, p.installmentAmount, p.lastInstallmentAmount,
                    p.monthlyRate, cardBalance, p.createdAt);
        }
    }
}
