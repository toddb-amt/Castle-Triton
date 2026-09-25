package castech.emvtxn;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Custom withdrawal amounts round UP to the next multiple of the configured minimum
 * (the minimum is the step): min $10 → $12.50 becomes $20, $5 becomes $10, $20 stays $20.
 * Cents arithmetic only — never doubles — so $12.50 and $0.01 behave predictably.
 */
public class AmountRoundingTest {

    private static final long TEN = 10_00L;

    @Test
    public void belowTheMinimum_roundsUpToTheMinimum() {
        assertEquals(TEN, AmountRounding.roundUpToStepCents(5_00L, TEN));
        assertEquals(TEN, AmountRounding.roundUpToStepCents(1L, TEN));
    }

    @Test
    public void betweenSteps_roundsUpToTheNextMultipleOfTheMinimum() {
        assertEquals(20_00L, AmountRounding.roundUpToStepCents(12_50L, TEN));
        assertEquals(30_00L, AmountRounding.roundUpToStepCents(20_01L, TEN));
        assertEquals(500_00L, AmountRounding.roundUpToStepCents(495_00L, TEN));
    }

    @Test
    public void exactMultiples_areUnchanged() {
        assertEquals(TEN, AmountRounding.roundUpToStepCents(TEN, TEN));
        assertEquals(20_00L, AmountRounding.roundUpToStepCents(20_00L, TEN));
        assertEquals(500_00L, AmountRounding.roundUpToStepCents(500_00L, TEN));
    }

    @Test
    public void nonWholeDollarMinimum_isStillTheStep() {
        assertEquals(15_00L, AmountRounding.roundUpToStepCents(12_50L, 7_50L));
        assertEquals(7_50L, AmountRounding.roundUpToStepCents(7_50L, 7_50L));
    }

    @Test
    public void noUsableStep_leavesTheAmountAlone() {
        assertEquals(12_50L, AmountRounding.roundUpToStepCents(12_50L, 0L));
        assertEquals(12_50L, AmountRounding.roundUpToStepCents(12_50L, -1L));
        assertEquals(0L, AmountRounding.roundUpToStepCents(0L, TEN));
    }

    @Test
    public void dollarsHelper_convertsThroughCentsWithoutFloatDrift() {
        assertEquals(20.00, AmountRounding.roundUpToStep(12.50, 10.00), 0.0);
        assertEquals(10.00, AmountRounding.roundUpToStep(5.00, 10.00), 0.0);
        assertEquals(0.30, AmountRounding.roundUpToStep(0.1 + 0.2, 0.10), 0.0); // 0.30000000000000004 in
    }
}
