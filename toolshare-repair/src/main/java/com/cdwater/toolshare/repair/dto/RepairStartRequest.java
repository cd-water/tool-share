package com.cdwater.toolshare.repair.dto;

import lombok.Data;

/**
 * 开始维修 / 切换维修状态（status 可传 WAITING_PARTS 待配件）
 */
@Data
public class RepairStartRequest {

    private Long handlerId;

    private String estimatedDuration;

    private String status;
}
