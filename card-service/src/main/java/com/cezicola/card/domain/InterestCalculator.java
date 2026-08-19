package com.cezicola.card.domain;

import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Prices an instalment plan.
 *
 * <p>A single instalment is a cash purchase and carries no interest whatever the
 * administered rate is. Beyond that the rate compounds monthly.
 */
@ApplicationScoped
public class InterestCalculator {
    private static final BigDecimal ONE = BigDecimal.ONE;

    /** Product rule: a purchase is paid in cash or split over at most twelve months. */
    public static final int MAX_INSTALLMENTS = 12;

    /**
     * Ceiling on the administered monthly rate, as a fraction: 0.60 is 60% a
     * month. A bound belongs in the domain and not only in request validation,
     * because the calculator is what turns a rate into money owed.
     */
    public static final BigDecimal MAX_MONTHLY_RATE = new BigDecimal("0.60");

    public PurchasePlan calculate(BigDecimal principal, int installments, BigDecimal monthlyRate) {
        requireMoney(principal, "principal");
        if (installments < 1 || installments > MAX_INSTALLMENTS) {
            throw new IllegalArgumentException("installments must be between 1 and " + MAX_INSTALLMENTS);
        }
        requireRate(monthlyRate);

        BigDecimal total = installments == 1
                ? principal
                : principal.multiply(ONE.add(monthlyRate).pow(installments)).setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal interest = total.subtract(principal).setScale(2, RoundingMode.UNNECESSARY);

        // Floor the regular instalment so the remainder is never negative, then
        // give the whole remainder to the last one. Rounding each instalment to
        // the nearest cent instead would leave the plan short or over by a few
        // cents against its own total, which is money nobody pays or receives.
        BigDecimal installmentAmount = total.divide(BigDecimal.valueOf(installments), 2, RoundingMode.DOWN);
        BigDecimal lastInstallmentAmount = total.subtract(
                installmentAmount.multiply(BigDecimal.valueOf(installments - 1L)));

        return new PurchasePlan(principal, interest, total, installments,
                installmentAmount, lastInstallmentAmount, monthlyRate);
    }

    /** Rejects a rate outside the administered range, so an out-of-range policy cannot price anything. */
    public static void requireRate(BigDecimal monthlyRate) {
        if (monthlyRate == null || monthlyRate.signum() < 0 || monthlyRate.compareTo(MAX_MONTHLY_RATE) > 0) {
            throw new IllegalArgumentException("monthlyRate must be between 0 and " + MAX_MONTHLY_RATE);
        }
    }

    private static void requireMoney(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0 || value.scale() > 2 || value.precision() > 19) {
            throw new IllegalArgumentException(field + " must be positive and fit NUMERIC(19,2)");
        }
    }
}
