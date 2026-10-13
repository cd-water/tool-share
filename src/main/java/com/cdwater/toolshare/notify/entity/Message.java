package com.cdwater.toolshare.notify.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息提醒表（提醒记录存档）
 */
@Data
@TableName("message")
public class Message {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 接收人 user.id */
    private Long userId;

    /** 类型：1取件提醒 2到期提醒 3逾期提醒 4维修完成提醒 */
    private Integer type;

    /** 标题 */
    private String title;

    /** 内容 */
    private String content;

    /** 渠道：1系统内 2短信 */
    private Integer channel;

    /** 发送状态：1待发送 2已发送 3发送失败 */
    private Integer sendStatus;

    /** 发送时间 */
    private LocalDateTime sendTime;

    /** 关联业务单号（预约单号/报修单号） */
    private String bizNo;

    /** 阅读状态：0未读 1已读 */
    private Integer readStatus;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
