package com.cdwater.toolshare.repair.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cdwater.toolshare.common.constant.RoleConstant;
import com.cdwater.toolshare.common.enums.ResultCode;
import com.cdwater.toolshare.common.exception.BizException;
import com.cdwater.toolshare.common.result.PageResult;
import com.cdwater.toolshare.common.util.DesensitizeUtil;
import com.cdwater.toolshare.common.util.SerialNoGenerator;
import com.cdwater.toolshare.notify.service.NotifyService;
import com.cdwater.toolshare.repair.dto.RepairAcceptDTO;
import com.cdwater.toolshare.repair.dto.RepairCreateDTO;
import com.cdwater.toolshare.repair.dto.RepairProgressDTO;
import com.cdwater.toolshare.repair.dto.RepairQueryDTO;
import com.cdwater.toolshare.repair.dto.RepairVerifyDTO;
import com.cdwater.toolshare.repair.entity.RepairOrder;
import com.cdwater.toolshare.repair.entity.RepairProgress;
import com.cdwater.toolshare.repair.mapper.RepairOrderMapper;
import com.cdwater.toolshare.repair.mapper.RepairProgressMapper;
import com.cdwater.toolshare.repair.service.RepairService;
import com.cdwater.toolshare.repair.vo.RepairCreateVO;
import com.cdwater.toolshare.repair.vo.RepairOrderVO;
import com.cdwater.toolshare.rental.service.RentalService;
import com.cdwater.toolshare.rental.vo.RentalOrderVO;
import com.cdwater.toolshare.tool.dto.ToolStatusDTO;
import com.cdwater.toolshare.tool.service.ToolService;
import com.cdwater.toolshare.user.entity.User;
import com.cdwater.toolshare.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 损坏报修业务实现
 */
@Service
@RequiredArgsConstructor
public class RepairServiceImpl implements RepairService {

    private static final int VERIFY_CONFIRMED = 1;
    private static final int TOOL_STATUS_REPAIRING = 3;
    private static final int REPAIR_STATUS_WAITING_PARTS = 1;
    private static final int REPAIR_STATUS_REPAIRING = 2;
    private static final int REPAIR_STATUS_COMPLETED = 3;
    private static final int TOOL_STATUS_AVAILABLE = 1;
    private static final int TOOL_STATUS_SCRAPPED = 4;
    private static final int MESSAGE_TYPE_REPAIR_COMPLETED = 4;

    private final RepairOrderMapper repairOrderMapper;
    private final RepairProgressMapper repairProgressMapper;
    private final SerialNoGenerator serialNoGenerator;
    private final RentalService rentalService;
    private final ToolService toolService;
    private final NotifyService notifyService;
    private final UserMapper userMapper;

    @Override
    @Transactional
    public RepairCreateVO create(RepairCreateDTO dto) {
        Long userId = StpUtil.getLoginIdAsLong();
        RentalOrderVO rental = rentalService.detail(dto.getRentalOrderId());
        if (rental == null) {
            throw new BizException(ResultCode.NOT_FOUND.getCode(), "关联租赁单不存在");
        }
        if (!Objects.equals(userId, rental.getUserId())) {
            throw new BizException(ResultCode.FORBIDDEN.getCode(), "只能为本人租赁单提交报修");
        }
        if (!Objects.equals(dto.getToolId(), rental.getToolId())) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(), "工具与关联租赁单不一致");
        }

        RepairOrder order = new RepairOrder();
        order.setRepairNo(serialNoGenerator.next("SQ-BX-"));
        order.setRentalOrderId(dto.getRentalOrderId());
        order.setToolId(dto.getToolId());
        order.setReporterId(userId);
        order.setDamagePart(dto.getDamagePart());
        order.setDamageDesc(dto.getDamageDesc());
        order.setImages(dto.getImages());
        order.setRepairFee(BigDecimal.ZERO);
        repairOrderMapper.insert(order);

        RepairCreateVO vo = new RepairCreateVO();
        vo.setId(order.getId());
        vo.setRepairNo(order.getRepairNo());
        return vo;
    }

    @Override
    public PageResult<RepairOrderVO> page(RepairQueryDTO query) {
        Long reporterId = isStaff() ? null : StpUtil.getLoginIdAsLong();
        Page<RepairOrder> page = query.toPage();
        IPage<RepairOrder> result = repairOrderMapper.selectRepairPage(
                page,
                StringUtils.hasText(query.getToolCode()) ? query.getToolCode() : null,
                query.getRepairStatus(),
                reporterId);
        return PageResult.of(result.convert(order -> toVO(order, false)));
    }

    @Override
    public RepairOrderVO detail(Long id) {
        RepairOrder order = getOrder(id);
        if (!isStaff() && !Objects.equals(StpUtil.getLoginIdAsLong(), order.getReporterId())) {
            throw new BizException(ResultCode.FORBIDDEN.getCode(), "无权查看该报修单");
        }
        return toVO(order, true);
    }

    @Override
    @Transactional
    public void verify(Long id, RepairVerifyDTO dto) {
        RepairOrder order = getOrder(id);
        LambdaUpdateWrapper<RepairOrder> update = new LambdaUpdateWrapper<RepairOrder>()
                .eq(RepairOrder::getId, id)
                .set(RepairOrder::getVerifyBy, StpUtil.getLoginIdAsLong())
                .set(RepairOrder::getVerifyTime, LocalDateTime.now())
                .set(RepairOrder::getVerifyResult, dto.getVerifyResult());
        // 确认损坏即进入维修流程初始状态（待配件），否则单据无「更新进度」入口
        if (dto.getVerifyResult() == VERIFY_CONFIRMED) {
            update.set(RepairOrder::getRepairStatus, REPAIR_STATUS_WAITING_PARTS);
        }
        repairOrderMapper.update(null, update);

        if (dto.getVerifyResult() == VERIFY_CONFIRMED) {
            updateToolStatus(order.getToolId(), TOOL_STATUS_REPAIRING,
                    "报修单 " + order.getRepairNo() + " 核验确认损坏，转维修");
        }
    }

    @Override
    @Transactional
    public void progress(Long id, RepairProgressDTO dto) {
        RepairOrder order = getOrder(id);

        RepairProgress progress = new RepairProgress();
        progress.setRepairId(id);
        progress.setStatus(dto.getStatus());
        progress.setDescription(dto.getDescription());
        progress.setOperatorId(StpUtil.getLoginIdAsLong());
        repairProgressMapper.insert(progress);

        LambdaUpdateWrapper<RepairOrder> update = new LambdaUpdateWrapper<RepairOrder>()
                .eq(RepairOrder::getId, id)
                .set(RepairOrder::getRepairStatus, dto.getStatus());
        if (dto.getStatus() == REPAIR_STATUS_REPAIRING && order.getRepairStartTime() == null) {
            update.set(RepairOrder::getRepairStartTime, LocalDateTime.now());
        }
        repairOrderMapper.update(null, update);

        if (dto.getStatus() == REPAIR_STATUS_COMPLETED) {
            sendRepairCompletedMessage(order, "维修已完成，等待工作人员验收。");
        }
    }

    @Override
    @Transactional
    public void accept(Long id, RepairAcceptDTO dto) {
        RepairOrder order = getOrder(id);

        repairOrderMapper.update(null, new LambdaUpdateWrapper<RepairOrder>()
                .eq(RepairOrder::getId, id)
                .set(RepairOrder::getAcceptBy, StpUtil.getLoginIdAsLong())
                .set(RepairOrder::getAcceptTime, LocalDateTime.now())
                .set(RepairOrder::getAcceptResult, dto.getAcceptResult())
                .set(RepairOrder::getRemark, dto.getRemark()));

        updateToolStatus(order.getToolId(), dto.getToolStatus(),
                "报修单 " + order.getRepairNo() + " 维修验收");

        String content = switch (dto.getToolStatus()) {
            case TOOL_STATUS_AVAILABLE -> "已维修验收合格并重新上架，可正常预约。";
            case TOOL_STATUS_REPAIRING -> "验收后仍需继续维修。";
            case TOOL_STATUS_SCRAPPED -> "无法修复，已做报废处理。";
            default -> "维修验收已完成。";
        };
        sendRepairCompletedMessage(order, content);
    }

    private RepairOrder getOrder(Long id) {
        RepairOrder order = repairOrderMapper.selectById(id);
        if (order == null) {
            throw new BizException(ResultCode.NOT_FOUND.getCode(), "报修单不存在");
        }
        return order;
    }

    private boolean isStaff() {
        return StpUtil.hasRole(RoleConstant.STAFF) || StpUtil.hasRole(RoleConstant.ADMIN);
    }

    private void updateToolStatus(Long toolId, Integer status, String remark) {
        ToolStatusDTO dto = new ToolStatusDTO();
        dto.setStatus(status);
        dto.setRemark(remark);
        toolService.updateStatus(toolId, dto);
    }

    private void sendRepairCompletedMessage(RepairOrder order, String content) {
        notifyService.sendInAppMessage(order.getReporterId(), MESSAGE_TYPE_REPAIR_COMPLETED,
                "维修完成提醒", "您报修的" + toolLabel(order) + content, order.getRepairNo());
    }

    private String toolLabel(RepairOrder order) {
        RentalOrderVO rental = rentalService.detail(order.getRentalOrderId());
        if (rental != null && StringUtils.hasText(rental.getToolName())) {
            return rental.getToolName() + "（" + rental.getToolCode() + "）";
        }
        return "工具";
    }

    private RepairOrderVO toVO(RepairOrder order, boolean withProgress) {
        RepairOrderVO vo = new RepairOrderVO();
        BeanUtils.copyProperties(order, vo);

        RentalOrderVO rental = rentalService.detail(order.getRentalOrderId());
        if (rental != null) {
            vo.setOrderNo(rental.getOrderNo());
            vo.setToolCode(rental.getToolCode());
            vo.setToolName(rental.getToolName());
        }

        User reporter = userMapper.selectById(order.getReporterId());
        if (reporter != null) {
            vo.setReporterName(reporter.getRealName());
            vo.setReporterPhone(DesensitizeUtil.maskPhone(reporter.getPhone()));
        }

        if (order.getVerifyBy() != null) {
            User verifier = userMapper.selectById(order.getVerifyBy());
            vo.setVerifyByName(verifier == null ? null : verifier.getRealName());
        }
        if (order.getAcceptBy() != null) {
            User accepter = userMapper.selectById(order.getAcceptBy());
            vo.setAcceptByName(accepter == null ? null : accepter.getRealName());
        }

        if (withProgress) {
            vo.setProgressList(repairProgressMapper.selectList(new LambdaQueryWrapper<RepairProgress>()
                    .eq(RepairProgress::getRepairId, order.getId())
                    .orderByAsc(RepairProgress::getCreateTime)
                    .orderByAsc(RepairProgress::getId)));
        }
        return vo;
    }
}
