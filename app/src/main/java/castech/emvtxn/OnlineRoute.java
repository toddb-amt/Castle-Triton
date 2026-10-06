package castech.emvtxn;

/**
 * Which online path a card takes once the kernel has said "go online". Pure — the
 * mapping is pinned by OnlineRouteTest.
 *
 * <p><b>Why this exists (TAP-01, 6.2.12):</b> the chain in MainActivity's transaction
 * thread read
 * <pre>
 *   if (QuickChip &amp;&amp; contact) { ...contactless host send... }
 *   else if (contact)          { ...contact host send... }
 *   else                       { decline "91 / Host service not available" }
 * </pre>
 * QuickChip is the sample app's checkbox and is never ticked, so the contactless send
 * could not run: every tapped card dropped into the last branch and was declined at the
 * terminal with a 91 the processor never sent. Only an inserted chip reached the host.
 */
public enum OnlineRoute {
    /** Tap: send to the host as a contactless transaction. */
    CONTACTLESS_HOST,
    /** Inserted chip: send to the host, then complete the chip transaction. */
    CONTACT_HOST,
    /**
     * Inserted chip with the sample app's QuickChip box ticked. Kept exactly as it was
     * (QuickChip completion, then the contactless-style send) — not used by the ATM flow.
     */
    QUICK_CHIP_CONTACT,
    /** Swipe, or anything unrecognised: there is no host path; decline at the terminal. */
    NO_HOST_PATH;

    /**
     * @param entryMode one of {@code GlobalDef.d_ENTRY_MODE_*}
     * @param quickChip {@code GlobalPara.isQuickChipTransaction}
     */
    public static OnlineRoute of(byte entryMode, boolean quickChip) {
        if (entryMode == GlobalDef.d_ENTRY_MODE_CL) return CONTACTLESS_HOST;
        if (entryMode == GlobalDef.d_ENTRY_MODE_CT) return quickChip ? QUICK_CHIP_CONTACT : CONTACT_HOST;
        return NO_HOST_PATH;
    }
}
