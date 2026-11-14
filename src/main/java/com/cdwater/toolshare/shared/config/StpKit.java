package com.cdwater.toolshare.shared.config;

import cn.dev33.satoken.stp.StpLogic;

/**
 * Sa-Token 多账号体系门面：user / admin 两套完全隔离的登录会话。
 * new StpLogic(loginType) 构造时自动注册进 SaManager，注解鉴权可用 type 指定体系。
 *
 * <p>两套 StpLogic 各自维护独立令牌、会话与权限列表，用户端登录不会
 * 影响管理端会话，反之亦然。业务代码通过本类取得对应端的会话，而不是
 * 使用 Sa-Token 的全局默认实例，避免双端串号。</p>
 *
 * <p>令牌读取、有效期与鉴权路由由 {@link SaTokenSetup} 统一配置；本类
 * 只暴露会话对象，不包含可变状态。</p>
 */
public class StpKit {

    /** 用户端会话 */
    /*
     * <p>用于 {@code /api/user/**} 的登录校验与用户 ID 读取，令牌有效期
     * 较长，适合普通消费者场景。</p>
     */
    public static final StpLogic USER = new StpLogic("user");

    /** 管理端会话 */
    /*
     * <p>用于 {@code /api/admin/**} 的登录校验与权限判断，令牌有效期更短，
     * 并通过 {@code @SaCheckPermission(type = "admin")} 做细粒度授权。</p>
     */
    public static final StpLogic ADMIN = new StpLogic("admin");
}
