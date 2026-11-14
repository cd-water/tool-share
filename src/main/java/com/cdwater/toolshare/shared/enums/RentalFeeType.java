package com.cdwater.toolshare.shared.enums;

import java.util.Optional;

/** 租赁费用流水类型。WAIVER 的金额表示减免额，用于冲减 OVERDUE/DAMAGE。 */
public enum RentalFeeType implements CodeLabel {

    /**
     * 逾期费：超过归还期限后按规则产生的费用。
     */
    OVERDUE("OVERDUE", "逾期费"),
    /**
     * 损坏赔偿：归还验收发现损坏时产生的赔偿金额。
     */
    DAMAGE("DAMAGE", "损坏赔偿"),
    /**
     * 减免：正向冲减费用，金额字段记录的是被减免的额度。
     */
    WAIVER("WAIVER", "减免"),
    /**
     * 其他：无法归入上述类别的人工调整项。
     */
    OTHER("OTHER", "其他");

    /** 费用流水表中持久化的英文类型码。 */
    private final String code;

    /** 后台费用明细展示的中文类型名。 */
    private final String label;

    /**
     * 构造费用流水类型。
     *
     * @param code  英文类型码
     * @param label 中文显示名
     */
    RentalFeeType(String code, String label) {
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
     * 按类型码反查费用类型。
     *
     * @param code 英文类型码
     * @return 匹配项；代码为空或未知时返回 {@link Optional#empty()}
     */
    public static Optional<RentalFeeType> fromCode(String code) {
        return CodeLabel.find(RentalFeeType.class, code);
    }
}
