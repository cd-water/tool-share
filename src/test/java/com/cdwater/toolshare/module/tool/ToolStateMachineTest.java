package com.cdwater.toolshare.module.tool;

import com.cdwater.toolshare.shared.enums.ToolStatus;
import com.cdwater.toolshare.shared.web.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 工具状态机：已报废为终态，普通更新不能恢复；维修与租出之间必须经由可用。
 */
class ToolStateMachineTest {

    @Test
    void allows_documented_transitions() {
        assertDoesNotThrow(() -> ToolStateMachine.assertTransition(
                ToolStatus.AVAILABLE, ToolStatus.RENTED));
        assertDoesNotThrow(() -> ToolStateMachine.assertTransition(
                ToolStatus.AVAILABLE, ToolStatus.MAINTENANCE));
        assertDoesNotThrow(() -> ToolStateMachine.assertTransition(
                ToolStatus.RENTED, ToolStatus.AVAILABLE));
        assertDoesNotThrow(() -> ToolStateMachine.assertTransition(
                ToolStatus.RENTED, ToolStatus.MAINTENANCE));
        assertDoesNotThrow(() -> ToolStateMachine.assertTransition(
                ToolStatus.MAINTENANCE, ToolStatus.AVAILABLE));
    }

    @Test
    void maintenance_cannot_be_rented_directly() {
        assertThrows(BizException.class, () -> ToolStateMachine.assertTransition(
                ToolStatus.MAINTENANCE, ToolStatus.RENTED));
    }

    @Test
    void scrapped_is_terminal() {
        assertThrows(BizException.class, () -> ToolStateMachine.assertTransition(
                ToolStatus.SCRAPPED, ToolStatus.AVAILABLE));
        assertThrows(BizException.class, () -> ToolStateMachine.assertTransition(
                ToolStatus.SCRAPPED, ToolStatus.MAINTENANCE));
    }

    @Test
    void same_state_is_a_no_op() {
        assertDoesNotThrow(() -> ToolStateMachine.assertTransition(
                ToolStatus.MAINTENANCE, ToolStatus.MAINTENANCE));
    }
}
