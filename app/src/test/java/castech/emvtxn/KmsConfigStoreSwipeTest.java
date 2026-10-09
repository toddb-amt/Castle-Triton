package castech.emvtxn;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** T1: swipe_enabled is a boolean exactly like use_flat_fee; invalid values leave it alone. */
public class KmsConfigStoreSwipeTest {

    private boolean before;

    @Before public void remember() { before = GlobalPara.atmSwipeEnabled; GlobalPara.atmSwipeEnabled = true; }
    @After public void restore() { GlobalPara.atmSwipeEnabled = before; }

    @Test
    public void defaultIsOn() {
        assertTrue(GlobalPara.atmSwipeEnabled);
    }

    @Test
    public void trueAndOne_turnItOn() {
        GlobalPara.atmSwipeEnabled = false;
        KmsConfigStore.applyPayload("swipe_enabled=true\n");
        assertTrue(GlobalPara.atmSwipeEnabled);
        GlobalPara.atmSwipeEnabled = false;
        KmsConfigStore.applyPayload("swipe_enabled=1\n");
        assertTrue(GlobalPara.atmSwipeEnabled);
        KmsConfigStore.applyPayload("swipe_enabled= TRUE \n");
        assertTrue(GlobalPara.atmSwipeEnabled);
    }

    @Test
    public void falseAndZero_turnItOff() {
        GlobalPara.atmSwipeEnabled = true;
        KmsConfigStore.applyPayload("swipe_enabled=false\n");
        assertFalse(GlobalPara.atmSwipeEnabled);
        GlobalPara.atmSwipeEnabled = true;
        KmsConfigStore.applyPayload("swipe_enabled=0\n");
        assertFalse(GlobalPara.atmSwipeEnabled);
    }

    @Test
    public void anInvalidValue_isIgnored_previousValueStands() {
        GlobalPara.atmSwipeEnabled = true;
        KmsConfigStore.applyPayload("swipe_enabled=maybe\n");
        assertTrue(GlobalPara.atmSwipeEnabled);
    }

    @Test
    public void absent_leavesItAlone() {
        GlobalPara.atmSwipeEnabled = true;
        KmsConfigStore.applyPayload("use_flat_fee=true\n");
        assertTrue(GlobalPara.atmSwipeEnabled);
    }
}
