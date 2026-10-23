package castech.emvtxn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** TIP-02: tip_presets is parsed like the other keys; an invalid value is logged and the previous value kept. */
public class KmsConfigStoreTipPresetsTest {

    private TipPresets before;

    @Before public void remember() { before = GlobalPara.atmTipPresets; GlobalPara.atmTipPresets = TipPresets.DEFAULT; }
    @After public void restore() { GlobalPara.atmTipPresets = before; }

    @Test
    public void theDefault_isTenFifteenTwenty() {
        assertEquals("10,15,20", GlobalPara.atmTipPresets.toParam());
    }

    @Test
    public void aValidValue_isApplied() {
        KmsConfigStore.applyPayload("tip_presets=$1,$5,$10\n");
        assertTrue(GlobalPara.atmTipPresets.dollars);
        assertEquals("$1,$5,$10", GlobalPara.atmTipPresets.toParam());
        KmsConfigStore.applyPayload("tip_presets= 15, 18, 20 \n");
        assertEquals("15,18,20", GlobalPara.atmTipPresets.toParam());
    }

    @Test
    public void anInvalidValue_keepsThePreviousOne() {
        KmsConfigStore.applyPayload("tip_presets=5,10,20\n");
        KmsConfigStore.applyPayload("tip_presets=10,15\n");
        assertEquals("5,10,20", GlobalPara.atmTipPresets.toParam());
        KmsConfigStore.applyPayload("tip_presets=\n");
        assertEquals("5,10,20", GlobalPara.atmTipPresets.toParam());
    }

    @Test
    public void absent_leavesItAlone() {
        KmsConfigStore.applyPayload("tip_presets=$1,$5,$10\n");
        KmsConfigStore.applyPayload("tips_enabled=true\n");
        assertEquals("$1,$5,$10", GlobalPara.atmTipPresets.toParam());
    }
}
