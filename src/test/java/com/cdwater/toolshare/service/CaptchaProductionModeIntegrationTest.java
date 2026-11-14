// 验证码生产模式集成测试：验证关闭回显后接口不再泄露验证码。
// 生产模式由 toolshare.auth.expose-captcha=false 配置启用。
// 该开关关闭的是「把验证码返回给调用方」的调试能力，而非验证码本身。
// 因此服务端仍必须生成并保存验证码，否则真实用户将无法完成登录。
// 本用例同时覆盖发送接口响应、Redis 存储内容与登录链路三段行为。
// 依赖完整 Spring 上下文、随机端口与真实 Redis，避免替身掩盖存储分支。
// 手机号与其它验证码用例隔离，防止共享键空间造成偶发失败。
// 文件内所有注释仅用于说明测试意图，不改变任何断言与执行顺序。
package com.cdwater.toolshare.service;

// 集成测试基类负责数据库与容器生命周期，存储使用内存实现。
// 基类在测试类之间隔离数据，避免验证码用例受其它测试残留影响。
// 这样本用例只需关注验证码生产模式本身的行为。
import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
// JUnit 生命周期注解用于准备和清理验证码相关的 Redis 键。
// Spring 注解负责启动完整应用并注入真实的 Redis 客户端。
// 随机端口让 HTTP 客户端访问与生产一致的 Web 链路。
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
// 自动注入 Redis 模板，直接检查服务端是否保存了验证码。
// 通过真实 Redis 读取可以验证「不回显但仍落码」这一关键不变量。
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
// 使用真实 Redis 客户端清理验证码、尝试次数与限流键。
// 清理动作保证每次测试都从干净状态开始，避免限流窗口干扰断言。
// Redis 客户端同时用于读取服务端生成的 6 位验证码。
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// Map 用于构造 JSON 请求体，Set 用于接收限流键集合。
// 两者均为标准集合类型，不引入额外测试依赖。
import java.util.Map;
import java.util.Set;

// 断言发送响应成功、data 为空以及登录令牌非空。
// 三个断言分别对应脱敏、不泄露与登录可用三条不变量。
// 断言失败信息会带上具体响应，便于定位行为回归。
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证码生产模式集成测试。
 * <p>
 * 测试范围：关闭验证码回显开关后，发送验证码接口的响应与登录链路。
 * 依赖环境：随机端口完整应用、真实 Redis、内存对象存储；
 * 通过配置把 toolshare.auth.expose-captcha 覆盖为 false。
 * 关键不变量：接口仍返回成功但 data 为 null，验证码不得出现在响应中；
 * 服务端 Redis 中仍保存 6 位验证码；使用该服务端验证码仍可正常登录，
 * 保证生产模式不破坏真实用户流程。
 * 补充说明：本类只覆盖关闭回显的单一配置分支。
 * 发送接口仍走完整业务链路，包括验证码生成、Redis 写入与限流。
 * 因此即使响应被脱敏，服务端状态也必须与开发模式保持一致。
 * 登录使用 Redis 中真实保存的验证码，而不是任何回显值。
 * 这样可以证明关闭回显不会破坏真实用户的登录流程。
 * 测试结束不保留验证码键，避免后续用例复用同一手机号时互相影响。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "toolshare.auth.expose-captcha=false")
@Import(TestStorageConfig.class)
class CaptchaProductionModeIntegrationTest extends AbstractIntegrationTest {

    // 固定测试手机号，避免与用户认证、限流等其它验证码用例抢占键空间。
    // 手机号只用于构造 captcha:user:<phone> 等 Redis 键。
    // 由于每个用例前都会清理相关键，重复运行不会累积状态。
    /** 测试手机号，与其他验证码用例隔离。 */
    private static final String PHONE = "13900004444";

    // 随机端口由 Spring Boot 测试框架注入。
    // HTTP 客户端据此访问真实 Web 层，而不是直接调用服务方法。
    /** 随机端口，供测试 HTTP 客户端使用。 */
    @LocalServerPort
    int port;

    // 注入真实 Redis 客户端，用于准备与核对验证码存储状态。
    @Autowired
    // 该客户端不经过 mock，确保读取到的是应用实际写入的值。
    // 验证码键、尝试次数键与限流键都由本类在用例前主动清理。
    // 读取结果还会与 6 位数字格式做匹配，防止空值或占位值误判。
    /** 真实 Redis 客户端，用于清理验证码/限流键并读取服务端验证码。 */
    StringRedisTemplate redis;

    // RestClient 在每个用例前绑定随机端口。
    // 它负责发送验证码与验证码登录两类 HTTP 请求。
    // 统一使用 JSON 内容类型，与前端实际调用方式保持一致。
    RestClient client;
    final ObjectMapper om = new ObjectMapper();

    /**
     * 每个用例前创建 HTTP 客户端，并清理该手机号的验证码、尝试次数与限流键。
     * 防止上一次发送的验证码或限流窗口影响本次生产模式断言。
     * 清理范围包括验证码本体、错误尝试次数与所有限流键。
     * 限流键使用通配匹配，覆盖不同手机号与时间窗口产生的记录。
     * 若上一次运行残留限流状态，本次发送可能被拒绝，断言将失真。
     * 因此清理先于任何请求执行，保证用例可重复运行。
     */
    @BeforeEach
    void setUp() {
        // 绑定当前测试实例的随机端口，保证请求进入同一个 Spring 上下文。
        // 每个用例重新创建客户端，避免跨用例共享连接状态。
        // 客户端本身无状态，随后续请求逐条构造。
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        // 删除该手机号的验证码键，强制本次发送重新生成验证码。
        // 否则可能读到上一次运行留下的验证码，掩盖发送逻辑的问题。
        // 键名与生产代码中的 captcha:user:<phone> 约定保持一致。
        redis.delete("captcha:user:" + PHONE);
        // 清理错误尝试次数，避免触发验证码校验失败上限。
        // 尝试次数键在多次输错时累加，会直接影响登录断言。
        // 每个用例都从零开始，才能验证正常登录路径。
        redis.delete("captcha:attempt:user:" + PHONE);
        // 读取所有验证码限流键，准备在用例前统一清除。
        // 限流键按手机号与窗口维度生成，数量不固定。
        // 通配读取后一次性删除，避免逐键推断命名格式。
        Set<String> rateKeys = redis.keys("captcha:rl:*");
        // keys 在无匹配时可能返回空集合，需要判空后再删除。
        // 这一步保证即使 Redis 中没有限流记录也能安全执行。
        if (rateKeys != null && !rateKeys.isEmpty()) {
            // 批量删除限流键，清空发送频率窗口。
            // 清理完成后，本次发送请求不会因历史限流而被拒绝。
            redis.delete(rateKeys);
        }
    }

    /**
     * 场景：在生产模式（回显开关关闭）下发送验证码，再用服务端保存的验证码登录。
     * 前置条件：expose-captcha=false；Redis 中无残留验证码与限流记录。
     * 断言目标：发送接口返回 A200 但 data 为 null，响应中不得泄露验证码；
     * Redis 中仍存在 6 位验证码；使用该验证码登录成功并返回非空 token，
     * 证明关闭回显只影响接口展示，不影响真实登录链路。
     * 补充说明：发送与登录均通过真实 HTTP 接口完成。
     * 发送阶段验证响应脱敏，登录阶段验证验证码仍然有效。
     * 服务端验证码从 Redis 直接读取，不依赖任何接口回显。
     * 若登录成功，说明关闭回显只影响展示，不影响校验逻辑。
     * 令牌非空进一步证明登录链路返回了可用的会话凭证。
     * 用例不清理登录产生的会话，交由基类在测试结束后统一处理。
     */
    @Test
    void send_code_hides_code_in_production_mode() {
        // 调用发送验证码接口，模拟前端在生产模式下请求验证码。
        // 请求体只包含手机号，与真实前端参数一致。
        // 响应以原始字符串接收，便于先确认接口确实返回了 JSON。
        // 该请求仍会经过限流与验证码生成逻辑。
        String raw = client.post().uri("/api/user/send-code")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("phone", PHONE))
                .retrieve()
                .body(String.class);
        // 解析响应 JSON，后续按业务码与 data 字段断言。
        // 使用共享 ObjectMapper，保持与测试其它部分一致的解析方式。
        // 若接口返回空体，解析会失败并暴露契约变化。
        JsonNode node = om.readTree(raw);

        // 生产模式下发送接口仍返回成功业务码。
        // 关闭回显不应改变接口的可用性，只改变返回内容。
        // 若此处失败，说明配置覆盖或发送链路本身出了问题。
        assertEquals("A200", node.get("code").asString());
        // 关键断言：data 必须为 null，验证码不得出现在响应中。
        // 这是生产模式与开发回显模式的核心差异。
        // 任何非空 data 都可能把验证码泄露给任意调用方。
        assertTrue(node.get("data").isNull(), "关闭开关时 data 应为 null");

        // 从 Redis 读取服务端实际保存的验证码。
        // 回显关闭只影响响应，不影响验证码的生成与存储。
        // 该值是后续登录请求的唯一凭据来源。
        // 读取键与生产代码约定一致，避免误读其它数据。
        String stored = redis.opsForValue().get("captcha:user:" + PHONE);
        // 断言验证码存在且为 6 位数字。
        // 格式检查可以排除空值、占位值或异常对象写入。
        // 只有真实验证码才能走通下一步登录。
        assertTrue(stored != null && stored.matches("\\d{6}"), "验证码应仍写入服务端");

        // 使用服务端保存的验证码调用验证码登录接口。
        // 这模拟真实用户收到短信后输入验证码的流程。
        // 登录请求不依赖任何接口回显，证明生产模式闭环可用。
        // 请求同样经过验证码校验、会话创建等完整链路。
        String loginRaw = client.post().uri("/api/user/login/by-code")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("phone", PHONE, "code", stored))
                .retrieve()
                .body(String.class);
        // 解析登录响应，检查业务码与令牌字段。
        // 登录链路与发送链路分开断言，便于定位失败环节。
        JsonNode login = om.readTree(loginRaw);
        // 断言登录成功，说明服务端验证码被正确校验。
        // 若失败，可能是验证码被消费、键被清理或登录契约变化。
        assertEquals("A200", login.get("code").asString());
        // 断言返回的令牌非空，证明登录确实建立了会话。
        // 空令牌意味着登录接口虽返回成功但用户无法继续访问。
        // 该断言闭合「脱敏不影响真实登录」的完整证明链。
        assertFalse(login.get("data").get("tokenValue").asString().isEmpty());
    }
}
