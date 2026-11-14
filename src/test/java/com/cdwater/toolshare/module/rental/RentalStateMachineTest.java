package com.cdwater.toolshare.module.rental;

import com.cdwater.toolshare.shared.enums.RentalStatus;
import com.cdwater.toolshare.shared.web.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 租赁单状态机：合法跃迁通过，终态与跨阶段跃迁一律 409。
 */
class RentalStateMachineTest {

    @Test
    void allows_every_documented_transition() {
        assertDoesNotThrow(() -> RentalStateMachine.assertTransition(
                RentalStatus.PENDING_PAYMENT, RentalStatus.RESERVED));
        assertDoesNotThrow(() -> RentalStateMachine.assertTransition(
                RentalStatus.PENDING_PAYMENT, RentalStatus.CANCELLED));
        assertDoesNotThrow(() -> RentalStateMachine.assertTransition(
                RentalStatus.RESERVED, RentalStatus.PICKED_UP));
        assertDoesNotThrow(() -> RentalStateMachine.assertTransition(
                RentalStatus.RESERVED, RentalStatus.CANCELLED));
        assertDoesNotThrow(() -> RentalStateMachine.assertTransition(
                RentalStatus.PICKED_UP, RentalStatus.OVERDUE));
        assertDoesNotThrow(() -> RentalStateMachine.assertTransition(
                RentalStatus.PICKED_UP, RentalStatus.RETURNED));
        assertDoesNotThrow(() -> RentalStateMachine.assertTransition(
                RentalStatus.OVERDUE, RentalStatus.RETURNED));
    }

    @Test
    void rejects_skipping_payment_or_bypassing_pickup() {
        assertThrows(BizException.class, () -> RentalStateMachine.assertTransition(
                RentalStatus.PENDING_PAYMENT, RentalStatus.PICKED_UP));
        assertThrows(BizException.class, () -> RentalStateMachine.assertTransition(
                RentalStatus.RESERVED, RentalStatus.RETURNED));
    }

    @Test
    void terminal_states_cannot_transition() {
        assertThrows(BizException.class, () -> RentalStateMachine.assertTransition(
                RentalStatus.RETURNED, RentalStatus.PICKED_UP));
        assertThrows(BizException.class, () -> RentalStateMachine.assertTransition(
                RentalStatus.CANCELLED, RentalStatus.RESERVED));
    }

    @Test
    void same_state_is_a_no_op() {
        assertDoesNotThrow(() -> RentalStateMachine.assertTransition(
                RentalStatus.RETURNED, RentalStatus.RETURNED));
    }
}
