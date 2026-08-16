package com.cezicola.card.domain;

import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;

@ApplicationScoped
public class InterestCalculator {
    private static final BigDecimal ONE = BigDecimal.ONE;

    public PurchasePlan calculate(BigDecimal principal, int installments, BigDecimal monthlyRate) {
        requireMoney(principal, "principal");
        if (installments < 1 || installments > 24) {
            throw new IllegalArgumentException("installments must be between 1 and 24");
        }
        if (monthlyRate == null || monthlyRate.signum() < 0 || monthlyRate.compareTo(ONE) > 0) {
            throw new IllegalArgumentException("monthlyRate must be between 0 and 1");
        }
        BigDecimal total = installments == 1
                ? principal
                : principal.multiply(ONE.add(monthlyRate).pow(installments)).setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal interest = total.subtract(principal).setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal installmentAmount = total.divide(BigDecimal.valueOf(installments), 2, RoundingMode.HALF_EVEN);
        return new PurchasePlan(principal, interest, total, installments, installmentAmount);
    }

    private static void requireMoney(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0 || value.scale() > 2 || value.precision() > 19) {
            throw new IllegalArgumentException(field + " must be positive and fit NUMERIC(19,2)");
        }
    }
}
