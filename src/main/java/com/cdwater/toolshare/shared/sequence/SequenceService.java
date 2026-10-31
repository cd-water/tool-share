package com.cdwater.toolshare.shared.sequence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.module.damage.DamageReport;
import com.cdwater.toolshare.module.rental.Rental;
import com.cdwater.toolshare.module.tool.Tool;
import com.cdwater.toolshare.module.damage.DamageReportMapper;
import com.cdwater.toolshare.module.rental.RentalMapper;
import com.cdwater.toolshare.module.tool.ToolMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Redis 原子序号：工具编码、租赁单号、报修单号。
 */
@Service
@RequiredArgsConstructor
public class SequenceService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private final StringRedisTemplate redis;
    private final ToolMapper toolMapper;
    private final RentalMapper rentalMapper;
    private final DamageReportMapper damageReportMapper;
    private final SecureRandom random = new SecureRandom();

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
        } catch (Exception ignored) {
            // 数据库暂不可用时保持 Redis 自增，业务层仍有初始化保护
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
        } catch (Exception ignored) {
            // Redis 不可用时退化为随机序号，保证主流程可用
        }
        return 1 + random.nextInt(9999);
    }
}
