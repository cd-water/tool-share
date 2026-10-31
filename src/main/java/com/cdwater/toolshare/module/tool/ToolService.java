package com.cdwater.toolshare.module.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.shared.sequence.SequenceService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cdwater.toolshare.shared.web.BizException;
import com.cdwater.toolshare.shared.web.Dict;
import com.cdwater.toolshare.shared.web.ResultCode;
import com.cdwater.toolshare.shared.config.StpKit;
import com.cdwater.toolshare.module.tool.ToolSaveDTO;
import com.cdwater.toolshare.module.rental.Rental;
import com.cdwater.toolshare.module.tool.Tool;
import com.cdwater.toolshare.module.tool.ToolLog;
import com.cdwater.toolshare.module.rental.RentalMapper;
import com.cdwater.toolshare.module.tool.ToolLogMapper;
import com.cdwater.toolshare.module.tool.ToolMapper;
import com.cdwater.toolshare.module.tool.AvailabilityVO;
import com.cdwater.toolshare.shared.web.PageVO;
import com.cdwater.toolshare.module.tool.ToolLogVO;
import com.cdwater.toolshare.module.tool.ToolVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ToolService {

    private static final DateTimeFormatter SUGGEST_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final List<String> ACTIVE_RENTAL_STATUS =
            List.of("PENDING_PAYMENT", "RESERVED", "PICKED_UP", "OVERDUE");

    private final ToolMapper toolMapper;
    private final ToolLogMapper toolLogMapper;
    private final RentalMapper rentalMapper;
    private final SequenceService sequenceService;

    public PageVO<ToolVO> userPage(String keyword, String category, String status, long page, long size) {
        LambdaQueryWrapper<Tool> wrapper = baseWrapper(keyword, category, status, null);
        if (!StringUtils.hasText(status)) {
            wrapper.ne(Tool::getStatus, "SCRAPPED");
        }
        Page<Tool> result = toolMapper.selectPage(new Page<>(page, clamp(size)), wrapper);
        return PageVO.of(result, this::toVO);
    }

    public PageVO<ToolVO> adminPage(String keyword, String category, String status,
                                    String storageArea, long page, long size) {
        LambdaQueryWrapper<Tool> wrapper = baseWrapper(keyword, category, status, storageArea);
        Page<Tool> result = toolMapper.selectPage(new Page<>(page, clamp(size)), wrapper);
        return PageVO.of(result, this::toVO);
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
        return wrapper.orderByDesc(Tool::getCreateTime);
    }

    public ToolVO getVO(Long id) {
        return toVO(getTool(id));
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
        tool.setAuditStatus(1);
        tool.setRentalCount(0);
        tool.setStatus(StringUtils.hasText(dto.getStatus()) ? dto.getStatus() : "AVAILABLE");
        tool.setCreatedBy(StpKit.ADMIN.getLoginIdAsLong());
        toolMapper.insert(tool);
        log(tool.getId(), "CREATE", "新增工具 " + tool.getCode());
        return toVO(tool);
    }

    @Transactional
    public ToolVO update(Long id, ToolSaveDTO dto) {
        validateDict(dto);
        Tool tool = getTool(id);
        apply(tool, dto);
        if (StringUtils.hasText(dto.getStatus())) {
            tool.setStatus(dto.getStatus());
        }
        toolMapper.updateById(tool);
        log(tool.getId(), "UPDATE", "编辑工具 " + tool.getCode());
        return toVO(tool);
    }

    @Transactional
    public void updateStatus(Long id, String status) {
        if (!Dict.containsKey(Dict.TOOL_STATUS, status)) {
            throw new BizException(ResultCode.BAD_REQUEST, "无效的工具状态");
        }
        Tool tool = getTool(id);
        String before = tool.getStatus();
        tool.setStatus(status);
        if ("AVAILABLE".equals(status)) {
            tool.setLastRepairTime(LocalDateTime.now());
        }
        toolMapper.updateById(tool);
        log(id, "STATUS_CHANGE",
                "状态由 " + Dict.label(Dict.TOOL_STATUS, before) + " 变为 " + Dict.label(Dict.TOOL_STATUS, status));
    }

    public List<ToolLogVO> logs(Long toolId) {
        return toolLogMapper.selectList(new LambdaQueryWrapper<ToolLog>()
                        .eq(ToolLog::getToolId, toolId)
                        .orderByDesc(ToolLog::getCreateTime))
                .stream().map(item -> {
                    ToolLogVO vo = new ToolLogVO();
                    vo.setId(item.getId());
                    vo.setToolId(item.getToolId());
                    vo.setAction(item.getAction());
                    vo.setDetail(item.getDetail());
                    vo.setOperatorId(item.getOperatorId());
                    vo.setCreateTime(item.getCreateTime());
                    return vo;
                }).toList();
    }

    public AvailabilityVO availability(Long toolId, LocalDateTime start, LocalDateTime end) {
        Tool tool = getTool(toolId);
        if (!"AVAILABLE".equals(tool.getStatus())) {
            return new AvailabilityVO(false,
                    "工具当前状态为" + Dict.label(Dict.TOOL_STATUS, tool.getStatus()) + "，暂不可预约",
                    List.of());
        }
        if (start == null || end == null || !start.isBefore(end)) {
            return new AvailabilityVO(false, "租赁开始时间必须早于结束时间", List.of());
        }
        List<Rental> conflicts = rentalMapper.selectList(new LambdaQueryWrapper<Rental>()
                .eq(Rental::getToolId, toolId)
                .in(Rental::getStatus, ACTIVE_RENTAL_STATUS)
                .lt(Rental::getStartTime, end)
                .gt(Rental::getEndTime, start)
                .orderByAsc(Rental::getEndTime));
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

    public void log(Long toolId, String action, String detail) {
        ToolLog log = new ToolLog();
        log.setToolId(toolId);
        log.setAction(action);
        log.setDetail(detail);
        log.setOperatorId(StpKit.ADMIN.isLogin() ? StpKit.ADMIN.getLoginIdAsLong() : null);
        toolLogMapper.insert(log);
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
        tool.setUsageGuide(dto.getUsageGuide());
        List<String> images = dto.getImageUrls() == null ? List.of() : dto.getImageUrls();
        tool.setImageUrls(String.join(",", images));
        tool.setImageUrl(images.isEmpty() ? "" : images.get(0));
        tool.setPurchaseDate(dto.getPurchaseDate());
        tool.setSupplier(dto.getSupplier() == null ? "" : dto.getSupplier().trim());
    }

    private ToolVO toVO(Tool tool) {
        ToolVO vo = new ToolVO();
        vo.setId(tool.getId());
        vo.setCode(tool.getCode());
        vo.setName(tool.getName());
        vo.setModel(tool.getModel());
        vo.setCategory(tool.getCategory());
        vo.setCategoryName(Dict.label(Dict.TOOL_CATEGORY, tool.getCategory()));
        vo.setDamageLevel(tool.getDamageLevel());
        vo.setDamageLevelName(Dict.label(Dict.DAMAGE_LEVEL, tool.getDamageLevel()));
        vo.setStatus(tool.getStatus());
        vo.setStatusName(Dict.label(Dict.TOOL_STATUS, tool.getStatus()));
        vo.setDeposit(tool.getDeposit());
        vo.setStorageArea(tool.getStorageArea());
        vo.setUsageGuide(tool.getUsageGuide());
        vo.setImageUrl(tool.getImageUrl());
        vo.setImageUrls(parseImages(tool.getImageUrls()));
        vo.setPurchaseDate(tool.getPurchaseDate());
        vo.setSupplier(tool.getSupplier());
        vo.setRentalCount(tool.getRentalCount());
        vo.setLastRepairTime(tool.getLastRepairTime());
        vo.setCreateTime(tool.getCreateTime());
        vo.setUpdateTime(tool.getUpdateTime());
        return vo;
    }

    private List<String> parseImages(String images) {
        if (!StringUtils.hasText(images)) {
            return List.of();
        }
        return Arrays.stream(images.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    private long clamp(long size) {
        return Math.min(Math.max(size, 1), 100);
    }
}
