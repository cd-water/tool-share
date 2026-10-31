package com.cdwater.toolshare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.entity.Rental;
import com.cdwater.toolshare.mapper.RentalMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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
     * 每 10 分钟扫描一次：取件前 2 小时、到期前 24 小时、逾期。
     */
    @Scheduled(initialDelay = 60_000, fixedDelay = 600_000)
    public void scan() {
        LocalDateTime now = LocalDateTime.now();
        try {
            List<Rental> pickups = rentalMapper.selectList(new LambdaQueryWrapper<Rental>()
                    .eq(Rental::getStatus, "RESERVED")
                    .between(Rental::getStartTime, now, now.plusHours(2)));
            for (Rental rental : pickups) {
                messageService.notifyUserOnce(rental.getUserId(), "PICKUP", "取件提醒",
                        "预约单 " + rental.getOrderNo() + " 即将到取件时间，请携带身份证或预约单号前往"
                                + "社区服务站取件。",
                        "PICKUP:" + rental.getId());
            }

            List<Rental> dueSoon = rentalMapper.selectList(new LambdaQueryWrapper<Rental>()
                    .eq(Rental::getStatus, "PICKED_UP")
                    .between(Rental::getEndTime, now, now.plusHours(24)));
            for (Rental rental : dueSoon) {
                messageService.notifyUserOnce(rental.getUserId(), "RENT_DUE", "租赁到期提醒",
                        "预约单 " + rental.getOrderNo() + " 将于 "
                                + rental.getEndTime().toLocalDate() + " 到期，请按时归还工具。",
                        "RENT_DUE:" + rental.getId());
            }
            rentalService.markOverdue();
        } catch (Exception e) {
            log.warn("定时提醒任务执行失败: {}", e.getMessage());
        }
    }
}
