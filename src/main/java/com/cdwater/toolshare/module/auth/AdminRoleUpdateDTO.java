/**
 * 修改管理端角色请求体文件。
 * <p>只有一个 role 字段；编码必须属于 AdminRole 枚举，
 * 当前允许 STAFF（工作人员）与 SUPER_ADMIN（系统管理员）。
 * <h2>约束</h2>
 * <p>非空白且最长 20 字符；服务层会再次按枚举解析，
 * 未知编码返回 400，不会把任意字符串写入数据库。
 * <p>调用方：AdminAccountController#updateRole。
 */
package com.cdwater.toolshare.module.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改管理端角色的请求体。
 * <p>只允许 {@code STAFF} 与 {@code SUPER_ADMIN} 两种角色；
 * 角色到权限的映射由 {@code AdminRole} 枚举维护。
 */
@Data
public class AdminRoleUpdateDTO {

    @NotBlank(message = "角色不能为空")
    @Size(max = 20, message = "角色不能超过 20 个字符")
    @Schema(allowableValues = {"STAFF", "SUPER_ADMIN"})
    // 目标角色编码；大小写与枚举 code 一致，非法值由服务层拒绝
    private String role;
}
