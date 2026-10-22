package com.cdwater.toolshare.tool.service;

import com.cdwater.toolshare.common.result.PageResult;
import com.cdwater.toolshare.tool.dto.ToolAuditDTO;
import com.cdwater.toolshare.tool.dto.ToolDTO;
import com.cdwater.toolshare.tool.dto.ToolQueryDTO;
import com.cdwater.toolshare.tool.dto.ToolStatusDTO;
import com.cdwater.toolshare.tool.entity.Tool;
import com.cdwater.toolshare.tool.entity.ToolStatusLog;
import com.cdwater.toolshare.tool.vo.ToolCreateVO;

import java.util.List;

/**
 * 工具信息业务接口
 */
public interface ToolService {

    PageResult<Tool> page(ToolQueryDTO query);

    Tool detail(Long id);

    ToolCreateVO create(ToolDTO dto);

    void update(Long id, ToolDTO dto);

    void updateStatus(Long id, ToolStatusDTO dto);

    /**
     * 归还验收后重估损坏程度（供 rental 模块联动调用，不改变状态、不写状态日志）
     */
    void updateDamageLevel(Long id, Integer damageLevel);

    void audit(Long id, ToolAuditDTO dto);

    List<ToolStatusLog> statusLogs(Long id);
}
