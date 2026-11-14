package com.cdwater.toolshare.module.tool;

import com.cdwater.toolshare.shared.config.StpKit;
import com.cdwater.toolshare.shared.web.PageVO;
import com.cdwater.toolshare.shared.web.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/user/tools")
@RequiredArgsConstructor
public class UserToolController {

    private final ToolService toolService;
    private final ToolWatchService toolWatchService;

    @GetMapping
    public Result<PageVO<ToolVO>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String orderBy,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "12") long size) {
        return Result.success(toolService.userPage(keyword, category, status, orderBy, page, size));
    }

    @GetMapping("/{id}")
    public Result<ToolVO> detail(@PathVariable Long id) {
        return Result.success(toolService.getUserVO(id));
    }

    @GetMapping("/{id}/availability")
    public Result<AvailabilityVO> availability(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return Result.success(toolService.availability(id, startTime, endTime));
    }

    /** 蹲守该工具（工具被占用时登记，恢复可用时站内提醒） */
    @PostMapping("/{id}/watch")
    public Result<Void> watch(@PathVariable Long id) {
        toolWatchService.watch(StpKit.USER.getLoginIdAsLong(), id);
        return Result.success();
    }

    @DeleteMapping("/{id}/watch")
    public Result<Void> unwatch(@PathVariable Long id) {
        toolWatchService.unwatch(StpKit.USER.getLoginIdAsLong(), id);
        return Result.success();
    }

    @GetMapping("/{id}/watch")
    public Result<Map<String, Boolean>> watched(@PathVariable Long id) {
        boolean watched = toolWatchService.isWatched(StpKit.USER.getLoginIdAsLong(), id);
        return Result.success(Map.of("watched", watched));
    }

    /** 我蹲守的工具列表 */
    @GetMapping("/watch/list")
    public Result<List<ToolWatchVO>> watchList() {
        return Result.success(toolWatchService.watchedList(StpKit.USER.getLoginIdAsLong()));
    }
}
