package com.cdwater.toolshare.module.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
  * <p>配置前缀 {@code toolshare.auth}；exposeCaptcha 只允许在开发/测试环境打开。
  *
  * @param exposeCaptcha 是否在发送验证码接口的响应中直接返回验证码
  * @param maxAttempts   同一验证码允许的最大错误次数，达到后验证码立即失效
  * @param rateLimit     验证码发送频控参数，分别按手机号和 IP 限流
 * 认证相关配置：验证码暴露开关与频控参数。
 */
@ConfigurationProperties(prefix = "toolshare.auth")
public record AuthProperties(boolean exposeCaptcha, int maxAttempts, RateLimit rateLimit) {

    /**
     * 验证码发送频率限制。
     *
     * @param phonePerMinute 同一手机号每分钟最多发送次数
     * @param phonePerHour   同一手机号每小时最多发送次数
     * @param ipPerHour      同一来源 IP 每小时最多发送次数
     */
    public record RateLimit(int phonePerMinute, int phonePerHour, int ipPerHour) {
    }
}
