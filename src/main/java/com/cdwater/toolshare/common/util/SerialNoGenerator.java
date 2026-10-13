package com.cdwater.toolshare.common.util;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 业务单号生成器：前缀 + yyyyMMdd + 4 位日内序号（如 SQ-ZY-20261017-0001）
 * 序号用 Redis INCR，并发安全；ponytail: INCR 后补 TTL 存在极小概率留下永不过期的键，介意可改 Lua
 */
@Component
@RequiredArgsConstructor
public class SerialNoGenerator {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StringRedisTemplate redisTemplate;

    /**
     * @param prefix 单号前缀，含分隔符，如 "SQ-ZY-"、"SQ-BX-"
     */
    public String next(String prefix) {
        String date = LocalDate.now().format(DATE);
        String key = "serial:" + prefix + date;
        Long seq = redisTemplate.opsForValue().increment(key);
        if (seq != null && seq == 1L) {
            redisTemplate.expire(key, Duration.ofDays(2));
        }
        return prefix + date + "%04d".formatted(seq);
    }
}
