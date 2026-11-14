package com.cdwater.toolshare.module.damage;

import com.cdwater.toolshare.shared.enums.ReportStatus;
import com.cdwater.toolshare.shared.web.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 报修单状态机单元测试。
 * <p>
 * 测试范围：SUBMITTED -> VERIFIED/REJECTED -> REPAIRING -> COMPLETED/SCRAPPED 的合法迁移。
 * 依赖环境：纯状态机静态方法，不启动 Spring、不访问数据库。
 * 关键不变量：只允许文档列出的迁移；终态不可再流转；非法迁移统一返回冲突错误码。
 */
class DamageStateMachineTest {

    /**
     * 场景：依次尝试报修流程中所有合法的正向迁移。
     * 前置条件：起点与终点均在状态机的允许边集合内。
     * 断言目标：合法迁移不抛异常，覆盖提交、核验、维修、完成与报废路径。
     */
    @Test
    void allows_only_the_documented_transitions() {
        // 提交 -> 核验：报修单进入人工审核环节。
        // 这是报修流程的第一个正向分支。
        assertDoesNotThrow(() -> DamageStateMachine.assertTransition(
                ReportStatus.SUBMITTED, ReportStatus.VERIFIED));
        // 提交 -> 驳回：审核直接拒绝不合理的报修。
        // 驳回是终态，后续不能再流转。
        assertDoesNotThrow(() -> DamageStateMachine.assertTransition(
                ReportStatus.SUBMITTED, ReportStatus.REJECTED));
        // 核验 -> 维修：确认损坏后进入维修执行。
        // 该边连接审核与维修两个阶段。
        assertDoesNotThrow(() -> DamageStateMachine.assertTransition(
                ReportStatus.VERIFIED, ReportStatus.REPAIRING));
        // 维修 -> 完成：维修成功并归还工具。
        // 完成是正常结案路径。
        assertDoesNotThrow(() -> DamageStateMachine.assertTransition(
                ReportStatus.REPAIRING, ReportStatus.COMPLETED));
        // 维修 -> 报废：损坏严重且无法修复时直接报废。
        // 与完成并列作为维修阶段的两种出口。
        assertDoesNotThrow(() -> DamageStateMachine.assertTransition(
                ReportStatus.REPAIRING, ReportStatus.SCRAPPED));
    }

    /**
     * 场景：尝试跳过核验、跨阶段回退等非法迁移。
     * 前置条件：这些边不在允许集合中。
     * 断言目标：全部抛出业务异常且错误码为 C409，防止状态被越权改写。
     */
    @Test
    void rejects_illegal_transitions_with_conflict() {
    /**
     * 辅助方法：断言一次迁移被拒绝，并核对错误码为冲突（C409）。
     * 失败信息附带起止状态，便于快速定位是哪条非法边未被拦截。
     */
        // 提交后不能跳过核验直接维修；
        // 否则未经确认的损坏会直接消耗维修资源。
        // 以下逐条覆盖跨阶段、跳步骤与回退等非法边；
        // 每一条都应被状态机拒绝，避免报修单被越权改写。
        assertConflict(ReportStatus.SUBMITTED, ReportStatus.REPAIRING);
        // 提交后直接完成同样非法，
        // 报修流程必须经过中间状态。
        assertConflict(ReportStatus.SUBMITTED, ReportStatus.COMPLETED);
        // 核验后不能跳过维修直接完成，
        // 防止未实际修复就结案。
        assertConflict(ReportStatus.VERIFIED, ReportStatus.COMPLETED);
        // 核验后不能再回到驳回：
        // 驳回只适用于提交阶段，跨阶段回退会破坏审计轨迹。
        assertConflict(ReportStatus.VERIFIED, ReportStatus.REJECTED);
        // 维修后不能回退到核验，
        // 状态只能沿定义方向前进。
        assertConflict(ReportStatus.REPAIRING, ReportStatus.VERIFIED);
        // 维修后也不能驳回，
        // 驳回与维修是互斥的不同处理结果。
        assertConflict(ReportStatus.REPAIRING, ReportStatus.REJECTED);
    }

    /**
     * 场景：遍历 COMPLETED、REJECTED、SCRAPPED 三个终态到其余所有状态。
     * 前置条件：状态机将三者定义为终态。
     * 断言目标：任意终态出发的迁移都被拒绝，避免已结案单据被重新处理。
     */
    @Test
    void terminal_states_never_transition_again() {
        // 遍历三个终态：完成、驳回、报废。
        // 终态代表单据已结案，不应再被处理。
        // 每个终态都要独立验证，避免漏掉某一路径。
        for (ReportStatus terminal : new ReportStatus[]{
                // 使用数组显式列出终态，
                // 新增终态时需要同步补充到这里。
                ReportStatus.COMPLETED, ReportStatus.REJECTED, ReportStatus.SCRAPPED}) {
            // 对每个终态尝试所有可能的目标状态，
            // 包括同状态以外的全部枚举值。
            for (ReportStatus target : ReportStatus.values()) {
                // 跳过自身：同状态重复设置是幂等空操作，不属于非法迁移。
                // 其余目标都应被拒绝。
                if (target == terminal) {
                    continue;
                }
                // 任意终态出发的迁移都必须抛出冲突异常，
                // 避免已结案单据被重新打开。
                // 每个非自身目标都走统一的冲突断言，
                // 失败时会输出具体终态与目标状态，便于定位遗漏的边。
                assertConflict(terminal, target);
            }
        }
    }

    // 辅助方法：断言一次迁移被拒绝，并核对错误码为冲突（C409）。
    // 失败信息附带起止状态，便于快速定位是哪条非法边未被拦截。
    private void assertConflict(ReportStatus from, ReportStatus to) {
        // assertThrows 同时验证异常类型与触发点；
        // 第三个参数让 JUnit 在失败时输出具体状态对。
        BizException error = assertThrows(BizException.class,
                () -> DamageStateMachine.assertTransition(from, to),
                from + " -> " + to);
        // 错误码必须是 C409，
        // 前端据此把非法状态迁移映射为冲突提示，而不是系统错误。
        assertEquals("C409", error.getCode(), from + " -> " + to);
    }
}
