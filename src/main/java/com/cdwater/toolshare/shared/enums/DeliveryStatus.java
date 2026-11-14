package com.cdwater.toolshare.shared.enums;

import java.util.Optional;

/**
 * 消息投递状态字典。
 *
 * <p>站内消息写入后通常立即视为已送达；Mock 短信等外部通道需要先进入
 * 待发送队列。投递失败的消息保留失败状态，便于后台重试与排查，不直接
 * 删除业务消息本身。</p>
 */
public enum DeliveryStatus implements CodeLabel {

    /**
     * 待发送：消息已落库但尚未交给外部通道。
     */
    PENDING("PENDING", "待发送"),
    /**
     * 已发送：请求已提交给通道，尚未收到最终送达回执。
     */
    SENT("SENT", "已发送"),
    /**
     * 已送达：通道确认用户可读取，流程正常结束。
     */
    DELIVERED("DELIVERED", "已送达"),
    /**
     * 发送失败：通道返回异常，保留记录供人工或定时任务重试。
     */
    FAILED("FAILED", "发送失败");

    /** 消息表中持久化的英文状态码。 */
    private final String code;

    /** 管理端消息记录展示的中文状态。 */
    private final String label;

    /**
     * 构造投递状态。
     *
     * @param code  英文状态码
     * @param label 中文显示名
     */
    DeliveryStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /**
     * 返回英文状态码。
     *
     * @return 持久化代码
     */
    @Override
    public String code() {
        return code;
    }

    /**
     * 返回中文状态名。
     *
     * @return 展示名称
     */
    @Override
    public String label() {
        return label;
    }

    /**
     * 按状态码反查枚举。
     *
     * @param code 英文状态码
     * @return 匹配项；代码为空或未知时返回 {@link Optional#empty()}
     */
    public static Optional<DeliveryStatus> fromCode(String code) {
        return CodeLabel.find(DeliveryStatus.class, code);
    }
}
