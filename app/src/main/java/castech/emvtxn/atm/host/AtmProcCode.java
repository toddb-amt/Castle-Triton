package castech.emvtxn.atm.host;

/**
 * ATM transaction procedure codes (task #18).
 *
 * <p>Mirrors the proc-code dispatch in BlueVerse {@code fnAPP_MainTranProc}
 * (see {@code docs/HYOSUNG_BLUEVERSE_REVERSE_ENGINEERING_FINDINGS.md} §10).
 * Each value corresponds to a distinct transaction type the application can
 * dispatch.</p>
 *
 * <p>BlueVerse uses raw integers (0x0B, 0x0D, 0x14, etc.) stored at
 * {@code devCmn->[0x884]}. We use a Java enum for type safety; the
 * {@link #blueverseCode} field carries the integer for diagnostic alignment
 * with BlueVerse logs / reverse-engineered traces.</p>
 */
public enum AtmProcCode {

    /** Type 88 config download / Open procedure. BlueVerse code 0x0B. */
    OPEN(0x0B),

    /** Type 86 reversal. BlueVerse code 0x0D. */
    REVERSAL(0x0D),

    /** Type 85 customer transaction (withdrawal, balance inquiry, transfer). BlueVerse code 0x14. */
    TRANSACTION(0x14),

    /** Day totals (settlement/reconciliation). BlueVerse code 0x32. */
    DAY_TOTAL(0x32),

    /** Day totals alternate variant. BlueVerse code 0x33. */
    DAY_TOTAL_ALT(0x33),

    /** Add cash (operator action). BlueVerse code 0x34. */
    ADD_CASH(0x34),

    /** Per-customer totals. BlueVerse code 0x36. */
    CUSTOMER_TOTAL(0x36),

    /** Trial customer totals (test mode). BlueVerse code 0x37. */
    TRIAL_CUSTOMER_TOTAL(0x37),

    /** Set bill denominations. BlueVerse code 0x39. */
    SET_DENOMINATION(0x39),

    /** Type 89 health check. BlueVerse codes 0x3D/0x46/0x47/0x5A. */
    HEALTH_CHECK(0x46),

    /** All setup print (diagnostic). BlueVerse code 0x64. */
    SETUP_PRINT(0x64),

    /** Check cashing transaction. BlueVerse code 0x6E. */
    CHECK_CASHING(0x6E),

    /** Check cashing totals. BlueVerse code 0x78. */
    CHECK_CASHING_TOTAL(0x78),

    /** Check cashing trial totals. BlueVerse code 0x79. */
    CHECK_CASHING_TRIAL_TOTAL(0x79);

    /** Numeric proc code as used in BlueVerse {@code devCmn->[0x884]}. */
    public final int blueverseCode;

    AtmProcCode(int blueverseCode) {
        this.blueverseCode = blueverseCode;
    }

    /**
     * Returns the AtmProcCode for a BlueVerse-style integer code, or null
     * if no enum value maps to that integer.
     */
    public static AtmProcCode fromBlueverseCode(int code) {
        for (AtmProcCode pc : values()) {
            if (pc.blueverseCode == code) return pc;
        }
        return null;
    }
}
