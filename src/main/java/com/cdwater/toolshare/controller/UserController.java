package com.cdwater.toolshare.controller;

import com.cdwater.toolshare.common.Result;
import com.cdwater.toolshare.dto.CodeLoginDTO;
import com.cdwater.toolshare.dto.PwdLoginDTO;
import com.cdwater.toolshare.dto.SendCodeDTO;
import com.cdwater.toolshare.service.UserService;
import com.cdwater.toolshare.vo.LoginVO;
import com.cdwater.toolshare.vo.UserVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/send-code")
    public Result<String> sendCode(@Valid @RequestBody SendCodeDTO dto) {
        return Result.success(userService.sendCode(dto.getPhone()));
    }

    @PostMapping("/login/by-code")
    public Result<LoginVO> loginByCode(@Valid @RequestBody CodeLoginDTO dto) {
        return Result.success(userService.loginByCode(dto));
    }

    @PostMapping("/login/by-pwd")
    public Result<LoginVO> loginByPwd(@Valid @RequestBody PwdLoginDTO dto) {
        return Result.success(userService.loginByPwd(dto));
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        userService.logout();
        return Result.success();
    }

    @GetMapping("/info")
    public Result<UserVO> info() {
        return Result.success(userService.info());
    }
}
