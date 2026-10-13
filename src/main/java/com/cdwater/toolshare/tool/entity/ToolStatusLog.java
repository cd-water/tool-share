package com.cdwater.toolshare.tool.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具状态日志表（状态每次变更自动记录）
 */
@Data
@TableName("tool_status_log")
public class ToolStatusLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 工具 id */
    private Long toolId;

    /** 变更前状态（取值同 tool.status） */
    private Integer oldStatus;

    /** 变更后状态（取值同 tool.status） */
    private Integer newStatus;

    /** 操作人 user.id */
    private Long operatorId;

    /** 备注（关联单号等） */
    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
