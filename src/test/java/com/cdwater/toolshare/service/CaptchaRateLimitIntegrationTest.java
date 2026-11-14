package com.cdwater.toolshare.service;

import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
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

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证码频控：手机号每小时上限与 IP 每小时上限。
 * 放宽每分钟上限，便于在单测中验证每小时维度。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "toolshare.auth.expose-captcha=true",
        "toolshare.auth.rate-limit.phone-per-minute=100",
        "toolshare.auth.rate-limit.phone-per-hour=5",
        "toolshare.auth.rate-limit.ip-per-hour=10"
})
@Import(TestStorageConfig.class)
class CaptchaRateLimitIntegrationTest extends AbstractIntegrationTest {

    @LocalServerPort
    // 随机端口由 Spring Boot 在启动时分配，避免与开发机上的服务冲突。
    // 测试通过该端口拼出本地请求地址。
    // 端口只在测试进程内有效，不会写死到配置文件。
    int port;

    @Autowired
    // 直接操作 Redis 以便在用例之间清理频控键，保证每个用例从零计数开始。
    // 生产代码同样通过这些键做计数，因此这里观察的是真实存储状态。
    // 使用真实 Redis 容器（Testcontainers），频控行为与生产一致。
    StringRedisTemplate redis;

    // RestClient 在每个用例前按随机端口重建，是访问真实 HTTP 接口的客户端。
    // 使用真实请求链路而非直接调用 Service，才能覆盖拦截器与频控切面。
    // 每个用例独立重建客户端，避免连接状态泄漏。
    RestClient client;
    // 统一把响应体解析成 JsonNode，断言时只关心业务 code 字段。
    // ObjectMapper 无状态，可在用例之间共享。
    // 只解析响应 JSON，不依赖 Spring 的全局 ObjectMapper 配置。
    final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // 每个用例前重建客户端，确保 baseUrl 指向本次启动的随机端口。
        // 不设置默认超时，由测试框架的整体超时兜底。
        // 请求直接打到本机随机端口，绕过任何外部网关。
        // 不配置全局拦截器，避免给请求额外附加认证信息。
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        // 频控键按手机号与 IP 两个维度写入 Redis，前缀统一为 captcha:rl:。
        // 测试开始前必须清空，否则上一个用例的计数会污染本用例。
        // 使用 keys 扫描所有频控键；测试数据量很小，不存在性能顾虑。
        // 只清理频控键，不影响验证码本身等其他 Redis 数据。
        Set<String> rateKeys = redis.keys("captcha:rl:*");
        // keys 在空库时可能返回空集合，需同时处理 null 与 empty。
        // 删除前先判空可避免向 Redis 发送无意义的 DEL。
        if (rateKeys != null && !rateKeys.isEmpty()) {
            // 批量删除所有频控键，使每个用例都从「零次发送」开始。
            // 清理动作在发送任何请求之前完成。
            redis.delete(rateKeys);
        }
    }

    /**
     * 辅助方法：向指定手机号发送验证码，并返回解析后的统一响应。
     *
     * <p>发送接口无论成功、频控还是参数错误都返回 HTTP 200，
     * 业务结果放在响应体的 code 字段中，因此需要读取 body 而非依赖状态码。
     *
     * @param phone 接收验证码的手机号
     * @return 包含 code、message 与 data 的 JSON 响应
     */
    private JsonNode send(String phone) {
        // 直接请求真实发送验证码接口，走完整的参数校验与频控链路。
        // JSON 请求体只包含手机号，其他字段由服务端补齐。
        // 请求体通过 Map 序列化为 JSON，手机号字段名为 phone。
        // 使用 POST 与 JSON 内容类型，与服务端接口定义一致。
        String raw = client.post().uri("/api/user/send-code")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("phone", phone))
                // retrieve 后仍统一读取字符串，避免非 2xx 响应直接抛异常。
                // 先拿到响应再统一解析，错误分支由业务码表达。
                .retrieve()
                // 将所有 HTTP 状态都视为可读取，业务错误由响应体 code 表达。
                // 空处理器表示不因状态码抛出 RestClientException。
                // 显式覆盖默认的 4xx/5xx 异常策略。
                .onStatus(status -> true, (request, response) -> {
                })
                .body(String.class);
        // 空响应体按空对象处理，保证调用方可以安全读取 code 字段。
        // 正常路径下服务端总会返回 JSON。
        // 解析失败会抛出受检异常，测试会直接失败并暴露响应格式问题。
        // 返回值由调用方按业务码断言，辅助方法本身不做任何断言。
        return om.readTree(raw == null ? "{}" : raw);
    }

    /**
     * 场景：同一手机号在一小时内连续发送验证码，超过配置的每小时上限。
     *
     * <p>前置条件：测试属性把 phone-per-hour 设为 5，并把每分钟上限放宽到 100，
     * 从而排除分钟级频控的干扰，单独验证小时维度。
     *
     * <p>断言目标：前 5 次发送全部放行并返回 A200；第 6 次返回 C429。
     * 该规则防止攻击者对同一号码反复发送短信造成骚扰与费用损失。
     * 计数键按手机号维度隔离，不同号码之间互不影响。
     * 频控窗口由 Redis 键的过期时间维护，无需额外的定时清理任务。
     */
    @Test
    void phone_is_limited_to_five_per_hour() {
        // 使用独立手机号，避免与其它用例共享频控计数。
        // 固定号码让频控键在用例内保持稳定，便于理解计数来源。
        String phone = "13900005555";
        // 连续发送 5 次，正好用满小时配额。
        // 循环变量同时用于生成「第 n 次」的失败提示。
        // 每次请求都应真实落库计数，而不是本地自增。
        // 循环次数与配置的小时上限严格一致，边界清晰。
        for (int i = 0; i < 5; i++) {
            // 配额内的请求必须放行，否则正常用户会被误伤。
            assertEquals("A200", send(phone).get("code").asString(), "第 " + (i + 1) + " 次应放行");
        }
        // 第 6 次触发小时上限，返回 429 对应的业务码。
        // 断言业务码而非 HTTP 状态，与项目统一响应契约一致。
        // 限流后仍不应抛出服务端错误，因此这里期望的明确是 C429。
        // 该结果同时证明前 5 次计数被正确累加。
        assertEquals("C429", send(phone).get("code").asString());
    }

    /**
     * 场景：同一来源 IP 在一小时内为不同手机号发送验证码。
     *
     * <p>前置条件：测试属性把 ip-per-hour 设为 10，手机号小时上限设为 5。
     * 每个手机号只发送一次，因此不会触发手机号维度限制。
     *
     * <p>断言目标：前 10 次（10 个不同号码）放行，第 11 次返回 C429。
     * IP 维度用于防止攻击者轮换手机号绕过单号码限制。
     * 本地测试中所有请求共享 127.0.0.1，正好模拟同一来源的批量请求。
     * 该限制与手机号限制取交集，任一维度耗尽都会拒绝发送。
     */
    @Test
    void ip_is_limited_to_ten_per_hour() {
        // 生成 10 个不同手机号，确保命中的是 IP 维度而非号码维度。
        // 每个号码只出现一次，手机号计数始终为 1。
        // 若实现错误地只按号码计数，本用例会失败。
        for (int i = 0; i < 10; i++) {
            // IP 配额内的 10 次请求均应放行。
            // 号码通过固定前缀加序号拼接，避免与 seed 用户冲突。
            assertEquals("A200", send("1390000" + (6000 + i)).get("code").asString(),
                    "第 " + (i + 1) + " 次应放行");
        }
        // 第 11 个号码仍来自同一 IP，应被 IP 小时上限拦截。
        // 这里使用全新的手机号，排除号码维度耗尽的可能性。
        // 返回 C429 证明限流判定发生在短信发送之前，不会产生额外短信费用。
        // 断言结果与手机号限制用例保持一致，便于前端统一处理。
        assertEquals("C429", send("13900006666").get("code").asString());
    }
}
