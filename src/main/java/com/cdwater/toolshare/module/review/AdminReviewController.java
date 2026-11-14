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
@RestController
@RequestMapping("/api/admin/reviews")
@RequiredArgsConstructor
@SaCheckPermission(type = "admin", value = "rental:manage")
public class AdminReviewController {

    private final ReviewService reviewService;

    @GetMapping
    public Result<PageVO<ReviewVO>> list(
            @RequestParam(required = false) Long toolId,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return Result.success(reviewService.adminPage(toolId, page, size));
    }
}
