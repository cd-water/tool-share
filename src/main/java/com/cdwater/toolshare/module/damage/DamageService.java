package com.cdwater.toolshare.module.damage;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cdwater.toolshare.module.auth.Admin;
import com.cdwater.toolshare.module.auth.AdminMapper;
import com.cdwater.toolshare.module.auth.User;
import com.cdwater.toolshare.module.auth.UserMapper;
import com.cdwater.toolshare.module.message.MessageService;
import com.cdwater.toolshare.module.rental.Rental;
import com.cdwater.toolshare.module.rental.RentalMapper;
import com.cdwater.toolshare.module.tool.Tool;
import com.cdwater.toolshare.module.tool.ToolMapper;
import com.cdwater.toolshare.module.tool.ToolService;
import com.cdwater.toolshare.shared.config.StpKit;
import com.cdwater.toolshare.shared.enums.AcceptResult;
import com.cdwater.toolshare.shared.enums.DamageLevel;
import com.cdwater.toolshare.shared.enums.MessageType;
import com.cdwater.toolshare.shared.enums.RentalStatus;
import com.cdwater.toolshare.shared.enums.RepairProgress;
import com.cdwater.toolshare.shared.enums.ReportStatus;
import com.cdwater.toolshare.shared.enums.ToolStatus;
import com.cdwater.toolshare.shared.enums.VerifyResult;
import com.cdwater.toolshare.shared.sequence.SequenceService;
import com.cdwater.toolshare.shared.tx.AfterCommit;
import com.cdwater.toolshare.shared.web.BizException;
import com.cdwater.toolshare.shared.web.PageQuery;
import com.cdwater.toolshare.shared.web.PageVO;
import com.cdwater.toolshare.shared.web.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DamageService {

    /** 报修单关联租赁仅在「使用中 / 已逾期」时可核验。 */
    private static final Set<String> IN_USE_RENTAL_STATUS =
            Set.of(RentalStatus.PICKED_UP.code(), RentalStatus.OVERDUE.code());
    /** 验收后允许的工具目标状态；已租出不可由验收直接指定。 */
    private static final Set<ToolStatus> ACCEPT_TOOL_STATUS =
            Set.of(ToolStatus.AVAILABLE, ToolStatus.MAINTENANCE, ToolStatus.SCRAPPED);

    private final DamageReportMapper damageReportMapper;
    private final RentalMapper rentalMapper;
    private final ToolMapper toolMapper;
    private final UserMapper userMapper;
    private final AdminMapper adminMapper;
    private final ToolService toolService;
    private final SequenceService sequenceService;
    private final MessageService messageService;
    private final DamageReportVoMapper damageReportVoMapper;
    private final AfterCommit afterCommit;

    @Transactional
    public DamageReportVO create(DamageCreateDTO dto) {
        Long userId = StpKit.USER.getLoginIdAsLong();
        // 锁租赁行，串行化同一租赁的并发报修；数据库唯一索引兜底
        Rental rental = rentalMapper.selectByIdForUpdate(dto.getRentalId());
        if (rental == null || !rental.getUserId().equals(userId)) {
            throw new BizException(ResultCode.NOT_FOUND, "租赁单不存在");
        }
        if (!IN_USE_RENTAL_STATUS.contains(rental.getStatus())) {
            throw new BizException(ResultCode.BAD_REQUEST, "仅使用中的租赁可以报修");
        }
        long active = damageReportMapper.selectCount(new LambdaQueryWrapper<DamageReport>()
                .eq(DamageReport::getRentalId, rental.getId())
                .in(DamageReport::getStatus, activeStatusCodes()));
        if (active > 0) {
            throw new BizException(ResultCode.CONFLICT, "该租赁已有处理中的报修单");
        }
        User user = userMapper.selectById(userId);
        DamageReport report = new DamageReport();
        report.setReportNo(sequenceService.nextDamageReportNo());
        report.setRentalId(rental.getId());
        report.setToolId(rental.getToolId());
        report.setToolCode(rental.getToolCode());
        report.setToolName(rental.getToolName());
        report.setUserId(userId);
        report.setReporterName(displayName(user));
        report.setReporterPhone(user == null ? rental.getRenterPhone() : user.getPhone());
        report.setDamagePart(dto.getDamagePart().trim());
        report.setDescription(dto.getDescription().trim());
        report.setImages(dto.getImages() == null ? "" : String.join(",", dto.getImages()));
        report.setStatus(ReportStatus.SUBMITTED.code());
        try {
            damageReportMapper.insert(report);
        } catch (DuplicateKeyException e) {
            throw new BizException(ResultCode.CONFLICT, "该租赁已有处理中的报修单");
        }
        afterCommit.run(() -> messageService.notifyAdmins(MessageType.REPAIR, "新的损坏报修",
                report.getReporterName() + " 提交了报修单 " + report.getReportNo()
                        + "（工具 " + report.getToolCode() + "），请及时核验。",
                "REPAIR_SUBMIT:" + report.getId()));
        return toVO(report);
    }

    @Transactional
    public void verify(Long id, DamageVerifyDTO dto) {
        DamageReport report = getReport(id);
        VerifyResult result = VerifyResult.fromCode(dto.getVerifyResult())
                .orElseThrow(() -> new BizException(ResultCode.BAD_REQUEST, "无效的核验结果"));
        ReportStatus target = result == VerifyResult.FALSE
                ? ReportStatus.REJECTED : ReportStatus.VERIFIED;
        // 非 SUBMITTED 一律 409（含重复核验）
        if (statusOf(report) != ReportStatus.SUBMITTED) {
            throw new BizException(ResultCode.CONFLICT, "仅待核验的报修可核验");
        }
        DamageStateMachine.assertTransition(ReportStatus.SUBMITTED, target);
        // 旧报修不得把已归还或已新租的工具再次置维修
        Rental rental = rentalMapper.selectByIdForUpdate(report.getRentalId());
        if (rental == null || !IN_USE_RENTAL_STATUS.contains(rental.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "关联租赁单已结束，不能再核验该报修");
        }

        LambdaUpdateWrapper<DamageReport> update = new LambdaUpdateWrapper<DamageReport>()
                .eq(DamageReport::getId, id)
                .eq(DamageReport::getStatus, ReportStatus.SUBMITTED.code())
                .set(DamageReport::getStatus, target.code())
                .set(DamageReport::getVerifier, currentAdminName())
                .set(DamageReport::getVerifyTime, LocalDateTime.now())
                .set(DamageReport::getVerifyResult, result.code())
                .set(DamageReport::getRepairPerson, dto.getRepairPerson())
                .set(DamageReport::getRepairEstimate, dto.getRepairEstimate())
                .set(DamageReport::getRemark, dto.getRemark());
        if (damageReportMapper.update(null, update) == 0) {
            throw new BizException(ResultCode.CONFLICT, "报修单状态已变更，请刷新后重试");
        }

        if (result == VerifyResult.FALSE) {
            afterCommit.run(() -> messageService.notifyUser(report.getUserId(), MessageType.REPAIR, "报修已驳回",
                    "报修单 " + report.getReportNo() + " 经核验为误报，工具无需维修。"));
            return;
        }
        Tool tool = toolService.getTool(report.getToolId());
        toolService.transition(tool, ToolStatus.MAINTENANCE, "REPAIR", "报修核验确认，工具转维修中");
        afterCommit.run(() -> messageService.notifyUser(report.getUserId(), MessageType.REPAIR, "报修已确认",
                "报修单 " + report.getReportNo() + " 已核验，维修人员将尽快处理。"));
    }

    @Transactional
    public void progress(Long id, DamageProgressDTO dto) {
        DamageReport report = getReport(id);
        ReportStatus from = statusOf(report);
        RepairProgress progress = RepairProgress.fromCode(dto.getProgressStatus())
                .orElseThrow(() -> new BizException(ResultCode.BAD_REQUEST, "无效的维修进度"));
        if (from != ReportStatus.VERIFIED && from != ReportStatus.REPAIRING) {
            // 已提交 / 终态均不可更新进度，统一 409
            DamageStateMachine.assertTransition(from, ReportStatus.REPAIRING);
        }

        String verifier = currentAdminName();
        LambdaUpdateWrapper<DamageReport> update = new LambdaUpdateWrapper<DamageReport>()
                .eq(DamageReport::getId, id)
                .eq(DamageReport::getStatus, from.code())
                .set(DamageReport::getStatus, ReportStatus.REPAIRING.code())
                .set(DamageReport::getProgressStatus, progress.code())
                .set(DamageReport::getProgressNote, dto.getProgressNote())
                .set(DamageReport::getProgressUpdateTime, LocalDateTime.now())
                .setSql("repair_start_time = COALESCE(repair_start_time, NOW())");
        if (dto.getRepairCost() != null) {
            update.set(DamageReport::getRepairCost, dto.getRepairCost());
        }
        if (StringUtils.hasText(dto.getRepairEstimate())) {
            update.set(DamageReport::getRepairEstimate, dto.getRepairEstimate());
        }
        if (StringUtils.hasText(dto.getRepairPerson())) {
            update.set(DamageReport::getRepairPerson, dto.getRepairPerson());
        }
        if (damageReportMapper.update(null, update) == 0) {
            throw new BizException(ResultCode.CONFLICT, "报修单状态已变更，请刷新后重试");
        }
        afterCommit.run(() -> {
            messageService.notifyUser(report.getUserId(), MessageType.REPAIR, "维修进度更新",
                    "报修单 " + report.getReportNo() + " 进度：" + progress.label()
                            + "。" + dto.getProgressNote());
            if (progress == RepairProgress.COMPLETED) {
                messageService.notifyAdmins(MessageType.REPAIR, "维修已完成",
                        "报修单 " + report.getReportNo() + "（工具 " + report.getToolCode()
                                + "）维修已完成，请及时验收。",
                        "REPAIR_COMPLETE:" + id);
            }
        });
    }

    @Transactional
    public void accept(Long id, DamageAcceptDTO dto) {
        DamageReport report = getReport(id);
        ReportStatus from = statusOf(report);
        if (from != ReportStatus.REPAIRING) {
            // 未进入维修 / 终态重复验收，统一 409
            throw new BizException(ResultCode.CONFLICT, "仅维修中的报修可验收");
        }
        if (!RepairProgress.COMPLETED.code().equals(report.getProgressStatus())) {
            throw new BizException(ResultCode.CONFLICT, "维修尚未完成，不能验收");
        }
        AcceptResult result = AcceptResult.fromCode(dto.getAcceptResult())
                .orElseThrow(() -> new BizException(ResultCode.BAD_REQUEST, "无效的验收结果"));
        ToolStatus requested = ToolStatus.fromCode(dto.getToolStatusAfter())
                .orElseThrow(() -> new BizException(ResultCode.BAD_REQUEST, "无效的工具状态"));
        if (!ACCEPT_TOOL_STATUS.contains(requested)) {
            throw new BizException(ResultCode.BAD_REQUEST, "验收后工具状态只能是可用、维修中或已报废");
        }
        if (result != AcceptResult.QUALIFIED && requested == ToolStatus.AVAILABLE) {
            throw new BizException(ResultCode.BAD_REQUEST,
                    result.label() + "的工具不能直接置为可用");
        }
        ToolStatus target = resolveAcceptToolStatus(report, result, requested);
        ReportStatus reportTarget = target == ToolStatus.SCRAPPED
                ? ReportStatus.SCRAPPED : ReportStatus.COMPLETED;
        DamageStateMachine.assertTransition(from, reportTarget);

        LambdaUpdateWrapper<DamageReport> update = new LambdaUpdateWrapper<DamageReport>()
                .eq(DamageReport::getId, id)
                .eq(DamageReport::getStatus, ReportStatus.REPAIRING.code())
                .eq(DamageReport::getProgressStatus, RepairProgress.COMPLETED.code())
                .set(DamageReport::getStatus, reportTarget.code())
                .set(DamageReport::getAcceptor, currentAdminName())
                .set(DamageReport::getAcceptTime, LocalDateTime.now())
                .set(DamageReport::getAcceptResult, result.code())
                .set(DamageReport::getToolStatusAfter, target.code());
        if (dto.getRepairCost() != null) {
            update.set(DamageReport::getRepairCost, dto.getRepairCost());
        }
        if (StringUtils.hasText(dto.getRemark())) {
            update.set(DamageReport::getRemark, dto.getRemark());
        }
        if (damageReportMapper.update(null, update) == 0) {
            throw new BizException(ResultCode.CONFLICT, "报修单状态已变更，请刷新后重试");
        }

        Tool tool = toolService.getTool(report.getToolId());
        tool.setLastRepairTime(LocalDateTime.now());
        tool.setDamageLevel(switch (result) {
            case QUALIFIED -> DamageLevel.LIGHT.code();
            case PARTIAL -> DamageLevel.MEDIUM.code();
            case UNQUALIFIED -> DamageLevel.SEVERE.code();
        });
        toolService.transition(tool, target, "REPAIR", "维修验收：" + result.label());
        afterCommit.run(() -> messageService.notifyUser(report.getUserId(), MessageType.REPAIR, "维修验收完成",
                "报修单 " + report.getReportNo() + " 验收结果：" + result.label()
                        + "，工具状态：" + target.label() + "。"));
    }

    /**
     * 验收结论与工具目标状态组合：
     * <ul>
     *   <li>合格：租赁已结束才可置为可用；仍在租赁中保持维修中</li>
     *   <li>部分合格 / 不合格：只能维修中或报废</li>
     *   <li>报废：工具终态，不可再被验收改回</li>
     * </ul>
     */
    private ToolStatus resolveAcceptToolStatus(DamageReport report, AcceptResult result,
                                               ToolStatus requested) {
        if (requested == ToolStatus.SCRAPPED) {
            return ToolStatus.SCRAPPED;
        }
        if (result != AcceptResult.QUALIFIED) {
            return ToolStatus.MAINTENANCE;
        }
        Rental rental = rentalMapper.selectById(report.getRentalId());
        boolean inUse = rental != null && IN_USE_RENTAL_STATUS.contains(rental.getStatus());
        return requested == ToolStatus.AVAILABLE && inUse
                ? ToolStatus.MAINTENANCE : requested;
    }

    public PageVO<DamageReportVO> userPage(String keyword, String status, long page, long size) {
        LambdaQueryWrapper<DamageReport> wrapper = new LambdaQueryWrapper<DamageReport>()
                .eq(DamageReport::getUserId, StpKit.USER.getLoginIdAsLong());
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(DamageReport::getReportNo, keyword)
                    .or().like(DamageReport::getToolCode, keyword));
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(DamageReport::getStatus, status);
        }
        Page<DamageReport> result = damageReportMapper.selectPage(PageQuery.of(page, size),
                wrapper.orderByDesc(DamageReport::getCreateTime));
        return toPage(result);
    }

    public PageVO<DamageReportVO> adminPage(String keyword, String status, long page, long size) {
        LambdaQueryWrapper<DamageReport> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(DamageReport::getReportNo, keyword)
                    .or().like(DamageReport::getToolCode, keyword)
                    .or().like(DamageReport::getToolName, keyword)
                    .or().like(DamageReport::getReporterName, keyword));
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(DamageReport::getStatus, status);
        }
        Page<DamageReport> result = damageReportMapper.selectPage(PageQuery.of(page, size),
                wrapper.orderByDesc(DamageReport::getCreateTime));
        return toPage(result);
    }

    /** 列表批量加载工具主图，避免逐行查询工具表。 */
    private PageVO<DamageReportVO> toPage(Page<DamageReport> result) {
        Set<Long> toolIds = result.getRecords().stream()
                .map(DamageReport::getToolId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> images = new HashMap<>();
        if (!toolIds.isEmpty()) {
            for (Tool tool : toolMapper.selectBatchIds(toolIds)) {
                images.put(tool.getId(), tool.getImageUrl());
            }
        }
        return PageVO.of(result, report -> {
            DamageReportVO vo = damageReportVoMapper.toVO(report);
            vo.setToolImageUrl(images.get(report.getToolId()));
            return vo;
        });
    }

    public DamageReportVO userDetail(Long id) {
        DamageReport report = getReport(id);
        if (!report.getUserId().equals(StpKit.USER.getLoginIdAsLong())) {
            throw new BizException(ResultCode.FORBIDDEN, "无权查看该报修单");
        }
        return toVO(report);
    }

    public DamageReportVO adminDetail(Long id) {
        return toVO(getReport(id));
    }

    private DamageReport getReport(Long id) {
        DamageReport report = damageReportMapper.selectById(id);
        if (report == null) {
            throw new BizException(ResultCode.NOT_FOUND, "报修单不存在");
        }
        return report;
    }

    private ReportStatus statusOf(DamageReport report) {
        return ReportStatus.fromCode(report.getStatus())
                .orElseThrow(() -> new BizException(ResultCode.CONFLICT, "无效的报修状态"));
    }

    private List<String> activeStatusCodes() {
        return List.of(ReportStatus.SUBMITTED.code(), ReportStatus.VERIFIED.code(),
                ReportStatus.REPAIRING.code());
    }

    private String displayName(User user) {
        if (user == null) {
            return "居民";
        }
        return StringUtils.hasText(user.getRealName()) ? user.getRealName() : user.getNickname();
    }

    private String currentAdminName() {
        Admin admin = adminMapper.selectById(StpKit.ADMIN.getLoginIdAsLong());
        return admin == null ? "工作人员" : admin.getUsername();
    }

    private DamageReportVO toVO(DamageReport report) {
        DamageReportVO vo = damageReportVoMapper.toVO(report);
        Tool tool = toolMapper.selectById(report.getToolId());
        vo.setToolImageUrl(tool == null ? null : tool.getImageUrl());
        return vo;
    }
}
