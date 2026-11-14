package com.cdwater.toolshare.shared.sequence;

import com.cdwater.toolshare.shared.web.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 编号生成服务单元测试。
 * <p>
 * 测试范围：工具编号与租借单号依赖 Redis 自增序列的生成规则，以及失败语义。
 * 依赖环境：Mockito 模拟的 StringRedisTemplate 与 JdbcTemplate，无真实 Redis。
 * 关键不变量：编号格式固定、序列只递增不跳号；Redis 异常时快速失败，绝不随机兜底。
 */
class SequenceServiceTest {

    /**
     * 辅助方法：为 mock 的 RedisTemplate 配置并返回 ValueOperations 桩对象。
     * 统一包装避免每个用例重复 mock opsForValue 的样板代码。
     */
    @SuppressWarnings("unchecked")
    private static ValueOperations<String, String> ops(StringRedisTemplate redis) {
        // ValueOperations 承载 Redis 的 GET/INCR 等字符串操作，
        // 单独 mock 出来才能精确控制 increment 的返回值与异常。
        // 返回类型固定为字符串，与 SequenceService 的取值签名一致。
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        // 让被测服务拿到同一个桩对象，避免每次调用返回新的 mock。
        when(redis.opsForValue()).thenReturn(ops);
        return ops;
    }

    /**
     * 辅助方法：用给定 Redis mock 构造序列服务，JdbcTemplate 用哑元 mock 顶替。
     * 本组用例只关心 Redis 序列路径，不触达数据库。
     */
    private static SequenceService service(StringRedisTemplate redis) {
        // 本组用例只走 Redis 序列路径，数据库依赖用哑元 mock 顶替，
        // 既避免真实连接，也确保测试不会意外访问持久层。
        // 构造顺序与生产 Bean 保持一致，避免测试与运行时装配差异。
        return new SequenceService(redis, mock(JdbcTemplate.class));
    }

    /**
     * 场景：Redis 自增返回 7，为分类 WX 生成工具编号。
     * 前置条件：redis 的 tool:seq:WX 键自增结果被 mock 为 7。
     * 断言目标：编号按 SQ-G-分类四位补零格式输出，即 SQ-G-WX-0007。
     */
    @Test
    void builds_tool_code_from_redis_sequence() {
        // 模拟 Redis 客户端；真实环境下自增由 INCR 命令原子完成。
        // 这里只验证服务如何消费自增结果。
        // 模拟 Redis 返回空结果的异常客户端。
        // 真实 INCR 不会返回 null，但防御性代码仍需处理。
        // 模拟一次 Redis 不可用的场景。
        // 目标是验证失败语义，而不是重试或降级。
        // 每个用例使用全新 mock，防止序列返回值在用例之间串扰。
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        // 指定分类 WX 的序列当前值为 7，下一步应生成 0007。
        // 键名格式 tool:seq:{分类} 与生产保持一致。
        when(ops(redis).increment("tool:seq:WX")).thenReturn(7L);

        // 编号格式为 SQ-G-分类-四位补零序号，便于人工识别与按前缀检索。
        // 断言完整字符串而非仅后缀，确保格式变更会被测试捕获。
        // 分类部分直接嵌入，无需额外的查表或缓存。
        assertEquals("SQ-G-WX-0007", service(redis).nextToolCode("WX"));
    }

    /**
     * 场景：Redis 自增调用抛出运行时异常。
     * 前置条件：opsForValue().increment 被 mock 为抛出 IllegalStateException。
     * 断言目标：抛出业务异常且提示重试，确保不会退化为随机或本地计数器造成重号。
     */
    @Test
    void redis_failure_fails_loudly_instead_of_random_fallback() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        // 任意序列键的自增都抛出运行时异常，模拟连接中断。
        when(ops(redis).increment(anyString())).thenThrow(new IllegalStateException("redis down"));

        // 服务必须把底层异常转换为业务异常，并提示调用方重试。
        // 绝不退化为随机数或本地计数器，否则会造成编号重复。
        // 断言异常类型，避免底层异常直接透传到接口层。
        // 失败路径必须快速失败，不做隐藏重试以保持接口响应时间可控。
        BizException error = assertThrows(BizException.class,
                () -> service(redis).nextToolCode("WX"));

        // 消息中包含「重试」是对调用方的可操作提示。
        // 同时把实际消息带入断言失败信息，便于排障。
        assertTrue(error.getMessage().contains("重试"), error.getMessage());
    }

    /**
     * 场景：Redis 自增返回 null（例如被误用为普通 set 的客户端）。
     * 前置条件：increment 的返回值被 mock 为 null。
     * 断言目标：租借单号生成同样抛出业务异常，空序列不得生成编号。
     */
    @Test
    void empty_increment_result_also_fails() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        // 空序列值意味着无法确定下一个编号。
        when(ops(redis).increment(anyString())).thenReturn(null);

        // 租借单号与工具编号共用同一套失败策略：
        // 空结果同样抛出业务异常，宁可失败也不生成不可信编号。
        // 这样可避免后续对账时出现无法解释的单号。
        // 空值不能被当作 0 继续生成，否则会与历史单号冲突。
        assertThrows(BizException.class, () -> service(redis).nextRentalOrderNo());
    }
}
