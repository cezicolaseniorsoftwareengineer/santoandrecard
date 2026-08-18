package com.cezicola.card.domain;

import java.security.SecureRandom;

/**
 * The number printed on a simulated card.
 *
 * <p>This is not a PAN. It carries a fictitious BIN that no card network routes,
 * it is issued by nothing, and it authorises nothing: purchases in this platform
 * are settled against the customer's own balance, never presented to an
 * acquirer. Storing it is therefore a property of a demonstration card and not
 * cardholder data — a real issuer would keep the PAN in a vault, out of this
 * service, and this class would hold a token instead.
 *
 * <p>It still satisfies the Luhn check so that any interface or validator that
 * treats it as a card number behaves the way it would in production.
 */
public record CardNumber(String value) {
    /** Not assigned to any scheme, so a number built on it cannot reach a network. */
    private static final String FICTITIOUS_BIN = "999900";
    private static final int LENGTH = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    public CardNumber {
        if (value == null || !value.matches("\\d{" + LENGTH + "}")) {
            throw new IllegalArgumentException("a card number must contain exactly " + LENGTH + " digits");
        }
        if (!passesLuhn(value)) {
            throw new IllegalArgumentException("card number fails the Luhn check");
        }
    }

    public static CardNumber generate() {
        StringBuilder digits = new StringBuilder(FICTITIOUS_BIN);
        while (digits.length() < LENGTH - 1) {
            digits.append(RANDOM.nextInt(10));
        }
        return new CardNumber(digits.append(checkDigitFor(digits.toString())).toString());
    }

    public String lastFourDigits() {
        return value.substring(LENGTH - 4);
    }

    /** Grouped the way it is printed, so the interface does not have to know the format. */
    public String grouped() {
        return value.replaceAll("(\\d{4})(?=\\d)", "$1 ");
    }

    private static int checkDigitFor(String withoutCheckDigit) {
        int sum = sumForLuhn(withoutCheckDigit, true);
        return (10 - (sum % 10)) % 10;
    }

    private static boolean passesLuhn(String number) {
        return sumForLuhn(number, false) % 10 == 0;
    }

    /**
     * Luhn doubles every second digit counting from the right. When the check
     * digit is not yet present the parity of every position flips, which is why
     * the caller has to say which case it is.
     */
    private static int sumForLuhn(String digits, boolean checkDigitMissing) {
        int sum = 0;
        boolean doubling = checkDigitMissing;
        for (int position = digits.length() - 1; position >= 0; position--) {
            int digit = digits.charAt(position) - '0';
            if (doubling) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubling = !doubling;
        }
        return sum;
    }
}
