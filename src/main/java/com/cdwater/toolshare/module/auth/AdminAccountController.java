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
 * 管理员账号管理：仅系统管理员可访问。
 */
@RestController
@RequestMapping("/api/admin/admins")
@RequiredArgsConstructor
@SaCheckPermission(type = "admin", value = "admin:manage")
public class AdminAccountController {

    private final AdminService adminService;

    @GetMapping
    public Result<List<AdminVO>> list() {
        return Result.success(adminService.list());
    }

    @PatchMapping("/{id}/role")
    public Result<Void> updateRole(@PathVariable Long id, @Valid @RequestBody AdminRoleUpdateDTO dto) {
        adminService.updateRole(id, dto.getRole());
        return Result.success();
    }
}
