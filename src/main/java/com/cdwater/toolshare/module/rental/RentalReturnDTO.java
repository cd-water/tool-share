package com.cdwater.toolshare.module.rental;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 归还验收请求：只接受业务事实与带原因的减免，金额一律由服务端计算。
 */
@Data
public class RentalReturnDTO {

    @NotNull(message = "请选择实际归还时间")
    @PastOrPresent(message = "实际归还时间不能晚于当前时间")
    private LocalDateTime actualReturnTime;

    @NotBlank(message = "请选择验收结果")
    @Size(max = 16, message = "验收结果不能超过 16 个字符")
    private String damageResult;

    /** 全额减免逾期费 */
    private Boolean waiveOverdue;

    /** 全额减免损坏赔偿 */
    private Boolean waiveDamage;

    /** 减免原因：任一减免标志为 true 时必填 */
    @Size(max = 500, message = "减免原因不能超过 500 个字符")
    private String waiverReason;

    @Size(max = 500, message = "备注不能超过 500 个字符")
    private String remark;
}
