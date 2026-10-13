-- ============================================================
-- 社区共享工具租赁系统 全量数据库参考 Schema
-- 生成方式：由 Flyway 迁移（V1 建表 + V3 补列）应用后的 MySQL 8.0 导出
-- 用途：仅供快速查阅，实际结构以 src/main/resources/db/migration/ 为准
-- ============================================================

CREATE TABLE `user`
(
    `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`    varchar(50)  NOT NULL COMMENT '登录名',
    `password`    varchar(100) NOT NULL COMMENT '密码（BCrypt）',
    `real_name`   varchar(50)  NOT NULL COMMENT '真实姓名',
    `phone`       varchar(255) NOT NULL COMMENT '手机号（加密存储，展示脱敏）',
    `id_card`     varchar(255)          DEFAULT NULL COMMENT '身份证号（加密存储，展示脱敏）',
    `role`        tinyint      NOT NULL DEFAULT '0' COMMENT '角色：0居民 1社区工作人员 2系统管理员',
    `status`      tinyint      NOT NULL DEFAULT '1' COMMENT '状态：1启用 0停用',
    `create_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户表';

CREATE TABLE `tool`
(
    `id`                bigint         NOT NULL AUTO_INCREMENT COMMENT '主键',
    `tool_code`         varchar(20)    NOT NULL COMMENT '工具编号：SQ-G-XX-YYYY（XX=WX维修/YY园艺/QJ清洁/QT其他）',
    `name`              varchar(100)   NOT NULL COMMENT '工具名称',
    `model_spec`        varchar(200)            DEFAULT NULL COMMENT '型号规格',
    `category`          tinyint        NOT NULL COMMENT '类别：1维修 2园艺 3清洁 4其他',
    `damage_level`      tinyint        NOT NULL DEFAULT '1' COMMENT '损坏程度：1全新 2轻微磨损 3中度磨损 4严重损坏（归还验收后重估）',
    `deposit`           decimal(10, 2) NOT NULL COMMENT '押金金额（元）',
    `storage_location`  varchar(100)   NOT NULL COMMENT '存放区域：社区服务站+柜体编号',
    `usage_instruction` text COMMENT '使用说明（图文富文本）',
    `images`            json                    DEFAULT NULL COMMENT '工具图片 URL 数组（MinIO）',
    `purchase_date`     date                    DEFAULT NULL COMMENT '采购日期',
    `supplier_info`     varchar(200)            DEFAULT NULL COMMENT '供应商信息',
    `status`            tinyint        NOT NULL DEFAULT '1' COMMENT '状态：1可用 2已借出 3维修中 4报废 5下架',
    `audit_status`      tinyint        NOT NULL DEFAULT '1' COMMENT '审核状态：1待审核 2已通过 3需修改',
    `audit_opinion`     varchar(200)            DEFAULT NULL COMMENT '审核修改意见（audit_status=3 时填写）',
    `create_by`         bigint                  DEFAULT NULL COMMENT '录入人 user.id',
    `create_time`       datetime       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`       datetime       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tool_code` (`tool_code`),
    KEY                 `idx_category_status` (`category`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='工具表';

CREATE TABLE `tool_status_log`
(
    `id`          bigint   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `tool_id`     bigint   NOT NULL COMMENT '工具 id',
    `old_status`  tinyint           DEFAULT NULL COMMENT '变更前状态',
    `new_status`  tinyint  NOT NULL COMMENT '变更后状态',
    `operator_id` bigint   NOT NULL COMMENT '操作人 user.id',
    `remark`      varchar(200)      DEFAULT NULL COMMENT '备注（关联单号等）',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY           `idx_tool_id` (`tool_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='工具状态日志表';

CREATE TABLE `rental_order`
(
    `id`                    bigint         NOT NULL AUTO_INCREMENT COMMENT '主键',
    `order_no`              varchar(30)    NOT NULL COMMENT '预约单号：SQ-ZY-YYYYMMDD-XXXX',
    `tool_id`               bigint         NOT NULL COMMENT '工具 id',
    `user_id`               bigint         NOT NULL COMMENT '租赁人 user.id',
    `pickup_name`           varchar(50)    NOT NULL COMMENT '取件人（可为代取人）',
    `start_time`            datetime       NOT NULL COMMENT '租赁开始时间',
    `end_time`              datetime       NOT NULL COMMENT '租赁结束时间',
    `deposit_amount`        decimal(10, 2) NOT NULL COMMENT '押金金额（元）',
    `deposit_status`        tinyint        NOT NULL DEFAULT '1' COMMENT '押金状态：1未缴 2已缴 3已退还（支付沙箱）',
    `status`                tinyint        NOT NULL DEFAULT '1' COMMENT '状态：1待支付 2已预约 3已取件 4已归还 5已取消',
    `cancel_time`           datetime                DEFAULT NULL COMMENT '取消时间（取件前 24 小时可取消）',
    `return_time`           datetime                DEFAULT NULL COMMENT '实际归还时间',
    `accept_result`         tinyint                 DEFAULT NULL COMMENT '归还验收结果：1完好 2轻微损坏（无需赔偿） 3严重损坏（需赔偿）',
    `overdue_fee`           decimal(10, 2) NOT NULL DEFAULT '0.00' COMMENT '逾期费用（元，封顶押金）',
    `deposit_refund_amount` decimal(10, 2)          DEFAULT NULL COMMENT '押金实退金额（扣除逾期费/赔偿）',
    `update_by`             bigint                  DEFAULT NULL COMMENT '更新人 user.id（归还补充记录的工作人员）',
    `create_time`           datetime       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`           datetime       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    KEY                     `idx_tool_time` (`tool_id`,`start_time`,`end_time`),
    KEY                     `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租赁单表';

CREATE TABLE `repair_order`
(
    `id`                bigint         NOT NULL AUTO_INCREMENT COMMENT '主键',
    `repair_no`         varchar(30)    NOT NULL COMMENT '报修单号：SQ-BX-YYYYMMDD-XXXX',
    `rental_order_id`   bigint         NOT NULL COMMENT '关联租赁单 id',
    `tool_id`           bigint         NOT NULL COMMENT '工具 id',
    `reporter_id`       bigint         NOT NULL COMMENT '报修人 user.id',
    `damage_part`       varchar(100)   NOT NULL COMMENT '损坏部位',
    `damage_desc`       text           NOT NULL COMMENT '损坏描述（场景、现象）',
    `images`            json                    DEFAULT NULL COMMENT '损坏证明材料 URL 数组（1-3 张）',
    `verify_by`         bigint                  DEFAULT NULL COMMENT '核验人 user.id',
    `verify_time`       datetime                DEFAULT NULL COMMENT '核验时间',
    `verify_result`     tinyint                 DEFAULT NULL COMMENT '核验结果：1确认损坏（需维修） 2疑似损坏（需检测） 3误报',
    `repairer_name`     varchar(50)             DEFAULT NULL COMMENT '维修负责人（社区维修员/外部师傅，存文本）',
    `expect_duration`   varchar(50)             DEFAULT NULL COMMENT '维修预计时长',
    `repair_start_time` datetime                DEFAULT NULL COMMENT '维修开始时间',
    `repair_status`     tinyint                 DEFAULT NULL COMMENT '维修进度：1待配件 2维修中 3维修完成 4维修失败（报废）',
    `repair_fee`        decimal(10, 2) NOT NULL DEFAULT '0.00' COMMENT '维修费用（元，支付沙箱）',
    `accept_by`         bigint                  DEFAULT NULL COMMENT '验收人 user.id',
    `accept_time`       datetime                DEFAULT NULL COMMENT '验收时间',
    `accept_result`     tinyint                 DEFAULT NULL COMMENT '验收结果：1合格（恢复可用） 2不合格（二次维修） 3部分合格（限用）',
    `remark`            varchar(500)            DEFAULT NULL COMMENT '备注（费用分摊、赔偿说明）',
    `create_time`       datetime       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`       datetime       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_repair_no` (`repair_no`),
    KEY                 `idx_rental_order_id` (`rental_order_id`),
    KEY                 `idx_tool_id` (`tool_id`),
    KEY                 `idx_reporter` (`reporter_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='损坏报修单表';

CREATE TABLE `repair_progress`
(
    `id`          bigint   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `repair_id`   bigint   NOT NULL COMMENT '报修单 id',
    `status`      tinyint  NOT NULL COMMENT '维修进度：1待配件 2维修中 3维修完成 4维修失败（报废）',
    `description` varchar(500)      DEFAULT NULL COMMENT '进度说明',
    `operator_id` bigint   NOT NULL COMMENT '操作人 user.id',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY           `idx_repair_id` (`repair_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='维修进度表';

CREATE TABLE `message`
(
    `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     bigint       NOT NULL COMMENT '接收人 user.id',
    `type`        tinyint      NOT NULL COMMENT '类型：1取件提醒 2到期提醒 3逾期提醒 4维修完成提醒',
    `title`       varchar(100) NOT NULL COMMENT '标题',
    `content`     varchar(500)          DEFAULT NULL COMMENT '内容',
    `channel`     tinyint      NOT NULL DEFAULT '1' COMMENT '渠道：1系统内 2短信',
    `send_status` tinyint      NOT NULL DEFAULT '1' COMMENT '发送状态：1待发送 2已发送 3发送失败',
    `send_time`   datetime              DEFAULT NULL COMMENT '发送时间',
    `biz_no`      varchar(30)           DEFAULT NULL COMMENT '关联业务单号（预约单号/报修单号）',
    `read_status` tinyint      NOT NULL DEFAULT '0' COMMENT '阅读状态：0未读 1已读',
    `create_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY           `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消息提醒表';

CREATE TABLE `kb_document`
(
    `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `file_name`   varchar(200) NOT NULL COMMENT '文档名称',
    `file_url`    varchar(500) NOT NULL COMMENT '文档地址（MinIO）',
    `file_size`   bigint                DEFAULT NULL COMMENT '文件大小（字节）',
    `chunk_count` int                   DEFAULT NULL COMMENT '切分片段数（解析完成后回填）',
    `status`      tinyint      NOT NULL DEFAULT '1' COMMENT '解析状态：1解析中 2完成 3失败',
    `upload_by`   bigint                DEFAULT NULL COMMENT '上传人 user.id',
    `create_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识库文档表';
