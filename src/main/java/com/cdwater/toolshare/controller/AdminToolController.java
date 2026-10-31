package com.cdwater.toolshare.controller;

import com.cdwater.toolshare.common.Result;
import com.cdwater.toolshare.dto.ToolSaveDTO;
import com.cdwater.toolshare.dto.ToolStatusDTO;
import com.cdwater.toolshare.service.ToolService;
import com.cdwater.toolshare.vo.PageVO;
import com.cdwater.toolshare.vo.ToolLogVO;
import com.cdwater.toolshare.vo.ToolVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/tools")
@RequiredArgsConstructor
public class AdminToolController {

    private final ToolService toolService;

    @GetMapping
    public Result<PageVO<ToolVO>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String storageArea,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return Result.success(toolService.adminPage(keyword, category, status, storageArea, page, size));
    }

    @PostMapping
    public Result<ToolVO> create(@Valid @RequestBody ToolSaveDTO dto) {
        return Result.success(toolService.create(dto));
    }

    @PutMapping("/{id}")
    public Result<ToolVO> update(@PathVariable Long id, @Valid @RequestBody ToolSaveDTO dto) {
        return Result.success(toolService.update(id, dto));
    }

    @PatchMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id, @Valid @RequestBody ToolStatusDTO dto) {
        toolService.updateStatus(id, dto.getStatus());
        return Result.success();
    }

    @GetMapping("/{id}/logs")
    public Result<List<ToolLogVO>> logs(@PathVariable Long id) {
        return Result.success(toolService.logs(id));
    }
}
