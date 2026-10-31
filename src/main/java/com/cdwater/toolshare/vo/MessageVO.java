package com.cdwater.toolshare.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MessageVO {

    private Long id;
    private String type;
    private String typeName;
    private String title;
    private String content;
    private String channel;
    private Boolean readStatus;
    private LocalDateTime createTime;
}
