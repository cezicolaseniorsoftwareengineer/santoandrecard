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
    /**
     * Funds held for a merchant against an open authorization.
     *
     * <p>A hold does not take money from the customer — the issuer still owes it,
     * just not for the customer to spend. Modelling it as its own liability is
     * what makes the sum of wallet, held and card balances invariant across an
     * authorization and its reversal.
     */
    CUSTOMER_HELD(Side.CREDIT, true),
    /**
     * What the issuer owes each customer on the card itself. Separate from the
     * wallet because loading the card moves money between two obligations rather
     * than creating one: the pair of postings is what proves nothing was minted.
     */
    CARD_PREPAID(Side.CREDIT, true),
    /**
     * What each customer owes the issuer on credit purchases.
     *
     * <p>An asset, and the mirror of the prepaid card: a prepaid purchase spends
     * money the customer already handed over, while a credit purchase creates a
     * debt the issuer will collect. Keeping them as different accounts is what
     * lets available credit be computed from postings rather than from a stored
     * number somebody has to remember to update.
     */
    CUSTOMER_RECEIVABLE(Side.DEBIT, true),
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
