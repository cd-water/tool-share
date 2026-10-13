package com.cdwater.toolshare.notify.dto;

import com.cdwater.toolshare.common.dto.PageQuery;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 我的消息分页查询入参
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class NotifyQueryDTO extends PageQuery {

    /** 类型：1取件提醒 2到期提醒 3逾期提醒 4维修完成提醒 */
    @Min(value = 1, message = "消息类型取值不正确")
    @Max(value = 4, message = "消息类型取值不正确")
    private Integer type;

    /** 阅读状态：0未读 1已读 */
    @Min(value = 0, message = "阅读状态取值不正确")
    @Max(value = 1, message = "阅读状态取值不正确")
    private Integer readStatus;
}
