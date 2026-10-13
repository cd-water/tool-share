package com.cdwater.toolshare.common.constant;

/**
 * 角色标识（sa-token @SaCheckRole 用），与 user.role 数值对应：0居民 1社区工作人员 2系统管理员
 */
public final class RoleConstant {

    public static final String RESIDENT = "resident";
    public static final String STAFF = "staff";
    public static final String ADMIN = "admin";

    private RoleConstant() {
    }
}
