/**
 * 租赁模块核心服务文件。
 *
 * <h2>职责边界</h2>
 * <p>本服务负责租赁单从创建到归还的完整生命周期，以及用户端/管理端的查询与导出。
 * 工具自身的状态机在 ToolService，报修在 damage 模块，评价在 review 模块，
 * 信用分在 credit 模块；本服务只在这些模块的公开入口上做编排，不直接修改它们的表。
 *
 * <h2>状态流转</h2>
 * <p>PENDING_PAYMENT -> RESERVED -> PICKED_UP -> RETURNED，其中：
 * PENDING_PAYMENT/RESERVED 可取消；PICKED_UP 到期后由定时任务物化为 OVERDUE；
 * OVERDUE 仍可归还。状态机定义在 RentalStateMachine，本服务只调用其断言。
 *
 * <h2>并发控制</h2>
 * <p>1. 创建预约：READ_COMMITTED + 工具行锁 + 冲突查询，保证同一工具的
 *    并发预约串行执行，且能看到前一事务已提交的租赁单。
 * <p>2. 支付/取消/归还：带原状态条件的 UPDATE，影响 0 行即返回冲突；
 *    不依赖先查后改，避免竞态下的重复流转。
 * <p>3. 逾期刷新：10 秒节流 + CAS，保证多实例下只有一个请求执行全量刷新。
 *
  * <h2>方法级流程</h2>
  * <p>create：实名校验 -> 工具行锁 -> 审核/状态/时间校验 -> 冲突查询 ->
  * 快照填写 -> 插入 -> 提交后登记提醒与创建消息。
  * <p>pay：所有权 -> 状态机 -> 带超时条件的原子更新 ->
  * 押金流水 -> 提交后重排提醒与支付成功消息。
  * <p>cancelByUser/cancelByAdmin：状态机 -> （用户侧）24 小时窗口 ->
  * 原子取消 -> 以更新后押金状态补退款流水 -> 提交后清理提醒与消息。
  * <p>pickup：押金校验 -> 状态机 -> 工具可用校验 -> 租赁单原子流转 ->
  * 工具转已租出并累加次数 -> 提交后消息。
  * <p>returnRental：验收解析 -> 状态/时间校验 -> 报修守卫 -> 金额计算 ->
  * 原子归还 -> 信用分/费用/流水 -> 工具状态联动 -> 提交后通知。
  *
  * <h2>幂等与重试</h2>
  * <p>支付、取消、取件、归还都以“原状态 + 条件更新”实现幂等：
  * 重复请求影响 0 行并返回 409，不会产生重复费用或重复退款。
  * 消息使用 biz_key 去重；提醒调度重复写入会覆盖旧值。
  *
  * <h2>错误码约定</h2>
  * <ul>
  *   <li>400：资料不完整、时间非法、验收结果非法、减免缺少原因；</li>
  *   <li>403：访问他人租赁单；</li>
  *   <li>404：租赁单或工具不存在；</li>
  *   <li>409：状态不允许、档期冲突、已超时或乐观锁冲突。</li>
  * </ul>
  *
  * <h2>性能与批量</h2>
  * <p>列表映射一次性批量读取工具主图、提醒提前量与计划提醒时刻；
  * 详情才逐条读取费用与流水。导出直接读租赁单快照，不访问工具表与 Redis。
  *
       * <h2>相关文件</h2>
       * <p>Rental（实体）、RentalCreateDTO/RentalReturnDTO/CancelDTO（入参）、
       * RentalVO/RentalVoMapper（出参）、RentalStateMachine（迁移表）、
       * SettlementCalculator（金额）、RentalMapper（原子 SQL）、
       * RentalReminderService/ReminderService（提醒）。
      * <h2>兼容性</h2>
      * <p>租赁状态、费用类型与资金类型都是对外契约；
      * 新增枚举值不会破坏旧客户端，但删除或重命名会影响历史数据解析。
     * <h2>安全与权限</h2>
     * <p>用户端所有读写都先校验租赁单归属；管理端接口由 @SaCheckPermission 保护。
     * 身份证只保存脱敏快照，完整证件号不进入租赁单与日志。
     * <p>导出接口可能产生包含大量个人信息的文件，应限制访问并审计下载行为。
    * <h2>性能建议</h2>
    * <p>列表映射固定为“一次分页 + 一次工具主图 + 两次提醒批量读取”；
    * 详情才读取费用与流水。导出不访问 Redis，适合大批量。
    * <p>逾期惰性刷新有 10 秒节流，避免每个列表请求都全表扫描；
    * 若逾期数据量大，应让定时任务承担主要刷新，而不是依赖查询触发。
    * <p>用户端分页依赖 (user_id, create_time) 索引；管理端筛选应结合
    * (status, create_time) 使用，避免全表排序。
   * <h2>日志与审计</h2>
   * <p>租赁单本身保存状态、金额与经办人快照；
   * 费用明细与资金流水按追加方式记录，能还原每一次结算。
   * <p>关键节点（自动取消、逾期物化、提醒发送失败）记录 WARN，
   * 并带 rentalId；不记录完整手机号或身份证。
   * <p>对账时以 t_payment_record 为准，租赁单金额只作展示；
   * 若两者不一致，检查取消/归还的并发路径是否都补记了流水。
  * <h2>可观测性</h2>
  * <p>关键外部副作用（消息、提醒、索引）失败只记录 WARN，不影响主事务；
  * 逾期刷新与到期提醒扫描都有独立日志，便于定位定时任务问题。
  *
   * <h2>并发时序（预约创建）</h2>
   * <p>两个请求同时预约同一工具时：
   * 请求 A 获得工具行锁 -> 查冲突为空 -> 插入租赁单 -> 提交释放锁；
   * 请求 B 等待锁 -> 获得锁后按 READ_COMMITTED 看到 A 已提交的租赁单 ->
   * 冲突查询命中 -> 返回 409。RR 隔离级别下 B 的快照可能早于 A 提交，
   * 因此这里必须使用 READ_COMMITTED。
   *
   * <h2>并发时序（支付与取消）</h2>
   * <p>支付与取消都可能改变押金状态。取消 SQL 用 IF 表达式按更新时的
   * deposit_status 计算退款字段；随后服务层重新读取租赁单，
   * 只有实际退款才补记资金流水，避免竞态下漏记或重复记。
   *
   * <h2>时钟与边界</h2>
   * <p>创建预约使用应用时钟校验“开始时间晚于当前”；
   * 待支付超时、逾期物化使用数据库时钟。
   * 部署时应保证两者时区一致，否则边界附近会出现分钟级偏差。
   *
   * <h2>提醒与消息</h2>
   * <p>提醒调度失败不影响业务；消息发送失败记录日志。
   * 所有 biz_key 都包含租赁单 id，重复提交不会重复打扰用户。
   *
   * <h2>数据保留</h2>
   * <p>租赁单、费用与流水都不做物理删除；
   * 工具或用户删除后，历史单据仍保留快照字段用于审计与导出。
   * <h2>接口清单</h2>
   * <p>用户端：GET /api/user/rentals 分页；GET /{id} 详情；POST 创建；
   * POST /{id}/pay 支付；POST /{id}/cancel 取消。
   * <p>管理端（rental:manage）：GET /api/admin/rentals 分页；GET /{id} 详情；
   * POST /{id}/pickup 取件；POST /{id}/return 归还；
   * POST /{id}/cancel 取消；GET /export 导出。
   * <p>控制器只做参数绑定与文件输出；所有业务错误由本服务抛出，
   * 统一转换为 Result.fail，不返回半成功状态。
  * <h2>扩展点</h2>
  * <p>新增租赁状态需同步 RentalStatus、RentalStateMachine 与各流程的断言；
  * 新增费用类型需同步 RentalFeeType、SettlementCalculator 与导出列；
  * 新增资金类型需同步 PaymentRecordType 与流水记录点。
  * <h2>状态与可见性</h2>
  * <p>用户端分页强制附加 userId 条件，详情由 getUserRental 校验所有权；
  * 管理端可分页查看全部租赁单，并可导出。
  * <p>逾期状态是派生的：PICKED_UP 到期后由定时任务物化为 OVERDUE。
  * 列表查询前做 10 秒节流的惰性刷新，减少“已过期但仍显示使用中”的窗口。
  * <p>待支付单在超时前占用档期，超时后被自动取消并释放档期；
  * 这部分逻辑由 RentalMapper 的条件更新与 ReminderService 共同保证。
  *
  * <h2>导出与报表</h2>
  * <p>导出直接使用租赁单快照，不联表查询工具或用户，
  * 因此历史单据在工具/用户资料变更后仍保持原值。
  * 导出列顺序由 RentalExportRow 固定，调整前需确认下游报表。
  *
  * <h2>安全与隐私</h2>
  * <p>接口只返回脱敏手机号与脱敏身份证；完整联系方式不进入视图对象。
  * 导出文件同样脱敏。资金与费用明细只对有权查看该租赁单的用户/管理员返回。
  *
  * <h2>故障处理建议</h2>
  * <p>状态更新返回 409 时先重新读取租赁单确认当前状态，不要盲目重试；
  * 提醒/消息失败不影响业务结果，可在日志中按 rentalId 追踪。
 * <h2>金额规则</h2>
 * <p>押金在创建时快照；逾期费、损坏赔偿、减免与退款只由 SettlementCalculator
 * 基于服务端数据计算，请求体不能直接提交金额。
 * 费用明细（t_rental_fee）与资金流水（t_payment_record）是审计事实，
 * 租赁单上的金额字段只是展示快照。
 *
 * <h2>外部副作用与幂等</h2>
 * <p>提醒调度、站内消息、蹲守通知都通过 AfterCommit 在事务提交后执行。
 * 消息使用业务幂等键（租赁单 id、提醒档位等），重复调度不会重复打扰。
 * 提醒数据存 Redis，丢失后由启动重建按库内活跃租赁补齐。
 */
package com.cdwater.toolshare.module.rental;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.module.auth.Admin;
import com.cdwater.toolshare.module.auth.AdminMapper;
import com.cdwater.toolshare.module.credit.CreditService;
import com.cdwater.toolshare.module.damage.DamageReport;
import com.cdwater.toolshare.module.damage.DamageReportMapper;
import com.cdwater.toolshare.module.message.MessageService;
import com.cdwater.toolshare.module.tool.ToolService;
import com.cdwater.toolshare.shared.enums.AuditStatus;
import com.cdwater.toolshare.shared.enums.DamageResult;
import com.cdwater.toolshare.shared.enums.DepositStatus;
import com.cdwater.toolshare.shared.enums.MessageType;
import com.cdwater.toolshare.shared.enums.PaymentRecordType;
import com.cdwater.toolshare.shared.enums.RentalFeeType;
import com.cdwater.toolshare.shared.enums.RentalStatus;
import com.cdwater.toolshare.shared.enums.ReportStatus;
import com.cdwater.toolshare.shared.enums.ToolStatus;
import com.cdwater.toolshare.shared.security.IdCardCipher;
import com.cdwater.toolshare.shared.sequence.SequenceService;
import com.cdwater.toolshare.shared.tx.AfterCommit;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cdwater.toolshare.shared.web.BizException;
import com.cdwater.toolshare.shared.web.Dict;
import com.cdwater.toolshare.shared.web.PageQuery;
import com.cdwater.toolshare.shared.web.ResultCode;
import com.cdwater.toolshare.shared.config.StpKit;
import com.cdwater.toolshare.module.rental.RentalCreateDTO;
import com.cdwater.toolshare.module.rental.RentalReturnDTO;
import com.cdwater.toolshare.module.rental.Rental;
import com.cdwater.toolshare.module.tool.Tool;
import com.cdwater.toolshare.module.auth.User;
import com.cdwater.toolshare.module.rental.RentalMapper;
import com.cdwater.toolshare.module.tool.ToolMapper;
import com.cdwater.toolshare.module.auth.UserMapper;
import com.cdwater.toolshare.shared.web.PageVO;
import com.cdwater.toolshare.module.rental.RentalExportRow;
import com.cdwater.toolshare.module.rental.RentalVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 租赁领域服务：预约创建、支付、取消、取件、归还结算与分页查询。
 * <p>状态机：所有状态变更先经 {@link RentalStateMachine} 校验，
 * 再通过 RentalMapper 的条件更新原子落库；更新影响 0 行即视为并发冲突。
 * <p>并发与事务：
 * <ul>
 *   <li>预约创建使用 READ_COMMITTED，先锁工具行再查档期冲突，
 *       避免可重复读快照漏看前一事务已提交的租赁单；</li>
 *   <li>支付/取消/归还都用带原状态条件的 UPDATE，天然幂等且不会覆盖他人流转；</li>
 *   <li>逾期状态由定时任务物化，列表查询前做 10 秒节流的惰性刷新。</li>
 * </ul>
 * <p>金额：押金在创建时快照；逾期费与损坏赔偿只由 {@link SettlementCalculator} 计算，
 * 请求体只能提交减免标志与原因，不能直接提交金额。
 * <p>外部副作用：提醒调度、站内消息、蹲守通知全部通过 {@code AfterCommit} 延迟到事务提交后，
 * 事务回滚时不会产生不可回滚的外部状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RentalService {

    // 冲突提示与导出使用的时间格式，精确到分钟
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    // 未终结报修状态；存在这些状态时归还必须显式确认带伤归还
    private static final List<ReportStatus> UNFINISHED_REPORT_STATUS =
            List.of(ReportStatus.SUBMITTED, ReportStatus.VERIFIED, ReportStatus.REPAIRING);

    // 租赁单读写与所有原子状态流转 SQL
    private final RentalMapper rentalMapper;
    // 逾期费、损坏赔偿与减免明细
    private final RentalFeeMapper rentalFeeMapper;
    // 押金收取、退还与补收的资金流水
    private final PaymentRecordMapper paymentRecordMapper;
    // 归还前检查是否存在未终结报修
    private final DamageReportMapper damageReportMapper;
    // 预约时锁工具行；列表映射时批量读取工具主图
    private final ToolMapper toolMapper;
    // 创建预约时读取当前用户真实姓名与身份证
    private final UserMapper userMapper;
    // 管理端操作时补全经办人用户名
    private final AdminMapper adminMapper;
    // 取件/归还时联动工具状态机
    private final ToolService toolService;
    // 生成租赁单号
    private final SequenceService sequenceService;
    // 预约、支付、取消、归还等节点发送站内消息
    private final MessageService messageService;
    // 到期提醒的登记、取消与批量读取
    private final RentalReminderService reminderService;
    // 归还时记录信用分事件
    private final CreditService creditService;
    // 租赁实体到视图的基础映射
    private final RentalVoMapper rentalVoMapper;
    // 身份证脱敏与解密；租赁单只保存脱敏快照
    private final IdCardCipher idCardCipher;
    // 延迟外部副作用到事务提交后执行
    private final AfterCommit afterCommit;

    @Value("${toolshare.rental.pending-payment-timeout-minutes:30}")
    // 待支付单占用档期的超时窗口，超时后由定时任务取消
    private long pendingPaymentTimeoutMinutes = 30;

    @Value("${toolshare.rental.severe-damage-fee:50}")
    // 严重损坏的固定赔偿金额
    private BigDecimal severeDamageFee = new BigDecimal("50");

    /** 逾期状态由定时任务物化，列表查询前做一次惰性刷新，避免筛选存在最长 10 分钟的窗口 */
    // 上次惰性刷新逾期状态的时间戳，用于 10 秒节流
    private final AtomicLong lastOverdueRefresh = new AtomicLong(0);

    /**
     * 列表查询前的惰性逾期刷新。
     * <p>定时任务每 2 分钟物化一次逾期状态，这里最多每 10 秒补刷一次，
     * 把筛选与展示的延迟从分钟级压到秒级；多实例下用 CAS 保证同一时刻只有一个请求执行刷新。
     */
    private void ensureOverdueFresh() {
        // 节流窗口：10 秒内的重复查询直接复用上次刷新结果
        long now = System.currentTimeMillis();
        long last = lastOverdueRefresh.get();
        // 节流窗口内直接返回，避免每个列表请求都触发全表扫描
        if (now - last < 10_000) {
            return;
        }
        // CAS 抢到刷新权才查库，避免并发请求同时全表扫描
        if (lastOverdueRefresh.compareAndSet(last, now)) {
            try {
                // 惰性刷新直接复用定时任务的物化逻辑
                markOverdue();
            } catch (Exception e) {
                // 刷新失败不影响本次查询
                log.warn("刷新逾期状态失败: {}", e.getMessage());
            }
        }
    }

    // READ_COMMITTED：拿到工具行锁后，冲突查询必须看到前一事务已提交的租赁单（RR 快照会漏读）
    /**
     * 创建租赁预约。
     * <p>流程：校验用户实名信息 -> 锁定工具行 -> 校验审核/状态/时间 -> 查档期冲突 ->
     * 生成单号并写入待支付租赁单 -> 登记提醒与站内消息。
     * <p>隔离级别必须是 READ_COMMITTED：拿到工具行锁后，
     * 冲突查询需要看到前一事务已提交的租赁单，RR 快照会漏读并造成重复预约。
     *
     * @param dto 工具、租期、取件人与提醒提前量
     * @return 新建的租赁单视图
     * @throws BizException 资料不完整、工具不可租、时间非法（400）或档期冲突（409）
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RentalVO create(RentalCreateDTO dto) {
        // 预约人必须是当前登录用户，不能由请求体指定
        // 预约人固定为当前登录用户，请求体不能指定他人
        User user = currentUser();
        // 取件需要核验身份，未完善实名信息不允许创建预约
        // 取件需要核验身份，实名信息缺失直接拒绝
        if (!StringUtils.hasText(user.getRealName()) || !StringUtils.hasText(user.getIdCard())) {
            throw new BizException(ResultCode.BAD_REQUEST, "请先在个人中心完善真实姓名与身份证号");
        }
        // 先锁工具行，再查冲突并插入，串行化同一工具的并发预约
        // 工具行锁是本流程的并发闸门
        Tool tool = toolMapper.selectByIdForUpdate(dto.getToolId());
        // 锁不到行说明工具不存在
        if (tool == null) {
            throw new BizException(ResultCode.NOT_FOUND, "工具不存在");
        }
        // 未审核工具对用户不可见，也不能被预约
        if (tool.getAuditStatus() == null || tool.getAuditStatus() != AuditStatus.APPROVED.value()) {
            throw new BizException(ResultCode.BAD_REQUEST, "工具尚未通过审核，暂不可预约");
        }
        // 工具必须处于可用状态；维修中、已租出、已报废都拒绝
        // 只有可用工具可以创建预约
        if (!"AVAILABLE".equals(tool.getStatus())) {
            throw new BizException(ResultCode.BAD_REQUEST,
                    "工具当前不可预约：" + Dict.label(Dict.TOOL_STATUS, tool.getStatus()));
        }
        // 统一使用应用时钟做时间边界校验；数据库时钟只用于绝对时间比较的 SQL
        LocalDateTime now = LocalDateTime.now();
        // 开始时间必须严格晚于当前时间，避免创建已开始的预约
        // 开始时间必须晚于当前时间
        if (!dto.getStartTime().isAfter(now)) {
            throw new BizException(ResultCode.BAD_REQUEST, "开始时间必须晚于当前时间");
        }
        // 半开区间语义：开始必须早于结束，长度至少 1 秒
        // 区间必须正向且非空
        if (!dto.getStartTime().isBefore(dto.getEndTime())) {
            throw new BizException(ResultCode.BAD_REQUEST, "结束时间必须晚于开始时间");
        }
        // 冲突集合包含活动租赁与未过期待支付单；超时待支付单不占用档期
        List<Rental> conflicts = rentalMapper.selectConflicts(tool.getId(),
                dto.getStartTime(), dto.getEndTime(), pendingPaymentTimeoutMinutes);
        // 任一冲突都拒绝预约；提示最早冲突结束后的时间，降低用户反复试错
        if (!conflicts.isEmpty()) {
            // 冲突提示取最早结束的冲突单，建议在其结束 1 小时后开始
            // 建议时间取最早冲突结束后的 1 小时
            String suggestion = conflicts.stream()
                    .map(item -> item.getEndTime().plusHours(1).format(DATE_TIME))
                    .findFirst().orElse("其他时间");
            throw new BizException(ResultCode.CONFLICT, "该时段已被预约，建议 " + suggestion + " 之后");
        }

        // 构造租赁单并写入快照字段
        Rental rental = new Rental();
        // 单号全局唯一，作为对外展示与消息文案的标识
        // 单号全局唯一
        rental.setOrderNo(sequenceService.nextRentalOrderNo());
        // 以下字段构成租赁单快照：工具、用户、租期与联系信息在创建时固化
        rental.setToolId(tool.getId());
        // 工具编号快照：工具资料后续修改不影响历史单
        rental.setToolCode(tool.getCode());
        // 工具名称快照
        rental.setToolName(tool.getName());
        // 租用人 id 用于所有权校验与消息投递
        rental.setUserId(user.getId());
        // 优先使用实名，未填写时退回昵称；实名完整性已在方法开头校验
        rental.setRenterName(StringUtils.hasText(user.getRealName()) ? user.getRealName() : user.getNickname());
        // 联系人手机号保存原值，对外展示与导出时再脱敏
        // 保存原值，展示与导出时脱敏
        rental.setRenterPhone(user.getPhone());
        // 身份证只保存脱敏快照，租赁单不落完整证件号
        // 只保存脱敏快照
        rental.setRenterIdCardMasked(idCardCipher.mask(user.getIdCard()));
        // 计划租期来自请求，服务端已校验先后顺序与未来时间
        rental.setStartTime(dto.getStartTime());
        // 结束时间同时用于取件提醒与逾期判断
        rental.setEndTime(dto.getEndTime());
        // 取件人可以与租用人不同，例如家属代取
        rental.setPickupPerson(dto.getPickupPerson().trim());
        // 用户自助创建，录入人默认为租用人
        rental.setCreatedByName(rental.getRenterName());
        // 初始更新人与录入人一致
        rental.setUpdatedByName(rental.getRenterName());
        // 押金在创建时快照，后续工具押金调整不影响已创建的租赁单
        // 押金快照，后续工具调价不影响本单
        rental.setDepositAmount(tool.getDeposit());
        // 初始押金未支付；支付成功后原子转为 PAID
        rental.setDepositStatus(DepositStatus.UNPAID.code());
        // 新单进入待支付状态，超时未支付会被定时任务自动取消
        rental.setStatus(RentalStatus.PENDING_PAYMENT.code());
        // 初始化金额字段，避免 null 参与后续结算
        // 金额字段初始化为 0，避免 null 参与结算
        rental.setOverdueFee(BigDecimal.ZERO);
        // 损坏赔偿在归还验收时才可能大于 0
        // 损坏赔偿归还时才可能产生
        rental.setDamageFee(BigDecimal.ZERO);
        // 押金退还金额在取消或归还时计算
        // 退款金额取消/归还时计算
        rental.setRefundAmount(BigDecimal.ZERO);
        // 插入完成后实体回填主键，后续调度与消息都依赖该 id
        // 插入后回填主键
        rentalMapper.insert(rental);
        afterCommit.run(() -> {
            // 按用户选择的提前量登记到期提醒（0 = 不提醒）
            // 用户选择的提前量可以是 0（不提醒），null 时服务层用默认值
            reminderService.schedule(rental, dto.getRemindBeforeMinutes());
            // 创建成功通知包含单号与支付时限，事务提交后发送
            messageService.notifyUser(user.getId(), MessageType.RESERVATION, "预约已创建",
                    "租赁单 " + rental.getOrderNo() + " 已创建，请在 " + pendingPaymentTimeoutMinutes
                            + " 分钟内完成押金支付。");
        });
        // 新建单直接返回视图
        return toVO(rental);
    }

    /**
     * 支付押金。
     * <p>只有本人、处于待支付且未超时的租赁单可以支付；
     * 状态更新带超时条件，真正落库时才判断是否超时，避免应用与数据库时钟偏差。
     * 成功后补记押金收取流水，并重新调度到期提醒。
     *
     * @param id 租赁单 id
     * @throws BizException 无权操作、状态不允许或已超时（409）
     */
    @Transactional
    public void pay(Long id) {
        Rental rental = getUserRental(id);
        // 先做状态机校验，给出明确的状态错误而不是笼统的更新失败
        // 先做状态机断言，给出明确错误
        RentalStateMachine.assertTransition(statusOf(rental), RentalStatus.RESERVED);
        // 条件更新同时校验状态、押金状态与创建时间，任何一项不满足都失败
        // 条件更新同时校验状态、押金状态与超时
        if (rentalMapper.pay(id, pendingPaymentTimeoutMinutes) == 0) {
            throw new BizException(ResultCode.CONFLICT, "当前状态不可支付或支付已超时");
        }
        // 支付成功后押金状态转已支付；退款由取消/归还流程处理
        rental.setDepositStatus(DepositStatus.PAID.code());
        // 内存实体同步目标状态，供后续流水与消息使用
        rental.setStatus(RentalStatus.RESERVED.code());
        // 资金事实写入流水表；租赁单上的金额只是快照
        // 资金事实写入流水
        recordPayment(rental.getId(), PaymentRecordType.DEPOSIT_IN, rental.getDepositAmount(),
                rental.getRenterName(), "押金支付");
        afterCommit.run(() -> {
            // 支付完成后确保提醒仍在调度中（用户可能在下单后修改过提前量）
            // 支付后按用户原选择重新登记提醒，保证提醒时刻准确
            // 支付后按原选择恢复提醒调度
            reminderService.schedule(rental, reminderService.leadOf(rental.getId()));
            // 支付成功通知提醒用户按预约时间取件
            messageService.notifyUser(rental.getUserId(), MessageType.RESERVATION, "预约成功",
                    "租赁单 " + rental.getOrderNo() + " 押金支付成功，请按预约时间取件。");
        });
    }

    /**
     * 用户取消预约。
     * <p>取件前 24 小时内不允许用户自行取消，需联系社区工作人员；
     * 已支付押金会原子置为已退款并补记退款流水。
     *
     * @param id     租赁单 id
     * @param reason 取消原因，可为空
     * @throws BizException 无权操作、状态不允许或已进入取件前 24 小时（409）
     */
    @Transactional
    public void cancelByUser(Long id, String reason) {
        // 用户取消先校验所有权，再校验状态与 24 小时窗口
        Rental rental = getUserRental(id);
        RentalStateMachine.assertTransition(statusOf(rental), RentalStatus.CANCELLED);
        // 临近取件时工具已为预约人保留，限制自助取消以减少空档损失
        // 取件前 24 小时内禁止用户自助取消
        if (LocalDateTime.now().isAfter(rental.getStartTime().minusHours(24))) {
            throw new BizException(ResultCode.CONFLICT, "取件前 24 小时内不可取消，请联系社区工作人员");
        }
        // 用户取消与管理员取消共用同一原子取消逻辑，仅默认原因不同
        // 用户取消与管理员取消共用原子取消实现
        doCancel(rental, reason, "用户取消");
    }

    /**
     * 管理员取消预约。
     * <p>不受取件前 24 小时限制；若押金已支付会自动退款并记录流水。
     *
     * @param id     租赁单 id
     * @param reason 取消原因，可为空，为空时使用“工作人员取消”
     * @throws BizException 状态不允许取消（409）
     */
    @Transactional
    public void cancelByAdmin(Long id, String reason) {
        // 管理员取消不校验所有权，但仍必须满足状态机
        Rental rental = getRental(id);
        RentalStateMachine.assertTransition(statusOf(rental), RentalStatus.CANCELLED);
        // 管理员取消没有时间窗口限制，但仍受状态机约束
        // 管理员取消没有时间窗口限制
        doCancel(rental, reason, "工作人员取消");
    }

    /**
     * 取消预约的共用实现。
     * <p>取消 SQL 原子处理押金退款字段；由于支付与取消可能并发，
     * 退款流水必须以更新后的押金状态为准补记，不能使用取消前的快照。
     * 提醒取消与站内消息在事务提交后执行。
     *
     * @param rental   租赁单
     * @param reason   取消原因
     * @param operator 操作来源文案
     * @throws BizException 当前状态不可取消（409）
     */
    private void doCancel(Rental rental, String reason, String operator) {
        // 未填写原因时使用操作来源，保证取消原因字段可读
        // 原因为空时使用操作来源文案
        String cancelReason = StringUtils.hasText(reason) ? reason : operator;
        // 经办人写入租赁单快照，便于导出与审计
        String updatedBy = operatorName(rental);
        // 带原状态条件的原子取消；返回 0 说明已被并发流转
        // 带原状态条件的原子取消
        if (rentalMapper.cancel(rental.getId(), cancelReason, updatedBy) == 0) {
            throw new BizException(ResultCode.CONFLICT, "当前状态不可取消");
        }
        // 支付与取消可能并发，以原子更新后的押金状态为准补记退款流水
        // 重新读取以押金状态为准，解决支付与取消并发的竞态
        // 重新读取以更新后的押金状态为准
        Rental cancelled = rentalMapper.selectById(rental.getId());
        // 只有实际发生了退款才补记退款流水，避免未支付单产生虚假流水
        // 只有实际退款才补记退款流水
        if (cancelled != null && DepositStatus.REFUNDED.code().equals(cancelled.getDepositStatus())
                && cancelled.getRefundAmount() != null && cancelled.getRefundAmount().signum() > 0) {
            // 退款金额来自原子更新后的 refund_amount
            // 退款金额来自原子更新后的字段
            recordPayment(cancelled.getId(), PaymentRecordType.DEPOSIT_REFUND,
                    cancelled.getRefundAmount(), updatedBy, "取消租赁退还押金");
        }
        afterCommit.run(() -> {
            reminderService.cancel(rental.getId());
            // 站内消息在事务提交后发送，失败不影响取消失败回滚
            messageService.notifyUser(rental.getUserId(), MessageType.SYSTEM, "预约已取消",
                    "租赁单 " + rental.getOrderNo() + " 已取消，押金将在 1-3 个工作日内退还。");
        });
    }

    /**
     * 确认取件。
     * <p>前置条件：押金已支付、租赁单处于已预约、工具当前可用。
     * 租赁单与工具状态在同一个事务内联动：租赁单转使用中，工具转已租出并累加租赁次数。
     *
     * @param id 租赁单 id
     * @throws BizException 押金未支付、状态不允许或工具不可取件（409）
     */
    @Transactional
    public void pickup(Long id) {
        Rental rental = getRental(id);
        // 未支付押金的预约不能取件
        // 未支付押金不能取件
        if (!DepositStatus.PAID.code().equals(rental.getDepositStatus())) {
            throw new BizException(ResultCode.CONFLICT, "押金尚未支付，不可取件");
        }
        // RESERVED -> PICKED_UP 是唯一合法入口
        // 只有已预约可以取件
        RentalStateMachine.assertTransition(statusOf(rental), RentalStatus.PICKED_UP);
        // 工具可能在预约后被手动置为维修/报废，取件前必须重新确认
        Tool tool = toolService.getTool(rental.getToolId());
        // 工具不可用时拒绝取件，并返回当前状态便于排查
        // 预约后工具可能被改为维修/报废
        if (!ToolStatus.AVAILABLE.code().equals(tool.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "工具当前状态不可取件："
                    + Dict.label(Dict.TOOL_STATUS, tool.getStatus()));
        }
        // 条件更新保证并发下只有一次取件成功
        // 条件更新保证并发下只成功一次
        if (rentalMapper.transit(id, RentalStatus.RESERVED.code(),
                RentalStatus.PICKED_UP.code()) == 0) {
            throw new BizException(ResultCode.CONFLICT, "当前状态不可取件");
        }
        // 租赁次数在取件时累计，归还后不再重复计数
        // 取件即累计一次租赁
        tool.setRentalCount((tool.getRentalCount() == null ? 0 : tool.getRentalCount()) + 1);
        // 工具状态机同步转已租出；若此处失败整个取件事务回滚
        // 租赁单与工具状态在同一事务内联动
        toolService.transition(tool, ToolStatus.RENTED, "PICKUP", "取件后工具状态变为已租出");
        // 取件成功消息延迟到事务提交后发送
        afterCommit.run(() -> messageService.notifyUser(rental.getUserId(), MessageType.SYSTEM, "工具已取件",
                "租赁单 " + rental.getOrderNo() + " 已确认取件，请按时归还。"));
    }

    /**
     * 归还验收与结算。
     * <p>流程：解析验收结果 -> 校验状态与归还时间 -> 未终结报修守卫 ->
     * 计算金额 -> 原子归还 -> 记录信用分/费用/资金流水 -> 联动工具状态 -> 发送通知。
     * <p>金额完全由 {@link SettlementCalculator} 计算，请求体只能提交减免标志与原因。
     * 存在未终结报修时必须显式带伤归还，此时工具保持维修中。
     *
     * @param id  租赁单 id
     * @param dto 实际归还时间、验收结果、减免与备注
     * @throws BizException 验收结果非法、时间非法、报修未终结或状态不允许（400/409）
     */
    @Transactional
    public void returnRental(Long id, RentalReturnDTO dto) {
        // 验收结果只接受枚举编码，未知值直接拒绝
        // 验收结果必须是合法枚举
        DamageResult damageResult = DamageResult.fromCode(dto.getDamageResult())
                .orElseThrow(() -> new BizException(ResultCode.BAD_REQUEST, "无效的验收结果"));
        // 归还由管理端执行，不做用户所有权校验
        Rental rental = getRental(id);
        // 使用中与已逾期都允许归还，其余状态拒绝
        // 使用中或逾期可以归还
        RentalStateMachine.assertTransition(statusOf(rental), RentalStatus.RETURNED);
        // 实际归还时间由经办人填写，不能晚于当前时间（入参已校验）
        LocalDateTime actualReturnTime = dto.getActualReturnTime();
        // 归还时间不能早于租期开始，否则逾期费计算会失真
        // 归还时间不能早于租期开始
        if (actualReturnTime.isBefore(rental.getStartTime())) {
            throw new BizException(ResultCode.BAD_REQUEST, "实际归还时间不能早于租赁开始时间");
        }
        // 仅显式 true 才视为带伤归还，null/false 都按正常归还
        // 只有显式 true 才算带伤归还
        boolean returnWithDamage = Boolean.TRUE.equals(dto.getReturnWithDamage());
        // 报修未终结时不能把工具直接恢复可用，必须先处理或显式带伤归还
        // 报修未终结时必须显式确认带伤归还
        if (!returnWithDamage && hasUnfinishedReport(id)) {
            throw new BizException(ResultCode.CONFLICT,
                    "该租赁存在未终结报修，请先完成维修，或显式选择带伤归还");
        }

        // 减免标志同样只认显式 true
        // 减免标志只认显式 true
        boolean waiveOverdue = Boolean.TRUE.equals(dto.getWaiveOverdue());
        // 损坏赔偿减免独立于逾期费减免
        // 损坏赔偿减免独立于逾期费
        boolean waiveDamage = Boolean.TRUE.equals(dto.getWaiveDamage());
        // 任何减免都必须留下原因，便于审计
        // 任何减免都必须有原因
        if ((waiveOverdue || waiveDamage) && !StringUtils.hasText(dto.getWaiverReason())) {
            throw new BizException(ResultCode.BAD_REQUEST, "减免必须填写原因");
        }
        // 押金、逾期时长与验收结果共同决定应收、减免与退款
        // 金额完全由服务端规则计算
        SettlementCalculator.Settlement settlement = SettlementCalculator.calculate(
                rental, actualReturnTime, damageResult == DamageResult.SEVERE, severeDamageFee,
                waiveOverdue, waiveDamage);
        // 原始逾期费（减免前），费用明细按该金额记录
        BigDecimal overdueFee = settlement.overdueFee();
        // 原始损坏赔偿（减免前）
        BigDecimal damageFee = settlement.damageFee();
        // 本次减免总额，用于通知文案
        BigDecimal waived = settlement.waived();
        // 押金扣除净应收后的退款额
        BigDecimal refund = settlement.refund();
        // 净应收超过押金时的补收金额
        BigDecimal receivable = settlement.receivable();

        // 管理端登录时记录管理员用户名，否则使用租用人姓名
        // 管理端操作记录管理员，否则使用租用人姓名
        String operator = operatorName(rental);
        String returnRemark = dto.getRemark();
        // 带伤归还：报修未终结，工具保持维修中。
        // 备注优先使用经办人填写内容，未填写时落固定文案，保证可追溯。
        if (returnWithDamage) {
            returnRemark = StringUtils.hasText(returnRemark) ? returnRemark.trim()
                    : "存在未终结报修，带伤归还";
        }
        // 条件更新保证只有使用中/逾期状态能归还一次
        // 条件更新保证只归还一次
        if (rentalMapper.returnRental(id, actualReturnTime, overdueFee, damageFee, refund,
                damageResult.code(), returnRemark, operator) == 0) {
            throw new BizException(ResultCode.CONFLICT, "当前状态不可归还");
        }

        // 信用分事件在同一事务内写入：按时归还加分、逾期扣分互斥，严重损坏单独扣分
        // 信用分与归还验收入同一事务：按时/逾期互斥，严重损坏单独计一次
        // 是否按时以实际归还时间与计划结束时间比较
        // 信用分事件与归还同事务
        creditService.recordReturn(rental.getUserId(), rental.getId(), rental.getOrderNo(),
                // 恰好等于结束时间仍算按时
                !actualReturnTime.isAfter(rental.getEndTime()),
                damageResult == DamageResult.SEVERE);

        // 逾期费明细：记录原始金额，减免不修改该条明细
        // 逾期费明细保留原始金额
        if (overdueFee.signum() > 0) {
            // 每条费用事实单独成行，金额保留两位小数
            recordFee(rental.getId(), RentalFeeType.OVERDUE, overdueFee,
                    "按实际归还时间计算的逾期费用", operator);
        }
        // 损坏赔偿明细：金额由验收结果与配置决定
        // 损坏赔偿明细
        if (damageFee.signum() > 0) {
            // 损坏赔偿按验收结果生成
            recordFee(rental.getId(), RentalFeeType.DAMAGE, damageFee,
                    "验收结果：" + damageResult.label(), operator);
        }
        // 减免单独记一条 WAIVER 明细，保留原始费用与减免原因的对应关系
        // 逾期费减免单独成行
        if (waiveOverdue && overdueFee.signum() > 0) {
            // 减免记录原始金额与原因，不修改上面的逾期费明细
            recordFee(rental.getId(), RentalFeeType.WAIVER, overdueFee,
                    "减免逾期费：" + dto.getWaiverReason().trim(), operator);
        }
        // 损坏赔偿减免同样单独记录
        // 损坏赔偿减免单独成行
        if (waiveDamage && damageFee.signum() > 0) {
            // 损坏赔偿减免同样单独记录
            recordFee(rental.getId(), RentalFeeType.WAIVER, damageFee,
                    "减免损坏赔偿：" + dto.getWaiverReason().trim(), operator);
        }
        // 押金退款写入资金流水；为 0 时不产生无意义流水
        // 押金退款流水
        if (refund.signum() > 0) {
            // 押金退款流水：金额来自结算结果的应退部分
            recordPayment(rental.getId(), PaymentRecordType.DEPOSIT_REFUND, refund,
                    operator, "归还验收后退还押金");
        }
        // 赔偿超过押金时生成应收流水，押金不足部分需另行追缴
        // 押金不足时的应收流水
        if (receivable.signum() > 0) {
            // 押金不足时的应收流水，需线下追缴
            recordPayment(rental.getId(), PaymentRecordType.RECEIVABLE, receivable,
                    operator, "赔偿超出押金的应收");
        }
        // 归还后取消到期提醒，避免已归还租赁继续收到提醒
        // 归还后取消提醒
        afterCommit.run(() -> reminderService.cancel(rental.getId()));

        // 工具状态联动在归还事务内完成，失败则整体回滚
        Tool tool = toolService.getTool(rental.getToolId());
        if (returnWithDamage) {
            // 带伤归还：报修尚未终结，工具保持维修中，原因写入返回备注与状态日志
            // 带伤且严重损坏时同步提升损坏程度
            // 带伤归还且严重损坏：提升损坏程度后再转维修
            // 带伤归还且严重损坏：提升损坏程度后再转维修
            if (damageResult == DamageResult.SEVERE) {
                // 损坏程度是工具资产属性，严重损坏后不可再按原等级出租
                tool.setDamageLevel("SEVERE");
            }
            toolService.transition(tool, ToolStatus.MAINTENANCE, "RETURN",
                    "带伤归还，工具保持维修中：" + returnRemark);
        // 正常归还但验收严重损坏：转维修中，不能直接恢复可用
        // 严重损坏与带伤归归还共用维修处理，区别只在归还备注
        // 严重损坏与带伤归归还共用维修处理，区别只在归还备注
        } else if (damageResult == DamageResult.SEVERE) {
            // 正常归还但验收严重损坏：直接转维修中
            tool.setDamageLevel("SEVERE");
            toolService.transition(tool, ToolStatus.MAINTENANCE, "RETURN",
                    "归还验收为严重损坏，工具转维修中");
        } else {
            // 轻微损坏只调整损坏程度，工具仍可恢复可用
            // 轻微损坏只调整损坏程度，工具仍可恢复可用
            if (damageResult == DamageResult.MINOR) {
                tool.setDamageLevel("LIGHT");
            }
            // 完好或轻微损坏：工具恢复可用并触发蹲守通知
            // 完好或轻微损坏恢复可用并触发蹲守通知
            toolService.transition(tool, ToolStatus.AVAILABLE, "RETURN",
                    "归还验收完成，工具恢复可用");
        }
        // lambda 捕获需要 effectively final，单独复制一份
        // lambda 捕获 effectively final
        BigDecimal waivedFinal = waived;
        // 归还完成消息汇总费用、减免与退款，事务提交后发送
        afterCommit.run(() -> messageService.notifyUser(rental.getUserId(), MessageType.SYSTEM, "归还验收完成",
                "租赁单 " + rental.getOrderNo() + " 已归还，逾期费 " + overdueFee
                        + " 元，损坏赔偿 " + damageFee + " 元，减免 " + waivedFinal
                        + " 元，退还押金 " + refund + " 元"
                        + (receivable.signum() > 0 ? "，另有应收 " + receivable + " 元。" : "。")
                        + (returnWithDamage ? " 工具存在未终结报修，暂保持维修中。" : "")));
    }

    /**
     * 判断租赁单是否存在未终结报修。
     * <p>报修与租赁是不同聚合，归还前需要跨模块检查，
     * 防止工具在维修未完成时被重新上架。
     *
     * @param rentalId 租赁单 id
     * @return 存在已提交/已核验/维修中的报修返回 true
     */
    private boolean hasUnfinishedReport(Long rentalId) {
        // 未终结报修按租赁单维度检查
        return damageReportMapper.selectCount(new LambdaQueryWrapper<DamageReport>()
                .eq(DamageReport::getRentalId, rentalId)
                .in(DamageReport::getStatus, UNFINISHED_REPORT_STATUS.stream()
                        .map(ReportStatus::code).toList())) > 0;
    }

    /**
     * 追加费用明细，金额统一保留两位小数。
     *
     * @param rentalId 租赁单 id
     * @param feeType  费用类型
     * @param amount   金额
     * @param reason   费用说明
     * @param operator 经办人
     */
    private void recordFee(Long rentalId, RentalFeeType feeType, BigDecimal amount,
                           String reason, String operator) {
        RentalFee fee = new RentalFee();
        fee.setRentalId(rentalId);
        fee.setFeeType(feeType.code());
        // 费用金额统一两位小数
        fee.setAmount(amount.setScale(2, RoundingMode.HALF_UP));
        fee.setReason(reason);
        // 记录经办人快照
        fee.setOperatorName(operator);
        rentalFeeMapper.insert(fee);
    }

    /**
     * 追加资金流水，金额统一保留两位小数。
     * <p>流水是资金事实，租赁单上的金额字段只作展示快照。
     *
     * @param rentalId   租赁单 id
     * @param recordType 流水类型
     * @param amount     金额
     * @param operator   经办人
     * @param remark     备注
     */
    private void recordPayment(Long rentalId, PaymentRecordType recordType, BigDecimal amount,
                               String operator, String remark) {
        PaymentRecord record = new PaymentRecord();
        record.setRentalId(rentalId);
        record.setRecordType(recordType.code());
        // 流水金额统一两位小数
        record.setAmount(amount.setScale(2, RoundingMode.HALF_UP));
        record.setOperatorName(operator);
        // 流水说明用于审计展示
        record.setRemark(remark);
        paymentRecordMapper.insert(record);
    }

    /**
     * 解析当前操作人名称。
     * <p>管理端会话优先使用管理员用户名；用户侧操作回退到租用人姓名。
     *
     * @param rental 租赁单
     * @return 操作人名称
     */
    private String operatorName(Rental rental) {
        // 用户端请求也可能触发归还？当前仅管理端归还，但保留兼容判断
        // 管理端会话优先记录管理员用户名
        if (StpKit.ADMIN.isLogin()) {
            Admin admin = adminMapper.selectById(StpKit.ADMIN.getLoginIdAsLong());
            if (admin != null) {
                return admin.getUsername();
            }
        }
        return rental.getRenterName();
    }

    /**
     * 用户端租赁分页，只返回当前登录用户的租赁单。
     * <p>关键字匹配单号、工具名称与工具编号；结果按创建时间倒序。
     *
     * @param keyword 关键字
     * @param status  状态编码
     * @param page    页码
     * @param size    每页条数
     * @return 租赁分页结果
     */
    public PageVO<RentalVO> userPage(String keyword, String status, long page, long size) {
        ensureOverdueFresh();
        // 用户端查询固定附加 userId 条件
        LambdaQueryWrapper<Rental> wrapper = new LambdaQueryWrapper<Rental>()
                // 用户端查询强制限定当前用户，避免越权读取他人租赁
                .eq(Rental::getUserId, StpKit.USER.getLoginIdAsLong());
        // 用户端关键字只匹配自己的单号与工具信息
        // 关键字在单号、工具名称、工具编号之间 OR
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(Rental::getOrderNo, keyword)
                    .or().like(Rental::getToolName, keyword)
                    .or().like(Rental::getToolCode, keyword));
        }
        // 状态筛选为空时返回该用户的全部状态
        if (StringUtils.hasText(status)) {
            wrapper.eq(Rental::getStatus, status);
        }
        // 用户端按创建时间倒序，最新预约优先展示
        Page<Rental> result = rentalMapper.selectPage(PageQuery.of(page, size),
                wrapper.orderByDesc(Rental::getCreateTime));
        return pageOf(result);
    }

    /**
     * 管理端租赁分页。
     * <p>支持关键字、状态、逾期与验收结果筛选；逾期筛选口径见 {@link #adminWrapper}。
     *
     * @param keyword      关键字
     * @param status       状态编码
     * @param overdue      true/false/all，控制逾期过滤
     * @param damageResult 验收结果编码
     * @param page         页码
     * @param size         每页条数
     * @return 租赁分页结果
     */
    public PageVO<RentalVO> adminPage(String keyword, String status, String overdue,
                                      String damageResult, long page, long size) {
        ensureOverdueFresh();
        // 管理端筛选统一由 adminWrapper 构造
        Page<Rental> result = rentalMapper.selectPage(PageQuery.of(page, size),
                adminWrapper(keyword, status, overdue, damageResult, null)
                        .orderByDesc(Rental::getCreateTime));
        return pageOf(result);
    }

    // 先确认工具存在，再复用管理端筛选条件；工具删除后仍可通过其它入口按 id 查日志，
    // 但历史租赁入口要求工具当前存在，避免对无效 id 返回空页造成误解。
    /** 管理端工具历史租赁：按工具分页，复用同一套批量映射。 */
    /**
     * 按工具查询历史租赁。
     *
     * @param toolId 工具 id
     * @param page   页码
     * @param size   每页条数
     * @return 租赁分页结果
     * @throws BizException 工具不存在时返回 404
     */
    public PageVO<RentalVO> toolRentalPage(Long toolId, long page, long size) {
        // 工具不存在时返回 404，而不是返回空分页造成误解
        if (toolMapper.selectById(toolId) == null) {
            throw new BizException(ResultCode.NOT_FOUND, "工具不存在");
        }
        ensureOverdueFresh();
        // 工具历史租赁复用管理端筛选，仅固定 toolId
        Page<Rental> result = rentalMapper.selectPage(PageQuery.of(page, size),
                adminWrapper(null, null, null, null, toolId)
                        .orderByDesc(Rental::getCreateTime));
        return pageOf(result);
    }

    /**
      * <p>筛选之间是 AND 关系；overdue=all 或空时不追加逾期条件。
      * overdue=true 时把“已物化 OVERDUE”和“使用中但已过结束时间”都算逾期，
      * 覆盖定时任务尚未执行的窗口。
      *
      * @param keyword      关键字
      * @param status       状态编码，all 表示不过滤
      * @param overdue      逾期过滤：true/false/all
      * @param damageResult 验收结果编码，all 表示不过滤
      * @param toolId       工具 id，非空时只查该工具
      * @return 查询条件
     * 管理端筛选口径：
     * <ul>
     *   <li>overdue=true：已物化 OVERDUE，或仍在使用中但已过结束时间（兜底定时任务延迟）</li>
     *   <li>overdue=false：排除 OVERDUE，且排除使用中但已过结束时间</li>
     *   <li>overdue=all/空：不按逾期过滤</li>
     * </ul>
     */
    private LambdaQueryWrapper<Rental> adminWrapper(String keyword, String status, String overdue,
                                                    String damageResult, Long toolId) {
        // 管理端条件按需追加
        LambdaQueryWrapper<Rental> wrapper = new LambdaQueryWrapper<>();
        // 工具历史租赁入口使用该条件限定单一工具
        if (toolId != null) {
            wrapper.eq(Rental::getToolId, toolId);
        }
        if (StringUtils.hasText(keyword)) {
            // 管理端关键字条件加括号，避免 OR 优先级问题
            wrapper.and(w -> w.like(Rental::getOrderNo, keyword)
                    .or().like(Rental::getToolCode, keyword)
                    .or().like(Rental::getToolName, keyword)
                    .or().like(Rental::getRenterName, keyword)
                    .or().like(Rental::getRenterPhone, keyword));
        }
        // all 是前端的“全部”约定，不进入 SQL 条件
        if (StringUtils.hasText(status) && !"all".equalsIgnoreCase(status)) {
            wrapper.eq(Rental::getStatus, status);
        }
        // 真逾期：已物化 OVERDUE，或使用中但计划结束时间已过
        // 真逾期包含已物化 OVERDUE 与延迟物化的使用中单据
        if ("true".equalsIgnoreCase(overdue)) {
            wrapper.and(w -> w.eq(Rental::getStatus, RentalStatus.OVERDUE.code())
                    .or(x -> x.eq(Rental::getStatus, RentalStatus.PICKED_UP.code())
                            .lt(Rental::getEndTime, LocalDateTime.now())));
        // 未逾期：排除 OVERDUE，同时排除“使用中但已过期”的延迟窗口
        // 未逾期排除上述两种情况
        } else if ("false".equalsIgnoreCase(overdue)) {
            wrapper.ne(Rental::getStatus, RentalStatus.OVERDUE.code())
                    .and(w -> w.ne(Rental::getStatus, RentalStatus.PICKED_UP.code())
                            .or().ge(Rental::getEndTime, LocalDateTime.now()));
        }
        // 验收结果筛选只允许合法枚举编码
        // 验收结果筛选必须是合法枚举
        if (StringUtils.hasText(damageResult) && !"all".equalsIgnoreCase(damageResult)) {
            DamageResult result = DamageResult.fromCode(damageResult)
                    .orElseThrow(() -> new BizException(ResultCode.BAD_REQUEST, "无效的验收结果"));
            wrapper.eq(Rental::getDamageResult, result.code());
        }
        // 返回组合后的查询条件
        return wrapper;
    }

    /**
     * 分页实体批量转视图，避免逐行查询工具与提醒。
     *
     * @param result 实体分页结果
     * @return 视图分页结果
     */
    private PageVO<RentalVO> pageOf(Page<Rental> result) {
        // 批量映射后组装分页对象
        return PageVO.of(toVOList(result.getRecords()), result.getTotal(),
                result.getCurrent(), result.getSize());
    }

    /**
     * 用户端租赁详情，附带费用与资金流水。
     *
     * @param id 租赁单 id
     * @return 租赁详情视图
     * @throws BizException 非本人租赁单返回 403
     */
    public RentalVO userDetail(Long id) {
        // 用户详情先做所有权校验
        return toDetailVO(getUserRental(id));
    }

    /**
     * 管理端租赁详情，不做用户所有权限制。
     *
     * @param id 租赁单 id
     * @return 租赁详情视图
     */
    public RentalVO adminDetail(Long id) {
        // 管理端详情不做所有权校验
        return toDetailVO(getRental(id));
    }

    /**
     * 读取租赁单实体，不存在时统一返回 404。
     *
     * @param id 租赁单 id
     * @return 租赁单实体
     * @throws BizException 租赁单不存在时返回 404
     */
    public Rental getRental(Long id) {
        // 单条读取；详情接口随后再按需批量补费用与流水
        // 单条读取，不存在统一 404
        Rental rental = rentalMapper.selectById(id);
        // 不存在统一返回 404，不区分是否曾经存在
        // 不存在时抛出 404
        if (rental == null) {
            throw new BizException(ResultCode.NOT_FOUND, "租赁单不存在");
        }
        return rental;
    }

    /**
     * 读取当前用户自己的租赁单。
     * <p>越权访问返回 403 而不是 404，便于用户确认单号存在但无权查看。
     *
     * @param id 租赁单 id
     * @return 租赁单实体
     * @throws BizException 不存在（404）或非本人（403）
     */
    private Rental getUserRental(Long id) {
        Rental rental = getRental(id);
        // 越权访问返回 403，明确告诉用户“存在但无权查看”
        // 非本人租赁单返回 403
        if (!rental.getUserId().equals(StpKit.USER.getLoginIdAsLong())) {
            throw new BizException(ResultCode.FORBIDDEN, "无权查看该租赁单");
        }
        return rental;
    }

    // 导出面向批量数据，全部字段来自租赁单快照；手机号在导出时脱敏，
    // 避免把完整联系方式写入可随意转发的 Excel 文件。
    /** 导出直接读租赁行快照，不查工具表也不查 Redis，避免逐行查询。 */
    /**
     * 构造导出数据，筛选口径与列表一致。
     *
     * @param keyword      关键字
     * @param status       状态编码
     * @param overdue      逾期过滤
     * @param damageResult 验收结果
     * @return 导出行列表
     */
    public List<RentalExportRow> exportRows(String keyword, String status, String overdue,
                                            String damageResult) {
        // 导出口径与列表筛选一致
        List<Rental> rentals = rentalMapper.selectList(
                adminWrapper(keyword, status, overdue, damageResult, null)
                        .orderByDesc(Rental::getCreateTime));
        // 导出映射只读租赁单快照，不触发额外查询
        return rentals.stream().map(this::toExportRow).toList();
    }

    /**
     * 租赁单转导出行。
     * <p>字典字段翻译为中文，金额与时间转为展示字符串；
     * 手机号再次脱敏，身份证直接使用已脱敏快照。
     *
     * @param rental 租赁单
     * @return 导出行
     */
    private RentalExportRow toExportRow(Rental rental) {
        // 逐字段转换，不触发额外查询
        RentalExportRow row = new RentalExportRow();
        // 以下字段按导出列顺序逐项转换
        row.setOrderNo(rental.getOrderNo());
        // 工具编号快照
        row.setToolCode(rental.getToolCode());
        // 工具名称快照
        row.setToolName(rental.getToolName());
        // 租用人姓名快照
        row.setRenterName(rental.getRenterName());
        // 导出文件可能被转发，手机号必须脱敏
        // 导出时再次脱敏手机号
        row.setRenterPhone(IdCardCipher.maskPhone(rental.getRenterPhone()));
        // 身份证已在入库时脱敏，导出直接使用
        row.setRenterIdCardMasked(rental.getRenterIdCardMasked());
        // 取件人
        row.setPickupPerson(rental.getPickupPerson());
        // 录入人
        row.setCreatedByName(rental.getCreatedByName());
        // 归还/取消经办人
        row.setUpdatedByName(rental.getUpdatedByName());
        // 时间统一格式化为 yyyy-MM-dd HH:mm
        row.setStartTime(format(rental.getStartTime()));
        // 计划结束时间
        row.setEndTime(format(rental.getEndTime()));
        // 押金快照
        row.setDepositAmount(amount(rental.getDepositAmount()));
        // 导出面向业务人员，使用中文名称而不是编码
        // 导出使用中文状态名
        row.setStatusName(Dict.label(Dict.RENTAL_STATUS, rental.getStatus()));
        // 押金状态翻译为中文
        row.setDepositStatusName(Dict.label(Dict.DEPOSIT_STATUS, rental.getDepositStatus()));
        // 实际归还时间，未归还时为空串
        row.setActualReturnTime(format(rental.getActualReturnTime()));
        // 验收结果翻译为中文
        row.setDamageResultName(Dict.label(Dict.DAMAGE_RESULT, rental.getDamageResult()));
        // 逾期费原始金额
        row.setOverdueFee(amount(rental.getOverdueFee()));
        // 损坏赔偿原始金额
        row.setDamageFee(amount(rental.getDamageFee()));
        // 押金退还金额
        row.setRefundAmount(amount(rental.getRefundAmount()));
        return row;
    }

    /**
     * 金额转导出字符串；null 输出空串而不是 "null"。
     *
     * @param value 金额
     * @return 展示文本
     */
    private String amount(BigDecimal value) {
        // 不用 toString，避免科学计数法影响 Excel 阅读
        // 避免科学计数法
        return value == null ? "" : value.toPlainString();
    }

    /**
     * 物化逾期状态：把已过结束时间且仍在使用中的租赁单转为 OVERDUE。
     * <p>逐单条件更新，影响 0 行说明已被其它流程归还或流转，直接跳过。
     * 状态迁移后发送一次逾期提醒，biz_key 保证同一租赁单不重复发送。
     */
    public void markOverdue() {
        // 只扫描使用中且结束时间已过的单；已归还/已取消的不处理
        List<Rental> list = rentalMapper.selectList(new LambdaQueryWrapper<Rental>()
                .eq(Rental::getStatus, RentalStatus.PICKED_UP.code())
                .lt(Rental::getEndTime, LocalDateTime.now()));
        // 逐单物化逾期并发送一次提醒
        for (Rental rental : list) {
            // 物化前仍做状态机断言，防止未来状态表调整后出现非法写入
            RentalStateMachine.assertTransition(RentalStatus.PICKED_UP, RentalStatus.OVERDUE);
            // 并发下可能已被归还，条件更新失败时无需补发消息
            // 并发归还时影响 0 行，跳过通知
            if (rentalMapper.markOverdue(rental.getId()) == 0) {
                continue;
            }
            // 消息幂等键为 OVERDUE:{rentalId}，与兜底提醒的 1d/3d 键区分
            messageService.notifyUserOnce(rental.getUserId(), MessageType.OVERDUE, "租赁已逾期",
                    "租赁单 " + rental.getOrderNo() + " 已逾期，逾期费按每小时 5 元计算，最高不超过押金。",
                    "OVERDUE:" + rental.getId());
        }
    }

    // 超时判断使用数据库时钟（SQL 内 NOW()），避免应用与数据库时区/时钟不一致。
    // 取消成功后释放档期，并清理该单的到期提醒。
    /** 待支付超时：原子取消并释放档期。由 ReminderService 每 2 分钟调度一次。 */
    /**
     * 批量取消超时未支付的租赁单。
     * <p>每单独立条件更新，失败（已支付或已取消）直接跳过；
     * 成功后取消提醒并发送一次自动取消通知。
     */
    public void expirePending() {
        // 查询与更新使用同一超时口径，保证边界一致
        List<Rental> list = rentalMapper.selectExpiredPending(pendingPaymentTimeoutMinutes);
        // 逐单原子取消，失败跳过
        for (Rental rental : list) {
            // 条件更新失败说明状态已变化，交给对应流程处理
            // 已支付或已取消时跳过
            if (rentalMapper.expirePending(rental.getId()) == 0) {
                continue;
            }
            // 自动取消后清理提醒调度
            reminderService.cancel(rental.getId());
            // 自动取消通知使用幂等键，重复调度不会重复打扰
            messageService.notifyUserOnce(rental.getUserId(), MessageType.SYSTEM, "预约已取消",
                    "租赁单 " + rental.getOrderNo() + " 超过支付时限，已自动取消并释放档期。",
                    "PENDING_EXPIRE:" + rental.getId());
        }
    }

    /**
     * 解析租赁状态编码，非法值返回 409（数据状态冲突）。
     *
     * @param rental 租赁单
     * @return 状态枚举
     */
    private RentalStatus statusOf(Rental rental) {
        // 状态编码非法时返回 409
        return RentalStatus.fromCode(rental.getStatus())
                .orElseThrow(() -> new BizException(ResultCode.CONFLICT, "无效的租赁状态"));
    }

    /**
     * 读取当前登录用户，不存在时返回 401。
     *
     * @return 当前用户实体
     */
    private User currentUser() {
        // 会话用户不存在视为未授权
        User user = userMapper.selectById(StpKit.USER.getLoginIdAsLong());
        // 登录 id 对应用户已被删除时返回 401，要求重新登录
        if (user == null) {
            throw new BizException(ResultCode.UNAUTHORIZED, "用户不存在");
        }
        // 返回当前用户实体
        return user;
    }

    // 单条场景直接查询提醒状态，避免为一条记录做批量读取。
    /** 单条详情：实时读取提醒状态。 */
    /**
     * 租赁单转视图，补齐工具主图与提醒状态。
     *
     * @param rental 租赁单
     * @return 租赁视图
     */
    private RentalVO toVO(Rental rental) {
        // 基础字段映射
        RentalVO vo = rentalVoMapper.toVO(rental);
        // 工具可能已被删除，主图允许为空，不影响租赁单展示
        Tool tool = toolMapper.selectById(rental.getToolId());
        vo.setToolImageUrl(tool == null ? null : tool.getImageUrl());
        // 单条详情实时读取提醒提前量
        // 单条详情实时读取提醒状态
        vo.setRemindBeforeMinutes(reminderService.leadOf(rental.getId()));
        // 计划提醒时刻可能为空（用户选择不提醒或已发送）
        // 计划提醒时刻可能为空
        vo.setRemindAt(reminderService.remindAtOf(rental.getId()));
        return vo;
    }

    // 费用与资金流水按创建时间升序返回，便于还原结算过程。
    /** 详情附带费用与支付流水：资金事实以流水为准，单行金额只作展示快照。 */
    /**
     * 租赁详情：在基础视图上追加费用明细与资金流水。
     *
     * @param rental 租赁单
     * @return 详情视图
     */
    private RentalVO toDetailVO(Rental rental) {
        // 先构造基础视图
        RentalVO vo = toVO(rental);
        // 费用明细按创建时间升序，便于还原结算顺序
        // 费用明细按时间升序
        vo.setFees(rentalFeeMapper.selectList(new LambdaQueryWrapper<RentalFee>()
                        .eq(RentalFee::getRentalId, rental.getId())
                        .orderByAsc(RentalFee::getCreateTime))
                .stream().map(this::toFeeVO).toList());
        // 资金流水同样按创建时间升序
        // 资金流水按时间升序
        vo.setPayments(paymentRecordMapper.selectList(new LambdaQueryWrapper<PaymentRecord>()
                        .eq(PaymentRecord::getRentalId, rental.getId())
                        .orderByAsc(PaymentRecord::getCreateTime))
                .stream().map(this::toPaymentVO).toList());
        return vo;
    }

    /**
     * 费用实体转视图，补全费用类型中文名。
     *
     * @param fee 费用实体
     * @return 费用视图
     */
    private RentalFeeVO toFeeVO(RentalFee fee) {
        RentalFeeVO vo = new RentalFeeVO();
        // 费用视图逐字段复制，避免实体字段变化直接暴露到接口
        vo.setId(fee.getId());
        // 费用类型编码
        vo.setFeeType(fee.getFeeType());
        vo.setFeeTypeName(RentalFeeType.fromCode(fee.getFeeType())
                .map(RentalFeeType::label).orElse(fee.getFeeType()));
        // 金额
        vo.setAmount(fee.getAmount());
        // 费用说明
        vo.setReason(fee.getReason());
        // 经办人
        vo.setOperatorName(fee.getOperatorName());
        // 记录时间
        vo.setCreateTime(fee.getCreateTime());
        return vo;
    }

    /**
     * 资金流水实体转视图，补全流水类型中文名。
     *
     * @param record 流水实体
     * @return 流水视图
     */
    private PaymentRecordVO toPaymentVO(PaymentRecord record) {
        PaymentRecordVO vo = new PaymentRecordVO();
        // 流水视图逐字段复制
        vo.setId(record.getId());
        // 流水类型编码
        vo.setRecordType(record.getRecordType());
        vo.setRecordTypeName(PaymentRecordType.fromCode(record.getRecordType())
                .map(PaymentRecordType::label).orElse(record.getRecordType()));
        // 资金金额
        vo.setAmount(record.getAmount());
        // 经办人
        vo.setOperatorName(record.getOperatorName());
        // 流水说明
        vo.setRemark(record.getRemark());
        // 记录时间
        vo.setCreateTime(record.getCreateTime());
        return vo;
    }

    // 分页映射只做三次批量读取：工具主图、提醒提前量、计划提醒时刻。
    /** 列表/分页：工具一次批量加载，提醒一次批量读取，避免 N+1。 */
    /**
     * 批量转换租赁视图。
     * <p>先收集当页工具 id 与租赁 id，再分别批量查询，避免逐行访问数据库与 Redis。
     *
     * @param rentals 租赁单列表
     * @return 租赁视图列表
     */
    private List<RentalVO> toVOList(List<Rental> rentals) {
        // 空页直接返回，避免构造空 Map 与无效查询
        // 空页直接返回
        if (rentals.isEmpty()) {
            return List.of();
        }
        // 收集当页工具 id，随后一次批量查询主图
        // 当页工具 id 去重
        List<Long> toolIds = rentals.stream().map(Rental::getToolId)
                .filter(Objects::nonNull).distinct().toList();
        // 一次批量加载当页工具，仅用于取主图
        Map<Long, Tool> tools = toolIds.isEmpty() ? Map.of()
                : toolMapper.selectBatchIds(toolIds).stream()
                .collect(Collectors.toMap(Tool::getId, tool -> tool));
        // 收集当页租赁 id，用于批量读取提醒状态
        // 当页租赁 id 用于批量读取提醒
        List<Long> rentalIds = rentals.stream().map(Rental::getId).toList();
        // 一次批量读取用户选择的提醒提前量
        Map<Long, Integer> leads = reminderService.leadsOf(rentalIds);
        // 一次批量读取计划提醒时刻
        Map<Long, LocalDateTime> remindAts = reminderService.remindAtsOf(rentalIds);
        return rentals.stream().map(rental -> {
            // 使用预加载的批量结果逐条组装
            RentalVO vo = rentalVoMapper.toVO(rental);
            // 工具已删除时主图为空，不影响其它字段
            Tool tool = tools.get(rental.getToolId());
            vo.setToolImageUrl(tool == null ? null : tool.getImageUrl());
            vo.setRemindBeforeMinutes(leads.get(rental.getId()));
            vo.setRemindAt(remindAts.get(rental.getId()));
            return vo;
        }).toList();
    }

    /**
     * 时间格式化；null 输出空串。
     *
     * @param time 时间
     * @return yyyy-MM-dd HH:mm 文本
     */
    private String format(LocalDateTime time) {
        // 空时间输出空串，避免导出出现 null
        return time == null ? "" : time.format(DATE_TIME);
    }
}
