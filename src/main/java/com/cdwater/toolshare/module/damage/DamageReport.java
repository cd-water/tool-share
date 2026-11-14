package com.cdwater.toolshare.module.damage;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_damage_report")
/**
 * 损坏报修单持久化实体，对应表 {@code t_damage_report}。
 * <p>一张报修单完整记录「提交 → 核验 → 维修 → 验收」全过程的快照：
 * 租赁与工具信息在提交时冗余落库，避免后续租赁或工具变更影响历史追溯。
 * <p>状态字段只允许由 {@link DamageService} 依据 {@link DamageStateMachine}
 * <p>生命周期与字段写入时机：
 * <ol>
 *   <li>创建：写入租赁/工具/报修人快照、报修单号，状态为 SUBMITTED；</li>
 *   <li>核验：写入核验人、核验时间、核验结论与维修安排，状态变为
 *       VERIFIED 或 REJECTED；</li>
 *   <li>维修：每次进度更新刷新进度字段，首次更新写入 repair_start_time；</li>
 *   <li>验收：写入验收人、结论与工具最终状态，状态变为 COMPLETED
 *       或 SCRAPPED。</li>
 * </ol>
 * <p>报修单保存的是提交时刻的租赁与工具快照，而不是实时关联查询结果，
 * <p>状态字段只允许由 DamageService 依据状态机迁移。
 * 因此即使租赁结束或工具改名，历史报修仍然完整可读。
 * 迁移；报修单号由全局序列服务生成，同一租赁的处理中报修由唯一索引兜底。
 */
public class DamageReport {

    @TableId(type = IdType.AUTO)
    /** 报修单主键，数据库自增。 */
    private Long id;

    /** 业务报修单号，由序列服务生成，用于展示与检索。 */
    private String reportNo;

    /** 关联租赁单主键；报修创建后不再变更。 */
    private Long rentalId;

    /** 关联工具主键，创建时从租赁单冗余。 */
    private Long toolId;

    /** 工具编号快照，便于报修单独立展示。 */
    private String toolCode;

    /** 工具名称快照，便于后续检索与历史追溯。 */
    private String toolName;

    /** 报修居民的用户主键。 */
    private Long userId;

    /** 报修人显示名称快照，优先真实姓名，其次昵称。 */
    private String reporterName;

    /** 报修人联系电话快照；对外返回时会做脱敏处理。 */
    private String reporterPhone;

    /** 损坏部位，例如「刀片」「把手」。 */
    private String damagePart;

    /** 损坏情况文字描述，由居民提交。 */
    private String description;

    /** 损坏照片地址，多个地址以英文逗号分隔。 */
    private String images;

    /** 报修单状态：SUBMITTED/VERIFIED/REJECTED/REPAIRING/COMPLETED/SCRAPPED。 */
    private String status;

    /** 核验管理员名称快照。 */
    private String verifier;

    /** 核验完成时间。 */
    private LocalDateTime verifyTime;

    /** 核验结论：CONFIRMED（确认损坏）、SUSPECTED（存疑）或 FALSE（误报）。 */
    private String verifyResult;

    /** 维修负责人姓名或工号。 */
    private String repairPerson;

    /** 预计维修时长描述。 */
    private String repairEstimate;

    /** 首次更新维修进度时写入的维修开始时间。 */
    private LocalDateTime repairStartTime;

    /** 当前记录的维修费用，验收时可覆盖为最终费用。 */
    private BigDecimal repairCost;

    /** 最新维修进度：WAITING_PARTS/REPAIRING/COMPLETED/FAILED。 */
    private String progressStatus;

    /** 最近一次进度更新时间。 */
    private LocalDateTime progressUpdateTime;

    /** 最近一次进度说明，面向报修人展示。 */
    private String progressNote;

    /** 验收管理员名称快照。 */
    private String acceptor;

    /** 验收完成时间。 */
    private LocalDateTime acceptTime;

    /** 验收结论：QUALIFIED（合格）、UNQUALIFIED（不合格）或 PARTIAL（部分合格）。 */
    private String acceptResult;

    /** 验收后工具最终状态：AVAILABLE/MAINTENANCE/SCRAPPED。 */
    private String toolStatusAfter;

    /** 核验或验收备注。 */
    private String remark;

    /** 报修单创建时间，列表默认按此字段倒序。 */
    private LocalDateTime createTime;

    /** 报修单最后更新时间。 */
    private LocalDateTime updateTime;
}
