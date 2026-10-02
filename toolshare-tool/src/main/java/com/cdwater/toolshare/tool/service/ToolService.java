package com.cdwater.toolshare.tool.service;

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
import java.time.LocalDate;
import java.util.List;

/**
 * 工具信息 / 分类 / 编码规则 / 状态机
 */
public interface ToolService {

    List<CategoryItem> categories();

    PageResult<ToolItem> page(ToolQuery query);

    ToolDetail detail(Long id);

    /** 某日已占时段（预约冲突展示用） */
    AvailabilityResponse availability(Long id, LocalDate date);

    /** 录入（auditStatus 置 PENDING，编码按规则生成） */
    ToolCreatedResponse create(ToolCreateRequest request);

    void audit(Long id, ToolAuditRequest request);

    List<ToolImageItem> bindImages(Long id, ToolImageRequest request);

    void removeImage(Long imageId);

    /** 手动状态变更（写 tool_status_log，DISCARDED 仅 ADMIN） */
    void changeStatus(Long id, ToolStatusRequest request);
}
