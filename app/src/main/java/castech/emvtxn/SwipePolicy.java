package castech.emvtxn;

/**
 * Whether a swiped card may proceed (MSR-01, 6.2.14). Pure Java, no Android.
 *
 * <p>Swipes exist for cards whose chip cannot be read. The service code on Track 2 (the three
 * digits after the expiry) says whether the card has a chip at all — first digit 2 or 6 — and a
 * chip card is accepted by swipe only after a failed chip read in the same transaction
 * (technical fallback; it keeps chip liability off the merchant). Cards with no chip are
 * accepted at once. {@code swipe_enabled=false} refuses every swipe (a fleet-wide kill switch).
 * An unparseable service code is treated as "no chip": a short or odd stripe is still a card
 * the processor can judge.
 */
public final class SwipePolicy {

    public enum Decision { ACCEPT, INSERT_CHIP, NOT_ACCEPTED, UNREADABLE }

    private SwipePolicy() {}

    public static Decision decide(String hostTrack2, boolean swipeEnabled, boolean chipFailedThisTxn) {
        if (hostTrack2 == null || hostTrack2.isEmpty()) return Decision.UNREADABLE;
        if (!swipeEnabled) return Decision.NOT_ACCEPTED;
        if (hasChip(serviceCode(hostTrack2)) && !chipFailedThisTxn) return Decision.INSERT_CHIP;
        return Decision.ACCEPT;
    }

    /** The three-digit service code after the four-digit expiry, or null when the track is too short. */
    public static String serviceCode(String hostTrack2) {
        if (hostTrack2 == null) return null;
        int eq = hostTrack2.indexOf('=');
        if (eq < 0) return null;
        int start = eq + 1 + 4;                       // '=' YYMM
        if (start + 3 > hostTrack2.length()) return null;
        String sc = hostTrack2.substring(start, start + 3);
        for (int i = 0; i < 3; i++) if (!Character.isDigit(sc.charAt(i))) return null;
        return sc;
    }

    public static boolean hasChip(String serviceCode) {
        if (serviceCode == null || serviceCode.isEmpty()) return false;
        char c = serviceCode.charAt(0);
        return c == '2' || c == '6';
    }

    /** The PAN digits from a host-form track (";PAN=…?"). */
    public static String pan(String hostTrack2) {
        if (hostTrack2 == null) return "";
        int start = hostTrack2.startsWith(";") ? 1 : 0;
        int eq = hostTrack2.indexOf('=');
        return eq > start ? hostTrack2.substring(start, eq) : "";
    }

    /** First six and last four; everything else '*'. For the screen and the receipt. */
    public static String maskedPan(String pan) {
        if (pan == null || pan.length() < 10) return pan == null ? "" : pan;
        StringBuilder b = new StringBuilder(pan.length());
        for (int i = 0; i < pan.length(); i++) b.append(i < 6 || i >= pan.length() - 4 ? pan.charAt(i) : '*');
        return b.toString();
    }

    public static String lastFour(String pan) {
        return pan == null || pan.length() < 4 ? "" : pan.substring(pan.length() - 4);
    }
}
