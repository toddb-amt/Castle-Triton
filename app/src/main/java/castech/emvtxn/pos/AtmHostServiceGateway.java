package castech.emvtxn.pos;

import android.util.Log;

import castech.emvtxn.GlobalPara;
import castech.emvtxn.atm.host.AtmHostService;
import castech.emvtxn.atm.host.AtmTransactionManager;
import castech.emvtxn.atm.host.HostTotalsResponse;

/**
 * Production implementation of {@link PosTerminalGateway} backed by the
 * existing {@link AtmHostService}. Thin adapter — no business logic.
 *
 * <p><b>Scope note (Phase 7):</b>
 * <ul>
 *   <li>{@link #startReversal} and {@link #startSettlement} are fully wired.</li>
 *   <li>{@link #startSale} and {@link #startBalanceInquiry} return
 *       {@code not_supported} for now — they require a POS-driven card-read
 *       trigger that is the subject of Phase 7b. The existing customer-driven
 *       flow drives card reading via Fragment_page_transaction's button
 *       handler; in POS mode the proxy supplies the amount and we need to
 *       drive that flow programmatically.</li>
 * </ul>
 *
 * <p>Threading: gateway methods are called from the OkHttp dispatcher thread
 * (via PosCommandDispatcher). They marshal to the host service's internal
 * executor as appropriate.
 */
public final class AtmHostServiceGateway implements PosTerminalGateway {

    private static final String TAG = "AtmHostServiceGateway";

    private final AtmHostService hostService;
    private final UiBridge ui;

    /** One POS reversal at a time — see {@link #startReversal}. */
    private final java.util.concurrent.atomic.AtomicBoolean reversalInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Production constructor with UI navigation support (for sale + balance_inquiry).
     */
    public AtmHostServiceGateway(AtmHostService hostService, UiBridge ui) {
        if (hostService == null) throw new IllegalArgumentException("hostService required");
        this.hostService = hostService;
        this.ui = ui;  // may be null in test contexts where only reversal/settlement are exercised
    }

    /**
     * No-UI constructor (legacy / settlement+reversal only). Calls to startSale or
     * startBalanceInquiry will fail fast since they need UI to drive card-read.
     */
    public AtmHostServiceGateway(AtmHostService hostService) {
        this(hostService, null);
    }

    // ---- Status accessors -----------------------------------------------------

    @Override
    public boolean isReady() {
        // Deliberately NO isConnected() conjunct: the processor socket is opened
        // per-transaction (keep-alive off — the host closes it after each txn), so
        // at idle there is never a live socket and a connection check would reject
        // every POS-driven transaction on a healthy terminal. The transaction path
        // dials on demand in both keep-alive modes; a real connectivity failure
        // surfaces from the transaction itself as host_unreachable.
        return hostService.isInitialized()
            && hostService.hasValidWorkingKey()
            && !hostService.isTransactionInProgress()
            && !GlobalPara.atmTransactionInProgress;
    }

    @Override
    public String getNotReadyReason() {
        if (!hostService.isInitialized())          return "host service not initialized";
        if (!hostService.hasValidWorkingKey())     return "no working key loaded";
        if (hostService.isTransactionInProgress()) return "transaction in progress";
        if (GlobalPara.atmTransactionInProgress)   return "customer transaction in progress";
        return "";
    }

    @Override
    public int getPendingReversalCount() {
        return hostService.getPendingReversalCount();
    }

    // ---- Sale / Balance Inquiry (Phase 7b) ------------------------------------

    @Override
    public void startSale(long amountCents, long surchargeCents, String accountType,
                          TransactionCallback callback) {
        startCardDrivenTransaction(false, amountCents, surchargeCents, accountType, callback);
    }

    @Override
    public void startBalanceInquiry(String accountType, TransactionCallback callback) {
        startCardDrivenTransaction(true, 0L, 0L, accountType, callback);
    }

    /**
     * Drives a POS-initiated transaction (sale or balance inquiry) through the
     * existing customer-flow plumbing:
     * <ol>
     *   <li>Set the GlobalPara fields that {@code Fragment_page_transaction}
     *       reads to know the amount/mode (matches the customer-driven path
     *       where the amount-selection fragment writes these fields).</li>
     *   <li>Arm {@link PosTransactionObserver} with a bridge callback.</li>
     *   <li>Navigate to the TRANSACTION page on the UI thread —
     *       Fragment_page_transaction.onResume auto-triggers card detection.</li>
     *   <li>When the existing card-read + host-call completes, MainActivity's
     *       atmTransactionEventListener fires; its hooks notify
     *       PosTransactionObserver, which invokes our bridge, which builds the
     *       TransactionResult and fires the executor's callback.</li>
     * </ol>
     */
    private void startCardDrivenTransaction(boolean balanceInquiry, long amountCents,
                                             long surchargeCents, String accountType,
                                             TransactionCallback callback) {
        if (ui == null) {
            callback.onError(PosWire.ERR_INTERNAL,
                    "gateway constructed without UI bridge — cannot drive card read");
            return;
        }
        if (!hostService.isInitialized()) {
            callback.onError(PosWire.ERR_INTERNAL, "host service not initialized");
            return;
        }
        if (PosTransactionObserver.isArmed()) {
            callback.onError(PosWire.ERR_TERMINAL_BUSY,
                    "another POS transaction is already in progress");
            return;
        }
        // The cashier and the customer screen are mutually exclusive drivers. The
        // host-service flag only goes true once the request is being sent; during
        // card detection and PIN entry it is still false, so a sale arriving then
        // overwrote the customer's amount mid-flow and this callback received an
        // approval for a transaction it never initiated. MainActivity's flag covers
        // the whole customer flow from the button press to the thread's exit.
        if (GlobalPara.atmTransactionInProgress || hostService.isTransactionInProgress()) {
            callback.onError(PosWire.ERR_TERMINAL_BUSY,
                    "a transaction is already in progress at the terminal");
            return;
        }

        // Translate POS wire account-type → existing GlobalPara constant (matches
        // what the customer-driven account-selection flow sets).
        int acctTypeCode;
        switch (accountType == null ? "" : accountType) {
            case "savings": acctTypeCode = GlobalPara.ATM_ACCOUNT_SAVINGS;  break;
            case "credit":  acctTypeCode = GlobalPara.ATM_ACCOUNT_CREDIT;   break;
            case "checking":
            default:        acctTypeCode = GlobalPara.ATM_ACCOUNT_CHECKING; break;
        }

        // Populate GlobalPara so Fragment_page_transaction's existing flow finds the
        // amount it needs. Strings + cents match the customer-mode amount-selection
        // fragment's writes.
        GlobalPara.atmBalanceInquiryMode = balanceInquiry;
        GlobalPara.atmAccountType = acctTypeCode;
        final long appliedSurchargeCents;
        final long appliedTotalCents;
        if (balanceInquiry) {
            appliedSurchargeCents = 0L;
            appliedTotalCents = 0L;
            GlobalPara.atmSelectedAmount = "0.00";
            GlobalPara.atmFee = "0.00";
            GlobalPara.atmTotal = "0.00";
            GlobalPara.strAmount = "0";
        } else {
            // D7: the surcharge is the terminal's (fee configuration), exactly as for a
            // walk-up. The register's "surcharge" is advisory only. Chip amount = total,
            // like a walk-up (closes EMV-01).
            PosSaleFee fee = PosSaleFee.resolve(amountCents, surchargeCents,
                    GlobalPara.atmUseFlatFee, GlobalPara.atmFlatFeeAmount, GlobalPara.atmPercentageFee);
            if (fee.posSurchargeDiffers()) {
                Log.w(TAG, "POS sent surcharge=" + surchargeCents + " cents; terminal fee config governs: "
                        + fee.feeCents + " cents (reply carries the applied value)");
            }
            appliedSurchargeCents = fee.feeCents;
            appliedTotalCents = fee.totalCents;
            GlobalPara.atmSelectedAmount = castech.emvtxn.Money.dollars(fee.amountCents);
            GlobalPara.atmFee = castech.emvtxn.Money.dollars(fee.feeCents);
            GlobalPara.atmTotal = castech.emvtxn.Money.dollars(fee.totalCents);
            GlobalPara.strAmount = fee.chipAmountCents();
        }

        Log.d(TAG, "POS txn arming: balanceInquiry=" + balanceInquiry
                + " amt=" + amountCents + " surcharge(applied)=" + appliedSurchargeCents
                + " total=" + appliedTotalCents + " acct=" + acctTypeCode);

        // Arm the observer. The bridge below fires the executor's TransactionCallback
        // when the existing listener path completes.
        PosTransactionObserver.arm(new PosTransactionObserver.Callback() {
            @Override
            public void onApproved(String responseCode, String referenceNumber,
                                    String authDate, String authTime,
                                    long acctBal, long availBal, String displayMessage) {
                callback.onApproved(new PosTerminalGateway.TransactionResult(
                        responseCode, referenceNumber, /* authCode */ "",
                        authDate, authTime, acctBal, availBal, displayMessage,
                        appliedSurchargeCents, appliedTotalCents));
            }
            @Override
            public void onDeclined(String responseCode, String responseMessage, boolean retainCard) {
                callback.onDeclined(responseCode, responseMessage, retainCard);
            }
            @Override
            public void onError(String error) {
                callback.onError(PosWire.ERR_HOST_UNREACHABLE, error);
            }
        });

        // Kick the UI flow — this hops to the main thread and navigates to the
        // transaction page, which auto-starts card detection.
        ui.runOnUi(ui::navigateToTransactionPage);
    }

    // ---- Reversal (no card read required) -------------------------------------

    @Override
    public void startReversal(String reason, OperationCallback callback) {
        if (!hostService.isInitialized()) {
            callback.onError(PosWire.ERR_INTERNAL, "host service not initialized");
            return;
        }
        // sendReversal() returns silently when there is nothing to reverse (fresh
        // boot, or the last record already drained). The wrapper below would then
        // never fire and never restore the original listener: the POS caller timed
        // out, and the NEXT onError from any host operation — a key-download
        // failure, a health check — fired this stale callback with an unrelated
        // error for a dead flowId. Answer immediately instead.
        if (!hostService.hasReversibleTransaction()) {
            callback.onError(PosWire.ERR_INVALID_REQUEST, "no transaction to reverse");
            return;
        }
        // One reversal at a time: a second wrapper installed over the first would
        // capture the first wrapper as its "original", restore the wrong listener,
        // and drop whichever result arrived second. Released when the result fires.
        if (!reversalInFlight.compareAndSet(false, true)) {
            callback.onError(PosWire.ERR_TERMINAL_BUSY, "a reversal is already in progress");
            return;
        }

        // Install a temporary event listener that bridges the host result back
        // to our callback. Restore the original listener after the result fires.
        final AtmHostService.AtmEventListener original = hostService.getEventListener();
        final java.util.concurrent.atomic.AtomicBoolean fired = new java.util.concurrent.atomic.AtomicBoolean(false);

        hostService.setEventListener(new AtmHostService.AtmEventListener() {
            @Override public void onProgress(String message) {
                if (original != null) original.onProgress(message);
            }
            @Override public void onError(String error) {
                if (fired.compareAndSet(false, true)) {
                    hostService.setEventListener(original);
                    reversalInFlight.set(false);
                    callback.onError(PosWire.ERR_HOST_UNREACHABLE, error);
                }
            }
            @Override public void onReversalComplete(boolean success) {
                if (fired.compareAndSet(false, true)) {
                    hostService.setEventListener(original);
                    reversalInFlight.set(false);
                    if (success) callback.onSuccess("reversal complete");
                    else callback.onError(PosWire.ERR_HOST_UNREACHABLE, "reversal failed");
                }
            }
            // The remaining callbacks aren't relevant to a reversal — pass through to the
            // original listener so other concurrent observers (health check, key load,
            // host totals) keep working.
            @Override public void onTransactionApproved(String responseCode, String referenceNumber,
                    String authDate, String authTime, long accountBalanceCents,
                    long availableBalanceCents, String displayMessage) {
                if (original != null) original.onTransactionApproved(responseCode, referenceNumber,
                        authDate, authTime, accountBalanceCents, availableBalanceCents, displayMessage);
            }
            @Override public void onTransactionDeclined(String responseCode, String responseMessage, boolean retainCard) {
                if (original != null) original.onTransactionDeclined(responseCode, responseMessage, retainCard);
            }
            @Override public void onBalanceReceived(String responseCode, long accountBalanceCents, long availableBalanceCents) {
                if (original != null) original.onBalanceReceived(responseCode, accountBalanceCents, availableBalanceCents);
            }
            @Override public void onKeysLoaded(String keyCheckValue) {
                if (original != null) original.onKeysLoaded(keyCheckValue);
            }
            @Override public void onHealthCheckResult(boolean success) {
                if (original != null) original.onHealthCheckResult(success);
            }
            @Override public void onHostTotalsReceived(HostTotalsResponse response) {
                if (original != null) original.onHostTotalsReceived(response);
            }
        });

        try {
            hostService.sendReversal(reason);
        } catch (RuntimeException e) {
            // e.g. RejectedExecutionException when the host executor was shut down
            // under us. Nothing will fire: restore the listener and answer now.
            if (fired.compareAndSet(false, true)) {
                hostService.setEventListener(original);
                reversalInFlight.set(false);
                callback.onError(PosWire.ERR_INTERNAL, "reversal could not be started: " + e.getMessage());
            }
        }
    }

    // ---- Settlement (no card read required) -----------------------------------

    @Override
    public void startSettlement(boolean reset, SettlementCallback callback) {
        if (!hostService.isInitialized()) {
            callback.onError(PosWire.ERR_INTERNAL, "host service not initialized");
            return;
        }

        hostService.requestHostTotals(reset, new AtmTransactionManager.HostTotalsCallback() {
            @Override
            public void onHostTotalsReceived(HostTotalsResponse response) {
                if (response == null) {
                    callback.onError(PosWire.ERR_INTERNAL, "null host totals response");
                    return;
                }
                if (!response.isSuccess()) {
                    callback.onError(PosWire.ERR_HOST_UNREACHABLE,
                            response.getErrorMessage() == null ? "host totals failed" : response.getErrorMessage());
                    return;
                }
                SettlementResult result = new SettlementResult(
                        response.getWithdrawalCount(),
                        response.getBalanceInquiryCount(),
                        // HostTotalsResponse stores cents directly
                        response.getTotalCashDispensed(),
                        response.getTotalSurcharges());
                callback.onSettled(result);
            }
            @Override
            public void onError(String error) {
                callback.onError(PosWire.ERR_HOST_UNREACHABLE, error == null ? "settlement error" : error);
            }
        });
    }

    /**
     * Minimal UI surface the gateway needs to drive a card-read flow. Production
     * impl wraps MainActivity; tests can supply a stub.
     */
    public interface UiBridge {
        /** Post the given runnable to the UI thread. */
        void runOnUi(Runnable r);

        /** Navigate to the TRANSACTION page; equivalent to GlobalPara.mainActivity.navigateToPage(d_PAGE_TRANSACTION). */
        void navigateToTransactionPage();
    }
}
