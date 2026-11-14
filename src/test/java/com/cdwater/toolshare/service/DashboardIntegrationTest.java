package com.cdwater.toolshare.service;

import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
import com.cdwater.toolshare.module.auth.User;
import com.cdwater.toolshare.module.auth.UserMapper;
import com.cdwater.toolshare.module.damage.DamageReport;
import com.cdwater.toolshare.module.damage.DamageReportMapper;
import com.cdwater.toolshare.module.dashboard.AdminDashboardVO;
import com.cdwater.toolshare.module.dashboard.DashboardService;
import com.cdwater.toolshare.module.dashboard.UserDashboardVO;
import com.cdwater.toolshare.module.message.Message;
import com.cdwater.toolshare.module.message.MessageMapper;
import com.cdwater.toolshare.module.rental.Rental;
import com.cdwater.toolshare.module.rental.RentalMapper;
import com.cdwater.toolshare.module.tool.Tool;
import com.cdwater.toolshare.module.tool.ToolMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * #23 仪表盘统计口径：只统计真实数据，终态不计入活动量，空数据返回 0。
 */
@SpringBootTest
@Import(TestStorageConfig.class)
class DashboardIntegrationTest extends AbstractIntegrationTest {

    private static final String PREFIX = "IT-DASH-";
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    DashboardService dashboardService;
    @Autowired
    ToolMapper toolMapper;
    @Autowired
    RentalMapper rentalMapper;
    @Autowired
    DamageReportMapper damageReportMapper;
    @Autowired
    UserMapper userMapper;
    @Autowired
    MessageMapper messageMapper;

    final List<Long> toolIds = new ArrayList<>();
    final List<Long> rentalIds = new ArrayList<>();
    final List<Long> reportIds = new ArrayList<>();
    final List<Long> userIds = new ArrayList<>();
    final List<Long> messageIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        messageIds.forEach(messageMapper::deleteById);
        reportIds.forEach(damageReportMapper::deleteById);
        rentalIds.forEach(rentalMapper::deleteById);
        toolIds.forEach(toolMapper::deleteById);
        userIds.forEach(userMapper::deleteById);
        messageIds.clear();
        reportIds.clear();
        rentalIds.clear();
        toolIds.clear();
        userIds.clear();
    }

    @Test
    void admin_dashboard_counts_active_rentals_and_unfinished_reports_only() {
        AdminDashboardVO before = dashboardService.adminDashboard();

        createTool("AVAILABLE", 1);
        createTool("AVAILABLE", 0);
        createTool("RENTED", 1);
        createTool("MAINTENANCE", 1);
        createTool("SCRAPPED", 1);

        long renterId = createUser();
        LocalDateTime now = LocalDateTime.now().withNano(0);
        createRental(renterId, "PENDING_PAYMENT", now);
        createRental(renterId, "RESERVED", now);
        createRental(renterId, "PICKED_UP", now);
        createRental(renterId, "OVERDUE", now);
        createRental(renterId, "RETURNED", now);
        createRental(renterId, "CANCELLED", now);

        createReport("SUBMITTED");
        createReport("VERIFIED");
        createReport("REPAIRING");
        createReport("REJECTED");
        createReport("COMPLETED");
        createReport("SCRAPPED");

        AdminDashboardVO after = dashboardService.adminDashboard();

        assertEquals(before.getToolTotal() + 5, after.getToolTotal(), "工具总数含已报废台账");
        assertEquals(before.getAvailableToolCount() + 1, after.getAvailableToolCount(),
                "可借用只统计审核通过且状态 AVAILABLE 的工具");
        assertEquals(before.getRentedToolCount() + 1, after.getRentedToolCount());
        assertEquals(before.getMaintenanceToolCount() + 1, after.getMaintenanceToolCount());
        assertEquals(before.getActiveRentalCount() + 3, after.getActiveRentalCount(),
                "活跃租赁 = RESERVED + PICKED_UP + OVERDUE，不含待支付与终态");
        assertEquals(before.getOverdueRentalCount() + 1, after.getOverdueRentalCount());
        assertEquals(before.getPendingDamageCount() + 3, after.getPendingDamageCount(),
                "待处理报修 = SUBMITTED + VERIFIED + REPAIRING");
        assertEquals(before.getUserCount() + 1, after.getUserCount());
        assertEquals(before.getTodayRentalCount() + 6, after.getTodayRentalCount());
    }

    @Test
    void overdue_count_is_computed_even_before_materialization() {
        AdminDashboardVO before = dashboardService.adminDashboard();
        long renterId = createUser();
        LocalDateTime now = LocalDateTime.now().withNano(0);
        // PICKED_UP 且已过结束时间：定时物化尚未执行，仪表盘也必须算作逾期
        createRental(renterId, "PICKED_UP", now, now.minusHours(2), now.minusHours(1));

        AdminDashboardVO after = dashboardService.adminDashboard();

        assertEquals(before.getOverdueRentalCount() + 1, after.getOverdueRentalCount());
    }

    @Test
    void user_dashboard_counts_only_own_active_rentals_and_unread_messages() {
        long ownerId = createUser();
        long otherId = createUser();
        UserDashboardVO emptyOwner = dashboardService.userDashboard(ownerId);

        createTool("AVAILABLE", 1);
        createTool("AVAILABLE", 0);

        LocalDateTime now = LocalDateTime.now().withNano(0);
        createRental(ownerId, "PENDING_PAYMENT", now);
        createRental(ownerId, "PICKED_UP", now);
        createRental(ownerId, "OVERDUE", now);
        createRental(ownerId, "RETURNED", now);
        createRental(ownerId, "CANCELLED", now);
        createRental(otherId, "PENDING_PAYMENT", now);

        createMessage(ownerId, 0);
        createMessage(ownerId, 0);
        createMessage(ownerId, 1);
        createMessage(otherId, 0);

        UserDashboardVO owner = dashboardService.userDashboard(ownerId);
        UserDashboardVO other = dashboardService.userDashboard(otherId);

        assertEquals(0, emptyOwner.getPendingPaymentCount(), "无数据时待支付为 0");
        assertEquals(0, emptyOwner.getRentingCount(), "无数据时使用中为 0");
        assertEquals(0, emptyOwner.getUnreadCount(), "无数据时未读为 0");

        assertEquals(emptyOwner.getAvailableToolCount() + 1, owner.getAvailableToolCount(),
                "可用工具只统计审核通过的可借工具，全用户一致");
        assertEquals(owner.getAvailableToolCount(), other.getAvailableToolCount());
        assertEquals(1, owner.getPendingPaymentCount(), "只统计本人待支付");
        assertEquals(2, owner.getRentingCount(), "使用中 = PICKED_UP + OVERDUE");
        assertEquals(2, owner.getUnreadCount(), "只统计本人未读");
        assertEquals(1, other.getPendingPaymentCount());
        assertEquals(0, other.getRentingCount());
        assertEquals(1, other.getUnreadCount());
    }

    private long createUser() {
        User user = new User();
        user.setPhone(PREFIX + "1" + SEQ.incrementAndGet());
        user.setNickname(PREFIX + "居民");
        user.setStatus(1);
        userMapper.insert(user);
        userIds.add(user.getId());
        return user.getId();
    }

    private void createTool(String status, int auditStatus) {
        Tool tool = new Tool();
        tool.setCode(PREFIX + SEQ.incrementAndGet());
        tool.setName(PREFIX + "工具");
        tool.setCategory("WX");
        tool.setStatus(status);
        tool.setAuditStatus(auditStatus);
        tool.setDeposit(new BigDecimal("100.00"));
        toolMapper.insert(tool);
        toolIds.add(tool.getId());
    }

    private void createRental(long userId, String status, LocalDateTime createTime) {
        createRental(userId, status, createTime, createTime.plusDays(1), createTime.plusDays(2));
    }

    private void createRental(long userId, String status, LocalDateTime createTime,
                              LocalDateTime startTime, LocalDateTime endTime) {
        Rental rental = new Rental();
        rental.setOrderNo(PREFIX + SEQ.incrementAndGet());
        rental.setToolId(toolIds.isEmpty() ? 1L : toolIds.get(0));
        rental.setToolCode(PREFIX + "CODE");
        rental.setToolName(PREFIX + "工具");
        rental.setUserId(userId);
        rental.setRenterName(PREFIX + "租赁人");
        rental.setRenterPhone("13900000000");
        rental.setStartTime(startTime);
        rental.setEndTime(endTime);
        rental.setDepositAmount(new BigDecimal("100.00"));
        rental.setDepositStatus("PAID");
        rental.setStatus(status);
        rental.setCreateTime(createTime);
        rentalMapper.insert(rental);
        rentalIds.add(rental.getId());
    }

    private void createReport(String status) {
        DamageReport report = new DamageReport();
        report.setReportNo(PREFIX + SEQ.incrementAndGet());
        report.setRentalId(rentalIds.isEmpty() ? 1L : rentalIds.get(0));
        report.setToolId(toolIds.isEmpty() ? 1L : toolIds.get(0));
        report.setToolCode(PREFIX + "CODE");
        report.setToolName(PREFIX + "工具");
        report.setUserId(userIds.isEmpty() ? 1L : userIds.get(0));
        report.setReporterName(PREFIX + "报修人");
        report.setReporterPhone("13900000000");
        report.setDamagePart("电机");
        report.setDescription(PREFIX + "损坏描述");
        report.setStatus(status);
        report.setCreateTime(LocalDateTime.now().withNano(0));
        // 活动报修同一租赁单只允许一张（V10 唯一索引）：每条活动报修使用独立 rentalId。
        report.setRentalId(9_000_000L + SEQ.incrementAndGet());
        damageReportMapper.insert(report);
        reportIds.add(report.getId());
    }

    private void createMessage(long receiverId, int readStatus) {
        Message message = new Message();
        message.setReceiverType("USER");
        message.setReceiverId(receiverId);
        message.setType("SYSTEM");
        message.setTitle(PREFIX + "消息");
        message.setContent(PREFIX + "内容");
        message.setChannel("SYSTEM");
        message.setReadStatus(readStatus);
        messageMapper.insert(message);
        messageIds.add(message.getId());
    }
}
