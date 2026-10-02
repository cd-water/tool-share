package com.cdwater.toolshare.repair.service.impl;

import com.cdwater.toolshare.common.result.PageResult;
import com.cdwater.toolshare.repair.dto.RepairAcceptRequest;
import com.cdwater.toolshare.repair.dto.RepairCreateRequest;
import com.cdwater.toolshare.repair.dto.RepairCreatedResponse;
import com.cdwater.toolshare.repair.dto.RepairDetail;
import com.cdwater.toolshare.repair.dto.RepairItem;
import com.cdwater.toolshare.repair.dto.RepairProgressRequest;
import com.cdwater.toolshare.repair.dto.RepairStartRequest;
import com.cdwater.toolshare.repair.dto.RepairVerifyRequest;
import com.cdwater.toolshare.repair.service.RepairService;
import org.springframework.stereotype.Service;

@Service
public class RepairServiceImpl implements RepairService {

    @Override
    public RepairCreatedResponse create(RepairCreateRequest request) {
        // TODO 业务实现：生成 SQ-BX-YYYYMMDD-XXXX，status=SUBMITTED
        return null;
    }

    @Override
    public PageResult<RepairItem> my(long page, long size, String status) {
        // TODO 业务实现：本人报修
        return null;
    }

    @Override
    public PageResult<RepairItem> page(long page, long size, String status, String toolCode) {
        // TODO 业务实现
        return null;
    }

    @Override
    public RepairDetail detail(Long id) {
        // TODO 业务实现：含进度时间线与凭证；居民仅可查本人单据
        return null;
    }

    @Override
    public void verify(Long id, RepairVerifyRequest request) {
        // TODO 业务实现：CONFIRMED 联动工具 REPAIRING；发报修确认通知
    }

    @Override
    public void startRepair(Long id, RepairStartRequest request) {
        // TODO 业务实现：REPAIRING / WAITING_PARTS
    }

    @Override
    public void addProgress(Long id, RepairProgressRequest request) {
        // TODO 业务实现：追加 repair_progress
    }

    @Override
    public void accept(Long id, RepairAcceptRequest request) {
        // TODO 业务实现：PASS/PARTIAL 工具重新上架，FAIL 回维修或报废；发维修完成提醒
    }
}
