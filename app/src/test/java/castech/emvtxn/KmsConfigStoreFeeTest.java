package castech.emvtxn;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Review I4 (6.2.13): a negative fee or limit from CasHUB must not reach GlobalPara — with
 * AmountBreakdown refusing negative fees loudly, a negative value would otherwise crash every
 * walk-up and every register sale until CasHUB was corrected.
 */
public class KmsConfigStoreFeeTest {

    private double flat, pct, min, max;

    @Before public void remember() {
        flat = GlobalPara.atmFlatFeeAmount; pct = GlobalPara.atmPercentageFee;
        min = GlobalPara.atmMinAmount; max = GlobalPara.atmMaxAmount;
        GlobalPara.atmFlatFeeAmount = 3.50; GlobalPara.atmPercentageFee = 2.0;
        GlobalPara.atmMinAmount = 10; GlobalPara.atmMaxAmount = 500;
    }
    @After public void restore() {
        GlobalPara.atmFlatFeeAmount = flat; GlobalPara.atmPercentageFee = pct;
        GlobalPara.atmMinAmount = min; GlobalPara.atmMaxAmount = max;
    }

    @Test
    public void negativeFlatFee_isIgnored_previousValueStands() {
        KmsConfigStore.applyPayload("flat_fee=-3.50\n");
        assertEquals(3.50, GlobalPara.atmFlatFeeAmount, 0.0001);
    }

    @Test
    public void negativePercentageFee_isIgnored() {
        KmsConfigStore.applyPayload("percentage_fee=-1\n");
        assertEquals(2.0, GlobalPara.atmPercentageFee, 0.0001);
    }

    @Test
    public void negativeLimits_areIgnored() {
        KmsConfigStore.applyPayload("min_amount=-10\nmax_amount=-500\n");
        assertEquals(10, GlobalPara.atmMinAmount, 0.0001);
        assertEquals(500, GlobalPara.atmMaxAmount, 0.0001);
    }

    @Test
    public void validValues_stillApply() {
        KmsConfigStore.applyPayload("flat_fee=2.95\npercentage_fee=0\nmin_amount=20\nmax_amount=400\n");
        assertEquals(2.95, GlobalPara.atmFlatFeeAmount, 0.0001);
        assertEquals(0, GlobalPara.atmPercentageFee, 0.0001);
        assertEquals(20, GlobalPara.atmMinAmount, 0.0001);
        assertEquals(400, GlobalPara.atmMaxAmount, 0.0001);
    }
}
