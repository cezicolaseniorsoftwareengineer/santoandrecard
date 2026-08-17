package com.cezicola.card.adapter.in.rest;

/**
 * Raised when an authenticated token does not carry a usable identity claim. The
 * token is valid but unusable for this API, so the request is refused rather than
 * falling back to any caller-supplied value.
 */
public class MissingIdentityClaimException extends RuntimeException {
    private final String claim;

    public MissingIdentityClaimException(String claim) {
        super("Access token is missing a valid '" + claim + "' claim");
        this.claim = claim;
    }

    public String claim() {
        return claim;
    }
}
