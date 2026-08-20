package com.cezicola.card.application;

import com.cezicola.card.adapter.out.persistence.StatementEntity;
import com.cezicola.card.domain.BillingCycle;
import com.cezicola.card.domain.FundingSource;
import com.cezicola.card.domain.StatementStatus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The delinquency sweep.
 *
 * <p>Driven directly rather than waited for: the tests assert what the sweep
 * does, not when the scheduler happens to fire. Being late is a state of a claim
 * rather than an event that happens to it, so running the sweep twice must
 * change nothing the second time.
 */
@QuarkusTest
class StatementOverdueTest {

    @Inject FinanceService finance;
    @Inject BillingService billing;
    @Inject CardService cards;
    @Inject EntityManager entityManager;

    /** Backdates the due date so the sweep sees a statement the clock has passed. */
    @Transactional
    void makeDue(UUID statementId, LocalDate dueDate) {
        StatementEntity entity = entityManager.find(StatementEntity.class, statementId);
        entity.dueDate = dueDate;
    }

    private UUID closedStatement(UUID tenant, UUID customer, String amount) {
        finance.setInterestPolicy(tenant, BigDecimal.ZERO);
        cards.issueForCustomer(tenant, customer);
        finance.purchase(tenant, customer, "RETAIL", new BigDecimal(amount), 1, FundingSource.CREDIT);
        return billing.close(tenant, customer, BillingCycle.containing(Instant.now())).id();
    }

    private StatementStatus statusOf(UUID tenant, UUID customer) {
        return billing.statements(tenant, customer, 1).get(0).status();
    }

    @Test
    void aBalanceLeftAfterTheDueDateBecomesOverdue() {
        UUID tenant = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID statementId = closedStatement(tenant, customer, "200.00");

        makeDue(statementId, LocalDate.now(BillingCycle.ZONE).minusDays(1));
        int marked = billing.markOverdue(50);

        assertTrue(marked >= 1);
        assertEquals(StatementStatus.OVERDUE, statusOf(tenant, customer));
    }

    @Test
    void theSweepIsIdempotent() {
        UUID tenant = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID statementId = closedStatement(tenant, customer, "120.00");
        makeDue(statementId, LocalDate.now(BillingCycle.ZONE).minusDays(3));

        billing.markOverdue(50);
        StatementStatus after = statusOf(tenant, customer);
        // The second run finds nothing left to do with this one: a sweep that
        // reports work every time it runs cannot be used to measure anything.
        billing.markOverdue(50);

        assertEquals(StatementStatus.OVERDUE, after);
        assertEquals(StatementStatus.OVERDUE, statusOf(tenant, customer));
    }

    @Test
    void aStatementPaidInFullIsNeverMarkedLate() {
        UUID tenant = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID statementId = closedStatement(tenant, customer, "90.00");
        finance.topUp(tenant, customer, new BigDecimal("500.00"));
        billing.pay(tenant, customer, statementId, new BigDecimal("90.00"));

        makeDue(statementId, LocalDate.now(BillingCycle.ZONE).minusDays(10));
        billing.markOverdue(50);

        assertEquals(StatementStatus.PAID, statusOf(tenant, customer));
    }

    @Test
    void aStatementStillWithinItsDueDateIsLeftAlone() {
        UUID tenant = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID statementId = closedStatement(tenant, customer, "75.00");

        makeDue(statementId, LocalDate.now(BillingCycle.ZONE).plusDays(5));
        billing.markOverdue(50);

        assertEquals(StatementStatus.CLOSED, statusOf(tenant, customer));
    }

    @Test
    void anOverdueStatementStillAcceptsPayment() {
        UUID tenant = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID statementId = closedStatement(tenant, customer, "300.00");
        finance.topUp(tenant, customer, new BigDecimal("500.00"));
        makeDue(statementId, LocalDate.now(BillingCycle.ZONE).minusDays(2));
        billing.markOverdue(50);

        // Being late does not close the door: refusing payment on an overdue
        // claim would leave the customer no way to settle it.
        var paid = billing.pay(tenant, customer, statementId, new BigDecimal("300.00"));

        assertEquals(StatementStatus.PAID, paid.status());
        assertEquals(0, BigDecimal.ZERO.compareTo(paid.balance()));
    }

    @Test
    void theSweepIsBounded() {
        // A sweep that grows with history eventually holds a transaction open
        // longer than a payment can wait for it.
        assertTrue(billing.markOverdue(1) <= 1);
    }
}
