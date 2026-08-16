package com.cezicola.card.domain;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class InterestCalculatorTest {
    private final InterestCalculator calculator = new InterestCalculator();

    @Test
    void doesNotChargeInterestForCashPurchase() {
        PurchasePlan plan = calculator.calculate(new BigDecimal("100.00"), 1, new BigDecimal("0.10"));
        assertEquals(new BigDecimal("0.00"), plan.interest());
        assertEquals(new BigDecimal("100.00"), plan.total());
    }

    @Test
    void compoundsMonthlyInterestAndRoundsHalfEven() {
        PurchasePlan plan = calculator.calculate(new BigDecimal("100.00"), 2, new BigDecimal("0.10"));
        assertEquals(new BigDecimal("21.00"), plan.interest());
        assertEquals(new BigDecimal("121.00"), plan.total());
        assertEquals(new BigDecimal("60.50"), plan.installmentAmount());
    }

    @Test
    void rejectsUnsupportedInstallmentCount() {
        assertThrows(IllegalArgumentException.class,
                () -> calculator.calculate(new BigDecimal("100.00"), 25, BigDecimal.ZERO));
    }
}
