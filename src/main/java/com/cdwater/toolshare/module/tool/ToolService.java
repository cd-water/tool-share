package com.cdwater.toolshare.module.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cdwater.toolshare.module.auth.Admin;
import com.cdwater.toolshare.module.auth.AdminMapper;
import com.cdwater.toolshare.module.damage.DamageReport;
import com.cdwater.toolshare.module.damage.DamageReportMapper;
import com.cdwater.toolshare.module.rental.Rental;
import com.cdwater.toolshare.module.rental.RentalMapper;
import com.cdwater.toolshare.module.review.ToolRatingStats;
import com.cdwater.toolshare.module.review.ToolReviewMapper;
import com.cdwater.toolshare.shared.config.StpKit;
import com.cdwater.toolshare.shared.enums.AuditStatus;
import com.cdwater.toolshare.shared.enums.RentalStatus;
import com.cdwater.toolshare.shared.enums.ToolStatus;
import com.cdwater.toolshare.shared.html.HtmlSanitizer;
import com.cdwater.toolshare.shared.sequence.SequenceService;
import com.cdwater.toolshare.shared.tx.AfterCommit;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ToolService {

    private static final DateTimeFormatter SUGGEST_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final List<RentalStatus> ACTIVE_RENTAL_STATUS =
            List.of(RentalStatus.PENDING_PAYMENT, RentalStatus.RESERVED,
                    RentalStatus.PICKED_UP, RentalStatus.OVERDUE);
    private static final List<String> UNFINISHED_REPORT_STATUS =
            List.of("SUBMITTED", "VERIFIED", "REPAIRING");
    /** 按平均分倒序：无评价工具按 0 分参与排序，不会被从结果中过滤。 */
    private static final String ORDER_BY_RATING_SQL =
            "ORDER BY (SELECT COALESCE(AVG(r.rating), 0) FROM t_tool_review r"
                    + " WHERE r.tool_id = t_tool.id) DESC, t_tool.create_time DESC";

    private final ToolMapper toolMapper;
    private final ToolLogMapper toolLogMapper;
    private final RentalMapper rentalMapper;
    private final DamageReportMapper damageReportMapper;
    private final AdminMapper adminMapper;
    private final SequenceService sequenceService;
    private final ToolWatchService toolWatchService;
    private final ToolReviewMapper toolReviewMapper;
    private final ToolIndexService toolIndexService;
    private final ToolVoMapper toolVoMapper;
    private final ToolLogVoMapper toolLogVoMapper;
    private final AfterCommit afterCommit;

    @Value("${toolshare.rental.pending-payment-timeout-minutes:30}")
    private long pendingPaymentTimeoutMinutes = 30;

    @Value("${toolshare.availability.slot-minutes:30}")
    private long availabilitySlotMinutes = 30;

    @Value("${toolshare.availability.max-suggestions:3}")
    private int availabilityMaxSuggestions = 3;

    @Value("${toolshare.availability.search-days:30}")
    private long availabilitySearchDays = 30;

    public PageVO<ToolVO> userPage(String keyword, String category, String status,
                                   String orderBy, long page, long size) {
        LambdaQueryWrapper<Tool> wrapper = baseWrapper(keyword, category, status, null)
                .eq(Tool::getAuditStatus, AuditStatus.APPROVED.value());
        if (!StringUtils.hasText(status)) {
            wrapper.ne(Tool::getStatus, ToolStatus.SCRAPPED.code());
        }
        if ("rating".equals(orderBy)) {
            wrapper.last(ORDER_BY_RATING_SQL);
        } else {
            // 默认排序保持不变
            wrapper.orderByDesc(Tool::getCreateTime);
        }
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

    /** 用户端工具历史租赁：只返回已归还的租期窗口与次数，不暴露他人信息。 */
    public ToolRentalHistoryVO userRentalHistory(Long toolId) {
        getUserVO(toolId);
        List<Rental> rentals = rentalMapper.selectList(new LambdaQueryWrapper<Rental>()
                .eq(Rental::getToolId, toolId)
                .eq(Rental::getStatus, RentalStatus.RETURNED.code())
                .orderByDesc(Rental::getEndTime));
        ToolRentalHistoryVO vo = new ToolRentalHistoryVO();
        vo.setToolId(toolId);
        vo.setCompletedCount((long) rentals.size());
        vo.setWindows(rentals.stream().map(rental -> {
            ToolRentalHistoryVO.Window window = new ToolRentalHistoryVO.Window();
            window.setStartTime(rental.getStartTime());
            window.setEndTime(rental.getEndTime());
            return window;
        }).toList());
        return vo;
    }

    @Transactional
    public ToolVO create(ToolSaveDTO dto) {
        validateDict(dto);
        Tool tool = new Tool();
        apply(tool, dto);
        tool.setCode(sequenceService.nextToolCode(dto.getCategory()));
        tool.setAuditStatus(AuditStatus.PENDING.value());
        tool.setVersion(0);
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
        tool.setAuditStatus(target.value());
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
        // 乐观锁：版本不匹配说明期间已有其他状态变更，拒绝覆盖
        if (toolMapper.updateById(tool) == 0) {
            throw new BizException(ResultCode.CONFLICT, "工具状态已被其他操作更新，请刷新后重试");
        }
        logStructured(tool.getId(), action, from.code(), target.code(), remark);
        if (target == ToolStatus.SCRAPPED
                || target == ToolStatus.AVAILABLE || target == ToolStatus.MAINTENANCE) {
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
        if (toolMapper.updateById(tool) == 0) {
            throw new BizException(ResultCode.CONFLICT, "工具状态已被其他操作更新，请刷新后重试");
        }
        logStructured(id, "RESTORE", ToolStatus.SCRAPPED.code(), ToolStatus.AVAILABLE.code(),
                dto.getReason().trim());
        syncIndex(tool);
        notifyWatchersIfAvailable(tool);
        return toVO(tool);
    }

    /** 索引与数据库保持一致：通过审核且未报废才写入，其余一律删除；在事务提交后执行。 */
    private void syncIndex(Tool tool) {
        if (tool == null || tool.getId() == null) {
            return;
        }
        Long toolId = tool.getId();
        boolean indexable = isApproved(tool)
                && !ToolStatus.SCRAPPED.code().equals(tool.getStatus());
        afterCommit.run(() -> {
            if (indexable) {
                toolIndexService.upsert(tool);
            } else {
                toolIndexService.remove(toolId);
            }
        });
    }

    private boolean hasActiveRental(Long toolId) {
        return rentalMapper.selectCount(new LambdaQueryWrapper<Rental>()
                .eq(Rental::getToolId, toolId)
                .in(Rental::getStatus, ACTIVE_RENTAL_STATUS.stream()
                        .map(RentalStatus::code).toList())) > 0;
    }

    private boolean hasUnfinishedReport(Long toolId) {
        return damageReportMapper.selectCount(new LambdaQueryWrapper<DamageReport>()
                .eq(DamageReport::getToolId, toolId)
                .in(DamageReport::getStatus, UNFINISHED_REPORT_STATUS)) > 0;
    }

    /** 工具恢复可用时提醒蹲守用户（手动改状态/归还/维修完成都会走到这里），事务提交后执行。 */
    public void notifyWatchersIfAvailable(Tool tool) {
        if (tool == null || !ToolStatus.AVAILABLE.code().equals(tool.getStatus())) {
            return;
        }
        Long toolId = tool.getId();
        String toolName = tool.getName();
        LocalDateTime availableAt = tool.getLastRepairTime();
        afterCommit.run(() -> toolWatchService.notifyAvailable(toolId, toolName, availableAt));
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
            return unavailable("工具待审核，暂不可预约");
        }
        if (!ToolStatus.AVAILABLE.code().equals(tool.getStatus())) {
            return unavailable("工具当前状态为"
                    + Dict.label(Dict.TOOL_STATUS, tool.getStatus()) + "，暂不可预约");
        }
        if (start == null || end == null || !start.isBefore(end)) {
            return unavailable("租赁开始时间必须早于结束时间");
        }
        if (!start.isAfter(LocalDateTime.now())) {
            return unavailable("租赁开始时间必须晚于当前时间");
        }
        Duration duration = Duration.between(start, end);
        LocalDateTime horizonEnd = start.plusDays(availabilitySearchDays);
        List<AvailabilityVO.TimeWindow> occupied = mergeOccupied(
                rentalMapper.selectConflicts(toolId, start, horizonEnd, pendingPaymentTimeoutMinutes));
        boolean available = !overlaps(occupied, start, end);
        List<AvailabilityVO.TimeWindow> freeWindows = freeWindows(occupied, start, end);
        if (available) {
            return new AvailabilityVO(true, "该时段可预约", List.of(), freeWindows, List.of());
        }
        List<AvailabilityVO.TimeWindow> suggestionWindows =
                suggestWindows(occupied, start, duration, horizonEnd);
        List<String> suggestions = suggestionWindows.stream()
                .map(window -> window.startTime().format(SUGGEST_FORMAT)
                        + " 至 " + window.endTime().format(SUGGEST_FORMAT))
                .toList();
        return new AvailabilityVO(false,
                suggestionWindows.isEmpty()
                        ? "该时段已被预约，近期暂无满足时长的空闲时段"
                        : "该时段已被预约，可尝试其他时间",
                suggestions, freeWindows, suggestionWindows);
    }

    private AvailabilityVO unavailable(String message) {
        return new AvailabilityVO(false, message, List.of(), List.of(), List.of());
    }

    /** 活动租赁区间按开始时间排序并合并，首尾相接视为连续占用。 */
    private List<AvailabilityVO.TimeWindow> mergeOccupied(List<Rental> rentals) {
        List<AvailabilityVO.TimeWindow> merged = new ArrayList<>();
        rentals.stream()
                .map(rental -> new AvailabilityVO.TimeWindow(rental.getStartTime(), rental.getEndTime()))
                .sorted(Comparator.comparing(AvailabilityVO.TimeWindow::startTime))
                .forEach(window -> {
                    if (merged.isEmpty()
                            || window.startTime().isAfter(merged.get(merged.size() - 1).endTime())) {
                        merged.add(window);
                        return;
                    }
                    AvailabilityVO.TimeWindow last = merged.remove(merged.size() - 1);
                    merged.add(new AvailabilityVO.TimeWindow(last.startTime(),
                            window.endTime().isAfter(last.endTime()) ? window.endTime() : last.endTime()));
                });
        return merged;
    }

    private boolean overlaps(List<AvailabilityVO.TimeWindow> occupied,
                             LocalDateTime start, LocalDateTime end) {
        return occupied.stream()
                .anyMatch(window -> window.startTime().isBefore(end) && window.endTime().isAfter(start));
    }

    /** 期望窗口减去占用区间后的真实空闲区间。 */
    private List<AvailabilityVO.TimeWindow> freeWindows(List<AvailabilityVO.TimeWindow> occupied,
                                                        LocalDateTime start, LocalDateTime end) {
        List<AvailabilityVO.TimeWindow> free = new ArrayList<>();
        LocalDateTime cursor = start;
        for (AvailabilityVO.TimeWindow window : occupied) {
            if (!window.startTime().isBefore(end)) {
                break;
            }
            if (window.endTime().isAfter(cursor)) {
                if (window.startTime().isAfter(cursor)) {
                    free.add(new AvailabilityVO.TimeWindow(cursor, window.startTime()));
                }
                cursor = window.endTime();
                if (!cursor.isBefore(end)) {
                    return free;
                }
            }
        }
        if (cursor.isBefore(end)) {
            free.add(new AvailabilityVO.TimeWindow(cursor, end));
        }
        return free;
    }

    /** 从期望开始时间起，按槽位粒度寻找满足所需时长的最近空闲窗口。 */
    private List<AvailabilityVO.TimeWindow> suggestWindows(List<AvailabilityVO.TimeWindow> occupied,
                                                           LocalDateTime from, Duration duration,
                                                           LocalDateTime horizonEnd) {
        List<AvailabilityVO.TimeWindow> suggestions = new ArrayList<>();
        LocalDateTime candidate = ceilToSlot(from);
        while (suggestions.size() < availabilityMaxSuggestions
                && !candidate.plus(duration).isAfter(horizonEnd)) {
            LocalDateTime windowStart = candidate;
            LocalDateTime candidateEnd = candidate.plus(duration);
            AvailabilityVO.TimeWindow blocker = occupied.stream()
                    .filter(window -> window.startTime().isBefore(candidateEnd)
                            && window.endTime().isAfter(windowStart))
                    .findFirst().orElse(null);
            if (blocker == null) {
                suggestions.add(new AvailabilityVO.TimeWindow(candidate, candidateEnd));
                candidate = ceilToSlot(candidateEnd);
            } else {
                candidate = ceilToSlot(blocker.endTime());
            }
        }
        return suggestions;
    }

    private LocalDateTime ceilToSlot(LocalDateTime time) {
        LocalDateTime minute = time.truncatedTo(ChronoUnit.MINUTES);
        if (minute.isBefore(time)) {
            minute = minute.plusMinutes(1);
        }
        long minuteOfDay = minute.getHour() * 60L + minute.getMinute();
        long remainder = minuteOfDay % availabilitySlotMinutes;
        return remainder == 0 ? minute : minute.plusMinutes(availabilitySlotMinutes - remainder);
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
                && tool.getAuditStatus() == AuditStatus.APPROVED.value();
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
        applyRating(vo, toolReviewMapper.selectStatsByToolId(tool.getId()));
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
        List<Long> toolIds = result.getRecords().stream().map(Tool::getId).toList();
        Map<Long, ToolRatingStats> ratings = toolIds.isEmpty() ? Map.of()
                : toolReviewMapper.selectStatsByToolIds(toolIds).stream()
                        .collect(Collectors.toMap(ToolRatingStats::getToolId, stats -> stats));
        return PageVO.of(result, tool -> {
            ToolVO vo = toolVoMapper.toVO(tool);
            vo.setCreatedByName(names.get(tool.getCreatedBy()));
            applyRating(vo, ratings.get(tool.getId()));
            return vo;
        });
    }

    /** 平均分保留一位小数；无评价时明确返回 0.0 与 0。 */
    private void applyRating(ToolVO vo, ToolRatingStats stats) {
        if (stats == null || stats.getReviewCount() == null || stats.getReviewCount() == 0) {
            vo.setRatingAvg(BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP));
            vo.setReviewCount(0L);
            return;
        }
        vo.setRatingAvg(stats.getRatingAvg().setScale(1, RoundingMode.HALF_UP));
        vo.setReviewCount(stats.getReviewCount());
    }
}
