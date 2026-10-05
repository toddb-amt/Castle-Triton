package castech.emvtxn.atm.host;

/**
 * When a host decline is re-tried with a fresh PIN, and what that means for the register.
 * Pure — pinned by PinRetryPolicyTest.
 *
 * <p>The STD1 spec marks "55 Incorrect PIN" as decline-allow-retry: the terminal re-prompts
 * the PIN and resends the same card data with a new PIN block, up to a local attempt limit.
 * Such a 55 is therefore not the outcome of the transaction.
 *
 * <p><b>POS-14 (6.2.12):</b> the decline hook used to tell the register "declined 55" the
 * moment the host said so, and release the POS slot. The terminal then re-prompted and
 * resent; an approval on the retry found no slot to report to. Money could move on a sale
 * the register showed as declined. The register now hears only the outcome: an approval
 * through the normal hook, a final 55 (attempt limit reached, or the customer gave up on
 * the re-prompt) from the transaction's local-ending answer (POS-13), which carries the
 * host's code and reason.
 */
public final class PinRetryPolicy {

    private PinRetryPolicy() {}

    /** True when a host decline with this code is re-tried rather than final. */
    public static boolean isRetryableDecline(boolean retryEnabled, String responseCode) {
        return retryEnabled && HyosungProtocol.RESP_INCORRECT_PIN.equals(responseCode);
    }

    /**
     * True when the terminal should re-prompt the PIN and send again.
     *
     * @param hostApproved the host approved this attempt
     * @param responseCode the host's response code for this attempt
     * @param attempt      the attempt just made, counting from 1
     * @param maxAttempts  the local limit on attempts
     */
    public static boolean shouldRetry(boolean retryEnabled, boolean hostApproved, String responseCode,
                                      int attempt, int maxAttempts) {
        return !hostApproved
                && isRetryableDecline(retryEnabled, responseCode)
                && attempt < maxAttempts;
    }

    /**
     * True when a decline with this code is reported to the register as soon as it arrives.
     * A retryable decline is not: the register hears the transaction's outcome instead.
     */
    public static boolean reportDeclineToRegisterNow(boolean retryEnabled, String responseCode) {
        return !isRetryableDecline(retryEnabled, responseCode);
    }
}
