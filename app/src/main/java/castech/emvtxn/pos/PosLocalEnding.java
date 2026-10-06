package castech.emvtxn.pos;

/**
 * What the register is told when a POS-driven transaction finishes on the terminal
 * without a host result (POS-13, 6.2.12). Pure — the mapping is pinned by
 * PosLocalEndingTest.
 *
 * <p><b>Why this exists:</b> the register used to be answered only by the host callbacks
 * and by Cancel. A transaction that ended locally — a card the terminal could not send,
 * a read error, an abandoned PIN pad, a failure in the transaction thread — showed its
 * result on the terminal and left the POS slot armed until the 300 s watchdog, which
 * then reported {@code host_unreachable}. The register timed out long before that and
 * every POS command in between was refused as {@code terminal_busy}.
 *
 * <p>A local ending is sent as a {@code declined} response whose {@code response_code}
 * is NOT a two-digit host code: the terminal's own code when it recorded one (the same
 * code and reason the receipt shows), otherwise one of the three below.
 */
public final class PosLocalEnding {

    /** Nothing was recorded: the customer backed out (same answer Cancel sends). */
    public static final String RC_USER_CANCELLED = "user_cancelled";
    /** The terminal recorded a reason but no code. */
    public static final String RC_TERMINAL_DECLINED = "terminal_declined";
    /** The transaction thread itself failed. */
    public static final String RC_TERMINAL_ERROR = "terminal_error";

    public final String responseCode;
    public final String message;

    PosLocalEnding(String responseCode, String message) {
        this.responseCode = responseCode;
        this.message = message;
    }

    /**
     * @param hostCallInFlight a request is still with the host layer. The transaction
     *        thread can give up before the host layer does; answering now would empty
     *        the slot and a late approval would be dropped — so say nothing and let the
     *        real result (or the watchdog) answer.
     * @param hostApproved the host approved. The approval callback has answered the
     *        register already; never follow an approval with a decline.
     * @param responseCode the terminal's result code, as the receipt shows it
     * @param responseMessage the terminal's reason, as the receipt shows it
     * @param threadError what failed, when the terminal itself ended the transaction (a
     *        Throwable's class name, "card reader not ready", ...),
     *        or null when it ended normally
     * @return what to tell the register, or {@code null} to say nothing
     */
    public static PosLocalEnding decide(boolean hostCallInFlight, boolean hostApproved,
                                        String responseCode, String responseMessage, String threadError) {
        if (hostCallInFlight || hostApproved) return null;

        if (threadError != null && !threadError.trim().isEmpty()) {
            return new PosLocalEnding(RC_TERMINAL_ERROR, "terminal error: " + threadError.trim());
        }

        String code = responseCode == null ? "" : responseCode.trim();
        String reason = responseMessage == null ? "" : responseMessage.trim();
        if (code.isEmpty() && reason.isEmpty()) {
            return new PosLocalEnding(RC_USER_CANCELLED, "cancelled at terminal");
        }
        return new PosLocalEnding(code.isEmpty() ? RC_TERMINAL_DECLINED : code,
                reason.isEmpty() ? "declined at terminal" : reason);
    }
}
