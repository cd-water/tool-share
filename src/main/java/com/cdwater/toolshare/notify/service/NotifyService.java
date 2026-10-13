package com.cdwater.toolshare.notify.service;

import com.cdwater.toolshare.common.result.PageResult;
import com.cdwater.toolshare.notify.dto.NotifyConfigDTO;
import com.cdwater.toolshare.notify.dto.NotifyQueryDTO;
import com.cdwater.toolshare.notify.entity.Message;

/**
 * 消息提醒业务接口
 */
public interface NotifyService {

    PageResult<Message> page(NotifyQueryDTO query);

    void read(Long id);

    void readAll();

    NotifyConfigDTO getConfig();

    void updateConfig(NotifyConfigDTO dto);
}
