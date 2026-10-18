package com.cdwater.toolshare.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.CustomExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 消息提醒（延迟消息）基础设施，与后端 B 的 notify 消费者共用。
 *
 * <p>契约：</p>
 * <ul>
 *   <li>交换机：{@value #NOTIFY_DELAY_EXCHANGE}（x-delayed-message / direct）</li>
 *   <li>队列：{@value #NOTIFY_DELAY_QUEUE}，路由键：{@value #NOTIFY_DELAY_ROUTING_KEY}</li>
 *   <li>消息体：{@link NotifyMessage}（JSON，字段 userId/type/bizNo/content）</li>
 *   <li>延迟：消息头 {@code x-delay}，单位毫秒</li>
 * </ul>
 *
 * <p>变更交换机/队列/消息体字段需与后端 B 同步。</p>
 */
@Configuration
public class RabbitMqConfig {

    /** 提醒延迟交换机 */
    public static final String NOTIFY_DELAY_EXCHANGE = "notify.delay.exchange";

    /** 提醒延迟队列（后端 B 消费） */
    public static final String NOTIFY_DELAY_QUEUE = "notify.delay.queue";

    /** 提醒路由键 */
    public static final String NOTIFY_DELAY_ROUTING_KEY = "notify.delay";

    /** 延迟毫秒数消息头（rabbitmq_delayed_message_exchange 插件） */
    public static final String DELAY_HEADER = "x-delay";

    @Bean
    public CustomExchange notifyDelayExchange() {
        return new CustomExchange(NOTIFY_DELAY_EXCHANGE, "x-delayed-message", true, false,
                Map.of("x-delayed-type", "direct"));
    }

    @Bean
    public Queue notifyDelayQueue() {
        return QueueBuilder.durable(NOTIFY_DELAY_QUEUE).build();
    }

    @Bean
    public Binding notifyDelayBinding() {
        return BindingBuilder.bind(notifyDelayQueue())
                .to(notifyDelayExchange())
                .with(NOTIFY_DELAY_ROUTING_KEY)
                .noargs();
    }

    /**
     * JSON 序列化消息体；Boot 会把它同时应用到 RabbitTemplate 与消费者容器。
     */
    @Bean
    public MessageConverter messageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
