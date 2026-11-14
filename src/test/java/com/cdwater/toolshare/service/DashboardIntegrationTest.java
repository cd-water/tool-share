// 仪表盘统计集成测试：校验管理端与用户端的计数口径。
// 管理端关注全局工具、租赁、报修、用户与今日租赁数量。
// 用户端关注本人待支付、使用中、逾期与未读消息数量。
// 统计必须排除终态数据，并对逾期做实时计算。
// 测试直接调用 DashboardService，绕过 HTTP 层聚焦统计逻辑。
// 依赖完整 Spring 上下文、测试数据库与内存对象存储。
// 每个用例用统一前缀准备样本数据，结束后按登记的 ID 清理。
// 文件内注释只说明数据准备与断言意图，不改变执行顺序。
package com.cdwater.toolshare.service;

// 集成测试基类负责数据库迁移与测试隔离。
// 存储配置使用内存实现，避免依赖外部对象存储。
// 仪表盘本身不涉及文件，但上下文启动需要该配置。
import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
// 用户实体与 Mapper 用于准备独立测试用户。
// 用户数量是管理端仪表盘的一项统计指标。
import com.cdwater.toolshare.module.auth.User;
import com.cdwater.toolshare.module.auth.UserMapper;
import com.cdwater.toolshare.module.damage.DamageReport;
import com.cdwater.toolshare.module.damage.DamageReportMapper;
// 管理端与用户端 VO 是断言的直接对象。
// DashboardService 是本次被测的统计入口。
import com.cdwater.toolshare.module.dashboard.AdminDashboardVO;
import com.cdwater.toolshare.module.dashboard.DashboardService;
import com.cdwater.toolshare.module.dashboard.UserDashboardVO;
// 消息实体与 Mapper 用于准备未读消息样本。
// 用户端未读数只统计本人且未读的消息。
import com.cdwater.toolshare.module.message.Message;
import com.cdwater.toolshare.module.message.MessageMapper;
import com.cdwater.toolshare.module.rental.Rental;
import com.cdwater.toolshare.module.rental.RentalMapper;
import com.cdwater.toolshare.module.tool.Tool;
import com.cdwater.toolshare.module.tool.ToolMapper;
// 生命周期注解用于逐用例清理数据。
// 测试注解启动完整 Spring 上下文。
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

// BigDecimal 构造押金金额，保证字段类型与生产一致。
// 时间与集合类型用于构造租赁时间窗口与 ID 登记表。
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

// 断言计数结果与预期增量一致。
// 断言消息会说明统计口径，便于失败时快速定位。
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 仪表盘统计集成测试。
 * <p>
 * 测试范围：管理端与用户端仪表盘的计数口径、终态排除、逾期实时计算与空数据默认值。
 * 依赖环境：完整 Spring 上下文、测试数据库与内存对象存储；直接调用 DashboardService。
 * 关键不变量：活动租赁只含 RESERVED/PICKED_UP/OVERDUE，待支付与终态不计入；
 * 待处理报修只含 SUBMITTED/VERIFIED/REPAIRING；逾期即使尚未物化也要实时计入；
 * 用户端只统计本人数据，但可用工具数对所有人一致；空数据返回 0 而非 null。
 * 补充说明：统计采用增量断言，先记录基线再比较变化。
 * 这样可以避免测试库中既有数据影响绝对数值断言。
 * 所有样本都带 PREFIX 前缀，便于精确准备与清理。
 * 活动租赁、待处理报修与逾期的口径是本类的核心不变量。
 * 用户端统计必须按用户隔离，不能把他人数据计入。
 * 空数据场景要求返回 0 而不是 null，避免前端出现空指针。
 */
@SpringBootTest
@Import(TestStorageConfig.class)
class DashboardIntegrationTest extends AbstractIntegrationTest {

    // 统一前缀用于识别本类创建的工具、租赁、报修与用户。
    // 数据清理依赖该前缀或登记的 ID 列表。
    // 自增序号保证编码、订单号与手机号在同类中唯一。
    // 并发安全的 AtomicInteger 避免多个用例准备数据时冲突。
    private static final String PREFIX = "IT-DASH-";
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    // 仪表盘服务是统计口径的唯一实现，直接调用可避开 HTTP 层。
    // 断言关注返回值字段，而不是接口包装或序列化格式。
    /** 被测仪表盘服务。 */
    DashboardService dashboardService;
    @Autowired
    // 工具 Mapper 用于插入不同状态与审核状态的工具。
    // 工具总数与可借数都依赖这些样本。
    /** 工具 Mapper，用于准备工具统计样本。 */
    ToolMapper toolMapper;
    @Autowired
    // 租赁 Mapper 用于准备各状态租赁单。
    // 活动租赁、逾期与今日租赁三项统计都来自这些记录。
    /** 租赁单 Mapper，用于准备各状态租赁样本。 */
    RentalMapper rentalMapper;
    @Autowired
    // 报修 Mapper 用于准备待处理与终态报修样本。
    // 待处理数量只统计未终结的报修状态。
    /** 报修 Mapper，用于准备各状态报修样本。 */
    DamageReportMapper damageReportMapper;
    @Autowired
    // 用户 Mapper 用于创建独立租用人，隔离用户维度统计。
    // 每个测试用户都会登记 ID，结束后统一删除。
    /** 用户 Mapper，用于准备独立测试用户。 */
    UserMapper userMapper;
    @Autowired
    // 消息 Mapper 用于准备不同已读状态的站内消息。
    // 用户端未读数依赖接收人与已读状态两个字段。
    /** 消息 Mapper，用于准备未读消息样本。 */
    MessageMapper messageMapper;

    // 登记本用例创建的工具 ID，供结束时统一删除。
    // 延迟清理避免样本数据在用例执行期间被误删。
    /** 本用例创建的工具 ID，统一清理。 */
    final List<Long> toolIds = new ArrayList<>();
    // 登记租赁 ID，便于同时清理依赖租赁的报修数据。
    // 清理顺序会先报修、再租赁、最后工具。
    /** 本用例创建的租赁单 ID，统一清理。 */
    final List<Long> rentalIds = new ArrayList<>();
    // 登记报修 ID，避免终态样本残留在统计表中。
    // 报修与租赁分离登记，使清理顺序可控。
    /** 本用例创建的报修单 ID，统一清理。 */
    final List<Long> reportIds = new ArrayList<>();
    // 登记测试用户 ID，防止独立用户影响后续用户总数断言。
    // 用户数据没有其它依赖，可最后删除。
    /** 本用例创建的用户 ID，统一清理。 */
    final List<Long> userIds = new ArrayList<>();
    // 登记消息 ID，确保未读样本不会泄漏到其它用例。
    // 消息按接收人区分，清理时逐条删除即可。
    /** 本用例创建的消息 ID，统一清理。 */
    final List<Long> messageIds = new ArrayList<>();

    /**
     * 用例结束后按登记顺序删除消息、报修、租赁、工具与用户。
     * 先删依赖方再删被依赖方，并清空 ID 列表以便下个用例复用。
     * 清理顺序为消息、报修、租赁、工具、用户。
     * 先删依赖方再删被依赖方，避免留下悬挂引用。
     * 所有列表在清理后清空，保证同一用例实例可重复运行。
     * 即使断言失败，JUnit 仍会执行清理以降低污染。
     * ID 登记与清理配合，避免依赖名称前缀做全表匹配。
     * 删除使用主键，范围精确且不会影响其它测试数据。
     */
    @AfterEach
    void cleanUp() {
        // 删除本用例产生的消息记录。
        // 消息是用户端未读统计的唯一来源。
        // 逐条按主键删除，避免误删其它接收人的消息。
        messageIds.forEach(messageMapper::deleteById);
        // 删除报修样本，释放对租赁与工具的引用。
        // 报修表对活动租赁有唯一索引，残留会导致后续插入失败。
        // 清理发生在所有断言之后，因此不会影响统计结果。
        reportIds.forEach(damageReportMapper::deleteById);
        // 删除租赁样本，避免活动租赁与逾期计数泄漏。
        // 租赁记录还参与今日租赁与用户计数。
        // 先删报修再删租赁，符合业务依赖顺序。
        rentalIds.forEach(rentalMapper::deleteById);
        // 删除工具样本，避免工具总数与可借数被抬高。
        // 工具是租赁与报修的引用目标，因此最后删除。
        // 按主键删除可以精确定位本用例插入的数据。
        toolIds.forEach(toolMapper::deleteById);
        // 删除独立测试用户，恢复用户总数基线。
        // 用户没有前置依赖，放在清理链路末端。
        // 每个用例创建的用户都带自增手机号，不会互相覆盖。
        userIds.forEach(userMapper::deleteById);
        // 清空消息 ID 列表，防止下个用例重复删除。
        // 列表与数据库状态保持一致，便于断言与排查。
        messageIds.clear();
        // 清空报修 ID 列表。
        // 下次用例从空列表开始登记新的样本。
        reportIds.clear();
        // 清空租赁 ID 列表。
        // 避免跨用例累积导致重复删除或误删。
        rentalIds.clear();
        // 清空工具 ID 列表。
        // 清理与登记成对出现，保证列表语义始终表示当前用例数据。
        toolIds.clear();
        // 清空用户 ID 列表。
        // 所有登记表清空后，本用例的资源生命周期结束。
        userIds.clear();
    }

    /**
     * 场景：先记录基线，再创建覆盖全部状态与审核状态的工具、租赁与报修，
     * 然后重新读取管理端仪表盘。
     * 前置条件：所有数据带本类前缀，用例结束可精确清理。
     * 断言目标：工具总数含报废台账；可借用只统计审核通过且 AVAILABLE；
     * 活跃租赁只含 RESERVED/PICKED_UP/OVERDUE；待处理报修只含
     * SUBMITTED/VERIFIED/REPAIRING；用户与今日租赁按新增数量递增。
     * 补充说明：计数使用基线与终值之差，规避库内历史数据。
     * 工具样本覆盖可用、已租、维修、报废与审核未通过。
     * 租赁样本覆盖待支付、活动、逾期与终态。
     * 报修样本覆盖三个待处理状态与三个终态。
     * 今日租赁按创建时间统计，因此六条租赁都应计入。
     * 断言逐项校验，失败时可定位到具体统计口径。
     */
    @Test
    void admin_dashboard_counts_active_rentals_and_unfinished_reports_only() {
        // 先记录管理端仪表盘基线，后续断言只看增量。
        // 测试库可能包含其它数据，绝对数值不可直接硬编码。
        // 基线读取发生在准备样本之前，保证差值对应本用例数据。
        AdminDashboardVO before = dashboardService.adminDashboard();

        // 准备五种状态与审核组合的工具样本。
        // 工具总数应包含已报废台账，可借数只认审核通过且可用的工具。
        // 审核未通过的工具不能进入可借统计，即使状态为 AVAILABLE。
        // 这组样本同时覆盖可借、已租、维修与报废四种生命周期状态。
        createTool("AVAILABLE", 1);
        createTool("AVAILABLE", 0);
        // 已租、维修与报废工具分别对应各项状态计数。
        // 每个工具插入后都会登记 ID 以便清理。
        createTool("RENTED", 1);
        createTool("MAINTENANCE", 1);
        createTool("SCRAPPED", 1);

        // 创建一名独立租用人，使租赁样本与其它用例隔离。
        // 用户数量统计也会因此 +1。
        // 创建时间统一取当前时刻，保证今日租赁计数可控。
        long renterId = createUser();
        // 统一时间基准，去除纳秒避免数据库精度差异。
        // 所有租赁的创建时间都落在此刻，便于今日计数断言。
        LocalDateTime now = LocalDateTime.now().withNano(0);
        // 为同一租用人准备六种状态的租赁单。
        // 只有 RESERVED、PICKED_UP、OVERDUE 计入活动租赁。
        // PENDING_PAYMENT 与终态 RETURNED、CANCELLED 不应计入。
        // 所有租赁都发生在今天，因此今日租赁数应增加 6。
        createRental(renterId, "PENDING_PAYMENT", now);
        createRental(renterId, "RESERVED", now);
        createRental(renterId, "PICKED_UP", now);
        // 终态样本用于验证统计排除逻辑。
        // 逾期样本同时影响活动租赁与逾期计数。
        createRental(renterId, "OVERDUE", now);
        createRental(renterId, "RETURNED", now);
        createRental(renterId, "CANCELLED", now);

        // 准备六种状态的报修单，覆盖待处理与终态。
        // 待处理只含 SUBMITTED、VERIFIED、REPAIRING 三种。
        // REJECTED、COMPLETED、SCRAPPED 是终态，不应计入。
        // 报修使用独立 rentalId 段，避免唯一索引冲突。
        createReport("SUBMITTED");
        createReport("VERIFIED");
        createReport("REPAIRING");
        // 终态报修用于确认统计不会把已关闭问题算作待处理。
        // 每条报修都登记 ID，结束时统一删除。
        createReport("REJECTED");
        createReport("COMPLETED");
        createReport("SCRAPPED");

        // 重新读取管理端仪表盘，与基线做增量比较。
        // 读数发生在全部样本插入之后，覆盖同一统计快照。
        // 后续断言逐字段验证，避免只看总数掩盖口径错误。
        AdminDashboardVO after = dashboardService.adminDashboard();

        // 工具总数应包含所有新插入工具，包括报废台账。
        // 报废工具仍占用资产记录，因此不能被排除。
        // 数量增量固定为 5，与样本数量一致。
        assertEquals(before.getToolTotal() + 5, after.getToolTotal(), "工具总数含已报废台账");
        assertEquals(before.getAvailableToolCount() + 1, after.getAvailableToolCount(),
                "可借用只统计审核通过且状态 AVAILABLE 的工具");
        // 已租工具数只统计状态为 RENTED 的样本。
        assertEquals(before.getRentedToolCount() + 1, after.getRentedToolCount());
        assertEquals(before.getMaintenanceToolCount() + 1, after.getMaintenanceToolCount());
        // 活动租赁 = RESERVED + PICKED_UP + OVERDUE，共 3 条。
        // 待支付尚未占用档期，终态也不再活动，都应排除。
        // 这条断言直接固化活动租赁的枚举口径。
        assertEquals(before.getActiveRentalCount() + 3, after.getActiveRentalCount(),
                "活跃租赁 = RESERVED + PICKED_UP + OVERDUE，不含待支付与终态");
        // 逾期计数只统计 OVERDUE 样本，共 1 条。
        assertEquals(before.getOverdueRentalCount() + 1, after.getOverdueRentalCount());
        // 待处理报修 = SUBMITTED + VERIFIED + REPAIRING，共 3 条。
        // REJECTED、COMPLETED、SCRAPPED 均视为已终结。
        // 待处理口径影响管理端工作量展示。
        assertEquals(before.getPendingDamageCount() + 3, after.getPendingDamageCount(),
                "待处理报修 = SUBMITTED + VERIFIED + REPAIRING");
        // 新增一名用户后，用户总数应增加 1。
        assertEquals(before.getUserCount() + 1, after.getUserCount());
        // 六条租赁的创建时间都是今天，今日租赁数应增加 6。
        // 该统计与租赁状态无关，只看创建时间。
        assertEquals(before.getTodayRentalCount() + 6, after.getTodayRentalCount());
    }

    /**
     * 场景：创建一条状态仍为 PICKED_UP 但结束时间已过的租赁单。
     * 前置条件：定时任务尚未把它物化为 OVERDUE。
     * 断言目标：仪表盘逾期数立即 +1，说明统计按时间实时计算，
     * 不依赖状态字段是否已被后台更新。
     * 补充说明：租赁状态仍为 PICKED_UP，但结束时间已经过去。
     * 定时任务可能尚未把状态物化为 OVERDUE。
     * 仪表盘必须按时间判断逾期，而不是只看状态字段。
     * 因此即使状态未更新，逾期计数也应立即增加。
     * 这条不变量决定管理端能否及时发现超期未还。
     * 用例只准备一条样本，增量断言最直接。
     */
    @Test
    void overdue_count_is_computed_even_before_materialization() {
        // 读取逾期计数基线，避免受库内既有逾期数据影响。
        // 后续只比较增量，确保断言聚焦本条样本。
        // 基线在创建租赁前读取，顺序不可颠倒。
        AdminDashboardVO before = dashboardService.adminDashboard();
        // 创建独立租用人，隔离用户维度数据。
        long renterId = createUser();
        // 统一时间基准，保证结束时间精确落在过去。
        LocalDateTime now = LocalDateTime.now().withNano(0);
        // 结束时间设置为两小时前，开始时间更早。
        // 状态仍为 PICKED_UP，用来模拟定时任务尚未物化的窗口。
        // PICKED_UP 且已过结束时间：定时物化尚未执行，仪表盘也必须算作逾期
        // 插入一条已超期但状态未更新的租赁。
        // 工具 ID 回退到默认值，避免没有工具样本时取空。
        // 这条记录只用于验证逾期实时计算。
        createRental(renterId, "PICKED_UP", now, now.minusHours(2), now.minusHours(1));

        // 再次读取仪表盘，逾期数应因时间判断而增加。
        // 此时数据库状态并未变化，差异只能来自实时计算。
        AdminDashboardVO after = dashboardService.adminDashboard();

        // 逾期计数应 +1，证明统计不依赖补偿任务先物化状态。
        // 若此断言失败，管理端会漏报已超期但未标记的租赁。
        assertEquals(before.getOverdueRentalCount() + 1, after.getOverdueRentalCount());
    }

    /**
     * 场景：两个用户分别拥有不同状态租赁与未读消息。
     * 前置条件：owner 有待支付、使用中、逾期、已归还、已取消各一单，
     * other 只有一单待支付；消息未读数不同。
     * 断言目标：空数据用户各项为 0；可用工具数对所有用户一致；
     * 待支付、使用中（PICKED_UP+OVERDUE）与未读数都只统计本人，
     * 不把他人数据计入当前用户。
     * 补充说明：owner 与 other 是两个完全独立的用户。
     * owner 的五条租赁覆盖待支付、使用中、逾期与终态。
     * other 只有一条待支付租赁，用于验证用户隔离。
     * 可用工具数属于全局数据，两个用户应看到相同值。
     * 未读消息按接收人过滤，不能把他人消息计入。
     * 空数据基线验证默认值为 0，避免前端空指针。
     */
    @Test
    void user_dashboard_counts_only_own_active_rentals_and_unread_messages() {
        // 创建数据所有者，拥有多种状态的租赁与消息。
        long ownerId = createUser();
        // 创建对照用户，只准备少量数据用于隔离断言。
        long otherId = createUser();
        // 在准备任何数据前读取空用户仪表盘。
        // 该快照用于验证无数据时各项统计返回 0。
        // 必须在 owner 产生数据之前读取，否则空数据语义失真。
        UserDashboardVO emptyOwner = dashboardService.userDashboard(ownerId);

        // 准备两个可借工具：一个审核通过，一个审核未通过。
        // 可用工具数只应统计审核通过且状态可用的那一个。
        createTool("AVAILABLE", 1);
        createTool("AVAILABLE", 0);

        // 统一时间基准，所有租赁创建时间都落在今天。
        LocalDateTime now = LocalDateTime.now().withNano(0);
        // owner 的租赁覆盖待支付、使用中、逾期与两个终态。
        // 待支付只应计入 pendingPaymentCount。
        // PICKED_UP 与 OVERDUE 都应计入 rentingCount。
        // RETURNED 与 CANCELLED 不再属于用户活动数据。
        createRental(ownerId, "PENDING_PAYMENT", now);
        createRental(ownerId, "PICKED_UP", now);
        createRental(ownerId, "OVERDUE", now);
        // 终态样本用于确认用户端统计同样排除已结束租赁。
        createRental(ownerId, "RETURNED", now);
        createRental(ownerId, "CANCELLED", now);
        // other 只有一条待支付租赁，用于验证统计按用户隔离。
        createRental(otherId, "PENDING_PAYMENT", now);

        // 为 owner 准备三条消息，其中两条未读。
        // 未读消息数只统计 readStatus=0 的记录。
        createMessage(ownerId, 0);
        createMessage(ownerId, 0);
        createMessage(ownerId, 1);
        // other 的一条未读消息不应出现在 owner 的统计中。
        createMessage(otherId, 0);

        // 读取 owner 的用户端仪表盘。
        UserDashboardVO owner = dashboardService.userDashboard(ownerId);
        // 读取 other 的用户端仪表盘，用于对比用户隔离。
        UserDashboardVO other = dashboardService.userDashboard(otherId);

        // 空数据用户待支付数为 0，而不是 null。
        // 默认值语义保证前端可以直接参与数值运算。
        assertEquals(0, emptyOwner.getPendingPaymentCount(), "无数据时待支付为 0");
        assertEquals(0, emptyOwner.getRentingCount(), "无数据时使用中为 0");
        assertEquals(0, emptyOwner.getUnreadCount(), "无数据时未读为 0");

        // 可用工具数只统计审核通过的可借工具，且全用户一致。
        // owner 与 other 看到的全局可借数量应相同。
        // 这条断言同时验证了审核状态过滤与全局口径。
        assertEquals(emptyOwner.getAvailableToolCount() + 1, owner.getAvailableToolCount(),
                "可用工具只统计审核通过的可借工具，全用户一致");
        assertEquals(owner.getAvailableToolCount(), other.getAvailableToolCount());
        // owner 只有一条待支付租赁，统计不应包含 other 的数据。
        assertEquals(1, owner.getPendingPaymentCount(), "只统计本人待支付");
        // 使用中 = PICKED_UP + OVERDUE，共两条。
        assertEquals(2, owner.getRentingCount(), "使用中 = PICKED_UP + OVERDUE");
        // 只统计 owner 本人未读消息，共两条。
        assertEquals(2, owner.getUnreadCount(), "只统计本人未读");
        // other 的待支付租赁独立计数为 1。
        assertEquals(1, other.getPendingPaymentCount());
        // other 没有使用中的租赁，统计为 0。
        assertEquals(0, other.getRentingCount());
        // other 只有一条未读消息，证明未读数按接收人隔离。
        assertEquals(1, other.getUnreadCount());
    }

    // 插入测试用户并返回主键。
    // 手机号带自增序号，避免用户表唯一约束冲突。
    /** 辅助方法：插入测试用户并返回 ID，手机号带自增序号避免冲突。 */
    private long createUser() {
        // 构造用户实体，手机号使用统一前缀加自增序号。
        User user = new User();
        // 手机号唯一，保证每次创建都是独立用户。
        user.setPhone(PREFIX + "1" + SEQ.incrementAndGet());
        // 昵称同样带前缀，便于人工识别测试数据。
        user.setNickname(PREFIX + "居民");
        // status=1 表示用户正常可用。
        user.setStatus(1);
        // 插入后由数据库生成主键。
        userMapper.insert(user);
        // 登记 ID 供用例结束清理，并返回给调用方。
        userIds.add(user.getId());
        return user.getId();
    }

    // 插入指定状态与审核状态的工具样本。
    // 工具编码带自增序号，避免唯一约束冲突。
    /** 辅助方法：插入指定状态与审核状态的测试工具。 */
    private void createTool(String status, int auditStatus) {
        // 构造工具实体，所有字段取最小可用值。
        // 状态与审核状态由参数控制，用于覆盖不同统计口径。
        // 工具名称带前缀，便于识别测试数据。
        // 押金金额不影响仪表盘统计，但需要满足非空约束。
        Tool tool = new Tool();
        tool.setCode(PREFIX + SEQ.incrementAndGet());
        tool.setName(PREFIX + "工具");
        tool.setCategory("WX");
        // 状态决定工具进入可借、已租、维修或报废计数。
        // 审核状态决定工具是否进入可借统计。
        tool.setStatus(status);
        tool.setAuditStatus(auditStatus);
        // 设置固定押金金额，保证实体字段完整。
        tool.setDeposit(new BigDecimal("100.00"));
        // 插入工具，由数据库生成主键。
        toolMapper.insert(tool);
        // 登记工具 ID，供结束时统一清理。
        toolIds.add(tool.getId());
    }

    /**
     * 辅助方法：插入指定用户、状态与时间窗口的租赁单。
     * 押金状态统一为 PAID，保证仪表盘统计只受目标字段影响；
     * 工具 ID 缺省时回退到 1，避免空列表取值异常。
     * 该重载使用默认时间窗口：创建后一天开始、两天结束。
     * 默认窗口只影响租赁时间字段，不改变状态与押金。
     * 需要精确控制逾期时应调用五参数重载。
     * 两个重载最终写入的数据结构完全一致。
     */
    private void createRental(long userId, String status, LocalDateTime createTime) {
        // 委托给五参数重载，避免重复字段赋值逻辑。
        // 默认开始时间为创建后一天，结束时间再延后一天。
        createRental(userId, status, createTime, createTime.plusDays(1), createTime.plusDays(2));
    }

    // 插入指定用户、状态与时间窗口的租赁单。
    // 押金状态固定为 PAID，避免其它字段干扰统计。
    // 工具、用户与租赁字段都带前缀，便于清理与排查。
    // 创建时间单独传入，用于控制今日租赁计数。
    private void createRental(long userId, String status, LocalDateTime createTime,
                              LocalDateTime startTime, LocalDateTime endTime) {
        // 构造租赁实体，订单号使用自增序号保证唯一。
        // 工具字段回退到默认值，避免没有工具样本时取空。
        // 用户字段决定用户端统计归属。
        // 时间窗口决定活动租赁与逾期计算。
        Rental rental = new Rental();
        rental.setOrderNo(PREFIX + SEQ.incrementAndGet());
        rental.setToolId(toolIds.isEmpty() ? 1L : toolIds.get(0));
        rental.setToolCode(PREFIX + "CODE");
        rental.setToolName(PREFIX + "工具");
        // 绑定租赁人与联系方式，保证实体字段完整。
        // 这些字段不参与仪表盘计数。
        rental.setUserId(userId);
        rental.setRenterName(PREFIX + "租赁人");
        rental.setRenterPhone("13900000000");
        // 写入开始与结束时间，供活动与逾期判断使用。
        rental.setStartTime(startTime);
        rental.setEndTime(endTime);
        // 押金金额固定，押金状态固定为已支付。
        // 仪表盘统计不依赖押金字段，但实体需要完整。
        rental.setDepositAmount(new BigDecimal("100.00"));
        rental.setDepositStatus("PAID");
        // 状态决定租赁是否计入活动、待支付或逾期。
        // 创建时间决定今日租赁计数。
        rental.setStatus(status);
        rental.setCreateTime(createTime);
        // 插入租赁并取得数据库生成的主键。
        rentalMapper.insert(rental);
        // 登记租赁 ID，供结束时清理。
        rentalIds.add(rental.getId());
    }

    /**
     * 辅助方法：插入指定状态的报修单。
     * 活动报修对同一租赁单有唯一索引，因此使用独立租赁 ID 区段，
     * 避免多条测试报修互相冲突。
     * 活动报修对同一租赁单有唯一索引，不能共用 rentalId。
     * 因此这里为每条报修分配独立的租赁 ID 区段。
     * 报修状态决定是否计入待处理数量。
     * 报修与工具、用户字段只要求非空，不影响统计口径。
     */
    private void createReport(String status) {
        // 构造报修实体，报修单号带自增序号保证唯一。
        // 工具与用户字段回退到默认值，避免样本为空时取空。
        // 报修描述与零件字段只用于满足实体约束。
        // 状态字段决定是否进入待处理统计。
        DamageReport report = new DamageReport();
        report.setReportNo(PREFIX + SEQ.incrementAndGet());
        // 先设置一个临时租赁 ID，随后会被独立区段覆盖。
        report.setRentalId(rentalIds.isEmpty() ? 1L : rentalIds.get(0));
        report.setToolId(toolIds.isEmpty() ? 1L : toolIds.get(0));
        report.setToolCode(PREFIX + "CODE");
        // 绑定报修人与工具归属，保证实体字段完整。
        // 这些字段不参与管理端待处理计数。
        report.setToolName(PREFIX + "工具");
        report.setUserId(userIds.isEmpty() ? 1L : userIds.get(0));
        report.setReporterName(PREFIX + "报修人");
        // 填写损坏部位与描述，满足业务字段约束。
        // 创建时间取当前时刻，避免受时区与精度影响。
        report.setReporterPhone("13900000000");
        report.setDamagePart("电机");
        // 写入目标状态，决定是否计入待处理。
        report.setDescription(PREFIX + "损坏描述");
        // 创建时间统一取当前时刻，去掉纳秒。
        report.setStatus(status);
        report.setCreateTime(LocalDateTime.now().withNano(0));
        // 活动报修对同一租赁单只允许一张，使用独立 ID 区段。
        // 覆盖临时租赁 ID，避免多条样本互相触发唯一索引。
        // 区段偏移与自增序号结合，保证跨用例也不冲突。
        // 活动报修同一租赁单只允许一张（V10 唯一索引）：每条活动报修使用独立 rentalId。
        // 为每条报修分配唯一租赁 ID。
        report.setRentalId(9_000_000L + SEQ.incrementAndGet());
        // 插入报修，由数据库生成主键。
        damageReportMapper.insert(report);
        // 登记报修 ID，供结束时统一清理。
        reportIds.add(report.getId());
    }

    // 插入指定接收人与已读状态的站内消息。
    // 接收人决定消息归属，已读状态决定是否计入未读数。
    /** 辅助方法：插入指定接收人与已读状态的站内消息。 */
    private void createMessage(long receiverId, int readStatus) {
        // 构造消息实体，接收人类型为用户。
        // 消息类型与渠道取 SYSTEM，表示系统通知。
        // 标题与内容带前缀，便于识别测试数据。
        // 已读状态由参数控制，用于准备未读样本。
        Message message = new Message();
        message.setReceiverType("USER");
        message.setReceiverId(receiverId);
        message.setType("SYSTEM");
        // 标题与内容只用于满足实体约束，不参与统计。
        // 前缀便于在数据库中快速定位测试消息。
        message.setTitle(PREFIX + "消息");
        message.setContent(PREFIX + "内容");
        message.setChannel("SYSTEM");
        // 写入已读状态：0 未读，1 已读。
        // 用户端未读数只统计 0 值记录。
        message.setReadStatus(readStatus);
        // 插入消息，由数据库生成主键。
        messageMapper.insert(message);
        // 登记消息 ID，供用例结束清理。
        messageIds.add(message.getId());
    }
}
