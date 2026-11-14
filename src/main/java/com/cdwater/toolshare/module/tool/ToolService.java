/**
 * 工具模块核心服务文件。
 *
 * <h2>职责边界</h2>
 * <p>负责工具的查询、创建、编辑、审核、状态流转、可用性计算与操作日志。
 * 工具状态机在 ToolStateMachine；向量索引在 ToolIndexService；
 * 蹲守通知在 ToolWatchService；租赁与报修状态只读，通过公开 Mapper/Service 获取。
 *
 * <h2>可见性规则</h2>
 * <p>用户端只可见“审核通过”的工具；未指定状态时再排除已报废。
 * 管理端可见全部工具，包括待审核与需修改的。
 *
 * <h2>状态写入</h2>
 * <p>所有状态变更统一经过 transition：
 * 状态机断言 -> 可用性守卫（无活动租赁、无未完成报修）-> 乐观锁更新 -> 结构化日志。
 * 置为 AVAILABLE 时刷新 lastRepairTime，并触发蹲守通知。
 *
 * <h2>索引一致性</h2>
 * <p>索引内容只包含静态属性，是否可租在查询时由数据库过滤。
 * 索引写入/删除在事务提交后执行，失败进入重试队列，不影响业务主流程。
 *
 * <h2>可用性计算</h2>
 * <p>占用区间 = 活动租赁 + 未过期待支付单；区间合并后做差集得到空闲窗口，
 * 再按槽位粒度向后搜索满足原时长的建议窗口。
 *
  * <h2>请求到落库的主流程</h2>
  * <p>管理端新增：validateDict -> apply（trim/清洗/图片拼接）-> 生成编号 ->
  * 强制待审核 -> 写库 -> 结构化日志 -> 事务提交后同步索引。
  * <p>管理端编辑：validateDict -> apply -> updateById -> 日志；
  * 若 status 发生变化，再走 changeStatus 的完整状态机链路。
  * <p>审核：枚举解析 -> 退回意见校验 -> 幂等判断 -> 写审核字段 -> 日志 -> 同步索引。
  * <p>状态修改：changeStatus -> transition（状态机 + 守卫 + 乐观锁 + 日志 + 索引 + 蹲守通知）。
  *
  * <h2>事务边界</h2>
  * <p>create/update/audit/updateStatus/transition/restore 都标注 @Transactional。
  * 索引同步、蹲守通知与站内消息通过 AfterCommit 推迟到提交后，
  * 保证事务回滚时不会留下外部副作用；索引失败只进重试队列。
  *
  * <h2>错误码约定</h2>
  * <ul>
  *   <li>400：字典值非法、状态迁移非法、守卫条件不满足、审核参数非法；</li>
  *   <li>404：工具不存在，或用户端访问未审核工具；</li>
  *   <li>409：乐观锁版本冲突，要求刷新后重试。</li>
  * </ul>
  *
  * <h2>查询性能</h2>
  * <p>管理端与用户端分页都只做一次分页查询；
  * 创建人用户名与评分统计按当页 id 批量查询，避免 N+1。
  * 可用性查询的冲突集合在数据库完成区间过滤，内存中只做合并与差值。
  *
  * <h2>可观测性</h2>
  * <p>所有状态变更写 t_tool_log，包含动作、前后状态、操作人与备注；
  * 索引失败写 WARN 并带 toolId，便于按 id 排查。
  *
  * <h2>扩展点</h2>
  * <p>新增工具状态时需同步修改 ToolStatus、ToolStateMachine 与 ToolService 的守卫；
  * 新增字典时需同步 Dict 与 validateDict。评分排序 SQL 以子查询实现，
  * 若数据量增大可改为统计表 + 定时刷新。
  *
        * <h2>相关文件</h2>
        * <p>Tool（实体）、ToolSaveDTO/ToolAuditDTO/ToolStatusDTO/RestoreDTO（入参）、
        * ToolVO/ToolVoMapper（出参）、ToolStateMachine（迁移表）、
        * ToolIndexService/ToolWatchService（外部副作用）、ToolLog/ToolLogMapper（审计）。
       * <h2>兼容性</h2>
       * <p>工具字段的增删需同步 Tool、ToolSaveDTO、ToolVO 与数据库迁移；
       * 只增字段时旧客户端可忽略，删除字段需确认前端与管理端均已升级。
      * <h2>安全与权限</h2>
      * <p>创建、编辑、审核、报废恢复只允许管理端权限；
      * 用户端只能读取审核通过的工具并做可用性/蹲守操作。
      * <p>使用说明在落库前清洗，输出到前端时按纯文本或受控富文本处理；
      * 图片地址只接受字符串，不在此处做远程抓取，避免 SSRF。
     * <h2>性能建议</h2>
     * <p>用户端/管理端分页都只做一次查询，辅以两次批量查询（创建人、评分）；
     * 不要在这里恢复逐条查询，否则列表页会出现 N+1。
     * <p>可用性查询的冲突集合已按索引过滤，内存中只做线性合并；
     * 若单个工具的活动租赁异常多，应先排查是否有未取消的脏数据。
     * <p>评分排序使用相关子查询，适合中小规模；数据量增大后可改为统计表。
    * <h2>日志与审计</h2>
    * <p>t_tool_log 记录每一次创建、编辑、审核、状态流转与恢复；
    * fromStatus/toStatus 可还原迁移路径，remark 保留审核意见或恢复原因。
    * <p>日志中的 operatorName 是写入时的用户名快照，
    * 账号改名或删除不会影响历史可读性。
    * <p>查询日志时按工具 id + 创建时间倒序，即可得到完整生命周期。
    * 若出现状态与日志不一致，优先检查是否有绕过 transition 的直接更新。
   * <h2>常见排障</h2>
   * <ul>
   *   <li>工具在用户端消失：检查 auditStatus 是否为 1、status 是否为 SCRAPPED；</li>
   *   <li>无法置为可用：检查是否存在活动租赁或未终结报修；</li>
   *   <li>状态更新返回 409：version 已被其它请求推进，刷新后重试；</li>
   *   <li>推荐结果缺少工具：检查向量索引重试日志与 Qdrant/embedding 可用性；</li>
   *   <li>可用性建议为空：检查搜索天数上限与槽位配置。</li>
   * </ul>
   * <p>日志中的 toolId 可以直接用于查询 t_tool_log 还原操作时间线。
   *
   * <h2>测试关注点</h2>
   * <ul>
   *   <li>状态机迁移表与守卫条件的组合；</li>
   *   <li>乐观锁冲突路径；</li>
   *   <li>审核幂等与退回意见必填；</li>
   *   <li>索引同步失败是否进入重试队列且不影响主事务；</li>
   *   <li>可用性窗口合并、差集与槽位对齐的边界。</li>
   * </ul>
   *
   * <h2>依赖约束</h2>
   * <p>本服务依赖 RentalMapper、DamageReportMapper、AdminMapper、ToolReviewMapper，
   * 但只做只读查询；写工具状态的职责唯一属于本服务。
   * 跨模块写操作通过 ToolService 公开方法（如 transition）进行，避免绕过状态机。
   * <h2>接口清单</h2>
   * <p>管理端（tool:manage）：
   * GET /api/admin/tools 分页；POST 创建；PUT /{id} 编辑；
   * PATCH /{id}/audit 审核；PATCH /{id}/status 状态修改；
   * POST /{id}/restore 报废恢复；GET /{id}/rentals 历史租赁；GET /{id}/logs 操作日志。
   * <p>用户端（登录态）：
   * GET /api/user/tools 分页；GET /{id} 详情；GET /{id}/history 历史租赁；
   * GET /{id}/availability 可用性；POST/DELETE /{id}/watch 蹲守。
   * <p>响应统一包装为 Result；分页使用 PageVO，字段包含 total/current/size/records。
   * 错误通过 BizException + ResultCode 表达，控制器不吞异常。
  * <h2>安全边界</h2>
  * <p>用户端接口从不暴露待审核工具；创建/编辑/审核/恢复只由管理端权限保护；
  * 使用说明在落库前清洗，避免存储型 XSS。
  * <h2>数据模型与字典</h2>
  * <p>工具表自身保存资产属性与统计字段；类别、损坏程度、工具状态、审核状态
  * 都通过共享字典/枚举翻译，不在前端硬编码中文。
  * <p>审核状态与工具状态相互独立：
  * 审核决定“是否可见/可推荐”，工具状态决定“当前是否可租”。
  * 用户可以查询到审核通过但维修中的工具详情，但不能预约。
  * <p>图片以逗号分隔字符串保存，第一张作为列表主图；
  * 使用说明可能是 HTML 或 JSON 步骤数组，索引前会转成纯文本。
  *
  * <h2>定时与外部依赖</h2>
  * <p>本服务没有自己的定时任务；逾期物化与待支付超时在租赁模块，
  * 索引重试在 ToolIndexService，蹲守通知在 ToolWatchService。
  * 这些外部动作都通过 AfterCommit 或定时任务触发，避免长事务。
  * <p>向量库、embedding 服务都视为可选依赖：不可用时工具主流程照常。
  *
  * <h2>版本与兼容</h2>
  * <p>version 字段由 MyBatis-Plus 自动维护。调用 transition 时实体必须携带
  * 数据库当前版本；若调用方持有旧快照，更新会返回 0 并抛出 409。
  * <p>新增状态或字典值时，必须同时更新数据库字典、枚举与前端展示，
  * 否则历史数据可能无法解析。
  *
  * <h2>本地开发</h2>
  * <p>可通过 toolshare.ai.tool-index-enabled=false 关闭写时索引，
  * 通过 toolshare.ai.recommend.index-on-startup=false 跳过启动重建。
  * 可用性槽位、建议数量与搜索天数都有独立配置项，便于联调。
 * <h2>金额与评分</h2>
 * <p>押金只做快照与展示；评分按工具批量聚合，无评价按 0 分参与排序。
 */
package com.cdwater.toolshare.module.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cdwater.toolshare.module.auth.Admin;
import com.cdwater.toolshare.module.auth.AdminMapper;
import com.cdwater.toolshare.module.damage.DamageReport;
import com.cdwater.toolshare.module.damage.DamageReportMapper;
import com.cdwater.toolshare.module.rental.Rental;
import com.cdwater.toolshare.module.rental.RentalMapper;
import com.cdwater.toolshare.module.review.ToolRatingStats;
import com.cdwater.toolshare.module.review.ToolReviewMapper;
import com.cdwater.toolshare.shared.config.StpKit;
import com.cdwater.toolshare.shared.enums.AuditStatus;
import com.cdwater.toolshare.shared.enums.RentalStatus;
import com.cdwater.toolshare.shared.enums.ToolStatus;
import com.cdwater.toolshare.shared.html.HtmlSanitizer;
import com.cdwater.toolshare.shared.sequence.SequenceService;
import com.cdwater.toolshare.shared.tx.AfterCommit;
import com.cdwater.toolshare.shared.web.BizException;
import com.cdwater.toolshare.shared.web.Dict;
import com.cdwater.toolshare.shared.web.PageQuery;
import com.cdwater.toolshare.shared.web.PageVO;
import com.cdwater.toolshare.shared.web.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工具领域服务：工具查询、创建、编辑、审核、状态流转与可用性计算。
 * <p>关键约束：
 * <ul>
 *   <li>所有状态写入统一经过 {@link #transition}，由 ToolStateMachine 校验；</li>
 *   <li>置为 AVAILABLE 前必须没有活动租赁与未完成报修；</li>
 *   <li>状态更新使用乐观锁，版本冲突返回 409；</li>
 *   <li>用户端只能看到审核通过的工具；</li>
 *   <li>向量索引在事务提交后同步，失败不影响业务主流程；</li>
 *   <li>可用性查询把待支付超时窗口、活动租赁与槽位对齐纳入计算。</li>
 * </ul>
 * <p>与租赁/报修/评价/蹲守模块的关系：读取租赁占用与报修状态做守卫，
 * 读取评价统计补充排序与展示，工具恢复可用时触发蹲守通知。
 */
@Service
@RequiredArgsConstructor
public class ToolService {

    // 可用性建议窗口的展示格式
    private static final DateTimeFormatter SUGGEST_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    // 占用工具的活动租赁状态：待支付也算占用（有超时窗口），已归还/已取消不算
    private static final List<RentalStatus> ACTIVE_RENTAL_STATUS =
            List.of(RentalStatus.PENDING_PAYMENT, RentalStatus.RESERVED,
                    RentalStatus.PICKED_UP, RentalStatus.OVERDUE);
    // 未终结的报修状态；存在这些状态的工具不能置为可用
    private static final List<String> UNFINISHED_REPORT_STATUS =
            List.of("SUBMITTED", "VERIFIED", "REPAIRING");
    /** 按平均分倒序：无评价工具按 0 分参与排序，不会被从结果中过滤。 */
    private static final String ORDER_BY_RATING_SQL =
            "ORDER BY (SELECT COALESCE(AVG(r.rating), 0) FROM t_tool_review r"
                    + " WHERE r.tool_id = t_tool.id) DESC, t_tool.create_time DESC";

    // 工具表访问
    private final ToolMapper toolMapper;
    // 工具操作日志
    private final ToolLogMapper toolLogMapper;
    // 查询活动租赁占用与历史租赁
    private final RentalMapper rentalMapper;
    // 判断是否存在未完成报修
    private final DamageReportMapper damageReportMapper;
    // 批量补全创建人用户名
    private final AdminMapper adminMapper;
    // 生成按类别前缀的工具编号
    private final SequenceService sequenceService;
    // 工具恢复可用时通知蹲守用户
    private final ToolWatchService toolWatchService;
    // 批量读取评分统计
    private final ToolReviewMapper toolReviewMapper;
    // 工具向量索引的写入与删除
    private final ToolIndexService toolIndexService;
    // 实体到视图的基础映射
    private final ToolVoMapper toolVoMapper;
    // 日志实体到视图映射
    private final ToolLogVoMapper toolLogVoMapper;
    // 索引与通知延迟到事务提交后执行，避免回滚后产生外部副作用
    private final AfterCommit afterCommit;

    @Value("${toolshare.rental.pending-payment-timeout-minutes:30}")
    // 待支付租赁占用档期的超时窗口；与租赁模块共用同一配置含义
    private long pendingPaymentTimeoutMinutes = 30;

    @Value("${toolshare.availability.slot-minutes:30}")
    // 可用性建议窗口的对齐粒度（分钟）
    private long availabilitySlotMinutes = 30;

    @Value("${toolshare.availability.max-suggestions:3}")
    // 一次可用性查询最多返回的建议窗口数
    private int availabilityMaxSuggestions = 3;

    @Value("${toolshare.availability.search-days:30}")
    // 从期望开始时间向后搜索可用窗口的天数上限
    private long availabilitySearchDays = 30;

    /** E2E 等外部向量服务不可控的环境可关闭写时索引，避免阻塞业务响应。 */
    @Value("${toolshare.ai.tool-index-enabled:true}")
    // 外部向量服务不可控的环境（如 E2E）可关闭写时索引
    private boolean toolIndexEnabled = true;

    /**
     * 用户端工具分页。
     * <p>固定过滤审核通过；未显式指定状态时排除已报废工具。
     * orderBy=rating 时按平均分倒序，无评价工具按 0 分参与排序而不是被过滤。
     *
     * @param keyword  关键字，匹配名称/型号/编号
     * @param category 类别编码
     * @param status   状态编码
     * @param orderBy  排序方式，rating 为评分优先，其余按创建时间倒序
     * @param page     页码
     * @param size     每页条数
     * @return 工具分页结果
     */
    public PageVO<ToolVO> userPage(String keyword, String category, String status,
                                   String orderBy, long page, long size) {
        LambdaQueryWrapper<Tool> wrapper = baseWrapper(keyword, category, status, null)
                .eq(Tool::getAuditStatus, AuditStatus.APPROVED.value());
        // 用户未指定状态时隐藏报废工具；显式查询报废状态仍可命中（但审核过滤仍生效）
        if (!StringUtils.hasText(status)) {
            wrapper.ne(Tool::getStatus, ToolStatus.SCRAPPED.code());
        }
        // 评分排序使用相关子查询，无评价时 COALESCE 为 0，工具仍保留在结果中
        if ("rating".equals(orderBy)) {
            wrapper.last(ORDER_BY_RATING_SQL);
        } else {
            // 默认排序保持不变
            wrapper.orderByDesc(Tool::getCreateTime);
        }
        // 用户端分页同样走统一分页参数
        Page<Tool> result = toolMapper.selectPage(PageQuery.of(page, size), wrapper);
        return toPage(result);
    }

    /**
      * <p>neverRepaired 优先级高于 repairedWithinDays：同时传入时只按从未维修过滤。
      * 所有排序最后都以创建时间倒序兜底，保证分页结果稳定。
      *
      * @param query 查询条件与分页参数
      * @return 工具分页结果
     * 管理端列表：支持按租赁次数 / 最近维修时间排序，以及最低租赁次数、维修时间范围筛选。
     */
    public PageVO<ToolVO> adminPage(ToolAdminQuery query) {
        LambdaQueryWrapper<Tool> wrapper = baseWrapper(query.getKeyword(), query.getCategory(),
                query.getStatus(), query.getStorageArea());
        // 最低租赁次数筛选
        if (query.getMinRentalCount() != null) {
            wrapper.ge(Tool::getRentalCount, query.getMinRentalCount());
        }
        // neverRepaired 优先于 repairedWithinDays，二者互斥
        if (Boolean.TRUE.equals(query.getNeverRepaired())) {
            wrapper.isNull(Tool::getLastRepairTime);
        // 最近 N 天维修过：按 lastRepairTime 下界过滤
        } else if (query.getRepairedWithinDays() != null && query.getRepairedWithinDays() > 0) {
            wrapper.ge(Tool::getLastRepairTime,
                    LocalDateTime.now().minusDays(query.getRepairedWithinDays()));
        }
        // 排序字段来自白名单 switch，避免把用户输入拼进 SQL
        switch (query.getOrderBy() == null ? "" : query.getOrderBy()) {
            case "rentalCount" -> wrapper.orderByDesc(Tool::getRentalCount);
            case "lastRepairTime" -> wrapper.orderByDesc(Tool::getLastRepairTime);
            default -> {
            }
        }
        // 次级排序固定为创建时间倒序，保证分页稳定
        // 次级排序固定为创建时间倒序，保证分页稳定
        // 次级排序保证结果稳定
        wrapper.orderByDesc(Tool::getCreateTime);
        // 管理端分页使用统一 PageQuery，size 由入参层限制
        Page<Tool> result = toolMapper.selectPage(PageQuery.of(query.getPage(), query.getSize()), wrapper);
        return toPage(result);
    }

    /**
     * 构造用户端与管理端共用的基础筛选条件。
     * <p>关键字在名称、型号、编号三列之间用 OR 组合，并用括号包裹，
     * 避免与其它 AND 条件产生优先级错误。
     *
     * @param keyword     关键字
     * @param category    类别编码
     * @param status      状态编码
     * @param storageArea 存放区域（仅管理端使用）
     * @return 基础查询条件
     */
    private LambdaQueryWrapper<Tool> baseWrapper(String keyword, String category,
                                                  String status, String storageArea) {
        LambdaQueryWrapper<Tool> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            // 关键字条件整体加括号，避免 OR 与其它 AND 条件优先级错误
            wrapper.and(w -> w.like(Tool::getName, keyword)
                    .or().like(Tool::getModel, keyword)
                    .or().like(Tool::getCode, keyword));
        }
        // 类别与状态是精确匹配，关键字与区域是模糊匹配
        if (StringUtils.hasText(category)) {
            wrapper.eq(Tool::getCategory, category);
        }
        // 状态为空时由调用方决定是否排除报废，不在此处默认过滤
        if (StringUtils.hasText(status)) {
            wrapper.eq(Tool::getStatus, status);
        }
        // 区域仅管理端使用，用户端传入 null 不参与条件
        if (StringUtils.hasText(storageArea)) {
            wrapper.like(Tool::getStorageArea, storageArea);
        }
        return wrapper;
    }

    /** 管理端详情：未审核工具也可见。 */
    /**
     * 管理端工具详情：不限制审核状态。
     *
     * @param id 工具 id
     * @return 工具视图
     * @throws BizException 工具不存在时返回 404
     */
    public ToolVO getVO(Long id) {
        return toVO(getTool(id));
    }

    /** 用户端详情：未通过审核的工具按不存在处理。 */
    /**
     * 用户端工具详情：未通过审核的工具按不存在处理，
     * 避免通过详情接口探测待审核工具。
     *
     * @param id 工具 id
     * @return 工具视图
     * @throws BizException 工具不存在或未通过审核时返回 404
     */
    public ToolVO getUserVO(Long id) {
        // 用户端详情先读取实体，再按审核状态决定是否可见
        Tool tool = getTool(id);
        // 未审核工具按不存在处理，避免被探测
        if (!isApproved(tool)) {
            throw new BizException(ResultCode.NOT_FOUND, "工具不存在");
        }
        return toVO(tool);
    }

    /**
     * 读取工具实体，不存在时抛出统一 404。
     *
     * @param id 工具 id
     * @return 工具实体
     * @throws BizException 工具不存在时返回 404
     */
    public Tool getTool(Long id) {
        // 主键查询；不存在时统一抛出 404
        Tool tool = toolMapper.selectById(id);
        // 主键查不到即不存在
        if (tool == null) {
            throw new BizException(ResultCode.NOT_FOUND, "工具不存在");
        }
        // 返回实体供内部流程使用，调用方负责状态判断
        return tool;
    }

    // 先做用户端可见性校验，未审核/不存在的工具不返回任何历史。
    // 只查询 RETURNED 状态的租赁，且只输出时间窗口，不含租用人字段。
    /** 用户端工具历史租赁：只返回已归还的租期窗口与次数，不暴露他人信息。 */
    public ToolRentalHistoryVO userRentalHistory(Long toolId) {
        getUserVO(toolId);
        List<Rental> rentals = rentalMapper.selectList(new LambdaQueryWrapper<Rental>()
                .eq(Rental::getToolId, toolId)
                .eq(Rental::getStatus, RentalStatus.RETURNED.code())
                .orderByDesc(Rental::getEndTime));
        // 历史视图只填充工具 id、次数与时间窗口
        ToolRentalHistoryVO vo = new ToolRentalHistoryVO();
        // 历史视图固定当前工具 id
        vo.setToolId(toolId);
        // 已归还租赁条数即完成次数
        vo.setCompletedCount((long) rentals.size());
        // 只输出租期窗口，丢弃租用人等敏感字段
        // 只输出时间窗口
        vo.setWindows(rentals.stream().map(rental -> {
            ToolRentalHistoryVO.Window window = new ToolRentalHistoryVO.Window();
            window.setStartTime(rental.getStartTime());
            window.setEndTime(rental.getEndTime());
            return window;
        }).toList());
        return vo;
    }

    @Transactional
    /**
     * 新增工具。
     * <p>编号按类别生成；新工具强制进入待审核，忽略请求中的审核相关字段；
     * 创建人取当前管理端登录账号。创建日志与索引同步都在同一业务流程内完成。
     *
     * @param dto 工具属性
     * @return 新建工具视图
     * @throws BizException 字典值非法时返回 400
     */
    public ToolVO create(ToolSaveDTO dto) {
        validateDict(dto);
        // 构造新实体，编号、审核状态、版本与创建人由服务端设置
        Tool tool = new Tool();
        apply(tool, dto);
        // 编号按类别前缀生成，创建后不可修改
        tool.setCode(sequenceService.nextToolCode(dto.getCategory()));
        // 无论请求如何，新工具都必须重新走审核
        tool.setAuditStatus(AuditStatus.PENDING.value());
        // 显式初始化乐观锁版本，避免数据库默认值与实体不一致
        tool.setVersion(0);
        // 新工具从 0 次租赁开始统计
        tool.setRentalCount(0);
        // 新增时允许指定初始状态，为空默认可用
        tool.setStatus(StringUtils.hasText(dto.getStatus())
                ? dto.getStatus() : ToolStatus.AVAILABLE.code());
        // 创建人取当前管理端账号，用于列表展示与审计
        tool.setCreatedBy(StpKit.ADMIN.getLoginIdAsLong());
        // 插入后回填主键，日志与索引都依赖该 id
        toolMapper.insert(tool);
        // 创建日志记录初始状态与来源
        logStructured(tool.getId(), "CREATE", null, tool.getStatus(), "新增工具 " + tool.getCode());
        syncIndex(tool);
        return toVO(tool);
    }

    @Transactional
    /**
     * 编辑工具属性。
     * <p>静态属性直接更新；若请求携带不同的 status，则委托状态机完成状态变更，
     * 状态变更成功后再重新读取，保证返回的视图与数据库一致。
     *
     * @param id  工具 id
     * @param dto 工具属性
     * @return 更新后的工具视图
     * @throws BizException 字典值非法（400）或状态迁移非法（400/409）
     */
    public ToolVO update(Long id, ToolSaveDTO dto) {
        validateDict(dto);
        Tool tool = getTool(id);
        apply(tool, dto);
        toolMapper.updateById(tool);
        // 编辑日志记录编辑前后状态相同，便于与状态变更日志区分
        logStructured(tool.getId(), "UPDATE", tool.getStatus(), tool.getStatus(), "编辑工具 " + tool.getCode());
        // 状态字段有变化时不能简单 updateById，必须走状态机与可用性守卫
        if (StringUtils.hasText(dto.getStatus()) && !dto.getStatus().equals(tool.getStatus())) {
            // 状态变更由 transition 统一同步索引
            changeStatus(id, dto.getStatus());
            // 状态变更后重新读取，确保返回视图包含最新版本
            tool = getTool(id);
        } else {
            syncIndex(tool);
        }
        return toVO(tool);
    }

    /** 审核：仅系统管理员可调用；退回时必须填写审核意见。 */
    @Transactional
    // 审核只允许两种结果：通过或退回；退回必须带意见，重复审核同一结果直接返回当前视图（幂等）。
    public ToolVO audit(Long id, ToolAuditDTO dto) {
        // 请求使用枚举 name，落库使用整数值，非法值直接拒绝
        // 审核请求使用枚举 name，落库使用 value
        AuditStatus target = AuditStatus.fromName(dto.getAuditStatus())
                .orElseThrow(() -> new BizException(ResultCode.BAD_REQUEST, "无效的审核状态"));
        // 审核只能通过或退回，不能回退到待审核
        if (target == AuditStatus.PENDING) {
            throw new BizException(ResultCode.BAD_REQUEST, "不能把工具改回待审核");
        }
        // 退回必须说明原因
        if (target == AuditStatus.NEEDS_CHANGE && !StringUtils.hasText(dto.getRemark())) {
            throw new BizException(ResultCode.BAD_REQUEST, "退回需填写审核意见");
        }
        Tool tool = getTool(id);
        // 历史数据 auditStatus 为空时按待审核处理
        AuditStatus from = AuditStatus.fromCode(tool.getAuditStatus()).orElse(AuditStatus.PENDING);
        // 幂等：状态未变化时不重复写日志、不重复同步索引
        // 幂等：相同审核结果直接返回
        if (from == target) {
            return toVO(tool);
        }
        // 审核状态使用整数值落库，展示名称由映射器翻译
        tool.setAuditStatus(target.value());
        // 审核意见覆盖旧值；通过时允许为空
        tool.setAuditRemark(dto.getRemark());
        // 记录审核人与审核时间，便于审计
        tool.setReviewedBy(StpKit.ADMIN.getLoginIdAsLong());
        tool.setReviewedAt(LocalDateTime.now());
        toolMapper.updateById(tool);
        // 审核日志记录状态枚举名称与审核意见
        logStructured(id, "AUDIT", from.name(), target.name(), dto.getRemark());
        syncIndex(tool);
        return toVO(tool);
    }

    /** 手动状态更新入口。 */
    @Transactional
    // 手动状态更新入口，仍强制走 changeStatus -> transition 的完整校验链。
    public void updateStatus(Long id, String status) {
        changeStatus(id, status);
    }

    /**
     * 手动状态变更的薄封装：解析目标状态后交给统一迁移入口。
     *
     * @param id     工具 id
     * @param status 目标状态编码
     */
    private void changeStatus(Long id, String status) {
        Tool tool = getTool(id);
        transition(tool, parseStatus(status), "STATUS_CHANGE", "手动更新工具状态");
    }

    /**
      * <p>置为 AVAILABLE 的两个守卫不可绕过：有活动租赁或未完成报修时必须拒绝，
      * 否则会出现同一工具被重复预约或带伤上架。
      * <p>乐观锁更新失败返回 409，提示调用方刷新后重试。
      *
      * @param tool   工具实体（需带当前 version）
      * @param target 目标状态
      * @param action 日志动作编码
      * @param remark 日志备注
      * @throws BizException 迁移非法/守卫失败（400）或版本冲突（409）
     * 所有工具状态写入口统一走这里：状态机校验 + 可用性守卫 + 结构化日志。
     */
    @Transactional
    public void transition(Tool tool, ToolStatus target, String action, String remark) {
        // 从数据库当前状态解析，避免使用可能过期的实体快照
        // 解析当前状态，非法数据按 400 处理
        ToolStatus from = parseStatus(tool.getStatus());
        ToolStateMachine.assertTransition(from, target);
        if (target == ToolStatus.AVAILABLE) {
            // 有活动租赁时不能置为可用，否则会被重复预约
            if (hasActiveRental(tool.getId())) {
                throw new BizException(ResultCode.BAD_REQUEST, "存在活动租赁，不能将工具置为可用");
            }
            // 有未终结报修时不能置为可用，避免带伤上架
            if (hasUnfinishedReport(tool.getId())) {
                throw new BizException(ResultCode.BAD_REQUEST, "存在未完成报修，不能将工具置为可用");
            }
        }
        // 状态编码落库；随后由乐观锁 updateById 原子提交
        tool.setStatus(target.code());
        if (target == ToolStatus.AVAILABLE) {
            tool.setLastRepairTime(LocalDateTime.now());
        }
        // 乐观锁：版本不匹配说明期间已有其他状态变更，拒绝覆盖
        if (toolMapper.updateById(tool) == 0) {
            throw new BizException(ResultCode.CONFLICT, "工具状态已被其他操作更新，请刷新后重试");
        }
        // 统一记录动作、前后状态与备注
        logStructured(tool.getId(), action, from.code(), target.code(), remark);
        // 只有影响“是否可推荐/是否可租”的状态才同步向量索引
        // 只有影响可租/可推荐的状态才同步向量索引
        if (target == ToolStatus.SCRAPPED
                || target == ToolStatus.AVAILABLE || target == ToolStatus.MAINTENANCE) {
            syncIndex(tool);
        }
        notifyWatchersIfAvailable(tool);
    }

    /**
      * <p>恢复前同样检查活动租赁与未完成报修；恢复到 AVAILABLE 后刷新维修时间，
      * 并触发蹲守用户通知。恢复不经过普通状态机的 SCRAPPED 终态限制，
      * 因此必须是独立入口而不是普通状态更新。
      *
      * @param id  工具 id
      * @param dto 恢复原因
      * @return 恢复后的工具视图
      * @throws BizException 非报废状态（400）或存在占用/报修（400）
     * 显式报废恢复：仅系统管理员（{@code tool:manage}）可调用，必须填写原因并写结构化日志。
     * <p>普通状态更新仍受状态机约束，不能覆盖 SCRAPPED。
     */
    @Transactional
    public ToolVO restore(Long id, ToolRestoreDTO dto) {
        Tool tool = getTool(id);
        // 只有报废终态可以走恢复入口，其它状态请使用普通状态更新
        if (!ToolStatus.SCRAPPED.code().equals(tool.getStatus())) {
            throw new BizException(ResultCode.BAD_REQUEST, "只有已报废的工具可以恢复");
        }
        // 恢复原因同时满足入参校验与服务端兜底
        if (!StringUtils.hasText(dto.getReason())) {
            throw new BizException(ResultCode.BAD_REQUEST, "恢复原因不能为空");
        }
        // 恢复前同样检查活动租赁
        if (hasActiveRental(id)) {
            throw new BizException(ResultCode.BAD_REQUEST, "存在活动租赁，不能恢复工具");
        }
        // 恢复前同样检查未完成报修
        if (hasUnfinishedReport(id)) {
            throw new BizException(ResultCode.BAD_REQUEST, "存在未完成报修，不能恢复工具");
        }
        // 恢复目标固定为可用；记录维修时间并触发蹲守通知
        tool.setStatus(ToolStatus.AVAILABLE.code());
        tool.setLastRepairTime(LocalDateTime.now());
        if (toolMapper.updateById(tool) == 0) {
            throw new BizException(ResultCode.CONFLICT, "工具状态已被其他操作更新，请刷新后重试");
        }
        // 恢复日志保留原始原因，便于审计报废-恢复过程
        logStructured(id, "RESTORE", ToolStatus.SCRAPPED.code(), ToolStatus.AVAILABLE.code(),
                dto.getReason().trim());
        syncIndex(tool);
        notifyWatchersIfAvailable(tool);
        return toVO(tool);
    }

    // 索引可见性与业务可见性保持同一口径：审核通过且未报废。
    // 事务提交后再调用向量服务，避免事务回滚后索引与数据库不一致。
    /** 索引与数据库保持一致：通过审核且未报废才写入，其余一律删除；在事务提交后执行。 */
    private void syncIndex(Tool tool) {
        // 索引开关关闭或缺少 id 时不做任何外部调用
        if (!toolIndexEnabled || tool == null || tool.getId() == null) {
            return;
        }
        // 提前取出 id，避免闭包捕获可变实体
        Long toolId = tool.getId();
        // 索引可见性与用户端可见性保持同一口径
        boolean indexable = isApproved(tool)
                && !ToolStatus.SCRAPPED.code().equals(tool.getStatus());
        // 事务提交后再调用向量服务
        afterCommit.run(() -> {
            // 可索引则 upsert，否则删除可能存在的旧索引
            if (indexable) {
                toolIndexService.upsert(tool);
            } else {
                toolIndexService.remove(toolId);
            }
        });
    }

    /**
     * 判断工具是否存在活动租赁。
     * <p>待支付单也算活动租赁，但在可用性查询中会按超时窗口排除。
     *
     * @param toolId 工具 id
     * @return 存在任一活动租赁返回 true
     */
    private boolean hasActiveRental(Long toolId) {
        // 任一活动状态存在即视为占用
        // 待支付、已预约、使用中、逾期都属于活动租赁
        return rentalMapper.selectCount(new LambdaQueryWrapper<Rental>()
                .eq(Rental::getToolId, toolId)
                .in(Rental::getStatus, ACTIVE_RENTAL_STATUS.stream()
                        .map(RentalStatus::code).toList())) > 0;
    }

    /**
     * 判断工具是否存在未终结报修（已提交/已核验/维修中）。
     *
     * @param toolId 工具 id
     * @return 存在未完成报修返回 true
     */
    private boolean hasUnfinishedReport(Long toolId) {
        // 已提交/已核验/维修中都属于未终结报修
        // 未终结报修集合来自共享枚举约定
        return damageReportMapper.selectCount(new LambdaQueryWrapper<DamageReport>()
                .eq(DamageReport::getToolId, toolId)
                .in(DamageReport::getStatus, UNFINISHED_REPORT_STATUS)) > 0;
    }

    // 只有 AVAILABLE 才通知；通知携带 lastRepairTime 作为幂等版本，
    // 同一可用事件重复触发不会重复打扰。事务提交后执行，避免回滚导致误通知。
    /** 工具恢复可用时提醒蹲守用户（手动改状态/归还/维修完成都会走到这里），事务提交后执行。 */
    public void notifyWatchersIfAvailable(Tool tool) {
        // 只有可用状态才通知蹲守用户
        if (tool == null || !ToolStatus.AVAILABLE.code().equals(tool.getStatus())) {
            return;
        }
        // 通知所需字段提前取出，闭包只捕获不可变值
        Long toolId = tool.getId();
        // 工具名称用于消息文案
        String toolName = tool.getName();
        // 恢复可用时间作为通知幂等版本
        LocalDateTime availableAt = tool.getLastRepairTime();
        // 蹲守通知延迟到事务提交后
        afterCommit.run(() -> toolWatchService.notifyAvailable(toolId, toolName, availableAt));
    }

    /**
     * 查询工具操作日志，按时间倒序。
     *
     * @param toolId 工具 id
     * @return 日志视图列表
     */
    public List<ToolLogVO> logs(Long toolId) {
        // 日志按时间倒序，最新操作优先展示
        // 日志按时间倒序返回
        return toolLogMapper.selectList(new LambdaQueryWrapper<ToolLog>()
                        .eq(ToolLog::getToolId, toolId)
                        .orderByDesc(ToolLog::getCreateTime))
                .stream().map(toolLogVoMapper::toVO).toList();
    }

    /**
     * 计算工具在期望时段的可租性。
     * <p>依次校验：审核通过、状态可用、时间参数合法（开始早于结束且晚于当前）。
     * 占用区间来自活动租赁与未过期待支付单；查询窗口从开始时间起向后延伸配置天数。
     * 不可租时同时返回期望窗口内的空闲区间与可满足原时长的建议窗口。
     *
     * @param toolId 工具 id
     * @param start  期望开始时间
     * @param end    期望结束时间
     * @return 可用性结论、空闲窗口与建议窗口
     */
    public AvailabilityVO availability(Long toolId, LocalDateTime start, LocalDateTime end) {
        // 可用性查询不要求审核通过，但会在下方给出明确原因
        Tool tool = getTool(toolId);
        // 未审核工具对用户不可预约
        if (!isApproved(tool)) {
            return unavailable("工具待审核，暂不可预约");
        }
        // 非可用状态直接返回原因，不进入区间计算
        if (!ToolStatus.AVAILABLE.code().equals(tool.getStatus())) {
            return unavailable("工具当前状态为"
                    + Dict.label(Dict.TOOL_STATUS, tool.getStatus()) + "，暂不可预约");
        }
        // 参数缺失或区间反向都视为不可用
        if (start == null || end == null || !start.isBefore(end)) {
            return unavailable("租赁开始时间必须早于结束时间");
        }
        // 开始时间必须晚于当前时间
        if (!start.isAfter(LocalDateTime.now())) {
            return unavailable("租赁开始时间必须晚于当前时间");
        }
        // 建议窗口必须保持与期望相同的租期时长
        // 计算期望时长，建议窗口必须保持同样长度
        // 计算期望时长，建议窗口必须保持同样长度
        Duration duration = Duration.between(start, end);
        // 搜索上限：从期望开始时间向后 N 天
        LocalDateTime horizonEnd = start.plusDays(availabilitySearchDays);
        // 冲突查询会同时纳入活动租赁与未过期待支付单
        List<AvailabilityVO.TimeWindow> occupied = mergeOccupied(
                rentalMapper.selectConflicts(toolId, start, horizonEnd, pendingPaymentTimeoutMinutes));
        // 期望区间与任何占用区间重叠即不可租
        boolean available = !overlaps(occupied, start, end);
        // 无论是否可租都返回期望窗口内的真实空闲区间
        List<AvailabilityVO.TimeWindow> freeWindows = freeWindows(occupied, start, end);
        // 可租时不需要建议窗口
        // 可租时只返回空闲窗口，不生成建议
        if (available) {
            return new AvailabilityVO(true, "该时段可预约", List.of(), freeWindows, List.of());
        }
        // 不可租时向后寻找满足原时长的最近窗口
        List<AvailabilityVO.TimeWindow> suggestionWindows =
                suggestWindows(occupied, start, duration, horizonEnd);
        // 兼容旧前端的文本建议，由建议窗口格式化得到
        List<String> suggestions = suggestionWindows.stream()
                .map(window -> window.startTime().format(SUGGEST_FORMAT)
                        + " 至 " + window.endTime().format(SUGGEST_FORMAT))
                .toList();
        // 不可租时同时给出原因、空闲窗口与建议窗口
        return new AvailabilityVO(false,
                suggestionWindows.isEmpty()
                        ? "该时段已被预约，近期暂无满足时长的空闲时段"
                        : "该时段已被预约，可尝试其他时间",
                suggestions, freeWindows, suggestionWindows);
    }

    /**
     * 构造不可用结果，所有窗口列表为空。
     *
     * @param message 不可用原因
     * @return 不可用视图
     */
    private AvailabilityVO unavailable(String message) {
        // 早期校验失败时没有区间信息，窗口列表为空
        return new AvailabilityVO(false, message, List.of(), List.of(), List.of());
    }

    // 合并后占用区间互不重叠，便于后续做差集与建议搜索。
    // 注意首尾相接（前一结束 == 后一开始）也合并，否则会返回长度为 0 的空闲窗口。
    /** 活动租赁区间按开始时间排序并合并，首尾相接视为连续占用。 */
    private List<AvailabilityVO.TimeWindow> mergeOccupied(List<Rental> rentals) {
        // 合并结果按开始时间升序
        List<AvailabilityVO.TimeWindow> merged = new ArrayList<>();
        // 活动租赁 -> 时间窗口
        rentals.stream()
                .map(rental -> new AvailabilityVO.TimeWindow(rental.getStartTime(), rental.getEndTime()))
                .sorted(Comparator.comparing(AvailabilityVO.TimeWindow::startTime))
                .forEach(window -> {
                    if (merged.isEmpty()
                            || window.startTime().isAfter(merged.get(merged.size() - 1).endTime())) {
                        // 与上一段不重叠（含首尾相接）时直接追加
                        merged.add(window);
                        return;
                    }
                    // 与上一段重叠时合并为更长的区间
                    AvailabilityVO.TimeWindow last = merged.remove(merged.size() - 1);
                    merged.add(new AvailabilityVO.TimeWindow(last.startTime(),
                            window.endTime().isAfter(last.endTime()) ? window.endTime() : last.endTime()));
                });
        return merged;
    }

    /**
     * 判断期望区间是否与任一占用区间重叠。
     * <p>使用半开区间语义：端点相接不算重叠。
     *
     * @param occupied 已合并的占用区间
     * @param start    期望开始
     * @param end      期望结束
     * @return 有重叠返回 true
     */
    private boolean overlaps(List<AvailabilityVO.TimeWindow> occupied,
                             LocalDateTime start, LocalDateTime end) {
        // 半开区间重叠判断，端点相接不算冲突
        return occupied.stream()
                // 半开区间重叠判断：端点相接不算重叠
                .anyMatch(window -> window.startTime().isBefore(end) && window.endTime().isAfter(start));
    }

    // 单次线性扫描：游标从期望开始向后推进，遇到占用就跳到占用结束。
    // 末尾若游标仍早于期望结束，补上最后一段空闲区间。
    /** 期望窗口减去占用区间后的真实空闲区间。 */
    private List<AvailabilityVO.TimeWindow> freeWindows(List<AvailabilityVO.TimeWindow> occupied,
                                                        LocalDateTime start, LocalDateTime end) {
        // 差集结果按时间升序
        List<AvailabilityVO.TimeWindow> free = new ArrayList<>();
        // 游标表示当前尚未处理的空闲起点
        LocalDateTime cursor = start;
        // 单次线性扫描即可完成差集
        for (AvailabilityVO.TimeWindow window : occupied) {
            // 占用完全在期望窗口之后，后续区间无需再看
            if (!window.startTime().isBefore(end)) {
                break;
            }
            // 占用覆盖或超过当前游标时才需要处理
            if (window.endTime().isAfter(cursor)) {
                // 占用开始前有一段空闲，记录下来
                if (window.startTime().isAfter(cursor)) {
                    free.add(new AvailabilityVO.TimeWindow(cursor, window.startTime()));
                }
                // 跳过占用区间
                cursor = window.endTime();
                // 游标已经到达期望结束，后续无需处理
                if (!cursor.isBefore(end)) {
                    return free;
                }
            }
        }
        // 最后一个占用之后仍有空闲，补上尾段
        if (cursor.isBefore(end)) {
            free.add(new AvailabilityVO.TimeWindow(cursor, end));
        }
        return free;
    }

    // 候选开始时间按 availabilitySlotMinutes 对齐；遇到占用则跳到占用结束再对齐，
    // 直到收集够建议数量或超出搜索天数上限。始终返回从近到远的顺序。
    /** 从期望开始时间起，按槽位粒度寻找满足所需时长的最近空闲窗口。 */
    private List<AvailabilityVO.TimeWindow> suggestWindows(List<AvailabilityVO.TimeWindow> occupied,
                                                           LocalDateTime from, Duration duration,
                                                           LocalDateTime horizonEnd) {
        // 建议窗口从近到远收集
        List<AvailabilityVO.TimeWindow> suggestions = new ArrayList<>();
        // 候选开始时间对齐到槽位边界
        LocalDateTime candidate = ceilToSlot(from);
        // 最多返回配置数量的建议，且不超出搜索范围
        while (suggestions.size() < availabilityMaxSuggestions
                && !candidate.plus(duration).isAfter(horizonEnd)) {
            // 记录候选窗口起点
            LocalDateTime windowStart = candidate;
            // 保持与期望相同的租期长度
            LocalDateTime candidateEnd = candidate.plus(duration);
            // 找到与候选窗口重叠的第一个占用区间
            AvailabilityVO.TimeWindow blocker = occupied.stream()
                    .filter(window -> window.startTime().isBefore(candidateEnd)
                            && window.endTime().isAfter(windowStart))
                    .findFirst().orElse(null);
            // 候选窗口无冲突则采纳，并把下一次候选推进到窗口结束
            if (blocker == null) {
                // 无冲突即采纳该窗口
                suggestions.add(new AvailabilityVO.TimeWindow(candidate, candidateEnd));
                // 下一个候选紧接当前窗口，并对齐槽位
                candidate = ceilToSlot(candidateEnd);
            } else {
                // 被占用时跳到占用结束并重新对齐槽位
                // 有冲突则跳过占用区间
                candidate = ceilToSlot(blocker.endTime());
            }
        }
        // 可能少于配置上限（搜索范围内没有更多窗口）
        return suggestions;
    }

    /**
     * 将时间向上对齐到槽位边界。
     * <p>先截断到分钟，再按当天分钟数对槽位取余；
     * 使用当天分钟数而不是 epoch 对齐，保证每天从 00:00 开始槽位一致。
     *
     * @param time 原始时间
     * @return 不早于原时间的最近槽位边界
     */
    private LocalDateTime ceilToSlot(LocalDateTime time) {
        // 先截断到分钟，再判断是否需要进位
        LocalDateTime minute = time.truncatedTo(ChronoUnit.MINUTES);
        // 截断后需要进位到下一分钟
        if (minute.isBefore(time)) {
            minute = minute.plusMinutes(1);
        }
        // 使用当天分钟数对齐，保证每天 00:00 起槽位一致
        long minuteOfDay = minute.getHour() * 60L + minute.getMinute();
        // 当天分钟数对槽位取余
        long remainder = minuteOfDay % availabilitySlotMinutes;
        // 已对齐则原样返回，否则补到下一个槽位
        return remainder == 0 ? minute : minute.plusMinutes(availabilitySlotMinutes - remainder);
    }

    /**
     * 追加一条工具操作日志。
     * <p>detail 生成优先级：备注 > 状态迁移文本 > 动作编码；
     * 操作人只在管理端登录时记录，用户侧触发的状态变化允许为空。
     *
     * @param toolId     工具 id
     * @param action     动作编码
     * @param fromStatus 变更前状态
     * @param toStatus   变更后状态
     * @param remark     原始备注
     */
    private void logStructured(Long toolId, String action,
                               String fromStatus, String toStatus, String remark) {
        // 构造日志实体；注意此处 log 变量遮蔽了 Slf4j 的 logger
        ToolLog log = new ToolLog();
        // 关联工具
        log.setToolId(toolId);
        // 动作编码
        log.setAction(action);
        // 变更前状态，创建时为空
        log.setFromStatus(fromStatus);
        // 变更后状态
        log.setToStatus(toStatus);
        // 原始备注
        log.setRemark(remark);
        // detail 优先使用备注
        if (StringUtils.hasText(remark)) {
            log.setDetail(remark);
        } else if (fromStatus != null && toStatus != null) {
            // 无备注时用状态迁移作为可读描述
            log.setDetail(fromStatus + " -> " + toStatus);
        } else {
            log.setDetail(action);
        }
        // 仅管理端操作记录操作人；用户侧状态变化可能没有管理员
        if (StpKit.ADMIN.isLogin()) {
            // 只记录管理端操作人
            Long adminId = StpKit.ADMIN.getLoginIdAsLong();
            log.setOperatorId(adminId);
            // 账号可能已删除，名称允许为空
            Admin admin = adminMapper.selectById(adminId);
            // 记录用户名快照，账号改名后历史日志仍可读
            log.setOperatorName(admin == null ? null : admin.getUsername());
        }
        // 日志只追加，不更新
        toolLogMapper.insert(log);
    }

    /**
     * 判断工具审核状态是否为通过。
     * <p>auditStatus 为空按未通过处理。
     *
     * @param tool 工具实体
     * @return 审核通过返回 true
     */
    private boolean isApproved(Tool tool) {
        // auditStatus 为空按未通过处理
        return tool.getAuditStatus() != null
                && tool.getAuditStatus() == AuditStatus.APPROVED.value();
    }

    /**
     * 解析工具状态编码，非法值统一返回 400。
     *
     * @param status 状态编码
     * @return 状态枚举
     * @throws BizException 编码非法时返回 400
     */
    private ToolStatus parseStatus(String status) {
        // 状态编码非法时统一 400
        return ToolStatus.fromCode(status)
                .orElseThrow(() -> new BizException(ResultCode.BAD_REQUEST, "无效的工具状态"));
    }

    /**
     * 校验类别、损坏程度与可选状态的字典合法性。
     * <p>@Schema 只影响文档展示，服务端仍必须按字典校验，
     * 防止绕过前端直接提交任意编码。
     *
     * @param dto 工具属性
     * @throws BizException 任一字典值非法时返回 400
     */
    private void validateDict(ToolSaveDTO dto) {
        // 类别必须属于字典
        if (!Dict.containsKey(Dict.TOOL_CATEGORY, dto.getCategory())) {
            throw new BizException(ResultCode.BAD_REQUEST, "无效的工具类别");
        }
        // 损坏程度必须属于字典，防止任意字符串进入排序与索引
        // 损坏程度必须属于字典
        if (!Dict.containsKey(Dict.DAMAGE_LEVEL, dto.getDamageLevel())) {
            throw new BizException(ResultCode.BAD_REQUEST, "无效的损坏程度");
        }
        // 状态可选，但传入时必须是合法编码
        if (StringUtils.hasText(dto.getStatus()) && !Dict.containsKey(Dict.TOOL_STATUS, dto.getStatus())) {
            throw new BizException(ResultCode.BAD_REQUEST, "无效的工具状态");
        }
    }

    /**
     * 把请求属性写入实体。
     * <p>字符串统一 trim 并把 null 归一为空串；
     * 使用说明经过 HTML 清洗，图片列表拼接为逗号分隔串并把第一张设为主图。
     *
     * @param tool 目标实体
     * @param dto  工具属性
     */
    private void apply(Tool tool, ToolSaveDTO dto) {
        // 名称必填，入参已校验非空白
        tool.setName(dto.getName().trim());
        // 可选文本归一为空串，避免数据库中混用 null 与空串
        // 可选文本归一为空串
        tool.setModel(dto.getModel() == null ? "" : dto.getModel().trim());
        // 类别与损坏程度已通过字典校验
        // 类别已通过字典校验
        tool.setCategory(dto.getCategory());
        // 损坏程度已通过字典校验
        tool.setDamageLevel(dto.getDamageLevel());
        // 押金精度与范围由入参校验保证
        // 押金已校验非负
        tool.setDeposit(dto.getDeposit());
        // 区域归一为空串
        // 区域归一为空串
        tool.setStorageArea(dto.getStorageArea() == null ? "" : dto.getStorageArea().trim());
        // 使用说明可能来自富文本编辑器，必须清洗后才能落库与展示
        // 使用说明可能是富文本，必须清洗后落库
        tool.setUsageGuide(HtmlSanitizer.clean(dto.getUsageGuide()));
        // 图片列表允许为空，内部统一用空列表处理
        // 图片列表统一为非 null
        List<String> images = dto.getImageUrls() == null ? List.of() : dto.getImageUrls();
        // 数据库以逗号分隔字符串保存
        // 以逗号分隔保存
        tool.setImageUrls(String.join(",", images));
        // 第一张作为列表主图
        // 第一张作为主图
        tool.setImageUrl(images.isEmpty() ? "" : images.get(0));
        // 采购日期可选
        tool.setPurchaseDate(dto.getPurchaseDate());
        // 供应商归一为空串
        // 供应商归一为空串
        tool.setSupplier(dto.getSupplier() == null ? "" : dto.getSupplier().trim());
    }

    /**
     * 单条工具转视图：补齐创建人用户名与评分统计。
     *
     * @param tool 工具实体
     * @return 工具视图
     */
    private ToolVO toVO(Tool tool) {
        ToolVO vo = toolVoMapper.toVO(tool);
        if (tool.getCreatedBy() != null) {
            // 创建人可能已被删除，名称允许为空
            // 创建人可能已删除，名称允许为空
            Admin admin = adminMapper.selectById(tool.getCreatedBy());
            vo.setCreatedByName(admin == null ? null : admin.getUsername());
        }
        // 单条详情实时查询评分统计
        applyRating(vo, toolReviewMapper.selectStatsByToolId(tool.getId()));
        return vo;
    }

    /**
     * 分页结果批量转视图。
     * <p>创建人用户名与评分统计都按当页 id 批量查询，
     * 避免逐条查库造成 N+1。
     *
     * @param result 工具分页结果
     * @return 工具视图分页
     */
    private PageVO<ToolVO> toPage(Page<Tool> result) {
        // 收集当页创建人 id 并去重
        Set<Long> creatorIds = result.getRecords().stream()
                .map(Tool::getCreatedBy)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        // 批量查询创建人用户名，避免逐条查询
        Map<Long, String> names = creatorIds.isEmpty()
                ? Map.of()
                : adminMapper.selectBatchIds(creatorIds).stream()
                        .collect(Collectors.toMap(Admin::getId, Admin::getUsername));
        // 收集当页工具 id 用于批量评分
        List<Long> toolIds = result.getRecords().stream().map(Tool::getId).toList();
        // 批量查询评分统计，避免逐条聚合
        Map<Long, ToolRatingStats> ratings = toolIds.isEmpty() ? Map.of()
                : toolReviewMapper.selectStatsByToolIds(toolIds).stream()
                        .collect(Collectors.toMap(ToolRatingStats::getToolId, stats -> stats));
        // 分页映射在内存中补齐批量查询结果
        return PageVO.of(result, tool -> {
            ToolVO vo = toolVoMapper.toVO(tool);
            // 创建人名称可能为空（账号已删除）
            // 创建人名称来自批量查询
            vo.setCreatedByName(names.get(tool.getCreatedBy()));
            // 评分来自批量统计
            applyRating(vo, ratings.get(tool.getId()));
            return vo;
        });
    }

    // 统一在服务端完成四舍五入，避免不同端展示精度不一致；
    // 无评价时返回 0.0 而不是 null，前端无需额外判空。
    /** 平均分保留一位小数；无评价时明确返回 0.0 与 0。 */
    private void applyRating(ToolVO vo, ToolRatingStats stats) {
        // 无评价时返回 0.0 与 0
        if (stats == null || stats.getReviewCount() == null || stats.getReviewCount() == 0) {
            // 无评价返回 0.0，保持一位小数
            vo.setRatingAvg(BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP));
            // 无评价数量为 0
            vo.setReviewCount(0L);
            return;
        }
        // 平均分统一保留一位小数，服务端完成舍入
        vo.setRatingAvg(stats.getRatingAvg().setScale(1, RoundingMode.HALF_UP));
        vo.setReviewCount(stats.getReviewCount());
    }
}
