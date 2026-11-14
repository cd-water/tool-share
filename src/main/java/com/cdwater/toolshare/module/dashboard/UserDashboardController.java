package com.cdwater.toolshare.module.dashboard;

import com.cdwater.toolshare.shared.config.StpKit;
import com.cdwater.toolshare.shared.web.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端个人看板接口。
 *
 * <p>返回当前登录用户最关心的四个数字：可借用工具数、待支付订单数、
 * 使用中订单数与未读消息数。用户 ID 来自 Sa-Token 会话，接口不接受
 * 用户参数，避免查询他人数据。</p>
 *
 * <p>接口为只读聚合，幂等且无副作用；统计口径由 {@link DashboardService}
 * 统一维护，与工具列表、订单列表保持一致。</p>
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserDashboardController {

    /** 看板聚合服务，同时服务用户端与管理端两种视角。 */
    private final DashboardService dashboardService;

    /**
     * 返回当前登录用户的个人看板指标。
     *
     * @return 用户端看板指标快照
     */
    @GetMapping("/dashboard")
    public Result<UserDashboardVO> dashboard() {
        // 登录 ID 直接由会话取得，前端无法伪造 userId。
        return Result.success(dashboardService.userDashboard(StpKit.USER.getLoginIdAsLong()));
    }
}
