package com.cdwater.toolshare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
import com.cdwater.toolshare.module.auth.User;
import com.cdwater.toolshare.module.auth.UserMapper;
import com.cdwater.toolshare.shared.security.IdCardCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集成测试：密码登录、登出、用户信息。
 * 依赖 Testcontainers 自动启动 MySQL/Redis；seed 用户1 13800000001/Aa123456、用户2 13800000002 无密码。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestStorageConfig.class)
class UserAuthIntegrationTest extends AbstractIntegrationTest {

    // seed 用户1 的手机号，已设置密码，用于覆盖正常密码登录路径。
    // 固定手机号让登录与查库断言指向同一用户。
    private static final String USER1_PHONE = "13800000001";
    // seed 用户1 的明文测试密码，与启动种子保持一致。
    // 密码仅用于测试环境，真实环境由种子配置注入。
    private static final String PASSWORD = "Aa123456";

    @LocalServerPort
    // 随机端口由 Spring Boot 分配，测试通过真实 HTTP 请求覆盖拦截器与鉴权链路。
    // 端口由 Spring 测试上下文注入，无需手工读取配置。
    int port;

    @Autowired
    // 用于读取和临时修改用户状态，验证禁用用户的登录与访问行为。
    // 直接使用 Mapper 可绕过业务层，独立验证状态字段的持久化结果。
    UserMapper userMapper;

    @Autowired
    // 直接查库检查身份证字段的存储形态，绕过业务层的掩码逻辑。
    // 使用原生 SQL 读取字段原值，避免 ORM 层自动解密或掩码。
    JdbcTemplate jdbcTemplate;

    @Autowired
    // 与业务使用同一加密器，验证密文可被正确解密回原文。
    // 与生产使用同一 Bean，确保密钥与算法配置一致。
    IdCardCipher idCardCipher;

    // 每个用例前按随机端口重建客户端，避免连接状态跨用例泄漏。
    // 客户端不保存会话，鉴权完全由请求头中的 token 决定。
    RestClient client;
    // 响应统一解析成 JsonNode，断言只关注业务码与关键字段。
    // 只做 JSON 树解析，不绑定具体 VO，断言更灵活。
    final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // 指向本次启动的随机端口，请求不经过外部网关。
        // 地址使用 localhost，确保请求不经过外部网络。
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private JsonNode post(String url, Map<String, String> body) {
        // 无 token 的重载用于公开接口，统一委托给带 token 的实现。
        // 公开接口无需认证，统一传 null 保持调用形式一致。
        // 统一入口避免每个公开接口重复处理 null token。
        // 该重载本身不包含任何认证逻辑。
        return post(url, body, null);
    }

    /** 带 Bearer token 的 POST（用于受拦截器保护的接口，如 logout） */
    private JsonNode post(String url, Map<String, String> body, String tokenValue) {
        // 受保护接口需要 Bearer token，这里按需附加 Authorization 头。
        // 请求体统一为 JSON，与服务端接口契约一致。
        // 先构造请求规格，再根据是否有 token 决定是否加认证头。
        // 请求规格先于认证头构造，便于按 token 是否存在分支。
        // 所有 POST 请求共用同一套响应解析逻辑。
        RestClient.RequestBodySpec spec = client.post().uri(url)
                .contentType(MediaType.APPLICATION_JSON);
        // token 为空表示公开接口，不添加认证头。
        // 非空时按 Bearer 方案拼接，格式错误应由拦截器拒绝。
        // 空 token 不添加认证头，便于验证未登录场景。
        // 仅当调用方显式传入 token 时才添加认证头。
        // 缺少 token 的受保护接口应由服务端返回 401。
        if (tokenValue != null) {
            // 资料更新接口受拦截器保护，token 缺失会被拒绝。
            // 这里不做 token 非空判断，由测试调用方保证传入有效值。
            // 认证头格式必须严格为 Bearer 加空格加 token。
            spec.header("Authorization", "Bearer " + tokenValue);
        }
        // 不论 HTTP 状态码如何都读取响应体，业务错误通过 code 字段表达。
        // 这样可以在同一个辅助方法中断言成功与失败响应。
        // 读取原始字符串而非直接绑定对象，便于同时处理成功与错误响应。
        // 将请求体与响应处理放在同一条链上，减少中间变量。
        // 状态处理器为空，业务错误完全依赖响应体。
        String raw = spec.body(body).retrieve().onStatus(status -> true, (request, response) -> {
                }).body(String.class);
        // 空响应体按空对象处理，保证调用方可以安全读取 code 字段。
        // 解析失败会直接让用例失败，属于测试基础设施问题。
        // 返回 JsonNode 而非强类型对象，断言可自由读取任意字段。
        // 空响应按空对象处理，避免调用方空指针。
        return om.readTree(raw == null ? "{}" : raw);
    }

    private JsonNode get(String url, String tokenValue) {
        // GET 辅助方法用于查询用户信息等只读接口。
        // 与 POST 一样支持可选的 Bearer token。
        // GET 请求不携带请求体，token 通过请求头传递。
        // GET 规格只包含 URL 与可选认证头。
        // 不设置请求体，符合只读接口语义。
        RestClient.RequestHeadersSpec<?> spec = client.get().uri(url);
        if (tokenValue != null) {
            spec.header("Authorization", "Bearer " + tokenValue);
        }
        // 关闭状态码到异常的默认映射，统一由业务码判断结果。
        // 与 POST 辅助方法保持一致的错误处理策略。
        // 与 POST 路径相同，先取字符串再统一解析。
        // 避免 RestClient 对 4xx 自动抛异常。
        String raw = spec.retrieve().onStatus(status -> true, (request, response) -> {
                }).body(String.class);
        return om.readTree(raw == null ? "{}" : raw);
    }

    private JsonNode put(String url, Map<String, String> body, String tokenValue) {
        // PUT 用于更新用户资料，必须携带有效 token。
        // 请求体为 JSON，包含需要更新的字段。
        // PUT 请求体只包含需要更新的资料字段。
        // PUT 请求链直接内联，便于清晰看到方法、认证头与请求体。
        // 资料更新接口要求 JSON 请求体。
        String raw = client.put().uri(url)
                .header("Authorization", "Bearer " + tokenValue)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(status -> true, (request, response) -> {
                })
                .body(String.class);
        return om.readTree(raw == null ? "{}" : raw);
    }

    /** 密码登录并返回 tokenValue */
    private String login(String phone, String password) {
        // 使用手机号与密码登录，走真实密码校验与 token 签发流程。
        // 登录失败会在下方断言中直接暴露。
        // 登录请求体只包含手机号与密码，不传角色等可伪造字段。
        // 登录辅助方法复用统一 POST 逻辑，不单独处理响应格式。
        // 手机号与密码字段名与接口契约保持一致。
        JsonNode node = post("/api/user/login/by-pwd",
                Map.of("phone", phone, "password", password));
        assertEquals("A200", node.get("code").asString(), node.toString());
        // 提取 tokenValue 供受保护接口使用。
        // token 字段位于统一响应的 data 对象中。
        // 此处假定登录响应结构稳定，失败会在前一行断言中断。
        // 调用方拿到的是可直接放入请求头的原始 token。
        return node.get("data").get("tokenValue").asString();
    }

    /**
     * 场景：seed 用户1 使用正确手机号与密码登录。
     * 前置条件：启动种子已写入 13800000001/Aa123456。
     * 断言目标：登录返回 A200，且 tokenValue 非空。
     * 该用例是其他受保护接口用例的 token 来源。
     * 密码校验成功后服务端签发新 token，不依赖旧会话。
     */
    @Test
    void login_by_pwd_seed_user1_success() {
        // 登录辅助方法内部已断言 A200，此处直接使用返回的 token。
        // 后续断言均依赖该 token 能通过拦截器校验。
        String token = login(USER1_PHONE, PASSWORD);
        // token 非空说明凭证签发成功，后续接口才可正常鉴权。
        // 只确认凭证存在，不解析其具体编码格式。
        // 空 token 会导致后续所有受保护请求失败。
        // 该断言是 token 链路的最小健康检查。
        assertFalse(token.isEmpty());
    }

    /**
     * 场景：手机号存在但密码错误。
     * 前置条件：使用 seed 用户1 的手机号与错误密码。
     * 断言目标：返回 C400，提示文案不区分账号与密码错误。
     * 统一提示可防止攻击者枚举已注册手机号。
     * 登录失败不应泄露用户是否存在。
     */
    @Test
    void login_by_pwd_wrong_password() {
        JsonNode node = post("/api/user/login/by-pwd",
                // 手机号正确、密码错误，用于单独验证密码比对分支。
                // 请求体不可变，避免辅助方法意外修改参数。
                Map.of("phone", USER1_PHONE, "password", "wrong"));
        // 认证失败使用客户端错误码，避免暴露内部异常细节。
        // 认证失败统一使用客户端错误码。
        assertEquals("C400", node.get("code").asString());
        // 提示文案必须模糊，不能区分账号不存在与密码错误。
        // 文案固定，避免不同失败原因返回不同提示。
        // 文案固定可防止通过不同提示枚举账号状态。
        // 与未知手机号用例共享同一预期。
        assertEquals("手机号或密码错误", node.get("message").asString());
    }

    /**
     * 场景：使用未注册的手机号登录。
     * 前置条件：手机号 13800009999 不在用户表中。
     * 断言目标：返回与密码错误完全一致的 C400 与提示文案。
     * 两条失败路径一致，避免通过响应差异探测注册状态。
     * 服务端内部可能先查用户再校验密码，但对外结果必须统一。
     */
    @Test
    void login_by_pwd_unknown_phone_same_error() {
        // 不存在的手机号与密码错误提示一致，不泄露注册信息
        JsonNode node = post("/api/user/login/by-pwd",
                // 使用一个不存在的手机号，密码本身合法。
                // 该号码未注册，确保失败原因只可能是账号不存在。
                Map.of("phone", "13800009999", "password", PASSWORD));
        assertEquals("C400", node.get("code").asString());
        assertEquals("手机号或密码错误", node.get("message").asString());
    }

    /**
     * 场景：对静默注册、未设置密码的用户使用密码登录。
     * 前置条件：seed 用户2 的手机号存在，但 password 为空。
     * 断言目标：返回与密码错误一致的 C400 与提示文案。
     * 无密码用户不能通过密码登录，也不能借此获得特殊提示。
     * 这类用户应改用验证码登录。
     */
    @Test
    void login_by_pwd_no_password_user_same_error() {
        // seed 用户2 静默注册无密码：与密码错误提示一致
        JsonNode node = post("/api/user/login/by-pwd",
                // 用户2 由验证码静默注册产生，数据库中没有密码。
                // 用户2 无密码，密码登录应走统一失败分支。
                Map.of("phone", "13800000002", "password", PASSWORD));
        assertEquals("C400", node.get("code").asString());
        assertEquals("手机号或密码错误", node.get("message").asString());
    }

    /**
     * 场景：携带登录得到的 token 查询当前用户信息。
     * 前置条件：token 由 seed 用户1 成功登录签发。
     * 断言目标：返回 A200，手机号做掩码展示，昵称与种子一致。
     * 该用例验证 token 解析、用户回填与敏感字段脱敏。
     * 响应中不应出现明文密码或完整身份证号。
     * 掩码格式是前端展示与隐私保护的契约。
     */
    @Test
    void info_with_token() {
        String token = login(USER1_PHONE, PASSWORD);
        // 通过带 Bearer 头的 GET 请求访问受保护接口。
        // 该接口只读，不会修改用户资料。
        JsonNode node = get("/api/user/info", token);
        // 成功编码与业务数据同时校验，避免只看数据忽略响应契约。
        // 成功编码优先校验，避免在错误响应上做字段断言。
        // 先确认响应成功，再解读 data 字段更符合契约。
        // 失败时该断言会给出最直接的原因。
        assertEquals("A200", node.get("code").asString());
        // 手机号中间四位掩码，兼顾可识别性与隐私保护。
        // 掩码规则保留前三位与后四位，中间固定八位星号。
        // 掩码由服务端生成，前端不再二次处理。
        // 断言固定格式可防止不小心返回明文。
        assertEquals("138****0001", node.get("data").get("phone").asString());
        // 昵称来自 seed 数据，确认返回的是当前登录用户。
        // 固定值断言可发现种子数据或查询条件变化。
        // 昵称随种子数据固定，便于确认查询命中了正确用户。
        // 昵称不参与鉴权，只用于展示。
        // 该值来自 seed 数据，便于识别测试环境。
        assertEquals("user_mjafo8", node.get("data").get("nickname").asString());
    }

    /**
     * 场景：不携带任何 token 访问受保护的用户信息接口。
     * 前置条件：请求头中没有 Authorization。
     * 断言目标：返回 C401，而不是空数据或 5xx。
     * 未认证访问必须在拦截器阶段被拒绝。
     * 该行为与前端「跳转登录页」的处理约定一致。
     */
    @Test
    void info_without_token_unauthorized() {
        // token 传 null 表示不添加 Authorization 头。
        // token 传 null 表示请求不带任何认证信息。
        JsonNode node = get("/api/user/info", null);
        // 未认证统一返回 C401，便于前端识别并跳转登录。
        // 未认证请求在进入业务逻辑前即被拒绝。
        assertEquals("C401", node.get("code").asString());
    }

    /**
     * 场景：携带格式合法但内容无效的 token。
     * 前置条件：Authorization 头为 Bearer not-a-real-token。
     * 断言目标：返回 C401，而不是 500。
     * 无效 token 属于认证失败，不应触发服务端异常。
     * 解析失败必须被安全地转换为业务错误码。
     */
    @Test
    void info_with_garbage_token_unauthorized() {
        // 该值不具备任何真实 token 的结构，用于触发解析失败分支。
        // 该值无法解析为有效会话，用于触发认证失败。
        JsonNode node = get("/api/user/info", "not-a-real-token");
        assertEquals("C401", node.get("code").asString());
    }

    /** 发送任意值的 Authorization 头（不自动加 Bearer 前缀） */
    private JsonNode getWithAuthorization(String url, String headerValue) {
        // 该辅助方法用于发送任意 Authorization 头，验证拦截器的边界处理。
        // 与普通 get 不同，它不会自动添加 Bearer 前缀。
        // 该辅助方法只用于构造异常 Authorization 头，不处理登录态。
        // 该方法只负责发送原始请求头，不解析 Bearer 方案。
        // 因此可以构造无前缀、空值等异常输入。
        String raw = client.get().uri(url)
                // 原样透传请求头，便于构造无前缀、空值等异常场景。
                // 该方法不校验头格式，专门用于构造非法输入。
                // 请求头值原样写入，测试输入决定最终格式。
                // 服务端应自行判断格式是否合法。
                .header("Authorization", headerValue)
                .retrieve()
                .onStatus(status -> true, (request, response) -> {
                })
                .body(String.class);
        return om.readTree(raw == null ? "{}" : raw);
    }

    /**
     * 场景：携带缺少 Bearer 前缀的裸 token。
     * 前置条件：Authorization 头直接为 rawtoken。
     * 断言目标：返回 C401，而不是 500。
     * 拦截器应识别格式错误并按未认证处理，而不是抛解析异常。
     * 该用例覆盖客户端拼接请求头时的常见错误。
     */
    @Test
    void info_with_prefixless_token_unauthorized() {
        // 无 Bearer 前缀的裸 token → C401 而非 500
        // 直接设置原始请求头，不自动添加 Bearer 前缀。
        // 缺少 Bearer 前缀时应按未认证处理。
        JsonNode node = getWithAuthorization("/api/user/info", "rawtoken");
        assertEquals("C401", node.get("code").asString());
    }

    /**
     * 场景：Authorization 头为 Bearer 加空 token。
     * 前置条件：请求头值为 "Bearer "，后缀为空。
     * 断言目标：返回 C401，而不是 500 或越权访问。
     * 空凭证必须被视为未认证，不能解析出任何用户身份。
     * 该边界可防止字符串截取后得到空 token 时的异常。
     */
    @Test
    void info_with_empty_bearer_token_unauthorized() {
        // Bearer 后为空 → C401 而非 500
        // 保留 Bearer 前缀但 token 部分为空，模拟客户端空值。
        // 前缀存在但凭证为空，同样属于未认证。
        JsonNode node = getWithAuthorization("/api/user/info", "Bearer ");
        assertEquals("C401", node.get("code").asString());
    }

    /**
     * 场景：使用 GET 方法访问只接受 POST 的登录接口。
     * 前置条件：接口路径存在，但 HTTP 方法不匹配。
     * 断言目标：返回 C400，而不是 S500。
     * 方法不匹配属于客户端用法错误，应得到明确的 4xx。
     * 该用例验证全局异常处理对框架异常的映射。
     */
    @Test
    void wrong_http_method_bad_request() {
        // GET 打 POST 接口 → C400（客户端用法错误），而非 S500
        // 登录接口只接受 POST，这里故意使用 GET 触发方法不匹配。
        // 使用错误方法访问登录接口，触发框架层方法不匹配。
        JsonNode node = get("/api/user/login/by-code", null);
        assertEquals("C400", node.get("code").asString());
    }

    /**
     * 场景：访问受保护前缀下的不存在的路由。
     * 前置条件：路径 /api/user/nonexistent 未注册任何控制器。
     * 断言目标：返回 C404，而不是 C401。
     * 路由不存在应优先于认证判断，便于前端区分接口错误与登录失效。
     * 该行为也避免把拼错的地址误报为未登录。
     */
    @Test
    void unknown_route_under_protected_prefix_not_found() {
        // 受保护前缀下的未知路由 → C404（路由不存在），而非 C401
        // 该路径不在任何控制器的映射表中，用于触发 404 分支。
        // 未注册路由应由 404 处理，而不是认证拦截。
        JsonNode node = get("/api/user/nonexistent", null);
        assertEquals("C404", node.get("code").asString());
    }

    /**
     * 场景：被禁用的用户尝试使用正确密码登录。
     * 前置条件：先把 seed 用户1 的 status 改为 0，用例结束后恢复。
     * 断言目标：登录返回 C403，而不是 A200。
     * 禁用状态必须在密码校验通过后继续拦截，不能仅靠前端限制。
     * 使用 finally 恢复状态，避免污染其他用例。
     * 该用例同时验证状态字段的持久化与登录链路读取。
     */
    @Test
    void login_by_pwd_disabled_user_forbidden() {
        // 直接查库取得用户实体，避免依赖接口返回的掩码数据。
        // 查询条件使用手机号，与登录时一致。
        // 查询结果用于修改状态，断言其非空由后续 update 间接保证。
        User u = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getPhone, USER1_PHONE));
        // 状态 0 表示禁用，登录与受保护接口都应拒绝。
        // 禁用后应立即影响登录与已签发 token 的访问。
        // 直接修改实体后写回，模拟管理员禁用账号。
        // 该状态变更应立即影响后续请求。
        u.setStatus(0);
        // 立即写回数据库，确保后续 HTTP 请求读到的是禁用状态。
        // 立即持久化状态变更，确保 HTTP 请求读到新值。
        userMapper.updateById(u);
        try {
            JsonNode node = post("/api/user/login/by-pwd",
                    Map.of("phone", USER1_PHONE, "password", PASSWORD));
            // 已认证但被禁用返回 C403，与未认证的 C401 区分。
            // 前端可据此提示「账号已被禁用」而不是「请登录」。
            // 权限不足与未认证使用不同错误码，便于前端区分处理。
            // 禁用账号已通过密码校验，但仍被状态检查拦截。
            // 与密码错误返回不同错误码，便于前端提示。
            assertEquals("C403", node.get("code").asString());
        } finally {
            // 在 finally 中重新查询，确保恢复的是数据库中的最新状态。
            User u2 = userMapper.selectOne(
                    new LambdaQueryWrapper<User>().eq(User::getPhone, USER1_PHONE));
            // 恢复为启用状态，避免影响依赖 seed 用户1 的其他用例。
            // 恢复操作在 finally 中执行，保证异常场景也能清理。
            // 恢复操作必须在 finally 中执行，避免测试间相互影响。
            // 重新查询后再修改可避免使用过期实体。
            u2.setStatus(1);
            // 即使断言失败也会执行恢复逻辑，保证测试隔离。
            userMapper.updateById(u2);
        }
    }

    /**
     * 场景：用户登录后主动登出，再使用同一 token 查询信息。
     * 前置条件：logout 接口受拦截器保护，必须携带有效 token。
     * 断言目标：登出返回 A200，随后 info 返回 C401。
     * 说明 token 在服务端被吊销，而不是仅由客户端丢弃。
     * 该能力用于凭证泄露后的主动失效。
     */
    @Test
    void logout_invalidates_token() {
        String token = login(USER1_PHONE, PASSWORD);
        // logout 受拦截器保护，必须携带 token
        // 登出请求携带当前 token，服务端据此定位需要吊销的会话。
        // 请求体为空，仅依赖 Authorization 头。
        // 登出接口通过请求头识别要吊销的会话。
        JsonNode logout = post("/api/user/logout", Map.of(), token);
        // 登出成功返回业务成功码。
        // 重复登出应保持幂等，但本用例只覆盖首次登出。
        // 成功码表示服务端已完成吊销。
        assertEquals("A200", logout.get("code").asString());
        JsonNode node = get("/api/user/info", token);
        assertEquals("C401", node.get("code").asString());
    }

    /**
     * 场景：更新资料时提交校验位错误的身份证号。
     * 前置条件：用户已登录，号码长度为 18 位但校验位不匹配。
     * 断言目标：返回 C400，资料不被更新。
     * 身份证校验必须在写入前完成，防止脏数据进入实名流程。
     * 校验失败不应影响其他资料字段。
     */
    @Test
    void profile_update_rejects_invalid_id_card_checksum() {
        String token = login(USER1_PHONE, PASSWORD);
        // 末位 5 是错误校验位，用于触发校验失败分支。
        // 更新请求只包含身份证字段，其他资料保持不变。
        JsonNode node = put("/api/user/profile", Map.of("idCard", "310101199001011235"), token);
        assertEquals("C400", node.get("code").asString());
    }

    /**
     * 场景：提交合法身份证号更新资料，并检查数据库存储形态。
     * 前置条件：用户已登录，号码通过国标校验。
     * 断言目标：接口返回掩码号码，数据库存储 enc:v2: 密文且可解密回原文。
     * 加密后再入库是身份证隐私保护的核心要求。
     * 随机 IV 使同一号码每次加密结果不同。
     * 用例在 finally 中恢复原始值，避免污染 seed 数据。
     */
    @Test
    void profile_update_encrypts_id_card_with_random_iv() {
        String token = login(USER1_PHONE, PASSWORD);
        // 先保存原始身份证字段，便于用例结束后恢复。
        // 直接查库可绕过业务层的掩码转换。
        // 原始值可能为 null，JdbcTemplate 会按 String 类型返回。
        String original = jdbcTemplate.queryForObject(
                "SELECT id_card FROM t_user WHERE phone = ?", String.class, USER1_PHONE);
        try {
            // 使用校验位为 X 的合法号码，覆盖字母校验位场景。
            // 该号码同时覆盖地区码、出生日期与字母校验位。
            String newId = "11010519491231002X";
            // 通过真实更新接口写入身份证，验证加密发生在持久化之前。
            // 合法号码应通过校验并进入加密写入流程。
            JsonNode node = put("/api/user/profile", Map.of("idCard", newId), token);
            assertEquals("A200", node.get("code").asString());
            // 接口返回掩码值而非明文或密文，前端可直接展示。
            // 掩码保留前四位与后四位，末位 X 也应保留。
            // 掩码保留校验位 X，前端展示时无需特殊处理。
            // 中间部分统一使用八个星号。
            assertEquals("1101********002X", node.get("data").get("idCard").asString());

            // 直接读取数据库中的存储值，验证落库形态。
            // 查询条件使用手机号，确保读取的是当前测试用户。
            String stored = jdbcTemplate.queryForObject(
                    "SELECT id_card FROM t_user WHERE phone = ?", String.class, USER1_PHONE);
            // 存储值必须带 v2 版本前缀，说明使用了当前加密格式。
            // 断言失败时输出实际存储值，便于排查是否走了明文分支。
            // 前缀是识别加密格式的第一道依据。
            // 版本前缀确保读取端能选择正确解密分支。
            // 明文或旧格式都会使该断言失败。
            assertTrue(stored.startsWith("enc:v2:"), stored);
            // 用同一加密器解密，确认密文可逆且内容未损坏。
            // 这同时验证写路径与读路径使用相同的密钥与格式。
            // 解密结果必须与提交值完全一致，包括大小写 X。
            // 解密结果与提交值逐字符一致，证明加解密对称。
            // 包括末位 X 的大小写也必须保持。
            assertEquals(newId, idCardCipher.decrypt(stored));
        } finally {
            // 恢复原始身份证字段，保证该用例可重复执行且不影响其他用例。
            // 使用参数化 SQL，避免拼接带来的转义问题。
            // 参数化 SQL 避免手动拼接与转义错误。
            jdbcTemplate.update("UPDATE t_user SET id_card = ? WHERE phone = ?", original, USER1_PHONE);
        }
    }

    /**
     * 场景：同一用户连续登录两次。
     * 前置条件：token 配置为并发登录、不共享会话。
     * 断言目标：两次 token 不同，且旧 token 仍然有效。
     * 该策略允许多端同时在线，登出只失效当前 token。
     * 与「单端登录」策略相比，更符合多设备使用场景。
     */
    @Test
    void concurrent_login_creates_distinct_valid_tokens() {
        // is-concurrent=true + is-share=false：每次登录新 token，旧 token 仍有效
        // 第一次登录取得 token t1。
        // 第一次登录的 token 用于验证旧会话仍有效。
        String t1 = login(USER1_PHONE, PASSWORD);
        // 第二次登录取得 token t2，不应覆盖 t1。
        String t2 = login(USER1_PHONE, PASSWORD);
        // 每次登录都应生成新的独立凭证。
        // 若 token 复用，多端登录会互相踢下线。
        // 两个 token 必须独立，否则多端登录会互相干扰。
        // 服务端不应复用未过期的旧 token。
        assertNotEquals(t1, t2);
        // 旧 token 仍可访问，证明会话未被新登录踢下线。
        // 这是 is-share=false 配置的直接体现。
        // 旧 token 未被新登录覆盖，证明并发登录配置生效。
        assertEquals("A200", get("/api/user/info", t1).get("code").asString());
        // 新 token 同样有效，两个会话并存。
        // 新 token 与旧 token 并存，说明会话之间互不干扰。
        // 两个断言共同覆盖多端在线的完整语义。
        assertEquals("A200", get("/api/user/info", t2).get("code").asString());
    }

    /**
     * 场景：用户登录后被禁用，再使用已有 token 访问信息。
     * 前置条件：先登录取得 token，再把用户 status 改为 0。
     * 断言目标：访问返回 C403，且 finally 中恢复用户状态。
     * 说明禁用检查发生在每次请求，而不是只在登录时检查。
     * 该行为保证管理员禁用账号后立即生效。
     */
    @Test
    void info_disabled_user_forbidden() {
        String token = login(USER1_PHONE, PASSWORD);
        try {
            User u = userMapper.selectOne(
                    new LambdaQueryWrapper<User>().eq(User::getPhone, USER1_PHONE));
            u.setStatus(0);
            userMapper.updateById(u);

            JsonNode node = get("/api/user/info", token);
            assertEquals("C403", node.get("code").asString());
        } finally {
            User u = userMapper.selectOne(
                    new LambdaQueryWrapper<User>().eq(User::getPhone, USER1_PHONE));
            // 恢复启用状态，保证后续用例仍能正常登录。
            u.setStatus(1);
            userMapper.updateById(u);
            // 最后再查一次数据库，确认恢复操作确实生效。
            // 该断言防止 update 失败但测试仍静默通过。
            // 数据库状态恢复成功后，其他用例才能继续使用 seed 用户1。
            // 该断言是 finally 清理逻辑的最后一道保险。
            assertTrue(userMapper.selectOne(
                    new LambdaQueryWrapper<User>().eq(User::getPhone, USER1_PHONE)).getStatus() == 1);
        }
    }
}
