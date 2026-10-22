package com.cdwater.toolshare.config;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 提醒消息体（后端 A 生产，后端 B 的 notify 消费者消费）。
 * <p>交换机/队列声明见 {@code config.RabbitMqConfig}。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotifyMessage {

    /** 接收人 user.id */
    private Long userId;

    /** 类型：1取件提醒 2到期提醒 3逾期提醒 4维修完成提醒 */
    private Integer type;

    /** 关联业务单号（预约单号/报修单号） */
    private String bizNo;

    /** 消息内容 */
    private String content;
}
