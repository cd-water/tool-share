package com.cdwater.toolshare.rental.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 归还验收入参
 */
@Data
public class RentalReturnDTO {

    /** 验收结果：1完好 2轻微损坏（无需赔偿） 3严重损坏（需赔偿） */
    @NotNull(message = "验收结果不能为空")
    @Min(value = 1, message = "验收结果取值不正确")
    @Max(value = 3, message = "验收结果取值不正确")
    private Integer acceptResult;

    /** 逾期费用（元，封顶押金，无逾期传 0） */
    @NotNull(message = "逾期费用不能为空")
    @DecimalMin(value = "0.00", message = "逾期费用不能为负数")
    @Digits(integer = 8, fraction = 2, message = "逾期费用格式不正确")
    private BigDecimal overdueFee;

    /** 押金实退金额（扣除逾期费/赔偿） */
    @NotNull(message = "押金实退金额不能为空")
    @DecimalMin(value = "0.00", message = "押金实退金额不能为负数")
    @Digits(integer = 8, fraction = 2, message = "押金实退金额格式不正确")
    private BigDecimal depositRefundAmount;
}
