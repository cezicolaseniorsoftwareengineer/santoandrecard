package com.cezicola.card.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A balanced set of postings, validated before anything is written.
 *
 * <p>The invariant lives here, in the domain, rather than in the service or the
 * database: an unbalanced entry is not a persistence problem, it is not a
 * transaction at all.
 */
public record JournalEntry(Kind kind, String description, UUID referenceId, List<Posting> postings) {

    public enum Kind {
        OPENING_BALANCE, TOP_UP, PURCHASE, CARD_LOAD,
        AUTHORIZATION_HOLD, AUTHORIZATION_CAPTURE, AUTHORIZATION_REVERSAL, AUTHORIZATION_EXPIRY,
        /** A credit purchase: the issuer pays the merchant and the customer owes it back. */
        CREDIT_PURCHASE,
        /** The customer settles a statement from their wallet. */
        STATEMENT_PAYMENT
    }

    public JournalEntry {
        if (description == null || description.isBlank() || description.length() > 140) {
            throw new UnbalancedTransactionException("description must contain 1 to 140 characters");
        }
        postings = List.copyOf(postings);
        if (postings.size() < 2) {
            throw new UnbalancedTransactionException("a transaction needs at least two postings");
        }
        BigDecimal debits = sumOf(postings, LedgerAccount.Side.DEBIT);
        BigDecimal credits = sumOf(postings, LedgerAccount.Side.CREDIT);
        if (debits.compareTo(credits) != 0) {
            throw new UnbalancedTransactionException(debits, credits);
        }
        if (debits.signum() <= 0) {
            throw new UnbalancedTransactionException("a transaction must move a positive amount");
        }
    }

    private static BigDecimal sumOf(List<Posting> postings, LedgerAccount.Side side) {
        return postings.stream()
                .filter(posting -> posting.side() == side)
                .map(Posting::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** One line of a transaction. Amounts are always positive; direction carries the sign. */
    public record Posting(LedgerAccount account, UUID customerId, LedgerAccount.Side side, BigDecimal amount) {
        public Posting {
            if (amount == null || amount.signum() <= 0 || amount.scale() > 2) {
                throw new UnbalancedTransactionException("posting amount must be positive with at most two decimals");
            }
            if (account.perCustomer() == (customerId == null)) {
                throw new UnbalancedTransactionException(
                        account + " requires a customer identifier exactly when it is a per-customer account");
            }
        }

        public static Posting debit(LedgerAccount account, UUID customerId, BigDecimal amount) {
            return new Posting(account, customerId, LedgerAccount.Side.DEBIT, amount);
        }

        public static Posting credit(LedgerAccount account, UUID customerId, BigDecimal amount) {
            return new Posting(account, customerId, LedgerAccount.Side.CREDIT, amount);
        }
    }
}
