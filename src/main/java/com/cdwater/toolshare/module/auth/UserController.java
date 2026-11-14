/**
 * 用户端认证接口文件。
 * <p>路径：/api/user，提供验证码发送、验证码登录、密码登录、注销与当前用户信息。
 * <h2>流程</h2>
 * <p>发送验证码 -> 验证码登录（手机号不存在则静默注册）或密码登录
 * -> 返回 token -> 前端将 token 放入请求头。
 * <h2>安全约束</h2>
 * <p>手机号格式在入参层校验；验证码一次性消费；登录失败文案统一；
 * 来源 IP 取自 remoteAddr，用于发送频控。
 * <h2>相关接口</h2>
 * <p>个人资料维护见 UserProfileController，两者共用 /api/user 前缀。
 */
package com.cdwater.toolshare.module.auth;

import com.cdwater.toolshare.shared.web.Result;
import com.cdwater.toolshare.module.auth.CodeLoginDTO;
import com.cdwater.toolshare.module.auth.PwdLoginDTO;
import com.cdwater.toolshare.module.auth.SendCodeDTO;
import com.cdwater.toolshare.module.auth.UserService;
import com.cdwater.toolshare.module.auth.LoginVO;
import com.cdwater.toolshare.module.auth.UserVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端认证入口。
 * <p>提供验证码发送、验证码登录、密码登录、注销与当前用户信息；
 * 个人资料维护见 {@link UserProfileController}。
 * <p>登录态由 Sa-Token 用户端体系维护，与管理端完全隔离。
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    // 认证与用户资料逻辑集中在 UserService
    private final UserService userService;

    @PostMapping("/send-code")
    /**
     * 发送登录验证码。
     * <p>来源 IP 取自直接连接的 remoteAddr，用于服务层按 IP 限流。
     * 开发环境可能直接返回验证码，生产环境只写日志并返回 null。
     *
     * @param dto     手机号
     * @param request 用于获取来源 IP
     * @return 暴露开关打开时返回验证码，否则返回 null
     */
    public Result<String> sendCode(@Valid @RequestBody SendCodeDTO dto, HttpServletRequest request) {
        return Result.success(userService.sendCode(dto.getPhone(), request.getRemoteAddr()));
    }

    @PostMapping("/login/by-code")
    /**
     * 手机号 + 验证码登录；手机号不存在时静默注册。
     *
     * @param dto 手机号与验证码
     * @return token 信息
     */
    public Result<LoginVO> loginByCode(@Valid @RequestBody CodeLoginDTO dto) {
        return Result.success(userService.loginByCode(dto));
    }

    @PostMapping("/login/by-pwd")
    /**
     * 手机号 + 密码登录。
     *
     * @param dto 手机号与密码
     * @return token 信息
     */
    public Result<LoginVO> loginByPwd(@Valid @RequestBody PwdLoginDTO dto) {
        return Result.success(userService.loginByPwd(dto));
    }

    @PostMapping("/logout")
    /**
     * 注销当前用户会话。
     *
     * @return 空成功响应
     */
    public Result<Void> logout() {
        userService.logout();
        return Result.success();
    }

    @GetMapping("/info")
    /**
     * 查询当前登录用户信息（手机号与身份证均已脱敏）。
     *
     * @return 当前用户视图
     */
    public Result<UserVO> info() {
        return Result.success(userService.info());
    }
}
