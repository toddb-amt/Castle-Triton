package castech.emvtxn;

/**
 * 6.2.9: the terminal never shows a processor's name — the Admin screen shows a short code
 * (decision 2026-09-30). Display only: CasHUB keeps sending the real {@code processor_type}
 * values and the host layer keeps using them.
 */
public final class ProcessorLabel {

    private ProcessorLabel() {}

    public static final String UNKNOWN = "--";

    public static String codeFor(String processorType) {
        if (processorType == null) return UNKNOWN;
        switch (processorType.trim().toUpperCase(java.util.Locale.US)) {
            case "EFX":             return "E1";
            case "SWITCH_COMMERCE": return "S1";
            case "DNS":             return "D1";
            case "FIS":             return "F1";
            case "CARDTRONICS":     return "C1";
            default:                return UNKNOWN;
        }
    }
}
