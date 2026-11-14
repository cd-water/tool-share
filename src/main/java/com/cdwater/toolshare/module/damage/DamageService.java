package com.cdwater.toolshare.module.damage;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cdwater.toolshare.module.auth.Admin;
import com.cdwater.toolshare.module.auth.AdminMapper;
import com.cdwater.toolshare.module.auth.User;
import com.cdwater.toolshare.module.auth.UserMapper;
import com.cdwater.toolshare.module.message.MessageService;
import com.cdwater.toolshare.module.rental.Rental;
import com.cdwater.toolshare.module.rental.RentalMapper;
import com.cdwater.toolshare.module.tool.Tool;
import com.cdwater.toolshare.module.tool.ToolMapper;
import com.cdwater.toolshare.module.tool.ToolService;
import com.cdwater.toolshare.shared.config.StpKit;
import com.cdwater.toolshare.shared.enums.AcceptResult;
import com.cdwater.toolshare.shared.enums.DamageLevel;
import com.cdwater.toolshare.shared.enums.MessageType;
import com.cdwater.toolshare.shared.enums.RentalStatus;
import com.cdwater.toolshare.shared.enums.RepairProgress;
import com.cdwater.toolshare.shared.enums.ReportStatus;
import com.cdwater.toolshare.shared.enums.ToolStatus;
import com.cdwater.toolshare.shared.enums.VerifyResult;
import com.cdwater.toolshare.shared.sequence.SequenceService;
import com.cdwater.toolshare.shared.tx.AfterCommit;
import com.cdwater.toolshare.shared.web.BizException;
import com.cdwater.toolshare.shared.web.PageQuery;
import com.cdwater.toolshare.shared.web.PageVO;
import com.cdwater.toolshare.shared.web.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
/**
 * 报修领域服务：负责报修单创建、核验、维修进度与验收的完整生命周期。
 * <p>关键约束：
 * <ul>
 *   <li>报修必须绑定当前用户仍在使用中的租赁，并锁定租赁行以串行化
 *       同一租赁的并发提交；</li>
 *   <li>所有状态变更先经 {@link DamageStateMachine} 校验，再使用带原状态
 *       条件的 UPDATE 落库，防止并发请求重复迁移；</li>
 *   <li>工具状态联动与消息通知在事务内或提交后执行，消息失败不影响主流程；</li>
 *   <li>同一租赁的处理中报修由数据库唯一索引兜底。</li>
 * </ul>
 * <p>事务与并发：创建、核验、进度、验收均在事务内完成读改写；核验与验收
 * 使用 {@code eq(原状态)} 的条件更新，更新行数为 0 即说明存在并发操作，
 * 统一返回 409。创建时对租赁行加锁，确保「查询活跃报修 + 插入」之间不会
 * 被同一租赁的其他请求插入。
 * <p>通知策略：消息通过 {@link AfterCommit} 延迟到事务提交后执行，避免
 * 事务回滚后仍向用户推送；短信或站内信失败只影响通知，不影响报修状态。
 * <p>快照策略：报修单保存租赁、工具与报修人快照。即使后续租赁结束、
 * 工具改名或下架，历史报修仍能独立还原提交时的上下文。
 * <p>权限：居民端查询与详情按会话用户过滤；管理端不做归属过滤，但写操作
 * 仍受报修单当前状态约束。
 * <p>模块关系：租赁提供使用状态，{@link ToolService} 负责工具状态迁移，
 * {@link MessageService} 负责通知，{@link SequenceService} 生成报修单号，
 * {@link StpKit} 提供当前居民与管理员身份。
 * <p>异常策略：参数与状态类问题抛 {@link BizException} 并携带明确的
 * {@link ResultCode}；数据库唯一索引冲突转换为 409；其余异常交由全局
 * 异常处理器兜底。
 */
public class DamageService {

    /** 报修单关联租赁仅在「使用中 / 已逾期」时可核验。 */
    // 预约中尚未交付、已归还未在使用，均不允许发起新的损坏报修。
    private static final Set<String> IN_USE_RENTAL_STATUS =
            Set.of(RentalStatus.PICKED_UP.code(), RentalStatus.OVERDUE.code());
    /** 验收后允许的工具目标状态；已租出不可由验收直接指定。 */
    // 报废是终态；维护中可继续维修；可用需结合租赁状态二次判定。
    private static final Set<ToolStatus> ACCEPT_TOOL_STATUS =
            Set.of(ToolStatus.AVAILABLE, ToolStatus.MAINTENANCE, ToolStatus.SCRAPPED);

    /** 报修单持久化访问入口。 */
    private final DamageReportMapper damageReportMapper;
    /** 租赁单访问入口，用于校验归属、在用状态与行锁。 */
    private final RentalMapper rentalMapper;
    /** 工具查询入口，列表页用它批量补齐工具主图。 */
    private final ToolMapper toolMapper;
    /** 居民账号查询入口，用于生成报修人名称与联系电话快照。 */
    private final UserMapper userMapper;
    /** 管理员账号查询入口，用于记录核验/验收操作人。 */
    private final AdminMapper adminMapper;
    /** 工具领域服务，负责工具状态迁移与状态机校验。 */
    private final ToolService toolService;
    /** 全局序列服务，生成业务报修单号。 */
    private final SequenceService sequenceService;
    /** 消息服务，在事务提交后向居民或管理员推送报修通知。 */
    private final MessageService messageService;
    /** 报修实体到视图对象的映射器。 */
    private final DamageReportVoMapper damageReportVoMapper;
    /** 事务提交后回调，保证消息不会因事务回滚而误发。 */
    private final AfterCommit afterCommit;

    @Transactional
    /**
     * 创建报修单。
     * <p>处理顺序：锁定租赁行 → 校验归属与在用状态 → 检查是否已有处理中的
     * 报修 → 写入报修单 → 事务提交后通知全部管理员。
     * <p>并发说明：租赁行锁使同一租赁的并发报修串行执行，数据库唯一索引
     * <p>注意：查询活跃报修与插入之间虽然已由行锁保护，但唯一索引仍然必要，
     * 因为它同时约束了绕过本服务的写入路径（如数据修复脚本）。
     * <p>参数中的图片地址在落库前只做拼接，不校验对象是否存在；图片属于
     * 辅助信息，缺失不应阻塞报修主流程。
     * 证据，缺失不应阻塞报修主流程。
     * <p>图片地址只做拼接，不校验对象存储中是否存在。
     * <p>报修单号由序列服务生成，与数据库自增 ID 解耦。
     * <p>图片地址只做长度与数量校验，不校验对象是否存在；图片属于辅助
     * <p>报修人电话优先取账号最新值，其次取租赁下单时的电话，保证通知可达。
     * 则在极端情况下作为最后一道防线。
     *
     * @param dto 报修参数
     * <p>返回前会补齐工具主图，居民端可直接渲染详情。
     * @return 新建报修单视图对象
     * @throws BizException 租赁不存在、状态不允许或已有处理中报修时抛出
     */
    public DamageReportVO create(DamageCreateDTO dto) {
    // 事务边界覆盖报修单写入与消息登记。
    // 行锁只在创建阶段使用，避免影响后续读请求。
    // 返回视图对象前补齐工具主图。
        // 锁定租赁行，串行化同一租赁的并发报修。
        // 校验归属与在用状态，再检查是否已有处理中的报修。
        // 写入报修单后，在事务提交后通知全部管理员。
        // 创建流程在单个事务内完成：行锁 → 归属/状态校验 → 活跃报修判重 → 落库。
        // 任何一步失败都直接抛异常回滚，不会留下半张报修单。
        // 当前登录居民；报修单归属只信任会话，不接收前端传入的用户 ID
        Long userId = StpKit.USER.getLoginIdAsLong();
        // 锁租赁行，串行化同一租赁的并发报修；数据库唯一索引兜底
        Rental rental = rentalMapper.selectByIdForUpdate(dto.getRentalId());
        // 租赁不存在与不属于当前用户统一返回 404，避免泄露他人租赁信息
        if (rental == null || !rental.getUserId().equals(userId)) {
            // 不区分「租赁不存在」与「租赁不属于你」，避免暴露他人租赁主键。
            throw new BizException(ResultCode.NOT_FOUND, "租赁单不存在");
        }
        // 只有已取用或已逾期的租赁才允许报修，预约中/已归还不进入报修流程
        if (!IN_USE_RENTAL_STATUS.contains(rental.getStatus())) {
            // 预约中尚未交付、已归还未在使用，均不满足报修前置条件。
            throw new BizException(ResultCode.BAD_REQUEST, "仅使用中的租赁可以报修");
        }
        // 活跃报修 = 待核验 + 已核验 + 维修中；已完成/已驳回/已报废不阻塞新报修
        long active = damageReportMapper.selectCount(new LambdaQueryWrapper<DamageReport>()
                .eq(DamageReport::getRentalId, rental.getId())
                .in(DamageReport::getStatus, activeStatusCodes()));
        // 同一租赁在处理完成前不允许重复报修，避免维修进度与费用被多张单子分摊
        if (active > 0) {
            throw new BizException(ResultCode.CONFLICT, "该租赁已有处理中的报修单");
        }
        // 读取居民资料生成显示名；用户已被删除时回退到租赁上的承租人电话
        User user = userMapper.selectById(userId);
        // 报修单是「租赁 + 工具 + 报修人」三类信息的快照，创建后不再回查实时数据。
        DamageReport report = new DamageReport();
        // 报修单号独立于数据库自增 ID，便于对外展示与人工沟通。
        report.setReportNo(sequenceService.nextDamageReportNo());
        // 租赁 ID 用于后续核验时重新锁定租赁，判断是否仍在使用中。
        report.setRentalId(rental.getId());
        // 工具 ID 用于核验/验收时联动工具状态。
        report.setToolId(rental.getToolId());
        // 冗余工具编号：即使工具后续改名或下架，报修单仍能独立展示。
        report.setToolCode(rental.getToolCode());
        // 冗余工具名称：列表检索与通知文案直接使用，减少联表查询。
        report.setToolName(rental.getToolName());
        // 归属以会话为准，后续居民端详情与列表都按该字段做数据权限过滤。
        report.setUserId(userId);
        // 显示名优先真实姓名，其次昵称，缺失时使用「居民」。
        report.setReporterName(displayName(user));
        // 优先使用账号最新电话，账号缺失时回退到租赁下单时留存的电话。
        report.setReporterPhone(user == null ? rental.getRenterPhone() : user.getPhone());
        // 统一去除首尾空白，避免同一部位因空格差异产生重复文本。
        report.setDamagePart(dto.getDamagePart().trim());
        // 描述允许较长，但前端已限制长度；此处只做空白清理。
        report.setDescription(dto.getDescription().trim());
        // 数据库以逗号分隔保存图片地址，空列表写入空串而非 NULL，简化读取逻辑。
        report.setImages(dto.getImages() == null ? "" : String.join(",", dto.getImages()));
        // 新报修统一从「待核验」开始，后续状态只能由核验/维修/验收接口推进
        report.setStatus(ReportStatus.SUBMITTED.code());
        // 捕获唯一索引冲突：并发请求可能已抢先插入同一租赁的活跃报修。
        try {
            // 唯一索引冲突说明并发请求已抢先创建，转换为业务冲突而非 500
            damageReportMapper.insert(report);
        // 与前置的活跃报修检查同义，但覆盖并发窗口，返回一致的业务错误。
        } catch (DuplicateKeyException e) {
            throw new BizException(ResultCode.CONFLICT, "该租赁已有处理中的报修单");
        }
        // 事务提交后再发通知：回滚时不会产生「幽灵报修」提醒
        afterCommit.run(() -> messageService.notifyAdmins(MessageType.REPAIR, "新的损坏报修",
                report.getReporterName() + " 提交了报修单 " + report.getReportNo()
                        + "（工具 " + report.getToolCode() + "），请及时核验。",
                "REPAIR_SUBMIT:" + report.getId()));
        return toVO(report);
    }

    @Transactional
    /**
     * 核验报修单。
     * <p>只有 SUBMITTED 状态可以核验；误报直接驳回，确认或存疑则把报修单
     * 推进到 VERIFIED，并把关联工具切换为 MAINTENANCE。
     * <p>核验时重新锁定并校验租赁状态，防止「工具已归还后才核验」把工具
     * <p>核验结论中的「存疑」按确认处理，仍会安排维修；只有明确的误报
     * 才会驳回。这样可以避免因核验标准分歧导致真实损坏被遗漏。
     * <p>误报分支只更新报修单，不触碰工具；确认分支才调用工具服务迁移状态。
     * 也不进入后续维修与验收流程。
     * <p>核验备注会覆盖上一次备注，符合本次核验语义。
     * <p>确认或存疑都会把工具置为维修中，由维修人员进一步判断。
     * <p>若核验为误报，报修单直接进入终态 REJECTED，不触发工具状态迁移，
     * <p>核验结果、核验人、核验时间与维修安排在同一条 UPDATE 中写入。
     * 错误地重新置为维修中。
     *
     * @param id  报修单主键
     * @param dto 核验参数
     * <p>核验成功后报修单状态不再允许回退。
     * @throws BizException 状态不允许、租赁已结束或并发修改时抛出
     */
    public void verify(Long id, DamageVerifyDTO dto) {
    // 核验与工具状态迁移在同一事务内完成。
    // 条件更新失败时返回 409，不修改任何数据。
    // 通知在事务提交后执行。
        // 只允许 SUBMITTED 状态核验。
        // 误报直接驳回，确认或存疑则把工具置为维修中。
        // 核验前重新锁定租赁，防止旧报修影响已归还的工具。
        // 核验是唯一能把 SUBMITTED 推进到 VERIFIED/REJECTED 的入口。
        // 先校验状态，再重新确认租赁仍在进行，最后才更新报修单与工具。
        DamageReport report = getReport(id);
        // 只接受枚举白名单内的核验结论，防止前端写入任意字符串
        VerifyResult result = VerifyResult.fromCode(dto.getVerifyResult())
                .orElseThrow(() -> new BizException(ResultCode.BAD_REQUEST, "无效的核验结果"));
        // 误报 → REJECTED；确认/存疑 → VERIFIED，两条分支都需通过状态机校验
        ReportStatus target = result == VerifyResult.FALSE
                ? ReportStatus.REJECTED : ReportStatus.VERIFIED;
        // 非 SUBMITTED 一律 409（含重复核验）
        if (statusOf(report) != ReportStatus.SUBMITTED) {
            // 已核验、维修中或终态的报修都不能再次核验。
            throw new BizException(ResultCode.CONFLICT, "仅待核验的报修可核验");
        }
        // 即使前面已判断状态，仍走状态机：保证迁移规则只有一处定义。
        DamageStateMachine.assertTransition(ReportStatus.SUBMITTED, target);
        // 旧报修不得把已归还或已新租的工具再次置维修
        // 再次锁定租赁：核验期间租赁可能已被归还，此时不允许再把工具置维修
        Rental rental = rentalMapper.selectByIdForUpdate(report.getRentalId());
        if (rental == null || !IN_USE_RENTAL_STATUS.contains(rental.getStatus())) {
            // 租赁已归还或已产生新租约时，旧报修不能再影响工具状态。
            throw new BizException(ResultCode.CONFLICT, "关联租赁单已结束，不能再核验该报修");
        }

        LambdaUpdateWrapper<DamageReport> update = new LambdaUpdateWrapper<DamageReport>()
                .eq(DamageReport::getId, id)
                .eq(DamageReport::getStatus, ReportStatus.SUBMITTED.code())
                // 状态由核验结论决定：误报为 REJECTED，其余为 VERIFIED。
                .set(DamageReport::getStatus, target.code())
                // 记录操作管理员，便于审计与追责。
                .set(DamageReport::getVerifier, currentAdminName())
                // 核验时间以服务端时间为准，避免客户端时钟偏差。
                .set(DamageReport::getVerifyTime, LocalDateTime.now())
                // 带 status=SUBMITTED 条件更新：并发核验时只有一个请求能成功
                .set(DamageReport::getVerifyResult, result.code())
                // 允许核验时先指定维修负责人，后续进度更新可覆盖。
                .set(DamageReport::getRepairPerson, dto.getRepairPerson())
                // 预计时长仅用于展示，不参与调度计算。
                .set(DamageReport::getRepairEstimate, dto.getRepairEstimate())
                // 核验备注可为空，空值会覆盖旧备注，符合本次核验语义。
                .set(DamageReport::getRemark, dto.getRemark());
        if (damageReportMapper.update(null, update) == 0) {
            throw new BizException(ResultCode.CONFLICT, "报修单状态已变更，请刷新后重试");
        }

        // 误报分支提前返回：不触碰工具，避免把正常工具置为维修中。
        if (result == VerifyResult.FALSE) {
            afterCommit.run(() -> messageService.notifyUser(report.getUserId(), MessageType.REPAIR, "报修已驳回",
                    "报修单 " + report.getReportNo() + " 经核验为误报，工具无需维修。"));
            return;
        }
        Tool tool = toolService.getTool(report.getToolId());
        // 工具状态由工具服务统一迁移，确保工具状态机与审计字段同步更新。
        toolService.transition(tool, ToolStatus.MAINTENANCE, "REPAIR", "报修核验确认，工具转维修中");
        // 通知在提交后发送；若后续步骤失败回滚，居民不会收到错误提醒。
        afterCommit.run(() -> messageService.notifyUser(report.getUserId(), MessageType.REPAIR, "报修已确认",
                "报修单 " + report.getReportNo() + " 已核验，维修人员将尽快处理。"));
    }

    @Transactional
    /**
     * 更新维修进度。
     * <p>VERIFIED 与 REPAIRING 均可更新；其余状态（待核验或终态）会经状态机
     * 校验后返回 409。首次更新会以 SQL 的 COALESCE 写入维修开始时间，
     * <p>进度更新不要求填写全部字段：费用、预计时长、负责人均可选，
     * 传入空值时保留历史记录，避免后一次更新把先前信息清空。
     * <p>维修开始时间使用 SQL 的 COALESCE 原子写入，避免先查后写的竞态。
     * <p>维修开始时间只在首次进度更新时写入。
     * <p>进度为 COMPLETED 后仍需管理员验收，报修单不会自动完成。
     * <p>进度更新不会改变报修单的核验信息，只刷新维修相关字段与更新时间。
     * <p>进度说明与费用会同步通知报修人；维修完成时额外提醒管理员验收。
     * 后续更新不会覆盖。
     *
     * @param id  报修单主键
     * @param dto 进度参数
     * <p>首次更新进度会写入维修开始时间，后续更新保持原值。
     * @throws BizException 状态不允许或并发修改时抛出
     */
    public void progress(Long id, DamageProgressDTO dto) {
    // 进度更新只写报修单，不直接修改工具状态。
    // 可选字段为空时保留数据库已有值。
    // 通知内容包含最新进度说明。
        // VERIFIED 与 REPAIRING 均可更新进度。
        // 首次更新写入维修开始时间，后续保持不变。
        // 进度为 COMPLETED 时额外提醒管理员验收。
        // 进度更新只刷新维修相关字段，不改变报修单主状态（仍为 REPAIRING）。
        // 首次更新通过 COALESCE 固定维修开始时间，便于统计维修时长。
        DamageReport report = getReport(id);
        ReportStatus from = statusOf(report);
        // 进度编码同样做白名单校验
        RepairProgress progress = RepairProgress.fromCode(dto.getProgressStatus())
                .orElseThrow(() -> new BizException(ResultCode.BAD_REQUEST, "无效的维修进度"));
        // 这里仍调用状态机而非直接抛错：错误信息与状态机保持同一套中文文案。
        if (from != ReportStatus.VERIFIED && from != ReportStatus.REPAIRING) {
            // 已提交 / 终态均不可更新进度，统一 409
            DamageStateMachine.assertTransition(from, ReportStatus.REPAIRING);
        }

        // 记录操作管理员显示名；该值当前只用于通知文案与审计
        String verifier = currentAdminName();
        LambdaUpdateWrapper<DamageReport> update = new LambdaUpdateWrapper<DamageReport>()
                .eq(DamageReport::getId, id)
                // 以读取到的原状态为条件，防止并发请求造成状态跳跃。
                .eq(DamageReport::getStatus, from.code())
                // 首次更新进度时把报修单从 VERIFIED 推进到 REPAIRING。
                .set(DamageReport::getStatus, ReportStatus.REPAIRING.code())
                // 带原状态条件的更新：状态已被并发改变时更新行数为 0
                .set(DamageReport::getProgressStatus, progress.code())
                // 进度说明必填，直接展示给报修居民。
                .set(DamageReport::getProgressNote, dto.getProgressNote())
                // 费用、预计时长与维修人均为可选字段：为空时保留已有值，不覆盖
                // 因此只对非空字段拼接 SET 片段，避免把已有值改成 NULL。
                .set(DamageReport::getProgressUpdateTime, LocalDateTime.now())
                // 只在首次更新时写入开始时间，重复进度更新保持原始开工时间
                .setSql("repair_start_time = COALESCE(repair_start_time, NOW())");
        if (dto.getRepairCost() != null) {
            update.set(DamageReport::getRepairCost, dto.getRepairCost());
        }
        // 预计时长可选，空字符串不覆盖已有值
        if (StringUtils.hasText(dto.getRepairEstimate())) {
            // 预计时长非空时才覆盖，保留此前填写值。
            update.set(DamageReport::getRepairEstimate, dto.getRepairEstimate());
        }
        // 维修负责人可中途更换；只有显式传值时才更新
        if (StringUtils.hasText(dto.getRepairPerson())) {
            // 维修负责人非空时才覆盖，支持中途换人。
            update.set(DamageReport::getRepairPerson, dto.getRepairPerson());
        }
        if (damageReportMapper.update(null, update) == 0) {
            throw new BizException(ResultCode.CONFLICT, "报修单状态已变更，请刷新后重试");
        }
        // 事务提交后再发进度通知：保证居民看到的进度已真实落库
        // 通知包含最新进度说明；维修完成时再单独提醒管理员验收。
        afterCommit.run(() -> {
            messageService.notifyUser(report.getUserId(), MessageType.REPAIR, "维修进度更新",
                    "报修单 " + report.getReportNo() + " 进度：" + progress.label()
                            + "。" + dto.getProgressNote());
            // 维修完成时额外提醒管理员验收，bizKey 防止重复提醒
            if (progress == RepairProgress.COMPLETED) {
                // 维修完成时提醒管理员验收，bizKey 防止重复发送。
                messageService.notifyAdmins(MessageType.REPAIR, "维修已完成",
                        "报修单 " + report.getReportNo() + "（工具 " + report.getToolCode()
                                + "）维修已完成，请及时验收。",
                        "REPAIR_COMPLETE:" + id);
            }
        });
    }

    @Transactional
    /**
     * 验收维修结果并结束报修流程。
     * <p>前置条件：报修单处于 REPAIRING 且最新进度为 COMPLETED。验收结论
     * 与请求的工具目标状态会经 {@link #resolveAcceptToolStatus} 修正后再落库，
     * <p>验收结论与工具目标状态存在约束：非合格不能直接置为可用；
     * 租赁尚未结束时即使验收合格，工具也保持维修中，待归还后再释放。
     * <p>验收更新同时锁定报修单状态与进度，任一条件不满足都不会产生部分更新。
     * <p>报废验收会同时结束报修单与工具生命周期。
     * <p>非合格验收会保持工具维修中，避免未修好的工具重新流入市场。
     * <p>报废是工具终态，验收后不能再通过其他报修把它改回可用。
     * <p>工具损坏等级按验收结论映射，最近维修时间统一刷新为当前时间。
     * 随后联动工具状态、损坏等级与最近维修时间。
     *
     * @param id  报修单主键
     * @param dto 验收参数
     * <p>验收后报修单进入终态，不再接受进度更新。
     * @throws BizException 状态不允许、验收结果或工具状态非法时抛出
     */
    public void accept(Long id, DamageAcceptDTO dto) {
    // 验收同时结束报修单并更新工具。
    // 两个操作在同一事务中成功或回滚。
    // 验收后不再接受进度更新。
        // 要求报修单处于维修中且最新进度为已完成。
        // 验收结论与工具目标状态组合修正后再落库。
        // 同步更新工具状态、损坏等级与最近维修时间。
        // 验收会同时结束报修单与更新工具，必须保证两者在同一事务中成功或回滚。
        // 更新条件同时锁定「维修中 + 进度已完成」，防止并发验收。
        DamageReport report = getReport(id);
        ReportStatus from = statusOf(report);
        // 未进入维修或已处于终态的报修都不允许验收
        if (from != ReportStatus.REPAIRING) {
            // 未进入维修 / 终态重复验收，统一 409
            throw new BizException(ResultCode.CONFLICT, "仅维修中的报修可验收");
        }
        // 仅「维修已完成」的进度可验收，避免维修中途被提前关闭
        if (!RepairProgress.COMPLETED.code().equals(report.getProgressStatus())) {
            // 只有进度为 COMPLETED 时才允许验收，避免中途关闭报修。
            throw new BizException(ResultCode.CONFLICT, "维修尚未完成，不能验收");
        }
        // 验收结论白名单校验
        AcceptResult result = AcceptResult.fromCode(dto.getAcceptResult())
                .orElseThrow(() -> new BizException(ResultCode.BAD_REQUEST, "无效的验收结果"));
        // 请求的工具状态先做枚举校验，再与验收结论组合修正
        ToolStatus requested = ToolStatus.fromCode(dto.getToolStatusAfter())
                .orElseThrow(() -> new BizException(ResultCode.BAD_REQUEST, "无效的工具状态"));
        // 已租出等状态不能由验收直接指定，只允许可用/维修中/报废
        if (!ACCEPT_TOOL_STATUS.contains(requested)) {
            // 已租出等状态不能由验收直接指定，避免与租赁状态冲突。
            throw new BizException(ResultCode.BAD_REQUEST, "验收后工具状态只能是可用、维修中或已报废");
        }
        // 部分合格或不合格的维修结果不能把工具直接置为可用
        if (result != AcceptResult.QUALIFIED && requested == ToolStatus.AVAILABLE) {
            throw new BizException(ResultCode.BAD_REQUEST,
                    result.label() + "的工具不能直接置为可用");
        }
        // 结合租赁是否仍在进行修正：租赁未结束时合格工具也保持维修中
        ToolStatus target = resolveAcceptToolStatus(report, result, requested);
        // 工具报废时报修单同步进入 SCRAPPED；其余情况进入 COMPLETED。
        ReportStatus reportTarget = target == ToolStatus.SCRAPPED
                ? ReportStatus.SCRAPPED : ReportStatus.COMPLETED;
        // 报废进入 SCRAPPED，其余进入 COMPLETED；先校验再更新
        DamageStateMachine.assertTransition(from, reportTarget);

        LambdaUpdateWrapper<DamageReport> update = new LambdaUpdateWrapper<DamageReport>()
                .eq(DamageReport::getId, id)
                // 验收要求报修单仍处于维修中。
                .eq(DamageReport::getStatus, ReportStatus.REPAIRING.code())
                // 同时要求最新进度为已完成，两者共同构成验收前置条件。
                .eq(DamageReport::getProgressStatus, RepairProgress.COMPLETED.code())
                // 报废进入 SCRAPPED，其余进入 COMPLETED。
                .set(DamageReport::getStatus, reportTarget.code())
                // 记录验收管理员，便于审计。
                .set(DamageReport::getAcceptor, currentAdminName())
                // 验收时间以服务端时间为准。
                .set(DamageReport::getAcceptTime, LocalDateTime.now())
                // 同时锁定状态与进度，等价于对验收操作的乐观并发控制
                .set(DamageReport::getAcceptResult, result.code())
                // 保存最终工具状态快照，便于详情展示与历史追溯。
                .set(DamageReport::getToolStatusAfter, target.code());
        if (dto.getRepairCost() != null) {
            update.set(DamageReport::getRepairCost, dto.getRepairCost());
        }
        // 验收备注可选，空字符串不覆盖核验时写入的备注
        if (StringUtils.hasText(dto.getRemark())) {
            // 验收备注非空时覆盖核验备注，记录最终处理说明。
            update.set(DamageReport::getRemark, dto.getRemark());
        }
        if (damageReportMapper.update(null, update) == 0) {
            throw new BizException(ResultCode.CONFLICT, "报修单状态已变更，请刷新后重试");
        }

        Tool tool = toolService.getTool(report.getToolId());
        // 同步工具：记录最近维修时间，并按验收结论回写损坏等级
        tool.setLastRepairTime(LocalDateTime.now());
        // 损坏等级按验收结论映射：合格=轻微，部分合格=中等，不合格=严重
        tool.setDamageLevel(switch (result) {
            // 合格：仅轻微损耗，工具可继续使用。
            case QUALIFIED -> DamageLevel.LIGHT.code();
            // 部分合格：存在中度损坏，后续使用需重点关注。
            case PARTIAL -> DamageLevel.MEDIUM.code();
            // 不合格：损坏严重，工具进入维修中或报废。
            case UNQUALIFIED -> DamageLevel.SEVERE.code();
        });
        // 工具最终状态由工具服务统一迁移，报修单只记录验收结论快照。
        toolService.transition(tool, target, "REPAIR", "维修验收：" + result.label());
        // 验收完成后通知报修人，内容包含验收结论与工具最终状态
        afterCommit.run(() -> messageService.notifyUser(report.getUserId(), MessageType.REPAIR, "维修验收完成",
                "报修单 " + report.getReportNo() + " 验收结果：" + result.label()
                        + "，工具状态：" + target.label() + "。"));
    }

    /**
     * 验收结论与工具目标状态组合：
     * <ul>
     *   <li>合格：租赁已结束才可置为可用；仍在租赁中保持维修中</li>
     *   <li>部分合格 / 不合格：只能维修中或报废</li>
     * </ul>
     * <p>组合规则：
     * <ol>
     *   <li>请求报废：无条件进入 SCRAPPED；</li>
     *   <li>非合格：保持 MAINTENANCE，避免未修好的工具回到可用；</li>
     *   <li>合格但租赁仍在进行：保持 MAINTENANCE，待归还后再释放；</li>
     *   <li>合格且租赁已结束：采用请求的目标状态。</li>
     * </ol>
     * <p>该方法不修改工具或租赁，只返回建议状态。
     * <p>推导依赖租赁当前状态，因此调用前必须确保报修单仍处于维修中。
     * <p>该方法只做状态推导，不写数据库；最终状态由验收更新统一落库。
     *   <li>报废：工具终态，不可再被验收改回</li>
     */
    private ToolStatus resolveAcceptToolStatus(DamageReport report, AcceptResult result,
                                               ToolStatus requested) {
                                               // 报废优先，直接返回终态。
                                               // 非合格只能保持维修中。
                                               // 租赁未结束时合格工具也保持维修中。
        // 报废优先级最高：无论验收结论如何，请求报废即进入终态。
        if (requested == ToolStatus.SCRAPPED) {
            return ToolStatus.SCRAPPED;
        }
        // 部分合格或不合格时，工具只能保持维修中，不能直接回到可用。
        if (result != AcceptResult.QUALIFIED) {
            return ToolStatus.MAINTENANCE;
        }
        // 只读查询租赁状态，用于判断租赁是否已结束。
        Rental rental = rentalMapper.selectById(report.getRentalId());
        // 租赁仍在进行时，即使验收合格也保持维修中，避免工具被重复租出。
        boolean inUse = rental != null && IN_USE_RENTAL_STATUS.contains(rental.getStatus());
        // 可用状态仅在租赁已结束时生效；否则降级为维修中。
        return requested == ToolStatus.AVAILABLE && inUse
                ? ToolStatus.MAINTENANCE : requested;
    }

    /**
     * <p>归属条件来自会话，居民之间数据天然隔离。
     * 分页查询当前居民的报修单。
     * <p>工具删除后主图为 null，列表仍可正常返回。
     * <p>批量查询只读取工具 ID 与主图，避免加载完整工具实体。
     * <p>工具已删除时主图为空，前端使用占位图，不影响列表其他字段。
     * <p>列表项通过一次批量查询补齐工具主图，避免逐行查询。
     * <p>分页参数由 PageQuery 统一处理，避免超大 size 拖垮数据库。
     * <p>关键字匹配报修单号或工具编号，状态可选；按创建时间倒序。
     *
     * @param keyword 可选模糊关键字
     * @param status  可选状态编码
     * @param page    页码
     * @param size    每页条数
     * @return 报修单分页结果
     */
    public PageVO<DamageReportVO> userPage(String keyword, String status, long page, long size) {
    // 查询只返回当前居民的报修单。
    // 关键字与状态都是可选过滤条件。
    // 工具主图批量补齐。
        // 归属条件来自会话，居民只能看到本人报修。
        // 关键字匹配单号与工具编号。
        // 列表项批量补齐工具主图。
        // 居民端分页：归属条件始终来自会话，关键字与状态都是可选的附加过滤。
        LambdaQueryWrapper<DamageReport> wrapper = new LambdaQueryWrapper<DamageReport>()
                // 数据权限：居民只能查询本人报修，条件在 SQL 层强制拼接
                .eq(DamageReport::getUserId, StpKit.USER.getLoginIdAsLong());
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(DamageReport::getReportNo, keyword)
                    // 关键字只匹配单号与工具编号，避免误命中描述等长文本
                    .or().like(DamageReport::getToolCode, keyword));
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(DamageReport::getStatus, status);
        }
        Page<DamageReport> result = damageReportMapper.selectPage(PageQuery.of(page, size),
                wrapper.orderByDesc(DamageReport::getCreateTime));
        return toPage(result);
    }

    /**
     * <p>管理端不限制报修人，用于跨居民的维修调度。
     * 分页查询全部报修单（管理端）。
     * <p>关键字范围比居民端更广，额外匹配工具名称与报修人姓名；
     * <p>状态过滤在数据库完成，关键字过滤使用 OR 组合。
     * <p>管理端列表不限制报修人，用于跨居民的维修调度。
     * <p>管理端关键字覆盖单号、工具编号、工具名称与报修人姓名。
     * 状态可选，按创建时间倒序。
     *
     * @param keyword 可选模糊关键字
     * @param status  可选状态编码
     * @param page    页码
     * @param size    每页条数
     * @return 报修单分页结果
     */
    public PageVO<DamageReportVO> adminPage(String keyword, String status, long page, long size) {
        // 管理端不限制报修人。
        // 关键字覆盖单号、工具编号、工具名称与报修人姓名。
        // 结果按创建时间倒序。
        // 管理端分页：不带归属条件，关键字覆盖更多字段以支持维修调度。
        LambdaQueryWrapper<DamageReport> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(DamageReport::getReportNo, keyword)
                    // 管理端额外按工具名称、报修人姓名检索，便于线下沟通定位
                    .or().like(DamageReport::getToolCode, keyword)
                    .or().like(DamageReport::getToolName, keyword)
                    .or().like(DamageReport::getReporterName, keyword));
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(DamageReport::getStatus, status);
        }
        Page<DamageReport> result = damageReportMapper.selectPage(PageQuery.of(page, size),
                wrapper.orderByDesc(DamageReport::getCreateTime));
        return toPage(result);
    }

    /** 列表批量加载工具主图，避免逐行查询工具表。 */
    private PageVO<DamageReportVO> toPage(Page<DamageReport> result) {
        // 去重收集工具 ID，一次批量查询主图。
        // 工具缺失时主图为 null。
        // 复用单条映射逻辑保证字段一致。
        // 去重收集工具 ID，一次批量查询替代逐行查询，降低列表页 SQL 次数。
        Set<Long> toolIds = result.getRecords().stream()
                .map(DamageReport::getToolId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        // 工具可能已被删除，映射中缺失时主图返回 null，前端使用占位图。
        Map<Long, String> images = new HashMap<>();
        if (!toolIds.isEmpty()) {
            // 只读取主图字段即可，避免把工具完整实体加载进内存。
            for (Tool tool : toolMapper.selectBatchIds(toolIds)) {
                images.put(tool.getId(), tool.getImageUrl());
            }
        }
        // 复用单条映射逻辑，再补充批量查询得到的工具主图。
        return PageVO.of(result, report -> {
            DamageReportVO vo = damageReportVoMapper.toVO(report);
            // 批量查询结果中缺失时返回 null，前端使用占位图。
            vo.setToolImageUrl(images.get(report.getToolId()));
            return vo;
        });
    }

    /**
     * <p>非本人报修返回 403。
     * 查看当前居民的报修单详情。
     *
     * @param id 报修单主键
     * @return 报修单详情
     * <p>详情查询不修改任何状态，可安全重复调用。
     * <p>非本人报修返回 403，与「报修单不存在」的 404 区分开。
     * <p>先查实体再判归属，避免为越权请求执行工具主图查询。
     * @throws BizException 报修单不存在或不属于当前用户时抛出
     */
    public DamageReportVO userDetail(Long id) {
        // 先查实体，再校验归属。
        // 非本人报修返回 403。
        // 详情不修改任何状态。
        // 详情先查实体再校验归属，避免为越权请求额外查询工具主图。
        DamageReport report = getReport(id);
        // 归属校验在服务层完成，Controller 不参与数据权限判断
        if (!report.getUserId().equals(StpKit.USER.getLoginIdAsLong())) {
            throw new BizException(ResultCode.FORBIDDEN, "无权查看该报修单");
        }
        return toVO(report);
    }

    /**
     * <p>详情包含核验、维修与验收全过程字段。
     * <p>管理员详情不做归属校验，但仍要求报修单存在。
     * <p>详情包含工具主图、脱敏电话与全部状态字段。
     * 查看报修单详情（管理端），不受报修人归属限制。
     *
     * @param id 报修单主键
     * @return 报修单详情
     */
    public DamageReportVO adminDetail(Long id) {
        // 管理员详情不做归属校验。
        // 报修单不存在时返回 404。
        // 详情包含核验、维修与验收全过程字段。
        // 管理员详情不做归属校验，但仍要保证报修单存在。
        return toVO(getReport(id));
    }

    /**
     * <p>读取后由调用方自行判断状态与归属。
     * <p>所有写方法与详情接口都通过该方法读取，保证错误语义一致。
     * 按主键读取报修单，不存在时统一抛出 404。
     *
     * @param id 报修单主键
     * @return 报修单实体
     */
    private DamageReport getReport(Long id) {
        // 按主键读取报修单。
        // 不存在时统一抛出 404。
        // 所有写方法与详情接口共用该方法。
        DamageReport report = damageReportMapper.selectById(id);
        if (report == null) {
            throw new BizException(ResultCode.NOT_FOUND, "报修单不存在");
        }
        return report;
    }

    /**
     * <p>非法状态统一按冲突处理，提示前端刷新或联系管理员。
     * <p>不会把非法状态静默当作 SUBMITTED，避免脏数据被继续流转。
     * 将数据库中的状态编码解析为枚举；非法编码视为数据冲突。
     *
     * @param report 报修单实体
     * <p>非法状态统一按冲突处理。
     * @return 报修状态枚举
     */
    private ReportStatus statusOf(DamageReport report) {
        // 把数据库状态编码解析为枚举。
        // 非法编码按冲突处理。
        // 不会静默当作 SUBMITTED。
        return ReportStatus.fromCode(report.getStatus())
                .orElseThrow(() -> new BizException(ResultCode.CONFLICT, "无效的报修状态"));
    }

    /**
     * <p>该集合与状态机的非终态保持一致。
     * <p>已驳回、已完成与已报废不阻塞同一租赁再次报修。
     * 处理中的报修状态集合：待核验、已核验、维修中。
     *
     * @return 活跃状态编码列表
     */
    private List<String> activeStatusCodes() {
        // 待核验、已核验、维修中视为处理中。
        // 终态不阻塞同一租赁再次报修。
        // 该集合与状态机的非终态一致。
        return List.of(ReportStatus.SUBMITTED.code(), ReportStatus.VERIFIED.code(),
                ReportStatus.REPAIRING.code());
    }

    /**
     * <p>该方法只影响展示与通知文案，不参与权限判断。
     * <p>昵称为空时返回 null，由前端按空白展示。
     * 生成报修人显示名称：优先真实姓名，其次昵称，用户缺失时使用「居民」。
     *
     * @param user 居民账号，可为空
     * @return 用于展示与通知的名称
     */
    private String displayName(User user) {
        // 优先真实姓名，其次昵称。
        // 用户缺失时使用「居民」。
        // 只影响展示与通知文案。
        if (user == null) {
            return "居民";
        }
        // 昵称也可能为空，此时返回 null，由调用方展示为空白而非错误。
        return StringUtils.hasText(user.getRealName()) ? user.getRealName() : user.getNickname();
    }

    /**
     * <p>管理员账号被删除时使用占位名，流程仍可继续。
     * <p>核验人、验收人均通过该方法写入，保证显示口径一致。
     * 读取当前登录管理员名称，用于记录操作人；账号缺失时回退为「工作人员」。
     *
     * @return 管理员显示名称
     */
    private String currentAdminName() {
        // 读取当前登录管理员名称。
        // 账号缺失时使用「工作人员」占位。
        // 核验人与验收人均通过该方法写入。
        // 管理员账号可能已被删除；缺失时使用占位名称保证报修单可继续流转。
        Admin admin = adminMapper.selectById(StpKit.ADMIN.getLoginIdAsLong());
        return admin == null ? "工作人员" : admin.getUsername();
    }

    /**
     * <p>单条详情与列表批量补齐使用同一映射器，保证字段一致。
     * <p>工具已被删除时主图为 null，其余字段不受影响。
     * 单条报修单转视图对象，并补齐工具主图。
     *
     * @param report 报修单实体
     * @return 视图对象
     */
    private DamageReportVO toVO(DamageReport report) {
        // 先由映射器转换基础字段。
        // 再补查工具主图。
        // 工具已删除时主图为 null。
        DamageReportVO vo = damageReportVoMapper.toVO(report);
        // 详情页按需查询单条工具主图；工具缺失时主图为 null。
        Tool tool = toolMapper.selectById(report.getToolId());
        vo.setToolImageUrl(tool == null ? null : tool.getImageUrl());
        return vo;
    }
}
