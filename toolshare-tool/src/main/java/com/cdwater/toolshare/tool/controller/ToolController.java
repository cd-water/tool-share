package com.cdwater.toolshare.tool.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.cdwater.toolshare.common.result.PageResult;
import com.cdwater.toolshare.common.result.Result;
import com.cdwater.toolshare.tool.dto.AvailabilityResponse;
import com.cdwater.toolshare.tool.dto.CategoryItem;
import com.cdwater.toolshare.tool.dto.ToolAuditRequest;
import com.cdwater.toolshare.tool.dto.ToolCreateRequest;
import com.cdwater.toolshare.tool.dto.ToolCreatedResponse;
import com.cdwater.toolshare.tool.dto.ToolDetail;
import com.cdwater.toolshare.tool.dto.ToolImageItem;
import com.cdwater.toolshare.tool.dto.ToolImageRequest;
import com.cdwater.toolshare.tool.dto.ToolItem;
import com.cdwater.toolshare.tool.dto.ToolQuery;
import com.cdwater.toolshare.tool.dto.ToolStatusRequest;
import com.cdwater.toolshare.tool.service.ToolService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/tool")
public class ToolController {

    private final ToolService toolService;

    @GetMapping("/categories")
    public Result<List<CategoryItem>> categories() {
        return Result.success(toolService.categories());
    }

    @GetMapping("/tools")
    public Result<PageResult<ToolItem>> page(ToolQuery query) {
        return Result.success(toolService.page(query));
    }

    @GetMapping("/tools/{id}")
    public Result<ToolDetail> detail(@PathVariable Long id) {
        return Result.success(toolService.detail(id));
    }

    @GetMapping("/tools/{id}/availability")
    public Result<AvailabilityResponse> availability(@PathVariable Long id,
                                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Result.success(toolService.availability(id, date));
    }

    @SaCheckRole(value = {"STAFF", "ADMIN"}, mode = SaMode.OR)
    @PostMapping("/tools")
    public Result<ToolCreatedResponse> create(@Valid @RequestBody ToolCreateRequest request) {
        return Result.success(toolService.create(request));
    }

    @SaCheckRole("ADMIN")
    @PostMapping("/tools/{id}/audit")
    public Result<Void> audit(@PathVariable Long id, @Valid @RequestBody ToolAuditRequest request) {
        toolService.audit(id, request);
        return Result.success();
    }

    @SaCheckRole(value = {"STAFF", "ADMIN"}, mode = SaMode.OR)
    @PostMapping("/tools/{id}/images")
    public Result<List<ToolImageItem>> bindImages(@PathVariable Long id, @Valid @RequestBody ToolImageRequest request) {
        return Result.success(toolService.bindImages(id, request));
    }

    @SaCheckRole(value = {"STAFF", "ADMIN"}, mode = SaMode.OR)
    @DeleteMapping("/images/{imageId}")
    public Result<Void> removeImage(@PathVariable Long imageId) {
        toolService.removeImage(imageId);
        return Result.success();
    }

    @SaCheckRole(value = {"STAFF", "ADMIN"}, mode = SaMode.OR)
    @PutMapping("/tools/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @Valid @RequestBody ToolStatusRequest request) {
        toolService.changeStatus(id, request);
        return Result.success();
    }
}
