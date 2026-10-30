package com.cdwater.toolshare.config;

import cn.dev33.satoken.stp.StpLogic;

/**
 * Sa-Token 多账号体系门面：user / admin 两套完全隔离的登录会话。
 * new StpLogic(loginType) 构造时自动注册进 SaManager，注解鉴权可用 type 指定体系。
 */
public class StpKit {

    /** 用户端会话 */
    public static final StpLogic USER = new StpLogic("user");

    /** 管理端会话 */
    public static final StpLogic ADMIN = new StpLogic("admin");
}
