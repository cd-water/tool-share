package com.cdwater.toolshare.controller;

import com.cdwater.toolshare.common.Result;
import com.cdwater.toolshare.service.ToolService;
import com.cdwater.toolshare.vo.AvailabilityVO;
import com.cdwater.toolshare.vo.PageVO;
import com.cdwater.toolshare.vo.ToolVO;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/user/tools")
@RequiredArgsConstructor
public class UserToolController {

    private final ToolService toolService;

    @GetMapping
    public Result<PageVO<ToolVO>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "12") long size) {
        return Result.success(toolService.userPage(keyword, category, status, page, size));
    }

    @GetMapping("/{id}")
    public Result<ToolVO> detail(@PathVariable Long id) {
        return Result.success(toolService.getVO(id));
    }

    @GetMapping("/{id}/availability")
    public Result<AvailabilityVO> availability(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return Result.success(toolService.availability(id, startTime, endTime));
    }
}
