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

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserReviewController {

    private final ReviewService reviewService;

    @PostMapping("/reviews")
    public Result<ReviewVO> create(@Valid @RequestBody ReviewCreateDTO dto) {
        return Result.success(reviewService.create(dto));
    }

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
        return Result.success(reviewService.toolPage(toolId, page, size));
    }
}
