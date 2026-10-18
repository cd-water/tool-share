package com.cdwater.toolshare.tool.util;

import com.cdwater.toolshare.common.enums.ResultCode;
import com.cdwater.toolshare.common.exception.BizException;
import com.cdwater.toolshare.tool.mapper.ToolMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 工具编号生成器：SQ-G-{WX|YY|QJ|QT}-YYYY（4 位类别内序号，无日期）。
 * 序号按类别用 Redis INCR 递增，不设过期；计数器丢失时以库中该类别最大序号回填。
 */
@Component
@RequiredArgsConstructor
public class ToolCodeGenerator {

    private static final Map<Integer, String> CATEGORY_CODES = Map.of(
            1, "WX",
            2, "YY",
            3, "QJ",
            4, "QT"
    );

    private static final String KEY_PREFIX = "serial:tool:";

    private static final int MAX_SEQ = 9999;

    /** 仅当库中最大序号大于 Redis 计数器时抬升计数器，避免并发下把新值改小 */
    private static final DefaultRedisScript<Long> RAISE_COUNTER_SCRIPT = new DefaultRedisScript<>("""
            local current = tonumber(redis.call('GET', KEYS[1]) or '0')
            local dbMax = tonumber(ARGV[1])
            if current < dbMax then
                redis.call('SET', KEYS[1], dbMax)
                return dbMax
            end
            return current
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ToolMapper toolMapper;

    /**
     * 生成下一个工具编号
     *
     * @param category 工具类别：1维修 2园艺 3清洁 4其他
     */
    public String next(Integer category) {
        String categoryCode = categoryCode(category);
        String key = KEY_PREFIX + categoryCode;

        // 冷启动或 Redis 丢失 key 时，从库中该类别最大序号起步，避免与已有编号冲突
        if (Boolean.FALSE.equals(redisTemplate.hasKey(key))) {
            long dbMax = toolMapper.selectMaxSeqByCategory(categoryCode);
            redisTemplate.opsForValue().setIfAbsent(key, Long.toString(dbMax));
        }

        Long seq = redisTemplate.opsForValue().increment(key);
        if (seq == null) {
            throw new BizException(ResultCode.INTERNAL_SERVER_ERROR.getCode(), "工具编号生成失败");
        }
        if (seq > MAX_SEQ) {
            throw new BizException(ResultCode.CONFLICT.getCode(), "该类别工具编号序号已用尽");
        }
        return "SQ-G-%s-%04d".formatted(categoryCode, seq);
    }

    /**
     * 插入撞唯一索引后，按库中最大序号抬升 Redis 计数器，供上层重试
     */
    public void syncFromDatabase(Integer category) {
        String categoryCode = categoryCode(category);
        long dbMax = toolMapper.selectMaxSeqByCategory(categoryCode);
        redisTemplate.execute(RAISE_COUNTER_SCRIPT, List.of(KEY_PREFIX + categoryCode), Long.toString(dbMax));
    }

    private String categoryCode(Integer category) {
        String code = category == null ? null : CATEGORY_CODES.get(category);
        if (code == null) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(), "工具类别取值不正确");
        }
        return code;
    }
}
