package com.cdwater.toolshare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_damage_report")
public class DamageReport {

    @TableId(type = IdType.AUTO)
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

    /** 逗号分隔 */
    private String images;

    /** SUBMITTED/VERIFIED/REJECTED/REPAIRING/COMPLETED/SCRAPPED */
    private String status;

    private String verifier;

    private LocalDateTime verifyTime;

    private String verifyResult;

    private String repairPerson;

    private String repairEstimate;

    private LocalDateTime repairStartTime;

    private BigDecimal repairCost;

    private String progressStatus;

    private LocalDateTime progressUpdateTime;

    private String progressNote;

    private String acceptor;

    private LocalDateTime acceptTime;

    private String acceptResult;

    private String toolStatusAfter;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
