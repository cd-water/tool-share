-- V13 · 信用分：用户分数字段与可解释流水
-- 流水是信用分的唯一依据；delta 记录实际变化（已按 0–100 裁剪）。
-- 唯一键保证幂等：同一租赁的按时/逾期/严重损坏各只记一次，同一评价的低星事件只记一次。

ALTER TABLE `t_user`
    ADD COLUMN `credit_score` INT NOT NULL DEFAULT 100 COMMENT '信用分 0-100' AFTER `status`;

CREATE TABLE `t_user_credit_event`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '流水ID',
    `user_id`     BIGINT       NOT NULL COMMENT '用户ID',
    `event_type`  VARCHAR(32)  NOT NULL COMMENT '事件类型 ON_TIME_RETURN/OVERDUE/SEVERE_DAMAGE/LOW_REVIEW',
    `delta`       INT          NOT NULL COMMENT '实际分数变化（已按 0-100 裁剪）',
    `rental_id`   BIGINT       NULL COMMENT '关联租赁单ID',
    `review_id`   BIGINT       NULL COMMENT '关联评价ID',
    `reason`      VARCHAR(255) NOT NULL DEFAULT '' COMMENT '变更原因',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发生时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_credit_event_rental` (`rental_id`, `event_type`),
    UNIQUE KEY `uk_credit_event_review` (`review_id`, `event_type`),
    KEY `idx_credit_event_user_time` (`user_id`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户信用分流水';
