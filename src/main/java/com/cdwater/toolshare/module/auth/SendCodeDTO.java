/**
 * 发送验证码请求体文件。
 * <p>只包含手机号，格式必须是中国大陆 11 位号码。
 * <h2>频控</h2>
 * <p>服务端按手机号每分钟、手机号每小时与来源 IP 每小时三个维度限流；
 * 每次发送会覆盖旧验证码并清空错误计数。
 * <p>调用方：UserController#sendCode。
 */
package com.cdwater.toolshare.module.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发送验证码请求体。
 * <p>只接受手机号；同一手机号与来源 IP 的发送频率由服务层限制。
 */
@Data
public class SendCodeDTO {

    @NotBlank
    @Pattern(regexp = "^1[3-9]\\d{9}$")
    @Size(max = 20)
    // 接收验证码的手机号，也是登录时的账号标识
    private String phone;
}
