package com.cdwater.toolshare.module.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 认证相关配置：验证码暴露开关与频控参数。
 */
@ConfigurationProperties(prefix = "toolshare.auth")
public record AuthProperties(boolean exposeCaptcha, int maxAttempts, RateLimit rateLimit) {

    public record RateLimit(int phonePerMinute, int phonePerHour, int ipPerHour) {
    }
}
