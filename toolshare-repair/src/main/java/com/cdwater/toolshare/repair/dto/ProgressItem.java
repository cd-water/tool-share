package com.cdwater.toolshare.repair.dto;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class ProgressItem {

    private Long id;

    private String content;

    private String operatorName;

    private LocalDateTime createTime;
}
