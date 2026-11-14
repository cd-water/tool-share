package com.cdwater.toolshare.module.review;

import com.cdwater.toolshare.shared.web.PageVO;
import com.cdwater.toolshare.shared.web.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端评价接口。
 *
 * <p>提供三类操作：提交评价、查看“我的评价”、查看某个工具的公开评价。
 * 评价只能由本人针对已归还租赁单提交，且一单一评、不可修改；工具评价
 * 页对所有已登录用户开放，便于租前参考。</p>
 *
 * <p>所有路径位于 {@code /api/user/**} 下，由 Sa-Token 用户端拦截器
 * 统一校验登录态。请求体使用 Bean Validation 做基础格式校验，业务
 * 归属与状态校验在 {@link ReviewService} 中完成。</p>
 *
 * <p>三个接口都返回统一的 {@code Result} 包装；提交接口在成功时返回
 * 新建评价，便于前端立即插入列表。查询接口均为只读且幂等。</p>
 *
 * <p>提交接口的幂等性由数据库唯一键保证：重复提交同一租赁单会得到
 * 冲突错误，而不是创建第二条评价。</p>
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserReviewController {

    /** 评价业务服务，负责归属校验、快照落库与信用事件联动。 */
    private final ReviewService reviewService;

    /**
     * 提交一条评价。
     *
     * @param dto 评价请求体，已通过基础校验
     * @return 新建评价的视图对象
     */
    @PostMapping("/reviews")
    public Result<ReviewVO> create(@Valid @RequestBody ReviewCreateDTO dto) {
        // 用户 ID 由服务层从会话读取，请求体无法指定评价人。
        return Result.success(reviewService.create(dto));
    }

    /**
     * 分页查看当前用户提交过的评价。
     *
     * @param page 页码，默认 1
     * @param size 每页条数，默认 10，最大 100
     * @return 当前用户评价分页
     */
    @GetMapping("/reviews")
    public Result<PageVO<ReviewVO>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return Result.success(reviewService.userPage(page, size));
    }

    /** 工具评价公开只读（需用户端登录） */
    @GetMapping("/tools/{toolId}/reviews")
    public Result<PageVO<ReviewVO>> toolReviews(
            @PathVariable Long toolId,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        // 先校验工具存在，再返回该工具的评价分页。
        // 评价内容中的 HTML 已在写入时净化，读取时可直接返回。
        return Result.success(reviewService.toolPage(toolId, page, size));
    }
}
