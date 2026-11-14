-- V2 · 租赁单、费用/支付流水与报修单（schema only）

CREATE TABLE `t_rental`
(
    `id`                    BIGINT         NOT NULL AUTO_INCREMENT COMMENT '租赁ID',
    `order_no`              VARCHAR(40)    NOT NULL COMMENT '租赁单号',
    `tool_id`               BIGINT         NOT NULL COMMENT '工具ID',
    `tool_code`             VARCHAR(32)    NOT NULL COMMENT '工具编码快照',
    `tool_name`             VARCHAR(100)   NOT NULL COMMENT '工具名称快照',
    `user_id`               BIGINT         NOT NULL COMMENT '租赁用户',
    `renter_name`           VARCHAR(64)    NOT NULL COMMENT '租赁人姓名快照',
    `renter_phone`          VARCHAR(20)    NOT NULL COMMENT '租赁人手机号快照',
    `renter_id_card_masked` VARCHAR(32)    DEFAULT NULL COMMENT '租赁人身份证脱敏快照',
    `start_time`            DATETIME       NOT NULL COMMENT '租赁开始',
    `end_time`              DATETIME       NOT NULL COMMENT '租赁结束',
    `pickup_person`         VARCHAR(64)    NOT NULL DEFAULT '' COMMENT '取件人',
    `created_by_name`       VARCHAR(64)    DEFAULT NULL COMMENT '录入人',
    `updated_by_name`       VARCHAR(64)    DEFAULT NULL COMMENT '归还/取消经办人',
    `deposit_amount`        DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '押金金额',
    `deposit_status`        VARCHAR(20)    NOT NULL DEFAULT 'UNPAID' COMMENT '押金状态',
    `status`                VARCHAR(20)    NOT NULL DEFAULT 'PENDING_PAYMENT' COMMENT '租赁状态',
    `version`               INT            NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `actual_return_time`    DATETIME       DEFAULT NULL COMMENT '实际归还时间',
    `overdue_fee`           DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '逾期费',
    `damage_fee`            DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '损坏赔偿',
    `refund_amount`         DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '退还金额',
    `damage_result`         VARCHAR(16)    DEFAULT NULL COMMENT '验收结果',
    `return_remark`         VARCHAR(500)   DEFAULT NULL COMMENT '归还备注',
    `cancel_reason`         VARCHAR(500)   DEFAULT NULL COMMENT '取消原因',
    `cancel_time`           DATETIME       DEFAULT NULL COMMENT '取消时间',
    `create_time`           DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`           DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rental_order_no` (`order_no`),
    KEY `idx_rental_tool_time` (`tool_id`, `start_time`, `end_time`),
    KEY `idx_rental_user` (`user_id`),
    KEY `idx_rental_status` (`status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '租赁表';

CREATE TABLE `t_rental_fee`
(
    `id`            BIGINT         NOT NULL AUTO_INCREMENT COMMENT '费用流水ID',
    `rental_id`     BIGINT         NOT NULL COMMENT '租赁单ID',
    `fee_type`      VARCHAR(20)    NOT NULL COMMENT '费用类型 OVERDUE/DAMAGE/WAIVER/OTHER',
    `amount`        DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '金额（WAIVER 为减免额）',
    `reason`        VARCHAR(500)   DEFAULT NULL COMMENT '原因 / 说明',
    `operator_name` VARCHAR(64)    DEFAULT NULL COMMENT '经办人',
    `create_time`   DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_rental_fee_rental` (`rental_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '租赁费用流水';

CREATE TABLE `t_payment_record`
(
    `id`            BIGINT         NOT NULL AUTO_INCREMENT COMMENT '支付流水ID',
    `rental_id`     BIGINT         NOT NULL COMMENT '租赁单ID',
    `record_type`   VARCHAR(20)    NOT NULL COMMENT 'DEPOSIT_IN/DEPOSIT_REFUND/RECEIVABLE',
    `amount`        DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '金额',
    `operator_name` VARCHAR(64)    DEFAULT NULL COMMENT '经办人',
    `remark`        VARCHAR(500)   DEFAULT NULL COMMENT '备注',
    `create_time`   DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_payment_record_rental` (`rental_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '租赁支付流水';

CREATE TABLE `t_damage_report`
(
    `id`                   BIGINT         NOT NULL AUTO_INCREMENT COMMENT '报修ID',
    `report_no`            VARCHAR(40)    NOT NULL COMMENT '报修单号',
    `rental_id`            BIGINT         NOT NULL COMMENT '关联租赁',
    `tool_id`              BIGINT         NOT NULL COMMENT '工具ID',
    `tool_code`            VARCHAR(32)    NOT NULL COMMENT '工具编码快照',
    `tool_name`            VARCHAR(100)   NOT NULL COMMENT '工具名称快照',
    `user_id`              BIGINT         NOT NULL COMMENT '报修用户',
    `reporter_name`        VARCHAR(64)    NOT NULL COMMENT '报修人',
    `reporter_phone`       VARCHAR(20)    NOT NULL COMMENT '报修人手机号',
    `damage_part`          VARCHAR(200)   NOT NULL COMMENT '损坏部位',
    `description`          TEXT           NOT NULL COMMENT '损坏描述',
    `images`               TEXT           COMMENT '图片URL，逗号分隔',
    `status`               VARCHAR(20)    NOT NULL DEFAULT 'SUBMITTED' COMMENT '报修状态',
    `verifier`             VARCHAR(64)    DEFAULT NULL COMMENT '核验人',
    `verify_time`          DATETIME       DEFAULT NULL COMMENT '核验时间',
    `verify_result`        VARCHAR(20)    DEFAULT NULL COMMENT '核验结果',
    `repair_person`        VARCHAR(64)    DEFAULT NULL COMMENT '维修负责人',
    `repair_estimate`      VARCHAR(100)   DEFAULT NULL COMMENT '预计时长',
    `repair_start_time`    DATETIME       DEFAULT NULL COMMENT '维修开始时间',
    `repair_cost`          DECIMAL(10, 2) DEFAULT NULL COMMENT '维修费用',
    `progress_status`      VARCHAR(20)    DEFAULT NULL COMMENT '维修进度',
    `progress_update_time` DATETIME       DEFAULT NULL COMMENT '进度更新时间',
    `progress_note`        VARCHAR(500)   DEFAULT NULL COMMENT '进度说明',
    `acceptor`             VARCHAR(64)    DEFAULT NULL COMMENT '验收人',
    `accept_time`          DATETIME       DEFAULT NULL COMMENT '验收时间',
    `accept_result`        VARCHAR(20)    DEFAULT NULL COMMENT '验收结果',
    `tool_status_after`    VARCHAR(20)    DEFAULT NULL COMMENT '验收后工具状态',
    `remark`               VARCHAR(500)   DEFAULT NULL COMMENT '备注',
    `create_time`          DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`          DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `active_key`           BIGINT GENERATED ALWAYS AS (
        CASE
            WHEN `status` IN ('SUBMITTED', 'VERIFIED', 'REPAIRING') THEN `rental_id`
            ELSE NULL
            END
        ) STORED COMMENT '活动报修唯一键：活动时等于 rental_id，终态为 NULL',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_report_no` (`report_no`),
    UNIQUE KEY `uk_damage_active_rental` (`active_key`),
    KEY `idx_report_user` (`user_id`),
    KEY `idx_report_status` (`status`),
    KEY `idx_report_tool` (`tool_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '损坏报修表';
