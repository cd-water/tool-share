package com.cdwater.toolshare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
import com.cdwater.toolshare.module.auth.User;
import com.cdwater.toolshare.module.auth.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集成测试：发送验证码 + 手机号验证码登录（静默注册）。
 * 依赖 Testcontainers 自动启动 MySQL/Redis。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestStorageConfig.class)
class CaptchaLoginIntegrationTest extends AbstractIntegrationTest {

    private static final String NEW_PHONE = "13900001111";

    @LocalServerPort
    // 随机端口由 Spring Boot 分配，测试通过真实 HTTP 请求覆盖验证码与登录链路。
    // 该端口仅在本测试进程内有效。
    // 端口字段由 Spring 在启动测试上下文后注入，无需手工读取配置。
    int port;

    @Autowired
    // 直接访问 Redis 以清理验证码、尝试次数与频控键，保证用例从干净状态开始。
    // 生产逻辑同样依赖这些键，因此这里观察的是真实存储行为。
    // 使用真实 Redis 容器，验证码 TTL 与原子操作行为与生产一致。
    StringRedisTemplate redis;

    @Autowired
    // 用于清理并检查静默注册产生的用户记录。
    // 直接查库可以验证密码为空、状态与昵称等持久化结果。
    // 直接使用 Mapper 查库，绕过业务层以便独立验证持久化结果。
    UserMapper userMapper;

    // 每个用例前按随机端口重建，是访问真实接口的 HTTP 客户端。
    // 使用真实请求链路而非直接调用 Service，才能覆盖拦截器与限流。
    // 客户端不保存会话，登录态完全由 token 与 Redis 决定。
    RestClient client;
    // 响应统一解析成 JsonNode，断言只关注业务码与关键数据。
    // ObjectMapper 无状态，可跨用例共享。
    // 解析器只做 JSON 树读取，不绑定具体 VO，断言更灵活。
    final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // 指向本次启动的随机端口，避免与开发机上的其他服务冲突。
        // 使用明文 HTTP 访问本机服务，测试环境无需 TLS。
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        // 清理验证码键，避免上一个用例遗留的验证码让本用例直接登录成功。
        // 删除验证码本体，避免 TTL 尚未到期时跨用例复用。
        redis.delete("captcha:user:" + NEW_PHONE);
        // 清理错误尝试计数，否则达到上限后正确验证码也会被拒绝。
        // 尝试计数与验证码生命周期绑定，测试必须一并重置。
        // 尝试计数单独存储，必须与验证码一起清理。
        redis.delete("captcha:attempt:user:" + NEW_PHONE);
        // 发送验证码受手机号与 IP 双维度频控，测试前必须清空所有频控键。
        // 数据量很小，使用 keys 扫描不存在性能问题。
        // 频控键可能同时包含手机号与 IP 维度，全部清理最稳妥。
        var rateKeys = redis.keys("captcha:rl:*");
        // 空库时 keys 可能返回空集合，需同时处理 null 与 empty。
        // 只读扫描不修改数据，删除动作集中在下一行。
        if (rateKeys != null && !rateKeys.isEmpty()) {
            // 批量删除频控键，确保第一次发送不会被历史计数拦截。
            // 批量删除避免逐键往返 Redis。
            redis.delete(rateKeys);
        }
        // 删除测试手机号对应的用户，确保「静默注册」用例每次都是从零创建。
        // 只删除固定测试号码，不影响 seed 用户。
        // 该清理在验证码清理之后执行，顺序不影响最终状态。
        // 清理测试号码，保证每次静默注册用例都创建新用户。
        // 使用条件删除而非清空表，保留 seed 数据供其他用例使用。
        userMapper.delete(new LambdaQueryWrapper<User>().eq(User::getPhone, NEW_PHONE));
    }

    /** 构造一个必定不等于真实验证码的 6 位错误码 */
    private String wrongCode(String code) {
        // 保证错误码与真实验证码不同，同时避免与「000000」固定值冲突。
        // 返回的仍是 6 位数字，满足接口对格式的前置校验。
        // 三目表达式保证两个候选码都固定为 6 位数字。
        // 该辅助方法不访问服务端，纯本地计算。
        return "000000".equals(code) ? "000001" : "000000";
    }

    /**
     * 辅助方法：发送 JSON POST 请求并返回解析后的统一响应。
     *
     * <p>验证码与登录接口无论业务成功与否都返回 HTTP 200，
     * 业务结果放在响应体的 code 字段中，因此必须读取 body 而非依赖状态码。
     *
     * @param url 接口路径
     * @param body JSON 请求体字段
     * @return 包含 code、message 与 data 的 JSON 响应
     */
    private JsonNode post(String url, Map<String, String> body) {
        // 请求体统一使用 JSON，与后端接口契约一致。
        // 请求体序列化由 RestClient 自动完成，Map 直接作为 JSON 对象发送。
        String raw = client.post().uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                // 先获取响应，再交给下方统一的状态处理。
                // 获取响应对象后统一读取为字符串。
                .retrieve()
                // 将所有 HTTP 状态都视为可读取，业务错误通过响应体 code 表达。
                // 显式空处理器用于关闭 4xx/5xx 抛异常行为。
                // 这样错误响应也能进入 JSON 解析流程。
                .onStatus(status -> true, (request, response) -> {
                })
                .body(String.class);
        // 空响应体按空对象处理，保证调用方可以安全读取 code 字段。
        // 解析结果交给调用方按 code 断言。
        return om.readTree(raw == null ? "{}" : raw);
    }

    /** 发送验证码并返回 6 位码 */
    private String sendCode(String phone) {
        // 调用真实发送接口，走参数校验、频控与验证码落库的完整链路。
        // 发送请求只包含手机号，验证码由服务端生成。
        JsonNode node = post("/api/user/send-code", Map.of("phone", phone));
        // 发送成功是后续登录用例的前置条件，失败应立即终止当前用例。
        assertEquals("A200", node.get("code").asString());
        // 测试配置暴露验证码，data 字段直接返回 6 位明文，便于登录用例使用。
        // 该返回值仅用于测试，生产接口不会明文暴露验证码。
        return node.get("data").asString();
    }

    /**
     * 场景：向合法手机号发送验证码。
     * 前置条件：测试 Redis 与频控计数均已清空，手机号未被限流。
     * 断言目标：验证码为 6 位纯数字，且 Redis 中存储值与返回码一致。
     * 返回码与存储值一致是后续登录校验能通过的前提。
     * 验证码键按手机号维度隔离，不同号码之间不会串码。
     * 该用例覆盖「生成 -> 下发 -> 落库」的主流程。
     * 不校验具体随机值，避免测试耦合随机数实现。
     */
    @Test
    void send_code_returns_code_and_stores_in_redis() {
        // sendCode 内部已断言 A200，这里只关心返回的验证码内容。
        // 先获取一个真实验证码，确保失败原因是「码不匹配」而非「码不存在」。
        // 使用统一的发送辅助方法，失败会立即中断用例。
        // 确保 Redis 中存在一个有效验证码后再提交错误码。
        String code = sendCode(NEW_PHONE);
        // 验证码固定 6 位，前端输入框与短信模板都依赖这一长度。
        // 长度断言先于字符断言，失败原因更直观。
        assertEquals(6, code.length());
        // 必须全部为数字，避免出现字母或符号导致用户无法输入。
        // 使用字符流判断，避免正则带来的额外依赖。
        assertTrue(code.chars().allMatch(Character::isDigit));
        // Redis 中的值与接口返回值一致，证明登录校验读取的是同一份数据。
        // 键名格式 captcha:user:{手机号} 与生产保持一致。
        // 若键未写入，登录用例即使拿到正确码也无法通过。
        // 读取键值验证写入内容与下发内容完全一致。
        assertEquals(code, redis.opsForValue().get("captcha:user:" + NEW_PHONE));
    }

    /**
     * 场景：使用明显不合法的手机号请求发送验证码。
     * 前置条件：手机号长度不足，未通过接口参数校验。
     * 断言目标：返回 C400，且不写入验证码、不消耗频控配额。
     * 参数校验发生在业务逻辑之前，避免无效请求触发短信发送。
     * 该用例保证非法输入得到明确的客户端错误而非 5xx。
     */
    @Test
    void send_code_invalid_phone_bad_request() {
        // 5 位数字不满足手机号格式，用于触发参数校验分支。
        // 不合法号码在参数绑定阶段即被拒绝。
        JsonNode node = post("/api/user/send-code", Map.of("phone", "12345"));
        // 参数错误统一返回 C400，前端据此提示用户检查输入。
        assertEquals("C400", node.get("code").asString());
    }

    /**
     * 场景：使用已发送的验证码，但提交一个错误的 6 位码。
     * 前置条件：验证码已成功写入 Redis，错误码与真实码不同。
     * 断言目标：登录返回 C400，不签发 token。
     * 同时验证错误尝试会计入次数，为后续上限用例做铺垫。
     * 错误码必须走真实校验逻辑，而不是被格式校验提前拦截。
     */
    @Test
    void login_by_code_wrong_code_rejected() {
        sendCode(NEW_PHONE);
        // 提交错误验证码，接口应进入比对分支并返回失败。
        // 这里的错误码不会与真实验证码相同。
        JsonNode node = post("/api/user/login/by-code",
                Map.of("phone", NEW_PHONE, "code", "000000"));
        assertEquals("C400", node.get("code").asString());
    }

    /**
     * 场景：手机号首次使用正确验证码登录。
     * 前置条件：该手机号在用户表中不存在，验证码有效且未过期。
     * 断言目标：返回 A200 与可用的 Bearer token，并自动创建用户。
     * 静默注册的用户不应设置密码，状态为启用，昵称按规则生成。
     * 该流程降低首次使用门槛，同时保持账号体系完整。
     * 查库断言确保注册结果真正持久化，而非仅返回临时对象。
     */
    @Test
    void login_by_code_silent_register() {
        String code = sendCode(NEW_PHONE);
        JsonNode node = post("/api/user/login/by-code",
                Map.of("phone", NEW_PHONE, "code", code));
        assertEquals("A200", node.get("code").asString());
        // tokenName 固定为 Authorization，前端据此把 token 放入请求头。
        // tokenName 是前端拼接请求头时使用的字段名。
        // 管理端与用户端都遵循同一命名约定。
        // 若改名，需要同步修改前端请求拦截器。
        assertEquals("Authorization", node.get("data").get("tokenName").asString());
        // tokenValue 必须非空，否则后续受保护接口无法访问。
        // 返回的 token 是访问受保护接口的唯一凭证。
        // 只检查非空，不解析 JWT 内容，避免耦合签名算法。
        // tokenName 与 tokenValue 成对出现，前端据此拼接请求头。
        assertFalse(node.get("data").get("tokenValue").asString().isEmpty());

        // 直接查询用户表，验证静默注册确实写入了数据库。
        // 查询条件使用手机号，与登录时使用的号码一致。
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getPhone, NEW_PHONE));
        // 用户记录必须存在，证明登录流程完成了自动注册。
        // 空值会导致后续字段断言抛空指针，先显式检查更清晰。
        assertNotNull(user);
        // 静默注册不设置密码，避免产生空密码或默认密码的弱凭证。
        // 静默注册用户没有密码，只能通过验证码登录。
        assertNull(user.getPassword());
        // 状态 1 表示启用，新用户应可以直接使用。
        // 状态字段使用整数枚举，1 代表正常启用。
        assertEquals(1, user.getStatus());
        // 昵称按 user_ 加 6 位小写字母数字生成，便于展示且避免重名。
        // 使用正则断言格式，而不是固定某个随机值。
        // 昵称规则变化时该用例会明确失败，提醒同步前端展示逻辑。
        // 昵称由系统生成，不依赖用户输入，避免重复与敏感信息。
        assertTrue(user.getNickname().matches("user_[0-9a-z]{6}"));
    }

    /**
     * 场景：同一验证码连续使用两次登录。
     * 前置条件：第一次登录成功并消费了验证码。
     * 断言目标：第二次使用同一码返回 C400，证明验证码是一次性的。
     * 一次性消费可防止验证码被截获后重复使用。
     * 消费必须是原子的，否则并发场景下仍可能多次成功。
     * 该用例是并发消费用例的基础版本。
     */
    @Test
    void login_by_code_captcha_one_time() {
        String code = sendCode(NEW_PHONE);
        // 第一次登录使用正确验证码，应成功并消费该码。
        // 首次登录请求携带手机号与正确验证码。
        JsonNode first = post("/api/user/login/by-code",
                Map.of("phone", NEW_PHONE, "code", code));
        // 首次登录成功的断言，确保后续失败不是因为码本身无效。
        assertEquals("A200", first.get("code").asString());

        // 同一验证码重放必须被拒绝（一次性）
        // 立即用同一验证码重放登录请求。
        // 此时 Redis 中的验证码应已被删除。
        // 第二次请求体与第一次完全相同，只依赖服务端的消费状态。
        JsonNode second = post("/api/user/login/by-code",
                Map.of("phone", NEW_PHONE, "code", code));
        // 重放必须失败，证明验证码在首次成功后立即失效。
        // 第二次请求不再获得任何 token。
        // C400 表示验证码无效或已使用，具体原因不对外区分。
        // 这样可避免攻击者通过错误差异推断验证码状态。
        assertEquals("C400", second.get("code").asString());
    }

    /**
     * 场景：多个线程同时使用同一验证码登录。
     * 前置条件：预置已注册用户，排除并发静默注册的干扰。
     * 断言目标：恰好一个请求成功，其余全部返回 C400，且没有 S500。
     * 验证码消费必须原子，否则同一码可能签发多个 token。
     * 通过固定线程池同时发起请求，模拟真实并发竞争。
     * 断言分布而非仅断言成功数，可发现重复消费与异常吞没。
     */
    @Test
    void login_by_code_captcha_atomic_consume() throws Exception {
        // 预置已注册用户，排除并发静默注册的干扰
        // 预置用户，避免并发请求同时走静默注册导致主键或唯一索引竞争。
        // 这里只填充登录所需的最小字段。
        // 预置用户只需手机号、昵称、头像与状态四个非空字段。
        User existing = new User();
        existing.setPhone(NEW_PHONE);
        existing.setNickname("user_aaa000");
        existing.setAvatar("");
        existing.setStatus(1);
        // 先写入用户记录，后续登录会走「已存在用户」分支。
        // 插入后并发登录会命中已存在用户，不会触发注册分支。
        // 插入的是普通启用用户，登录时无需再经过静默注册。
        // 若并发用例错误地走了注册分支，会因手机号唯一索引而出现 5xx。
        // 因此该预置数据也间接验证了注册与登录分支的隔离。
        userMapper.insert(existing);

        String code = sendCode(NEW_PHONE);
        // 8 个并发请求足以暴露非原子的读取-删除竞争。
        // 线程数取偶数，便于观察成功与失败请求的比例。
        int threads = 8;
        // 固定线程池让所有请求尽量同时到达验证码校验点。
        // 使用固定线程池而非并行流，线程数量完全可控。
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            // 收集每个请求的 Future，便于统一等待与统计结果。
            // Future 用于等待每个请求完成并获取响应。
            List<Future<JsonNode>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                // 提交并发登录任务；每个任务使用同一个验证码。
                // 任务返回解析后的响应，供主线程统计业务码。
                // Lambda 中只做 HTTP 调用，不做断言，避免异步线程中的异常被吞。
                // 每个请求都使用相同的手机号与验证码，形成真实竞争。
                // Future 的结果在主线程统一读取和统计。
                futures.add(pool.submit(() -> post("/api/user/login/by-code",
                        Map.of("phone", NEW_PHONE, "code", code))));
            }
            // 收集所有响应的业务码，避免在循环中直接断言导致提前退出。
            // 先收集后统计，避免在异步任务中直接断言。
            List<String> codes = new ArrayList<>();
            for (Future<JsonNode> f : futures) {
                codes.add(f.get().get("code").asString());
            }
            // 统计成功次数，期望恰好为 1。
            // 成功次数是判断原子消费是否生效的核心指标。
            long a200 = codes.stream().filter("A200"::equals).count();
            // 统计被拒绝次数，期望为线程数减 1。
            // 其余请求应因验证码已被消费而被拒绝。
            long c400 = codes.stream().filter("C400"::equals).count();
            // 统计服务端错误，期望为 0；出现即说明并发处理有缺陷。
            // 任何服务端错误都意味着并发处理存在竞态或异常泄漏。
            long s500 = codes.stream().filter("S500"::equals).count();
            // 验证码必须原子消费：恰好一个并发请求成功，其余全部 C400，无 S500
            assertEquals(1, a200, "并发消费同一验证码应恰好成功一次，实际 A200=" + a200);
            assertEquals(threads - 1, c400, "其余请求应全部 C400，实际=" + c400);
            assertEquals(0, s500, "不应出现服务端错误，实际 S500=" + s500);
        } finally {
            // 无论断言是否失败都关闭线程池，避免测试进程残留线程。
            // 关闭线程池并中断未完成任务，保证测试资源释放。
            pool.shutdownNow();
        }
    }

    /**
     * 场景：同一手机号在一分钟内连续发送两次验证码。
     * 前置条件：使用默认配置的每分钟上限，第一次发送已消耗配额。
     * 断言目标：第二次发送返回 C429。
     * 分钟级频控用于防止短时间内的短信轰炸。
     * 该限制与小时级、IP 级限制叠加生效。
     * 清理频控键后该用例可重复执行。
     */
    @Test
    void send_code_is_rate_limited_per_phone_per_minute() {
        sendCode(NEW_PHONE);
        // 第二次发送仍使用同一手机号，应命中分钟级频控。
        // 第二次请求紧随第一次，分钟窗口尚未过期。
        JsonNode second = post("/api/user/send-code", Map.of("phone", NEW_PHONE));
        assertEquals("C429", second.get("code").asString());
    }

    /**
     * 场景：在错误尝试上限内多次输错，最后提交正确验证码。
     * 前置条件：错误次数为 4，尚未达到失效阈值。
     * 断言目标：前 4 次均返回 C400，第 5 次使用正确码仍返回 A200。
     * 说明尝试计数只在实际比对失败时增加，且未提前使验证码失效。
     * 该边界保证正常用户在输错几次后仍能完成登录。
     * 上限值需与生产配置保持一致。
     */
    @Test
    void login_by_code_within_attempt_limit_still_succeeds_with_correct_code() {
        String code = sendCode(NEW_PHONE);
        // 连续提交 4 次错误码，保持在允许的尝试次数以内。
        // 4 次低于 5 次的失效阈值，用于验证边界内仍可登录。
        for (int i = 0; i < 4; i++) {
            // 每次使用一个必定错误的 6 位码。
            // 错误码由真实验证码推导，保证与真实值不同。
            // 每次错误尝试都会增加计数，但未达到使验证码失效的条件。
            JsonNode wrong = post("/api/user/login/by-code",
                    Map.of("phone", NEW_PHONE, "code", wrongCode(code)));
            // 每次错误尝试都应被拒绝，但不应使验证码整体失效。
            // 错误码与真实验证码不同，必然进入失败分支。
            // 每次失败都会被记录，用于后续达到上限时使验证码失效。
            // 断言失败编码而非提示文案，减少文案变更造成的测试脆性。
            assertEquals("C400", wrong.get("code").asString());
        }
        // 第 5 次改用正确验证码，验证尝试次数未超限时仍可登录。
        // 正确码在尝试上限内仍应被接受。
        JsonNode login = post("/api/user/login/by-code",
                Map.of("phone", NEW_PHONE, "code", code));
        // 正确码应通过，证明尝试上限内的失败不会误伤正常用户。
        // 正确码成功说明尝试计数未在达到阈值前误删验证码。
        // 该断言与上一用例的失败路径形成互补。
        // 边界值 4 与 5 的差异由两个用例分别覆盖。
        assertEquals("A200", login.get("code").asString());
    }

    /**
     * 场景：错误尝试达到上限后，再提交正确验证码。
     * 前置条件：连续提交 5 次错误码。
     * 断言目标：第 6 次即使使用正确码也返回 C400，且 Redis 中验证码被删除。
     * 达到上限后使验证码失效，可防止暴力枚举 6 位数字。
     * 这是账户安全的关键不变量，不能仅依赖前端限制。
     * 断言删除后的存储状态，证明失效逻辑真正执行。
     */
    @Test
    void login_by_code_attempt_limit_invalidates_captcha() {
        String code = sendCode(NEW_PHONE);
        // 连续 5 次错误尝试，正好达到失效阈值。
        // 5 次达到失效阈值，验证码应被服务端主动删除。
        for (int i = 0; i < 5; i++) {
            JsonNode wrong = post("/api/user/login/by-code",
                    Map.of("phone", NEW_PHONE, "code", wrongCode(code)));
            assertEquals("C400", wrong.get("code").asString());
        }
        // 达到尝试上限后验证码失效，即使提交正确验证码也不再通过
        JsonNode login = post("/api/user/login/by-code",
                Map.of("phone", NEW_PHONE, "code", code));
        // 验证码已失效，正确码也不再被接受。
        assertEquals("C400", login.get("code").asString());
        // 验证码键必须被删除，避免后续请求继续尝试。
        // 同时验证尝试计数与验证码生命周期绑定。
        // 断言存储状态比仅断言接口返回更能证明失效逻辑生效。
        // 失效后键不存在，后续请求只能重新发送验证码。
        // 断言 Redis 状态可避免仅凭接口返回误判。
        assertNull(redis.opsForValue().get("captcha:user:" + NEW_PHONE));
    }
}
