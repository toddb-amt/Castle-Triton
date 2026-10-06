package castech.emvtxn.atm.host;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * POS-14 (6.2.12). A "55 Incorrect PIN" is re-tried with a fresh PIN, so it is not the
 * outcome of the transaction — and the register must hear only the outcome.
 *
 * <p>Before this, the decline hook told the register "declined 55" the moment the host
 * said so and released the POS slot; the terminal then re-prompted the PIN and resent,
 * and an approval on the retry found no slot to report to. Money could move on a sale
 * the register showed as declined.
 */
public class PinRetryPolicyTest {

    private static final boolean RETRY_ON = true;
    private static final boolean RETRY_OFF = false;
    private static final int MAX = 3;

    // ---- when the terminal re-prompts ---------------------------------------------

    @Test
    public void incorrectPin_isRetried_whileAttemptsRemain() {
        assertTrue(PinRetryPolicy.shouldRetry(RETRY_ON, false, "55", 1, MAX));
        assertTrue(PinRetryPolicy.shouldRetry(RETRY_ON, false, "55", 2, MAX));
    }

    @Test
    public void incorrectPin_onTheLastAttempt_isFinal() {
        assertFalse(PinRetryPolicy.shouldRetry(RETRY_ON, false, "55", 3, MAX));
    }

    @Test
    public void anyOtherDecline_isFinal() {
        assertFalse(PinRetryPolicy.shouldRetry(RETRY_ON, false, "75", 1, MAX));  // PIN tries exceeded
        assertFalse(PinRetryPolicy.shouldRetry(RETRY_ON, false, "51", 1, MAX));
        assertFalse(PinRetryPolicy.shouldRetry(RETRY_ON, false, "", 1, MAX));
        assertFalse(PinRetryPolicy.shouldRetry(RETRY_ON, false, null, 1, MAX));
    }

    @Test
    public void anApproval_isNeverRetried() {
        assertFalse(PinRetryPolicy.shouldRetry(RETRY_ON, true, "55", 1, MAX));
    }

    @Test
    public void withRetryDisabled_everyDeclineIsFinal() {
        assertFalse(PinRetryPolicy.shouldRetry(RETRY_OFF, false, "55", 1, MAX));
    }

    // ---- what the register hears, and when -------------------------------------

    @Test
    public void register_doesNotHearAnIncorrectPin_whileTheTerminalMayRetry() {
        assertFalse(PinRetryPolicy.reportDeclineToRegisterNow(RETRY_ON, "55"));
    }

    @Test
    public void register_hearsEveryOtherDecline_atOnce() {
        assertTrue(PinRetryPolicy.reportDeclineToRegisterNow(RETRY_ON, "75"));
        assertTrue(PinRetryPolicy.reportDeclineToRegisterNow(RETRY_ON, "51"));
        assertTrue(PinRetryPolicy.reportDeclineToRegisterNow(RETRY_ON, null));
    }

    @Test
    public void register_hearsAnIncorrectPinAtOnce_whenRetryIsDisabled() {
        assertTrue(PinRetryPolicy.reportDeclineToRegisterNow(RETRY_OFF, "55"));
    }
}
