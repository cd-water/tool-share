package com.cdwater.toolshare.shared.enums;

import java.util.Optional;

/** 租赁支付流水类型：押金收取、押金退还、超出押金的应收。 */
public enum PaymentRecordType implements CodeLabel {

    /**
     * 押金收取：下单支付时记录的资金流入。
     */
    DEPOSIT_IN("DEPOSIT_IN", "押金收取"),
    /**
     * 押金退还：归还结算后把剩余押金退回用户。
     */
    DEPOSIT_REFUND("DEPOSIT_REFUND", "退还押金"),
    /**
     * 应收：损坏赔偿等金额超过押金时，记录额外应收款项。
     */
    RECEIVABLE("RECEIVABLE", "应收");

    /** 支付流水表中持久化的英文类型码。 */
    private final String code;

    /** 用户端账单与后台流水展示的中文类型名。 */
    private final String label;

    /**
     * 构造支付流水类型。
     *
     * @param code  英文类型码
     * @param label 中文显示名
     */
    PaymentRecordType(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /**
     * 返回英文类型码。
     *
     * @return 持久化代码
     */
    @Override
    public String code() {
        return code;
    }

    /**
     * 返回中文类型名。
     *
     * @return 展示名称
     */
    @Override
    public String label() {
        return label;
    }

    /**
     * 按类型码反查支付流水类型。
     *
     * @param code 英文类型码
     * @return 匹配项；代码为空或未知时返回 {@link Optional#empty()}
     */
    public static Optional<PaymentRecordType> fromCode(String code) {
        return CodeLabel.find(PaymentRecordType.class, code);
    }
}
