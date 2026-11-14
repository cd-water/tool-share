package com.cdwater.toolshare.module.message;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.shared.enums.MessageChannel;
import com.cdwater.toolshare.shared.enums.MessageType;
import com.cdwater.toolshare.shared.web.BizException;
import com.cdwater.toolshare.shared.web.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 居民提醒偏好：按消息类型保存是否接收与投递渠道。
 * <p>SYSTEM 类型不可关闭，也不会出现在偏好列表中。
 */
@Service
@RequiredArgsConstructor
public class NotificationPrefService {

    private final UserNotificationPrefMapper prefMapper;
    private final MessageConfigService configService;

    public List<NotificationPrefVO> list(Long userId) {
        Map<String, UserNotificationPref> stored = prefMapper.selectList(
                        new LambdaQueryWrapper<UserNotificationPref>()
                                .eq(UserNotificationPref::getUserId, userId))
                .stream()
                .collect(Collectors.toMap(UserNotificationPref::getMessageType, Function.identity()));

        List<NotificationPrefVO> result = new ArrayList<>();
        String defaultChannel = configService.defaultChannel();
        for (MessageType type : MessageType.values()) {
            if (type == MessageType.SYSTEM) {
                continue;
            }
            UserNotificationPref pref = stored.get(type.code());
            result.add(toVO(type, pref == null ? defaultChannel : pref.getChannel(),
                    pref == null || pref.getEnabled() == null || pref.getEnabled() != 0));
        }
        return result;
    }

    public NotificationPrefVO update(Long userId, String typeCode, NotificationPrefUpdateDTO dto) {
        MessageType type = MessageType.fromCode(typeCode)
                .orElseThrow(() -> new BizException(ResultCode.NOT_FOUND, "未知消息类型"));
        if (type == MessageType.SYSTEM) {
            throw new BizException(ResultCode.BAD_REQUEST, "系统消息不可关闭");
        }
        String channel = MessageChannel.fromCode(dto.channel())
                .orElseThrow(() -> new BizException(ResultCode.BAD_REQUEST, "不支持的消息渠道"))
                .code();

        UserNotificationPref pref = prefMapper.selectOne(new LambdaQueryWrapper<UserNotificationPref>()
                .eq(UserNotificationPref::getUserId, userId)
                .eq(UserNotificationPref::getMessageType, type.code()));
        int enabled = Boolean.TRUE.equals(dto.enabled()) ? 1 : 0;
        if (pref == null) {
            pref = new UserNotificationPref();
            pref.setUserId(userId);
            pref.setMessageType(type.code());
            pref.setChannel(channel);
            pref.setEnabled(enabled);
            prefMapper.insert(pref);
        } else {
            pref.setChannel(channel);
            pref.setEnabled(enabled);
            prefMapper.updateById(pref);
        }
        return toVO(type, channel, enabled == 1);
    }

    private NotificationPrefVO toVO(MessageType type, String channel, boolean enabled) {
        NotificationPrefVO vo = new NotificationPrefVO();
        vo.setType(type.code());
        vo.setTypeName(type.label());
        vo.setChannel(channel);
        vo.setEnabled(enabled);
        return vo;
    }
}
