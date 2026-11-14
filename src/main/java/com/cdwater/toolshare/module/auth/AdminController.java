/**
 * 管理端认证接口文件。
 * <p>路径：/api/admin，提供登录、注销与当前账号信息。
 * <h2>登录流程</h2>
 * <p>账号密码 -> AdminService 校验 BCrypt 与启用状态 -> Sa-Token 建立管理端会话
 * -> 返回 tokenName/tokenValue，前端放入请求头。
 * <h2>安全约束</h2>
 * <p>账号不存在与密码错误使用同一文案；被禁用账号即使密码正确也拒绝。
 * 注销立即失效当前 token。
 * <h2>与账号管理的关系</h2>
 * <p>本控制器不提供账号增删改，角色维护见 AdminAccountController。
 */
package com.cdwater.toolshare.module.auth;

import com.cdwater.toolshare.shared.web.Result;
import com.cdwater.toolshare.module.auth.AdminLoginDTO;
import com.cdwater.toolshare.module.auth.AdminService;
import com.cdwater.toolshare.module.auth.AdminVO;
import com.cdwater.toolshare.module.auth.LoginVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端登录入口。
 * <p>只处理登录、注销与当前账号信息，不承载账号管理（见 {@link AdminAccountController}）。
 * 登录成功后返回 Sa-Token 的 token 名称与值，由前端放入请求头。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    // 认证逻辑集中在 AdminService，控制器保持无状态
    private final AdminService adminService;

    @PostMapping("/login")
    /**
     * 管理端账号密码登录。
     * <p>账号不存在、密码错误与账号禁用分别由服务层给出统一提示，
     * 避免通过错误文案枚举管理端账号。
     *
     * @param dto 账号与密码
     * @return token 名称与 token 值
     */
    public Result<LoginVO> login(@Valid @RequestBody AdminLoginDTO dto) {
        return Result.success(adminService.login(dto));
    }

    @PostMapping("/logout")
    /**
     * 注销当前管理端会话。
     *
     * @return 空成功响应；未登录时也按幂等成功处理
     */
    public Result<Void> logout() {
        adminService.logout();
        return Result.success();
    }

    @GetMapping("/info")
    /**
     * 查询当前登录管理端账号的信息。
     *
     * @return 当前管理端账号视图
     */
    public Result<AdminVO> info() {
        return Result.success(adminService.info());
    }
}
