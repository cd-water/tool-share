package com.cdwater.toolshare.module.rental;

import com.cdwater.toolshare.shared.enums.RentalStatus;
import com.cdwater.toolshare.shared.web.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 租赁单状态机单元测试。
 * <p>
 * 测试范围：待支付、已预订、租出中、逾期、已归还、已取消之间的状态迁移。
 * 依赖环境：纯状态机静态方法，不启动 Spring、不访问数据库。
 * 关键不变量：支付与取件不能跳过；终态不可再迁移；非法跃迁统一返回 409 冲突；
 * 同状态重复设置视为幂等空操作。
 */
class RentalStateMachineTest {

    /**
     * 场景：依次尝试租赁单全生命周期的合法迁移。
     * 前置条件：所有边均在状态机允许集合内。
     * 断言目标：支付、取消、取件、逾期、归还等正常路径均不抛异常。
     */
    @Test
    void allows_every_documented_transition() {
        // 待支付 -> 已预订：用户完成支付并锁定工具。
        // 这是租赁主流程的第一步。
        assertDoesNotThrow(() -> RentalStateMachine.assertTransition(
                RentalStatus.PENDING_PAYMENT, RentalStatus.RESERVED));
        // 待支付 -> 已取消：用户在支付前放弃订单。
        // 取消后应释放库存。
        assertDoesNotThrow(() -> RentalStateMachine.assertTransition(
                RentalStatus.PENDING_PAYMENT, RentalStatus.CANCELLED));
        // 已预订 -> 租出中：用户到店取件。
        // 取件动作触发押金与租期计算。
        assertDoesNotThrow(() -> RentalStateMachine.assertTransition(
                // 取件必须发生在支付之后，
                // 该边保证库存从预订转为实际租出。
                RentalStatus.RESERVED, RentalStatus.PICKED_UP));
        // 已预订 -> 已取消：取件前仍允许取消。
        // 与待支付取消并列，覆盖取消的两个时间点。
        assertDoesNotThrow(() -> RentalStateMachine.assertTransition(
                RentalStatus.RESERVED, RentalStatus.CANCELLED));
        // 租出中 -> 逾期：超过约定归还时间。
        // 逾期会触发信用与费用规则。
        assertDoesNotThrow(() -> RentalStateMachine.assertTransition(
                RentalStatus.PICKED_UP, RentalStatus.OVERDUE));
        // 租出中 -> 已归还：按时归还并完成验收。
        // 这是正常结案路径。
        assertDoesNotThrow(() -> RentalStateMachine.assertTransition(
                // 取件后可以直接归还，覆盖正常租期结束路径；
                // 归还动作会触发结算与押金退还。
                RentalStatus.PICKED_UP, RentalStatus.RETURNED));
        // 逾期 -> 已归还：逾期后仍可归还。
        // 状态机允许从逾期直接结案。
        // 该边保证逾期订单不会卡死。
        assertDoesNotThrow(() -> RentalStateMachine.assertTransition(
                // 逾期状态仍允许归还，避免订单永久停留在逾期；
                // 逾期费用在归还结算时统一计算。
                RentalStatus.OVERDUE, RentalStatus.RETURNED));
    }

    /**
     * 场景：未支付直接取件、未取件直接归还。
     * 前置条件：两条边都被状态机禁止。
     * 断言目标：抛出业务异常，防止跳过付款与验货环节。
     */
    @Test
    void rejects_skipping_payment_or_bypassing_pickup() {
        // 未支付不能直接取件：
        // 否则会绕过付款与押金冻结，造成资损。
        assertThrows(BizException.class, () -> RentalStateMachine.assertTransition(
                RentalStatus.PENDING_PAYMENT, RentalStatus.PICKED_UP));
        // 未取件不能直接归还：
        // 没有出库记录就归还，会破坏库存与验收链路。
        assertThrows(BizException.class, () -> RentalStateMachine.assertTransition(
                RentalStatus.RESERVED, RentalStatus.RETURNED));
    }

    /**
     * 场景：已归还或已取消的租赁单再次迁移。
     * 前置条件：归还与取消是终态。
     * 断言目标：离开终态一律被拒绝，保证结算与流水不可被重新打开。
     */
    @Test
    void terminal_states_cannot_transition() {
        // 已归还订单不能重新进入租出中，
        // 结案后费用与流水应保持不可变。
        assertThrows(BizException.class, () -> RentalStateMachine.assertTransition(
                // 已归还订单不能再次取件，
                // 否则会重复占用库存并产生错误流水。
                RentalStatus.RETURNED, RentalStatus.PICKED_UP));
        // 已取消订单不能恢复为已预订，
        // 取消是终态，重新下单应创建新订单。
        // 两条断言共同定义租赁终态。
        assertThrows(BizException.class, () -> RentalStateMachine.assertTransition(
                RentalStatus.CANCELLED, RentalStatus.RESERVED));
    }

    /**
     * 场景：对已归还租赁单重复设置为已归还。
     * 前置条件：接口可能收到重复的幂等请求。
     * 断言目标：同状态迁移不抛异常，便于安全重试。
     */
    @Test
    void same_state_is_a_no_op() {
        // 重复设置为已归还属于幂等请求：
        // 客户端重试或重复回调不应触发冲突。
        // 状态机对同状态迁移直接放行。
        assertDoesNotThrow(() -> RentalStateMachine.assertTransition(
                RentalStatus.RETURNED, RentalStatus.RETURNED));
    }
}
