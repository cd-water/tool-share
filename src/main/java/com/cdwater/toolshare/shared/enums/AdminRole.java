package com.cdwater.toolshare.shared.enums;

import java.util.List;
import java.util.Optional;

/**
 * 管理端角色：工作人员处理日常运营，系统管理员额外管理配置。
 */
public enum AdminRole implements CodeLabel {

    STAFF("STAFF", "工作人员", List.of(
            "rental:manage", "damage:manage", "message:manage", "dashboard:view")),
    SUPER_ADMIN("SUPER_ADMIN", "系统管理员", List.of(
            "rental:manage", "damage:manage", "message:manage", "message:config", "dashboard:view",
            "tool:manage", "knowledge:manage", "admin:manage"));

    private final String code;
    private final String label;
    private final List<String> permissions;

    AdminRole(String code, String label, List<String> permissions) {
        this.code = code;
        this.label = label;
        this.permissions = permissions;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String label() {
        return label;
    }

    public List<String> permissions() {
        return permissions;
    }

    public static Optional<AdminRole> fromCode(String code) {
        return CodeLabel.find(AdminRole.class, code);
    }
}
