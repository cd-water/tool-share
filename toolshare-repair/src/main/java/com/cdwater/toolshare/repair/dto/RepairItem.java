package com.cdwater.toolshare.repair.dto;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class RepairItem {

    private Long id;

    private String repairCode;

    private Long toolId;

    private String toolName;

    private String reporterName;

    private String damagePart;

    private String description;

    private String status;

    private LocalDateTime createTime;
}
