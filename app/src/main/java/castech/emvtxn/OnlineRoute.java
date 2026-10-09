package castech.emvtxn;

/**
 * Which online path a card-present transaction takes once the card has been read (TAP-01, 6.2.12;
 * MSR-01, 6.2.14). Pure, so the routing is unit-tested — the branch it replaced had sent every
 * tap to a terminal-made "91" for months because the host send sat behind a sample-app checkbox.
 */
public enum OnlineRoute {
    CONTACTLESS_HOST,
    CONTACT_HOST,
    QUICK_CHIP_CONTACT,
    /** A swipe, with swipes enabled: Track 2 to the host in Field 6, no EMV data (MSR-01). */
    MSR_HOST,
    /** No host send: ends at the terminal with a terminal-made decline. */
    NO_HOST_PATH;

    /** Pre-6.2.14 form: swipes have no host path. */
    public static OnlineRoute of(byte entryMode, boolean quickChip) {
        return of(entryMode, quickChip, false);
    }

    public static OnlineRoute of(byte entryMode, boolean quickChip, boolean swipeEnabled) {
        if (entryMode == GlobalDef.d_ENTRY_MODE_CL) return CONTACTLESS_HOST;
        if (entryMode == GlobalDef.d_ENTRY_MODE_CT) return quickChip ? QUICK_CHIP_CONTACT : CONTACT_HOST;
        if (entryMode == GlobalDef.d_ENTRY_MODE_MSR && swipeEnabled) return MSR_HOST;
        return NO_HOST_PATH;
    }
}
