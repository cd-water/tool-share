/**
 * 租赁定时提醒调度文件。
 *
 * <h2>调度节奏</h2>
 * <p>每 2 分钟一轮：待支付超时取消、取件前 2 小时提醒、逾期物化、逾期 1/3 天兜底催还。
 * 单实例调度假设见 ADR-0004；若扩展为多实例，需要引入分布式锁或改为分布式任务。
 *
 * <h2>幂等</h2>
 * <p>所有站内消息都走 notifyUserOnce，biz_key 由租赁单 id 与提醒档位组成；
 * 重复扫描不会重复打扰，失败也不会留下半发送状态。
 *
 * <h2>与用户自定义提醒的分工</h2>
 * <p>RentalReminderService 负责用户选择“提前多久”的到期提醒；
 * 本服务负责到期仍未归还的兜底提醒，即使用户选择不提醒也会收到。
 *
 * <h2>异常策略</h2>
 * <p>单轮任一步骤失败只记日志，下一轮继续执行；不会因一次异常让定时任务整体停摆。
 */
package com.cdwater.toolshare.module.rental;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.module.message.MessageService;
import com.cdwater.toolshare.shared.enums.MessageType;
import com.cdwater.toolshare.shared.enums.RentalStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 租赁定时提醒任务。
 * <p>每 2 分钟执行一轮，负责：待支付超时取消、取件前 2 小时提醒、
 * 逾期状态物化与逾期 1/3 天的兜底催还。
 * <p>与 {@link RentalReminderService} 的分工：后者按用户选择的提前量发送“即将到期”提醒；
 * 本服务负责“到期仍未归还”的兜底提醒，即使用户选择不提醒也会收到。
 * <p>单实例调度假设见 ADR-0004；所有提醒都通过 biz_key 幂等，重复执行不会重复打扰。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReminderService {

    // 扫描待取件与逾期租赁
    private final RentalMapper rentalMapper;
    // 站内提醒发送，按 biz_key 去重
    private final MessageService messageService;
    // 复用待支付超时取消与逾期物化逻辑
    private final RentalService rentalService;

    /**
     * 每 2 分钟扫描一次：待支付超时取消、取件前 2 小时提醒、逾期状态刷新、逾期兜底提醒。
     * <p>待支付超时复用现有 2 分钟调度节奏（ADR-0004 单实例假设），不额外新增调度器。
     * <p>到期前提醒由用户自定义（{@link RentalReminderService}）；这里负责“到期仍未归还”的兜底提醒，
     * 即使用户选择了「不提醒」也会收到。
     */
    @Scheduled(initialDelay = 30_000, fixedDelay = 120_000)
    /**
     * 单轮扫描入口。
     * <p>各步骤相互独立但共用一次异常兜底：任一步异常只记录日志，
     * 下一轮会继续执行，不会让定时任务整体停摆。
     */
    public void scan() {
        // 以本轮开始时间作为所有时间判断的基准，避免跨步骤时间漂移
        // 本轮所有时间判断共用同一基准，避免跨步骤时间漂移
        // 本轮所有时间判断共用同一基准，避免跨步骤时间漂移
        LocalDateTime now = LocalDateTime.now();
        try {
            // 超时待支付单自动取消并释放档期
            rentalService.expirePending();

            // 取件提醒窗口：已预约且开始时间落在 [now, now+2h]
            // 取件提醒窗口：已预约且开始时间落在 [now, now+2h]
            // 取件提醒窗口：已预约且开始时间落在 [now, now+2h]
            List<Rental> pickups = rentalMapper.selectList(new LambdaQueryWrapper<Rental>()
                    .eq(Rental::getStatus, RentalStatus.RESERVED.code())
                    .between(Rental::getStartTime, now, now.plusHours(2)));
            // 逐单发送；notifyUserOnce 内部保证同一预约只提醒一次
            for (Rental rental : pickups) {
                // 幂等键只含租赁 id：同一预约的取件提醒最多发送一次
                messageService.notifyUserOnce(rental.getUserId(), MessageType.PICKUP, "取件提醒",
                        "租赁单 " + rental.getOrderNo() + " 即将到取件时间，请携带身份证或租赁单号前往"
                                + "社区服务站取件。",
                        "PICKUP:" + rental.getId());
            }

            // 先物化 OVERDUE，确保后续兜底提醒能查到最新逾期集合
            // 到期未归还：先物化逾期状态（内部会发一条 OVERDUE 提醒），再补分级催还
            // 使用中且已过结束时间的单转逾期，并发送首次逾期提醒
            // 先把到期的使用中租赁物化为逾期，兜底提醒才能查到完整集合
            // 先把到期的使用中租赁物化为逾期，兜底提醒才能查到完整集合
            rentalService.markOverdue();
            // 已逾期单的分级兜底催还
            // 对已逾期单补充分级催还
            // 对已逾期单补充分级催还
            overdueFallback(now);
        } catch (Exception e) {
            log.warn("定时提醒任务执行失败: {}", e.getMessage());
        }
    }

    // 分级策略：1 天提醒尽快归还，3 天补充费用规则说明；
    // 两个档位使用不同 biz_key，同一档位只发送一次。
    /** 逾期兜底提醒：逾期 1 天、3 天各补一次（biz_key 去重，不重复打扰） */
    private void overdueFallback(LocalDateTime now) {
        // 只处理已物化为 OVERDUE 的单
        // 只处理已物化为 OVERDUE 的租赁单
        // 只处理已物化为 OVERDUE 的租赁单
        List<Rental> overdue = rentalMapper.selectList(new LambdaQueryWrapper<Rental>()
                .eq(Rental::getStatus, RentalStatus.OVERDUE.code()));
        // 逐单判断逾期天数，选择对应档位的幂等键
        for (Rental rental : overdue) {
            // 历史脏数据缺少结束时间时跳过，避免计算空指针
            if (rental.getEndTime() == null) {
                continue;
            }
            // 按完整天数计算逾期时长，向下取整
            // 完整天数向下取整，避免提前触发高一级提醒
            // 完整天数向下取整，避免提前触发高一级提醒
            long days = Duration.between(rental.getEndTime(), now).toDays();
            // 逾期 3 天及以上：提醒费用规则
            // 3 天档：补充逾期费规则，语气更强
            // 3 天档：补充逾期费规则，语气更强
            if (days >= 3) {
                messageService.notifyUserOnce(rental.getUserId(), MessageType.OVERDUE, "租赁逾期提醒",
                        "租赁单 " + rental.getOrderNo() + " 已逾期 " + days + " 天，请尽快归还，"
                                + "逾期费按每小时 5 元计算（封顶押金）。",
                        // 3 天档位的幂等键
                        "OVERDUE:" + rental.getId() + ":3d");
            // 逾期 1-2 天：轻量提醒
            // 1-2 天档：轻量提醒尽快归还
            // 1-2 天档：轻量提醒尽快归还
            } else if (days >= 1) {
                messageService.notifyUserOnce(rental.getUserId(), MessageType.OVERDUE, "租赁逾期提醒",
                        "租赁单 " + rental.getOrderNo() + " 已逾期 " + days + " 天，请尽快归还工具。",
                        // 1 天档位的幂等键
                        "OVERDUE:" + rental.getId() + ":1d");
            }
        }
    }
}
