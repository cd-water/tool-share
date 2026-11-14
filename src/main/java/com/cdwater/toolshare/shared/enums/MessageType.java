package com.cdwater.toolshare.shared.enums;

import java.util.Optional;

/**
 * 消息业务类型字典。
 *
 * <p>类型决定消息标题模板、跳转目标与用户端的分类展示。新增类型时需同步
 * 维护消息发送方的文案与前端图标映射，避免出现只有代码没有展示文案的
 * 情况。</p>
 */
public enum MessageType implements CodeLabel {

    /**
     * 预约通知：用户成功预约工具后发送。
     */
    RESERVATION("RESERVATION", "预约通知"),
    /**
     * 取件提醒：到达约定取件时间，提醒用户及时领取工具。
     */
    PICKUP("PICKUP", "取件提醒"),
    /**
     * 到期提醒：租赁即将到期，提醒用户按时归还。
     */
    RENT_DUE("RENT_DUE", "到期提醒"),
    /**
     * 逾期提醒：租赁已超过归还时间，提示产生逾期费用。
     */
    OVERDUE("OVERDUE", "逾期提醒"),
    /**
     * 报修通知：报修单状态变化时通知相关用户或管理员。
     */
    REPAIR("REPAIR", "报修通知"),
    /**
     * 蹲守到货提醒：用户关注的工具重新变为可借用时发送。
     */
    TOOL_AVAILABLE("TOOL_AVAILABLE", "蹲守到货提醒"),
    /**
     * 公告：管理员面向用户发布的通用通知。
     */
    ANNOUNCEMENT("ANNOUNCEMENT", "公告"),
    /**
     * 系统消息：兜底类型，用于无法归入具体业务的提示。
     */
    SYSTEM("SYSTEM", "系统消息");

    /** 消息表中持久化的类型代码。 */
    private final String code;

    /** 用户端消息中心展示的中文类型名。 */
    private final String label;

    /**
     * 构造消息类型。
     *
     * @param code  英文类型代码
     * @param label 中文显示名
     */
    MessageType(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /**
     * 返回英文类型代码。
     *
     * @return 持久化代码
     */
    @Override
    public String code() {
        return code;
    }

    /**
     * 返回中文类型名。
     *
     * @return 展示名称
     */
    @Override
    public String label() {
        return label;
    }

    /**
     * 按代码反查消息类型。
     *
     * @param code 英文类型代码
     * @return 匹配项；代码为空或未知时返回 {@link Optional#empty()}
     */
    public static Optional<MessageType> fromCode(String code) {
        return CodeLabel.find(MessageType.class, code);
    }
}
