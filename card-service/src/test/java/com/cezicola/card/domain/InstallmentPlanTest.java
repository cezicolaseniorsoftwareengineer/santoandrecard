package com.cezicola.card.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The instalments have to add up to the total.
 *
 * <p>Dividing a total into equal hundredths is not generally possible, and the
 * earlier rule rounded each instalment to the nearest cent and let the
 * difference fall on the floor: R$ 1.000,00 over twelve months at 2% quoted a
 * total of R$ 1.268,24 and twelve instalments of R$ 105,69, which come to
 * R$ 1.268,28. Four cents nobody paid and nobody received.
 */
class InstallmentPlanTest {
    private final InterestCalculator calculator = new InterestCalculator();

    @ParameterizedTest
    @CsvSource({
            "1000.00, 12, 0.02",
            "1000.00, 12, 0.035",
            "1000.00, 3,  0.02",
            "1000.00, 7,  0.019",
            "0.01,    12, 0.60",
            "999.99,  11, 0.005",
            "1234.56, 5,  0.0199",
            "100.00,  2,  0.10",
            "50.00,   1,  0.60"
    })
    void instalmentsAlwaysAddUpToTheTotal(String principal, int installments, String rate) {
        PurchasePlan plan = calculator.calculate(
                new BigDecimal(principal), installments, new BigDecimal(rate));

        assertEquals(0, plan.total().compareTo(plan.instalmentSum()),
                "instalments do not add up to the total: " + plan);
        assertEquals(0, plan.total().compareTo(plan.principal().add(plan.interest())),
                "total is not principal plus interest");
    }

    @Test
    void carriesTheRemainderOnTheLastInstalmentRatherThanLosingIt() {
        PurchasePlan plan = calculator.calculate(
                new BigDecimal("1000.00"), 12, new BigDecimal("0.02"));

        assertEquals(new BigDecimal("1268.24"), plan.total());
        assertEquals(new BigDecimal("105.68"), plan.installmentAmount());
        // Eleven at 105.68 is 1162.48; the last one closes the total exactly.
        assertEquals(new BigDecimal("105.76"), plan.lastInstallmentAmount());
        assertEquals(0, plan.total().compareTo(plan.instalmentSum()));
    }

    @Test
    void aCashPurchaseIsOneInstalmentAndCarriesNoInterest() {
        PurchasePlan plan = calculator.calculate(
                new BigDecimal("1000.00"), 1, InterestCalculator.MAX_MONTHLY_RATE);

        assertEquals(new BigDecimal("0.00"), plan.interest());
        assertEquals(new BigDecimal("1000.00"), plan.total());
        assertEquals(new BigDecimal("1000.00"), plan.lastInstallmentAmount());
        assertEquals(0, plan.total().compareTo(plan.instalmentSum()));
    }

    @Test
    void pricesAtTheCeilingButRefusesAnythingAboveIt() {
        assertDoesNotThrow(() -> calculator.calculate(
                new BigDecimal("100.00"), 12, new BigDecimal("0.60")));
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(
                new BigDecimal("100.00"), 12, new BigDecimal("0.601")));
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(
                new BigDecimal("100.00"), 12, new BigDecimal("-0.01")));
    }

    @Test
    void refusesMoreThanTwelveInstalments() {
        assertDoesNotThrow(() -> calculator.calculate(
                new BigDecimal("100.00"), 12, new BigDecimal("0.02")));
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(
                new BigDecimal("100.00"), 13, new BigDecimal("0.02")));
    }

    @Test
    void recordsTheRateThePlanWasPricedWith() {
        PurchasePlan plan = calculator.calculate(
                new BigDecimal("100.00"), 6, new BigDecimal("0.045"));

        assertEquals(new BigDecimal("0.045"), plan.monthlyRate());
    }
}
