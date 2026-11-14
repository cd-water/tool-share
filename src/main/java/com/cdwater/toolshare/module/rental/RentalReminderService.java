package com.cdwater.toolshare.module.rental;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.module.message.MessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 预约到期提醒：用户下单时选择「到期前多久提醒」，这里负责调度与发送。
 * <p>不新增数据表，使用 Redis 延迟队列：
 * <ul>
 *   <li>{@code remind:at}（ZSET）：member = 租赁 id，score = 提醒时刻（epoch 秒）</li>
 *   <li>{@code remind:lead}（HASH）：field = 租赁 id，value = 提前分钟数（0 表示用户选择不提醒）</li>
 * </ul>
 * 启动时按库内活跃租赁重建，Redis 数据丢失后最多退回默认提前量。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RentalReminderService {

    private static final String KEY_AT = "remind:at";
    private static final String KEY_LEAD = "remind:lead";
    private static final List<String> ACTIVE_STATUS = List.of("RESERVED", "PICKED_UP", "OVERDUE");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final StringRedisTemplate redis;
    private final RentalMapper rentalMapper;
    private final MessageService messageService;

    /** 用户未选择时的默认提前量（分钟），0 表示默认不提醒 */
    @Value("${toolshare.rental.default-remind-minutes:1440}")
    private int defaultRemindMinutes = 1440;

    /**
     * 写入/更新提醒调度。
     *
     * @param leadMinutes 提前分钟数，0 表示不提醒；null 使用默认值
     */
    public void schedule(Rental rental, Integer leadMinutes) {
        if (rental == null || rental.getId() == null || rental.getEndTime() == null) {
            return;
        }
        Long rentalId = rental.getId();
        int lead = leadMinutes == null ? defaultRemindMinutes : leadMinutes;
        try {
            // 提前量不超过租期时长，避免提醒落在租期之前
            if (rental.getStartTime() != null) {
                long durationMinutes = Duration.between(rental.getStartTime(), rental.getEndTime()).toMinutes();
                if (durationMinutes > 0) {
                    lead = (int) Math.min(lead, durationMinutes);
                }
            }
            // 把用户选择（含 0 = 不提醒）持久化到 Redis，启动重建时以此为准
            redis.opsForHash().put(KEY_LEAD, String.valueOf(rentalId), String.valueOf(lead));
            if (lead <= 0) {
                redis.opsForZSet().remove(KEY_AT, String.valueOf(rentalId));
                return;
            }
            LocalDateTime remindAt = rental.getEndTime().minusMinutes(lead);
            LocalDateTime now = LocalDateTime.now();
            if (!remindAt.isAfter(now)) {
                // 短租或提前量过大：1 分钟后补发一条「即将到期」
                remindAt = now.plusSeconds(60);
            }
            long score = remindAt.atZone(ZoneId.systemDefault()).toEpochSecond();
            redis.opsForZSet().add(KEY_AT, String.valueOf(rentalId), score);
        } catch (Exception e) {
            log.warn("写入到期提醒失败 rentalId={}: {}", rentalId, e.getMessage());
        }
    }

    /** 取消提醒（取消预约、归还、删除调度） */
    public void cancel(Long rentalId) {
        if (rentalId == null) {
            return;
        }
        try {
            redis.opsForZSet().remove(KEY_AT, String.valueOf(rentalId));
            redis.opsForHash().delete(KEY_LEAD, String.valueOf(rentalId));
        } catch (Exception e) {
            log.warn("清理到期提醒失败 rentalId={}: {}", rentalId, e.getMessage());
        }
    }

    /** 读取用户选择的提前量（分钟），null 表示未设置 */
    public Integer leadOf(Long rentalId) {
        try {
            Object value = redis.opsForHash().get(KEY_LEAD, String.valueOf(rentalId));
            return value == null ? null : Integer.valueOf(value.toString());
        } catch (Exception e) {
            return null;
        }
    }

    /** 批量读取用户选择的提前量，避免列表逐行查询 Redis。 */
    public Map<Long, Integer> leadsOf(Collection<Long> rentalIds) {
        List<Long> ids = distinctIds(rentalIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            List<String> keys = ids.stream().map(String::valueOf).toList();
            List<String> values = redis.<String, String>opsForHash().multiGet(KEY_LEAD, keys);
            Map<Long, Integer> result = new HashMap<>();
            for (int i = 0; i < ids.size(); i++) {
                String value = values.get(i);
                if (value != null) {
                    result.put(ids.get(i), Integer.valueOf(value));
                }
            }
            return result;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 批量读取计划提醒时刻。提醒集合只包含活跃租赁，单次读取即可覆盖当页。 */
    public Map<Long, LocalDateTime> remindAtsOf(Collection<Long> rentalIds) {
        List<Long> ids = distinctIds(rentalIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            Set<Long> wanted = Set.copyOf(ids);
            Set<ZSetOperations.TypedTuple<String>> tuples =
                    redis.opsForZSet().rangeWithScores(KEY_AT, 0, -1);
            Map<Long, LocalDateTime> result = new HashMap<>();
            if (tuples == null) {
                return result;
            }
            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                if (tuple.getValue() == null || tuple.getScore() == null) {
                    continue;
                }
                Long rentalId = Long.valueOf(tuple.getValue());
                if (wanted.contains(rentalId)) {
                    result.put(rentalId, java.time.Instant.ofEpochSecond(tuple.getScore().longValue())
                            .atZone(ZoneId.systemDefault()).toLocalDateTime());
                }
            }
            return result;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<Long> distinctIds(Collection<Long> rentalIds) {
        if (rentalIds == null || rentalIds.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(rentalIds.stream().filter(Objects::nonNull).distinct().toList());
    }

    /** 读取计划提醒时刻，null 表示不提醒或读取失败 */
    public LocalDateTime remindAtOf(Long rentalId) {
        try {
            Double score = redis.opsForZSet().score(KEY_AT, String.valueOf(rentalId));
            if (score == null) {
                return null;
            }
            return java.time.Instant.ofEpochSecond(score.longValue())
                    .atZone(ZoneId.systemDefault()).toLocalDateTime();
        } catch (Exception e) {
            return null;
        }
    }

    /** 每 30 秒扫描一次到点的提醒 */
    @Scheduled(initialDelay = 20_000, fixedDelay = 30_000)
    public void scanDueReminders() {
        long nowEpoch = System.currentTimeMillis() / 1000;
        Set<String> due;
        try {
            due = redis.opsForZSet().rangeByScore(KEY_AT, 0, nowEpoch, 0, 50);
        } catch (Exception e) {
            log.warn("扫描到期提醒失败: {}", e.getMessage());
            return;
        }
        if (due == null || due.isEmpty()) {
            return;
        }
        for (String member : due) {
            Long rentalId;
            try {
                rentalId = Long.valueOf(member);
            } catch (NumberFormatException e) {
                redis.opsForZSet().remove(KEY_AT, member);
                continue;
            }
            try {
                Rental rental = rentalMapper.selectById(rentalId);
                if (rental == null) {
                    // 订单已不存在，连同意愿记录一起清理
                    redis.opsForHash().delete(KEY_LEAD, member);
                    redis.opsForZSet().remove(KEY_AT, member);
                    continue;
                }
                Integer lead = leadOf(rentalId);
                boolean shouldNotify = ACTIVE_STATUS.contains(rental.getStatus())
                        && rental.getEndTime() != null
                        && rental.getEndTime().isAfter(LocalDateTime.now());
                boolean done = true;
                if (shouldNotify) {
                    String suffix = lead != null && lead > 0
                            ? "（你设置了提前 " + describeLead(lead) + "提醒）" : "";
                    done = messageService.notifyUserOnce(rental.getUserId(), "RENT_DUE", "租赁到期提醒",
                            "预约单 " + rental.getOrderNo() + " 将于 "
                                    + rental.getEndTime().format(FMT) + " 到期" + suffix + "，请按时归还。",
                            "RENT_DUE:" + rentalId + ":" + lead);
                }
                if (done) {
                    // 只出队，不删除用户选择的提前量：未支付被跳过时，支付后仍能按原选择重新调度
                    redis.opsForZSet().remove(KEY_AT, member);
                } else {
                    // 消息未写入成功：保留在 ZSet，下一轮扫描重试（biz_key 保证不会重复打扰）
                    log.warn("到期提醒未写入，保留在队列等待重试 rentalId={}", rentalId);
                }
            } catch (Exception e) {
                // Redis / 数据库异常同样保留，下一轮扫描重试
                log.warn("发送到期提醒失败 rentalId={}: {}", rentalId, e.getMessage());
            }
        }
    }

    /** 启动重建：Redis 丢数据时按库内活跃租赁补齐调度 */
    @EventListener(ApplicationReadyEvent.class)
    public void rebuildOnStartup() {
        try {
            List<Rental> active = rentalMapper.selectList(new LambdaQueryWrapper<Rental>()
                    .in(Rental::getStatus, ACTIVE_STATUS)
                    .gt(Rental::getEndTime, LocalDateTime.now()));
            for (Rental rental : active) {
                Integer lead = leadOf(rental.getId());
                if (lead == null) {
                    // 未设置过的历史订单按默认提前量补
                    schedule(rental, defaultRemindMinutes);
                } else if (lead > 0 && remindAtOf(rental.getId()) == null) {
                    schedule(rental, lead);
                }
            }
            log.info("到期提醒重建完成，活跃租赁 {} 条", active.size());
        } catch (Exception e) {
            log.warn("到期提醒重建失败: {}", e.getMessage());
        }
    }

    private String describeLead(int minutes) {
        if (minutes % 1440 == 0) {
            return (minutes / 1440) + " 天";
        }
        if (minutes % 60 == 0) {
            return (minutes / 60) + " 小时";
        }
        return minutes + " 分钟";
    }
}
