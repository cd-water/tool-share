package com.cdwater.toolshare.module.dashboard;

import com.cdwater.toolshare.shared.web.Result;
import com.cdwater.toolshare.module.dashboard.DashboardService;
import com.cdwater.toolshare.module.dashboard.AdminDashboardVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final DashboardService dashboardService;

    @GetMapping
    public Result<AdminDashboardVO> dashboard() {
        return Result.success(dashboardService.adminDashboard());
    }
}
