package com.cdwater.toolshare.notify.mq;

import lombok.Data;

/**
 * 后端 A 投递的提醒消息体：{userId, type, bizNo, content}
 */
@Data
public class NotifyMqMessage {

    /** 接收人 user.id */
    private Long userId;

    /** 类型：1取件提醒 2到期提醒 3逾期提醒 */
    private Integer type;

    /** 关联业务单号（预约单号） */
    private String bizNo;

    /** 提醒内容 */
    private String content;
}
