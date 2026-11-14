package com.cdwater.toolshare.module.dashboard;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.module.message.MessageService;
import com.cdwater.toolshare.module.damage.DamageReport;
import com.cdwater.toolshare.module.rental.Rental;
import com.cdwater.toolshare.module.tool.Tool;
import com.cdwater.toolshare.module.auth.User;
import com.cdwater.toolshare.module.damage.DamageReportMapper;
import com.cdwater.toolshare.module.rental.RentalMapper;
import com.cdwater.toolshare.module.tool.ToolMapper;
import com.cdwater.toolshare.module.auth.UserMapper;
import com.cdwater.toolshare.module.dashboard.AdminDashboardVO;
import com.cdwater.toolshare.module.dashboard.UserDashboardVO;
import com.cdwater.toolshare.shared.enums.AuditStatus;
import com.cdwater.toolshare.shared.enums.ReportStatus;
import com.cdwater.toolshare.shared.enums.RentalStatus;
import com.cdwater.toolshare.shared.enums.ToolStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 双端看板聚合服务。
 *
 * <p>用户端返回与“我”相关的待支付、使用中与未读消息数；管理端返回
 * 全平台工具、租赁、报修与用户的汇总指标。所有指标都在请求时实时查询，
 * 不落统计表，因此数据永远与业务表一致，代价是看板请求会执行多次
 * count 查询。</p>
 *
 * <p>统计口径集中在本类，避免控制器或前端各自计算导致同一指标在不同
 * 页面不一致。跨模块依赖只使用各模块的 Mapper 与消息服务，不反向调用
 * 业务写服务，看板因此天然是只读的。</p>
 *
 * <p>逾期数按当前时间动态推导，不依赖定时任务物化，避免出现“已逾期但
 * 看板还未刷新”的滞后。若后续数据量增大，可在此处增加短 TTL 缓存，
 * 但不建议把口径下移到 SQL 视图之外。</p>
 *
 * <p>读一致性：所有 count 查询不在同一事务快照内，极端并发下各数字
 * 可能来自略微不同的时间点。看板只用于展示趋势，不参与资金或状态
 * 判断，因此接受这种弱一致。</p>
 *
 * <p>性能边界：管理端一次请求约执行八次 count，依赖状态列与创建时间
 * 索引；数据量达到百万级前无需物化。新增指标时应优先复用现有查询，
 * 避免为单个数字引入新的联表扫描。</p>
 *
 * <p>为什么不做互斥拆分：运营需要同时看到“总资产”“可借”“已租出”
 * “维修中”四个独立口径，重叠比强行加总更符合实际理解。</p>
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    /** 工具表访问入口，用于统计总数与各状态数量。 */
    private final ToolMapper toolMapper;

    /** 租赁表访问入口，用于统计活跃、逾期与今日订单。 */
    private final RentalMapper rentalMapper;

    /** 报修表访问入口，用于统计待处理报修数量。 */
    private final DamageReportMapper damageReportMapper;

    /** 用户表访问入口，用于统计平台用户总数。 */
    private final UserMapper userMapper;

    /**
     * 消息服务，用于读取当前用户未读消息数。
     * 只调用只读的计数方法，不会改变消息已读状态。
     */
    private final MessageService messageService;

    /**
     * 构建当前用户的个人看板。
     *
     * <p>只看用户自己的订单与消息；可借用工具数是全平台口径，帮助用户
     * 发现可租物品。待支付与使用中互不重叠，使用中同时包含已逾期，
     * 因此两个数字相加不会漏掉逾期订单。</p>
     *
     * <p>可借用数要求审核通过，因此刚提交审核的工具不会提前出现在首页。
     * 未读消息数由消息模块负责，本服务只透传，不改变已读状态。</p>
     *
     * <p>若 userId 为空，各用户维度的 count 会得到 0；该情况只可能出现在
     * 会话异常时，由上层鉴权兜底，本方法不再重复校验。</p>
     *
     * @param userId 当前登录用户 ID，由控制器从会话取得
     * @return 用户端看板指标
     */
    public UserDashboardVO userDashboard(Long userId) {
        UserDashboardVO vo = new UserDashboardVO();
        // 与用户端工具列表口径一致：仅审核通过且可借用的工具。
        // 这里不按分类或关键字过滤，展示的是全平台可借总量。
        vo.setAvailableToolCount(toolMapper.selectCount(
                new LambdaQueryWrapper<Tool>()
                        .eq(Tool::getStatus, ToolStatus.AVAILABLE.code())
                        .eq(Tool::getAuditStatus, AuditStatus.APPROVED.value())));
        // 待支付仅统计当前用户，且只在 PENDING_PAYMENT 状态，不含已取消。
        vo.setPendingPaymentCount(rentalMapper.selectCount(
                new LambdaQueryWrapper<Rental>()
                        .eq(Rental::getUserId, userId)
                        .eq(Rental::getStatus, RentalStatus.PENDING_PAYMENT.code())));
        // 使用中口径包含已逾期：逾期订单仍占用工具，用户仍需关注。
        vo.setRentingCount(rentalMapper.selectCount(
                new LambdaQueryWrapper<Rental>()
                        .eq(Rental::getUserId, userId)
                        .in(Rental::getStatus, List.of(
                                RentalStatus.PICKED_UP.code(),
                                RentalStatus.OVERDUE.code()))));
        // 未读消息按用户端身份统计，与管理端消息互不干扰。
        // 消息类型中的订阅、系统公告等都会计入未读总数。
        // 读取未读数不应产生消息已读副作用。
        vo.setUnreadCount(messageService.unreadCount("USER", userId));
        return vo;
    }

    /**
     * 构建管理端运营看板。
     *
     * <p>一次请求执行多次 count 查询，覆盖工具、租赁、报修与用户四个
     * 维度。所有时间判断都以服务器时区为准，避免客户端时区造成口径漂移。</p>
     *
     * <p>指标之间刻意允许重叠：例如一台已租出的工具同时计入工具总数与
     * 已租出数；一张使用中但已超时的订单同时计入活跃与逾期。这样每个
     * 数字都能独立回答一个运营问题，而不是做互斥拆分。</p>
     *
     * @return 管理端看板指标
     */
    public AdminDashboardVO adminDashboard() {
        // 今日从服务器当天 0 点开始计算，不按 24 小时滚动窗口。
        // 使用 LocalDate 而非直接减 24 小时，能正确处理夏令时切换。
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        AdminDashboardVO vo = new AdminDashboardVO();
        // 工具总数含已报废台账；可借用排除未审核通过的工具。
        // 两个数字分别回答“平台有多少资产”和“现在能借多少”。
        // 未审核工具可能已存在于台账，但不会出现在可借用统计中。
        vo.setToolTotal(toolMapper.selectCount(null));
        vo.setAvailableToolCount(toolMapper.selectCount(
                new LambdaQueryWrapper<Tool>()
                        .eq(Tool::getStatus, ToolStatus.AVAILABLE.code())
                        .eq(Tool::getAuditStatus, AuditStatus.APPROVED.value())));
        // 已租出与维修中是互斥的实物状态，仅按状态列统计，不再过滤审核状态。
        // 报废工具不进入这两项，但已包含在 toolTotal 中。
        // 状态由业务流程维护，看板只读，不做任何状态纠正。
        vo.setRentedToolCount(toolMapper.selectCount(
                new LambdaQueryWrapper<Tool>().eq(Tool::getStatus, ToolStatus.RENTED.code())));
        vo.setMaintenanceToolCount(toolMapper.selectCount(
                new LambdaQueryWrapper<Tool>()
                        .eq(Tool::getStatus, ToolStatus.MAINTENANCE.code())));
        // 活跃租赁不含待支付、已归还、已取消；逾期仍属活动租赁。
        // 已预约虽然尚未取件，但工具已被占用，因此也计入活跃。
        // 待支付订单尚未锁定库存，运营上不视为已发生的租赁。
        vo.setActiveRentalCount(rentalMapper.selectCount(
                new LambdaQueryWrapper<Rental>()
                        .in(Rental::getStatus, List.of(
                                RentalStatus.RESERVED.code(),
                                RentalStatus.PICKED_UP.code(),
                                RentalStatus.OVERDUE.code()))));
        // 逾期按当前时间动态计算，不依赖定时物化，避免统计滞后
        // 显式 OVERDUE 直接计入；PICKED_UP 且已过归还时间也视为逾期。
        // 该口径与用户端“使用中”统计保持一致，只是增加了时间判断。
        LocalDateTime now = LocalDateTime.now();
        vo.setOverdueRentalCount(rentalMapper.selectCount(new LambdaQueryWrapper<Rental>()
                .and(w -> w.eq(Rental::getStatus, RentalStatus.OVERDUE.code())
                        .or(x -> x.eq(Rental::getStatus, RentalStatus.PICKED_UP.code())
                                .lt(Rental::getEndTime, now)))));
        // 待处理报修 = 尚未完结的报修，与报修状态机的活动状态一致。
        // 已驳回、已完成与已报废均不再占用运营精力，不计入待处理。
        // 维修进度（等待配件/维修中）不影响该计数，只看报修单主状态。
        vo.setPendingDamageCount(damageReportMapper.selectCount(
                new LambdaQueryWrapper<DamageReport>()
                        .in(DamageReport::getStatus, List.of(
                                ReportStatus.SUBMITTED.code(),
                                ReportStatus.VERIFIED.code(),
                                ReportStatus.REPAIRING.code()))));
        // 用户总数用于展示平台规模，不做角色区分。
        // 已注销或逻辑删除的用户是否计入取决于表上的删除标记策略。
        // 管理端账号不在用户表中，因此不会计入该数字。
        vo.setUserCount(userMapper.selectCount(new LambdaQueryWrapper<User>()));
        // 今日租赁按创建时间统计，包含当天已取消的订单，反映真实下单量。
        // 这里不使用支付时间或取件时间，避免不同状态订单带来的口径差异。
        vo.setTodayRentalCount(rentalMapper.selectCount(
                new LambdaQueryWrapper<Rental>().ge(Rental::getCreateTime, todayStart)));
        return vo;
    }
}
