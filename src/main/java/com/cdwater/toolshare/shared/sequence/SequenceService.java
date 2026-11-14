package com.cdwater.toolshare.shared.sequence;

import com.cdwater.toolshare.shared.enums.ToolCategory;
import com.cdwater.toolshare.shared.web.BizException;
import com.cdwater.toolshare.shared.web.ResultCode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Redis 原子序号：工具编码、租赁单号、报修单号。
 * <p>序号服务不可用时明确失败并提示重试，不回退随机数（随机数会破坏唯一性）。
 * <p>启动校准时只依赖 shared 内的 {@link JdbcTemplate}，不反向依赖各业务模块的 Mapper。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SequenceService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;

    /**
     * 启动时按数据库已有最大编号校准 Redis 序号，避免种子数据导致编码冲突。
     */
    @PostConstruct
    public void initSequence() {
        try {
            for (ToolCategory category : ToolCategory.values()) {
                raiseTo("tool:seq:" + category.code(), maxCode("t_tool", "code",
                        "SQ-G-" + category.code() + "-"));
            }
            String day = LocalDate.now().format(DAY);
            raiseTo("rental:seq:" + day, maxCode("t_rental", "order_no", "SQ-ZY-" + day + "-"));
            raiseTo("damage:seq:" + day, maxCode("t_damage_report", "report_no", "SQ-BX-" + day + "-"));
        } catch (Exception e) {
            log.error("初始化 Redis 序号失败，单号生成可能在恢复前不可用，请检查 Redis 后重试", e);
        }
    }

    private void raiseTo(String key, long min) {
        if (min <= 0) {
            return;
        }
        String current = redis.opsForValue().get(key);
        if (current == null || Long.parseLong(current) < min) {
            redis.opsForValue().set(key, String.valueOf(min));
        }
    }

    /** 按前缀取当前最大编号；SQL 标识符来自本类固定常量，不接受外部输入。 */
    private long maxCode(String table, String column, String prefix) {
        List<String> codes = jdbc.queryForList(
                "SELECT " + column + " FROM " + table + " WHERE " + column
                        + " LIKE ? ORDER BY " + column + " DESC LIMIT 1",
                String.class, prefix + "%");
        return codes.isEmpty() ? 0 : suffix(codes.get(0));
    }

    private long suffix(String code) {
        try {
            return Long.parseLong(code.substring(code.lastIndexOf('-') + 1));
        } catch (Exception e) {
            return 0;
        }
    }

    public String nextToolCode(String category) {
        long seq = next("tool:seq:" + category);
        return String.format("SQ-G-%s-%04d", category, seq);
    }

    public String nextRentalOrderNo() {
        String day = LocalDate.now().format(DAY);
        return String.format("SQ-ZY-%s-%04d", day, next("rental:seq:" + day));
    }

    public String nextDamageReportNo() {
        String day = LocalDate.now().format(DAY);
        return String.format("SQ-BX-%s-%04d", day, next("damage:seq:" + day));
    }

    private long next(String key) {
        try {
            Long value = redis.opsForValue().increment(key);
            if (value != null) {
                return value;
            }
            log.error("Redis 序号自增返回空值 key={}", key);
        } catch (Exception e) {
            log.error("Redis 序号自增失败 key={}", key, e);
        }
        throw new BizException(ResultCode.INTERNAL_SERVER_ERROR, "编号生成失败，请稍后重试");
    }
}
