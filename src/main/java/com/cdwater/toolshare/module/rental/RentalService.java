package com.cdwater.toolshare.module.rental;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.module.auth.Admin;
import com.cdwater.toolshare.module.auth.AdminMapper;
import com.cdwater.toolshare.module.message.MessageService;
import com.cdwater.toolshare.module.tool.ToolService;
import com.cdwater.toolshare.shared.enums.AuditStatus;
import com.cdwater.toolshare.shared.enums.DamageResult;
import com.cdwater.toolshare.shared.enums.DepositStatus;
import com.cdwater.toolshare.shared.enums.PaymentRecordType;
import com.cdwater.toolshare.shared.enums.RentalFeeType;
import com.cdwater.toolshare.shared.enums.RentalStatus;
import com.cdwater.toolshare.shared.enums.ToolStatus;
import com.cdwater.toolshare.shared.security.IdCardCipher;
import com.cdwater.toolshare.shared.sequence.SequenceService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cdwater.toolshare.shared.web.BizException;
import com.cdwater.toolshare.shared.web.Dict;
import com.cdwater.toolshare.shared.web.PageQuery;
import com.cdwater.toolshare.shared.web.ResultCode;
import com.cdwater.toolshare.shared.config.StpKit;
import com.cdwater.toolshare.module.rental.RentalCreateDTO;
import com.cdwater.toolshare.module.rental.RentalReturnDTO;
import com.cdwater.toolshare.module.rental.Rental;
import com.cdwater.toolshare.module.tool.Tool;
import com.cdwater.toolshare.module.auth.User;
import com.cdwater.toolshare.module.rental.RentalMapper;
import com.cdwater.toolshare.module.tool.ToolMapper;
import com.cdwater.toolshare.module.auth.UserMapper;
import com.cdwater.toolshare.shared.web.PageVO;
import com.cdwater.toolshare.module.rental.RentalExportRow;
import com.cdwater.toolshare.module.rental.RentalVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class RentalService {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final BigDecimal HOUR_FEE = new BigDecimal("5");

    private final RentalMapper rentalMapper;
    private final RentalFeeMapper rentalFeeMapper;
    private final PaymentRecordMapper paymentRecordMapper;
    private final ToolMapper toolMapper;
    private final UserMapper userMapper;
    private final AdminMapper adminMapper;
    private final ToolService toolService;
    private final SequenceService sequenceService;
    private final MessageService messageService;
    private final RentalReminderService reminderService;
    private final RentalVoMapper rentalVoMapper;

    @Value("${toolshare.rental.pending-payment-timeout-minutes:30}")
    private long pendingPaymentTimeoutMinutes = 30;

    @Value("${toolshare.rental.severe-damage-fee:50}")
    private BigDecimal severeDamageFee = new BigDecimal("50");

    /** 逾期状态由定时任务物化，列表查询前做一次惰性刷新，避免筛选存在最长 10 分钟的窗口 */
    private final AtomicLong lastOverdueRefresh = new AtomicLong(0);

    private void ensureOverdueFresh() {
        long now = System.currentTimeMillis();
        long last = lastOverdueRefresh.get();
        if (now - last < 10_000) {
            return;
        }
        if (lastOverdueRefresh.compareAndSet(last, now)) {
            try {
                markOverdue();
            } catch (Exception e) {
                log.warn("刷新逾期状态失败: {}", e.getMessage());
            }
        }
    }

    // READ_COMMITTED：拿到工具行锁后，冲突查询必须看到前一事务已提交的租赁单（RR 快照会漏读）
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RentalVO create(RentalCreateDTO dto) {
        User user = currentUser();
        if (!StringUtils.hasText(user.getRealName()) || !StringUtils.hasText(user.getIdCard())) {
            throw new BizException(ResultCode.BAD_REQUEST, "请先在个人中心完善真实姓名与身份证号");
        }
        // 先锁工具行，再查冲突并插入，串行化同一工具的并发预约
        Tool tool = toolMapper.selectByIdForUpdate(dto.getToolId());
        if (tool == null) {
            throw new BizException(ResultCode.NOT_FOUND, "工具不存在");
        }
        if (tool.getAuditStatus() == null || tool.getAuditStatus() != AuditStatus.APPROVED.code()) {
            throw new BizException(ResultCode.BAD_REQUEST, "工具尚未通过审核，暂不可预约");
        }
        if (!"AVAILABLE".equals(tool.getStatus())) {
            throw new BizException(ResultCode.BAD_REQUEST,
                    "工具当前不可预约：" + Dict.label(Dict.TOOL_STATUS, tool.getStatus()));
        }
        LocalDateTime now = LocalDateTime.now();
        if (!dto.getStartTime().isAfter(now)) {
            throw new BizException(ResultCode.BAD_REQUEST, "开始时间必须晚于当前时间");
        }
        if (!dto.getStartTime().isBefore(dto.getEndTime())) {
            throw new BizException(ResultCode.BAD_REQUEST, "结束时间必须晚于开始时间");
        }
        List<Rental> conflicts = rentalMapper.selectConflicts(tool.getId(),
                dto.getStartTime(), dto.getEndTime(), pendingPaymentTimeoutMinutes);
        if (!conflicts.isEmpty()) {
            String suggestion = conflicts.stream()
                    .map(item -> item.getEndTime().plusHours(1).format(DATE_TIME))
                    .findFirst().orElse("其他时间");
            throw new BizException(ResultCode.CONFLICT, "该时段已被预约，建议 " + suggestion + " 之后");
        }

        Rental rental = new Rental();
        rental.setOrderNo(sequenceService.nextRentalOrderNo());
        rental.setToolId(tool.getId());
        rental.setToolCode(tool.getCode());
        rental.setToolName(tool.getName());
        rental.setUserId(user.getId());
        rental.setRenterName(StringUtils.hasText(user.getRealName()) ? user.getRealName() : user.getNickname());
        rental.setRenterPhone(user.getPhone());
        rental.setStartTime(dto.getStartTime());
        rental.setEndTime(dto.getEndTime());
        rental.setPickupPerson(dto.getPickupPerson().trim());
        rental.setDepositAmount(tool.getDeposit());
        rental.setDepositStatus(DepositStatus.UNPAID.code());
        rental.setStatus(RentalStatus.PENDING_PAYMENT.code());
        rental.setOverdueFee(BigDecimal.ZERO);
        rental.setDamageFee(BigDecimal.ZERO);
        rental.setRefundAmount(BigDecimal.ZERO);
        rentalMapper.insert(rental);
        // 按用户选择的提前量登记到期提醒（0 = 不提醒）
        reminderService.schedule(rental, dto.getRemindBeforeMinutes());
        messageService.notifyUser(user.getId(), "RESERVATION", "预约已创建",
                "预约单 " + rental.getOrderNo() + " 已创建，请在 " + pendingPaymentTimeoutMinutes
                        + " 分钟内完成押金支付。");
        return toVO(rental);
    }

    @Transactional
    public void pay(Long id) {
        Rental rental = getUserRental(id);
        RentalStateMachine.assertTransition(statusOf(rental), RentalStatus.RESERVED);
        if (rentalMapper.pay(id, pendingPaymentTimeoutMinutes) == 0) {
            throw new BizException(ResultCode.CONFLICT, "当前状态不可支付或支付已超时");
        }
        rental.setDepositStatus(DepositStatus.PAID.code());
        rental.setStatus(RentalStatus.RESERVED.code());
        recordPayment(rental.getId(), PaymentRecordType.DEPOSIT_IN, rental.getDepositAmount(),
                rental.getRenterName(), "押金支付");
        // 支付完成后确保提醒仍在调度中（用户可能在下单后修改过提前量）
        reminderService.schedule(rental, reminderService.leadOf(rental.getId()));
        messageService.notifyUser(rental.getUserId(), "RESERVATION", "预约成功",
                "预约单 " + rental.getOrderNo() + " 押金支付成功，请按预约时间取件。");
    }

    @Transactional
    public void cancelByUser(Long id, String reason) {
        Rental rental = getUserRental(id);
        RentalStateMachine.assertTransition(statusOf(rental), RentalStatus.CANCELLED);
        if (LocalDateTime.now().isAfter(rental.getStartTime().minusHours(24))) {
            throw new BizException(ResultCode.CONFLICT, "取件前 24 小时内不可取消，请联系社区工作人员");
        }
        doCancel(rental, reason, "用户取消");
    }

    @Transactional
    public void cancelByAdmin(Long id, String reason) {
        Rental rental = getRental(id);
        RentalStateMachine.assertTransition(statusOf(rental), RentalStatus.CANCELLED);
        doCancel(rental, reason, "管理员取消");
    }

    private void doCancel(Rental rental, String reason, String operator) {
        String cancelReason = StringUtils.hasText(reason) ? reason : operator;
        if (rentalMapper.cancel(rental.getId(), cancelReason) == 0) {
            throw new BizException(ResultCode.CONFLICT, "当前状态不可取消");
        }
        // 支付与取消可能并发，以原子更新后的押金状态为准补记退款流水
        Rental cancelled = rentalMapper.selectById(rental.getId());
        if (cancelled != null && DepositStatus.REFUNDED.code().equals(cancelled.getDepositStatus())
                && cancelled.getRefundAmount() != null && cancelled.getRefundAmount().signum() > 0) {
            recordPayment(cancelled.getId(), PaymentRecordType.DEPOSIT_REFUND,
                    cancelled.getRefundAmount(), operatorName(rental), "取消租赁退还押金");
        }
        reminderService.cancel(rental.getId());
        messageService.notifyUser(rental.getUserId(), "SYSTEM", "预约已取消",
                "预约单 " + rental.getOrderNo() + " 已取消，押金将在 1-3 个工作日内退还。");
    }

    @Transactional
    public void pickup(Long id) {
        Rental rental = getRental(id);
        if (!DepositStatus.PAID.code().equals(rental.getDepositStatus())) {
            throw new BizException(ResultCode.CONFLICT, "押金尚未支付，不可取件");
        }
        RentalStateMachine.assertTransition(statusOf(rental), RentalStatus.PICKED_UP);
        Tool tool = toolService.getTool(rental.getToolId());
        if (!ToolStatus.AVAILABLE.code().equals(tool.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "工具当前状态不可取件："
                    + Dict.label(Dict.TOOL_STATUS, tool.getStatus()));
        }
        if (rentalMapper.transit(id, RentalStatus.RESERVED.code(),
                RentalStatus.PICKED_UP.code()) == 0) {
            throw new BizException(ResultCode.CONFLICT, "当前状态不可取件");
        }
        tool.setRentalCount((tool.getRentalCount() == null ? 0 : tool.getRentalCount()) + 1);
        toolService.transition(tool, ToolStatus.RENTED, "PICKUP", "取件后工具状态变为已租出");
        messageService.notifyUser(rental.getUserId(), "SYSTEM", "工具已取件",
                "预约单 " + rental.getOrderNo() + " 已确认取件，请按时归还。");
    }

    @Transactional
    public void returnRental(Long id, RentalReturnDTO dto) {
        DamageResult damageResult = DamageResult.fromCode(dto.getDamageResult())
                .orElseThrow(() -> new BizException(ResultCode.BAD_REQUEST, "无效的验收结果"));
        Rental rental = getRental(id);
        RentalStateMachine.assertTransition(statusOf(rental), RentalStatus.RETURNED);
        LocalDateTime actualReturnTime = dto.getActualReturnTime();
        if (actualReturnTime.isBefore(rental.getStartTime())) {
            throw new BizException(ResultCode.BAD_REQUEST, "实际归还时间不能早于租赁开始时间");
        }

        BigDecimal overdueFee = calcOverdueFee(rental, actualReturnTime);
        BigDecimal damageFee = damageResult == DamageResult.SEVERE
                ? severeDamageFee.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        boolean waiveOverdue = Boolean.TRUE.equals(dto.getWaiveOverdue());
        boolean waiveDamage = Boolean.TRUE.equals(dto.getWaiveDamage());
        BigDecimal waived = BigDecimal.ZERO;
        if (waiveOverdue || waiveDamage) {
            if (!StringUtils.hasText(dto.getWaiverReason())) {
                throw new BizException(ResultCode.BAD_REQUEST, "减免必须填写原因");
            }
            if (waiveOverdue) {
                waived = waived.add(overdueFee);
            }
            if (waiveDamage) {
                waived = waived.add(damageFee);
            }
        }
        BigDecimal netCharges = overdueFee.add(damageFee).subtract(waived);
        BigDecimal refund = rental.getDepositAmount().subtract(netCharges).max(BigDecimal.ZERO);
        BigDecimal receivable = netCharges.subtract(rental.getDepositAmount()).max(BigDecimal.ZERO);

        if (rentalMapper.returnRental(id, actualReturnTime, overdueFee, damageFee, refund,
                damageResult.code(), dto.getRemark()) == 0) {
            throw new BizException(ResultCode.CONFLICT, "当前状态不可归还");
        }

        String operator = operatorName(rental);
        if (overdueFee.signum() > 0) {
            recordFee(rental.getId(), RentalFeeType.OVERDUE, overdueFee,
                    "按实际归还时间计算的逾期费用", operator);
        }
        if (damageFee.signum() > 0) {
            recordFee(rental.getId(), RentalFeeType.DAMAGE, damageFee,
                    "验收结果：" + damageResult.label(), operator);
        }
        if (waiveOverdue && overdueFee.signum() > 0) {
            recordFee(rental.getId(), RentalFeeType.WAIVER, overdueFee,
                    "减免逾期费：" + dto.getWaiverReason().trim(), operator);
        }
        if (waiveDamage && damageFee.signum() > 0) {
            recordFee(rental.getId(), RentalFeeType.WAIVER, damageFee,
                    "减免损坏赔偿：" + dto.getWaiverReason().trim(), operator);
        }
        if (refund.signum() > 0) {
            recordPayment(rental.getId(), PaymentRecordType.DEPOSIT_REFUND, refund,
                    operator, "归还验收后退还押金");
        }
        if (receivable.signum() > 0) {
            recordPayment(rental.getId(), PaymentRecordType.RECEIVABLE, receivable,
                    operator, "赔偿超出押金的应收");
        }
        reminderService.cancel(rental.getId());

        Tool tool = toolService.getTool(rental.getToolId());
        if (damageResult == DamageResult.SEVERE) {
            tool.setDamageLevel("SEVERE");
            toolService.transition(tool, ToolStatus.MAINTENANCE, "RETURN",
                    "归还验收为严重损坏，工具转维修中");
        } else {
            if (damageResult == DamageResult.MINOR) {
                tool.setDamageLevel("LIGHT");
            }
            toolService.transition(tool, ToolStatus.AVAILABLE, "RETURN",
                    "归还验收完成，工具恢复可用");
        }
        messageService.notifyUser(rental.getUserId(), "SYSTEM", "归还验收完成",
                "预约单 " + rental.getOrderNo() + " 已归还，逾期费 " + overdueFee
                        + " 元，损坏赔偿 " + damageFee + " 元，减免 " + waived
                        + " 元，退还押金 " + refund + " 元"
                        + (receivable.signum() > 0 ? "，另有应收 " + receivable + " 元。" : "。"));
    }

    /** 逾期费按实际归还时间计算：超过结束时间的小时数向上取整，每小时 5 元，封顶押金。 */
    private BigDecimal calcOverdueFee(Rental rental, LocalDateTime actualReturnTime) {
        if (!actualReturnTime.isAfter(rental.getEndTime())) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        long seconds = Duration.between(rental.getEndTime(), actualReturnTime).getSeconds();
        long hours = Math.max(1, (seconds + 3599) / 3600);
        return HOUR_FEE.multiply(BigDecimal.valueOf(hours))
                .min(rental.getDepositAmount())
                .setScale(2, RoundingMode.HALF_UP);
    }

    private void recordFee(Long rentalId, RentalFeeType feeType, BigDecimal amount,
                           String reason, String operator) {
        RentalFee fee = new RentalFee();
        fee.setRentalId(rentalId);
        fee.setFeeType(feeType.code());
        fee.setAmount(amount.setScale(2, RoundingMode.HALF_UP));
        fee.setReason(reason);
        fee.setOperatorName(operator);
        rentalFeeMapper.insert(fee);
    }

    private void recordPayment(Long rentalId, PaymentRecordType recordType, BigDecimal amount,
                               String operator, String remark) {
        PaymentRecord record = new PaymentRecord();
        record.setRentalId(rentalId);
        record.setRecordType(recordType.code());
        record.setAmount(amount.setScale(2, RoundingMode.HALF_UP));
        record.setOperatorName(operator);
        record.setRemark(remark);
        paymentRecordMapper.insert(record);
    }

    private String operatorName(Rental rental) {
        if (StpKit.ADMIN.isLogin()) {
            Admin admin = adminMapper.selectById(StpKit.ADMIN.getLoginIdAsLong());
            if (admin != null) {
                return admin.getUsername();
            }
        }
        return rental.getRenterName();
    }

    public PageVO<RentalVO> userPage(String status, long page, long size) {
        ensureOverdueFresh();
        LambdaQueryWrapper<Rental> wrapper = new LambdaQueryWrapper<Rental>()
                .eq(Rental::getUserId, StpKit.USER.getLoginIdAsLong());
        if (StringUtils.hasText(status)) {
            wrapper.eq(Rental::getStatus, status);
        }
        Page<Rental> result = rentalMapper.selectPage(PageQuery.of(page, size),
                wrapper.orderByDesc(Rental::getCreateTime));
        return PageVO.of(result, rentalVoMapper::toVO);
    }

    public PageVO<RentalVO> adminPage(String keyword, String status, String overdue,
                                      long page, long size) {
        ensureOverdueFresh();
        Page<Rental> result = rentalMapper.selectPage(PageQuery.of(page, size),
                adminWrapper(keyword, status, overdue).orderByDesc(Rental::getCreateTime));
        return PageVO.of(result, rentalVoMapper::toVO);
    }

    private LambdaQueryWrapper<Rental> adminWrapper(String keyword, String status, String overdue) {
        LambdaQueryWrapper<Rental> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(Rental::getOrderNo, keyword)
                    .or().like(Rental::getToolCode, keyword)
                    .or().like(Rental::getToolName, keyword)
                    .or().like(Rental::getRenterName, keyword)
                    .or().like(Rental::getRenterPhone, keyword));
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(Rental::getStatus, status);
        }
        if ("true".equalsIgnoreCase(overdue)) {
            wrapper.and(w -> w.eq(Rental::getStatus, "OVERDUE")
                    .or(x -> x.eq(Rental::getStatus, "PICKED_UP")
                            .lt(Rental::getEndTime, LocalDateTime.now())));
        }
        return wrapper;
    }

    public RentalVO userDetail(Long id) {
        return toVO(getUserRental(id));
    }

    public RentalVO adminDetail(Long id) {
        return toVO(getRental(id));
    }

    public Rental getRental(Long id) {
        Rental rental = rentalMapper.selectById(id);
        if (rental == null) {
            throw new BizException(ResultCode.NOT_FOUND, "租赁记录不存在");
        }
        return rental;
    }

    private Rental getUserRental(Long id) {
        Rental rental = getRental(id);
        if (!rental.getUserId().equals(StpKit.USER.getLoginIdAsLong())) {
            throw new BizException(ResultCode.FORBIDDEN, "无权查看该租赁记录");
        }
        return rental;
    }

    public List<RentalExportRow> exportRows(String keyword, String status, String overdue) {
        List<Rental> rentals = rentalMapper.selectList(
                adminWrapper(keyword, status, overdue).orderByDesc(Rental::getCreateTime));
        return rentals.stream().map(this::toVO).map(vo -> {
            RentalExportRow row = new RentalExportRow();
            row.setOrderNo(vo.getOrderNo());
            row.setToolCode(vo.getToolCode());
            row.setToolName(vo.getToolName());
            row.setRenterName(vo.getRenterName());
            row.setRenterPhone(IdCardCipher.maskPhone(vo.getRenterPhone()));
            row.setPickupPerson(vo.getPickupPerson());
            row.setStartTime(format(vo.getStartTime()));
            row.setEndTime(format(vo.getEndTime()));
            row.setDepositAmount(vo.getDepositAmount() == null ? "" : vo.getDepositAmount().toPlainString());
            row.setStatusName(vo.getStatusName());
            row.setDepositStatusName(vo.getDepositStatusName());
            row.setActualReturnTime(format(vo.getActualReturnTime()));
            row.setDamageResultName(vo.getDamageResultName());
            row.setOverdueFee(vo.getOverdueFee() == null ? "" : vo.getOverdueFee().toPlainString());
            row.setDamageFee(vo.getDamageFee() == null ? "" : vo.getDamageFee().toPlainString());
            row.setRefundAmount(vo.getRefundAmount() == null ? "" : vo.getRefundAmount().toPlainString());
            return row;
        }).toList();
    }

    public void markOverdue() {
        List<Rental> list = rentalMapper.selectList(new LambdaQueryWrapper<Rental>()
                .eq(Rental::getStatus, RentalStatus.PICKED_UP.code())
                .lt(Rental::getEndTime, LocalDateTime.now()));
        for (Rental rental : list) {
            RentalStateMachine.assertTransition(RentalStatus.PICKED_UP, RentalStatus.OVERDUE);
            if (rentalMapper.markOverdue(rental.getId()) == 0) {
                continue;
            }
            messageService.notifyUserOnce(rental.getUserId(), "OVERDUE", "租赁已逾期",
                    "预约单 " + rental.getOrderNo() + " 已逾期，逾期费按每小时 5 元计算，最高不超过押金。",
                    "OVERDUE:" + rental.getId());
        }
    }

    /** 待支付超时：原子取消并释放档期。由 ReminderService 每 2 分钟调度一次。 */
    public void expirePending() {
        List<Rental> list = rentalMapper.selectExpiredPending(pendingPaymentTimeoutMinutes);
        for (Rental rental : list) {
            if (rentalMapper.expirePending(rental.getId()) == 0) {
                continue;
            }
            reminderService.cancel(rental.getId());
            messageService.notifyUserOnce(rental.getUserId(), "SYSTEM", "预约已取消",
                    "预约单 " + rental.getOrderNo() + " 超过支付时限，已自动取消并释放档期。",
                    "PENDING_EXPIRE:" + rental.getId());
        }
    }

    private RentalStatus statusOf(Rental rental) {
        return RentalStatus.fromCode(rental.getStatus())
                .orElseThrow(() -> new BizException(ResultCode.CONFLICT, "无效的租赁状态"));
    }

    private User currentUser() {
        User user = userMapper.selectById(StpKit.USER.getLoginIdAsLong());
        if (user == null) {
            throw new BizException(ResultCode.UNAUTHORIZED, "用户不存在");
        }
        return user;
    }

    private RentalVO toVO(Rental rental) {
        RentalVO vo = rentalVoMapper.toVO(rental);
        Tool tool = toolMapper.selectById(rental.getToolId());
        vo.setToolImageUrl(tool == null ? null : tool.getImageUrl());
        vo.setRemindBeforeMinutes(reminderService.leadOf(rental.getId()));
        vo.setRemindAt(reminderService.remindAtOf(rental.getId()));
        return vo;
    }

    private String format(LocalDateTime time) {
        return time == null ? "" : time.format(DATE_TIME);
    }
}
