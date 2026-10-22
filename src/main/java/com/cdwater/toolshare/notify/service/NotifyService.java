package com.cdwater.toolshare.notify.service;

import com.cdwater.toolshare.common.result.PageResult;
import com.cdwater.toolshare.notify.dto.NotifyConfigDTO;
import com.cdwater.toolshare.notify.dto.NotifyQueryDTO;
import com.cdwater.toolshare.notify.entity.Message;
import com.cdwater.toolshare.config.NotifyMessage;

/**
 * 消息提醒业务接口
 */
public interface NotifyService {

    PageResult<Message> page(NotifyQueryDTO query);

    void read(Long id);

    void readAll();

    NotifyConfigDTO getConfig();

    void updateConfig(NotifyConfigDTO dto);

    /**
     * 业务模块发送系统内消息（立即视为已发送）
     */
    void sendInAppMessage(Long userId, Integer type, String title, String content, String bizNo);

    /**
     * MQ 提醒消息存档：渠道取配置值，系统内消息置已发送，短信渠道记待发送
     */
    void saveMqMessage(NotifyMessage message);
}
