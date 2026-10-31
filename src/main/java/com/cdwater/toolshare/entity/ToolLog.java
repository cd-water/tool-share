package com.cdwater.toolshare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_tool_log")
public class ToolLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long toolId;

    private String action;

    private String detail;

    private Long operatorId;

    private LocalDateTime createTime;
}
