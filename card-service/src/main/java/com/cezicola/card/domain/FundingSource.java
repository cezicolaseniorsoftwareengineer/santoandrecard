package com.cezicola.card.domain;

/**
 * What pays for a purchase.
 *
 * <p>Fixed at authorization and immutable afterwards. Changing it later would
 * rewrite which account was debited after the postings were made, so a change of
 * mind is a reversal and a new purchase rather than an edit.
 */
public enum FundingSource {
    /**
     * The prepaid balance already loaded onto the card. Settles immediately:
     * the money was handed over before the purchase and simply moves.
     */
    CARD,
    /**
     * The issuer's credit. Creates a receivable that is billed by the cycle it
     * falls in and settled when the customer pays that statement.
     */
    CREDIT
}

