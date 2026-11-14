package com.cdwater.toolshare.module.dashboard;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.module.message.MessageService;
import com.cdwater.toolshare.module.damage.DamageReport;
import com.cdwater.toolshare.module.rental.Rental;
import com.cdwater.toolshare.module.tool.Tool;
import com.cdwater.toolshare.module.auth.User;
import com.cdwater.toolshare.module.damage.DamageReportMapper;
import com.cdwater.toolshare.module.rental.RentalMapper;
import com.cdwater.toolshare.module.tool.ToolMapper;
import com.cdwater.toolshare.module.auth.UserMapper;
import com.cdwater.toolshare.module.dashboard.AdminDashboardVO;
import com.cdwater.toolshare.module.dashboard.UserDashboardVO;
import com.cdwater.toolshare.shared.enums.AuditStatus;
import com.cdwater.toolshare.shared.enums.ReportStatus;
import com.cdwater.toolshare.shared.enums.RentalStatus;
import com.cdwater.toolshare.shared.enums.ToolStatus;
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
        // 与用户端工具列表口径一致：仅审核通过且可借用的工具。
        vo.setAvailableToolCount(toolMapper.selectCount(
                new LambdaQueryWrapper<Tool>()
                        .eq(Tool::getStatus, ToolStatus.AVAILABLE.code())
                        .eq(Tool::getAuditStatus, AuditStatus.APPROVED.value())));
        vo.setPendingPaymentCount(rentalMapper.selectCount(
                new LambdaQueryWrapper<Rental>()
                        .eq(Rental::getUserId, userId)
                        .eq(Rental::getStatus, RentalStatus.PENDING_PAYMENT.code())));
        vo.setRentingCount(rentalMapper.selectCount(
                new LambdaQueryWrapper<Rental>()
                        .eq(Rental::getUserId, userId)
                        .in(Rental::getStatus, List.of(
                                RentalStatus.PICKED_UP.code(),
                                RentalStatus.OVERDUE.code()))));
        vo.setUnreadCount(messageService.unreadCount("USER", userId));
        return vo;
    }

    public AdminDashboardVO adminDashboard() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        AdminDashboardVO vo = new AdminDashboardVO();
        // 工具总数含已报废台账；可借用排除未审核通过的工具。
        vo.setToolTotal(toolMapper.selectCount(null));
        vo.setAvailableToolCount(toolMapper.selectCount(
                new LambdaQueryWrapper<Tool>()
                        .eq(Tool::getStatus, ToolStatus.AVAILABLE.code())
                        .eq(Tool::getAuditStatus, AuditStatus.APPROVED.value())));
        vo.setRentedToolCount(toolMapper.selectCount(
                new LambdaQueryWrapper<Tool>().eq(Tool::getStatus, ToolStatus.RENTED.code())));
        vo.setMaintenanceToolCount(toolMapper.selectCount(
                new LambdaQueryWrapper<Tool>()
                        .eq(Tool::getStatus, ToolStatus.MAINTENANCE.code())));
        // 活跃租赁不含待支付、已归还、已取消；逾期仍属活动租赁。
        vo.setActiveRentalCount(rentalMapper.selectCount(
                new LambdaQueryWrapper<Rental>()
                        .in(Rental::getStatus, List.of(
                                RentalStatus.RESERVED.code(),
                                RentalStatus.PICKED_UP.code(),
                                RentalStatus.OVERDUE.code()))));
        // 逾期按当前时间动态计算，不依赖定时物化，避免统计滞后
        LocalDateTime now = LocalDateTime.now();
        vo.setOverdueRentalCount(rentalMapper.selectCount(new LambdaQueryWrapper<Rental>()
                .and(w -> w.eq(Rental::getStatus, RentalStatus.OVERDUE.code())
                        .or(x -> x.eq(Rental::getStatus, RentalStatus.PICKED_UP.code())
                                .lt(Rental::getEndTime, now)))));
        // 待处理报修 = 尚未完结的报修，与报修状态机的活动状态一致。
        vo.setPendingDamageCount(damageReportMapper.selectCount(
                new LambdaQueryWrapper<DamageReport>()
                        .in(DamageReport::getStatus, List.of(
                                ReportStatus.SUBMITTED.code(),
                                ReportStatus.VERIFIED.code(),
                                ReportStatus.REPAIRING.code()))));
        vo.setUserCount(userMapper.selectCount(new LambdaQueryWrapper<User>()));
        vo.setTodayRentalCount(rentalMapper.selectCount(
                new LambdaQueryWrapper<Rental>().ge(Rental::getCreateTime, todayStart)));
        return vo;
    }
}
