package com.cdwater.toolshare.repair.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.cdwater.toolshare.common.result.PageResult;
import com.cdwater.toolshare.common.result.Result;
import com.cdwater.toolshare.repair.dto.RepairAcceptRequest;
import com.cdwater.toolshare.repair.dto.RepairCreateRequest;
import com.cdwater.toolshare.repair.dto.RepairCreatedResponse;
import com.cdwater.toolshare.repair.dto.RepairDetail;
import com.cdwater.toolshare.repair.dto.RepairItem;
import com.cdwater.toolshare.repair.dto.RepairProgressRequest;
import com.cdwater.toolshare.repair.dto.RepairStartRequest;
import com.cdwater.toolshare.repair.dto.RepairVerifyRequest;
import com.cdwater.toolshare.repair.service.RepairService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/repair/orders")
public class RepairController {

    private final RepairService repairService;

    @SaCheckRole("RESIDENT")
    @PostMapping
    public Result<RepairCreatedResponse> create(@Valid @RequestBody RepairCreateRequest request) {
        return Result.success(repairService.create(request));
    }

    @SaCheckRole("RESIDENT")
    @GetMapping("/my")
    public Result<PageResult<RepairItem>> my(@RequestParam(defaultValue = "1") long page,
                                             @RequestParam(defaultValue = "10") long size,
                                             @RequestParam(required = false) String status) {
        return Result.success(repairService.my(page, size, status));
    }

    @SaCheckRole(value = {"STAFF", "ADMIN"}, mode = SaMode.OR)
    @GetMapping
    public Result<PageResult<RepairItem>> page(@RequestParam(defaultValue = "1") long page,
                                               @RequestParam(defaultValue = "10") long size,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) String toolCode) {
        return Result.success(repairService.page(page, size, status, toolCode));
    }

    @GetMapping("/{id}")
    public Result<RepairDetail> detail(@PathVariable Long id) {
        return Result.success(repairService.detail(id));
    }

    @SaCheckRole("STAFF")
    @PostMapping("/{id}/verify")
    public Result<Void> verify(@PathVariable Long id, @Valid @RequestBody RepairVerifyRequest request) {
        repairService.verify(id, request);
        return Result.success();
    }

    @SaCheckRole("STAFF")
    @PostMapping("/{id}/repair")
    public Result<Void> startRepair(@PathVariable Long id, @Valid @RequestBody RepairStartRequest request) {
        repairService.startRepair(id, request);
        return Result.success();
    }

    @SaCheckRole("STAFF")
    @PostMapping("/{id}/progress")
    public Result<Void> addProgress(@PathVariable Long id, @Valid @RequestBody RepairProgressRequest request) {
        repairService.addProgress(id, request);
        return Result.success();
    }

    @SaCheckRole("STAFF")
    @PostMapping("/{id}/accept")
    public Result<Void> accept(@PathVariable Long id, @Valid @RequestBody RepairAcceptRequest request) {
        repairService.accept(id, request);
        return Result.success();
    }
}
