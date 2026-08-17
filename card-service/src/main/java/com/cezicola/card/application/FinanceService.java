package com.cezicola.card.application;

import com.cezicola.card.adapter.out.persistence.InterestPolicyEntity;
import com.cezicola.card.adapter.out.persistence.PurchaseEntity;
import com.cezicola.card.adapter.out.persistence.WalletEntity;
import com.cezicola.card.domain.InterestCalculator;
import com.cezicola.card.domain.PurchasePlan;
import com.cezicola.card.application.port.MerchantAuthorizationPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@ApplicationScoped
public class FinanceService {
    private final EntityManager entityManager;
    private final InterestCalculator calculator;
    private final MerchantAuthorizationPort merchantAuthorization;
    private final PurchaseBackpressureGuard backpressure;
    private final Clock clock = Clock.systemUTC();

    public FinanceService(EntityManager entityManager, InterestCalculator calculator,
                          MerchantAuthorizationPort merchantAuthorization,
                          PurchaseBackpressureGuard backpressure) {
        this.entityManager = entityManager;
        this.calculator = calculator;
        this.merchantAuthorization = merchantAuthorization;
        this.backpressure = backpressure;
    }

    @Transactional
    public WalletView topUp(UUID tenantId, UUID customerId, BigDecimal amount) {
        requireMoney(amount);
        WalletEntity wallet = lockedWallet(tenantId, customerId);
        if (wallet == null) {
            wallet = new WalletEntity();
            wallet.walletKey = WalletEntity.key(tenantId, customerId);
            wallet.tenantId = tenantId;
            wallet.customerId = customerId;
            wallet.balance = BigDecimal.ZERO.setScale(2);
            entityManager.persist(wallet);
        }
        wallet.balance = wallet.balance.add(amount);
        return new WalletView(customerId, wallet.balance);
    }

    public PurchasePlan quote(UUID tenantId, BigDecimal principal, int installments) {
        return calculator.calculate(principal, installments, rateFor(tenantId));
    }

    @Transactional
    public PurchaseView purchase(UUID tenantId, UUID customerId, String merchantCategory,
                                 BigDecimal principal, int installments) {
        return backpressure.execute(
                () -> executePurchase(tenantId, customerId, merchantCategory, principal, installments));
    }

    private PurchaseView executePurchase(UUID tenantId, UUID customerId, String merchantCategory,
                                          BigDecimal principal, int installments) {
        if (merchantCategory == null || merchantCategory.isBlank() || merchantCategory.length() > 64) {
            throw new IllegalArgumentException("merchantCategory must contain 1 to 64 characters");
        }
        PurchasePlan plan = quote(tenantId, principal, installments);
        var decision = merchantAuthorization.authorize(tenantId, customerId, merchantCategory, plan.total());
        if (decision != MerchantAuthorizationPort.AuthorizationDecision.APPROVED) {
            throw new MerchantAuthorizationUnavailableException();
        }
        WalletEntity wallet = lockedWallet(tenantId, customerId);
        if (wallet == null || wallet.balance.compareTo(plan.total()) < 0) {
            throw new InsufficientFundsException();
        }
        wallet.balance = wallet.balance.subtract(plan.total());
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
        purchase.createdAt = clock.instant();
        entityManager.persist(purchase);
        return PurchaseView.from(purchase, wallet.balance);
    }

    @Transactional
    public InterestPolicyView setInterestPolicy(UUID tenantId, BigDecimal monthlyRate) {
        if (monthlyRate == null || monthlyRate.signum() < 0 || monthlyRate.compareTo(BigDecimal.ONE) > 0
                || monthlyRate.scale() > 6) {
            throw new IllegalArgumentException("monthlyRate must be between 0 and 1 with at most 6 decimals");
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

    public AdminSummary adminSummary(UUID tenantId) {
        Object[] values = (Object[]) entityManager.createQuery("""
                select count(w), coalesce(sum(w.balance), 0),
                  (select coalesce(sum(p.principal), 0) from PurchaseEntity p where p.tenantId = :tenantId),
                  (select coalesce(sum(p.interest), 0) from PurchaseEntity p where p.tenantId = :tenantId)
                from WalletEntity w where w.tenantId = :tenantId
                """).setParameter("tenantId", tenantId).getSingleResult();
        return new AdminSummary((Long) values[0], money(values[1]), money(values[2]), money(values[3]));
    }

    private WalletEntity lockedWallet(UUID tenantId, UUID customerId) {
        return entityManager.find(WalletEntity.class, WalletEntity.key(tenantId, customerId), LockModeType.PESSIMISTIC_WRITE);
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

    private static BigDecimal money(Object value) {
        return new BigDecimal(value.toString()).setScale(2);
    }

    public record WalletView(UUID customerId, BigDecimal balance) {}
    public record InterestPolicyView(BigDecimal monthlyRate, Instant updatedAt) {}
    public record AdminSummary(long customerWallets, BigDecimal totalWalletBalance,
                               BigDecimal purchasePrincipal, BigDecimal interestRevenue) {}
    public record PurchaseView(UUID id, UUID customerId, String merchantCategory, BigDecimal principal,
                               BigDecimal interest, BigDecimal total, int installments,
                               BigDecimal installmentAmount, BigDecimal remainingWalletBalance, Instant createdAt) {
        static PurchaseView from(PurchaseEntity p, BigDecimal balance) {
            return new PurchaseView(p.id, p.customerId, p.merchantCategory, p.principal, p.interest,
                    p.total, p.installments, p.installmentAmount, balance, p.createdAt);
        }
    }
}
