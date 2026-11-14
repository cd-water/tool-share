/**
 * 用户个人资料接口文件。
 * <p>路径：/api/user/profile，独立于认证接口，便于按职责划分。
 * <h2>接口</h2>
 * <ul>
 *   <li>GET /profile：查询当前用户脱敏资料；</li>
 *   <li>PUT /profile：按字段可选更新资料。</li>
 * </ul>
 * <h2>数据保护</h2>
 * <p>返回的手机号与身份证始终是脱敏值；
 * 身份证非空时加密落库，空值表示保留原值。
 * <p>所有接口依赖用户登录态，未登录由全局鉴权拦截。
 */
package com.cdwater.toolshare.module.auth;

import com.cdwater.toolshare.shared.web.Result;
import com.cdwater.toolshare.module.auth.UserService;
import com.cdwater.toolshare.module.auth.ProfileUpdateDTO;
import com.cdwater.toolshare.module.auth.UserVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户个人资料接口。
 * <p>与登录接口共用 {@code /api/user} 前缀，但独立成控制器便于按资料维护职责划分。
 * 所有接口都要求用户登录态。
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserProfileController {

    // 资料读写复用 UserService，保持登录与资料口径一致
    private final UserService userService;

    @GetMapping("/profile")
    /**
     * 查询当前用户个人资料。
     * <p>返回前对手机号与身份证号做脱敏处理。
     *
     * @return 脱敏后的用户资料
     */
    public Result<UserVO> profile() {
        return Result.success(userService.profile());
    }

    @PutMapping("/profile")
    /**
     * 更新当前用户个人资料。
     * <p>字段为空表示不修改；身份证号非空时先加密再落库。
     *
     * @param dto 可选的资料字段
     * @return 更新后的脱敏资料
     */
    public Result<UserVO> update(@Valid @RequestBody ProfileUpdateDTO dto) {
        return Result.success(userService.updateProfile(dto));
    }
}
