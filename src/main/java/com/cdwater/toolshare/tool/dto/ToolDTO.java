package com.cdwater.toolshare.tool.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 工具创建/更新入参（附录工具信息录入单字段）
 */
@Data
public class ToolDTO {

    @NotBlank(message = "工具名称不能为空")
    @Size(max = 100, message = "工具名称最长 100 字")
    private String name;

    @NotBlank(message = "型号规格不能为空")
    @Size(max = 200, message = "型号规格最长 200 字")
    private String modelSpec;

    /** 类别：1维修 2园艺 3清洁 4其他 */
    @NotNull(message = "工具类别不能为空")
    @Min(value = 1, message = "工具类别取值不正确")
    @Max(value = 4, message = "工具类别取值不正确")
    private Integer category;

    /** 损坏程度：1全新 2轻微磨损 3中度磨损 4严重损坏 */
    @NotNull(message = "损坏程度不能为空")
    @Min(value = 1, message = "损坏程度取值不正确")
    @Max(value = 4, message = "损坏程度取值不正确")
    private Integer damageLevel;

    /** 押金金额（元） */
    @NotNull(message = "押金金额不能为空")
    @DecimalMin(value = "0.01", message = "押金金额必须大于 0")
    @Digits(integer = 8, fraction = 2, message = "押金金额格式不正确")
    private BigDecimal deposit;

    @NotBlank(message = "存放区域不能为空")
    @Size(max = 100, message = "存放区域最长 100 字")
    private String storageLocation;

    @NotBlank(message = "使用说明不能为空")
    @Size(max = 5000, message = "使用说明最长 5000 字")
    private String usageInstruction;

    /** 工具图片 URL 列表 */
    @Size(max = 9, message = "工具图片最多 9 张")
    private List<String> images;

    /** 采购日期 */
    private LocalDate purchaseDate;

    /** 供应商信息 */
    @Size(max = 200, message = "供应商信息最长 200 字")
    private String supplierInfo;
}
