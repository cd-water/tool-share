/**
 * 管理端账号管理接口文件。
 * <p>路径：/api/admin/admins，受 {@code admin:manage} 权限保护，
 * 只有系统管理员可以访问。
 * <h2>接口</h2>
 * <ul>
 *   <li>GET /：账号列表，按 id 升序；</li>
 *   <li>PATCH /{id}/role：修改角色。</li>
 * </ul>
 * <h2>约束</h2>
 * <p>返回对象不包含密码哈希；角色编码必须是 STAFF 或 SUPER_ADMIN；
 * 目标账号不存在返回 404。控制器不承载认证逻辑，
 * 登录与当前账号信息见 AdminController。
 * <p>角色修改即时影响下一次接口鉴权，但不会主动注销目标账号的现有会话。
 */
package com.cdwater.toolshare.module.auth;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.cdwater.toolshare.shared.web.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端账号管理：仅系统管理员可访问。
 */
@RestController
@RequestMapping("/api/admin/admins")
@RequiredArgsConstructor
@SaCheckPermission(type = "admin", value = "admin:manage")
public class AdminAccountController {

    // 管理端账号的查询与角色维护都委托给 AdminService，控制器只做参数绑定
    private final AdminService adminService;

    @GetMapping
    /**
     * 管理端账号列表。
     * <p>按 id 升序返回全部管理端账号，仅暴露 id、username、role，
     * 不返回密码或其它敏感字段。
     *
     * @return 管理端账号视图列表
     */
    public Result<List<AdminVO>> list() {
        return Result.success(adminService.list());
    }

    @PatchMapping("/{id}/role")
    /**
     * 修改指定管理端账号的角色。
     * <p>角色编码必须属于 {@code STAFF} / {@code SUPER_ADMIN}，非法值返回 400；
     * 账号不存在返回 404。角色变更在下一次鉴权时即时生效。
     *
     * @param id  目标管理端账号 id
     * @param dto 仅包含 role 字段的请求体
     * @return 空成功响应
     */
    public Result<Void> updateRole(@PathVariable Long id, @Valid @RequestBody AdminRoleUpdateDTO dto) {
        adminService.updateRole(id, dto.getRole());
        return Result.success();
    }
}
