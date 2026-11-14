/**
 * 手机号 + 验证码登录请求体文件。
 * <p>手机号必须匹配中国大陆 11 位格式（1[3-9] 开头），
 * 验证码必须是 6 位数字。
 * <h2>校验与消费</h2>
 * <p>格式在入参阶段校验；验证码的一次性消费在 UserService 的 Redis Lua 脚本中完成，
 * 并发登录最多一个请求成功。
 * <p>调用方：UserController#loginByCode。
 */
package com.cdwater.toolshare.module.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 手机号 + 验证码登录请求体。
 * <p>手机号与验证码格式在入参阶段校验，服务层只负责一次性消费验证码。
 */
@Data
public class CodeLoginDTO {

    @NotBlank
    @Pattern(regexp = "^1[3-9]\\d{9}$")
    @Size(max = 20)
    // 中国大陆手机号，11 位且以 1[3-9] 开头
    private String phone;

    @NotBlank
    @Pattern(regexp = "^\\d{6}$")
    // 6 位数字验证码，由发送验证码接口生成
    private String code;
}
