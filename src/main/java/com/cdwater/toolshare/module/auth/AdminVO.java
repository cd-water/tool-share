/**
 * 管理端账号视图文件。
 * <p>用于登录后信息与账号列表展示，只包含 id、username、role。
 * 有意不包含 password、status 与时间字段，避免把认证敏感信息暴露到接口。
 * <p>role 的可能取值由 AdminRole 枚举决定：STAFF / SUPER_ADMIN。
 * <p>调用方：AdminService、AdminAccountController。
 */
package com.cdwater.toolshare.module.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 管理端账号视图。
 * <p>只暴露接口展示所需字段，绝不包含密码或密码哈希。
 */
@Data
public class AdminVO {

    // 管理端账号主键
    private Long id;

    // 登录名
    private String username;

    /** STAFF / SUPER_ADMIN */
    @Schema(allowableValues = {"STAFF", "SUPER_ADMIN"})
    private String role;
}
