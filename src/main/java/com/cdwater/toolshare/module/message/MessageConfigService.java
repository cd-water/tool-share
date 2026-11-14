package com.cdwater.toolshare.module.message;

import com.cdwater.toolshare.shared.enums.MessageChannel;
import com.cdwater.toolshare.shared.web.BizException;
import com.cdwater.toolshare.shared.web.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理端消息渠道配置：读取数据库单行配置，未配置时回退 application.yml 默认值。
 * 发送逻辑（站内消息/短信）只通过本服务取配置，避免配置入口与实际发送脱节。
 */
@Service
@RequiredArgsConstructor
public class MessageConfigService {

    private static final long SINGLETON_ID = 1L;

    private final MessageConfigMapper configMapper;

    @Value("${toolshare.message.sms-enabled:false}")
    private boolean defaultSmsEnabled;

    @Value("${toolshare.message.default-channel:SYSTEM}")
    private String defaultChannel = "SYSTEM";

    public String defaultChannel() {
        MessageConfig config = configMapper.selectById(SINGLETON_ID);
        if (config == null) {
            return defaultChannel;
        }
        return MessageChannel.fromCode(config.getDefaultChannel())
                .orElse(MessageChannel.SYSTEM).code();
    }

    public boolean smsEnabled() {
        MessageConfig config = configMapper.selectById(SINGLETON_ID);
        return config == null ? defaultSmsEnabled
                : config.getSmsEnabled() != null && config.getSmsEnabled() == 1;
    }

    public MessageConfigVO get() {
        return new MessageConfigVO(defaultChannel(), smsEnabled());
    }

    @Transactional
    public MessageConfigVO update(MessageConfigUpdateDTO dto) {
        String channel = MessageChannel.fromCode(dto.defaultChannel())
                .orElseThrow(() -> new BizException(ResultCode.BAD_REQUEST, "不支持的消息渠道"))
                .code();
        MessageConfig config = configMapper.selectById(SINGLETON_ID);
        if (config == null) {
            config = new MessageConfig();
            config.setId(SINGLETON_ID);
            config.setDefaultChannel(channel);
            config.setSmsEnabled(Boolean.TRUE.equals(dto.smsEnabled()) ? 1 : 0);
            configMapper.insert(config);
        } else {
            config.setDefaultChannel(channel);
            config.setSmsEnabled(Boolean.TRUE.equals(dto.smsEnabled()) ? 1 : 0);
            configMapper.updateById(config);
        }
        return new MessageConfigVO(channel, Boolean.TRUE.equals(dto.smsEnabled()));
    }
}
