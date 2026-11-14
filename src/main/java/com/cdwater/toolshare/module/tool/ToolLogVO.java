package com.cdwater.toolshare.module.tool;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ToolLogVO {

    private Long id;
    private Long toolId;
    private String action;
    private String fromStatus;
    private String toStatus;
    private String detail;
    private Long operatorId;
    private String operatorName;
    private String remark;
    private LocalDateTime createTime;
}
