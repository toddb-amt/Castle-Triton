package castech.emvtxn;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** T1: tips_enabled is a boolean exactly like use_flat_fee; invalid values leave it alone. */
public class KmsConfigStoreTipsTest {

    private boolean before;

    @Before public void remember() { before = GlobalPara.atmTipsEnabled; GlobalPara.atmTipsEnabled = false; }
    @After public void restore() { GlobalPara.atmTipsEnabled = before; }

    @Test
    public void defaultIsOff() {
        assertFalse(GlobalPara.atmTipsEnabled);
    }

    @Test
    public void trueAndOne_turnItOn() {
        KmsConfigStore.applyPayload("tips_enabled=true\n");
        assertTrue(GlobalPara.atmTipsEnabled);
        GlobalPara.atmTipsEnabled = false;
        KmsConfigStore.applyPayload("tips_enabled=1\n");
        assertTrue(GlobalPara.atmTipsEnabled);
        KmsConfigStore.applyPayload("tips_enabled= TRUE \n");
        assertTrue(GlobalPara.atmTipsEnabled);
    }

    @Test
    public void falseAndZero_turnItOff() {
        GlobalPara.atmTipsEnabled = true;
        KmsConfigStore.applyPayload("tips_enabled=false\n");
        assertFalse(GlobalPara.atmTipsEnabled);
        GlobalPara.atmTipsEnabled = true;
        KmsConfigStore.applyPayload("tips_enabled=0\n");
        assertFalse(GlobalPara.atmTipsEnabled);
    }

    @Test
    public void anInvalidValue_isIgnored_previousValueStands() {
        GlobalPara.atmTipsEnabled = true;
        KmsConfigStore.applyPayload("tips_enabled=maybe\n");
        assertTrue(GlobalPara.atmTipsEnabled);
    }

    @Test
    public void absent_leavesItAlone() {
        GlobalPara.atmTipsEnabled = true;
        KmsConfigStore.applyPayload("use_flat_fee=true\n");
        assertTrue(GlobalPara.atmTipsEnabled);
    }
}
