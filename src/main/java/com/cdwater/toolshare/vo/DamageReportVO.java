package com.cdwater.toolshare.vo;

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
    private Long userId;
    private String reporterName;
    private String reporterPhone;
    private String damagePart;
    private String description;
    private List<String> images;
    private String status;
    private String statusName;
    private String verifier;
    private LocalDateTime verifyTime;
    private String verifyResult;
    private String repairPerson;
    private String repairEstimate;
    private LocalDateTime repairStartTime;
    private BigDecimal repairCost;
    private String progressStatus;
    private String progressStatusName;
    private LocalDateTime progressUpdateTime;
    private String progressNote;
    private String acceptor;
    private LocalDateTime acceptTime;
    private String acceptResult;
    private String acceptResultName;
    private String toolStatusAfter;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
