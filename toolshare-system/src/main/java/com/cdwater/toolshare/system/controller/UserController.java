package com.cdwater.toolshare.system.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.cdwater.toolshare.common.result.PageResult;
import com.cdwater.toolshare.common.result.Result;
import com.cdwater.toolshare.system.dto.UserItem;
import com.cdwater.toolshare.system.dto.UserStatusRequest;
import com.cdwater.toolshare.system.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/system/users")
public class UserController {

    private final UserService userService;

    @SaCheckRole("ADMIN")
    @GetMapping
    public Result<PageResult<UserItem>> page(@RequestParam(defaultValue = "1") long page,
                                             @RequestParam(defaultValue = "10") long size,
                                             @RequestParam(required = false) String role,
                                             @RequestParam(required = false) String keyword) {
        return Result.success(userService.page(page, size, role, keyword));
    }

    @SaCheckRole("ADMIN")
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id, @Valid @RequestBody UserStatusRequest request) {
        userService.updateStatus(id, request.getStatus());
        return Result.success();
    }
}
