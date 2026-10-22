package com.cdwater.toolshare.notify.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cdwater.toolshare.common.enums.ResultCode;
import com.cdwater.toolshare.common.exception.BizException;
import com.cdwater.toolshare.common.result.PageResult;
import com.cdwater.toolshare.config.NotifyMessage;
import com.cdwater.toolshare.notify.dto.NotifyConfigDTO;
import com.cdwater.toolshare.notify.dto.NotifyQueryDTO;
import com.cdwater.toolshare.notify.entity.Message;
import com.cdwater.toolshare.notify.entity.NotifyConfig;
import com.cdwater.toolshare.notify.mapper.MessageMapper;
import com.cdwater.toolshare.notify.mapper.NotifyConfigMapper;
import com.cdwater.toolshare.notify.service.NotifyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 消息提醒业务实现
 */
@Service
@RequiredArgsConstructor
public class NotifyServiceImpl implements NotifyService {

    private static final long CONFIG_ID = 1L;
    private static final int CHANNEL_IN_APP = 1;
    private static final int SEND_STATUS_PENDING = 1;
    private static final int SEND_STATUS_SENT = 2;
    private static final int READ_STATUS_UNREAD = 0;

    private final MessageMapper messageMapper;
    private final NotifyConfigMapper notifyConfigMapper;

    @Override
    public PageResult<Message> page(NotifyQueryDTO query) {
        Long userId = StpUtil.getLoginIdAsLong();
        Page<Message> page = messageMapper.selectPage(query.toPage(),
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getUserId, userId)
                        .eq(query.getType() != null, Message::getType, query.getType())
                        .eq(query.getReadStatus() != null, Message::getReadStatus, query.getReadStatus())
                        .orderByDesc(Message::getCreateTime)
                        .orderByDesc(Message::getId));
        return PageResult.of(page);
    }

    @Override
    public void read(Long id) {
        Message message = messageMapper.selectById(id);
        if (message == null || !Objects.equals(message.getUserId(), StpUtil.getLoginIdAsLong())) {
            throw new BizException(ResultCode.NOT_FOUND.getCode(), "消息不存在");
        }
        if (!Objects.equals(message.getReadStatus(), 1)) {
            messageMapper.update(null, new LambdaUpdateWrapper<Message>()
                    .eq(Message::getId, id)
                    .set(Message::getReadStatus, 1));
        }
    }

    @Override
    public void readAll() {
        messageMapper.update(null, new LambdaUpdateWrapper<Message>()
                .eq(Message::getUserId, StpUtil.getLoginIdAsLong())
                .eq(Message::getReadStatus, READ_STATUS_UNREAD)
                .set(Message::getReadStatus, 1));
    }

    @Override
    public NotifyConfigDTO getConfig() {
        NotifyConfig config = notifyConfigMapper.selectById(CONFIG_ID);
        NotifyConfigDTO dto = new NotifyConfigDTO();
        dto.setChannel(config == null ? CHANNEL_IN_APP : config.getChannel());
        return dto;
    }

    @Override
    public void updateConfig(NotifyConfigDTO dto) {
        NotifyConfig config = notifyConfigMapper.selectById(CONFIG_ID);
        if (config == null) {
            config = new NotifyConfig();
            config.setId(CONFIG_ID);
            config.setChannel(dto.getChannel());
            notifyConfigMapper.insert(config);
            return;
        }
        config.setChannel(dto.getChannel());
        notifyConfigMapper.updateById(config);
    }

    @Override
    public void sendInAppMessage(Long userId, Integer type, String title, String content, String bizNo) {
        Message message = new Message();
        message.setUserId(userId);
        message.setType(type);
        message.setTitle(title);
        message.setContent(content);
        message.setChannel(CHANNEL_IN_APP);
        message.setSendStatus(SEND_STATUS_SENT);
        message.setSendTime(LocalDateTime.now());
        message.setBizNo(bizNo);
        message.setReadStatus(READ_STATUS_UNREAD);
        messageMapper.insert(message);
    }

    @Override
    public void saveMqMessage(NotifyMessage message) {
        int channel = getConfig().getChannel();
        Message entity = new Message();
        entity.setUserId(message.getUserId());
        entity.setType(message.getType());
        entity.setTitle(titleOf(message.getType()));
        entity.setContent(message.getContent());
        entity.setChannel(channel);
        entity.setBizNo(message.getBizNo());
        entity.setReadStatus(READ_STATUS_UNREAD);
        if (channel == CHANNEL_IN_APP) {
            entity.setSendStatus(SEND_STATUS_SENT);
            entity.setSendTime(LocalDateTime.now());
        } else {
            // 无短信网关：短信渠道仅存档，等待发送
            entity.setSendStatus(SEND_STATUS_PENDING);
        }
        messageMapper.insert(entity);
    }

    private String titleOf(Integer type) {
        return switch (type) {
            case 1 -> "取件提醒";
            case 2 -> "租赁到期提醒";
            case 3 -> "逾期提醒";
            default -> "消息提醒";
        };
    }
}
