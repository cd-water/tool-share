/**
 * 用户端工具接口文件。
 * <p>路径：/api/user/tools，提供列表、详情、历史租赁、可用性查询与蹲守操作。
 * <h2>可见性</h2>
 * <p>列表与详情由 ToolService 过滤审核状态；未审核工具按不存在处理。
 * 默认分页大小 12。
 * <h2>蹲守</h2>
 * <p>POST/DELETE /{id}/watch 登记或取消蹲守，GET 查询是否蹲守，
 * GET /watch/list 返回当前用户蹲守列表。
 * <h2>时间参数</h2>
 * <p>可用性查询使用 ISO-8601 日期时间格式，例如 2026-01-01T10:00:00。
 */
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

/**
 * 用户端工具接口。
 * <p>提供工具列表、详情、历史租赁、可用性查询与蹲守操作。
 * 列表与详情由服务层过滤审核状态；蹲守只要求用户登录态。
 */
@RestController
@RequestMapping("/api/user/tools")
@RequiredArgsConstructor
public class UserToolController {

    // 工具查询与可用性计算
    private final ToolService toolService;
    // 蹲守登记、取消与列表
    private final ToolWatchService toolWatchService;

    @GetMapping
    /**
     * 用户端工具分页。
     * <p>默认每页 12 条；只返回审核通过的工具，未指定状态时隐藏报废工具。
     *
     * @param keyword  关键字，匹配名称/型号/编号
     * @param category 类别编码
     * @param status   状态编码
     * @param orderBy  排序方式，rating 为评分优先
     * @param page     页码
     * @param size     每页条数
     * @return 工具分页结果
     */
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
    /**
     * 用户端工具详情，未审核工具按不存在处理。
     *
     * @param id 工具 id
     * @return 工具视图
     */
    public Result<ToolVO> detail(@PathVariable Long id) {
        return Result.success(toolService.getUserVO(id));
    }

    /** 历史租赁：只返回已完成租期窗口与次数，不暴露其他居民信息。 */
    @GetMapping("/{id}/history")
    /**
     * 查询工具历史租赁窗口。
     *
     * @param id 工具 id
     * @return 已归还租期窗口与次数
     */
    public Result<ToolRentalHistoryVO> history(@PathVariable Long id) {
        return Result.success(toolService.userRentalHistory(id));
    }

    @GetMapping("/{id}/availability")
    /**
     * 查询工具在指定时段的可用性。
     * <p>时间参数使用 ISO-8601 日期时间格式，例如 2026-01-01T10:00:00。
     *
     * @param id        工具 id
     * @param startTime 期望开始时间
     * @param endTime   期望结束时间
     * @return 可用性结论、空闲窗口与建议窗口
     */
    public Result<AvailabilityVO> availability(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return Result.success(toolService.availability(id, startTime, endTime));
    }

    /** 蹲守该工具（工具被占用时登记，恢复可用时站内提醒） */
    @PostMapping("/{id}/watch")
    /**
     * 登记蹲守工具；工具当前可用或不存在时拒绝。
     *
     * @param id 工具 id
     * @return 空成功响应
     */
    public Result<Void> watch(@PathVariable Long id) {
        toolWatchService.watch(StpKit.USER.getLoginIdAsLong(), id);
        return Result.success();
    }

    @DeleteMapping("/{id}/watch")
    /**
     * 取消蹲守，幂等。
     *
     * @param id 工具 id
     * @return 空成功响应
     */
    public Result<Void> unwatch(@PathVariable Long id) {
        toolWatchService.unwatch(StpKit.USER.getLoginIdAsLong(), id);
        return Result.success();
    }

    @GetMapping("/{id}/watch")
    /**
     * 查询当前用户是否已蹲守该工具。
     *
     * @param id 工具 id
     * @return 形如 {"watched": true} 的结果
     */
    public Result<Map<String, Boolean>> watched(@PathVariable Long id) {
        boolean watched = toolWatchService.isWatched(StpKit.USER.getLoginIdAsLong(), id);
        return Result.success(Map.of("watched", watched));
    }

    /** 我蹲守的工具列表 */
    @GetMapping("/watch/list")
    /**
     * 当前用户蹲守的工具列表。
     *
     * @return 蹲守工具视图列表
     */
    public Result<List<ToolWatchVO>> watchList() {
        return Result.success(toolWatchService.watchedList(StpKit.USER.getLoginIdAsLong()));
    }
}
