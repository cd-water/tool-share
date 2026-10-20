package com.cdwater.toolshare.repair.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.cdwater.toolshare.common.constant.RoleConstant;
import com.cdwater.toolshare.common.result.PageResult;
import com.cdwater.toolshare.common.result.Result;
import com.cdwater.toolshare.repair.dto.RepairAcceptDTO;
import com.cdwater.toolshare.repair.dto.RepairCreateDTO;
import com.cdwater.toolshare.repair.dto.RepairProgressDTO;
import com.cdwater.toolshare.repair.dto.RepairQueryDTO;
import com.cdwater.toolshare.repair.dto.RepairVerifyDTO;
import com.cdwater.toolshare.repair.service.RepairService;
import com.cdwater.toolshare.repair.vo.RepairCreateVO;
import com.cdwater.toolshare.repair.vo.RepairOrderVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 损坏报修接口
 */
@RestController
@RequestMapping("/api/repair")
@RequiredArgsConstructor
public class RepairController {

    private final RepairService repairService;

    /** 提交报修申请 */
    @SaCheckRole(RoleConstant.RESIDENT)
    @PostMapping
    public Result<RepairCreateVO> create(@Valid @RequestBody RepairCreateDTO dto) {
        return Result.success(repairService.create(dto));
    }

    /** 报修单分页 */
    @GetMapping("/page")
    public Result<PageResult<RepairOrderVO>> page(@Valid RepairQueryDTO query) {
        return Result.success(repairService.page(query));
    }

    /** 报修单详情（含维修进度） */
    @GetMapping("/{id}")
    public Result<RepairOrderVO> detail(@PathVariable Long id) {
        return Result.success(repairService.detail(id));
    }

    /** 现场核验 */
    @SaCheckRole(value = {RoleConstant.STAFF, RoleConstant.ADMIN}, mode = SaMode.OR)
    @PostMapping("/{id}/verify")
    public Result<Void> verify(@PathVariable Long id, @Valid @RequestBody RepairVerifyDTO dto) {
        repairService.verify(id, dto);
        return Result.success();
    }

    /** 更新维修进度 */
    @SaCheckRole(value = {RoleConstant.STAFF, RoleConstant.ADMIN}, mode = SaMode.OR)
    @PostMapping("/{id}/progress")
    public Result<Void> progress(@PathVariable Long id, @Valid @RequestBody RepairProgressDTO dto) {
        repairService.progress(id, dto);
        return Result.success();
    }

    /** 维修验收 */
    @SaCheckRole(value = {RoleConstant.STAFF, RoleConstant.ADMIN}, mode = SaMode.OR)
    @PostMapping("/{id}/accept")
    public Result<Void> accept(@PathVariable Long id, @Valid @RequestBody RepairAcceptDTO dto) {
        repairService.accept(id, dto);
        return Result.success();
    }
}
