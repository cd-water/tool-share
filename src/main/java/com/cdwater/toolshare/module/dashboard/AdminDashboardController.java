package com.cdwater.toolshare.module.dashboard;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.cdwater.toolshare.shared.web.Result;
import com.cdwater.toolshare.module.dashboard.DashboardService;
import com.cdwater.toolshare.module.dashboard.AdminDashboardVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端运营看板接口。
 *
 * <p>只提供一个只读聚合接口，返回工具、租赁、报修、用户与今日订单的
 * 汇总数字。权限点固定为 {@code dashboard:view}：工作人员与系统管理员
 * 都拥有该权限，因此两类账号都可查看，但都不能通过本接口修改任何数据。</p>
 *
 * <p>聚合查询在 {@link DashboardService} 中完成，控制器不包含统计口径，
 * 避免同一指标在不同入口出现不一致。响应统一使用 {@code Result} 包装。
 * 接口只读且幂等，可安全用于前端定时刷新与运维巡检。</p>
 */
@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
@SaCheckPermission(type = "admin", value = "dashboard:view")
public class AdminDashboardController {

    /** 看板聚合服务，负责各维度的计数查询与口径收敛。 */
    private final DashboardService dashboardService;

    /**
     * 返回管理端看板全部指标。
     *
     * <p>无请求参数；统计时间以服务器当前时间为准。该接口是只读且幂等的，
     * 前端可定时轮询刷新。</p>
     *
     * @return 管理端看板指标快照
     */
    @GetMapping
    public Result<AdminDashboardVO> dashboard() {
        return Result.success(dashboardService.adminDashboard());
    }
}
