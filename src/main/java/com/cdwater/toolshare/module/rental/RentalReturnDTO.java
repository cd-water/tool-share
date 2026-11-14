/**
 * 归还验收请求体文件。
 * <p>只接受业务事实与带原因的减免，金额一律由服务端计算。
 * <h2>字段</h2>
 * <ul>
 *   <li>actualReturnTime：不能晚于当前时间，也不能早于租期开始；</li>
 *   <li>damageResult：INTACT/MINOR/SEVERE；</li>
 *   <li>waiveOverdue/waiveDamage：全额减免标志；</li>
 *   <li>returnWithDamage：存在未终结报修时必须显式确认；</li>
 *   <li>waiverReason：任一减免标志为 true 时必填。</li>
 * </ul>
 * <p>调用方：AdminRentalController#returnRental。
 */
package com.cdwater.toolshare.module.rental;

import io.swagger.v3.oas.annotations.media.Schema;
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
    // 实际归还时间，不能晚于当前时间
    private LocalDateTime actualReturnTime;

    @NotBlank(message = "请选择验收结果")
    @Size(max = 16, message = "验收结果不能超过 16 个字符")
    @Schema(allowableValues = {"INTACT", "MINOR", "SEVERE"})
    // 验收结果：INTACT / MINOR / SEVERE
    private String damageResult;

    /** 全额减免逾期费 */
    // true 表示全额减免逾期费
    private Boolean waiveOverdue;

    /** 全额减免损坏赔偿 */
    // true 表示全额减免损坏赔偿
    private Boolean waiveDamage;

    /** 存在未终结报修时，显式确认带伤归还；工具保持维修中 */
    // 存在未终结报修时，必须显式 true 才能带伤归还
    private Boolean returnWithDamage;

    /** 减免原因：任一减免标志为 true 时必填 */
    @Size(max = 500, message = "减免原因不能超过 500 个字符")
    // 减免原因；任一减免标志为 true 时必填
    private String waiverReason;

    @Size(max = 500, message = "备注不能超过 500 个字符")
    // 归还备注，可为空
    private String remark;
}
