package com.cdwater.toolshare.module.damage;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
/**
 * progress_status、progress_note 与 progress_update_time，并保留
 * 首次写入的维修开始时间。进度为 COMPLETED 之前不允许验收。
 * <p>可选字段策略：费用、预计时长与负责人为空时保留数据库已有值，
 * 因此调用方可以只提交本次变化的字段，而不必回传完整维修信息。
 * <p>进度更新是维修过程的唯一写入口：每提交一次都会刷新
 * 管理端更新维修进度的请求参数。
 * <p>进度更新只能作用于已核验或维修中的报修单；进度状态为
 * WAITING_PARTS、REPAIRING、COMPLETED 或 FAILED。
 */
public class DamageProgressDTO {

    /**
     * 维修进度：WAITING_PARTS（等待配件）、REPAIRING（维修中）、
     * COMPLETED（维修完成）或 FAILED（维修失败）。
     * <p>只有 COMPLETED 的报修单才允许进入验收环节。
     */
    @NotBlank(message = "请选择维修进度")
    @Size(max = 20, message = "维修进度不能超过 20 个字符")
    @Schema(allowableValues = {"WAITING_PARTS", "REPAIRING", "COMPLETED", "FAILED"})
    private String progressStatus;

    /** 面向报修人的进度说明，最多 500 个字符。 */
    // 该说明会原样出现在居民端的进度通知中，应避免内部术语。
    @NotBlank(message = "请填写进度说明")
    @Size(max = 500, message = "进度说明不能超过 500 个字符")
    private String progressNote;

    /** 当前累计维修费用，可为空；非空时不能为负数。 */
    // 费用可在多次进度更新中修订，最终以验收时填写的金额为准。
    @DecimalMin(value = "0", message = "维修费用不能为负数")
    private BigDecimal repairCost;

    /** 预计维修时长，例如「约 3 天」，最多 100 个字符。 */
    // 预计时长仅用于告知居民，不参与任何时间计算。
    @Size(max = 100, message = "预计时长不能超过 100 个字符")
    private String repairEstimate;

    /** 维修负责人姓名或工号，最多 64 个字符。 */
    // 负责人可在维修中途更换，留空表示沿用上一次记录。
    @Size(max = 64, message = "维修负责人不能超过 64 个字符")
    private String repairPerson;
}
