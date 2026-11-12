-- V3 · AI 工具推荐记录
CREATE TABLE `t_ai_recommendation`
(
    `id`              BIGINT         NOT NULL AUTO_INCREMENT COMMENT '推荐记录ID',
    `user_id`         BIGINT         NOT NULL COMMENT '推荐用户',
    `question`        VARCHAR(500)   NOT NULL COMMENT '用户需求描述',
    `start_time`      DATETIME       NULL COMMENT '期望开始时间（可空）',
    `end_time`        DATETIME       NULL COMMENT '期望结束时间（可空）',
    `category`        VARCHAR(16)    NULL COMMENT '类别筛选（可空）',
    `max_deposit`     DECIMAL(10, 2) NULL COMMENT '押金上限（可空）',
    `recommended_ids` VARCHAR(500)   NOT NULL COMMENT '推荐工具ID，逗号分隔，按推荐顺序',
    `clicked_tool_id` BIGINT         NULL COMMENT '用户点击的工具ID',
    `feedback`        TINYINT        NOT NULL DEFAULT 0 COMMENT '0未反馈 1有帮助 2没帮助',
    `degraded`        TINYINT        NOT NULL DEFAULT 0 COMMENT '是否降级：0否 1是',
    `create_time`     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '推荐时间',
    PRIMARY KEY (`id`),
    KEY `idx_ai_rec_user_time` (`user_id`, `create_time`),
    KEY `idx_ai_rec_create` (`create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI 工具推荐记录';
