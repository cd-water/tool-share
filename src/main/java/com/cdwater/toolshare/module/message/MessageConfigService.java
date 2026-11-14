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
/**
 * <p>配置读取策略：数据库存在单行配置时以其为准，否则回退到
 * application.yml 中的 toolshare.message.* 默认值。这样既支持管理端
 * 在线切换渠道，也保证首次部署未初始化配置时系统仍可正常工作。
 * <p>单行配置的主键固定为 1，更新时存在则改、不存在则插入，避免出现
 * <p>读取入口 defaultChannel()/smsEnabled() 每次都会查一次数据库，保证
 * 管理端修改后立即生效；由于是单行主键查询，开销可控。
 * <p>更新采用 upsert：先按固定主键查询，存在则更新，不存在则插入。
 * <p>线程安全：配置读取不做本地缓存，管理端修改后下一次发送立即生效；
 * <p>更新采用 upsert：存在则改，不存在则插入固定主键记录。
 * 单行主键查询成本很低，不需要额外缓存一致性处理。
 * 渠道编码在写入前校验，历史脏数据在读取时也会回退到站内信。
 * 多行配置导致读取结果不确定。
 */
public class MessageConfigService {

    /** 单行配置的固定主键。 */
    private static final long SINGLETON_ID = 1L;

    /** 消息配置表访问入口。 */
    private final MessageConfigMapper configMapper;

    /** 数据库尚无配置时使用的短信开关默认值。 */
    @Value("${toolshare.message.sms-enabled:false}")
    private boolean defaultSmsEnabled;

    /** 数据库尚无配置时使用的默认渠道，缺省为站内信。 */
    @Value("${toolshare.message.default-channel:SYSTEM}")
    private String defaultChannel = "SYSTEM";

    /**
     * 读取当前默认消息渠道。
     * <p>数据库配置优先；配置缺失或编码非法时回退到 yml 默认值或站内信。
     *
     * @return SYSTEM 或 SMS
     * <p>配置值非法或缺失时不会抛错，而是回退到 yml 默认值。
     */
    public String defaultChannel() {
        MessageConfig config = configMapper.selectById(SINGLETON_ID);
        if (config == null) {
            return defaultChannel;
        }
        // 数据库中的历史脏数据可能不是合法枚举，此时回退站内信。
        return MessageChannel.fromCode(config.getDefaultChannel())
                .orElse(MessageChannel.SYSTEM).code();
    }

    /**
     * 读取短信通道是否启用。
     * <p>数据库记录存在时以 sms_enabled 字段为准，否则使用 yml 默认值。
     *
     * @return true 表示允许发送短信
     * <p>sms_enabled 为 NULL 时按关闭处理。
     */
    public boolean smsEnabled() {
        MessageConfig config = configMapper.selectById(SINGLETON_ID);
        // sms_enabled 为 NULL 时按关闭处理，避免空指针导致发送失败。
        return config == null ? defaultSmsEnabled
                : config.getSmsEnabled() != null && config.getSmsEnabled() == 1;
    }

    /**
     * 查询当前生效的消息配置，供管理端展示。
     *
     * @return 默认渠道与短信开关
     * <p>返回值经过规范化，可直接用于前端表单回显。
     */
    public MessageConfigVO get() {
        // 同时返回默认渠道与短信开关，前端据此渲染配置表单。
        return new MessageConfigVO(defaultChannel(), smsEnabled());
    }

    @Transactional
    /**
     * 更新消息渠道配置（单行 upsert）。
     * <p>更新前先校验渠道编码；数据库中不存在配置时插入主键为 1 的记录，
     * 已存在时更新原记录。整个操作在一个事务内完成。
     *
     * @param dto 新的渠道与短信开关
     * @return 更新后的配置
     * @throws BizException 渠道编码不受支持时抛出
     * <p>更新成功后立即对后续消息发送生效，无需重启。
     */
    public MessageConfigVO update(MessageConfigUpdateDTO dto) {
        // 只允许 SYSTEM/SMS 两种渠道，非法值直接 400。
        String channel = MessageChannel.fromCode(dto.defaultChannel())
                .orElseThrow(() -> new BizException(ResultCode.BAD_REQUEST, "不支持的消息渠道"))
                .code();
        MessageConfig config = configMapper.selectById(SINGLETON_ID);
        if (config == null) {
            config = new MessageConfig();
            config.setId(SINGLETON_ID);
            config.setDefaultChannel(channel);
            config.setSmsEnabled(Boolean.TRUE.equals(dto.smsEnabled()) ? 1 : 0);
            // 首次保存：创建固定主键的单行配置。
            configMapper.insert(config);
        } else {
            config.setDefaultChannel(channel);
            config.setSmsEnabled(Boolean.TRUE.equals(dto.smsEnabled()) ? 1 : 0);
            // 后续保存：覆盖同一行，保持全局唯一配置。
            configMapper.updateById(config);
        }
        // 返回规范化后的渠道编码与布尔值，而非回查数据库。
        return new MessageConfigVO(channel, Boolean.TRUE.equals(dto.smsEnabled()));
    }
}
