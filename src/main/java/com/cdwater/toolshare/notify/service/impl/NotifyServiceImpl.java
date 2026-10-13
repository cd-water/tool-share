package com.cdwater.toolshare.notify.service.impl;

import com.cdwater.toolshare.common.result.PageResult;
import com.cdwater.toolshare.notify.dto.NotifyConfigDTO;
import com.cdwater.toolshare.notify.dto.NotifyQueryDTO;
import com.cdwater.toolshare.notify.entity.Message;
import com.cdwater.toolshare.notify.service.NotifyService;
import org.springframework.stereotype.Service;

/**
 * 消息提醒业务实现（空实现，待填充）
 */
@Service
public class NotifyServiceImpl implements NotifyService {

    @Override
    public PageResult<Message> page(NotifyQueryDTO query) {
        return null;
    }

    @Override
    public void read(Long id) {
    }

    @Override
    public void readAll() {
    }

    @Override
    public NotifyConfigDTO getConfig() {
        return null;
    }

    @Override
    public void updateConfig(NotifyConfigDTO dto) {
    }
}
