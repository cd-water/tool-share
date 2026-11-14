package com.cdwater.toolshare.shared.enums;

import java.util.Optional;

/**
 * 工具审核状态。数据库沿用 V1 的 {@code t_tool.audit_status} TINYINT 字段：
 * 0 待审核、1 已通过、2 需修改。
 */
public enum AuditStatus {

    PENDING(0, "待审核"),
    APPROVED(1, "已通过"),
    NEEDS_CHANGE(2, "需修改");

    private final int code;
    private final String label;

    AuditStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static Optional<AuditStatus> fromCode(Integer code) {
        if (code == null) {
            return Optional.empty();
        }
        for (AuditStatus value : values()) {
            if (value.code == code) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    public static java.util.Optional<AuditStatus> fromName(String name) {
        if (name == null) {
            return java.util.Optional.empty();
        }
        for (AuditStatus value : values()) {
            if (value.name().equals(name)) {
                return java.util.Optional.of(value);
            }
        }
        return java.util.Optional.empty();
    }

    public static String labelOf(Integer code) {
        return fromCode(code).map(AuditStatus::label).orElse(null);
    }
}
