package com.cdwater.toolshare.shared.enums;

import java.util.Optional;

/**
 * 消息投递通道字典。
 *
 * <p>同一业务事件可能通过多个通道触达用户。当前项目使用站内消息作为
 * 主通道，短信为演示用 Mock 实现，因此短信通道不会真正产生运营商费用。</p>
 */
public enum MessageChannel implements CodeLabel {

    /**
     * 站内消息：写入消息表，用户登录后在消息中心查看。
     */
    SYSTEM("SYSTEM", "站内消息"),
    /**
     * Mock 短信：仅记录发送动作与结果，不接真实短信服务商。
     */
    SMS("SMS", "Mock 短信");

    /** 消息表中持久化的通道代码。 */
    private final String code;

    /** 后台展示的中文通道名。 */
    private final String label;

    /**
     * 构造消息通道。
     *
     * @param code  英文通道代码
     * @param label 中文显示名
     */
    MessageChannel(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /**
     * 返回英文通道代码。
     *
     * @return 持久化代码
     */
    @Override
    public String code() {
        return code;
    }

    /**
     * 返回中文通道名。
     *
     * @return 展示名称
     */
    @Override
    public String label() {
        return label;
    }

    /**
     * 按代码反查消息通道。
     *
     * @param code 英文通道代码
     * @return 匹配项；代码为空或未知时返回 {@link Optional#empty()}
     */
    public static Optional<MessageChannel> fromCode(String code) {
        return CodeLabel.find(MessageChannel.class, code);
    }
}
