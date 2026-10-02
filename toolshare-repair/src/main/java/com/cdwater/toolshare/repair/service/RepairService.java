package com.cdwater.toolshare.repair.service;

import com.cdwater.toolshare.common.result.PageResult;
import com.cdwater.toolshare.repair.dto.RepairAcceptRequest;
import com.cdwater.toolshare.repair.dto.RepairCreateRequest;
import com.cdwater.toolshare.repair.dto.RepairCreatedResponse;
import com.cdwater.toolshare.repair.dto.RepairDetail;
import com.cdwater.toolshare.repair.dto.RepairItem;
import com.cdwater.toolshare.repair.dto.RepairProgressRequest;
import com.cdwater.toolshare.repair.dto.RepairStartRequest;
import com.cdwater.toolshare.repair.dto.RepairVerifyRequest;

/**
 * 报修 → 核验 → 维修 → 验收 全链
 */
public interface RepairService {

    RepairCreatedResponse create(RepairCreateRequest request);

    PageResult<RepairItem> my(long page, long size, String status);

    PageResult<RepairItem> page(long page, long size, String status, String toolCode);

    RepairDetail detail(Long id);

    void verify(Long id, RepairVerifyRequest request);

    void startRepair(Long id, RepairStartRequest request);

    void addProgress(Long id, RepairProgressRequest request);

    void accept(Long id, RepairAcceptRequest request);
}
