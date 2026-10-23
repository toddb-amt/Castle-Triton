package castech.emvtxn;

/**
 * TIP-02 (6.2.20): the three suggested tips, from the CasHUB key {@code tip_presets}. Bare numbers
 * are whole percentages of the sale ({@code 10,15,20}, the default); a leading dollar sign makes
 * them fixed amounts ({@code $1,$5,$10}, cents allowed: {@code $2.50}). Exactly three, all of one
 * kind, in the order given; the tip screen shows them left to right. Pure Java, pinned by
 * TipPresetsTest. {@link #parse} throws with a reason; {@link KmsConfigStore} catches it, logs it
 * and keeps the previous value, like every other key.
 */
public final class TipPresets {

    public static final String KEY = "tip_presets";
    public static final int COUNT = 3;
    public static final TipPresets DEFAULT = new TipPresets(false, new long[] {10, 15, 20});

    /** False: {@link #values} are percentages. True: they are cents. */
    public final boolean dollars;
    /** Whole percentages 1..100, or cents 1..99999. Always {@link #COUNT} of them. */
    public final long[] values;

    private TipPresets(boolean dollars, long[] values) {
        this.dollars = dollars;
        this.values = values.clone();
    }

    public int size() { return values.length; }

    public static TipPresets parse(String raw) {
        if (raw == null) throw new IllegalArgumentException(KEY + ": missing");
        String trimmed = raw.trim();
        String[] parts = trimmed.split(",", -1);
        if (parts.length != COUNT) {
            throw new IllegalArgumentException(KEY + ": expected " + COUNT + " values separated by commas, got "
                    + parts.length + " in \"" + trimmed + "\"");
        }
        Boolean kind = null;
        long[] out = new long[COUNT];
        for (int i = 0; i < COUNT; i++) {
            String p = parts[i].trim();
            if (p.isEmpty()) throw new IllegalArgumentException(KEY + ": value " + (i + 1) + " is blank in \"" + trimmed + "\"");
            boolean isDollar = p.startsWith("$");
            if (kind == null) kind = isDollar;
            else if (kind != isDollar) {
                throw new IllegalArgumentException(KEY + ": percentages and dollar amounts are mixed in \"" + trimmed + "\"");
            }
            out[i] = isDollar ? parseDollars(p) : parsePercent(p);
        }
        return new TipPresets(kind, out);
    }

    private static long parsePercent(String p) {
        String digits = p.endsWith("%") ? p.substring(0, p.length() - 1).trim() : p;
        if (!digits.matches("\\d{1,3}")) {
            throw new IllegalArgumentException(KEY + ": \"" + p + "\" is not a whole percentage (1-100)");
        }
        int pct = Integer.parseInt(digits);
        if (pct < 1 || pct > 100) throw new IllegalArgumentException(KEY + ": \"" + p + "\" is outside 1-100%");
        return pct;
    }

    /** "$1", "$2.50": one to three dollar digits, up to two cent digits. Parsed as integers, never as a double. */
    private static long parseDollars(String p) {
        String amount = p.substring(1).trim();
        if (!amount.matches("\\d{1,3}(\\.\\d{1,2})?")) {
            throw new IllegalArgumentException(KEY + ": \"" + p + "\" is not a dollar amount ($1, $2.50, up to $999.99)");
        }
        int dot = amount.indexOf('.');
        long dollarsPart = Long.parseLong(dot < 0 ? amount : amount.substring(0, dot));
        String centsText = dot < 0 ? "00" : (amount.substring(dot + 1) + "0").substring(0, 2);
        long cents = dollarsPart * 100 + Long.parseLong(centsText);
        if (cents < 1) throw new IllegalArgumentException(KEY + ": \"" + p + "\" must be at least $0.01");
        return cents;
    }

    /** The tip for option {@code i}: a percentage of the sale (half-up at the cent) or the fixed amount. */
    public long tipFor(long saleCents, int i) {
        return dollars ? values[i] : TipQuote.percentOf(saleCents, (int) values[i]);
    }

    /** The percentage of option {@code i}; 0 for a dollar preset. */
    public int percent(int i) { return dollars ? 0 : (int) values[i]; }

    /** The button text: "15%" or "$5" / "$2.50". */
    public String label(int i) { return dollars ? dollarLabel(values[i]) : values[i] + "%"; }

    /** For the Admin card and logs: "10%, 15%, 20%" or "$1, $5, $10". */
    public String describe() { return join(", ", true); }

    /** The value as CasHUB would carry it: "10,15,20" or "$1,$5,$10". */
    public String toParam() { return join(",", false); }

    private String join(String sep, boolean withPercentSign) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(sep);
            if (dollars) sb.append(dollarLabel(values[i]));
            else sb.append(values[i]).append(withPercentSign ? "%" : "");
        }
        return sb.toString();
    }

    /** "$5" for whole dollars, "$2.50" otherwise. */
    static String dollarLabel(long cents) {
        return cents % 100 == 0 ? "$" + (cents / 100) : "$" + Money.dollars(cents);
    }
}
