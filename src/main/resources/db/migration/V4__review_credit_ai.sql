-- V4 · 评价、信用分流水与 AI 推荐记录（schema only）

CREATE TABLE `t_tool_review`
(
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '评价ID',
    `rental_id`     BIGINT       NOT NULL COMMENT '租赁单ID（一单一评）',
    `tool_id`       BIGINT       NOT NULL COMMENT '工具ID快照',
    `tool_name`     VARCHAR(100) NOT NULL COMMENT '工具名称快照',
    `user_id`       BIGINT       NOT NULL COMMENT '评价用户ID快照',
    `user_nickname` VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '评价人昵称快照',
    `rating`        TINYINT      NOT NULL COMMENT '评分 1-5',
    `content`       VARCHAR(500) NOT NULL DEFAULT '' COMMENT '文字评价（纯文本）',
    `images`        TEXT         COMMENT '图片URL，逗号分隔，最多 3 张',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '评价时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_review_rental` (`rental_id`),
    KEY `idx_review_tool_time` (`tool_id`, `create_time`),
    KEY `idx_review_user_time` (`user_id`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '工具评价';

CREATE TABLE `t_user_credit_event`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '流水ID',
    `user_id`     BIGINT       NOT NULL COMMENT '用户ID',
    `event_type`  VARCHAR(32)  NOT NULL COMMENT '事件类型 ON_TIME_RETURN/OVERDUE/SEVERE_DAMAGE/LOW_REVIEW',
    `delta`       INT          NOT NULL COMMENT '实际分数变化（已按 0-100 裁剪）',
    `rental_id`   BIGINT       DEFAULT NULL COMMENT '关联租赁单ID',
    `review_id`   BIGINT       DEFAULT NULL COMMENT '关联评价ID',
    `reason`      VARCHAR(255) NOT NULL DEFAULT '' COMMENT '变更原因',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发生时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_credit_event_rental` (`rental_id`, `event_type`),
    UNIQUE KEY `uk_credit_event_review` (`review_id`, `event_type`),
    KEY `idx_credit_event_user_time` (`user_id`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户信用分流水';

CREATE TABLE `t_ai_recommendation`
(
    `id`              BIGINT         NOT NULL AUTO_INCREMENT COMMENT '推荐记录ID',
    `user_id`         BIGINT         NOT NULL COMMENT '推荐用户',
    `question`        VARCHAR(500)   NOT NULL COMMENT '用户需求描述',
    `start_time`      DATETIME       DEFAULT NULL COMMENT '期望开始时间（可空）',
    `end_time`        DATETIME       DEFAULT NULL COMMENT '期望结束时间（可空）',
    `category`        VARCHAR(16)    DEFAULT NULL COMMENT '类别筛选（可空）',
    `max_deposit`     DECIMAL(10, 2) DEFAULT NULL COMMENT '押金上限（可空）',
    `recommended_ids` VARCHAR(500)   NOT NULL COMMENT '推荐工具ID，逗号分隔，按推荐顺序',
    `clicked_tool_id` BIGINT         DEFAULT NULL COMMENT '用户点击的工具ID',
    `feedback`        TINYINT        NOT NULL DEFAULT 0 COMMENT '0未反馈 1有帮助 2没帮助',
    `degraded`        TINYINT        NOT NULL DEFAULT 0 COMMENT '是否降级：0否 1是',
    `create_time`     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '推荐时间',
    PRIMARY KEY (`id`),
    KEY `idx_ai_rec_user_time` (`user_id`, `create_time`),
    KEY `idx_ai_rec_create` (`create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI 工具推荐记录';
