package com.cdwater.toolshare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cdwater.toolshare.common.BizException;
import com.cdwater.toolshare.common.Dict;
import com.cdwater.toolshare.common.ResultCode;
import com.cdwater.toolshare.entity.Admin;
import com.cdwater.toolshare.entity.Message;
import com.cdwater.toolshare.mapper.AdminMapper;
import com.cdwater.toolshare.mapper.MessageMapper;
import com.cdwater.toolshare.vo.MessageVO;
import com.cdwater.toolshare.vo.PageVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    private final MessageMapper messageMapper;
    private final AdminMapper adminMapper;

    public void notifyUser(Long userId, String type, String title, String content) {
        insert("USER", userId, type, title, content, null);
    }

    public void notifyUserOnce(Long userId, String type, String title, String content, String bizKey) {
        insert("USER", userId, type, title, content, bizKey);
    }

    public void notifyAdmins(String type, String title, String content, String bizKey) {
        List<Admin> admins = adminMapper.selectList(
                new LambdaQueryWrapper<Admin>().eq(Admin::getStatus, 1));
        for (Admin admin : admins) {
            String key = bizKey == null ? null : bizKey + ":admin:" + admin.getId();
            insert("ADMIN", admin.getId(), type, title, content, key);
        }
    }

    private void insert(String receiverType, Long receiverId, String type,
                        String title, String content, String bizKey) {
        Message message = new Message();
        message.setReceiverType(receiverType);
        message.setReceiverId(receiverId);
        message.setType(type);
        message.setTitle(title);
        message.setContent(content);
        message.setChannel("SYSTEM");
        message.setReadStatus(0);
        message.setBizKey(bizKey);
        try {
            messageMapper.insert(message);
        } catch (DuplicateKeyException ignored) {
            // 定时提醒按 biz_key 去重
        } catch (Exception e) {
            log.warn("消息写入失败: {}", e.getMessage());
        }
    }

    public PageVO<MessageVO> page(String receiverType, Long receiverId, long page, long size) {
        Page<Message> result = messageMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getReceiverType, receiverType)
                        .eq(Message::getReceiverId, receiverId)
                        .orderByDesc(Message::getCreateTime));
        return PageVO.of(result, this::toVO);
    }

    public long unreadCount(String receiverType, Long receiverId) {
        return messageMapper.selectCount(new LambdaQueryWrapper<Message>()
                .eq(Message::getReceiverType, receiverType)
                .eq(Message::getReceiverId, receiverId)
                .eq(Message::getReadStatus, 0));
    }

    public void markRead(String receiverType, Long receiverId, Long id) {
        Message message = messageMapper.selectById(id);
        if (message == null || !receiverType.equals(message.getReceiverType())
                || !receiverId.equals(message.getReceiverId())) {
            throw new BizException(ResultCode.NOT_FOUND, "消息不存在");
        }
        message.setReadStatus(1);
        messageMapper.updateById(message);
    }

    public void markAllRead(String receiverType, Long receiverId) {
        messageMapper.update(null, new LambdaUpdateWrapper<Message>()
                .eq(Message::getReceiverType, receiverType)
                .eq(Message::getReceiverId, receiverId)
                .eq(Message::getReadStatus, 0)
                .set(Message::getReadStatus, 1));
    }

    private MessageVO toVO(Message message) {
        MessageVO vo = new MessageVO();
        vo.setId(message.getId());
        vo.setType(message.getType());
        vo.setTypeName(Dict.label(Dict.MESSAGE_TYPE, message.getType()));
        vo.setTitle(message.getTitle());
        vo.setContent(message.getContent());
        vo.setChannel(message.getChannel());
        vo.setReadStatus(message.getReadStatus() != null && message.getReadStatus() == 1);
        vo.setCreateTime(message.getCreateTime());
        return vo;
    }
}
