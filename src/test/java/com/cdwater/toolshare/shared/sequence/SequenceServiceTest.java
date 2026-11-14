package com.cdwater.toolshare.shared.sequence;

import com.cdwater.toolshare.module.damage.DamageReportMapper;
import com.cdwater.toolshare.module.rental.RentalMapper;
import com.cdwater.toolshare.module.tool.ToolMapper;
import com.cdwater.toolshare.shared.web.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SequenceServiceTest {

    @SuppressWarnings("unchecked")
    private static ValueOperations<String, String> ops(StringRedisTemplate redis) {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        return ops;
    }

    private static SequenceService service(StringRedisTemplate redis) {
        return new SequenceService(redis, mock(ToolMapper.class),
                mock(RentalMapper.class), mock(DamageReportMapper.class));
    }

    @Test
    void builds_tool_code_from_redis_sequence() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(ops(redis).increment("tool:seq:WX")).thenReturn(7L);

        assertEquals("SQ-G-WX-0007", service(redis).nextToolCode("WX"));
    }

    @Test
    void redis_failure_fails_loudly_instead_of_random_fallback() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(ops(redis).increment(anyString())).thenThrow(new IllegalStateException("redis down"));

        BizException error = assertThrows(BizException.class,
                () -> service(redis).nextToolCode("WX"));

        assertTrue(error.getMessage().contains("重试"), error.getMessage());
    }

    @Test
    void empty_increment_result_also_fails() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(ops(redis).increment(anyString())).thenReturn(null);

        assertThrows(BizException.class, () -> service(redis).nextRentalOrderNo());
    }
}
