package com.cdwater.toolshare.module.message;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 单行消息渠道配置，主键固定为 1。 */
@Data
@TableName("t_message_config")
public class MessageConfig {

    @TableId(type = IdType.INPUT)
    private Long id;

    /** SYSTEM/SMS */
    private String defaultChannel;

    /** 0 关闭 1 开启 */
    private Integer smsEnabled;

    private LocalDateTime updateTime;
}
