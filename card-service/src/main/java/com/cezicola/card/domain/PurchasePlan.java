package com.cezicola.card.domain;

import java.math.BigDecimal;

/**
 * The priced instalment plan of a purchase.
 *
 * <p>{@code installmentAmount} is what the customer pays on every instalment but
 * the last, which is {@code lastInstallmentAmount}. Dividing a total into equal
 * hundredths is not generally possible — R$ 1.268,24 over twelve months leaves a
 * remainder — and a plan whose instalments do not add up to its total is a plan
 * the statement can never reconcile against. The remainder is carried by the
 * final instalment rather than dropped.
 *
 * <p>{@code monthlyRate} is the rate the plan was priced with, kept so a purchase
 * can record what it was actually charged under: the administered rate changes
 * over time, and a past purchase priced under a rate nobody can name any more is
 * not auditable.
 */
public record PurchasePlan(BigDecimal principal, BigDecimal interest, BigDecimal total,
                           int installments, BigDecimal installmentAmount,
                           BigDecimal lastInstallmentAmount, BigDecimal monthlyRate) {

    /** What the instalments actually add up to. Equal to {@link #total()} by construction. */
    public BigDecimal instalmentSum() {
        return installmentAmount.multiply(BigDecimal.valueOf(installments - 1L)).add(lastInstallmentAmount);
    }
}
