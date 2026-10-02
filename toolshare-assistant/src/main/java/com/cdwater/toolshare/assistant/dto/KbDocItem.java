package com.cdwater.toolshare.assistant.dto;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class KbDocItem {

    private Long id;

    private String docName;

    private String docType;

    private Long toolId;

    private String toolName;

    private String status;

    private Integer chunkCount;

    private LocalDateTime createTime;
}
