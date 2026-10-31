package com.cdwater.toolshare.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class ToolSaveDTO {

    @NotBlank(message = "工具名称不能为空")
    private String name;

    private String model;

    @NotBlank(message = "工具类别不能为空")
    private String category;

    @NotBlank(message = "损坏程度不能为空")
    private String damageLevel;

    @NotNull(message = "押金不能为空")
    @DecimalMin(value = "0", message = "押金不能为负数")
    private BigDecimal deposit;

    private String storageArea;

    private String usageGuide;

    private List<String> imageUrls;

    private LocalDate purchaseDate;

    private String supplier;

    private String status;
}
