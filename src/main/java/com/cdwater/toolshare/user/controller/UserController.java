package com.cdwater.toolshare.user.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.cdwater.toolshare.common.constant.RoleConstant;
import com.cdwater.toolshare.common.result.PageResult;
import com.cdwater.toolshare.common.result.Result;
import com.cdwater.toolshare.user.dto.LoginDTO;
import com.cdwater.toolshare.user.dto.RegisterDTO;
import com.cdwater.toolshare.user.dto.UserQueryDTO;
import com.cdwater.toolshare.user.dto.UserRoleDTO;
import com.cdwater.toolshare.user.dto.UserStatusDTO;
import com.cdwater.toolshare.user.service.UserService;
import com.cdwater.toolshare.user.vo.LoginVO;
import com.cdwater.toolshare.user.vo.UserVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户接口
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /** 登录 */
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        return Result.success(userService.login(dto));
    }

    /** 居民注册 */
    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterDTO dto) {
        userService.register(dto);
        return Result.success();
    }

    /** 当前登录用户信息 */
    @GetMapping("/me")
    public Result<UserVO> me() {
        return Result.success(userService.me());
    }

    /** 用户分页 */
    @SaCheckRole(RoleConstant.ADMIN)
    @GetMapping("/page")
    public Result<PageResult<UserVO>> page(@Valid UserQueryDTO query) {
        return Result.success(userService.page(query));
    }

    /** 修改用户角色 */
    @SaCheckRole(RoleConstant.ADMIN)
    @PutMapping("/{id}/role")
    public Result<Void> updateRole(@PathVariable Long id, @Valid @RequestBody UserRoleDTO dto) {
        userService.updateRole(id, dto);
        return Result.success();
    }

    /** 启用/停用用户 */
    @SaCheckRole(RoleConstant.ADMIN)
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id, @Valid @RequestBody UserStatusDTO dto) {
        userService.updateStatus(id, dto);
        return Result.success();
    }
}
