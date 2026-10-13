package com.cdwater.toolshare.repair.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 维修进度更新入参
 */
@Data
public class RepairProgressDTO {

    /** 维修进度：1待配件 2维修中 3维修完成 4维修失败（报废） */
    @NotNull(message = "维修进度不能为空")
    @Min(value = 1, message = "维修进度取值不正确")
    @Max(value = 4, message = "维修进度取值不正确")
    private Integer status;

    /** 进度说明 */
    @Size(max = 500, message = "进度说明最长 500 字")
    private String description;
}
