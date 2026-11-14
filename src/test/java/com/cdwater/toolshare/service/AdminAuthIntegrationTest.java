package com.cdwater.toolshare.service;

import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 集成测试：管理端三接口 + 双账号体系隔离。
 * 依赖 Testcontainers 自动启动 MySQL/Redis；seed 管理员 admin/Aa123456。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestStorageConfig.class)
class AdminAuthIntegrationTest extends AbstractIntegrationTest {

    @LocalServerPort
    // 管理端接口运行在随机端口，测试通过真实 HTTP 请求覆盖拦截器与鉴权链路。
    // 端口只在本测试进程内有效。
    // 随机端口由 Spring Boot 分配，测试无需预知具体端口号。
    // 字段注入由 Spring 测试上下文完成。
    int port;

    // 每个用例前按随机端口重建客户端，避免连接状态在用例间泄漏。
    // 管理端与用户端共用同一客户端，只靠 token 与路径区分体系。
    // 客户端不保存登录状态，所有鉴权信息都显式放在请求头中。
    // 客户端实例只在本用例内使用，不跨线程共享。
    RestClient client;
    // 响应统一解析为 JsonNode，断言只关注业务 code 与关键数据字段。
    // ObjectMapper 无状态，可跨用例共享。
    // 只用于解析响应体，避免依赖容器内的 Jackson 配置。
    // 解析失败会直接让用例失败，属于测试基础设施问题。
    final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // 指向本次启动的随机端口，避免与开发机上的其他服务冲突。
        // 不附加任何默认请求头，鉴权信息由各辅助方法按需添加。
        // 地址使用 localhost，确保请求不经过外部网络。
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private JsonNode post(String url, Map<String, String> body) {
        // 无 token 的重载用于登录等公开接口。
        // 统一委托给带 token 的实现，减少请求构造的重复代码。
        // null token 表示公开接口，不会附加 Authorization 头。
        // 公共接口不需要认证，统一传 null 保持调用形式一致。
        return post(url, body, null);
    }

    /** 带 Bearer token 的 POST（用于受拦截器保护的接口，如 logout） */
    private JsonNode post(String url, Map<String, String> body, String tokenValue) {
        // 受保护接口需要 Bearer token，这里按需附加 Authorization 头。
        // 请求体统一为 JSON，与后端接口契约一致。
        // 先构造请求规格，再根据是否有 token 决定是否加认证头。
        // Content-Type 固定为 JSON，服务端据此选择消息转换器。
        RestClient.RequestBodySpec spec = client.post().uri(url)
                .contentType(MediaType.APPLICATION_JSON);
        // token 为空表示公开接口，不添加认证头。
        // 非空时按 Bearer 方案拼接，格式错误应由拦截器拒绝。
        // 空 token 不添加认证头，便于验证未登录场景。
        if (tokenValue != null) {
            spec.header("Authorization", "Bearer " + tokenValue);
        }
        // 不论 HTTP 状态码如何都读取响应体，业务错误通过 code 字段表达。
        // 这样可以在同一个辅助方法中断言 4xx 与成功响应。
        // 读取原始字符串而非直接绑定对象，便于同时处理成功与错误响应。
        // 请求体序列化失败会在此处抛出，属于测试代码问题。
        String raw = spec.body(body).retrieve().onStatus(status -> true, (request, response) -> {
        }).body(String.class);
        return om.readTree(raw == null ? "{}" : raw);
    }

    private JsonNode get(String url, String tokenValue) {
        // GET 辅助方法用于查询用户/管理员信息等只读接口。
        // 与 POST 一样支持可选的 Bearer token。
        // GET 请求不携带请求体，token 通过请求头传递。
        RestClient.RequestHeadersSpec<?> spec = client.get().uri(url);
        if (tokenValue != null) {
            spec.header("Authorization", "Bearer " + tokenValue);
        }
        // 关闭状态码到异常的默认映射，统一由业务码判断结果。
        // 空处理器不修改响应，仅阻止 RestClient 抛异常。
        // 与 POST 辅助方法保持一致的错误处理策略。
        String raw = spec.retrieve().onStatus(status -> true, (request, response) -> {
        }).body(String.class);
        return om.readTree(raw == null ? "{}" : raw);
    }

    /** 管理员登录并返回 tokenValue */
    private String adminLogin() {
        // 使用 seed 管理员凭据登录，走真实密码校验与 token 签发流程。
        // 登录失败会由下方断言直接暴露，避免后续用例在无 token 状态下继续。
        // 登录请求体只包含账号与密码，不传角色等可伪造字段。
        // 登录成功后立即提取 token，失败则由断言拦截。
        JsonNode node = post("/api/admin/login",
                Map.of("username", "admin", "password", "Aa123456"));
        assertEquals("A200", node.get("code").asString(), node.toString());
        // 提取 tokenValue 供受保护接口使用。
        // 管理端 token 与用户端 token 互不通用，这是双账号体系隔离的基础。
        // data 结构由统一响应封装产生，tokenValue 是前端实际使用的凭证字段。
        return node.get("data").get("tokenValue").asString();
    }

    /**
     * 场景：使用 seed 管理员账号与正确密码登录管理端。
     * 前置条件：启动种子已写入 admin/Aa123456 管理员。
     * 断言目标：返回 A200 且 tokenValue 非空，证明登录链路可用。
     * 管理端与用户端各自维护 token 命名空间，不能互相替代。
     * 该 token 是后续信息查询与登出用例的前置依赖。
     */
    @Test
    void admin_login_success() {
        String token = adminLogin();
        // token 非空说明服务端成功签发并返回了凭证。
        // 只断言非空而不校验具体内容，避免测试耦合 token 生成算法。
        // 只需要确认凭证存在，不关心其具体编码格式。
        assertFalse(token.isEmpty());
    }

    /**
     * 场景：管理员账号存在但密码错误。
     * 前置条件：username 为 admin，password 为错误值。
     * 断言目标：返回 C400，且错误信息不区分「账号不存在」与「密码错误」。
     * 统一提示可以避免攻击者枚举管理员账号。
     * 登录失败不应签发 token，也不应产生服务端 5xx。
     */
    @Test
    void admin_login_wrong_password() {
        JsonNode node = post("/api/admin/login",
                // 账号正确、密码错误，用于单独验证密码比对分支。
                // Map.of 构造的请求体不可变，避免辅助方法意外修改。
                Map.of("username", "admin", "password", "wrong"));
        // 认证失败使用客户端错误码，避免暴露内部异常细节。
        // C400 是该接口约定的统一失败编码。
        // 失败编码固定为 C400，便于前端统一处理。
        assertEquals("C400", node.get("code").asString());
        // 提示文案必须模糊，不能泄露账号是否存在。
        assertEquals("账号或密码错误", node.get("message").asString());
    }

    /**
     * 场景：使用不存在的管理员账号登录。
     * 前置条件：username 为 nobody，密码任意。
     * 断言目标：返回与密码错误完全一致的 C400 与提示文案。
     * 两条失败路径的一致性可防止账号枚举攻击。
     * 服务端应先查账号再校验密码，但对外只暴露统一结果。
     */
    @Test
    void admin_login_unknown_user_same_error() {
        JsonNode node = post("/api/admin/login",
                // 故意使用一个不存在的用户名。
                // 密码本身合法，确保失败原因只可能是账号不存在。
                // 用户名不存在时也应走与密码错误相同的失败分支。
                Map.of("username", "nobody", "password", "Aa123456"));
        assertEquals("C400", node.get("code").asString());
        assertEquals("账号或密码错误", node.get("message").asString());
    }

    /**
     * 场景：携带管理员登录得到的 token 查询管理端信息。
     * 前置条件：token 由 adminLogin 成功签发，管理端拦截器认可该 token。
     * 断言目标：返回 A200，且 username 与登录账号一致。
     * 该用例验证 token 透传、解析与身份回填的完整链路。
     * 返回字段不包含密码等敏感信息。
     */
    @Test
    void admin_info_with_token() {
        String token = adminLogin();
        // 通过带 Bearer 头的 GET 请求访问受保护接口。
        // 若拦截器未正确解析 token，会返回 C401 而不是用户信息。
        // 该接口只读，不会修改管理员资料。
        // 携带管理端 token 访问管理端信息接口。
        JsonNode node = get("/api/admin/info", token);
        assertEquals("A200", node.get("code").asString());
        // 身份信息来自 token 对应的管理员记录，而不是请求参数。
        // 断言用户名确认返回的是当前登录账号。
        // 这也是后续双账号隔离用例的对照基线。
        assertEquals("admin", node.get("data").get("username").asString());
    }

    /**
     * 场景：管理员登录后主动登出，再次使用同一 token 查询信息。
     * 前置条件：logout 接口受拦截器保护，必须携带有效 token。
     * 断言目标：登出返回 A200，随后 info 返回 C401。
     * 说明 token 在服务端被加入失效集合，而非仅由客户端丢弃。
     * 该机制保证凭证泄露后的主动吊销能力。
     */
    @Test
    void admin_logout_invalidates_token() {
        String token = adminLogin();
        // logout 受拦截器保护，必须携带 token
        // 登出请求携带当前 token，服务端据此定位需要吊销的会话。
        // 请求体为空，仅依赖 Authorization 头。
        // 空请求体配合有效 token，是登出接口的典型调用方式。
        JsonNode logout = post("/api/admin/logout", Map.of(), token);
        // 登出成功返回业务成功码。
        // 重复登出应保持幂等，但本用例只覆盖首次登出。
        assertEquals("A200", logout.get("code").asString());
        // 旧 token 立即失效，证明吊销在服务端生效。
        // 若仍返回 A200，说明登出只清理了客户端状态。
        // C401 而非 C403，因为该 token 已不再代表有效会话。
        assertEquals("C401", get("/api/admin/info", token).get("code").asString());
    }

    /**
     * 场景：使用用户端 token 访问管理端受保护接口。
     * 前置条件：用户端 seed 用户1 登录成功并取得 token。
     * 断言目标：管理端拦截器不认可用户 token，返回 C401。
     * 双账号体系使用不同的 token 前缀与校验逻辑，杜绝越权。
     * 即使两端都使用 Bearer 方案，凭证也不能跨体系复用。
     * 这是管理端安全边界的关键不变量。
     */
    @Test
    void user_token_cannot_access_admin_api() {
        // 用户端 token（seed 用户1）访问管理端接口 → 管理端体系未登录 → C401
        // 先以普通用户身份登录，拿到合法但属于用户端的 token。
        // 该请求走用户端接口，不应受管理端拦截器影响。
        // 用户端登录使用独立路径，避免与管理端登录混淆。
        JsonNode userLogin = post("/api/user/login/by-pwd",
                Map.of("phone", "13800000001", "password", "Aa123456"));
        // 提取用户端 token，用于尝试访问管理端接口。
        String userToken = userLogin.get("data").get("tokenValue").asString();
        // 管理端应把用户 token 视为未认证，而不是解析出用户身份。
        // 返回 C401 表明请求在进入业务逻辑前就被拦截。
        // 断言的是管理端接口，确保越权路径被真正覆盖。
        assertEquals("C401", get("/api/admin/info", userToken).get("code").asString());
    }

    /**
     * 场景：使用管理员 token 访问用户端受保护接口。
     * 前置条件：管理员登录成功并取得管理端 token。
     * 断言目标：用户端拦截器不认可管理 token，返回 C401。
     * 与上一用例共同证明隔离是双向的，而非只保护管理端。
     * 双向隔离可避免管理员凭证被误用于用户侧业务操作。
     */
    @Test
    void admin_token_cannot_access_user_api() {
        // 取得合法的管理端 token。
        // 管理员登录辅助方法内部已断言成功，此处可直接使用返回值。
        String adminToken = adminLogin();
        // 用户端接口只接受用户体系签发的 token。
        // 越权请求在拦截器阶段被拒绝，不会触达用户数据。
        // 与反向用例一起构成完整的双账号隔离验证。
        assertEquals("C401", get("/api/user/info", adminToken).get("code").asString());
    }
}
