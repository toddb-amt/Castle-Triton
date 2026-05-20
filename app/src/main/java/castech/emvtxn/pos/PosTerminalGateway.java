package castech.emvtxn.pos;

/**
 * Minimum surface the POS module needs from the existing host layer.
 * Phase 7 provides the production implementation as a thin adapter over
 * {@code AtmHostService}; Phase 6 tests use an in-memory fake.
 *
 * <p>All methods are async: the caller passes a callback that fires exactly
 * once on completion. Implementations are responsible for driving any UI
 * (card-read prompt, PIN entry) the transaction requires.
 *
 * <p>Account type strings use POS wire vocabulary: {@code "checking"},
 * {@code "savings"}, {@code "credit"}. The gateway implementation maps to
 * the Hyosung protocol's two-letter codes ("CA"/"SA"/"CR") before
 * dispatching to the host layer.
 */
public interface PosTerminalGateway {

    /**
     * Snapshot of terminal readiness. Returns true when:
     * <ul>
     *   <li>Host service initialized</li>
     *   <li>Working key loaded (PIN encryption possible)</li>
     *   <li>No transaction currently in progress</li>
     * </ul>
     * Implementations may return a fail reason via {@link #getNotReadyReason()}
     * when this returns false.
     */
    boolean isReady();

    /**
     * Human-readable reason why {@link #isReady()} returned false.
     * Returns "" if currently ready.
     */
    String getNotReadyReason();

    /**
     * Number of reversals currently waiting to drain.
     */
    int getPendingReversalCount();

    // ---- Transaction commands -------------------------------------------------

    /**
     * Perform a sale (cash withdrawal). Drives card-read UI internally.
     *
     * @param amountCents      transaction amount in cents (must be &gt; 0)
     * @param surchargeCents   surcharge in cents (must be &ge; 0)
     * @param accountType      "checking" | "savings" | "credit"
     * @param callback         fires exactly once on completion
     */
    void startSale(long amountCents, long surchargeCents, String accountType, TransactionCallback callback);

    /**
     * Perform a balance inquiry. Drives card-read UI internally.
     *
     * @param accountType      "checking" | "savings" | "credit"
     * @param callback         fires exactly once on completion
     */
    void startBalanceInquiry(String accountType, TransactionCallback callback);

    /**
     * Trigger a reversal. Reason is logged but does not drive UI.
     *
     * @param reason           operator-supplied reason ("merchant_cancel", "host_timeout", etc.)
     * @param callback         fires exactly once on completion
     */
    void startReversal(String reason, OperationCallback callback);

    /**
     * Request a host-totals (batch close) operation.
     *
     * @param reset            true to reset totals after retrieval (closing the batch)
     * @param callback         fires exactly once on completion, with totals on success
     */
    void startSettlement(boolean reset, SettlementCallback callback);

    // ---- Callbacks ------------------------------------------------------------

    /**
     * Result of a sale or balance inquiry.
     */
    interface TransactionCallback {
        void onApproved(TransactionResult result);
        void onDeclined(String responseCode, String responseMessage, boolean retainCard);
        void onError(String errorCode, String errorMessage);
    }

    /**
     * Result of a simple operation (reversal) — succeed/fail only.
     */
    interface OperationCallback {
        void onSuccess(String message);
        void onError(String errorCode, String errorMessage);
    }

    /**
     * Result of a settlement.
     */
    interface SettlementCallback {
        void onSettled(SettlementResult result);
        void onError(String errorCode, String errorMessage);
    }

    /**
     * Approved transaction result. Optional fields use {@code null} or {@code 0}
     * when not present in the host response.
     */
    final class TransactionResult {
        public final String responseCode;
        public final String referenceNumber;
        public final String authCode;
        public final String authDate;
        public final String authTime;
        public final long   accountBalanceCents;
        public final long   availableBalanceCents;
        public final String displayMessage;

        public TransactionResult(String responseCode, String referenceNumber, String authCode,
                                 String authDate, String authTime,
                                 long accountBalanceCents, long availableBalanceCents,
                                 String displayMessage) {
            this.responseCode = nz(responseCode);
            this.referenceNumber = nz(referenceNumber);
            this.authCode = nz(authCode);
            this.authDate = nz(authDate);
            this.authTime = nz(authTime);
            this.accountBalanceCents = accountBalanceCents;
            this.availableBalanceCents = availableBalanceCents;
            this.displayMessage = nz(displayMessage);
        }
        private static String nz(String s) { return s == null ? "" : s; }
    }

    /**
     * Approved settlement result.
     */
    final class SettlementResult {
        public final int  withdrawalCount;
        public final int  balanceInquiryCount;
        public final long totalCashDispensedCents;
        public final long totalSurchargesCents;

        public SettlementResult(int withdrawalCount, int balanceInquiryCount,
                                long totalCashDispensedCents, long totalSurchargesCents) {
            this.withdrawalCount = withdrawalCount;
            this.balanceInquiryCount = balanceInquiryCount;
            this.totalCashDispensedCents = totalCashDispensedCents;
            this.totalSurchargesCents = totalSurchargesCents;
        }
    }
}
