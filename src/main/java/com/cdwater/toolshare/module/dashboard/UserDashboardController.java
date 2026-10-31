package com.cdwater.toolshare.module.dashboard;

import com.cdwater.toolshare.shared.config.StpKit;
import com.cdwater.toolshare.shared.web.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserDashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/dashboard")
    public Result<UserDashboardVO> dashboard() {
        return Result.success(dashboardService.userDashboard(StpKit.USER.getLoginIdAsLong()));
    }
}
