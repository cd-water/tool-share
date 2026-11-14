package com.cdwater.toolshare.shared.config;

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
 *
 * <p>系统有两套完全隔离的会话：{@link StpKit#USER} 与
 * {@link StpKit#ADMIN}。用户端会话有效期更长（60 天），管理端更短
 * （15 天），降低管理员令牌泄露后的风险。</p>
 *
 * <p>令牌只从 Authorization 请求头读取，不读 Cookie 与请求体，避免
 * CSRF 与跨端串用。并发登录允许多端共存，但不共享令牌。</p>
 *
 * <p>鉴权拦截器放行登录相关路径与静态资源兜底，其余 {@code /api/user/**}
 * 与 {@code /api/admin/**} 分别校验对应端的登录态。权限注解在此之上
 * 再做细粒度校验。</p>
 *
 * <p>双端会话完全隔离：同一浏览器可以同时持有用户令牌与管理令牌，
 * 互不影响。令牌只从请求头读取，不使用 Cookie，因此不存在跨站自动
 * 携带令牌的问题。</p>
 */
@Configuration
public class SaTokenSetup implements WebMvcConfigurer {

    /** 一天的秒数，用于把天数换算成 Sa-Token 的超时配置。 */
    private static final long DAY_SECONDS = 24 * 60 * 60L;

    /**
     * 构造时立即配置两套会话。
     *
     * <p>放在构造器中而不是 {@code @PostConstruct}，是为了确保配置早于
     * WebMvcConfigurer 的回调，避免首个请求到达时配置尚未生效。</p>
     */
    public SaTokenSetup() {
        // 用户端：登录有效期 60 天，30 天无操作自动失效。
        StpKit.USER.setConfig(base()
                .setTimeout(60 * DAY_SECONDS)
                .setActiveTimeout(30 * DAY_SECONDS)
                .setTokenStyle("uuid"));
        // 管理端：有效期与活跃超时都更短，降低令牌被盗用的窗口。
        StpKit.ADMIN.setConfig(base()
                .setTimeout(15 * DAY_SECONDS)
                .setActiveTimeout(7 * DAY_SECONDS)
                // random-64 提供更高熵的令牌，适合高权限会话。
                .setTokenStyle("random-64"));
    }

    /**
     * 注册全局鉴权拦截器。
     *
     * <p>拦截所有路径，但内部按前缀分流：静态资源兜底直接跳过，
     * 用户端放行验证码与登录接口，管理端放行登录接口。</p>
     *
     * @param registry 拦截器注册表
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handler -> {
            // 落到静态资源兜底 handler = 没有匹配的控制器（未知路由）：跳过鉴权，让其自然返回 C404
            if (handler instanceof ResourceHttpRequestHandler) {
                return;
            }
            // 用户端：放行发送验证码与两个登录接口
            // 登录接口必须匿名可访问，否则用户无法获得令牌。
            SaRouter.match("/api/user/**")
                    .notMatch("/api/user/send-code", "/api/user/login/**")
                    .check(r -> StpKit.USER.checkLogin());
            // 管理端：放行登录接口
            // 管理端不提供验证码登录，仅放行账号密码登录。
            SaRouter.match("/api/admin/**")
                    .notMatch("/api/admin/login")
                    .check(r -> StpKit.ADMIN.checkLogin());
        })).addPathPatterns("/**");
    }

    /** 双端公共配置（每次调用产生独立实例，避免两套 StpLogic 共享可变配置） */
    private SaTokenConfig base() {
        return new SaTokenConfig()
                // 令牌通过标准 Authorization 头传递，便于前端统一封装。
                .setTokenName("Authorization")
                // 使用 Bearer 前缀，符合常见 HTTP 鉴权习惯。
                .setTokenPrefix("Bearer")
                // 只读请求头，关闭 Cookie 与请求体读取，降低 CSRF 风险。
                .setIsReadHeader(true)
                .setIsReadCookie(false)
                .setIsReadBody(false)
                // 允许多端同时登录，但不共享同一个令牌。
                .setIsConcurrent(true)
                .setIsShare(false)
                // 活跃期间自动续期，避免用户频繁重新登录。
                .setAutoRenew(true)
                // 打开 Sa-Token 自身日志，便于排查鉴权问题。
                .setIsLog(true);
    }
}
