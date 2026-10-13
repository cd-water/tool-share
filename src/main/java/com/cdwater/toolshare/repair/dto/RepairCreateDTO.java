package com.cdwater.toolshare.repair.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 提交报修入参
 */
@Data
public class RepairCreateDTO {

    /** 关联租赁单 id */
    @NotNull(message = "关联租赁单不能为空")
    private Long rentalOrderId;

    /** 工具 id */
    @NotNull(message = "工具不能为空")
    private Long toolId;

    /** 损坏部位 */
    @NotBlank(message = "损坏部位不能为空")
    @Size(max = 100, message = "损坏部位最长 100 字")
    private String damagePart;

    /** 损坏描述 */
    @NotBlank(message = "损坏描述不能为空")
    @Size(max = 2000, message = "损坏描述最长 2000 字")
    private String damageDesc;

    /** 损坏证明材料 URL（1-3 张） */
    @Size(max = 3, message = "损坏证明材料最多 3 张")
    private List<String> images;
}
