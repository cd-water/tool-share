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
/**
 * <p>读取策略：以数据库记录为准，未配置的类型使用管理端默认渠道并视为开启，
 * 因此新增消息类型时无需为每个居民批量初始化偏好记录。
 * <p>写入策略：按「用户 + 消息类型」唯一维度 upsert；系统消息（SYSTEM）
 * <p>为什么允许偏好记录缺失：消息类型会随业务迭代新增，如果要求每个
 * 居民预先拥有全部类型的记录，新增类型时就必须做数据迁移。当前实现
 * 在读取时按默认渠道与「开启」补齐，新增类型可以零迁移上线。
 * <p>渠道回退：偏好中的渠道为 SMS 但管理端关闭短信时，实际发送阶段
 * 会退回站内信；偏好服务只负责保存居民选择，不判断总开关。
 * <p>系统消息：SYSTEM 类型不参与偏好设置，也不允许关闭，确保公告、
 * <p>数据一致性：偏好写入使用「先查后插/更新」的 upsert，唯一索引兜底
 * 并发插入；读取时按类型逐个补齐，避免一次查询返回不完整的设置列表。
 * <p>渠道校验：只接受 SYSTEM/SMS，非法渠道在写入前拒绝，避免后续发送
 * 阶段才发现配置错误。
 * 账号安全等重要通知始终可达。
 * 不可关闭，更新时直接返回 400。
 */
public class NotificationPrefService {

    /** 居民提醒偏好表访问入口。 */
    private final UserNotificationPrefMapper prefMapper;
    /** 消息配置服务，为未单独配置的类型提供默认渠道。 */
    private final MessageConfigService configService;

    /**
     * 查询某个居民的全部可配置提醒类型。
     * <p>返回顺序与 {@link MessageType} 枚举顺序一致；系统消息被过滤，
     * 因为它不可关闭。未保存过偏好的类型按默认渠道、开启状态返回。
     *
     * @param userId 居民用户主键
     * <p>列表顺序与 MessageType 枚举顺序一致，便于前端稳定渲染。
     * @return 提醒偏好列表
     * <p>结果覆盖除 SYSTEM 外的全部消息类型，前端可直接渲染设置表单。
     */
    public List<NotificationPrefVO> list(Long userId) {
        // 先一次性读出该用户的全部偏好，再按类型补齐，避免循环查库。
        Map<String, UserNotificationPref> stored = prefMapper.selectList(
                        new LambdaQueryWrapper<UserNotificationPref>()
                                // 一次读出该用户的全部偏好，避免在循环中查库。
                                .eq(UserNotificationPref::getUserId, userId))
                .stream()
                .collect(Collectors.toMap(UserNotificationPref::getMessageType, Function.identity()));

        List<NotificationPrefVO> result = new ArrayList<>();
        // 默认渠道由管理端配置决定，本方法只负责为缺失类型填充。
        String defaultChannel = configService.defaultChannel();
        // 按枚举顺序输出，保证不同用户看到的设置列表顺序一致。
        for (MessageType type : MessageType.values()) {
            if (type == MessageType.SYSTEM) {
                continue;
            }
            // 已保存的偏好优先；没有记录时使用默认渠道且默认开启。
            UserNotificationPref pref = stored.get(type.code());
            // 未配置偏好时使用默认渠道并视为开启；已配置时按记录值返回。
            result.add(toVO(type, pref == null ? defaultChannel : pref.getChannel(),
                    pref == null || pref.getEnabled() == null || pref.getEnabled() != 0));
        }
        return result;
    }

    /**
     * 更新某个消息类型的提醒偏好。
     * <p>系统消息不可关闭；类型与渠道都必须通过枚举白名单校验。
     * 偏好按「用户 + 类型」唯一，不存在时插入，存在时覆盖。
     *
     * @param userId   居民用户主键
     * @param typeCode 消息类型编码
     * @param dto      是否开启与投递渠道
     * <p>更新只影响当前用户与当前类型，不会修改其他类型的偏好。
     * @return 更新后的偏好
     * <p>渠道在关闭状态下也会保存，便于再次开启时恢复上次选择。
     * @throws BizException 类型未知、系统消息或渠道非法时抛出
     */
    public NotificationPrefVO update(Long userId, String typeCode, NotificationPrefUpdateDTO dto) {
        // 类型编码来自路径参数，必须做白名单校验，防止写入无效分类。
        MessageType type = MessageType.fromCode(typeCode)
                .orElseThrow(() -> new BizException(ResultCode.NOT_FOUND, "未知消息类型"));
        if (type == MessageType.SYSTEM) {
            // 即使请求显式指定 SYSTEM，也拒绝修改，避免绕过不可关闭约束。
            throw new BizException(ResultCode.BAD_REQUEST, "系统消息不可关闭");
        }
        // 渠道仅支持 SYSTEM/SMS；非法值直接 400。
        String channel = MessageChannel.fromCode(dto.channel())
                .orElseThrow(() -> new BizException(ResultCode.BAD_REQUEST, "不支持的消息渠道"))
                .code();

        UserNotificationPref pref = prefMapper.selectOne(new LambdaQueryWrapper<UserNotificationPref>()
                .eq(UserNotificationPref::getUserId, userId)
                // 按用户与类型唯一定位现有偏好记录。
                .eq(UserNotificationPref::getMessageType, type.code()));
        // 数据库用 0/1 表示开关，空值按关闭处理。
        int enabled = Boolean.TRUE.equals(dto.enabled()) ? 1 : 0;
        // 首次设置：插入一条新的偏好记录。
        if (pref == null) {
            pref = new UserNotificationPref();
            // 归属来自会话，不接受请求参数。
            pref.setUserId(userId);
            // 类型编码已经过枚举校验。
            pref.setMessageType(type.code());
            pref.setChannel(channel);
            pref.setEnabled(enabled);
            // 唯一索引保证同一用户同一类型只有一条记录。
            prefMapper.insert(pref);
        } else {
            pref.setChannel(channel);
            pref.setEnabled(enabled);
            // 已有记录：覆盖渠道与开关，保留原主键与更新时间。
            prefMapper.updateById(pref);
        }
        // 返回与列表接口一致的视图结构，前端可直接替换本地状态。
        return toVO(type, channel, enabled == 1);
    }

    /**
     * 将偏好字段组装为视图对象，并补齐消息类型中文名。
     *
     * @param type    消息类型
     * @param channel 渠道编码
     * @param enabled 是否开启
     * <p>视图对象同时包含编码与中文名，前端无需再查字典。
     * @return 偏好视图对象
     * <p>消息类型中文名来自枚举 label，避免前端再维护一份映射。
     */
    private NotificationPrefVO toVO(MessageType type, String channel, boolean enabled) {
        NotificationPrefVO vo = new NotificationPrefVO();
        // 返回编码供前端提交更新时使用。
        vo.setType(type.code());
        // 中文名用于设置页展示。
        vo.setTypeName(type.label());
        // 当前生效渠道。
        vo.setChannel(channel);
        // 当前开关状态。
        vo.setEnabled(enabled);
        return vo;
    }
}
