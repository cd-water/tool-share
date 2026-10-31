package com.cdwater.toolshare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.entity.DamageReport;
import com.cdwater.toolshare.entity.Rental;
import com.cdwater.toolshare.entity.Tool;
import com.cdwater.toolshare.entity.User;
import com.cdwater.toolshare.mapper.DamageReportMapper;
import com.cdwater.toolshare.mapper.RentalMapper;
import com.cdwater.toolshare.mapper.ToolMapper;
import com.cdwater.toolshare.mapper.UserMapper;
import com.cdwater.toolshare.vo.AdminDashboardVO;
import com.cdwater.toolshare.vo.UserDashboardVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final ToolMapper toolMapper;
    private final RentalMapper rentalMapper;
    private final DamageReportMapper damageReportMapper;
    private final UserMapper userMapper;
    private final MessageService messageService;

    public UserDashboardVO userDashboard(Long userId) {
        UserDashboardVO vo = new UserDashboardVO();
        vo.setAvailableToolCount(toolMapper.selectCount(
                new LambdaQueryWrapper<Tool>().eq(Tool::getStatus, "AVAILABLE")));
        vo.setPendingPaymentCount(rentalMapper.selectCount(
                new LambdaQueryWrapper<Rental>()
                        .eq(Rental::getUserId, userId)
                        .eq(Rental::getStatus, "PENDING_PAYMENT")));
        vo.setRentingCount(rentalMapper.selectCount(
                new LambdaQueryWrapper<Rental>()
                        .eq(Rental::getUserId, userId)
                        .in(Rental::getStatus, List.of("PICKED_UP", "OVERDUE"))));
        vo.setUnreadCount(messageService.unreadCount("USER", userId));
        return vo;
    }

    public AdminDashboardVO adminDashboard() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        AdminDashboardVO vo = new AdminDashboardVO();
        vo.setToolTotal(toolMapper.selectCount(null));
        vo.setAvailableToolCount(toolMapper.selectCount(
                new LambdaQueryWrapper<Tool>().eq(Tool::getStatus, "AVAILABLE")));
        vo.setRentedToolCount(toolMapper.selectCount(
                new LambdaQueryWrapper<Tool>().eq(Tool::getStatus, "RENTED")));
        vo.setMaintenanceToolCount(toolMapper.selectCount(
                new LambdaQueryWrapper<Tool>().eq(Tool::getStatus, "MAINTENANCE")));
        vo.setActiveRentalCount(rentalMapper.selectCount(
                new LambdaQueryWrapper<Rental>()
                        .in(Rental::getStatus, List.of("RESERVED", "PICKED_UP", "OVERDUE"))));
        vo.setOverdueRentalCount(rentalMapper.selectCount(
                new LambdaQueryWrapper<Rental>().eq(Rental::getStatus, "OVERDUE")));
        vo.setPendingDamageCount(damageReportMapper.selectCount(
                new LambdaQueryWrapper<DamageReport>().eq(DamageReport::getStatus, "SUBMITTED")));
        vo.setUserCount(userMapper.selectCount(new LambdaQueryWrapper<User>()));
        vo.setTodayRentalCount(rentalMapper.selectCount(
                new LambdaQueryWrapper<Rental>().ge(Rental::getCreateTime, todayStart)));
        return vo;
    }
}
