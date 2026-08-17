package com.cezicola.card.domain;

/**
 * Chart of accounts.
 *
 * <p>The normal side decides how a balance is read: an account increases on its
 * normal side and decreases on the other. A customer wallet is a liability
 * because the money belongs to the customer and the issuer owes it back, so it
 * grows on the credit side.
 */
public enum LedgerAccount {
    /** What the issuer owes each customer. Kept per customer. */
    CUSTOMER_WALLET(Side.CREDIT, true),
    /** Funds brought into the platform. */
    FUNDING(Side.DEBIT, false),
    /** What the issuer owes merchants for authorised purchases. */
    MERCHANT_PAYABLE(Side.CREDIT, false),
    /** Interest earned on instalment purchases. */
    INTEREST_REVENUE(Side.CREDIT, false);

    private final Side normalSide;
    private final boolean perCustomer;

    LedgerAccount(Side normalSide, boolean perCustomer) {
        this.normalSide = normalSide;
        this.perCustomer = perCustomer;
    }

    public Side normalSide() {
        return normalSide;
    }

    public boolean perCustomer() {
        return perCustomer;
    }

    public enum Side { DEBIT, CREDIT }
}
