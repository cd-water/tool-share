package com.cdwater.toolshare.shared.enums;

import java.util.Optional;

/**
 * 租赁押金状态字典。
 *
 * <p>押金生命周期与租赁单状态解耦：下单后待支付，支付完成后标记已支付，
 * 归还结算结束时按扣除项退款。状态只表示押金资金动作，不代表租赁是否
 * 已经结束。</p>
 */
public enum DepositStatus implements CodeLabel {

    /**
     * 未支付：租赁单已创建但押金尚未到账，不能进入取件环节。
     */
    UNPAID("UNPAID", "未支付"),
    /**
     * 已支付：押金已收取，可继续预约取件与使用。
     */
    PAID("PAID", "已支付"),
    /**
     * 已退款：归还结算完成，押金已按扣除后的金额退回用户。
     */
    REFUNDED("REFUNDED", "已退款");

    /** 数据库与接口使用的英文代码。 */
    private final String code;

    /** 用户端与后台展示的中文状态。 */
    private final String label;

    /**
     * 构造押金状态。
     *
     * @param code  英文代码
     * @param label 中文显示名
     */
    DepositStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /**
     * 返回英文代码。
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
     * 按代码反查押金状态。
     *
     * @param code 英文代码
     * @return 匹配项；代码为空或未知时返回 {@link Optional#empty()}
     */
    public static Optional<DepositStatus> fromCode(String code) {
        return CodeLabel.find(DepositStatus.class, code);
    }
}
