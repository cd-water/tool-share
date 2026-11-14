package com.cdwater.toolshare.module.damage;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class DamageReportVO {

    private Long id;
    private String reportNo;
    private Long rentalId;
    private Long toolId;
    private String toolCode;
    private String toolName;
    /** 工具主图（MinIO 路径） */
    private String toolImageUrl;
    private Long userId;
    private String reporterName;
    private String reporterPhone;
    private String damagePart;
    private String description;
    private List<String> images;
    @Schema(allowableValues = {
            "SUBMITTED", "VERIFIED", "REJECTED", "REPAIRING", "COMPLETED", "SCRAPPED"})
    private String status;
    private String statusName;
    private String verifier;
    private LocalDateTime verifyTime;
    @Schema(allowableValues = {"CONFIRMED", "SUSPECTED", "FALSE"})
    private String verifyResult;
    private String repairPerson;
    private String repairEstimate;
    private LocalDateTime repairStartTime;
    private BigDecimal repairCost;
    @Schema(allowableValues = {"WAITING_PARTS", "REPAIRING", "COMPLETED", "FAILED"})
    private String progressStatus;
    private String progressStatusName;
    private LocalDateTime progressUpdateTime;
    private String progressNote;
    private String acceptor;
    private LocalDateTime acceptTime;
    @Schema(allowableValues = {"QUALIFIED", "UNQUALIFIED", "PARTIAL"})
    private String acceptResult;
    private String acceptResultName;
    @Schema(allowableValues = {"AVAILABLE", "MAINTENANCE", "SCRAPPED"})
    private String toolStatusAfter;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
