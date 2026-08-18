package com.cezicola.card.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CardPinTest {

    @Test
    void acceptsTheCorrectPin() {
        assertTrue(CardPin.of("4821").matches("4821"));
    }

    @Test
    void rejectsEveryOtherPin() {
        CardPin pin = CardPin.of("4821");

        assertFalse(pin.matches("4822"));
        assertFalse(pin.matches("1284"));
        assertFalse(pin.matches(""));
        assertFalse(pin.matches(null));
    }

    @Test
    void neverStoresThePinItself() {
        CardPin pin = CardPin.of("0000");

        assertNotEquals("0000", pin.hash());
        assertFalse(pin.hash().contains("0000"));
    }

    @Test
    void derivesADifferentHashPerCardEvenForTheSamePin() {
        // Without a per-card salt, two customers choosing 1234 would share a hash
        // and one leaked derivation would unlock both.
        assertNotEquals(CardPin.of("1234").hash(), CardPin.of("1234").hash());
    }

    @Test
    void refusesToStoreAnythingThatIsNotFourDigits() {
        assertThrows(IllegalArgumentException.class, () -> CardPin.of("123"));
        assertThrows(IllegalArgumentException.class, () -> CardPin.of("12345"));
        assertThrows(IllegalArgumentException.class, () -> CardPin.of("abcd"));
    }
}
