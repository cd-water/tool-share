-- V1 · 全量表结构（schema）
-- 用户表（含实名资料）
CREATE TABLE `t_user`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID（主键）',
    `phone`       VARCHAR(20)  NOT NULL COMMENT '手机号',
    `password`    VARCHAR(100) NULL COMMENT '密码（BCrypt）',
    `nickname`    VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '昵称（静默注册自动生成）',
    `real_name`   VARCHAR(64)  NULL COMMENT '真实姓名',
    `id_card`     VARCHAR(255) NULL COMMENT '身份证号（enc: 前缀为 AES 密文）',
    `avatar`      VARCHAR(255) NOT NULL DEFAULT '' COMMENT '头像URL',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态 0-禁用 1-启用',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_phone` (`phone`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户表';

-- 管理员表
CREATE TABLE `t_admin`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '管理员ID（主键）',
    `username`    VARCHAR(20)  NOT NULL COMMENT '账号',
    `password`    VARCHAR(100) NOT NULL COMMENT '密码（BCrypt）',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态 0-禁用 1-启用',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '管理员表';

-- 工具表
CREATE TABLE `t_tool`
(
    `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '工具ID',
    `code`            VARCHAR(32)   NOT NULL COMMENT '工具编码 SQ-G-XX-YYYY',
    `name`            VARCHAR(100)  NOT NULL COMMENT '工具名称',
    `model`           VARCHAR(200)  NOT NULL DEFAULT '' COMMENT '型号规格',
    `category`        VARCHAR(16)   NOT NULL COMMENT '类别 WX/YY/QJ/QT',
    `damage_level`    VARCHAR(16)   NOT NULL DEFAULT 'NEW' COMMENT '损坏程度',
    `status`          VARCHAR(20)   NOT NULL DEFAULT 'AVAILABLE' COMMENT '工具状态',
    `deposit`         DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '押金',
    `storage_area`    VARCHAR(200)  NOT NULL DEFAULT '' COMMENT '存放区域',
    `usage_guide`     TEXT          NULL COMMENT '使用说明',
    `image_url`       VARCHAR(500)  NOT NULL DEFAULT '' COMMENT '主图',
    `image_urls`      TEXT          NULL COMMENT '图片URL，逗号分隔',
    `purchase_date`   DATE          NULL COMMENT '采购日期',
    `supplier`        VARCHAR(200)  NOT NULL DEFAULT '' COMMENT '供应商',
    `audit_status`    TINYINT       NOT NULL DEFAULT 1 COMMENT '审核状态 0待审核 1已通过',
    `rental_count`    INT           NOT NULL DEFAULT 0 COMMENT '累计租赁次数',
    `last_repair_time` DATETIME     NULL COMMENT '最近维修时间',
    `created_by`      BIGINT        NULL COMMENT '录入管理员',
    `create_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tool_code` (`code`),
    KEY `idx_tool_category` (`category`),
    KEY `idx_tool_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '工具表';

-- 租赁表（预约 + 记录合一）
CREATE TABLE `t_rental`
(
    `id`                 BIGINT        NOT NULL AUTO_INCREMENT COMMENT '租赁ID',
    `order_no`           VARCHAR(40)   NOT NULL COMMENT '租赁预约单号',
    `tool_id`            BIGINT        NOT NULL COMMENT '工具ID',
    `tool_code`          VARCHAR(32)   NOT NULL COMMENT '工具编码快照',
    `tool_name`          VARCHAR(100)  NOT NULL COMMENT '工具名称快照',
    `user_id`            BIGINT        NOT NULL COMMENT '租赁用户',
    `renter_name`        VARCHAR(64)   NOT NULL COMMENT '租赁人姓名快照',
    `renter_phone`       VARCHAR(20)   NOT NULL COMMENT '租赁人手机号快照',
    `start_time`         DATETIME      NOT NULL COMMENT '租赁开始',
    `end_time`           DATETIME      NOT NULL COMMENT '租赁结束',
    `pickup_person`      VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '取件人',
    `deposit_amount`     DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '押金金额',
    `deposit_status`     VARCHAR(20)   NOT NULL DEFAULT 'UNPAID' COMMENT '押金状态',
    `status`             VARCHAR(20)   NOT NULL DEFAULT 'PENDING_PAYMENT' COMMENT '租赁状态',
    `actual_return_time` DATETIME      NULL COMMENT '实际归还时间',
    `overdue_fee`        DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '逾期费',
    `damage_fee`         DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '损坏赔偿',
    `refund_amount`      DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '退还金额',
    `damage_result`      VARCHAR(16)   NULL COMMENT '验收结果',
    `return_remark`      VARCHAR(500)  NULL COMMENT '归还备注',
    `cancel_reason`      VARCHAR(500)  NULL COMMENT '取消原因',
    `cancel_time`        DATETIME      NULL COMMENT '取消时间',
    `create_time`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rental_order_no` (`order_no`),
    KEY `idx_rental_tool_time` (`tool_id`, `start_time`, `end_time`),
    KEY `idx_rental_user` (`user_id`),
    KEY `idx_rental_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '租赁表';

-- 损坏报修表
CREATE TABLE `t_damage_report`
(
    `id`                  BIGINT        NOT NULL AUTO_INCREMENT COMMENT '报修ID',
    `report_no`           VARCHAR(40)   NOT NULL COMMENT '报修单号',
    `rental_id`           BIGINT        NOT NULL COMMENT '关联租赁',
    `tool_id`             BIGINT        NOT NULL COMMENT '工具ID',
    `tool_code`           VARCHAR(32)   NOT NULL COMMENT '工具编码快照',
    `tool_name`           VARCHAR(100)  NOT NULL COMMENT '工具名称快照',
    `user_id`             BIGINT        NOT NULL COMMENT '报修用户',
    `reporter_name`       VARCHAR(64)   NOT NULL COMMENT '报修人',
    `reporter_phone`      VARCHAR(20)   NOT NULL COMMENT '报修人手机号',
    `damage_part`         VARCHAR(200)  NOT NULL COMMENT '损坏部位',
    `description`         TEXT          NOT NULL COMMENT '损坏描述',
    `images`              TEXT          NULL COMMENT '图片URL，逗号分隔',
    `status`              VARCHAR(20)   NOT NULL DEFAULT 'SUBMITTED' COMMENT '报修状态',
    `verifier`            VARCHAR(64)   NULL COMMENT '核验人',
    `verify_time`         DATETIME      NULL COMMENT '核验时间',
    `verify_result`       VARCHAR(20)   NULL COMMENT '核验结果',
    `repair_person`       VARCHAR(64)   NULL COMMENT '维修负责人',
    `repair_estimate`     VARCHAR(100)  NULL COMMENT '预计时长',
    `repair_start_time`   DATETIME      NULL COMMENT '维修开始时间',
    `repair_cost`         DECIMAL(10, 2) NULL COMMENT '维修费用',
    `progress_status`     VARCHAR(20)   NULL COMMENT '维修进度',
    `progress_update_time` DATETIME     NULL COMMENT '进度更新时间',
    `progress_note`       VARCHAR(500)  NULL COMMENT '进度说明',
    `acceptor`            VARCHAR(64)   NULL COMMENT '验收人',
    `accept_time`         DATETIME      NULL COMMENT '验收时间',
    `accept_result`       VARCHAR(20)   NULL COMMENT '验收结果',
    `tool_status_after`   VARCHAR(20)   NULL COMMENT '验收后工具状态',
    `remark`              VARCHAR(500)  NULL COMMENT '备注',
    `create_time`         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_report_no` (`report_no`),
    KEY `idx_report_user` (`user_id`),
    KEY `idx_report_status` (`status`),
    KEY `idx_report_tool` (`tool_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '损坏报修表';

-- 消息表
CREATE TABLE `t_message`
(
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '消息ID',
    `receiver_type` VARCHAR(10)  NOT NULL COMMENT 'USER/ADMIN',
    `receiver_id`   BIGINT       NOT NULL COMMENT '接收人ID',
    `type`          VARCHAR(20)  NOT NULL COMMENT '消息类型',
    `title`         VARCHAR(100) NOT NULL COMMENT '标题',
    `content`       VARCHAR(1000) NOT NULL COMMENT '内容',
    `channel`       VARCHAR(10)  NOT NULL DEFAULT 'SYSTEM' COMMENT 'SYSTEM/SMS',
    `read_status`   TINYINT      NOT NULL DEFAULT 0 COMMENT '0未读 1已读',
    `biz_key`       VARCHAR(100) NULL COMMENT '业务去重键',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_message_biz_key` (`biz_key`),
    KEY `idx_message_receiver` (`receiver_type`, `receiver_id`, `read_status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '消息表';

-- 工具状态日志表
CREATE TABLE `t_tool_log`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `tool_id`     BIGINT       NOT NULL COMMENT '工具ID',
    `action`      VARCHAR(32)  NOT NULL COMMENT '动作',
    `detail`      VARCHAR(500) NULL COMMENT '详情',
    `operator_id` BIGINT       NULL COMMENT '操作管理员',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_tool_log_tool` (`tool_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '工具状态日志';

-- 知识库文档表
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
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '知识库文档';
