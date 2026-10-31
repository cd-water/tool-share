package com.cdwater.toolshare.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ToolLogVO {

    private Long id;
    private Long toolId;
    private String action;
    private String detail;
    private Long operatorId;
    private LocalDateTime createTime;
}
