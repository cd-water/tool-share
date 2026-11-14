package com.cdwater.toolshare.shared.enums;

import java.util.Optional;

/**
 * 工具分类字典。
 *
 * <p>代码同时是工具编号的业务前缀（例如 {@code SQ-G-WX-0001}），因此
 * 常量 code 一经上线不可随意变更，否则会破坏编号规则与历史数据的一致性。
 * 分类顺序也影响前端筛选器的展示顺序。</p>
 */
public enum ToolCategory implements CodeLabel {

    /**
     * 维修工具：扳手、电钻、冲击钻等，编号前缀 WX。
     */
    REPAIR("WX", "维修工具"),
    /**
     * 园艺工具：剪刀、割草机等，编号前缀 YY。
     */
    GARDEN("YY", "园艺工具"),
    /**
     * 清洁工具：清洗机、吸尘器等，编号前缀 QJ。
     */
    CLEANING("QJ", "清洁工具"),
    /**
     * 其他工具：无法归入以上类别的物品，编号前缀 QT。
     */
    OTHER("QT", "其他工具");

    /** 分类代码，同时作为工具编号前缀的一部分。 */
    private final String code;

    /** 用户端分类筛选展示的中文名称。 */
    private final String label;

    /**
     * 构造工具分类。
     *
     * @param code  分类代码（同时是编号前缀）
     * @param label 中文分类名
     */
    ToolCategory(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /**
     * 返回分类代码。
     *
     * @return 分类代码，例如 {@code WX}
     */
    @Override
    public String code() {
        return code;
    }

    /**
     * 返回中文分类名。
     *
     * @return 展示名称
     */
    @Override
    public String label() {
        return label;
    }

    /**
     * 按分类代码反查枚举。
     *
     * @param code 分类代码
     * @return 匹配项；代码为空或未知时返回 {@link Optional#empty()}
     */
    public static Optional<ToolCategory> fromCode(String code) {
        return CodeLabel.find(ToolCategory.class, code);
    }
}
