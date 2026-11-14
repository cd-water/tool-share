package com.cdwater.toolshare.module.credit;

import com.cdwater.toolshare.shared.enums.CreditEventType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 信用分规则：初始 100；按时 +2、逾期 -3、严重损坏 -10、低星评价 -5；始终裁剪在 0–100。
 */
class CreditPolicyTest {

    @Test
    void first_events_apply_documented_deltas() {
        assertEquals(100, CreditPolicy.firstApplication(CreditEventType.ON_TIME_RETURN));
        assertEquals(97, CreditPolicy.firstApplication(CreditEventType.OVERDUE));
        assertEquals(90, CreditPolicy.firstApplication(CreditEventType.SEVERE_DAMAGE));
        assertEquals(95, CreditPolicy.firstApplication(CreditEventType.LOW_REVIEW));
    }

    @Test
    void score_never_exceeds_upper_bound() {
        assertEquals(100, CreditPolicy.apply(99, CreditEventType.ON_TIME_RETURN));
        assertEquals(100, CreditPolicy.apply(100, CreditEventType.ON_TIME_RETURN));
    }

    @Test
    void score_never_drops_below_zero() {
        assertEquals(0, CreditPolicy.apply(3, CreditEventType.SEVERE_DAMAGE));
        assertEquals(0, CreditPolicy.apply(0, CreditEventType.OVERDUE));
    }

    @Test
    void normal_arithmetic_inside_bounds() {
        assertEquals(95, CreditPolicy.apply(100, CreditEventType.LOW_REVIEW));
        assertEquals(92, CreditPolicy.apply(95, CreditEventType.OVERDUE));
    }
}
