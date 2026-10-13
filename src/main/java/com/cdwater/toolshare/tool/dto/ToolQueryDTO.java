package com.cdwater.toolshare.tool.dto;

import com.cdwater.toolshare.common.dto.PageQuery;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 工具分页查询入参
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ToolQueryDTO extends PageQuery {

    /** 关键词：工具名称/型号，模糊 */
    @Size(max = 100, message = "关键词最长 100 字")
    private String keyword;

    /** 类别：1维修 2园艺 3清洁 4其他 */
    @Min(value = 1, message = "类别取值不正确")
    @Max(value = 4, message = "类别取值不正确")
    private Integer category;

    /** 状态：1可用 2已借出 3维修中 4报废 5下架 */
    @Min(value = 1, message = "状态取值不正确")
    @Max(value = 5, message = "状态取值不正确")
    private Integer status;

    /** 存放区域，模糊 */
    @Size(max = 100, message = "存放区域最长 100 字")
    private String storageLocation;

    /** 工作人员端排序：rentalCount 按租赁次数、lastRepairTime 按最近维修时间 */
    @Pattern(regexp = "rentalCount|lastRepairTime", message = "排序字段取值不正确")
    private String orderBy;
}
