package com.cdwater.toolshare.tool.service.impl;

import com.cdwater.toolshare.common.result.PageResult;
import com.cdwater.toolshare.tool.dto.AvailabilityResponse;
import com.cdwater.toolshare.tool.dto.CategoryItem;
import com.cdwater.toolshare.tool.dto.ToolAuditRequest;
import com.cdwater.toolshare.tool.dto.ToolCreateRequest;
import com.cdwater.toolshare.tool.dto.ToolCreatedResponse;
import com.cdwater.toolshare.tool.dto.ToolDetail;
import com.cdwater.toolshare.tool.dto.ToolImageItem;
import com.cdwater.toolshare.tool.dto.ToolImageRequest;
import com.cdwater.toolshare.tool.dto.ToolItem;
import com.cdwater.toolshare.tool.dto.ToolQuery;
import com.cdwater.toolshare.tool.dto.ToolStatusRequest;
import com.cdwater.toolshare.tool.service.ToolService;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ToolServiceImpl implements ToolService {

    @Override
    public List<CategoryItem> categories() {
        // TODO 业务实现
        return null;
    }

    @Override
    public PageResult<ToolItem> page(ToolQuery query) {
        // TODO 业务实现：居民仅见 APPROVED 且非 DISCARDED/REPAIRING
        return null;
    }

    @Override
    public ToolDetail detail(Long id) {
        // TODO 业务实现：purchaseDate/supplier 仅 STAFF/ADMIN 返回
        return null;
    }

    @Override
    public AvailabilityResponse availability(Long id, LocalDate date) {
        // TODO 业务实现：查重叠时段
        return null;
    }

    @Override
    public ToolCreatedResponse create(ToolCreateRequest request) {
        // TODO 业务实现：Redisson 锁内生成 SQ-G-{category.code}-{序号}
        return null;
    }

    @Override
    public void audit(Long id, ToolAuditRequest request) {
        // TODO 业务实现：REJECTED 时 auditRemark 必填
    }

    @Override
    public List<ToolImageItem> bindImages(Long id, ToolImageRequest request) {
        // TODO 业务实现
        return null;
    }

    @Override
    public void removeImage(Long imageId) {
        // TODO 业务实现
    }

    @Override
    public void changeStatus(Long id, ToolStatusRequest request) {
        // TODO 业务实现：DISCARDED 仅 ADMIN；写 tool_status_log
    }
}
