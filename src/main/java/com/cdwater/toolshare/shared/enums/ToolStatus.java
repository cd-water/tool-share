package com.cdwater.toolshare.shared.enums;

import java.util.Optional;

/**
 * 工具实物状态字典。
 *
 * <p>描述工具当前是否可借：可借用、已租出、维修中或已报废。与
 * {@link AuditStatus} 共同决定用户端可见性——只有审核通过且状态为
 * 可借用的工具才会出现在可借用列表与看板统计中。</p>
 */
public enum ToolStatus implements CodeLabel {

    /**
     * 可借用：审核通过且不在租赁/维修中，可被用户预约。
     */
    AVAILABLE("AVAILABLE", "可借用"),
    /**
     * 已租出：正在被用户使用或已预约占用，暂不可再次出借。
     */
    RENTED("RENTED", "已租出"),
    /**
     * 维修中：因损坏进入维修，修复前不可出借。
     */
    MAINTENANCE("MAINTENANCE", "维修中"),
    /**
     * 已报废：已退出租赁台账，仅保留历史记录。
     */
    SCRAPPED("SCRAPPED", "已报废");

    /** 工具表中持久化的英文状态码。 */
    private final String code;

    /** 管理端与用户端展示的中文状态。 */
    private final String label;

    /**
     * 构造工具状态。
     *
     * @param code  英文状态码
     * @param label 中文显示名
     */
    ToolStatus(String code, String label) {
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
    public static Optional<ToolStatus> fromCode(String code) {
        return CodeLabel.find(ToolStatus.class, code);
    }
}
