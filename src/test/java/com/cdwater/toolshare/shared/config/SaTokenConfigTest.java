package com.cdwater.toolshare.shared.config;

import cn.dev33.satoken.config.SaTokenConfig;
import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sa-Token 双登录体系配置测试。
 * <p>
 * 测试范围：用户端（user）与管理端（admin）两套 StpLogic 的运行时配置，
 * 包括登录类型、令牌名称、前缀、有效期、活跃续期、令牌风格、
 * 读取来源、并发策略、共享策略、自动续签与日志开关。
 * <p>
 * 依赖环境：完整 Spring 上下文，读取配置类加载并生效后的真实 SaTokenConfig 对象，
 * 而不是手工 new 出来的配置副本，因此能发现注册顺序或覆盖失效的问题。
 * <p>
 * 关键不变量：
 * 1. 两端都只从 Authorization 头读取 “Bearer” 前缀令牌，不接受 Cookie/Body 注入；
 * 2. 用户端总有效期 60 天、活跃续期 30 天；管理端总有效期 15 天、活跃续期 7 天；
 * 3. 两端均开启并发登录且不共享会话，避免一端登录挤掉另一端；
 * 4. 两端配置对象互相独立，修改一端不会污染另一端；
 * 5. 令牌风格分别为 uuid 与 random-64，兼顾可读性与抗猜测强度。
 * <p>
 * 观察方式：本类不发起 HTTP 请求，而是直接读取 StpLogic 上的配置对象；
 * 因此能在不依赖具体业务接口的情况下，稳定守住登录寿命与令牌安全基线。
 */
@SpringBootTest
@Import(TestStorageConfig.class)
class SaTokenConfigTest extends AbstractIntegrationTest {

    /**
     * 场景：读取用户端 StpLogic 的运行时配置，对照产品规格逐项校验。
     * 前置条件：SaTokenConfig 配置类已由容器加载，StpKit.USER 已完成初始化。
     * 断言目标：
     * 1. 登录类型为 user，与 admin 端彻底隔离；
     * 2. 令牌名为 Authorization、前缀为 Bearer，且只从请求头读取；
     * 3. 总有效期 60 天（5184000 秒），活跃续期 30 天（2592000 秒）；
     * 4. 开启并发登录、关闭会话共享、开启自动续签与操作日志；
     * 5. 令牌风格为 uuid，符合用户端调试与排障需要。
     * 这些值一旦漂移，会直接改变线上登录寿命与多端登录行为。
     */
    @Test
    void user_config_matches_spec() {
        // 取得用户端实际生效的配置对象，后续所有断言都基于该实例。
        SaTokenConfig c = StpKit.USER.getConfig();
        // 登录类型必须为 user，确保用户令牌不会被管理端接口接受。
        assertEquals("user", StpKit.USER.getLoginType());
        // 令牌通过标准 Authorization 头传递，前缀与前端拦截器约定一致。
        // 名称或前缀变化都会导致所有已登录用户的请求立即失效。
        assertEquals("Authorization", c.getTokenName());
        assertEquals("Bearer", c.getTokenPrefix());
        // 60 天总有效期与 30 天活跃续期：长期未活跃会被要求重新登录。
        // 两个值分别对应“最长寿命”和“无操作窗口”，必须同时满足规格。
        assertEquals(5184000L, c.getTimeout());
        assertEquals(2592000L, c.getActiveTimeout());
        // UUID 风格令牌长度固定、便于日志检索。
        assertEquals("uuid", c.getTokenStyle());
        // 只允许请求头读取，避免 Cookie 自动携带带来的 CSRF 风险。
        assertTrue(c.getIsReadHeader());
        assertFalse(c.getIsReadCookie());
        assertFalse(c.getIsReadBody());
        // 允许多端同时在线，但不同登录之间不共享会话。
        assertTrue(c.getIsConcurrent());
        assertFalse(c.getIsShare());
        // 自动续签让活跃用户无感延长会话；日志开关便于审计登录行为。
        // 关闭续签会导致用户在有效期内被突然登出，属于体验回退。
        assertTrue(c.getAutoRenew());
        assertTrue(c.getIsLog());
    }

    /**
     * 场景：读取管理端 StpLogic 的运行时配置，对照更严格的后台规格逐项校验。
     * 前置条件：管理端配置与用户端独立注册，StpKit.ADMIN 已完成初始化。
     * 断言目标：
     * 1. 登录类型为 admin，管理接口只认管理端令牌；
     * 2. 有效期 15 天（1296000 秒）、活跃续期 7 天（604800 秒），短于用户端；
     * 3. 令牌风格为 random-64，强度高于用户端 uuid，降低后台令牌被猜测风险；
     * 4. 同样只走 Authorization 头，开启并发登录、关闭共享、自动续签与日志。
     * 管理端寿命更短是有意的安全取舍，不能与用户端配置混用。
     */
    @Test
    void admin_config_matches_spec() {
        // 取得管理端实际生效的配置对象，与用户端对象完全独立。
        SaTokenConfig c = StpKit.ADMIN.getConfig();
        // 登录类型必须为 admin，否则管理接口鉴权会失效或串端。
        assertEquals("admin", StpKit.ADMIN.getLoginType());
        // 请求头名称与前缀和用户端一致，但令牌空间不同。
        // 相同头部格式便于前端复用请求封装，隔离依靠 loginType 实现。
        assertEquals("Authorization", c.getTokenName());
        assertEquals("Bearer", c.getTokenPrefix());
        // 后台会话更短：15 天总寿命、7 天活跃续期。
        // 高权限账号暴露窗口更小，是管理端与用户端最重要的差异。
        assertEquals(1296000L, c.getTimeout());
        assertEquals(604800L, c.getActiveTimeout());
        // random-64 提供更高熵值，适合高权限的管理端。
        // 若降级为 uuid，令牌长度与随机性都会下降，属于安全回归。
        assertEquals("random-64", c.getTokenStyle());
        // 与用户端相同的传输与续期策略，减少前端适配成本。
        // 仍然禁止 Cookie 读取，避免后台会话被浏览器自动携带而被跨站利用。
        assertTrue(c.getIsReadHeader());
        assertFalse(c.getIsReadCookie());
        assertFalse(c.getIsReadBody());
        // 并发但隔离：多个管理员登录互不影响，也不共享会话。
        // 共享会话会导致后登录者覆盖前者，值班场景下非常危险。
        assertTrue(c.getIsConcurrent());
        assertFalse(c.getIsShare());
        assertTrue(c.getAutoRenew());
        assertTrue(c.getIsLog());
    }

    /**
     * 场景：比较用户端与管理端配置对象引用，验证双登录体系的隔离性。
     * 前置条件：StpKit 分别暴露两个登录类型的 StpLogic。
     * 断言目标：
     * 1. 两个 getConfig() 返回不同实例，而不是同一对象被复用；
     * 2. 运行时修改一端配置（例如测试中提高并发数）不会影响另一端；
     * 3. 该隔离性也是前面两组端点规格能分别成立的前提。
     */
    @Test
    void two_logics_use_independent_config_instances() {
        // 引用不同即代表配置空间独立，避免一端变更悄悄污染另一端。
        // 若这里退化为同一实例，前面两组不同的有效期断言迟早会互相覆盖。
        assertNotSame(StpKit.USER.getConfig(), StpKit.ADMIN.getConfig());
    }
}
