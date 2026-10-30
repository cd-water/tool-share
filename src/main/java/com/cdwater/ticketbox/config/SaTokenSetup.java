package com.cdwater.ticketbox.config;

import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

/**
 * Sa-Token 代码化配置：双端差异化参数 + 路由鉴权（不使用 yml）。
 * 在构造器中完成配置注入，确保早于 WebMvcConfigurer 回调执行。
 */
@Configuration
public class SaTokenSetup implements WebMvcConfigurer {

    private static final long DAY_SECONDS = 24 * 60 * 60L;

    public SaTokenSetup() {
        StpKit.USER.setConfig(base()
                .setTimeout(60 * DAY_SECONDS)
                .setActiveTimeout(30 * DAY_SECONDS)
                .setTokenStyle("uuid"));
        StpKit.ADMIN.setConfig(base()
                .setTimeout(15 * DAY_SECONDS)
                .setActiveTimeout(7 * DAY_SECONDS)
                .setTokenStyle("random-64"));
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handler -> {
            // 落到静态资源兜底 handler = 没有匹配的控制器（未知路由）：跳过鉴权，让其自然返回 C404
            if (handler instanceof ResourceHttpRequestHandler) {
                return;
            }
            // 用户端：放行发送验证码与两个登录接口
            SaRouter.match("/api/user/**")
                    .notMatch("/api/user/send-code", "/api/user/login/**")
                    .check(r -> StpKit.USER.checkLogin());
            // 管理端：放行登录接口
            SaRouter.match("/api/admin/**")
                    .notMatch("/api/admin/login")
                    .check(r -> StpKit.ADMIN.checkLogin());
        })).addPathPatterns("/**");
    }

    /** 双端公共配置（每次调用产生独立实例，避免两套 StpLogic 共享可变配置） */
    private SaTokenConfig base() {
        return new SaTokenConfig()
                .setTokenName("Authorization")
                .setTokenPrefix("Bearer")
                .setIsReadHeader(true)
                .setIsReadCookie(false)
                .setIsReadBody(false)
                .setIsConcurrent(true)
                .setIsShare(false)
                .setAutoRenew(true)
                .setIsLog(true);
    }
}
