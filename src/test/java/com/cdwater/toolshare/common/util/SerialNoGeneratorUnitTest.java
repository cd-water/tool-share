package com.cdwater.toolshare.common.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单号生成器单元测试（Mock Redis，不依赖中间件）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SerialNoGeneratorUnitTest {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private SerialNoGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new SerialNoGenerator(redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void next_formatsPrefixDateAndPaddedSequence() {
        when(valueOperations.increment("serial:SQ-ZY-" + today())).thenReturn(1L);

        assertEquals("SQ-ZY-" + today() + "-0001", generator.next("SQ-ZY-"));
    }

    @Test
    void next_padsSequenceToFourDigits() {
        when(valueOperations.increment("serial:SQ-BX-" + today())).thenReturn(123L);

        assertEquals("SQ-BX-" + today() + "-0123", generator.next("SQ-BX-"));
    }

    @Test
    void next_setsTtlOnlyOnFirstIncrementOfTheDay() {
        when(valueOperations.increment("serial:SQ-ZY-" + today())).thenReturn(1L, 2L);

        generator.next("SQ-ZY-");
        generator.next("SQ-ZY-");

        verify(redisTemplate).expire("serial:SQ-ZY-" + today(), Duration.ofDays(2));
    }

    @Test
    void next_doesNotRefreshTtlForSubsequentIncrements() {
        when(valueOperations.increment("serial:SQ-ZY-" + today())).thenReturn(2L);

        generator.next("SQ-ZY-");

        verify(redisTemplate, never()).expire("serial:SQ-ZY-" + today(), Duration.ofDays(2));
    }

    private static String today() {
        return LocalDate.now().format(DATE);
    }
}
