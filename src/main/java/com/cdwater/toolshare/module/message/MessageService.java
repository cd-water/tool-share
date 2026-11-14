package com.cdwater.toolshare.module.message;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cdwater.toolshare.shared.enums.DeliveryStatus;
import com.cdwater.toolshare.shared.enums.MessageChannel;
import com.cdwater.toolshare.shared.enums.MessageType;
import com.cdwater.toolshare.shared.web.BizException;
import com.cdwater.toolshare.shared.web.PageQuery;
import com.cdwater.toolshare.shared.web.ResultCode;
import com.cdwater.toolshare.module.auth.Admin;
import com.cdwater.toolshare.module.auth.AdminMapper;
import com.cdwater.toolshare.module.auth.User;
import com.cdwater.toolshare.module.auth.UserMapper;
import com.cdwater.toolshare.module.message.Message;
import com.cdwater.toolshare.module.message.MessageMapper;
import com.cdwater.toolshare.module.message.MessageVO;
import com.cdwater.toolshare.shared.web.PageVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    private final MessageMapper messageMapper;
    private final AdminMapper adminMapper;
    private final UserMapper userMapper;
    private final UserNotificationPrefMapper prefMapper;
    private final SmsSender smsSender;
    private final MessageVoMapper messageVoMapper;
    private final MessageConfigService configService;

    /** @return 消息已写入或按偏好/去重规则无需发送；写入失败返回 false，调用方据此决定是否重试。 */
    public boolean notifyUser(Long userId, MessageType type, String title, String content) {
        return insert("USER", userId, type, title, content, null);
    }

    /** @return 同上；bizKey 已存在视为成功（幂等去重）。 */
    public boolean notifyUserOnce(Long userId, MessageType type, String title, String content,
                                  String bizKey) {
        return insert("USER", userId, type, title, content, bizKey);
    }

    /** @return 全部管理端消息都写入成功；任一失败返回 false。 */
    public boolean notifyAdmins(MessageType type, String title, String content, String bizKey) {
        List<Admin> admins = adminMapper.selectList(
                new LambdaQueryWrapper<Admin>().eq(Admin::getStatus, 1));
        boolean success = true;
        for (Admin admin : admins) {
            String key = bizKey == null ? null : bizKey + ":admin:" + admin.getId();
            success &= insert("ADMIN", admin.getId(), type, title, content, key);
        }
        return success;
    }

    private boolean insert(String receiverType, Long receiverId, MessageType messageType,
                           String title, String content, String bizKey) {
        String type = messageType.code();
        String channel = MessageChannel.SYSTEM.code();
        // 居民提醒先读偏好：关闭的类型不发送，渠道按偏好或数据库配置的默认渠道
        if ("USER".equals(receiverType) && messageType != MessageType.SYSTEM) {
            UserNotificationPref pref = prefMapper.selectOne(
                    new LambdaQueryWrapper<UserNotificationPref>()
                            .eq(UserNotificationPref::getUserId, receiverId)
                            .eq(UserNotificationPref::getMessageType, type));
            if (pref != null && pref.getEnabled() != null && pref.getEnabled() == 0) {
                return true;
            }
            channel = MessageChannel.fromCode(pref == null
                            ? configService.defaultChannel() : pref.getChannel())
                    .orElse(MessageChannel.SYSTEM).code();
        }

        // 先按 biz_key 判重，避免 Mock 短信在重复调度时被再次发送
        if (bizKey != null && messageMapper.selectCount(
                new LambdaQueryWrapper<Message>().eq(Message::getBizKey, bizKey)) > 0) {
            return true;
        }

        DeliveryStatus deliveryStatus = DeliveryStatus.DELIVERED;
        LocalDateTime sentTime = LocalDateTime.now();
        if (MessageChannel.SMS.code().equals(channel)) {
            if (!configService.smsEnabled()) {
                // 未启用短信通道时退回站内消息，保证提醒仍可触达
                channel = MessageChannel.SYSTEM.code();
            } else {
                try {
                    User user = userMapper.selectById(receiverId);
                    if (user == null || user.getPhone() == null || user.getPhone().isBlank()) {
                        throw new IllegalStateException("接收人手机号不存在");
                    }
                    smsSender.send(user.getPhone(), title + "：" + content);
                    // Mock 只确认已交给通道，不模拟送达回执
                    deliveryStatus = DeliveryStatus.SENT;
                } catch (Exception e) {
                    log.warn("短信发送失败 receiverId={} type={}: {}", receiverId, type, e.getMessage());
                    deliveryStatus = DeliveryStatus.FAILED;
                    sentTime = null;
                }
            }
        }

        Message message = new Message();
        message.setReceiverType(receiverType);
        message.setReceiverId(receiverId);
        message.setType(type);
        message.setTitle(title);
        message.setContent(content);
        message.setChannel(channel);
        message.setDeliveryStatus(deliveryStatus.code());
        message.setSentTime(sentTime);
        message.setReadStatus(0);
        message.setBizKey(bizKey);
        try {
            messageMapper.insert(message);
            return true;
        } catch (DuplicateKeyException ignored) {
            // 定时提醒按 biz_key 去重；并发下另一方已写入，视为成功
            return true;
        } catch (Exception e) {
            log.error("消息写入失败 receiverType={} receiverId={} type={} bizKey={}",
                    receiverType, receiverId, type, bizKey, e);
            return false;
        }
    }

    public PageVO<MessageVO> page(String receiverType, Long receiverId, long page, long size) {
        Page<Message> result = messageMapper.selectPage(PageQuery.of(page, size),
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getReceiverType, receiverType)
                        .eq(Message::getReceiverId, receiverId)
                        .orderByDesc(Message::getCreateTime));
        return PageVO.of(result, messageVoMapper::toVO);
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

}
