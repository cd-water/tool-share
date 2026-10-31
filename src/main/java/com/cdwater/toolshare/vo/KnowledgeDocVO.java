package com.cdwater.toolshare.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KnowledgeDocVO {

    private Long id;
    private String title;
    private String fileName;
    private String contentPreview;
    private LocalDateTime createTime;
}
