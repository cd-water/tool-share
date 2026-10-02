package com.cdwater.toolshare.system.controller;

import com.cdwater.toolshare.common.result.Result;
import com.cdwater.toolshare.system.dto.LoginRequest;
import com.cdwater.toolshare.system.dto.LoginResponse;
import com.cdwater.toolshare.system.dto.UserItem;
import com.cdwater.toolshare.system.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    /** 登录（匿名） */
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.success(authService.login(request));
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout();
        return Result.success();
    }

    @GetMapping("/me")
    public Result<UserItem> me() {
        return Result.success(authService.me());
    }
}
