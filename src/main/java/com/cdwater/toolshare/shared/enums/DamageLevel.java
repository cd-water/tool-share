package com.cdwater.toolshare.shared.enums;

import java.util.Optional;

/**
 * 工具成色/损坏程度字典。
 *
 * <p>用于工具台账与验收环节描述实物状态：从全新到严重损坏共四档。
 * 该枚举只描述“程度”，是否构成赔偿或报修由 {@link DamageResult} 与
 * 业务规则决定，二者不要混用。</p>
 */
public enum DamageLevel implements CodeLabel {

    /**
     * 全新：几乎无使用痕迹，可直接再次出租。
     */
    NEW("NEW", "全新"),
    /**
     * 轻微磨损：正常使用痕迹，不影响安全与出租。
     */
    LIGHT("LIGHT", "轻微磨损"),
    /**
     * 中度磨损：需登记但通常仍可出租，必要时安排保养。
     */
    MEDIUM("MEDIUM", "中度磨损"),
    /**
     * 严重损坏：影响使用安全，应停止出租并进入报修流程。
     */
    SEVERE("SEVERE", "严重损坏");

    /** 数据库与接口使用的英文代码。 */
    private final String code;

    /** 管理端展示的中文程度名。 */
    private final String label;

    /**
     * 构造损坏程度常量。
     *
     * @param code  英文代码
     * @param label 中文显示名
     */
    DamageLevel(String code, String label) {
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
     * 返回中文程度名。
     *
     * @return 展示名称
     */
    @Override
    public String label() {
        return label;
    }

    /**
     * 按代码反查损坏程度。
     *
     * @param code 英文代码
     * @return 匹配项；代码为空或未知时返回 {@link Optional#empty()}
     */
    public static Optional<DamageLevel> fromCode(String code) {
        return CodeLabel.find(DamageLevel.class, code);
    }
}
