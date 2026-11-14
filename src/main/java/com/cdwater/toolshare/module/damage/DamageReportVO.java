package com.cdwater.toolshare.module.damage;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
/**
 * 报修单对外视图对象（居民端与管理端共用）。
 * <p>相比实体额外提供工具主图、图片数组、电话脱敏以及各状态的中文名称，
 * <p>字段分组：
 * <ul>
 *   <li>基础信息：报修单号、租赁与工具快照，保证历史可追溯；</li>
 *   <li>核验信息：核验人、时间、结论与维修安排；</li>
 *   <li>维修信息：进度、费用、负责人与时间；</li>
 *   <li>验收信息：验收人、结论、工具最终状态与备注。</li>
 * </ul>
 * <p>状态编码旁提供对应的中文名称字段，前端无需再维护一份字典；
 * <p>状态编码旁同时提供中文名称字段，前端无需维护字典。
 * 电话字段在返回前统一脱敏，图片字段则从逗号字符串拆为数组。
 * 由 {@link DamageReportVoMapper} 在映射后统一补齐。
 */
public class DamageReportVO {
    // 居民端与管理端共用同一视图对象。
    // 状态编码旁提供中文名称字段。
    // 电话已脱敏，图片已从逗号字符串拆为数组。
    // 工具主图由服务层按列表或详情场景补齐。

    /** 报修单主键。 */
    private Long id;
    /** 业务报修单号。 */
    private String reportNo;
    /** 关联租赁单主键。 */
    private Long rentalId;
    /** 关联工具主键。 */
    private Long toolId;
    /** 工具编号。 */
    private String toolCode;
    /** 工具名称。 */
    private String toolName;
    /** 工具主图地址（对象存储直链），列表批量补齐。 */
    private String toolImageUrl;
    /** 报修居民用户主键。 */
    private Long userId;
    /** 报修人显示名称。 */
    private String reporterName;
    /** 报修人电话，已脱敏。 */
    private String reporterPhone;
    /** 损坏部位。 */
    private String damagePart;
    /** 损坏描述。 */
    private String description;
    /** 损坏照片地址数组，由逗号分隔字符串解析而来。 */
    private List<String> images;
    @Schema(allowableValues = {
            "SUBMITTED", "VERIFIED", "REJECTED", "REPAIRING", "COMPLETED", "SCRAPPED"})
    /** 报修单状态编码。 */
    private String status;
    /** 报修单状态中文名称，由字典翻译。 */
    private String statusName;
    /** 核验管理员名称。 */
    private String verifier;
    /** 核验时间。 */
    private LocalDateTime verifyTime;
    @Schema(allowableValues = {"CONFIRMED", "SUSPECTED", "FALSE"})
    /** 核验结论编码。 */
    private String verifyResult;
    /** 维修负责人。 */
    private String repairPerson;
    /** 预计维修时长。 */
    private String repairEstimate;
    /** 维修开始时间。 */
    private LocalDateTime repairStartTime;
    /** 维修费用。 */
    private BigDecimal repairCost;
    @Schema(allowableValues = {"WAITING_PARTS", "REPAIRING", "COMPLETED", "FAILED"})
    /** 最新维修进度编码。 */
    private String progressStatus;
    /** 最新维修进度中文名称。 */
    private String progressStatusName;
    /** 最近进度更新时间。 */
    private LocalDateTime progressUpdateTime;
    /** 最新进度说明。 */
    private String progressNote;
    /** 验收管理员名称。 */
    private String acceptor;
    /** 验收时间。 */
    private LocalDateTime acceptTime;
    @Schema(allowableValues = {"QUALIFIED", "UNQUALIFIED", "PARTIAL"})
    /** 验收结论编码。 */
    private String acceptResult;
    /** 验收结论中文名称。 */
    private String acceptResultName;
    @Schema(allowableValues = {"AVAILABLE", "MAINTENANCE", "SCRAPPED"})
    /** 验收后工具状态编码。 */
    private String toolStatusAfter;
    /** 备注。 */
    private String remark;
    /** 创建时间。 */
    private LocalDateTime createTime;
    /** 更新时间。 */
    private LocalDateTime updateTime;
}
