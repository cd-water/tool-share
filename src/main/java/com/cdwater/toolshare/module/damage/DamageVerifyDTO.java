package com.cdwater.toolshare.module.damage;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
/**
 * <p>核验是报修从「居民自述」转为「官方确认」的环节：误报会被驳回，
 * 确认或存疑都会把工具置为维修中。核验人由会话决定，不来自请求体。
 * 维修负责人与预计时长在本环节写入，后续进度更新可以覆盖。
 * 管理端核验报修的请求参数。
 * <p>核验结论决定报修单进入「已核验」还是「已驳回」；
 * 维修负责人与预计时长会写入报修单并在验收前展示。
 */
public class DamageVerifyDTO {

    /**
     * 核验结论：CONFIRMED（确认损坏）、SUSPECTED（存疑）或 FALSE（误报）。
     * <p>仅 FALSE 会驳回报修，其余两种情况都会把工具转为维修中。
     */
    @NotBlank(message = "请选择核验结果")
    @Size(max = 20, message = "核验结果不能超过 20 个字符")
    @Schema(allowableValues = {"CONFIRMED", "SUSPECTED", "FALSE"})
    private String verifyResult;

    /** 维修负责人姓名或工号，可选。 */
    // 可为空：允许核验时尚未确定具体维修人员。
    @Size(max = 64, message = "维修负责人不能超过 64 个字符")
    private String repairPerson;

    /** 预计维修时长描述，可选。 */
    // 可为空：维修人员可在后续进度更新中补充。
    @Size(max = 100, message = "预计时长不能超过 100 个字符")
    private String repairEstimate;

    /** 核验备注，可选。 */
    // 备注会保存到报修单，供验收与后续查询参考。
    @Size(max = 500, message = "备注不能超过 500 个字符")
    private String remark;
}
