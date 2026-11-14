-- V8 · 结算流水：费用明细与支付流水（ADR-0002）
-- t_rental 的金额字段仅作展示快照，本表是资金事实来源。
CREATE TABLE `t_rental_fee`
(
    `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '费用流水ID',
    `rental_id`     BIGINT        NOT NULL COMMENT '租赁单ID',
    `fee_type`      VARCHAR(20)   NOT NULL COMMENT '费用类型 OVERDUE/DAMAGE/WAIVER/OTHER',
    `amount`        DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '金额（WAIVER 为减免额）',
    `reason`        VARCHAR(500)  NULL COMMENT '原因 / 说明',
    `operator_name` VARCHAR(64)   NULL COMMENT '经办人',
    `create_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_rental_fee_rental` (`rental_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '租赁费用流水';

CREATE TABLE `t_payment_record`
(
    `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '支付流水ID',
    `rental_id`     BIGINT        NOT NULL COMMENT '租赁单ID',
    `record_type`   VARCHAR(20)   NOT NULL COMMENT 'DEPOSIT_IN/DEPOSIT_REFUND/RECEIVABLE',
    `amount`        DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '金额',
    `operator_name` VARCHAR(64)   NULL COMMENT '经办人',
    `remark`        VARCHAR(500)  NULL COMMENT '备注',
    `create_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_payment_record_rental` (`rental_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '租赁支付流水';
