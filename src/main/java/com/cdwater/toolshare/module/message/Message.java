package com.cdwater.toolshare.module.message;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_message")
public class Message {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** USER/ADMIN */
    private String receiverType;

    private Long receiverId;

    private String type;

    private String title;

    private String content;

    /** SYSTEM/SMS */
    private String channel;

    private Integer readStatus;

    private String bizKey;

    private LocalDateTime createTime;
}
