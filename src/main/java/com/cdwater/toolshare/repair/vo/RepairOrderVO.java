package com.cdwater.toolshare.repair.vo;

import com.cdwater.toolshare.repair.entity.RepairProgress;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 报修单出参（附录损坏报修单，含工具/报修人关联信息与维修进度列表）
 */
@Data
public class RepairOrderVO {

    private Long id;

    /** 报修单号 SQ-BX-YYYYMMDD-XXXX */
    private String repairNo;

    private Long rentalOrderId;

    /** 关联预约单号（关联带出） */
    private String orderNo;

    private Long toolId;

    /** 工具编号（关联带出） */
    private String toolCode;

    /** 工具名称/型号（关联带出） */
    private String toolName;

    private Long reporterId;

    /** 报修人姓名（关联带出） */
    private String reporterName;

    /** 报修人联系方式（脱敏） */
    private String reporterPhone;

    /** 损坏部位 */
    private String damagePart;

    /** 损坏描述 */
    private String damageDesc;

    /** 损坏证明材料 URL */
    private List<String> images;

    /** 报修提交时间 */
    private LocalDateTime createTime;

    private Long verifyBy;

    /** 核验人姓名（关联带出） */
    private String verifyByName;

    private LocalDateTime verifyTime;

    /** 核验结果：1确认损坏 2疑似损坏 3误报 */
    private Integer verifyResult;

    /** 维修负责人 */
    private String repairerName;

    /** 维修预计时长 */
    private String expectDuration;

    /** 维修开始时间 */
    private LocalDateTime repairStartTime;

    /** 维修进度：1待配件 2维修中 3维修完成 4维修失败（报废） */
    private Integer repairStatus;

    /** 维修费用（元） */
    private BigDecimal repairFee;

    private Long acceptBy;

    /** 验收人姓名（关联带出） */
    private String acceptByName;

    private LocalDateTime acceptTime;

    /** 验收结果：1合格 2不合格 3部分合格 */
    private Integer acceptResult;

    /** 备注 */
    private String remark;

    /** 维修进度列表（按时间正序） */
    private List<RepairProgress> progressList;
}
