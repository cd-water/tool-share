package com.cdwater.toolshare.shared.enums;

import java.util.Optional;

/**
 * 工具审核状态。数据库沿用 V1 的 {@code t_tool.audit_status} TINYINT 字段：
 * 0 待审核、1 已通过、2 需修改。
 */
public enum AuditStatus implements CodeLabel {

    PENDING(0, "待审核"),
    APPROVED(1, "已通过"),
    NEEDS_CHANGE(2, "需修改");

    private final int value;
    private final String label;

    AuditStatus(int value, String label) {
        this.value = value;
        this.label = label;
    }

    @Override
    public String code() {
        return String.valueOf(value);
    }

    @Override
    public String label() {
        return label;
    }

    /** 数据库 TINYINT 编码。 */
    public int value() {
        return value;
    }

    public static Optional<AuditStatus> fromCode(Integer code) {
        if (code == null) {
            return Optional.empty();
        }
        return CodeLabel.find(AuditStatus.class, String.valueOf(code));
    }

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
