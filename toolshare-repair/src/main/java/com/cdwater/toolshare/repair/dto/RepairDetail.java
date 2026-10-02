package com.cdwater.toolshare.repair.dto;

import java.math.BigDecimal;
import java.util.List;
import lombok.Data;

@Data
public class RepairDetail {

    private Long id;

    private String repairCode;

    private Long toolId;

    private String toolName;

    private Long orderId;

    private String orderCode;

    private Long reporterId;

    private String damagePart;

    private String description;

    private String status;

    private String handlerName;

    private String estimatedDuration;

    private BigDecimal repairFee;

    private String repairDescription;

    private String acceptResult;

    private String rejectReason;

    private List<String> images;

    private List<ProgressItem> progress;
}
