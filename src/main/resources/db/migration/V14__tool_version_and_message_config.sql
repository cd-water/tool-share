-- V14 · 工具乐观锁版本号与管理端消息渠道配置
ALTER TABLE `t_tool`
    ADD COLUMN `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号' AFTER `status`;

-- 单行配置表：id 固定为 1。发送逻辑优先读取本表，未配置时回退 application.yml。
CREATE TABLE `t_message_config`
(
    `id`              BIGINT      NOT NULL COMMENT '固定为 1',
    `default_channel` VARCHAR(10) NOT NULL DEFAULT 'SYSTEM' COMMENT '默认消息渠道 SYSTEM/SMS',
    `sms_enabled`     TINYINT     NOT NULL DEFAULT 0 COMMENT 'Mock 短信开关 0关闭 1开启',
    `update_time`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '消息渠道配置';
