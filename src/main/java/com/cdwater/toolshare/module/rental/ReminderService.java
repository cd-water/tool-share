package com.cdwater.toolshare.module.rental;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.module.message.MessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReminderService {

    private final RentalMapper rentalMapper;
    private final MessageService messageService;
    private final RentalService rentalService;

    /**
     * 每 2 分钟扫描一次：待支付超时取消、取件前 2 小时提醒、逾期状态刷新、逾期兜底提醒。
     * <p>待支付超时复用现有 2 分钟调度节奏（ADR-0004 单实例假设），不额外新增调度器。
     * <p>到期前提醒由用户自定义（{@link RentalReminderService}）；这里负责“到期仍未归还”的兜底提醒，
     * 即使用户选择了「不提醒」也会收到。
     */
    @Scheduled(initialDelay = 30_000, fixedDelay = 120_000)
    public void scan() {
        LocalDateTime now = LocalDateTime.now();
        try {
            rentalService.expirePending();

            List<Rental> pickups = rentalMapper.selectList(new LambdaQueryWrapper<Rental>()
                    .eq(Rental::getStatus, "RESERVED")
                    .between(Rental::getStartTime, now, now.plusHours(2)));
            for (Rental rental : pickups) {
                messageService.notifyUserOnce(rental.getUserId(), "PICKUP", "取件提醒",
                        "预约单 " + rental.getOrderNo() + " 即将到取件时间，请携带身份证或预约单号前往"
                                + "社区服务站取件。",
                        "PICKUP:" + rental.getId());
            }

            // 到期未归还：先物化逾期状态（内部会发一条 OVERDUE 提醒），再补分级催还
            rentalService.markOverdue();
            overdueFallback(now);
        } catch (Exception e) {
            log.warn("定时提醒任务执行失败: {}", e.getMessage());
        }
    }

    /** 逾期兜底提醒：逾期 1 天、3 天各补一次（biz_key 去重，不重复打扰） */
    private void overdueFallback(LocalDateTime now) {
        List<Rental> overdue = rentalMapper.selectList(new LambdaQueryWrapper<Rental>()
                .eq(Rental::getStatus, "OVERDUE"));
        for (Rental rental : overdue) {
            if (rental.getEndTime() == null) {
                continue;
            }
            long days = Duration.between(rental.getEndTime(), now).toDays();
            if (days >= 3) {
                messageService.notifyUserOnce(rental.getUserId(), "OVERDUE", "租赁逾期提醒",
                        "预约单 " + rental.getOrderNo() + " 已逾期 " + days + " 天，请尽快归还，"
                                + "逾期费按每小时 5 元计算（封顶押金）。",
                        "OVERDUE:" + rental.getId() + ":3d");
            } else if (days >= 1) {
                messageService.notifyUserOnce(rental.getUserId(), "OVERDUE", "租赁逾期提醒",
                        "预约单 " + rental.getOrderNo() + " 已逾期 " + days + " 天，请尽快归还工具。",
                        "OVERDUE:" + rental.getId() + ":1d");
            }
        }
    }
}
