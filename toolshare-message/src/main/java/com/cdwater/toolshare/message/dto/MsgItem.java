package com.cdwater.toolshare.message.dto;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class MsgItem {

    private Long id;

    private String type;

    private String title;

    private String content;

    private String channel;

    private String sendStatus;

    private LocalDateTime sendTime;

    private String bizCode;

    private LocalDateTime createTime;
}
