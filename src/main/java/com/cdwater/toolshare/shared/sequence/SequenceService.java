package com.cdwater.toolshare.shared.sequence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.module.damage.DamageReport;
import com.cdwater.toolshare.module.rental.Rental;
import com.cdwater.toolshare.module.tool.Tool;
import com.cdwater.toolshare.module.damage.DamageReportMapper;
import com.cdwater.toolshare.module.rental.RentalMapper;
import com.cdwater.toolshare.module.tool.ToolMapper;
import com.cdwater.toolshare.shared.web.BizException;
import com.cdwater.toolshare.shared.web.ResultCode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Redis 原子序号：工具编码、租赁单号、报修单号。
 * <p>序号服务不可用时明确失败并提示重试，不回退随机数（随机数会破坏唯一性）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SequenceService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StringRedisTemplate redis;
    private final ToolMapper toolMapper;
    private final RentalMapper rentalMapper;
    private final DamageReportMapper damageReportMapper;

    /**
     * 启动时按数据库已有最大编号校准 Redis 序号，避免种子数据导致编码冲突。
     */
    @PostConstruct
    void initSequence() {
        try {
            for (String category : List.of("WX", "YY", "QJ", "QT", "OTHER")) {
                raiseTo("tool:seq:" + category, maxToolSeq(category));
            }
            String day = LocalDate.now().format(DAY);
            raiseTo("rental:seq:" + day, maxRentalSeq(day));
            raiseTo("damage:seq:" + day, maxDamageSeq(day));
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

    private long maxToolSeq(String category) {
        Tool tool = toolMapper.selectOne(new LambdaQueryWrapper<Tool>()
                .likeRight(Tool::getCode, "SQ-G-" + category + "-")
                .orderByDesc(Tool::getCode)
                .last("LIMIT 1"));
        return tool == null ? 0 : suffix(tool.getCode());
    }

    private long maxRentalSeq(String day) {
        Rental rental = rentalMapper.selectOne(new LambdaQueryWrapper<Rental>()
                .likeRight(Rental::getOrderNo, "SQ-ZY-" + day + "-")
                .orderByDesc(Rental::getOrderNo)
                .last("LIMIT 1"));
        return rental == null ? 0 : suffix(rental.getOrderNo());
    }

    private long maxDamageSeq(String day) {
        DamageReport report = damageReportMapper.selectOne(new LambdaQueryWrapper<DamageReport>()
                .likeRight(DamageReport::getReportNo, "SQ-BX-" + day + "-")
                .orderByDesc(DamageReport::getReportNo)
                .last("LIMIT 1"));
        return report == null ? 0 : suffix(report.getReportNo());
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
