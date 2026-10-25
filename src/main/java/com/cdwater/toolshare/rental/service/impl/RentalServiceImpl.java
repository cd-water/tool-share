package com.cdwater.toolshare.rental.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cdwater.toolshare.common.constant.RoleConstant;
import com.cdwater.toolshare.common.enums.ResultCode;
import com.cdwater.toolshare.common.exception.BizException;
import com.cdwater.toolshare.common.result.PageResult;
import com.cdwater.toolshare.common.util.DesensitizeUtil;
import com.cdwater.toolshare.common.util.SerialNoGenerator;
import com.cdwater.toolshare.config.NotifyMessage;
import com.cdwater.toolshare.config.RabbitMqConfig;
import com.cdwater.toolshare.rental.dto.RentalCreateDTO;
import com.cdwater.toolshare.rental.dto.RentalPickupDTO;
import com.cdwater.toolshare.rental.dto.RentalQueryDTO;
import com.cdwater.toolshare.rental.dto.RentalReturnDTO;
import com.cdwater.toolshare.rental.entity.RentalOrder;
import com.cdwater.toolshare.rental.mapper.RentalOrderMapper;
import com.cdwater.toolshare.rental.service.RentalService;
import com.cdwater.toolshare.rental.vo.RentalCreateVO;
import com.cdwater.toolshare.rental.vo.RentalExportVO;
import com.cdwater.toolshare.rental.vo.RentalOrderVO;
import com.cdwater.toolshare.rental.vo.TimeSlotVO;
import com.cdwater.toolshare.tool.dto.ToolStatusDTO;
import com.cdwater.toolshare.tool.entity.Tool;
import com.cdwater.toolshare.tool.service.ToolService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 租赁业务实现：预约（Redisson 锁 + 时段冲突校验）、押金、取件、归还、记录与导出。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RentalServiceImpl implements RentalService {

    /** 状态：1待支付 2已预约 3已取件 4已归还 5已取消 */
    private static final int PENDING_PAY = 1;
    private static final int RESERVED = 2;
    private static final int PICKED_UP = 3;
    private static final int RETURNED = 4;
    private static final int CANCELED = 5;

    /** 押金状态：1未缴 2已缴 3已退还 */
    private static final int DEPOSIT_UNPAID = 1;
    private static final int DEPOSIT_PAID = 2;
    private static final int DEPOSIT_REFUNDED = 3;

    /** 工具状态：1可用 2已借出 3维修中 */
    private static final int TOOL_AVAILABLE = 1;
    private static final int TOOL_BORROWED = 2;
    private static final int TOOL_REPAIRING = 3;

    /** 工具损坏程度：1全新 2轻微磨损 3中度磨损 4严重损坏 */
    private static final int DAMAGE_NEW = 1;
    private static final int DAMAGE_SLIGHT = 2;
    private static final int DAMAGE_SEVERE = 4;

    /** 提醒类型：1取件 2到期 3逾期 */
    private static final int NOTIFY_PICKUP = 1;
    private static final int NOTIFY_EXPIRE = 2;
    private static final int NOTIFY_OVERDUE = 3;

    /** 预约时段：按 1 小时切分，服务时间 09:00 - 18:00 */
    private static final LocalTime SERVICE_OPEN = LocalTime.of(9, 0);
    private static final LocalTime SERVICE_CLOSE = LocalTime.of(18, 0);

    private static final String LOCK_PREFIX = "lock:rental:tool:";

    private static final DateTimeFormatter MSG_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private static final DateTimeFormatter EXPORT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RentalOrderMapper rentalOrderMapper;
    private final ToolService toolService;
    private final RedissonClient redissonClient;
    private final SerialNoGenerator serialNoGenerator;
    private final RabbitTemplate rabbitTemplate;

    @Override
    public List<TimeSlotVO> availableTimes(Long toolId, LocalDate date) {
        Tool tool = getToolOrThrow(toolId);
        if (!Integer.valueOf(TOOL_AVAILABLE).equals(tool.getStatus())) {
            return List.of();
        }

        LocalDateTime dayStart = date.atTime(SERVICE_OPEN);
        LocalDateTime dayEnd = date.atTime(SERVICE_CLOSE);
        List<RentalOrder> occupied = rentalOrderMapper.selectList(new LambdaQueryWrapper<RentalOrder>()
                .eq(RentalOrder::getToolId, toolId)
                .in(RentalOrder::getStatus, RESERVED, PICKED_UP)
                .lt(RentalOrder::getStartTime, dayEnd)
                .gt(RentalOrder::getEndTime, dayStart));

        LocalDateTime now = LocalDateTime.now();
        List<TimeSlotVO> slots = new ArrayList<>();
        for (LocalDateTime cursor = dayStart; cursor.isBefore(dayEnd); cursor = cursor.plusHours(1)) {
            final LocalDateTime start = cursor;
            final LocalDateTime end = cursor.plusHours(1);
            // 已过去的时段不再开放
            if (start.isBefore(now)) {
                continue;
            }
            boolean conflict = occupied.stream()
                    .anyMatch(order -> overlaps(order.getStartTime(), order.getEndTime(), start, end));
            if (!conflict) {
                TimeSlotVO slot = new TimeSlotVO();
                slot.setStartTime(start);
                slot.setEndTime(end);
                slots.add(slot);
            }
        }
        return slots;
    }

    @Override
    public RentalCreateVO create(RentalCreateDTO dto) {
        validateCreateTimes(dto);
        Tool tool = getToolOrThrow(dto.getToolId());
        if (!Integer.valueOf(TOOL_AVAILABLE).equals(tool.getStatus())) {
            throw new BizException(ResultCode.CONFLICT.getCode(), "工具当前不可预约");
        }

        Long userId = StpUtil.getLoginIdAsLong();
        RLock lock = redissonClient.getLock(LOCK_PREFIX + dto.getToolId());
        boolean locked = false;
        try {
            locked = lock.tryLock(3, TimeUnit.SECONDS);
            if (!locked) {
                throw new BizException(ResultCode.CONFLICT.getCode(), "预约繁忙，请稍后重试");
            }

            Long conflict = rentalOrderMapper.selectCount(new LambdaQueryWrapper<RentalOrder>()
                    .eq(RentalOrder::getToolId, dto.getToolId())
                    .in(RentalOrder::getStatus, RESERVED, PICKED_UP)
                    .lt(RentalOrder::getStartTime, dto.getEndTime())
                    .gt(RentalOrder::getEndTime, dto.getStartTime()));
            if (conflict != null && conflict > 0) {
                throw conflictException(dto);
            }

            RentalOrder order = new RentalOrder();
            order.setOrderNo(serialNoGenerator.next("SQ-ZY-"));
            order.setToolId(dto.getToolId());
            order.setUserId(userId);
            order.setPickupName(dto.getPickupName());
            order.setStartTime(dto.getStartTime());
            order.setEndTime(dto.getEndTime());
            order.setDepositAmount(tool.getDeposit());
            order.setDepositStatus(DEPOSIT_UNPAID);
            order.setStatus(PENDING_PAY);
            rentalOrderMapper.insert(order);

            // 取件时间前 2h 提醒（已过时间则立即投递）
            publishNotify(userId, NOTIFY_PICKUP, order.getOrderNo(),
                    "您预约的%s（%s）将于 %s 可取件，请携带身份证或预约单号。".formatted(
                            tool.getName(), tool.getToolCode(), order.getStartTime().format(MSG_TIME)),
                    delayMillis(order.getStartTime().minusHours(2)));

            RentalCreateVO vo = new RentalCreateVO();
            vo.setId(order.getId());
            vo.setOrderNo(order.getOrderNo());
            return vo;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ResultCode.INTERNAL_SERVER_ERROR.getCode(), "预约失败，请重试");
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    @Transactional
    public void pay(Long id) {
        RentalOrder order = getOwnedOrder(id);
        if (!Integer.valueOf(PENDING_PAY).equals(order.getStatus())) {
            throw new BizException(ResultCode.CONFLICT.getCode(), "当前状态不可缴纳押金");
        }

        RentalOrder update = new RentalOrder();
        update.setId(id);
        update.setDepositStatus(DEPOSIT_PAID);
        update.setStatus(RESERVED);
        rentalOrderMapper.updateById(update);

        Tool tool = getToolOrThrow(order.getToolId());
        publishNotify(order.getUserId(), NOTIFY_EXPIRE, order.getOrderNo(),
                "您租赁的%s（%s）将于 %s 到期，请及时归还。".formatted(
                        tool.getName(), tool.getToolCode(), order.getEndTime().format(MSG_TIME)),
                delayMillis(order.getEndTime().minusHours(2)));
    }

    @Override
    @Transactional
    public void cancel(Long id) {
        RentalOrder order = getOwnedOrder(id);
        int status = order.getStatus() == null ? 0 : order.getStatus();
        if (status != PENDING_PAY && status != RESERVED) {
            throw new BizException(ResultCode.CONFLICT.getCode(), "当前状态不可取消");
        }
        // 取件前 24 小时内不可取消（对待支付、已预约统一生效）
        if (order.getStartTime().isBefore(LocalDateTime.now().plusHours(24))) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(), "取件前 24 小时内不可取消预约");
        }

        RentalOrder update = new RentalOrder();
        update.setId(id);
        update.setStatus(CANCELED);
        update.setCancelTime(LocalDateTime.now());
        if (Integer.valueOf(DEPOSIT_PAID).equals(order.getDepositStatus())) {
            update.setDepositStatus(DEPOSIT_REFUNDED);
            update.setDepositRefundAmount(order.getDepositAmount());
        } else {
            update.setDepositRefundAmount(BigDecimal.ZERO);
        }
        rentalOrderMapper.updateById(update);
    }

    @Override
    @Transactional
    public void pickup(Long id, RentalPickupDTO dto) {
        RentalOrder order = getOrderOrThrow(id);
        if (!Integer.valueOf(RESERVED).equals(order.getStatus())) {
            throw new BizException(ResultCode.CONFLICT.getCode(), "当前状态不可取件");
        }

        Tool tool = getToolOrThrow(order.getToolId());
        if (!Integer.valueOf(TOOL_AVAILABLE).equals(tool.getStatus())) {
            throw new BizException(ResultCode.CONFLICT.getCode(), "工具当前不可借出");
        }

        RentalOrder update = new RentalOrder();
        update.setId(id);
        update.setStatus(PICKED_UP);
        if (dto != null && StrUtil.isNotBlank(dto.getPickupName())) {
            update.setPickupName(dto.getPickupName());
        }
        rentalOrderMapper.updateById(update);

        updateToolStatus(order.getToolId(), TOOL_BORROWED,
                "租赁单 %s 取件".formatted(order.getOrderNo()));
    }

    @Override
    @Transactional
    public void returnTool(Long id, RentalReturnDTO dto) {
        RentalOrder order = getOrderOrThrow(id);
        if (!Integer.valueOf(PICKED_UP).equals(order.getStatus())) {
            throw new BizException(ResultCode.CONFLICT.getCode(), "当前状态不可归还");
        }

        BigDecimal deposit = order.getDepositAmount() == null ? BigDecimal.ZERO : order.getDepositAmount();
        // 逾期费封顶押金
        BigDecimal overdueFee = dto.getOverdueFee().min(deposit);
        BigDecimal refund = dto.getDepositRefundAmount() == null
                ? deposit.subtract(overdueFee) : dto.getDepositRefundAmount();
        refund = refund.max(BigDecimal.ZERO).min(deposit);

        RentalOrder update = new RentalOrder();
        update.setId(id);
        update.setStatus(RETURNED);
        update.setReturnTime(LocalDateTime.now());
        update.setAcceptResult(dto.getAcceptResult());
        update.setOverdueFee(overdueFee);
        update.setDepositRefundAmount(refund);
        update.setDepositStatus(DEPOSIT_REFUNDED);
        update.setUpdateBy(StpUtil.getLoginIdAsLong());
        rentalOrderMapper.updateById(update);

        // 联动工具：重估损坏程度，按验收结果回状态
        int acceptResult = dto.getAcceptResult();
        int damageLevel = switch (acceptResult) {
            case 1 -> DAMAGE_NEW;
            case 2 -> DAMAGE_SLIGHT;
            default -> DAMAGE_SEVERE;
        };
        int toolStatus = acceptResult == 3 ? TOOL_REPAIRING : TOOL_AVAILABLE;
        toolService.updateDamageLevel(order.getToolId(), damageLevel);
        updateToolStatus(order.getToolId(), toolStatus,
                "租赁单 %s 归还验收（%s），%s".formatted(
                        order.getOrderNo(), acceptResultText(acceptResult),
                        toolStatus == TOOL_REPAIRING ? "转维修" : "重新上架"));

        if (overdueFee.compareTo(BigDecimal.ZERO) > 0) {
            Tool tool = getToolOrThrow(order.getToolId());
            publishNotify(order.getUserId(), NOTIFY_OVERDUE, order.getOrderNo(),
                    "您租赁的%s（%s）已逾期，逾期费用 %s 元，将从押金中扣除。".formatted(
                            tool.getName(), tool.getToolCode(), overdueFee.setScale(2).toPlainString()),
                    0);
        }
    }

    @Override
    public PageResult<RentalOrderVO> page(RentalQueryDTO query) {
        Page<RentalOrderVO> page = query.toPage();
        rentalOrderMapper.selectVoPage(page, query, currentResidentId());
        page.getRecords().forEach(this::maskPhone);
        return PageResult.of(page);
    }

    @Override
    public RentalOrderVO detail(Long id) {
        RentalOrderVO vo = rentalOrderMapper.selectVoById(id);
        if (vo == null) {
            throw new BizException(ResultCode.NOT_FOUND.getCode(), "租赁单不存在");
        }
        if (isResident() && (vo.getUserId() == null || StpUtil.getLoginIdAsLong() != vo.getUserId())) {
            throw new BizException(ResultCode.FORBIDDEN.getCode(), "无权查看该租赁单");
        }
        maskPhone(vo);
        return vo;
    }

    @Override
    public void export(RentalQueryDTO query, HttpServletResponse response) {
        if (isResident()) {
            throw new BizException(ResultCode.FORBIDDEN.getCode(), "无权导出租赁记录");
        }
        List<RentalOrderVO> orders = rentalOrderMapper.selectVoList(query, null);
        orders.forEach(this::maskPhone);
        List<RentalExportVO> rows = orders.stream().map(this::toExportRow).toList();

        response.setContentType("application/vnd.ms-excel");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String fileName = URLEncoder.encode("租赁记录.xlsx", StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition", "attachment;filename=" + fileName);

        try {
            EasyExcel.write(response.getOutputStream(), RentalExportVO.class)
                    .sheet("租赁记录")
                    .doWrite(rows);
        } catch (IOException e) {
            log.error("导出租赁记录失败", e);
            throw new BizException(ResultCode.INTERNAL_SERVER_ERROR.getCode(), "导出失败");
        }
    }

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

    private void validateCreateTimes(RentalCreateDTO dto) {
        if (!dto.getStartTime().isBefore(dto.getEndTime())) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(), "租赁开始时间必须早于结束时间");
        }
        if (dto.getStartTime().isBefore(LocalDateTime.now())) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(), "租赁开始时间不能早于当前时间");
        }
    }

    /**
     * 时段冲突提示：附带当天最近的可约时段
     */
    private BizException conflictException(RentalCreateDTO dto) {
        String hint = availableTimes(dto.getToolId(), dto.getStartTime().toLocalDate()).stream()
                .map(TimeSlotVO::getStartTime)
                .filter(start -> !start.isBefore(dto.getStartTime()))
                .findFirst()
                .map(start -> "，最近可约时段：" + start.format(MSG_TIME))
                .orElse("，请选择其他时段");
        return new BizException(ResultCode.CONFLICT.getCode(), "该时段已被预约" + hint);
    }

    private boolean overlaps(LocalDateTime aStart, LocalDateTime aEnd,
                             LocalDateTime bStart, LocalDateTime bEnd) {
        return aStart.isBefore(bEnd) && bStart.isBefore(aEnd);
    }

    private RentalOrder getOrderOrThrow(Long id) {
        RentalOrder order = rentalOrderMapper.selectById(id);
        if (order == null) {
            throw new BizException(ResultCode.NOT_FOUND.getCode(), "租赁单不存在");
        }
        return order;
    }

    private RentalOrder getOwnedOrder(Long id) {
        RentalOrder order = getOrderOrThrow(id);
        if (order.getUserId() == null || StpUtil.getLoginIdAsLong() != order.getUserId()) {
            throw new BizException(ResultCode.FORBIDDEN.getCode(), "无权操作该租赁单");
        }
        return order;
    }

    private Tool getToolOrThrow(Long toolId) {
        try {
            return toolService.detail(toolId);
        } catch (BizException e) {
            if (ResultCode.NOT_FOUND.getCode().equals(e.getCode())) {
                throw new BizException(ResultCode.NOT_FOUND.getCode(), "工具不存在");
            }
            throw e;
        }
    }

    private void updateToolStatus(Long toolId, int status, String remark) {
        ToolStatusDTO dto = new ToolStatusDTO();
        dto.setStatus(status);
        dto.setRemark(remark);
        toolService.updateStatus(toolId, dto);
    }

    /**
     * 居民只返回本人 id；工作人员返回 null 表示全量。
     */
    private Long currentResidentId() {
        return isResident() ? StpUtil.getLoginIdAsLong() : null;
    }

    private boolean isResident() {
        return !StpUtil.hasRole(RoleConstant.STAFF);
    }

    private void maskPhone(RentalOrderVO vo) {
        vo.setUserPhone(DesensitizeUtil.maskPhone(vo.getUserPhone()));
    }

    private long delayMillis(LocalDateTime target) {
        return Math.max(0, Duration.between(LocalDateTime.now(), target).toMillis());
    }

    /**
     * 投递延迟提醒；MQ 故障不影响主业务。
     */
    private void publishNotify(Long userId, int type, String bizNo, String content, long delayMs) {
        try {
            NotifyMessage message = new NotifyMessage(userId, type, bizNo, content);
            rabbitTemplate.convertAndSend(RabbitMqConfig.NOTIFY_DELAY_EXCHANGE,
                    RabbitMqConfig.NOTIFY_DELAY_ROUTING_KEY, message,
                    msg -> {
                        msg.getMessageProperties().setDelayLong(delayMs);
                        return msg;
                    });
        } catch (Exception e) {
            log.warn("投递提醒消息失败，bizNo={}, type={}", bizNo, type, e);
        }
    }

    private RentalExportVO toExportRow(RentalOrderVO order) {
        RentalExportVO row = new RentalExportVO();
        row.setOrderNo(order.getOrderNo());
        row.setToolCode(order.getToolCode());
        row.setToolName(order.getToolName());
        row.setUserName(order.getUserName());
        row.setUserPhone(order.getUserPhone());
        row.setPickupName(order.getPickupName());
        row.setStartTime(formatTime(order.getStartTime()));
        row.setEndTime(formatTime(order.getEndTime()));
        row.setDepositAmount(order.getDepositAmount());
        row.setDepositStatusText(depositStatusText(order.getDepositStatus()));
        row.setStatusText(statusText(order.getStatus()));
        row.setAcceptResultText(order.getAcceptResult() == null ? "" : acceptResultText(order.getAcceptResult()));
        row.setOverdueFee(order.getOverdueFee());
        row.setDepositRefundAmount(order.getDepositRefundAmount());
        row.setCreateTime(formatTime(order.getCreateTime()));
        return row;
    }

    private String formatTime(LocalDateTime time) {
        return time == null ? "" : time.format(EXPORT_TIME);
    }

    private String statusText(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case PENDING_PAY -> "待支付";
            case RESERVED -> "已预约";
            case PICKED_UP -> "已取件";
            case RETURNED -> "已归还";
            case CANCELED -> "已取消";
            default -> String.valueOf(status);
        };
    }

    private String depositStatusText(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case DEPOSIT_UNPAID -> "未缴";
            case DEPOSIT_PAID -> "已缴";
            case DEPOSIT_REFUNDED -> "已退还";
            default -> String.valueOf(status);
        };
    }

    private String acceptResultText(Integer acceptResult) {
        return switch (acceptResult) {
            case 1 -> "完好";
            case 2 -> "轻微损坏";
            case 3 -> "严重损坏";
            default -> String.valueOf(acceptResult);
        };
    }
}
