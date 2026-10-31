package com.cdwater.toolshare.module.rental;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.module.message.MessageService;
import com.cdwater.toolshare.module.tool.ToolService;
import com.cdwater.toolshare.shared.security.IdCardCipher;
import com.cdwater.toolshare.shared.sequence.SequenceService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cdwater.toolshare.shared.web.BizException;
import com.cdwater.toolshare.shared.web.Dict;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RentalService {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final List<String> ACTIVE_STATUS =
            List.of("PENDING_PAYMENT", "RESERVED", "PICKED_UP", "OVERDUE");
    private static final BigDecimal HOUR_FEE = new BigDecimal("5");
    private static final BigDecimal SEVERE_DAMAGE_FEE = new BigDecimal("50");

    private final RentalMapper rentalMapper;
    private final ToolMapper toolMapper;
    private final UserMapper userMapper;
    private final ToolService toolService;
    private final SequenceService sequenceService;
    private final MessageService messageService;

    @Transactional
    public RentalVO create(RentalCreateDTO dto) {
        User user = currentUser();
        if (!StringUtils.hasText(user.getRealName()) || !StringUtils.hasText(user.getIdCard())) {
            throw new BizException(ResultCode.BAD_REQUEST, "请先在个人中心完善真实姓名与身份证号");
        }
        Tool tool = toolService.getTool(dto.getToolId());
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
        List<Rental> conflicts = rentalMapper.selectList(new LambdaQueryWrapper<Rental>()
                .eq(Rental::getToolId, tool.getId())
                .in(Rental::getStatus, ACTIVE_STATUS)
                .lt(Rental::getStartTime, dto.getEndTime())
                .gt(Rental::getEndTime, dto.getStartTime()));
        if (!conflicts.isEmpty()) {
            String suggestion = conflicts.stream()
                    .map(item -> item.getEndTime().plusHours(1).format(DATE_TIME))
                    .findFirst().orElse("其他时间");
            throw new BizException(ResultCode.BAD_REQUEST, "该时段已被预约，建议 " + suggestion + " 之后");
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
        rental.setDepositStatus("UNPAID");
        rental.setStatus("PENDING_PAYMENT");
        rental.setOverdueFee(BigDecimal.ZERO);
        rental.setDamageFee(BigDecimal.ZERO);
        rental.setRefundAmount(BigDecimal.ZERO);
        rentalMapper.insert(rental);
        messageService.notifyUser(user.getId(), "RESERVATION", "预约已创建",
                "预约单 " + rental.getOrderNo() + " 已创建，请在 30 分钟内完成押金支付。");
        return toVO(rental);
    }

    @Transactional
    public void pay(Long id) {
        Rental rental = getUserRental(id);
        if (!"PENDING_PAYMENT".equals(rental.getStatus())) {
            throw new BizException(ResultCode.BAD_REQUEST, "当前状态不可支付");
        }
        rental.setDepositStatus("PAID");
        rental.setStatus("RESERVED");
        rentalMapper.updateById(rental);
        messageService.notifyUser(rental.getUserId(), "RESERVATION", "预约成功",
                "预约单 " + rental.getOrderNo() + " 押金支付成功，请按预约时间取件。");
    }

    @Transactional
    public void cancelByUser(Long id, String reason) {
        Rental rental = getUserRental(id);
        if (!"PENDING_PAYMENT".equals(rental.getStatus()) && !"RESERVED".equals(rental.getStatus())) {
            throw new BizException(ResultCode.BAD_REQUEST, "当前状态不可取消");
        }
        if (LocalDateTime.now().isAfter(rental.getStartTime().minusHours(24))) {
            throw new BizException(ResultCode.BAD_REQUEST, "取件前 24 小时内不可取消，请联系社区工作人员");
        }
        doCancel(rental, reason, "用户取消");
    }

    @Transactional
    public void cancelByAdmin(Long id, String reason) {
        Rental rental = getRental(id);
        if (!"PENDING_PAYMENT".equals(rental.getStatus()) && !"RESERVED".equals(rental.getStatus())) {
            throw new BizException(ResultCode.BAD_REQUEST, "当前状态不可取消");
        }
        doCancel(rental, reason, "管理员取消");
    }

    private void doCancel(Rental rental, String reason, String operator) {
        rental.setStatus("CANCELLED");
        rental.setCancelReason(StringUtils.hasText(reason) ? reason : operator);
        rental.setCancelTime(LocalDateTime.now());
        if ("PAID".equals(rental.getDepositStatus())) {
            rental.setDepositStatus("REFUNDED");
            rental.setRefundAmount(rental.getDepositAmount());
        }
        rentalMapper.updateById(rental);
        messageService.notifyUser(rental.getUserId(), "SYSTEM", "预约已取消",
                "预约单 " + rental.getOrderNo() + " 已取消，押金将在 1-3 个工作日内退还。");
    }

    @Transactional
    public void pickup(Long id) {
        Rental rental = getRental(id);
        if (!"RESERVED".equals(rental.getStatus())) {
            throw new BizException(ResultCode.BAD_REQUEST, "仅已预约的租赁可确认取件");
        }
        Tool tool = toolService.getTool(rental.getToolId());
        rental.setStatus("PICKED_UP");
        rentalMapper.updateById(rental);
        tool.setStatus("RENTED");
        tool.setRentalCount((tool.getRentalCount() == null ? 0 : tool.getRentalCount()) + 1);
        toolMapper.updateById(tool);
        toolService.log(tool.getId(), "STATUS_CHANGE", "取件后工具状态变为已租出");
        messageService.notifyUser(rental.getUserId(), "SYSTEM", "工具已取件",
                "预约单 " + rental.getOrderNo() + " 已确认取件，请按时归还。");
    }

    @Transactional
    public void returnRental(Long id, RentalReturnDTO dto) {
        if (!Dict.containsKey(Dict.DAMAGE_RESULT, dto.getDamageResult())) {
            throw new BizException(ResultCode.BAD_REQUEST, "无效的验收结果");
        }
        Rental rental = getRental(id);
        if (!"PICKED_UP".equals(rental.getStatus()) && !"OVERDUE".equals(rental.getStatus())) {
            throw new BizException(ResultCode.BAD_REQUEST, "当前状态不可归还");
        }
        LocalDateTime now = LocalDateTime.now();
        BigDecimal overdueFee = dto.getOverdueFee() != null
                ? dto.getOverdueFee() : calcOverdueFee(rental, now);
        overdueFee = overdueFee.max(BigDecimal.ZERO).min(rental.getDepositAmount());
        BigDecimal damageFee = dto.getDamageFee() != null
                ? dto.getDamageFee()
                : ("SEVERE".equals(dto.getDamageResult()) ? SEVERE_DAMAGE_FEE : BigDecimal.ZERO);
        damageFee = damageFee.max(BigDecimal.ZERO);
        BigDecimal refund = rental.getDepositAmount().subtract(overdueFee).subtract(damageFee)
                .max(BigDecimal.ZERO);

        rental.setActualReturnTime(now);
        rental.setOverdueFee(overdueFee);
        rental.setDamageFee(damageFee);
        rental.setRefundAmount(refund);
        rental.setDepositStatus("REFUNDED");
        rental.setDamageResult(dto.getDamageResult());
        rental.setReturnRemark(dto.getRemark());
        rental.setStatus("RETURNED");
        rentalMapper.updateById(rental);

        Tool tool = toolService.getTool(rental.getToolId());
        if ("SEVERE".equals(dto.getDamageResult())) {
            tool.setStatus("MAINTENANCE");
            tool.setDamageLevel("SEVERE");
            tool.setLastRepairTime(now);
            toolService.log(tool.getId(), "RETURN", "归还验收为严重损坏，工具转维修中");
        } else {
            tool.setStatus("AVAILABLE");
            if ("MINOR".equals(dto.getDamageResult())) {
                tool.setDamageLevel("LIGHT");
            }
            toolService.log(tool.getId(), "RETURN", "归还验收完成，工具恢复可用");
        }
        toolMapper.updateById(tool);
        messageService.notifyUser(rental.getUserId(), "SYSTEM", "归还验收完成",
                "预约单 " + rental.getOrderNo() + " 已归还，逾期费 " + overdueFee
                        + " 元，损坏赔偿 " + damageFee + " 元，退还押金 " + refund + " 元。");
    }

    private BigDecimal calcOverdueFee(Rental rental, LocalDateTime now) {
        if (!now.isAfter(rental.getEndTime())) {
            return BigDecimal.ZERO;
        }
        long minutes = Duration.between(rental.getEndTime(), now).toMinutes();
        long hours = (minutes + 59) / 60;
        return HOUR_FEE.multiply(BigDecimal.valueOf(Math.max(hours, 1)))
                .min(rental.getDepositAmount())
                .setScale(2, RoundingMode.HALF_UP);
    }

    public PageVO<RentalVO> userPage(String status, long page, long size) {
        LambdaQueryWrapper<Rental> wrapper = new LambdaQueryWrapper<Rental>()
                .eq(Rental::getUserId, StpKit.USER.getLoginIdAsLong());
        if (StringUtils.hasText(status)) {
            wrapper.eq(Rental::getStatus, status);
        }
        Page<Rental> result = rentalMapper.selectPage(new Page<>(page, clamp(size)),
                wrapper.orderByDesc(Rental::getCreateTime));
        return PageVO.of(result, this::toVO);
    }

    public PageVO<RentalVO> adminPage(String keyword, String status, String overdue,
                                      long page, long size) {
        Page<Rental> result = rentalMapper.selectPage(new Page<>(page, clamp(size)),
                adminWrapper(keyword, status, overdue).orderByDesc(Rental::getCreateTime));
        return PageVO.of(result, this::toVO);
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
                .eq(Rental::getStatus, "PICKED_UP")
                .lt(Rental::getEndTime, LocalDateTime.now()));
        for (Rental rental : list) {
            rental.setStatus("OVERDUE");
            rentalMapper.updateById(rental);
            messageService.notifyUserOnce(rental.getUserId(), "OVERDUE", "租赁已逾期",
                    "预约单 " + rental.getOrderNo() + " 已逾期，逾期费按每小时 5 元计算，最高不超过押金。",
                    "OVERDUE:" + rental.getId());
        }
    }

    private User currentUser() {
        User user = userMapper.selectById(StpKit.USER.getLoginIdAsLong());
        if (user == null) {
            throw new BizException(ResultCode.UNAUTHORIZED, "用户不存在");
        }
        return user;
    }

    private RentalVO toVO(Rental rental) {
        RentalVO vo = new RentalVO();
        vo.setId(rental.getId());
        vo.setOrderNo(rental.getOrderNo());
        vo.setToolId(rental.getToolId());
        vo.setToolCode(rental.getToolCode());
        vo.setToolName(rental.getToolName());
        vo.setUserId(rental.getUserId());
        vo.setRenterName(rental.getRenterName());
        vo.setRenterPhone(IdCardCipher.maskPhone(rental.getRenterPhone()));
        vo.setStartTime(rental.getStartTime());
        vo.setEndTime(rental.getEndTime());
        vo.setPickupPerson(rental.getPickupPerson());
        vo.setDepositAmount(rental.getDepositAmount());
        vo.setDepositStatus(rental.getDepositStatus());
        vo.setDepositStatusName(Dict.label(Dict.DEPOSIT_STATUS, rental.getDepositStatus()));
        vo.setStatus(rental.getStatus());
        vo.setStatusName(Dict.label(Dict.RENTAL_STATUS, rental.getStatus()));
        vo.setActualReturnTime(rental.getActualReturnTime());
        vo.setOverdueFee(rental.getOverdueFee());
        vo.setDamageFee(rental.getDamageFee());
        vo.setRefundAmount(rental.getRefundAmount());
        vo.setDamageResult(rental.getDamageResult());
        vo.setDamageResultName(Dict.label(Dict.DAMAGE_RESULT, rental.getDamageResult()));
        vo.setReturnRemark(rental.getReturnRemark());
        vo.setCancelReason(rental.getCancelReason());
        vo.setCancelTime(rental.getCancelTime());
        vo.setCreateTime(rental.getCreateTime());
        vo.setUpdateTime(rental.getUpdateTime());
        return vo;
    }

    private String format(LocalDateTime time) {
        return time == null ? "" : time.format(DATE_TIME);
    }

    private long clamp(long size) {
        return Math.min(Math.max(size, 1), 100);
    }
}
