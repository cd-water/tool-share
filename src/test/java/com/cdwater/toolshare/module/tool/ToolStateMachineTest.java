package com.cdwater.toolshare.module.tool;

import com.cdwater.toolshare.shared.enums.ToolStatus;
import com.cdwater.toolshare.shared.web.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 工具状态机单元测试。
 * <p>
 * 测试范围：可用、租出、维修、报废四种工具状态之间的合法迁移。
 * 依赖环境：纯状态机静态方法，不启动 Spring、不访问数据库。
 * 关键不变量：维修中的工具不能直接租出，必须先回到可用；
 * 报废是终态，普通更新不得恢复；同状态重复设置是幂等空操作。
 */
class ToolStateMachineTest {

    /**
     * 场景：依次尝试可用、租出、维修之间的全部合法迁移。
     * 前置条件：这些边在状态机的允许集合中。
     * 断言目标：迁移不抛异常，覆盖上架、租出、归还与送修路径。
     */
    @Test
    void allows_documented_transitions() {
        // 可用 -> 租出：用户下单取件后的正常路径。
        // 状态机应允许该边。
        // assertDoesNotThrow 表示合法迁移无副作用。
        assertDoesNotThrow(() -> ToolStateMachine.assertTransition(
                ToolStatus.AVAILABLE, ToolStatus.RENTED));
        // 可用 -> 维修：工具损坏后送修。
        // 这是从库存状态进入维修流程的入口。
        // 该迁移不能被误判为冲突。
        assertDoesNotThrow(() -> ToolStateMachine.assertTransition(
                ToolStatus.AVAILABLE, ToolStatus.MAINTENANCE));
        // 租出 -> 可用：用户归还且验收通过。
        // 工具重新回到可租库存。
        assertDoesNotThrow(() -> ToolStateMachine.assertTransition(
                ToolStatus.RENTED, ToolStatus.AVAILABLE));
        // 租出 -> 维修：归还时发现损坏，直接转维修。
        // 跳过可用状态是业务允许的快捷路径。
        assertDoesNotThrow(() -> ToolStateMachine.assertTransition(
                ToolStatus.RENTED, ToolStatus.MAINTENANCE));
        // 维修 -> 可用：维修完成并通过质检。
        // 这是维修释放库存的唯一出口。
        // 覆盖该边保证维修流程可闭环。
        assertDoesNotThrow(() -> ToolStateMachine.assertTransition(
                ToolStatus.MAINTENANCE, ToolStatus.AVAILABLE));
    }

    /**
     * 场景：维修中的工具试图直接进入租出状态。
     * 前置条件：维修与租出之间没有直接边。
     * 断言目标：抛出业务异常，必须先经可用状态完成质检释放。
     */
    @Test
    void maintenance_cannot_be_rented_directly() {
        // 维修中的工具不能直接租出：
        // 必须先回到可用状态完成质检释放，否则可能租出未修复的资产。
        // 状态机应抛出业务异常而不是静默放行。
        assertThrows(BizException.class, () -> ToolStateMachine.assertTransition(
                // 断言异常类型为业务异常，
                // 便于上层统一转换为接口错误响应。
                ToolStatus.MAINTENANCE, ToolStatus.RENTED));
    }

    /**
     * 场景：已报废工具尝试恢复到可用或维修状态。
     * 前置条件：报废被定义为终态。
     * 断言目标：所有离开报废的迁移均被拒绝，防止废弃资产重新流入租借。
     */
    @Test
    void scrapped_is_terminal() {
        // 报废是终态：恢复正常库存会破坏资产审计。
        // 这里验证离开报废的第一条边被拒绝。
        assertThrows(BizException.class, () -> ToolStateMachine.assertTransition(
                ToolStatus.SCRAPPED, ToolStatus.AVAILABLE));
        // 报废转维修同样非法；
        // 终态的语义是任何后续流转都不允许。
        // 两条边共同证明报废不可恢复。
        assertThrows(BizException.class, () -> ToolStateMachine.assertTransition(
                ToolStatus.SCRAPPED, ToolStatus.MAINTENANCE));
    }

    /**
     * 场景：把工具状态更新为其当前状态。
     * 前置条件：更新接口可能重复提交相同状态。
     * 断言目标：同状态迁移被容忍，不误报冲突。
     */
    @Test
    void same_state_is_a_no_op() {
        // 同状态重复设置应视为幂等空操作：
        // 更新接口可能重试或重复提交，不应误报状态冲突。
        // 这保证调用方的重试策略是安全的。
        assertDoesNotThrow(() -> ToolStateMachine.assertTransition(
                ToolStatus.MAINTENANCE, ToolStatus.MAINTENANCE));
    }
}
