package com.cdwater.toolshare.shared.enums;

import java.util.Optional;

/**
 * 工具审核状态。数据库沿用 V1 的 {@code t_tool.audit_status} TINYINT 字段：
 * 0 待审核、1 已通过、2 需修改。
 *
 * <p>与 {@link ToolStatus} 相互独立：审核状态决定工具能否对外展示与出借，
 * 工具状态描述实物当前是否可借。只有“已通过”的工具才会出现在用户端列表
 * 与可借用统计中。</p>
 */
public enum AuditStatus implements CodeLabel {

    /**
     * 待审核：管理员尚未处理，工具不对用户端展示。
     */
    PENDING(0, "待审核"),
    /**
     * 已通过：工具可正式上架，是否可借仍取决于 {@link ToolStatus}。
     */
    APPROVED(1, "已通过"),
    /**
     * 需修改：审核发现问题，需管理员修正资料后重新提交审核。
     */
    NEEDS_CHANGE(2, "需修改");

    /** 数据库 TINYINT 编码，保持与 V1 表结构兼容。 */
    private final int value;

    /** 管理端展示的中文状态名。 */
    private final String label;

    /**
     * 构造审核状态。
     *
     * @param value 数据库 TINYINT 编码
     * @param label 中文状态名
     */
    AuditStatus(int value, String label) {
        this.value = value;
        this.label = label;
    }

    /**
     * 返回数据库编码的字符串形式，适配统一的 {@link CodeLabel} 接口。
     *
     * @return TINYINT 编码的十进制字符串
     */
    @Override
    public String code() {
        return String.valueOf(value);
    }

    /**
     * 返回中文状态名。
     *
     * @return 用于管理端展示的名称
     */
    @Override
    public String label() {
        return label;
    }

    /** 数据库 TINYINT 编码。 */
    public int value() {
        return value;
    }

    /**
     * 按数据库编码反查审核状态。
     *
     * @param code TINYINT 编码，允许为 null
     * @return 匹配的状态；入参为空或未知时返回 {@link Optional#empty()}
     */
    public static Optional<AuditStatus> fromCode(Integer code) {
        if (code == null) {
            return Optional.empty();
        }
        return CodeLabel.find(AuditStatus.class, String.valueOf(code));
    }

    /**
     * 按枚举名称反查审核状态，用于处理历史接口中以名称传参的调用。
     *
     * @param name 枚举名，例如 {@code APPROVED}
     * @return 匹配的状态；名称为空或不匹配时返回 {@link Optional#empty()}
     */
    public static Optional<AuditStatus> fromName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        for (AuditStatus value : values()) {
            if (value.name().equals(name)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }
}
