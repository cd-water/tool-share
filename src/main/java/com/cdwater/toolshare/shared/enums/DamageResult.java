package com.cdwater.toolshare.shared.enums;

import java.util.Optional;

/**
 * 归还验收的损坏结论字典。
 *
 * <p>与 {@link DamageLevel} 的区别：本枚举回答“有没有损坏、是否需要赔偿”，
 * 而 DamageLevel 回答“损坏到什么程度”。验收记录会保存本结论，并据此
 * 决定是否生成赔偿费用或报修工单。</p>
 */
public enum DamageResult implements CodeLabel {

    /**
     * 完好：未发现损坏，无需赔偿，工具可直接恢复可借用。
     */
    INTACT("INTACT", "完好"),
    /**
     * 轻微损坏：需要登记并可收取少量赔偿，通常不影响再次出租。
     */
    MINOR("MINOR", "轻微损坏"),
    /**
     * 严重损坏：需停止出租并转维修或报废，通常触发信用扣分。
     */
    SEVERE("SEVERE", "严重损坏");

    /** 数据库与接口使用的英文代码。 */
    private final String code;

    /** 管理端与用户端展示的中文结论。 */
    private final String label;

    /**
     * 构造损坏结论。
     *
     * @param code  英文代码
     * @param label 中文显示名
     */
    DamageResult(String code, String label) {
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
     * 返回中文结论。
     *
     * @return 展示名称
     */
    @Override
    public String label() {
        return label;
    }

    /**
     * 按代码反查损坏结论。
     *
     * @param code 英文代码
     * @return 匹配项；代码为空或未知时返回 {@link Optional#empty()}
     */
    public static Optional<DamageResult> fromCode(String code) {
        return CodeLabel.find(DamageResult.class, code);
    }
}
