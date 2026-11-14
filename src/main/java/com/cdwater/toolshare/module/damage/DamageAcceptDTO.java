package com.cdwater.toolshare.module.damage;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
/**
 * 管理端验收报修的请求参数。
 * <p>验收结果是报修单进入终态的关键输入：合格（QUALIFIED）、部分合格
 * （PARTIAL）与不合格（UNQUALIFIED）会分别影响工具最终状态。维修费用
 * <p>验收人、验收时间由服务端写入，请求体不包含这些字段。
 * 可为空，为空时保留维修过程中记录的费用。
 */
public class DamageAcceptDTO {
    // 验收结论决定报修单最终状态。
    // 验收后工具状态会结合租赁是否结束修正。
    // 维修费用为空时保留维修过程中记录的费用。
    // 验收人与验收时间由服务端写入。

    /**
     * 验收结论：QUALIFIED（合格）、UNQUALIFIED（不合格）或 PARTIAL（部分合格）。
     * <p>非合格结论不允许把工具直接置为可用，服务层会再次校验。
     */
    @NotBlank(message = "请选择验收结果")
    @Size(max = 20, message = "验收结果不能超过 20 个字符")
    @Schema(allowableValues = {"QUALIFIED", "UNQUALIFIED", "PARTIAL"})
    private String acceptResult;

    /**
     * 验收后希望的工具状态：AVAILABLE（可用）、MAINTENANCE（维修中）
     * 或 SCRAPPED（已报废）。
     * <p>最终状态还会结合验收结论与关联租赁是否结束进行修正。
     */
    @NotBlank(message = "请选择验收后工具状态")
    @Size(max = 20, message = "工具状态不能超过 20 个字符")
    @Schema(allowableValues = {"AVAILABLE", "MAINTENANCE", "SCRAPPED"})
    private String toolStatusAfter;

    /**
     * 本次维修的总费用，可为空；非空时必须大于等于 0。
     */
    @DecimalMin(value = "0", message = "维修费用不能为负数")
    private BigDecimal repairCost;

    /**
     * 验收备注，可选，最多 500 个字符。
     */
    @Size(max = 500, message = "备注不能超过 500 个字符")
    private String remark;
}
