package com.cdwater.toolshare.notify.mq;

import com.cdwater.toolshare.config.NotifyMessage;
import com.cdwater.toolshare.config.RabbitMqConfig;
import com.cdwater.toolshare.notify.service.NotifyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 消费租赁模块投递的取件/到期/逾期延迟消息，落库存档。
 * 队列与交换机声明见 {@code config.RabbitMqConfig}，JSON 反序列化走共享 MessageConverter。
 * 反序列化失败由 ConditionalRejectingErrorHandler 拒绝丢弃（不重投死循环），落库失败只记日志。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyMessageConsumer {

    private final NotifyService notifyService;

    @RabbitListener(queues = RabbitMqConfig.NOTIFY_DELAY_QUEUE)
    public void onNotifyMessage(NotifyMessage payload) {
        if (payload.getUserId() == null || payload.getType() == null) {
            log.warn("提醒消息缺少 userId/type，已丢弃: {}", payload);
            return;
        }

        try {
            notifyService.saveMqMessage(payload);
        } catch (Exception e) {
            log.error("提醒消息落库失败: {}", payload, e);
        }
    }
}
