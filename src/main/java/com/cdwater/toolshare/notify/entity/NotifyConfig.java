package com.cdwater.toolshare.notify.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 默认提醒方式配置（单行配置表，主键固定为 1）
 */
@Data
@TableName("notify_config")
public class NotifyConfig {

    @TableId(type = IdType.INPUT)
    private Long id;

    /** 渠道：1系统内 2短信 */
    private Integer channel;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
