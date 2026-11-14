/**
 * 租赁费用明细视图文件。
 * <p>用于租赁详情展示，包含费用类型编码与中文名、金额、说明、经办人与时间。
 * <h2>数据口径</h2>
 * <p>每条记录对应一次费用或减免事实；减免金额为正数并带原因，
 * 原始费用记录保持不变。
 * <p>调用方：RentalService#toFeeVO。
 */
package com.cdwater.toolshare.module.rental;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 租赁费用明细：逾期费、损坏赔偿与减免。 */
@Data
public class RentalFeeVO {

    // 费用明细主键
    private Long id;
    @Schema(allowableValues = {"OVERDUE", "DAMAGE", "WAIVER", "OTHER"})
    // 费用类型编码
    private String feeType;
    // 费用类型中文名
    private String feeTypeName;
    // 金额
    private BigDecimal amount;
    // 费用说明
    private String reason;
    // 经办人
    private String operatorName;
    // 记录时间
    private LocalDateTime createTime;
}
