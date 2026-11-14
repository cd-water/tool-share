/**
 * 手机号 + 密码登录请求体文件。
 * <p>仅适用于已设置密码的用户；验证码静默注册的用户密码为空，
 * 走此入口会得到统一的“手机号或密码错误”提示。
  * <h2>与验证码登录的区别</h2>
  * <p>验证码登录会在手机号不存在时静默注册；
  * 密码登录不会注册新用户，也不会提示“未设置密码”，
  * 三种失败情况共用同一文案，避免枚举已注册手机号。
 * <h2>校验</h2>
 * <p>手机号必须匹配中国大陆 11 位格式；密码非空白且最长 100 字符。
 * <p>调用方：UserController#loginByPwd。
 */
package com.cdwater.toolshare.module.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 手机号 + 密码登录请求体。
 * <p>仅适用于已设置密码的用户；静默注册用户的密码为空，不能走此入口。
 */
@Data
public class PwdLoginDTO {

    @NotBlank
    @Pattern(regexp = "^1[3-9]\\d{9}$")
    @Size(max = 20)
    // 中国大陆手机号，同时作为用户账号唯一标识
    private String phone;

    @NotBlank
    @Size(max = 100, message = "密码不能超过 100 个字符")
    // 明文密码，服务层与 BCrypt 密文比对
    // 明文密码，服务层用 BCrypt 比对
    private String password;
}
