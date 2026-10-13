package com.cdwater.toolshare.tool.service.impl;

import com.cdwater.toolshare.common.result.PageResult;
import com.cdwater.toolshare.tool.dto.ToolAuditDTO;
import com.cdwater.toolshare.tool.dto.ToolDTO;
import com.cdwater.toolshare.tool.dto.ToolQueryDTO;
import com.cdwater.toolshare.tool.dto.ToolStatusDTO;
import com.cdwater.toolshare.tool.entity.Tool;
import com.cdwater.toolshare.tool.entity.ToolStatusLog;
import com.cdwater.toolshare.tool.service.ToolService;
import com.cdwater.toolshare.tool.vo.ToolCreateVO;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 工具信息业务实现（空实现，待填充）
 */
@Service
public class ToolServiceImpl implements ToolService {

    @Override
    public PageResult<Tool> page(ToolQueryDTO query) {
        return null;
    }

    @Override
    public Tool detail(Long id) {
        return null;
    }

    @Override
    public ToolCreateVO create(ToolDTO dto) {
        return null;
    }

    @Override
    public void update(Long id, ToolDTO dto) {
    }

    @Override
    public void updateStatus(Long id, ToolStatusDTO dto) {
    }

    @Override
    public void audit(Long id, ToolAuditDTO dto) {
    }

    @Override
    public List<ToolStatusLog> statusLogs(Long id) {
        return null;
    }
}
