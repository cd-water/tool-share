package com.cdwater.toolshare.repair.dto;

import com.cdwater.toolshare.common.dto.PageQuery;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 报修单分页查询入参
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RepairQueryDTO extends PageQuery {

    /** 工具编号，模糊 */
    @Size(max = 20, message = "工具编号最长 20 字")
    private String toolCode;

    /** 维修进度：1待配件 2维修中 3维修完成 4维修失败（报废） */
    @Min(value = 1, message = "维修进度取值不正确")
    @Max(value = 4, message = "维修进度取值不正确")
    private Integer repairStatus;
}
