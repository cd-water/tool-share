package com.cdwater.toolshare.notify.mq;

import com.cdwater.toolshare.notify.service.NotifyService;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 消费后端 A 投递的取件/到期/逾期延迟消息，落库存档。
 * 解析失败只记日志不抛出，避免消息被反复重投形成死循环。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyMessageConsumer {

    private final ObjectMapper objectMapper;
    private final NotifyService notifyService;

    @RabbitListener(queuesToDeclare = {
            @Queue(name = NotifyMqConstants.PICKUP_QUEUE, durable = "true"),
            @Queue(name = NotifyMqConstants.DUE_QUEUE, durable = "true"),
            @Queue(name = NotifyMqConstants.OVERDUE_QUEUE, durable = "true")
    })
    public void onNotifyMessage(org.springframework.amqp.core.Message message) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);

        NotifyMqMessage payload;
        try {
            payload = objectMapper.readValue(body, NotifyMqMessage.class);
        } catch (Exception e) {
            log.error("提醒消息体解析失败，已丢弃: {}", body, e);
            return;
        }

        if (payload.getUserId() == null || payload.getType() == null) {
            log.warn("提醒消息缺少 userId/type，已丢弃: {}", body);
            return;
        }

        try {
            notifyService.saveMqMessage(payload);
        } catch (Exception e) {
            log.error("提醒消息落库失败: {}", body, e);
        }
    }
}
