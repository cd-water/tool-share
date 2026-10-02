package com.cdwater.toolshare.tool.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;

/**
 * 工具录入（字段对齐 docs/附录.md 录入单，录入后 auditStatus=PENDING）
 */
@Data
public class ToolCreateRequest {

    @NotBlank(message = "工具名称不能为空")
    private String name;

    @NotBlank(message = "型号规格不能为空")
    private String model;

    @NotNull(message = "工具类别不能为空")
    private Long categoryId;

    @NotBlank(message = "存放区域不能为空")
    private String storageArea;

    @NotBlank(message = "使用说明不能为空")
    private String description;

    @NotBlank(message = "初始损坏程度不能为空")
    private String damageLevel;

    @NotNull(message = "押金金额不能为空")
    @DecimalMin(value = "0.01", message = "押金金额必须大于 0")
    private BigDecimal depositAmount;

    private LocalDate purchaseDate;

    private String supplier;
}
