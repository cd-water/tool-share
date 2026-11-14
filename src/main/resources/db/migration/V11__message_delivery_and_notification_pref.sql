-- V11 · 消息送达状态与用户提醒偏好

ALTER TABLE `t_message`
    ADD COLUMN `delivery_status` VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING/SENT/DELIVERED/FAILED' AFTER `channel`,
    ADD COLUMN `sent_time` DATETIME NULL COMMENT '送达时间' AFTER `delivery_status`;

-- 历史站内消息视为已送达
UPDATE `t_message`
SET `delivery_status` = 'DELIVERED', `sent_time` = `create_time`
WHERE `channel` = 'SYSTEM';

CREATE TABLE `t_user_notification_pref`
(
    `id`           BIGINT      NOT NULL AUTO_INCREMENT,
    `user_id`      BIGINT      NOT NULL COMMENT '居民ID',
    `message_type` VARCHAR(20) NOT NULL COMMENT '提醒类型',
    `channel`      VARCHAR(10) NOT NULL DEFAULT 'SYSTEM' COMMENT 'SYSTEM/SMS',
    `enabled`      TINYINT     NOT NULL DEFAULT 1 COMMENT '0关闭 1开启',
    `update_time`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_notification_pref` (`user_id`, `message_type`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '居民提醒偏好';
