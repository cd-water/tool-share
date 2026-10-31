package com.cdwater.toolshare.controller;

import com.cdwater.toolshare.common.Result;
import com.cdwater.toolshare.config.StpKit;
import com.cdwater.toolshare.dto.ProfileUpdateDTO;
import com.cdwater.toolshare.service.DashboardService;
import com.cdwater.toolshare.service.UserService;
import com.cdwater.toolshare.vo.UserDashboardVO;
import com.cdwater.toolshare.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserProfileController {

    private final UserService userService;
    private final DashboardService dashboardService;

    @GetMapping("/profile")
    public Result<UserVO> profile() {
        return Result.success(userService.profile());
    }

    @PutMapping("/profile")
    public Result<UserVO> update(@RequestBody ProfileUpdateDTO dto) {
        return Result.success(userService.updateProfile(dto));
    }

    @GetMapping("/dashboard")
    public Result<UserDashboardVO> dashboard() {
        return Result.success(dashboardService.userDashboard(StpKit.USER.getLoginIdAsLong()));
    }
}
