package com.cdwater.toolshare.module.tool;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class ToolSaveDTO {

    @NotBlank(message = "工具名称不能为空")
    @Size(max = 100, message = "工具名称不能超过 100 个字符")
    private String name;

    @Size(max = 200, message = "型号规格不能超过 200 个字符")
    private String model;

    @NotBlank(message = "工具类别不能为空")
    @Size(max = 16, message = "工具类别不能超过 16 个字符")
    @Schema(allowableValues = {"WX", "YY", "QJ", "QT"})
    private String category;

    @NotBlank(message = "损坏程度不能为空")
    @Size(max = 16, message = "损坏程度不能超过 16 个字符")
    @Schema(allowableValues = {"NEW", "LIGHT", "MEDIUM", "SEVERE"})
    private String damageLevel;

    @NotNull(message = "押金不能为空")
    @DecimalMin(value = "0", message = "押金不能为负数")
    private BigDecimal deposit;

    @Size(max = 200, message = "存放区域不能超过 200 个字符")
    private String storageArea;

    private String usageGuide;

    private List<@Size(max = 500, message = "图片地址不能超过 500 个字符") String> imageUrls;

    private LocalDate purchaseDate;

    @Size(max = 200, message = "供应商不能超过 200 个字符")
    private String supplier;

    @Size(max = 20, message = "工具状态不能超过 20 个字符")
    @Schema(allowableValues = {"AVAILABLE", "RENTED", "MAINTENANCE", "SCRAPPED"})
    private String status;
}
