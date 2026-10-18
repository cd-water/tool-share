package com.cdwater.toolshare.tool.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cdwater.toolshare.common.enums.ResultCode;
import com.cdwater.toolshare.common.exception.BizException;
import com.cdwater.toolshare.common.result.PageResult;
import com.cdwater.toolshare.tool.dto.ToolAuditDTO;
import com.cdwater.toolshare.tool.dto.ToolDTO;
import com.cdwater.toolshare.tool.dto.ToolQueryDTO;
import com.cdwater.toolshare.tool.dto.ToolStatusDTO;
import com.cdwater.toolshare.tool.entity.Tool;
import com.cdwater.toolshare.tool.entity.ToolStatusLog;
import com.cdwater.toolshare.tool.mapper.ToolMapper;
import com.cdwater.toolshare.tool.mapper.ToolStatusLogMapper;
import com.cdwater.toolshare.tool.service.ToolService;
import com.cdwater.toolshare.tool.util.ToolCodeGenerator;
import com.cdwater.toolshare.tool.vo.ToolCreateVO;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 工具信息业务实现
 */
@Service
@RequiredArgsConstructor
public class ToolServiceImpl implements ToolService {

    /** 编号冲突时重试次数（Redis 计数器丢失后回填再试） */
    private static final int MAX_CODE_RETRY = 3;

    private final ToolMapper toolMapper;
    private final ToolStatusLogMapper toolStatusLogMapper;
    private final ToolCodeGenerator toolCodeGenerator;

    @Override
    public PageResult<Tool> page(ToolQueryDTO query) {
        QueryWrapper<Tool> wrapper = new QueryWrapper<>();
        if (StrUtil.isNotBlank(query.getKeyword())) {
            String keyword = query.getKeyword();
            wrapper.and(w -> w.like("name", keyword).or().like("model_spec", keyword));
        }
        wrapper.eq(query.getCategory() != null, "category", query.getCategory())
                .eq(query.getStatus() != null, "status", query.getStatus())
                .like(StrUtil.isNotBlank(query.getStorageLocation()), "storage_location", query.getStorageLocation());

        if ("rentalCount".equals(query.getOrderBy())) {
            // 租赁次数：按工具关联的租赁单数量降序
            wrapper.orderByDesc("(SELECT COUNT(*) FROM rental_order ro WHERE ro.tool_id = tool.id)", "id");
        } else if ("lastRepairTime".equals(query.getOrderBy())) {
            // 最近维修时间：取最近一次维修活动的最大时间
            wrapper.orderByDesc("(SELECT MAX(COALESCE(ro.accept_time, ro.repair_start_time, ro.create_time)) "
                    + "FROM repair_order ro WHERE ro.tool_id = tool.id)", "id");
        } else {
            wrapper.orderByDesc("create_time", "id");
        }

        Page<Tool> page = query.toPage();
        toolMapper.selectPage(page, wrapper);
        return PageResult.of(page);
    }

    @Override
    public Tool detail(Long id) {
        return getToolOrThrow(id);
    }

    @Override
    @Transactional
    public ToolCreateVO create(ToolDTO dto) {
        Tool tool = new Tool();
        applyBaseInfo(tool, dto);
        tool.setStatus(1);
        tool.setAuditStatus(1);
        tool.setCreateBy(StpUtil.getLoginIdAsLong());

        for (int i = 0; i < MAX_CODE_RETRY; i++) {
            tool.setToolCode(toolCodeGenerator.next(dto.getCategory()));
            try {
                toolMapper.insert(tool);
                ToolCreateVO vo = new ToolCreateVO();
                vo.setId(tool.getId());
                vo.setToolCode(tool.getToolCode());
                return vo;
            } catch (DuplicateKeyException e) {
                // 计数器落后于库中已有编号（如 Redis 重启），回填后重试
                toolCodeGenerator.syncFromDatabase(dto.getCategory());
            }
        }
        throw new BizException(ResultCode.CONFLICT.getCode(), "工具编号生成冲突，请重试");
    }

    @Override
    public void update(Long id, ToolDTO dto) {
        getToolOrThrow(id);
        // 编号不可修改，仅更新基础信息
        Tool update = new Tool();
        update.setId(id);
        applyBaseInfo(update, dto);
        toolMapper.updateById(update);
    }

    @Override
    @Transactional
    public void updateStatus(Long id, ToolStatusDTO dto) {
        Tool tool = getToolOrThrow(id);

        Tool update = new Tool();
        update.setId(id);
        update.setStatus(dto.getStatus());
        toolMapper.updateById(update);

        ToolStatusLog log = new ToolStatusLog();
        log.setToolId(id);
        log.setOldStatus(tool.getStatus());
        log.setNewStatus(dto.getStatus());
        log.setOperatorId(StpUtil.getLoginIdAsLong());
        log.setRemark(dto.getRemark());
        toolStatusLogMapper.insert(log);
    }

    @Override
    public void updateDamageLevel(Long id, Integer damageLevel) {
        getToolOrThrow(id);
        if (damageLevel == null || damageLevel < 1 || damageLevel > 4) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(), "损坏程度取值不正确");
        }
        Tool update = new Tool();
        update.setId(id);
        update.setDamageLevel(damageLevel);
        toolMapper.updateById(update);
    }

    @Override
    public void audit(Long id, ToolAuditDTO dto) {
        getToolOrThrow(id);

        boolean needsRevision = dto.getAuditStatus() != null && dto.getAuditStatus() == 3;
        if (needsRevision && StrUtil.isBlank(dto.getAuditOpinion())) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(), "需修改时必须填写修改意见");
        }

        Tool update = new Tool();
        update.setAuditStatus(dto.getAuditStatus());
        update.setAuditOpinion(needsRevision ? dto.getAuditOpinion() : null);
        // 审核通过时清空历史修改意见
        toolMapper.update(update, new LambdaUpdateWrapper<Tool>()
                .eq(Tool::getId, id)
                .set(Tool::getAuditOpinion, update.getAuditOpinion()));
    }

    @Override
    public List<ToolStatusLog> statusLogs(Long id) {
        return toolStatusLogMapper.selectList(new LambdaQueryWrapper<ToolStatusLog>()
                .eq(ToolStatusLog::getToolId, id)
                .orderByDesc(ToolStatusLog::getCreateTime)
                .orderByDesc(ToolStatusLog::getId));
    }

    private Tool getToolOrThrow(Long id) {
        Tool tool = toolMapper.selectById(id);
        if (tool == null) {
            throw new BizException(ResultCode.NOT_FOUND.getCode(), "工具不存在");
        }
        return tool;
    }

    private void applyBaseInfo(Tool tool, ToolDTO dto) {
        tool.setName(dto.getName());
        tool.setModelSpec(dto.getModelSpec());
        tool.setCategory(dto.getCategory());
        tool.setDamageLevel(dto.getDamageLevel());
        tool.setDeposit(dto.getDeposit());
        tool.setStorageLocation(dto.getStorageLocation());
        tool.setUsageInstruction(dto.getUsageInstruction());
        tool.setImages(dto.getImages());
        tool.setPurchaseDate(dto.getPurchaseDate());
        tool.setSupplierInfo(dto.getSupplierInfo());
    }
}
