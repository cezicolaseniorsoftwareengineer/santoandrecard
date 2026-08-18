package com.cezicola.card.domain;

import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * A card PIN, stored the only way a PIN may be stored: derived, never kept.
 *
 * <p>Four digits is ten thousand possibilities, so the derivation alone is not
 * the control — it makes an exposed database useless, not an online attacker
 * slow. Limiting attempts is what defends the live card, and that lives with the
 * card itself.
 */
public record CardPin(String salt, String hash) {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 210_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_BYTES = 16;

    public CardPin {
        if (salt == null || salt.isBlank() || hash == null || hash.isBlank()) {
            throw new IllegalArgumentException("a stored PIN needs both a salt and a hash");
        }
    }

    public static CardPin of(String plainPin) {
        requireFourDigits(plainPin);
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return new CardPin(encode(salt), encode(derive(plainPin, salt)));
    }

    /**
     * Compared in constant time. A comparison that returns early on the first
     * differing byte leaks how much of the derived key was guessed.
     */
    public boolean matches(String candidatePin) {
        if (candidatePin == null || !candidatePin.matches("\\d{4}")) {
            return false;
        }
        byte[] expected = Base64.getDecoder().decode(hash);
        byte[] actual = derive(candidatePin, Base64.getDecoder().decode(salt));
        return java.security.MessageDigest.isEqual(expected, actual);
    }

    private static void requireFourDigits(String pin) {
        if (pin == null || !pin.matches("\\d{4}")) {
            throw new IllegalArgumentException("the PIN must contain exactly four digits");
        }
    }

    private static byte[] derive(String pin, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (java.security.GeneralSecurityException unavailable) {
            throw new IllegalStateException("PIN derivation is unavailable", unavailable);
        } finally {
            // Clears the copy of the PIN this spec holds, so it does not sit in
            // the heap until the collector happens to reach it.
            spec.clearPassword();
        }
    }

    private static String encode(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }
}
