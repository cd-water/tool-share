package com.cdwater.toolshare.notify.mq;

/**
 * 提醒延迟消息契约（与后端 A 的 config/RabbitMqConfig 对齐）。
 * A 侧声明交换机并把 routingKey 绑定到下列队列；B 侧消费者监听队列。
 */
public final class NotifyMqConstants {

    /** 延迟消息交换机（A 侧声明） */
    public static final String EXCHANGE = "toolshare.notify.exchange";

    public static final String PICKUP_QUEUE = "toolshare.notify.pickup.queue";
    public static final String DUE_QUEUE = "toolshare.notify.due.queue";
    public static final String OVERDUE_QUEUE = "toolshare.notify.overdue.queue";

    public static final String PICKUP_ROUTING_KEY = "notify.pickup";
    public static final String DUE_ROUTING_KEY = "notify.due";
    public static final String OVERDUE_ROUTING_KEY = "notify.overdue";

    private NotifyMqConstants() {
    }
}
