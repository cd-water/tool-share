package com.cdwater.toolshare.shared.enums;

import java.util.List;
import java.util.Optional;

/**
 * 管理端角色：工作人员处理日常运营，系统管理员额外管理配置。
 *
 * <p>工作人员负责日常运营（租赁、报修、消息、看板）；系统管理员在此基础上
 * 额外拥有工具、知识库、消息配置与管理员账号的维护权限。权限字符串会原样
 * 写入 Sa-Token 的权限列表，供 {@code @SaCheckPermission(type = "admin")}
 * 注解校验，因此修改常量列表等同于调整管理端授权矩阵。</p>
 */
public enum AdminRole implements CodeLabel {

    /**
     * 工作人员：处理租赁、报修与消息等日常业务，只读查看看板。
     */
    STAFF("STAFF", "工作人员", List.of(
            "rental:manage", "damage:manage", "message:manage", "dashboard:view")),
    /**
     * 系统管理员：在工作人员权限之外，可维护工具、知识库、消息配置与管理员。
     */
    SUPER_ADMIN("SUPER_ADMIN", "系统管理员", List.of(
            "rental:manage", "damage:manage", "message:manage", "message:config", "dashboard:view",
            "tool:manage", "knowledge:manage", "admin:manage"));

    /** 数据库与登录会话中使用的角色代码。 */
    private final String code;

    /** 管理端界面展示的中文角色名。 */
    private final String label;

    /** 该角色被授予的权限点列表，供登录时写入会话。 */
    private final List<String> permissions;

    /**
     * 构造管理角色。
     *
     * @param code        角色代码
     * @param label       中文角色名
     * @param permissions 权限点列表，顺序不影响鉴权
     */
    AdminRole(String code, String label, List<String> permissions) {
        this.code = code;
        this.label = label;
        this.permissions = permissions;
    }

    /**
     * 返回角色代码。
     *
     * @return 数据库存储的角色代码
     */
    @Override
    public String code() {
        return code;
    }

    /**
     * 返回中文角色名。
     *
     * @return 用于管理端展示的名称
     */
    @Override
    public String label() {
        return label;
    }

    /**
     * 返回该角色拥有的权限点。
     *
     * <p>列表由枚举常量持有且不对外修改，调用方只应读取，不应尝试变更。</p>
     *
     * @return 权限点列表
     */
    public List<String> permissions() {
        return permissions;
    }

    /**
     * 按角色代码反查枚举。
     *
     * @param code 数据库中的角色代码
     * @return 匹配的角色；代码为空或未知时返回 {@link Optional#empty()}
     */
    public static Optional<AdminRole> fromCode(String code) {
        return CodeLabel.find(AdminRole.class, code);
    }
}
