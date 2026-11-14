-- V3 · 消息、渠道配置、提醒偏好与知识库（schema only）

CREATE TABLE `t_message`
(
    `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '消息ID',
    `receiver_type`   VARCHAR(10)   NOT NULL COMMENT 'USER/ADMIN',
    `receiver_id`     BIGINT        NOT NULL COMMENT '接收人ID',
    `type`            VARCHAR(20)   NOT NULL COMMENT '消息类型',
    `title`           VARCHAR(100)  NOT NULL COMMENT '标题',
    `content`         VARCHAR(1000) NOT NULL COMMENT '内容',
    `channel`         VARCHAR(10)   NOT NULL DEFAULT 'SYSTEM' COMMENT 'SYSTEM/SMS',
    `delivery_status` VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENT/DELIVERED/FAILED',
    `sent_time`       DATETIME      DEFAULT NULL COMMENT '送达时间',
    `read_status`     TINYINT       NOT NULL DEFAULT 0 COMMENT '0未读 1已读',
    `biz_key`         VARCHAR(100)  DEFAULT NULL COMMENT '业务去重键',
    `create_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_message_biz_key` (`biz_key`),
    KEY `idx_message_receiver` (`receiver_type`, `receiver_id`, `read_status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '消息表';

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
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '居民提醒偏好';

CREATE TABLE `t_knowledge_doc`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `title`       VARCHAR(200) NOT NULL COMMENT '标题',
    `file_name`   VARCHAR(255) NOT NULL DEFAULT '' COMMENT '文件名',
    `content`     LONGTEXT     NOT NULL COMMENT '解析后的文本',
    `indexed`     TINYINT      NOT NULL DEFAULT 0 COMMENT '是否已建立向量索引（0-否 1-是）',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '知识库文档';
