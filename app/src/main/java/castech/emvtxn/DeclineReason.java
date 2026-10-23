package castech.emvtxn;

/**
 * RCPT-03 (6.2.20): the one decline text the screen, the printed slip, the journal row (and so the
 * reprint and the MyView push) share. Bench 2026-10-22: a 76 arrived with a whitespace-only host
 * message, the code treated "not empty" as "has a message", and the slip printed "Reason:" followed
 * by nothing. Rule: the host's message counts only when it says something; otherwise the code's
 * description below; 76 is the processor's PIN key sync answer (HOST-15), not "Invalid account".
 * Pure Java, pinned by DeclineReasonTest.
 */
public final class DeclineReason {

    private DeclineReason() {}

    /** The text to show for a decline, or null when there is neither a message nor a code. */
    public static String text(String hostMessage, String responseCode) {
        if (hostMessage != null && !hostMessage.trim().isEmpty()) return hostMessage.trim();
        return describe(responseCode);
    }

    /** The terminal's description of a response code; "Declined (code NN)" for one it does not know; null for blank. */
    public static String describe(String responseCode) {
        if (responseCode == null) return null;
        String code = responseCode.trim();
        if (code.isEmpty()) return null;
        switch (code) {
            case "00": return "Approved";
            case "01": return "Refer to card issuer";
            case "03": return "Invalid merchant";
            case "04": return "Pick up card";
            case "05": return "Do not honor";
            case "06": return "Error";
            case "07": return "Pick up card - special";
            case "12": return "Invalid transaction";
            case "13": return "Invalid amount";
            case "14": return "Invalid card number";
            case "30": return "Format error";
            case "41": return "Lost card - pick up";
            case "43": return "Stolen card - pick up";
            case "51": return "Insufficient funds";
            case "54": return "Expired card";
            case "55": return "Incorrect PIN";
            case "57": return "Transaction not permitted";
            case "58": return "Transaction not allowed";
            case "61": return "Exceeds withdrawal limit";
            case "62": return "Restricted card";
            case "63": return "Security violation";
            case "65": return "Exceeds withdrawal frequency";
            case "75": return "PIN tries exceeded";
            case "76": return "PIN key sync";
            case "78": return "No account";
            case "80": return "Invalid date";
            case "82": return "CVV validation error";
            case "83": return "Cannot verify PIN";
            case "85": return "No reason to decline";
            case "86": return "Cannot verify PIN";
            case "89": return "Invalid terminal ID";
            case "91": return "Issuer unavailable";
            case "92": return "Routing error";
            case "94": return "Duplicate transaction";
            case "96": return "System malfunction";
            default:   return "Declined (code " + code + ")";
        }
    }
}
