/**
 * 管理端登录请求体文件。
 * <p>只包含用户名与密码，不包含验证码或登录类型。
 * <h2>校验</h2>
 * <ul>
 *   <li>username：非空白，最长 20 字符，与 t_admin.username 字段一致；</li>
 *   <li>password：非空白，最长 100 字符，避免超长输入进入 BCrypt。</li>
 * </ul>
 * <p>密码只在本请求生命周期内以明文存在，服务层不会记录或回显。
 */
package com.cdwater.toolshare.module.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理端登录请求体。
 * <p>用户名与密码均不允许空白；长度上限与数据库字段保持一致，
 * 防止超长输入进入 BCrypt 校验造成无谓开销。
 */
@Data
public class AdminLoginDTO {

    @NotBlank
    @Size(max = 20, message = "账号不能超过 20 个字符")
    // 管理端登录名，最长 20 个字符，必须非空白
    private String username;

    @NotBlank
    @Size(max = 100, message = "密码不能超过 100 个字符")
    // 明文密码只在请求生命周期内存在，服务层用 BCrypt 与密文比对
    private String password;
}
