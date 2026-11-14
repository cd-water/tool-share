-- V1 · 用户、管理员、工具与工具日志（schema only）
-- 演示数据见 ops/seed-demo.sql；测试数据见 src/test/resources/db/test-seed.sql。

CREATE TABLE `t_admin`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '管理员ID（主键）',
    `username`    VARCHAR(20)  NOT NULL COMMENT '账号',
    `password`    VARCHAR(100) NOT NULL COMMENT '密码（BCrypt）',
    `role`        VARCHAR(20)  NOT NULL DEFAULT 'STAFF' COMMENT '角色 STAFF/SUPER_ADMIN',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态 0-禁用 1-启用',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '管理员表';

CREATE TABLE `t_user`
(
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID（主键）',
    `phone`        VARCHAR(20)  NOT NULL COMMENT '手机号',
    `password`     VARCHAR(100) DEFAULT NULL COMMENT '密码（BCrypt）',
    `nickname`     VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '昵称（静默注册自动生成）',
    `real_name`    VARCHAR(64)  DEFAULT NULL COMMENT '真实姓名',
    `id_card`      VARCHAR(255) DEFAULT NULL COMMENT '身份证号（enc:v2: 前缀为 AES-GCM 密文）',
    `avatar`       VARCHAR(255) NOT NULL DEFAULT '' COMMENT '头像URL',
    `status`       TINYINT      NOT NULL DEFAULT 1 COMMENT '状态 0-禁用 1-启用',
    `credit_score` INT          NOT NULL DEFAULT 100 COMMENT '信用分 0-100',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_phone` (`phone`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户表';

CREATE TABLE `t_tool`
(
    `id`               BIGINT         NOT NULL AUTO_INCREMENT COMMENT '工具ID',
    `code`             VARCHAR(32)    NOT NULL COMMENT '工具编码 SQ-G-XX-YYYY',
    `name`             VARCHAR(100)   NOT NULL COMMENT '工具名称',
    `model`            VARCHAR(200)   NOT NULL DEFAULT '' COMMENT '型号规格',
    `category`         VARCHAR(16)    NOT NULL COMMENT '类别 WX/YY/QJ/QT',
    `damage_level`     VARCHAR(16)    NOT NULL DEFAULT 'NEW' COMMENT '损坏程度',
    `status`           VARCHAR(20)    NOT NULL DEFAULT 'AVAILABLE' COMMENT '工具状态',
    `version`          INT            NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `deposit`          DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '押金',
    `storage_area`     VARCHAR(200)   NOT NULL DEFAULT '' COMMENT '存放区域',
    `usage_guide`      TEXT           COMMENT '使用说明',
    `image_url`        VARCHAR(500)   NOT NULL DEFAULT '' COMMENT '主图',
    `image_urls`       TEXT           COMMENT '图片URL，逗号分隔',
    `purchase_date`    DATE           DEFAULT NULL COMMENT '采购日期',
    `supplier`         VARCHAR(200)   NOT NULL DEFAULT '' COMMENT '供应商',
    `audit_status`     TINYINT        NOT NULL DEFAULT 1 COMMENT '审核状态 0待审核 1已通过 2需修改',
    `audit_remark`     VARCHAR(500)   DEFAULT NULL COMMENT '审核意见',
    `reviewed_by`      BIGINT         DEFAULT NULL COMMENT '审核管理员',
    `reviewed_at`      DATETIME       DEFAULT NULL COMMENT '审核时间',
    `rental_count`     INT            NOT NULL DEFAULT 0 COMMENT '累计租赁次数',
    `last_repair_time` DATETIME       DEFAULT NULL COMMENT '最近维修时间',
    `created_by`       BIGINT         DEFAULT NULL COMMENT '录入管理员',
    `create_time`      DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`      DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tool_code` (`code`),
    KEY `idx_tool_category` (`category`),
    KEY `idx_tool_status` (`status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '工具表';

CREATE TABLE `t_tool_log`
(
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `tool_id`       BIGINT       NOT NULL COMMENT '工具ID',
    `action`        VARCHAR(32)  NOT NULL COMMENT '动作',
    `from_status`   VARCHAR(20)  DEFAULT NULL COMMENT '变更前状态（状态变更/审核时为原状态码）',
    `to_status`     VARCHAR(20)  DEFAULT NULL COMMENT '变更后状态',
    `detail`        VARCHAR(500) DEFAULT NULL COMMENT '详情',
    `operator_id`   BIGINT       DEFAULT NULL COMMENT '操作管理员',
    `operator_name` VARCHAR(64)  DEFAULT NULL COMMENT '操作人名称',
    `remark`        VARCHAR(500) DEFAULT NULL COMMENT '备注/审核意见',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_tool_log_tool` (`tool_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '工具状态日志';
