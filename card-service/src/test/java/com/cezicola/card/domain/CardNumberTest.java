package com.cezicola.card.domain;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CardNumberTest {

    @RepeatedTest(50)
    void generatesSixteenDigitsThatPassLuhn() {
        CardNumber number = CardNumber.generate();

        assertTrue(number.value().matches("\\d{16}"));
        // Rebuilding through the constructor re-runs the Luhn check, so a
        // generator that produced an invalid number could not construct one.
        assertEquals(number, new CardNumber(number.value()));
    }

    @Test
    void carriesTheFictitiousBinSoItReachesNoNetwork() {
        assertTrue(CardNumber.generate().value().startsWith("999900"));
    }

    @Test
    void refusesANumberThatFailsLuhn() {
        assertThrows(IllegalArgumentException.class, () -> new CardNumber("9999000000000001"));
    }

    @Test
    void refusesAnythingThatIsNotSixteenDigits() {
        assertThrows(IllegalArgumentException.class, () -> new CardNumber("4111111111111"));
        assertThrows(IllegalArgumentException.class, () -> new CardNumber("abcdabcdabcdabcd"));
    }

    @Test
    void exposesTheLastFourAndThePrintedGrouping() {
        CardNumber number = new CardNumber("9999000000000004");

        assertEquals("0004", number.lastFourDigits());
        assertEquals("9999 0000 0000 0004", number.grouped());
    }
}
