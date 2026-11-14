package com.cdwater.toolshare.module.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cdwater.toolshare.module.auth.Admin;
import com.cdwater.toolshare.module.auth.AdminMapper;
import com.cdwater.toolshare.module.damage.DamageReport;
import com.cdwater.toolshare.module.damage.DamageReportMapper;
import com.cdwater.toolshare.module.rental.Rental;
import com.cdwater.toolshare.module.rental.RentalMapper;
import com.cdwater.toolshare.shared.config.StpKit;
import com.cdwater.toolshare.shared.enums.AuditStatus;
import com.cdwater.toolshare.shared.enums.ToolStatus;
import com.cdwater.toolshare.shared.html.HtmlSanitizer;
import com.cdwater.toolshare.shared.sequence.SequenceService;
import com.cdwater.toolshare.shared.web.BizException;
import com.cdwater.toolshare.shared.web.Dict;
import com.cdwater.toolshare.shared.web.PageQuery;
import com.cdwater.toolshare.shared.web.PageVO;
import com.cdwater.toolshare.shared.web.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ToolService {

    private static final DateTimeFormatter SUGGEST_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final List<String> ACTIVE_RENTAL_STATUS =
            List.of("PENDING_PAYMENT", "RESERVED", "PICKED_UP", "OVERDUE");
    private static final List<String> UNFINISHED_REPORT_STATUS =
            List.of("SUBMITTED", "VERIFIED", "REPAIRING");

    private final ToolMapper toolMapper;
    private final ToolLogMapper toolLogMapper;
    private final RentalMapper rentalMapper;
    private final DamageReportMapper damageReportMapper;
    private final AdminMapper adminMapper;
    private final SequenceService sequenceService;
    private final ToolWatchService toolWatchService;
    private final ToolIndexService toolIndexService;
    private final ToolVoMapper toolVoMapper;
    private final ToolLogVoMapper toolLogVoMapper;

    @Value("${toolshare.rental.pending-payment-timeout-minutes:30}")
    private long pendingPaymentTimeoutMinutes = 30;

    public PageVO<ToolVO> userPage(String keyword, String category, String status, long page, long size) {
        LambdaQueryWrapper<Tool> wrapper = baseWrapper(keyword, category, status, null)
                .eq(Tool::getAuditStatus, AuditStatus.APPROVED.code());
        if (!StringUtils.hasText(status)) {
            wrapper.ne(Tool::getStatus, ToolStatus.SCRAPPED.code());
        }
        wrapper.orderByDesc(Tool::getCreateTime);
        Page<Tool> result = toolMapper.selectPage(PageQuery.of(page, size), wrapper);
        return toPage(result);
    }

    /**
     * 管理端列表：支持按租赁次数 / 最近维修时间排序，以及最低租赁次数、维修时间范围筛选。
     */
    public PageVO<ToolVO> adminPage(ToolAdminQuery query) {
        LambdaQueryWrapper<Tool> wrapper = baseWrapper(query.getKeyword(), query.getCategory(),
                query.getStatus(), query.getStorageArea());
        if (query.getMinRentalCount() != null) {
            wrapper.ge(Tool::getRentalCount, query.getMinRentalCount());
        }
        if (Boolean.TRUE.equals(query.getNeverRepaired())) {
            wrapper.isNull(Tool::getLastRepairTime);
        } else if (query.getRepairedWithinDays() != null && query.getRepairedWithinDays() > 0) {
            wrapper.ge(Tool::getLastRepairTime,
                    LocalDateTime.now().minusDays(query.getRepairedWithinDays()));
        }
        switch (query.getOrderBy() == null ? "" : query.getOrderBy()) {
            case "rentalCount" -> wrapper.orderByDesc(Tool::getRentalCount);
            case "lastRepairTime" -> wrapper.orderByDesc(Tool::getLastRepairTime);
            default -> {
            }
        }
        // 次级排序保证结果稳定
        wrapper.orderByDesc(Tool::getCreateTime);
        Page<Tool> result = toolMapper.selectPage(PageQuery.of(query.getPage(), query.getSize()), wrapper);
        return toPage(result);
    }

    private LambdaQueryWrapper<Tool> baseWrapper(String keyword, String category,
                                                  String status, String storageArea) {
        LambdaQueryWrapper<Tool> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(Tool::getName, keyword)
                    .or().like(Tool::getModel, keyword)
                    .or().like(Tool::getCode, keyword));
        }
        if (StringUtils.hasText(category)) {
            wrapper.eq(Tool::getCategory, category);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(Tool::getStatus, status);
        }
        if (StringUtils.hasText(storageArea)) {
            wrapper.like(Tool::getStorageArea, storageArea);
        }
        return wrapper;
    }

    /** 管理端详情：未审核工具也可见。 */
    public ToolVO getVO(Long id) {
        return toVO(getTool(id));
    }

    /** 用户端详情：未通过审核的工具按不存在处理。 */
    public ToolVO getUserVO(Long id) {
        Tool tool = getTool(id);
        if (!isApproved(tool)) {
            throw new BizException(ResultCode.NOT_FOUND, "工具不存在");
        }
        return toVO(tool);
    }

    public Tool getTool(Long id) {
        Tool tool = toolMapper.selectById(id);
        if (tool == null) {
            throw new BizException(ResultCode.NOT_FOUND, "工具不存在");
        }
        return tool;
    }

    @Transactional
    public ToolVO create(ToolSaveDTO dto) {
        validateDict(dto);
        Tool tool = new Tool();
        apply(tool, dto);
        tool.setCode(sequenceService.nextToolCode(dto.getCategory()));
        tool.setAuditStatus(AuditStatus.PENDING.code());
        tool.setRentalCount(0);
        tool.setStatus(StringUtils.hasText(dto.getStatus())
                ? dto.getStatus() : ToolStatus.AVAILABLE.code());
        tool.setCreatedBy(StpKit.ADMIN.getLoginIdAsLong());
        toolMapper.insert(tool);
        logStructured(tool.getId(), "CREATE", null, tool.getStatus(), "新增工具 " + tool.getCode());
        syncIndex(tool);
        return toVO(tool);
    }

    @Transactional
    public ToolVO update(Long id, ToolSaveDTO dto) {
        validateDict(dto);
        Tool tool = getTool(id);
        apply(tool, dto);
        toolMapper.updateById(tool);
        logStructured(tool.getId(), "UPDATE", tool.getStatus(), tool.getStatus(), "编辑工具 " + tool.getCode());
        if (StringUtils.hasText(dto.getStatus()) && !dto.getStatus().equals(tool.getStatus())) {
            // 状态变更由 transition 统一同步索引
            changeStatus(id, dto.getStatus());
            tool = getTool(id);
        } else {
            syncIndex(tool);
        }
        return toVO(tool);
    }

    /** 审核：仅系统管理员可调用；退回时必须填写审核意见。 */
    @Transactional
    public ToolVO audit(Long id, ToolAuditDTO dto) {
        AuditStatus target = AuditStatus.fromName(dto.getAuditStatus())
                .orElseThrow(() -> new BizException(ResultCode.BAD_REQUEST, "无效的审核状态"));
        if (target == AuditStatus.PENDING) {
            throw new BizException(ResultCode.BAD_REQUEST, "不能把工具改回待审核");
        }
        if (target == AuditStatus.NEEDS_CHANGE && !StringUtils.hasText(dto.getRemark())) {
            throw new BizException(ResultCode.BAD_REQUEST, "退回需填写审核意见");
        }
        Tool tool = getTool(id);
        AuditStatus from = AuditStatus.fromCode(tool.getAuditStatus()).orElse(AuditStatus.PENDING);
        if (from == target) {
            return toVO(tool);
        }
        tool.setAuditStatus(target.code());
        tool.setAuditRemark(dto.getRemark());
        tool.setReviewedBy(StpKit.ADMIN.getLoginIdAsLong());
        tool.setReviewedAt(LocalDateTime.now());
        toolMapper.updateById(tool);
        logStructured(id, "AUDIT", from.name(), target.name(), dto.getRemark());
        syncIndex(tool);
        return toVO(tool);
    }

    /** 手动状态更新入口。 */
    @Transactional
    public void updateStatus(Long id, String status) {
        changeStatus(id, status);
    }

    private void changeStatus(Long id, String status) {
        Tool tool = getTool(id);
        transition(tool, parseStatus(status), "STATUS_CHANGE", "手动更新工具状态");
    }

    /**
     * 所有工具状态写入口统一走这里：状态机校验 + 可用性守卫 + 结构化日志。
     */
    @Transactional
    public void transition(Tool tool, ToolStatus target, String action, String remark) {
        ToolStatus from = parseStatus(tool.getStatus());
        ToolStateMachine.assertTransition(from, target);
        if (target == ToolStatus.AVAILABLE) {
            if (hasActiveRental(tool.getId())) {
                throw new BizException(ResultCode.BAD_REQUEST, "存在活动租赁，不能将工具置为可用");
            }
            if (hasUnfinishedReport(tool.getId())) {
                throw new BizException(ResultCode.BAD_REQUEST, "存在未完成报修，不能将工具置为可用");
            }
        }
        tool.setStatus(target.code());
        if (target == ToolStatus.AVAILABLE) {
            tool.setLastRepairTime(LocalDateTime.now());
        }
        toolMapper.updateById(tool);
        logStructured(tool.getId(), action, from.code(), target.code(), remark);
        if (target == ToolStatus.SCRAPPED) {
            toolIndexService.remove(tool.getId());
        } else if (target == ToolStatus.AVAILABLE || target == ToolStatus.MAINTENANCE) {
            syncIndex(tool);
        }
        notifyWatchersIfAvailable(tool);
    }

    /**
     * 显式报废恢复：仅系统管理员（{@code tool:manage}）可调用，必须填写原因并写结构化日志。
     * <p>普通状态更新仍受状态机约束，不能覆盖 SCRAPPED。
     */
    @Transactional
    public ToolVO restore(Long id, ToolRestoreDTO dto) {
        Tool tool = getTool(id);
        if (!ToolStatus.SCRAPPED.code().equals(tool.getStatus())) {
            throw new BizException(ResultCode.BAD_REQUEST, "只有已报废的工具可以恢复");
        }
        if (!StringUtils.hasText(dto.getReason())) {
            throw new BizException(ResultCode.BAD_REQUEST, "恢复原因不能为空");
        }
        if (hasActiveRental(id)) {
            throw new BizException(ResultCode.BAD_REQUEST, "存在活动租赁，不能恢复工具");
        }
        if (hasUnfinishedReport(id)) {
            throw new BizException(ResultCode.BAD_REQUEST, "存在未完成报修，不能恢复工具");
        }
        tool.setStatus(ToolStatus.AVAILABLE.code());
        tool.setLastRepairTime(LocalDateTime.now());
        toolMapper.updateById(tool);
        logStructured(id, "RESTORE", ToolStatus.SCRAPPED.code(), ToolStatus.AVAILABLE.code(),
                dto.getReason().trim());
        syncIndex(tool);
        notifyWatchersIfAvailable(tool);
        return toVO(tool);
    }

    /** 索引与数据库保持一致：通过审核且未报废才写入，其余一律删除。 */
    private void syncIndex(Tool tool) {
        if (isApproved(tool) && !ToolStatus.SCRAPPED.code().equals(tool.getStatus())) {
            toolIndexService.upsert(tool);
        } else {
            toolIndexService.remove(tool.getId());
        }
    }

    private boolean hasActiveRental(Long toolId) {
        return rentalMapper.selectCount(new LambdaQueryWrapper<Rental>()
                .eq(Rental::getToolId, toolId)
                .in(Rental::getStatus, ACTIVE_RENTAL_STATUS)) > 0;
    }

    private boolean hasUnfinishedReport(Long toolId) {
        return damageReportMapper.selectCount(new LambdaQueryWrapper<DamageReport>()
                .eq(DamageReport::getToolId, toolId)
                .in(DamageReport::getStatus, UNFINISHED_REPORT_STATUS)) > 0;
    }

    /** 工具恢复可用时提醒蹲守用户（手动改状态/归还/维修完成都会走到这里） */
    public void notifyWatchersIfAvailable(Tool tool) {
        if (tool == null || !ToolStatus.AVAILABLE.code().equals(tool.getStatus())) {
            return;
        }
        toolWatchService.notifyAvailable(tool.getId(), tool.getName());
    }

    public List<ToolLogVO> logs(Long toolId) {
        return toolLogMapper.selectList(new LambdaQueryWrapper<ToolLog>()
                        .eq(ToolLog::getToolId, toolId)
                        .orderByDesc(ToolLog::getCreateTime))
                .stream().map(toolLogVoMapper::toVO).toList();
    }

    public AvailabilityVO availability(Long toolId, LocalDateTime start, LocalDateTime end) {
        Tool tool = getTool(toolId);
        if (!isApproved(tool)) {
            return new AvailabilityVO(false, "工具待审核，暂不可预约", List.of());
        }
        if (!ToolStatus.AVAILABLE.code().equals(tool.getStatus())) {
            return new AvailabilityVO(false,
                    "工具当前状态为" + Dict.label(Dict.TOOL_STATUS, tool.getStatus()) + "，暂不可预约",
                    List.of());
        }
        if (start == null || end == null || !start.isBefore(end)) {
            return new AvailabilityVO(false, "租赁开始时间必须早于结束时间", List.of());
        }
        List<Rental> conflicts = rentalMapper.selectConflicts(toolId, start, end,
                pendingPaymentTimeoutMinutes);
        if (conflicts.isEmpty()) {
            return new AvailabilityVO(true, "该时段可预约", List.of());
        }
        List<String> suggestions = new ArrayList<>();
        for (Rental rental : conflicts) {
            if (suggestions.size() >= 3) {
                break;
            }
            suggestions.add(rental.getEndTime().plusHours(1).format(SUGGEST_FORMAT) + " 之后");
        }
        return new AvailabilityVO(false, "该时段已被预约，可尝试其他时间", suggestions);
    }

    private void logStructured(Long toolId, String action,
                               String fromStatus, String toStatus, String remark) {
        ToolLog log = new ToolLog();
        log.setToolId(toolId);
        log.setAction(action);
        log.setFromStatus(fromStatus);
        log.setToStatus(toStatus);
        log.setRemark(remark);
        if (StringUtils.hasText(remark)) {
            log.setDetail(remark);
        } else if (fromStatus != null && toStatus != null) {
            log.setDetail(fromStatus + " -> " + toStatus);
        } else {
            log.setDetail(action);
        }
        if (StpKit.ADMIN.isLogin()) {
            Long adminId = StpKit.ADMIN.getLoginIdAsLong();
            log.setOperatorId(adminId);
            Admin admin = adminMapper.selectById(adminId);
            log.setOperatorName(admin == null ? null : admin.getUsername());
        }
        toolLogMapper.insert(log);
    }

    private boolean isApproved(Tool tool) {
        return tool.getAuditStatus() != null
                && tool.getAuditStatus() == AuditStatus.APPROVED.code();
    }

    private ToolStatus parseStatus(String status) {
        return ToolStatus.fromCode(status)
                .orElseThrow(() -> new BizException(ResultCode.BAD_REQUEST, "无效的工具状态"));
    }

    private void validateDict(ToolSaveDTO dto) {
        if (!Dict.containsKey(Dict.TOOL_CATEGORY, dto.getCategory())) {
            throw new BizException(ResultCode.BAD_REQUEST, "无效的工具类别");
        }
        if (!Dict.containsKey(Dict.DAMAGE_LEVEL, dto.getDamageLevel())) {
            throw new BizException(ResultCode.BAD_REQUEST, "无效的损坏程度");
        }
        if (StringUtils.hasText(dto.getStatus()) && !Dict.containsKey(Dict.TOOL_STATUS, dto.getStatus())) {
            throw new BizException(ResultCode.BAD_REQUEST, "无效的工具状态");
        }
    }

    private void apply(Tool tool, ToolSaveDTO dto) {
        tool.setName(dto.getName().trim());
        tool.setModel(dto.getModel() == null ? "" : dto.getModel().trim());
        tool.setCategory(dto.getCategory());
        tool.setDamageLevel(dto.getDamageLevel());
        tool.setDeposit(dto.getDeposit());
        tool.setStorageArea(dto.getStorageArea() == null ? "" : dto.getStorageArea().trim());
        tool.setUsageGuide(HtmlSanitizer.clean(dto.getUsageGuide()));
        List<String> images = dto.getImageUrls() == null ? List.of() : dto.getImageUrls();
        tool.setImageUrls(String.join(",", images));
        tool.setImageUrl(images.isEmpty() ? "" : images.get(0));
        tool.setPurchaseDate(dto.getPurchaseDate());
        tool.setSupplier(dto.getSupplier() == null ? "" : dto.getSupplier().trim());
    }

    private ToolVO toVO(Tool tool) {
        ToolVO vo = toolVoMapper.toVO(tool);
        if (tool.getCreatedBy() != null) {
            Admin admin = adminMapper.selectById(tool.getCreatedBy());
            vo.setCreatedByName(admin == null ? null : admin.getUsername());
        }
        return vo;
    }

    private PageVO<ToolVO> toPage(Page<Tool> result) {
        Set<Long> creatorIds = result.getRecords().stream()
                .map(Tool::getCreatedBy)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> names = creatorIds.isEmpty()
                ? Map.of()
                : adminMapper.selectBatchIds(creatorIds).stream()
                        .collect(Collectors.toMap(Admin::getId, Admin::getUsername));
        return PageVO.of(result, tool -> {
            ToolVO vo = toolVoMapper.toVO(tool);
            vo.setCreatedByName(names.get(tool.getCreatedBy()));
            return vo;
        });
    }
}
