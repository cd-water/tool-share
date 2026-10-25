package com.cdwater.toolshare.tool.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.cdwater.toolshare.common.constant.RoleConstant;
import com.cdwater.toolshare.common.result.PageResult;
import com.cdwater.toolshare.common.result.Result;
import com.cdwater.toolshare.tool.dto.ToolAuditDTO;
import com.cdwater.toolshare.tool.dto.ToolDTO;
import com.cdwater.toolshare.tool.dto.ToolQueryDTO;
import com.cdwater.toolshare.tool.dto.ToolStatusDTO;
import com.cdwater.toolshare.tool.entity.Tool;
import com.cdwater.toolshare.tool.entity.ToolStatusLog;
import com.cdwater.toolshare.tool.service.ToolService;
import com.cdwater.toolshare.tool.vo.ToolCreateVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工具信息接口
 */
@RestController
@RequestMapping("/api/tool")
@RequiredArgsConstructor
public class ToolController {

    private final ToolService toolService;

    /** 分页筛选 */
    @GetMapping("/page")
    public Result<PageResult<Tool>> page(@Valid ToolQueryDTO query) {
        return Result.success(toolService.page(query));
    }

    /** 工具详情 */
    @GetMapping("/{id}")
    public Result<Tool> detail(@PathVariable Long id) {
        return Result.success(toolService.detail(id));
    }

    /** 创建工具（编号自动生成） */
    @SaCheckRole(RoleConstant.STAFF)
    @PostMapping
    public Result<ToolCreateVO> create(@Valid @RequestBody ToolDTO dto) {
        return Result.success(toolService.create(dto));
    }

    /** 更新工具信息 */
    @SaCheckRole(RoleConstant.STAFF)
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody ToolDTO dto) {
        toolService.update(id, dto);
        return Result.success();
    }

    /** 更新工具状态（自动记录状态日志） */
    @SaCheckRole(RoleConstant.STAFF)
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestBody ToolStatusDTO dto) {
        toolService.updateStatus(id, dto);
        return Result.success();
    }

    /** 录入审核 */
    @SaCheckRole(RoleConstant.ADMIN)
    @PutMapping("/{id}/audit")
    public Result<Void> audit(@PathVariable Long id, @RequestBody ToolAuditDTO dto) {
        toolService.audit(id, dto);
        return Result.success();
    }

    /** 工具状态变更日志 */
    @SaCheckRole(RoleConstant.STAFF)
    @GetMapping("/{id}/status-logs")
    public Result<List<ToolStatusLog>> statusLogs(@PathVariable Long id) {
        return Result.success(toolService.statusLogs(id));
    }
}
