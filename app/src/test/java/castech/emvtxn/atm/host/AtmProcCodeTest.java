package castech.emvtxn.atm.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Unit tests for {@link AtmProcCode} BlueVerse compatibility (task #11).
 */
public class AtmProcCodeTest {

    @Test
    public void openMatchesBlueverseCode0x0B() {
        assertEquals(0x0B, AtmProcCode.OPEN.blueverseCode);
        assertEquals(AtmProcCode.OPEN, AtmProcCode.fromBlueverseCode(0x0B));
    }

    @Test
    public void reversalMatchesBlueverseCode0x0D() {
        assertEquals(0x0D, AtmProcCode.REVERSAL.blueverseCode);
        assertEquals(AtmProcCode.REVERSAL, AtmProcCode.fromBlueverseCode(0x0D));
    }

    @Test
    public void transactionMatchesBlueverseCode0x14() {
        assertEquals(0x14, AtmProcCode.TRANSACTION.blueverseCode);
        assertEquals(AtmProcCode.TRANSACTION, AtmProcCode.fromBlueverseCode(0x14));
    }

    @Test
    public void healthCheckMatchesOneOfBlueverseCodes() {
        // BlueVerse uses 0x3D/0x46/0x47/0x5A for health check; we picked 0x46
        assertEquals(0x46, AtmProcCode.HEALTH_CHECK.blueverseCode);
    }

    @Test
    public void fromBlueverseCodeUnknownReturnsNull() {
        assertNull(AtmProcCode.fromBlueverseCode(0xFFFF));
        assertNull(AtmProcCode.fromBlueverseCode(0));
    }

    @Test
    public void allEnumValuesHaveUniqueBlueverseCodes() {
        AtmProcCode[] values = AtmProcCode.values();
        for (int i = 0; i < values.length; i++) {
            for (int j = i + 1; j < values.length; j++) {
                if (values[i].blueverseCode == values[j].blueverseCode) {
                    throw new AssertionError(
                            "Duplicate BlueVerse code 0x" + Integer.toHexString(values[i].blueverseCode)
                                    + " on " + values[i] + " and " + values[j]);
                }
            }
        }
    }
}
