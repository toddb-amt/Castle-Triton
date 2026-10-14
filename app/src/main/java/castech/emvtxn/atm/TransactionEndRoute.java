package castech.emvtxn.atm;

/**
 * Where the transaction thread goes when it ends. Pure; pinned by TransactionEndRouteTest.
 *
 * PIN-02 (6.2.17): a Cancel on the PIN pad (chip, swipe or tap) ends the transaction without a
 * host answer exactly like the on-screen Cancel does, so it must return to the menu and never
 * show the receipt page, which would otherwise render its layout placeholders under a green check.
 * An approval always takes the receipt path, cancelled or not: money moved.
 */
public final class TransactionEndRoute {

    public enum Route { MAIN_MENU, RECEIPT, SAMPLE_BUTTONS }

    private TransactionEndRoute() {}

    /**
     * @param userCancelled  on-screen Cancel during card detection, or Cancel on the PIN pad
     * @param hostApproved   the host approved this transaction
     * @param atmMode        the app is running as an ATM (always true in production)
     * @param amountEntered  a withdrawal amount other than 0.00 was selected
     * @param balanceInquiry the transaction is a balance inquiry
     */
    public static Route decide(boolean userCancelled, boolean hostApproved, boolean atmMode,
                               boolean amountEntered, boolean balanceInquiry) {
        if (userCancelled && !hostApproved) return Route.MAIN_MENU;
        if (atmMode || amountEntered || balanceInquiry) return Route.RECEIPT;
        return Route.SAMPLE_BUTTONS;
    }
}
