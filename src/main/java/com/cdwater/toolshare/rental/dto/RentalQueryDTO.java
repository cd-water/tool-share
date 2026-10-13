package com.cdwater.toolshare.rental.dto;

import com.cdwater.toolshare.common.dto.PageQuery;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 租赁记录分页查询入参
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RentalQueryDTO extends PageQuery {

    /** 租赁人姓名，模糊（工作人员端） */
    @Size(max = 50, message = "租赁人姓名最长 50 字")
    private String userName;

    /** 工具编号，模糊 */
    @Size(max = 20, message = "工具编号最长 20 字")
    private String toolCode;

    /** 验收结果：1完好 2轻微损坏 3严重损坏 */
    @Min(value = 1, message = "验收结果取值不正确")
    @Max(value = 3, message = "验收结果取值不正确")
    private Integer acceptResult;

    /** 逾期筛选：true 仅逾期 */
    private Boolean overdue;
}
