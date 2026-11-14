package com.cdwater.toolshare.module.review;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.cdwater.toolshare.shared.web.PageVO;
import com.cdwater.toolshare.shared.web.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 管理端只读查看评价，不提供删改。 */
/*
 * <p>权限点复用 {@code rental:manage}，因为评价与租赁订单强相关，现有
 * 工作人员角色已具备该权限，无需新增权限点。接口支持按工具过滤，
 * 便于运营排查某个工具的口碑问题。</p>
 * <p>分页参数由统一的分页组件校正，非法值不会导致查询异常。</p>
 *
 * <p>管理端同样不提供评价修改：评价是用户产生的内容，运营只能查看，
 * 不能替用户改写或删除。</p>
 */
@RestController
@RequestMapping("/api/admin/reviews")
@RequiredArgsConstructor
@SaCheckPermission(type = "admin", value = "rental:manage")
public class AdminReviewController {

    /** 评价业务服务，负责分页查询与实体转换。 */
    private final ReviewService reviewService;

    /**
     * 分页查询评价；可按工具过滤。
     *
     * <p>管理端不提供修改或删除：评价一经提交即作为历史快照保留，
     * 避免运营人员影响用户口碑数据。toolId 为空时返回全部工具的评价。</p>
     *
     * @param toolId 可选工具 ID，用于只看某个工具的评价
     * @param page   页码，默认 1，由 PageQuery 收敛
     * @param size   每页条数，默认 10，最大 100
     * @return 评价分页结果
     */
    @GetMapping
    public Result<PageVO<ReviewVO>> list(
            @RequestParam(required = false) Long toolId,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        // 只读查询，不改变任何评价数据。
        return Result.success(reviewService.adminPage(toolId, page, size));
    }
}
