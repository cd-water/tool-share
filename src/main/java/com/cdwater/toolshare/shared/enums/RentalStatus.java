package com.cdwater.toolshare.shared.enums;

import java.util.Optional;

/**
 * 租赁单状态字典。
 *
 * <p>状态机主干为：待支付 → 已预约 → 使用中 → 已归还；超时未归还转为
 * 已逾期，未支付阶段可取消。看板中的“活跃租赁”只统计已预约、使用中与
 * 已逾期三种状态，待支付与已归还均不计入。</p>
 */
public enum RentalStatus implements CodeLabel {

    /**
     * 待支付：订单已创建但押金未支付，尚未占用工具。
     */
    PENDING_PAYMENT("PENDING_PAYMENT", "待支付"),
    /**
     * 已预约：押金已支付，工具为该用户保留，等待取件。
     */
    RESERVED("RESERVED", "已预约"),
    /**
     * 使用中：用户已取件，租赁计时开始。
     */
    PICKED_UP("PICKED_UP", "使用中"),
    /**
     * 已归还：工具已交回并完成验收，订单进入历史列表。
     */
    RETURNED("RETURNED", "已归还"),
    /**
     * 已取消：待支付阶段终止订单，不产生费用与信用事件。
     */
    CANCELLED("CANCELLED", "已取消"),
    /**
     * 已逾期：超过约定归还时间仍未归还，可能持续计费并扣信用分。
     */
    OVERDUE("OVERDUE", "已逾期");

    /** 租赁表中持久化的英文状态码。 */
    private final String code;

    /** 用户端订单列表展示的中文状态。 */
    private final String label;

    /**
     * 构造租赁状态。
     *
     * @param code  英文状态码
     * @param label 中文显示名
     */
    RentalStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /**
     * 返回英文状态码。
     *
     * @return 持久化代码
     */
    @Override
    public String code() {
        return code;
    }

    /**
     * 返回中文状态名。
     *
     * @return 展示名称
     */
    @Override
    public String label() {
        return label;
    }

    /**
     * 按状态码反查枚举。
     *
     * @param code 英文状态码
     * @return 匹配项；代码为空或未知时返回 {@link Optional#empty()}
     */
    public static Optional<RentalStatus> fromCode(String code) {
        return CodeLabel.find(RentalStatus.class, code);
    }
}
