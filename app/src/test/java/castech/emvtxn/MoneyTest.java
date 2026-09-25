package castech.emvtxn;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * All dollar↔cent conversions go through one place and ROUND, never truncate.
 * (int)(2.95 * 100) is 294 in IEEE double — a $2.95 surcharge reached the host as $2.94
 * while the receipt said $2.95.
 */
public class MoneyTest {

    @Test
    public void toCents_roundsInsteadOfTruncating() {
        assertEquals(295L, Money.toCents(2.95));
        assertEquals(115L, Money.toCents(1.15));
        assertEquals(435L, Money.toCents(4.35));
        assertEquals(1030L, Money.toCents(10 + 0.3));   // 1029.9999999999998 as a double
        assertEquals(350L, Money.toCents(3.50));
        assertEquals(0L, Money.toCents(0.0));
        assertEquals(50000L, Money.toCents(500.00));
    }

    @Test
    public void feeCents_flatMode_isTheFlatFeeInCents() {
        assertEquals(350L, Money.feeCents(2000L, true, 3.50, 2.99));
        assertEquals(295L, Money.feeCents(2000L, true, 2.95, 0.0));
    }

    @Test
    public void feeCents_percentMode_isRoundedPercentOfTheAmount() {
        assertEquals(30L, Money.feeCents(1000L, false, 3.50, 3.0));      // 3% of $10
        assertEquals(179L, Money.feeCents(6000L, false, 3.50, 2.99));    // 2.99% of $60 = 1.794 → 1.79
        assertEquals(150L, Money.feeCents(5000L, false, 3.50, 2.999));   // 1.4995 → 1.50
        assertEquals(0L, Money.feeCents(0L, false, 3.50, 2.99));
    }

    @Test
    public void dollars_formatsCentsWithTwoDecimals() {
        assertEquals("10.30", Money.dollars(1030L));
        assertEquals("0.00", Money.dollars(0L));
        assertEquals("2.95", Money.dollars(295L));
        assertEquals("500.00", Money.dollars(50000L));
    }

    @Test
    public void displayAndWireAgree_forAwkwardFeeValues() {
        // The receipt string and the host/chip cents must come from the same integer.
        long amount = Money.toCents(20.00);
        long fee = Money.feeCents(amount, true, 2.95, 0.0);
        assertEquals("2.95", Money.dollars(fee));
        assertEquals(2295L, amount + fee);
        assertEquals("22.95", Money.dollars(amount + fee));
    }
}
