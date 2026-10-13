-- ============================================================
-- 社区共享工具租赁系统 初始 Schema
-- 约定：所有表含 id 自增主键、create_time、update_time（MyBatis-Plus 自动填充）
--       金额 DECIMAL(10,2)，枚举 TINYINT（取值见列注释）
--       表间关系为逻辑外键（建索引、不建物理外键约束）
-- ============================================================

-- 1. 用户表（三级角色同一张表）
CREATE TABLE `user`
(
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`   VARCHAR(50)  NOT NULL COMMENT '登录名',
    `password`   VARCHAR(100) NOT NULL COMMENT '密码（BCrypt）',
    `real_name`  VARCHAR(50)  NOT NULL COMMENT '真实姓名',
    `phone`      VARCHAR(255) NOT NULL COMMENT '手机号（加密存储，展示脱敏）',
    `id_card`    VARCHAR(255) NULL COMMENT '身份证号（加密存储，展示脱敏）',
    `role`       TINYINT      NOT NULL DEFAULT 0 COMMENT '角色：0居民 1社区工作人员 2系统管理员',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户表';

-- 2. 工具表
CREATE TABLE `tool`
(
    `id`                BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `tool_code`         VARCHAR(20)   NOT NULL COMMENT '工具编号：SQ-G-XX-YYYY（XX=WX维修/YY园艺/QJ清洁/QT其他）',
    `name`              VARCHAR(100)  NOT NULL COMMENT '工具名称',
    `model_spec`        VARCHAR(200)  NULL COMMENT '型号规格',
    `category`          TINYINT       NOT NULL COMMENT '类别：1维修 2园艺 3清洁 4其他',
    `damage_level`      TINYINT       NOT NULL DEFAULT 1 COMMENT '损坏程度：1全新 2轻微磨损 3中度磨损 4严重损坏（归还验收后重估）',
    `deposit`           DECIMAL(10,2) NOT NULL COMMENT '押金金额（元）',
    `storage_location`  VARCHAR(100)  NOT NULL COMMENT '存放区域：社区服务站+柜体编号',
    `usage_instruction` TEXT          NULL COMMENT '使用说明（图文富文本）',
    `images`            JSON          NULL COMMENT '工具图片 URL 数组（MinIO）',
    `purchase_date`     DATE          NULL COMMENT '采购日期',
    `supplier_info`     VARCHAR(200)  NULL COMMENT '供应商信息',
    `status`            TINYINT       NOT NULL DEFAULT 1 COMMENT '状态：1可用 2已借出 3维修中 4报废 5下架',
    `audit_status`      TINYINT       NOT NULL DEFAULT 1 COMMENT '审核状态：1待审核 2已通过 3需修改',
    `audit_opinion`     VARCHAR(200)  NULL COMMENT '审核修改意见（audit_status=3 时填写）',
    `create_by`         BIGINT        NULL COMMENT '录入人 user.id',
    `create_time`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tool_code` (`tool_code`),
    KEY `idx_category_status` (`category`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '工具表';

-- 3. 工具状态日志表（状态每次变更自动记录）
CREATE TABLE `tool_status_log`
(
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `tool_id`     BIGINT      NOT NULL COMMENT '工具 id',
    `old_status`  TINYINT     NULL COMMENT '变更前状态',
    `new_status`  TINYINT     NOT NULL COMMENT '变更后状态',
    `operator_id` BIGINT      NOT NULL COMMENT '操作人 user.id',
    `remark`      VARCHAR(200) NULL COMMENT '备注（关联单号等）',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_tool_id` (`tool_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '工具状态日志表';

-- 4. 租赁单表（预约 → 取件 → 归还全生命周期单表，状态机区分阶段）
CREATE TABLE `rental_order`
(
    `id`                     BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `order_no`               VARCHAR(30)   NOT NULL COMMENT '预约单号：SQ-ZY-YYYYMMDD-XXXX',
    `tool_id`                BIGINT        NOT NULL COMMENT '工具 id',
    `user_id`                BIGINT        NOT NULL COMMENT '租赁人 user.id',
    `pickup_name`            VARCHAR(50)   NOT NULL COMMENT '取件人（可为代取人）',
    `start_time`             DATETIME      NOT NULL COMMENT '租赁开始时间',
    `end_time`               DATETIME      NOT NULL COMMENT '租赁结束时间',
    `deposit_amount`         DECIMAL(10,2) NOT NULL COMMENT '押金金额（元）',
    `deposit_status`         TINYINT       NOT NULL DEFAULT 1 COMMENT '押金状态：1未缴 2已缴 3已退还（支付沙箱）',
    `status`                 TINYINT       NOT NULL DEFAULT 1 COMMENT '状态：1待支付 2已预约 3已取件 4已归还 5已取消',
    `cancel_time`            DATETIME      NULL COMMENT '取消时间（取件前 24 小时可取消）',
    `return_time`            DATETIME      NULL COMMENT '实际归还时间',
    `accept_result`          TINYINT       NULL COMMENT '归还验收结果：1完好 2轻微损坏（无需赔偿） 3严重损坏（需赔偿）',
    `overdue_fee`            DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '逾期费用（元，封顶押金）',
    `deposit_refund_amount`  DECIMAL(10,2) NULL COMMENT '押金实退金额（扣除逾期费/赔偿）',
    `update_by`              BIGINT        NULL COMMENT '更新人 user.id（归还补充记录的工作人员）',
    `create_time`            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    KEY `idx_tool_time` (`tool_id`, `start_time`, `end_time`),
    KEY `idx_user_id` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '租赁单表';

-- 5. 损坏报修单表
CREATE TABLE `repair_order`
(
    `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `repair_no`       VARCHAR(30)   NOT NULL COMMENT '报修单号：SQ-BX-YYYYMMDD-XXXX',
    `rental_order_id` BIGINT        NOT NULL COMMENT '关联租赁单 id',
    `tool_id`         BIGINT        NOT NULL COMMENT '工具 id',
    `reporter_id`     BIGINT        NOT NULL COMMENT '报修人 user.id',
    `damage_part`     VARCHAR(100)  NOT NULL COMMENT '损坏部位',
    `damage_desc`     TEXT          NOT NULL COMMENT '损坏描述（场景、现象）',
    `images`          JSON          NULL COMMENT '损坏证明材料 URL 数组（1-3 张）',
    `verify_by`       BIGINT        NULL COMMENT '核验人 user.id',
    `verify_time`     DATETIME      NULL COMMENT '核验时间',
    `verify_result`   TINYINT       NULL COMMENT '核验结果：1确认损坏（需维修） 2疑似损坏（需检测） 3误报',
    `repairer_name`   VARCHAR(50)   NULL COMMENT '维修负责人（社区维修员/外部师傅，存文本）',
    `expect_duration` VARCHAR(50)   NULL COMMENT '维修预计时长',
    `repair_start_time` DATETIME    NULL COMMENT '维修开始时间',
    `repair_status`   TINYINT       NULL COMMENT '维修进度：1待配件 2维修中 3维修完成 4维修失败（报废）',
    `repair_fee`      DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '维修费用（元，支付沙箱）',
    `accept_by`       BIGINT        NULL COMMENT '验收人 user.id',
    `accept_time`     DATETIME      NULL COMMENT '验收时间',
    `accept_result`   TINYINT       NULL COMMENT '验收结果：1合格（恢复可用） 2不合格（二次维修） 3部分合格（限用）',
    `remark`          VARCHAR(500)  NULL COMMENT '备注（费用分摊、赔偿说明）',
    `create_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_repair_no` (`repair_no`),
    KEY `idx_rental_order_id` (`rental_order_id`),
    KEY `idx_tool_id` (`tool_id`),
    KEY `idx_reporter` (`reporter_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '损坏报修单表';

-- 6. 维修进度表（进度多次更新，逐条可追溯）
CREATE TABLE `repair_progress`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `repair_id`   BIGINT       NOT NULL COMMENT '报修单 id',
    `status`      TINYINT      NOT NULL COMMENT '维修进度：1待配件 2维修中 3维修完成 4维修失败（报废）',
    `description` VARCHAR(500) NULL COMMENT '进度说明',
    `operator_id` BIGINT       NOT NULL COMMENT '操作人 user.id',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_repair_id` (`repair_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '维修进度表';

-- 7. 消息提醒表（提醒记录存档）
CREATE TABLE `message`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT       NOT NULL COMMENT '接收人 user.id',
    `type`        TINYINT      NOT NULL COMMENT '类型：1取件提醒 2到期提醒 3逾期提醒 4维修完成提醒',
    `title`       VARCHAR(100) NOT NULL COMMENT '标题',
    `content`     VARCHAR(500) NULL COMMENT '内容',
    `channel`     TINYINT      NOT NULL DEFAULT 1 COMMENT '渠道：1系统内 2短信',
    `send_status` TINYINT      NOT NULL DEFAULT 1 COMMENT '发送状态：1待发送 2已发送 3发送失败',
    `send_time`   DATETIME     NULL COMMENT '发送时间',
    `biz_no`      VARCHAR(30)  NULL COMMENT '关联业务单号（预约单号/报修单号）',
    `read_status` TINYINT      NOT NULL DEFAULT 0 COMMENT '阅读状态：0未读 1已读',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '消息提醒表';

-- 8. 知识库文档表（向量本体在 Qdrant，本表管理上传记录）
CREATE TABLE `kb_document`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `file_name`   VARCHAR(200) NOT NULL COMMENT '文档名称',
    `file_url`    VARCHAR(500) NOT NULL COMMENT '文档地址（MinIO）',
    `file_size`   BIGINT       NULL COMMENT '文件大小（字节）',
    `chunk_count` INT          NULL COMMENT '切分片段数（解析完成后回填）',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '解析状态：1解析中 2完成 3失败',
    `upload_by`   BIGINT       NULL COMMENT '上传人 user.id',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '知识库文档表';
