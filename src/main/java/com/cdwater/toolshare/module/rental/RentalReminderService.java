/**
 * 预约到期提醒调度文件。
 *
 * <h2>数据模型</h2>
 * <p>不新增数据表，全部使用 Redis：
 * <ul>
 *   <li>{@code remind:at}（ZSET）：member=租赁 id，score=提醒时刻 epoch 秒；</li>
 *   <li>{@code remind:lead}（HASH）：field=租赁 id，value=用户选择的提前分钟数。</li>
 * </ul>
 *
 * <h2>生命周期</h2>
 * <p>创建预约、支付成功时 schedule；取消预约、归还、待支付超时时 cancel；
 * 定时任务每 30 秒扫描到点成员并发送站内消息。
 *
 * <h2>幂等与容错</h2>
 * <p>消息 biz_key 包含租赁 id 与提前量；发送成功才出队，失败保留重试。
 * Redis 全部操作都捕获异常并降级，不影响预约主流程。
 * 启动时按库内活跃租赁重建，Redis 丢数据最多退回默认提前量。
 *
  * <h2>键的生命周期</h2>
  * <p>remind:at 与 remind:lead 当前都没有 TTL：活跃租赁数量有限，
  * 由 cancel 与发送成功后的出队显式清理。若历史数据长期累积，
  * 可在启动重建时清理不存在于活跃集合中的成员。
  * <p>remind:lead 在用户选择不提醒（0）时仍然保留，
  * 目的是支付后能按原选择恢复调度；remind:at 在 0 时会被移除。
  *
  * <h2>失败恢复</h2>
  * <p>写入失败只记日志，预约主流程继续；下一次 schedule 会覆盖写入。
  * 扫描时消息写入失败保留 ZSET 成员，下一轮重试；
  * biz_key 保证重试不会重复打扰。租赁单被删除时同时清理两个键。
  *
  * <h2>多实例行为</h2>
  * <p>schedule/cancel 是幂等写入，多实例同时执行结果一致；
  * 扫描未加分布式锁，但消息幂等键使重复发送被抑制。
  * 若消息量大，应改为单实例调度或引入分布式任务锁。
  *
  * <h2>扫描批处理</h2>
  * <p>每轮最多取 50 个到期成员，避免长时间占用 Redis；
  * 处理顺序按 score 升序，最久未提醒的优先。
  *
    * <h2>容量与清理</h2>
    * <p>ZSET 只保存活跃租赁的提醒时刻，HASH 保存用户选择；
    * 两者都通过 cancel 与扫描成功后的显式删除控制规模。
    * 若历史数据异常增长，可在启动重建时按活跃租赁集合做一次清理。
    *
    * <h2>测试建议</h2>
    * <p>覆盖：提前量为 0、null、超过租期、提醒时刻已过；
    * 租赁单删除、状态非活跃、消息写入失败；
    * Redis 不可用时的降级与启动重建。
    * 断言重点是 ZSET/HASH 的最终状态与消息幂等键，而不是内部调用次数。
   * <h2>配置</h2>
   * <p>default-remind-minutes 默认 1440（1 天）；
   * 用户下单时传入的提前量优先于该配置。
   * 提前量为 0 表示不提醒，但仍保存选择，支付后可恢复调度。
   *
   * <h2>与租赁流程的衔接</h2>
   * <p>create 与 pay 成功后 schedule；cancel、归还、待支付超时后 cancel。
   * schedule 是覆盖写，重复调用安全；cancel 幂等。
   * 扫描任务与租赁定时任务相互独立，互不阻塞。
   *
        * <h2>相关文件</h2>
        * <p>RentalService（schedule/cancel 调用方）、MessageService（消息）、
        * RentalMapper（重建数据源）、RentalVO（提醒字段展示）。
       * <h2>数据一致性</h2>
       * <p>ZSET 与 HASH 不要求同时存在：用户选择 0 时只有 HASH；
       * 提醒发送成功后只删 ZSET。读取方必须容忍单边存在。
      * <h2>告警建议</h2>
      * <p>可对“扫描失败”“消息写入失败”“重建失败”的 WARN 日志设置告警；
      * 连续失败通常意味着 Redis 不可用或消息表写入异常。
      * 不建议对单条提醒失败告警，避免噪音。
     * <h2>容量估算</h2>
     * <p>每个活跃租赁在 ZSET 占一个成员、在 HASH 占一个字段；
     * 数量级与活跃租赁数相同，通常很小。
     * ZSET 按 score 范围查询，扫描成本与到期成员数相关，而不是全量。
     * 若提醒量显著增长，应减小每批数量或拆分扫描频率。
    * <h2>运维操作</h2>
    * <p>查看待发提醒：ZRANGEBYSCORE remind:at -inf +inf WITHSCORES；
    * 查看用户选择：HGETALL remind:lead。
    * <p>Redis 数据丢失后无需手工恢复：重启应用会按库内活跃租赁重建；
    * 也可等待下一次 schedule/pay 重新写入。
    * <p>若需临时停发，可移除 ZSET 成员但不删除 HASH，支付后仍可恢复调度。
   * <h2>失败可观测</h2>
   * <p>写入、取消、扫描、重建失败都记录 WARN，并带 rentalId；
   * 不记录用户手机号等敏感信息。
  * <h2>文案</h2>
  * <p>提前量按整日、整小时、分钟三档格式化；
  * 消息中回显用户设置，帮助理解提醒时机。
 * <h2>边界</h2>
 * <p>提前量不超过租期时长；若提醒时刻已过（短租），改为 1 分钟后补发一次。
 * 用户选择 0 表示不提醒，但仍保留选择记录，支付后可据此恢复调度。
 */
package com.cdwater.toolshare.module.rental;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.module.message.MessageService;
import com.cdwater.toolshare.shared.enums.MessageType;
import com.cdwater.toolshare.shared.enums.RentalStatus;
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

    // ZSET：member=租赁 id，score=提醒时刻（epoch 秒）
    private static final String KEY_AT = "remind:at";
    // HASH：field=租赁 id，value=提前分钟数；0 表示用户选择不提醒
    private static final String KEY_LEAD = "remind:lead";
    // 只有这些状态的租赁单需要到期提醒
    private static final List<RentalStatus> ACTIVE_STATUS =
            List.of(RentalStatus.RESERVED, RentalStatus.PICKED_UP, RentalStatus.OVERDUE);
    // 提醒消息中的到期时间格式
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    // 延迟队列与用户选择都存 Redis，不新增数据表
    private final StringRedisTemplate redis;
    // 发送前重新读取租赁单，确认状态与结束时间
    private final RentalMapper rentalMapper;
    // 站内消息发送，写入成功后才出队
    private final MessageService messageService;

    /** 用户未选择时的默认提前量（分钟），0 表示默认不提醒 */
    @Value("${toolshare.rental.default-remind-minutes:1440}")
    // 用户未选择时的默认提前量；0 表示默认不提醒
    private int defaultRemindMinutes = 1440;

    /**
      * <p>提前量会收敛到不超过租期时长；提醒时刻已过（短租或提前量过大）时
      * 改为 1 分钟后补发。提前量为 0 时只保存用户选择并从 ZSET 移除。
      * 任何 Redis 异常都只记日志，不影响预约主流程。
      *
      * @param rental      租赁单
      * @param leadMinutes 提前分钟数，0 表示不提醒；null 使用默认值
     * 写入/更新提醒调度。
     *
     * @param leadMinutes 提前分钟数，0 表示不提醒；null 使用默认值
     */
    public void schedule(Rental rental, Integer leadMinutes) {
        // 缺少主键或结束时间无法计算提醒时刻，直接跳过
        // 缺少必要字段无法调度
        if (rental == null || rental.getId() == null || rental.getEndTime() == null) {
            return;
        }
        // 后续所有 Redis 键都以租赁 id 为成员，局部变量避免重复取值
        Long rentalId = rental.getId();
        // null 表示用户未选择，使用配置默认值；0 是明确的“不提醒”
        // null 使用默认值；0 是明确的“不提醒”
        int lead = leadMinutes == null ? defaultRemindMinutes : leadMinutes;
        try {
            // 提前量不超过租期时长，避免提醒落在租期之前
            // 提前量不能超过租期，否则提醒会落在租期开始之前
            // 提前量不能超过租期，否则提醒会落在租期开始之前
            // 提前量不能超过租期，避免提醒落在租期之前
            if (rental.getStartTime() != null) {
                long durationMinutes = Duration.between(rental.getStartTime(), rental.getEndTime()).toMinutes();
                // 租期非正时不收敛，交给上层时间校验处理
                // 租期非正时不收敛，交给上层时间校验
                if (durationMinutes > 0) {
                    lead = (int) Math.min(lead, durationMinutes);
                }
            }
            // 把用户选择（含 0 = 不提醒）持久化到 Redis，启动重建时以此为准
            // 先持久化用户选择，启动重建时以此为准
            // 先持久化用户选择，启动重建时以此为准
            // 先保存用户选择，启动重建以它为准
            // 先保存用户选择
            redis.opsForHash().put(KEY_LEAD, String.valueOf(rentalId), String.valueOf(lead));
            // 不提醒：仅保留选择记录，确保 ZSET 中没有残留成员
            if (lead <= 0) {
                redis.opsForZSet().remove(KEY_AT, String.valueOf(rentalId));
                return;
            }
            // 提醒时刻 = 结束时间 - 提前量
            // 提醒时刻 = 结束时间 - 提前量
            // 提醒时刻 = 结束 - 提前量
            LocalDateTime remindAt = rental.getEndTime().minusMinutes(lead);
            LocalDateTime now = LocalDateTime.now();
            // 短租或提前量过大时提醒时刻已过，改为 1 分钟后补发
            // 短租或提前量过大时改为 1 分钟后补发
            // 已过时刻改为 1 分钟后补发
            if (!remindAt.isAfter(now)) {
                // 短租或提前量过大：1 分钟后补发一条「即将到期」
                remindAt = now.plusSeconds(60);
            }
            // ZSET score 使用系统时区的 epoch 秒，与扫描逻辑一致
            // score 使用系统时区 epoch 秒
            // score 使用 epoch 秒
            long score = remindAt.atZone(ZoneId.systemDefault()).toEpochSecond();
            // 同一租赁重复 schedule 会覆盖旧 score，天然幂等
            // 覆盖写，天然幂等
            redis.opsForZSet().add(KEY_AT, String.valueOf(rentalId), score);
        } catch (Exception e) {
            log.warn("写入到期提醒失败 rentalId={}: {}", rentalId, e.getMessage());
        }
    }

    // 幂等：不存在对应 member/field 时 Redis 删除操作本身返回 0，不报错。
    /** 取消提醒（取消预约、归还、删除调度） */
    /**
     * 取消提醒调度。
     * <p>同时清理 ZSET 与用户选择；取消预约、归还和待支付超时都会调用。
     * 异常只记日志，调用方不需要因提醒失败而回滚业务。
     *
     * @param rentalId 租赁单 id
     */
    public void cancel(Long rentalId) {
        if (rentalId == null) {
            return;
        }
        try {
            // 调度成员与用户选择必须同时清理，避免残留
            redis.opsForZSet().remove(KEY_AT, String.valueOf(rentalId));
            // 取消时同时删除用户选择
            // 取消时清理用户选择
            redis.opsForHash().delete(KEY_LEAD, String.valueOf(rentalId));
        } catch (Exception e) {
            log.warn("清理到期提醒失败 rentalId={}: {}", rentalId, e.getMessage());
        }
    }

    // 读取失败返回 null，让调用方按“未设置”处理，而不是中断业务。
    /** 读取用户选择的提前量（分钟），null 表示未设置 */
    public Integer leadOf(Long rentalId) {
        try {
            // 读取失败时返回 null，由调用方按“未设置”处理
            Object value = redis.opsForHash().get(KEY_LEAD, String.valueOf(rentalId));
            return value == null ? null : Integer.valueOf(value.toString());
        } catch (Exception e) {
            return null;
        }
    }

    // multiGet 一次取回当页所有租赁的选择，缺失项不放入结果。
    /** 批量读取用户选择的提前量，避免列表逐行查询 Redis。 */
    public Map<Long, Integer> leadsOf(Collection<Long> rentalIds) {
        List<Long> ids = distinctIds(rentalIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            // Redis HASH field 使用字符串 id
            // HASH field 使用字符串 id
            List<String> keys = ids.stream().map(String::valueOf).toList();
            // 一次 multiGet 取回当页所有选择，避免逐条访问 Redis
            List<String> values = redis.<String, String>opsForHash().multiGet(KEY_LEAD, keys);
            Map<Long, Integer> result = new HashMap<>();
            for (int i = 0; i < ids.size(); i++) {
                String value = values.get(i);
                // 只返回实际存在的选择
                // 只返回存在的选择
                if (value != null) {
                    result.put(ids.get(i), Integer.valueOf(value));
                }
            }
            return result;
        } catch (Exception e) {
            return Map.of();
        }
    }

    // ZSET 通常只保存少量活跃提醒，范围读取整表后在内存中过滤当页 id。
    /** 批量读取计划提醒时刻。提醒集合只包含活跃租赁，单次读取即可覆盖当页。 */
    public Map<Long, LocalDateTime> remindAtsOf(Collection<Long> rentalIds) {
        List<Long> ids = distinctIds(rentalIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            Set<Long> wanted = Set.copyOf(ids);
            // 提醒集合只含活跃租赁，整表读取后在内存中过滤当页 id
            Set<ZSetOperations.TypedTuple<String>> tuples =
                    redis.opsForZSet().rangeWithScores(KEY_AT, 0, -1);
            Map<Long, LocalDateTime> result = new HashMap<>();
            // 空集合直接返回空 Map
            // 空集合返回空 Map
            if (tuples == null) {
                return result;
            }
            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                // 跳过脏成员
                // 跳过脏成员
                if (tuple.getValue() == null || tuple.getScore() == null) {
                    continue;
                }
                // 成员值非法时会在外层捕获，读取失败返回空 Map 而不是报错
                Long rentalId = Long.valueOf(tuple.getValue());
                // 只保留当页需要的 id
                // 只保留当页 id
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

    /**
     * 过滤 null 并去重，保持原始顺序。
     *
     * @param rentalIds 租赁 id 集合，可为 null
     * @return 去重后的 id 列表
     */
    private List<Long> distinctIds(Collection<Long> rentalIds) {
        // 空输入直接返回
        // 空输入直接返回
        if (rentalIds == null || rentalIds.isEmpty()) {
            return List.of();
        }
        // 去重且过滤 null，保持输入顺序
        return new ArrayList<>(rentalIds.stream().filter(Objects::nonNull).distinct().toList());
    }

    // 单条读取用于详情页；score 不存在说明用户选择不提醒或提醒已发送。
    /** 读取计划提醒时刻，null 表示不提醒或读取失败 */
    public LocalDateTime remindAtOf(Long rentalId) {
        try {
            // 不存在 score 表示不提醒或提醒已发送
            Double score = redis.opsForZSet().score(KEY_AT, String.valueOf(rentalId));
            // 不存在分数表示不提醒或已发送
            // 不存在分数表示不提醒或已发送
            if (score == null) {
                return null;
            }
            return java.time.Instant.ofEpochSecond(score.longValue())
                    .atZone(ZoneId.systemDefault()).toLocalDateTime();
        } catch (Exception e) {
            return null;
        }
    }

    // 每批最多处理 50 条，避免单次扫描占用 Redis/数据库过久。
    // 发送失败或状态暂不满足时保留在 ZSET，下一轮重试；biz_key 防止重复消息。
    /** 每 30 秒扫描一次到点的提醒 */
    @Scheduled(initialDelay = 20_000, fixedDelay = 30_000)
    public void scanDueReminders() {
        // ZSET score 使用秒级时间戳，与写入时保持一致
        // 扫描使用秒级当前时间，与写入 score 的精度一致
        // 扫描使用秒级当前时间，与写入 score 的精度一致
        long nowEpoch = System.currentTimeMillis() / 1000;
        Set<String> due;
        try {
            // 只取 score <= 当前时间的成员，限制每批数量
            // 每批最多 50 条，控制单轮扫描的 Redis 与数据库压力
            // 每批最多 50 条，控制单轮扫描的 Redis 与数据库压力
            // 只取已到点且未处理的前 50 条
            // 每批最多 50 条
            due = redis.opsForZSet().rangeByScore(KEY_AT, 0, nowEpoch, 0, 50);
        } catch (Exception e) {
            log.warn("扫描到期提醒失败: {}", e.getMessage());
            return;
        }
        // 没有到期成员时直接结束本轮
        // 无到期成员时结束本轮
        if (due == null || due.isEmpty()) {
            return;
        }
        for (String member : due) {
            Long rentalId;
            try {
                // member 不是合法 id 时直接移除，防止脏数据反复占用扫描窗口
                // 非法成员直接移除，避免脏数据反复占用扫描窗口
                // 非法成员直接移除，避免脏数据反复占用扫描窗口
                // 非法成员直接移除
                // 非法成员直接移除
                rentalId = Long.valueOf(member);
            } catch (NumberFormatException e) {
                redis.opsForZSet().remove(KEY_AT, member);
                continue;
            }
            try {
                // 以数据库当前状态为准
                Rental rental = rentalMapper.selectById(rentalId);
                // 租赁单已删除：连同意愿记录一起清理
                // 租赁单已删除：调度与用户选择一起清理
                // 租赁单已删除：调度与用户选择一起清理
                // 租赁单已删除时清理调度与选择
                // 租赁单已删除时清理两个键
                if (rental == null) {
                    // 租赁单已不存在，连同意愿记录一起清理
                    redis.opsForHash().delete(KEY_LEAD, member);
                    redis.opsForZSet().remove(KEY_AT, member);
                    continue;
                }
                // 重新读取用户选择，用于消息文案
                // 读取用户选择用于消息文案
                // 读取用户选择用于消息文案
                // 读取用户选择用于文案
                // 读取提前量用于文案
                Integer lead = leadOf(rentalId);
                // 状态非法时按无需提醒处理，只出队不发送
                // 状态非法视为无需提醒
                // 状态非法视为无需提醒
                RentalStatus status = RentalStatus.fromCode(rental.getStatus()).orElse(null);
                // 仅在活跃且尚未到期时提醒；已归还/已到期的单不再打扰
                // 仅在活跃且未到期时提醒，已归还/已取消的不再打扰
                // 仅在活跃且未到期时提醒，已归还/已取消的不再打扰
                // 只有活跃且未到期的租赁才提醒
                // 只提醒活跃且未到期的单
                boolean shouldNotify = status != null && ACTIVE_STATUS.contains(status)
                        && rental.getEndTime() != null
                        && rental.getEndTime().isAfter(LocalDateTime.now());
                boolean done = true;
                if (shouldNotify) {
                    // 在消息中回显用户设置的提前量，便于用户理解提醒时机
                    // 在文案中回显提前量，帮助用户理解提醒时机
                    // 在文案中回显提前量，帮助用户理解提醒时机
                    String suffix = lead != null && lead > 0
                            ? "（你设置了提前 " + describeLead(lead) + "提醒）" : "";
                    // 幂等键包含租赁 id 与提前量；同一调度重复扫描不会重复发送
                    // biz_key 含租赁 id 与提前量，重复扫描不会重复发送
                    // biz_key 含租赁 id 与提前量，重复扫描不会重复发送
                    // 幂等键包含租赁 id 与提前量
                    // biz_key 含租赁 id 与提前量
                    done = messageService.notifyUserOnce(rental.getUserId(), MessageType.RENT_DUE, "租赁到期提醒",
                            "租赁单 " + rental.getOrderNo() + " 将于 "
                                    + rental.getEndTime().format(FMT) + " 到期" + suffix + "，请按时归还。",
                            "RENT_DUE:" + rentalId + ":" + lead);
                }
                // 消息写入成功才出队；提前量记录保留，支付后仍可按原选择重新调度
                // 消息写入成功才出队；失败保留在 ZSET 等待下一轮
                // 消息写入成功才出队；失败保留在 ZSET 等待下一轮
                if (done) {
                    // 只删除调度时刻，保留 remind:lead 中的用户选择
                    // 只删除调度时刻，保留 remind:lead 中的用户选择
                    // 只出队，不删除用户选择的提前量：未支付被跳过时，支付后仍能按原选择重新调度
                    redis.opsForZSet().remove(KEY_AT, member);
                } else {
                    // 消息未写入成功：保留在 ZSet，下一轮扫描重试（biz_key 保证不会重复打扰）
                    // 消息未写入：保留在 ZSET，下一轮扫描重试
                    // 消息未写入：保留调度，下一轮重试
                    // 消息未写入：保留调度，下一轮重试
                    log.warn("到期提醒未写入，保留在队列等待重试 rentalId={}", rentalId);
                }
            } catch (Exception e) {
                // Redis / 数据库异常同样保留，下一轮扫描重试
                // Redis/数据库异常保留成员，下一轮重试
                // 异常保留成员，下一轮重试
                log.warn("发送到期提醒失败 rentalId={}: {}", rentalId, e.getMessage());
            }
        }
    }

    // 以库内活跃租赁为准：没有用户选择的历史单按默认提前量补，
    // 有计划时刻的保持不变；Redis 数据丢失最多退回默认提前量。
    /** 启动重建：Redis 丢数据时按库内活跃租赁补齐调度 */
    @EventListener(ApplicationReadyEvent.class)
    public void rebuildOnStartup() {
        try {
            // 只重建未到期且仍活跃的租赁单
            // 只重建未到期且仍活跃的租赁单
            // 只重建未到期且仍活跃的租赁单
            List<Rental> active = rentalMapper.selectList(new LambdaQueryWrapper<Rental>()
                    .in(Rental::getStatus, ACTIVE_STATUS.stream().map(RentalStatus::code).toList())
                    .gt(Rental::getEndTime, LocalDateTime.now()));
            // 逐单补齐调度，已存在的不覆盖
            // 逐单补齐调度
            for (Rental rental : active) {
                Integer lead = leadOf(rental.getId());
                // 从未设置过提前量的历史单使用默认值
                // 从未设置过的历史单使用默认提前量
                // 从未设置过的历史单使用默认提前量
                if (lead == null) {
                    // 未设置过的历史租赁单按默认提前量补
                    // 历史单使用默认提前量
                    // 历史单使用默认值
                    schedule(rental, defaultRemindMinutes);
                // 用户选择 0 表示不提醒，不重建；已有调度时刻的不重复写入
                // 用户选择 0 不重建；已有调度时刻的不重复写入
                // 用户选择 0 不重建；已有调度时刻的不重复写入
                } else if (lead > 0 && remindAtOf(rental.getId()) == null) {
                    // 用户选择保留，只补缺失的调度时刻
                    // 保留用户选择，只补缺失调度
                    schedule(rental, lead);
                }
            }
            log.info("到期提醒重建完成，活跃租赁 {} 条", active.size());
        } catch (Exception e) {
            log.warn("到期提醒重建失败: {}", e.getMessage());
        }
    }

    /**
     * 把提前分钟数转为易读文本：整天、整小时或分钟。
     *
     * @param minutes 提前分钟数
     * @return 例如“1 天”“2 小时”“30 分钟”
     */
    private String describeLead(int minutes) {
        // 优先用“天”表达，避免出现 1440 分钟这类不直观文案
        if (minutes % 1440 == 0) {
            // 整日显示为天
            // 整日显示为天
            return (minutes / 1440) + " 天";
        }
        // 整小时用“小时”表达
        if (minutes % 60 == 0) {
            // 整小时显示为小时
            // 整小时显示为小时
            return (minutes / 60) + " 小时";
        }
        // 其余按分钟显示
        // 其余按分钟显示
        return minutes + " 分钟";
    }
}
