package com.cezicola.card.domain;

/**
 * The card product a customer holds.
 *
 * <p>It lives in the domain rather than in the interface because the product
 * determines what the card is worth: the issuing limit and, later, the fees and
 * benefits attached to it. A name held only in the front end would be decoration
 * that no server-side rule could rely on.
 */
public enum CardProduct {
    PLATINUM("Santo André Card Platinum");

    private final String displayName;

    CardProduct(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
