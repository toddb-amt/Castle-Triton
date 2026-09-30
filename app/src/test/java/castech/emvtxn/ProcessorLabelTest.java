package castech.emvtxn;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 6.2.9: the terminal never shows a processor's name. The Admin screen shows a short code
 * instead (decision 2026-09-30). The CasHUB parameter values are unchanged — this is a
 * display mapping only.
 */
public class ProcessorLabelTest {

    @Test
    public void knownProcessors_mapToTheirCodes() {
        assertEquals("E1", ProcessorLabel.codeFor("EFX"));
        assertEquals("S1", ProcessorLabel.codeFor("SWITCH_COMMERCE"));
        assertEquals("D1", ProcessorLabel.codeFor("DNS"));
        assertEquals("F1", ProcessorLabel.codeFor("FIS"));
        assertEquals("C1", ProcessorLabel.codeFor("CARDTRONICS"));
    }

    @Test
    public void lookupIsCaseAndWhitespaceInsensitive() {
        assertEquals("E1", ProcessorLabel.codeFor(" efx "));
        assertEquals("S1", ProcessorLabel.codeFor("switch_commerce"));
    }

    @Test
    public void unknownOrMissing_isNeutral_neverTheRawName() {
        assertEquals("--", ProcessorLabel.codeFor(null));
        assertEquals("--", ProcessorLabel.codeFor(""));
        assertEquals("--", ProcessorLabel.codeFor("SOMETHING_NEW"));
    }
}
