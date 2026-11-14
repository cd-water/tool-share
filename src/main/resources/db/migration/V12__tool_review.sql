-- V12 · 工具评价（一单一评、不可修改；工具与用户均为快照，不使用外键）

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
    `images`        TEXT         NULL COMMENT '图片URL，逗号分隔，最多 3 张',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '评价时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_review_rental` (`rental_id`),
    KEY `idx_review_tool_time` (`tool_id`, `create_time`),
    KEY `idx_review_user_time` (`user_id`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '工具评价';
