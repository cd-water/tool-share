package com.cdwater.toolshare.tool.util;

import com.cdwater.toolshare.common.exception.BizException;
import com.cdwater.toolshare.tool.mapper.ToolMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 工具编号生成器单元测试（Mock Redis 与 Mapper）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ToolCodeGeneratorTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ToolMapper toolMapper;

    private ToolCodeGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new ToolCodeGenerator(redisTemplate, toolMapper);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void next_usesCategoryCodeAndFourDigitSequence() {
        when(redisTemplate.hasKey("serial:tool:WX")).thenReturn(true);
        when(valueOperations.increment("serial:tool:WX")).thenReturn(5L);

        assertEquals("SQ-G-WX-0005", generator.next(1));
    }

    @Test
    void next_bootstrapsCounterFromDatabaseOnColdStart() {
        when(redisTemplate.hasKey("serial:tool:YY")).thenReturn(false);
        when(toolMapper.selectMaxSeqByCategory("YY")).thenReturn(7L);
        when(valueOperations.increment("serial:tool:YY")).thenReturn(8L);

        assertEquals("SQ-G-YY-0008", generator.next(2));
        verify(valueOperations).setIfAbsent("serial:tool:YY", "7");
    }

    @Test
    void next_doesNotQueryDatabaseWhenCounterExists() {
        when(redisTemplate.hasKey("serial:tool:QJ")).thenReturn(true);
        when(valueOperations.increment("serial:tool:QJ")).thenReturn(1L);

        assertEquals("SQ-G-QJ-0001", generator.next(3));
        verify(toolMapper, never()).selectMaxSeqByCategory(anyString());
    }

    @Test
    void next_rejectsUnknownCategory() {
        BizException ex = assertThrows(BizException.class, () -> generator.next(9));

        assertEquals("C400", ex.getCode());
        verifyNoInteractions(redisTemplate, toolMapper);
    }

    @Test
    void next_rejectsNullCategory() {
        BizException ex = assertThrows(BizException.class, () -> generator.next(null));

        assertEquals("C400", ex.getCode());
    }

    @Test
    void next_rejectsExhaustedSequence() {
        when(redisTemplate.hasKey("serial:tool:QT")).thenReturn(true);
        when(valueOperations.increment("serial:tool:QT")).thenReturn(10000L);

        BizException ex = assertThrows(BizException.class, () -> generator.next(4));

        assertEquals("C409", ex.getCode());
        assertTrue(ex.getMessage().contains("序号已用尽"));
    }

    @Test
    void next_failsWhenRedisIncrementReturnsNull() {
        when(redisTemplate.hasKey("serial:tool:QT")).thenReturn(true);
        when(valueOperations.increment("serial:tool:QT")).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> generator.next(4));

        assertEquals("S500", ex.getCode());
    }

    @Test
    void syncFromDatabase_raisesCounterWithLuaScript() {
        when(toolMapper.selectMaxSeqByCategory("WX")).thenReturn(42L);

        generator.syncFromDatabase(1);

        verify(redisTemplate).execute(any(RedisScript.class),
                eq(List.of("serial:tool:WX")), eq("42"));
    }
}
