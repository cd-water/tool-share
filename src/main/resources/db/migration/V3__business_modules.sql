-- 用户实名资料
ALTER TABLE `t_user`
    ADD COLUMN `real_name` VARCHAR(64) NULL COMMENT '真实姓名' AFTER `nickname`,
    ADD COLUMN `id_card` VARCHAR(255) NULL COMMENT '身份证号（enc: 前缀为 AES 密文）' AFTER `real_name`;

UPDATE `t_user`
SET `real_name` = '王建国',
    `id_card` = '310101199001011234'
WHERE `id` = 1;

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

CREATE TABLE `t_knowledge_doc`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `title`       VARCHAR(200) NOT NULL COMMENT '标题',
    `file_name`   VARCHAR(255) NOT NULL DEFAULT '' COMMENT '文件名',
    `content`     LONGTEXT     NOT NULL COMMENT '解析后的文本',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '知识库文档';

-- 演示工具
INSERT INTO `t_tool` (`id`, `code`, `name`, `model`, `category`, `damage_level`, `status`, `deposit`, `storage_area`, `usage_guide`, `image_url`, `image_urls`, `purchase_date`, `supplier`, `created_by`)
VALUES (1, 'SQ-G-WX-0001', '手电钻', '博世 GSB550（550W，夹头最大 13mm）', 'WX', 'LIGHT', 'AVAILABLE', 200.00, '社区服务站 2 号工具柜', '<p>1. 操作前检查电源线与夹头；</p><p>2. 钻孔时垂直对准目标位置，缓慢按下开关；</p><p>3. 使用后断电并清理碎屑。</p>', '', '', '2024-03-15', '鼎力机电设备有限公司', 1),
       (2, 'SQ-G-WX-0002', '家用工具箱', '史丹利 65 件套', 'WX', 'NEW', 'AVAILABLE', 100.00, '社区服务站 1 号工具柜', '<p>包含螺丝刀、扳手、锤子等常用工具，使用后请按原位归放。</p>', '', '', '2024-05-20', '史丹利工具专卖店', 1),
       (3, 'SQ-G-YY-0001', '园艺修枝剪', '嘉丁拿 8857', 'YY', 'LIGHT', 'AVAILABLE', 50.00, '社区服务站 3 号工具柜', '<p>仅用于修剪树枝与绿篱，使用后擦拭刀片并涂防锈油。</p>', '', '', '2024-04-08', '绿友园林用品', 1),
       (4, 'SQ-G-YY-0002', '手推式割草机', '本田 GX35', 'YY', 'MEDIUM', 'AVAILABLE', 300.00, '社区服务站车库 A 区', '<p>使用前检查机油与刀片，禁止在湿滑草地作业。</p>', '', '', '2023-09-12', '绿友园林用品', 1),
       (5, 'SQ-G-QJ-0001', '高压清洗机', '凯驰 K3', 'QJ', 'LIGHT', 'AVAILABLE', 250.00, '社区服务站 4 号工具柜', '<p>接通水源后排气，使用完毕排空水管并擦干机身。</p>', '', '', '2024-06-01', '凯驰清洁设备', 1),
       (6, 'SQ-G-QJ-0002', '吸尘器', '戴森 V8', 'QJ', 'NEW', 'AVAILABLE', 180.00, '社区服务站 4 号工具柜', '<p>使用前确认滤网干燥，尘盒满后及时清理。</p>', '', '', '2025-01-18', '戴森官方旗舰店', 1),
       (7, 'SQ-G-WX-0003', '电锤', '博世 GBH 2-26', 'WX', 'MEDIUM', 'AVAILABLE', 350.00, '社区服务站 2 号工具柜', '<p>佩戴护目镜与防尘口罩，钻孔前确认墙体管线位置。</p>', '', '', '2023-11-30', '鼎力机电设备有限公司', 1),
       (8, 'SQ-G-QT-0001', '折叠梯', '奥鹏 5 步梯', 'QT', 'NEW', 'AVAILABLE', 80.00, '社区服务站门厅', '<p>展开后确认卡扣锁定，禁止站在最高两阶作业。</p>', '', '', '2025-02-14', '奥鹏家居', 1);

-- 演示知识库
INSERT INTO `t_knowledge_doc` (`title`, `file_name`, `content`)
VALUES ('社区工具租赁规则', '租赁规则.txt', '社区共享工具租赁规则：居民需完善真实姓名与身份证号后方可预约。预约时需选择开始与结束时间，系统自动校验时段是否冲突。押金按工具价值收取，支付后预约生效。取件前 24 小时外可取消预约，押金原路退还；24 小时内取消需联系社区工作人员。租赁逾期按每小时 5 元收取逾期费，最高不超过押金。归还时由工作人员验收，如有严重损坏将从押金中扣除赔偿。'),
       ('手电钻安全操作说明', '手电钻安全操作.txt', '手电钻使用前请检查电源线是否破损、夹头是否夹紧钻头。钻孔时保持身体稳定，垂直对准目标位置，缓慢按下开关，避免用力过猛。禁止用于金属以外的坚硬材质，如石材。使用后关闭开关、拔掉电源并清理钻头碎屑。若发现电机异响、接口发黑或无法转动，请立即停止使用并提交损坏报修。'),
       ('园艺工具使用与保养', '园艺工具保养.txt', '园艺修枝剪仅用于修剪树枝与绿篱，使用后应擦拭刀片并涂抹防锈油。割草机使用前检查机油与刀片，禁止在湿滑草地作业。长时间不使用时请存放在干燥工具柜内。损坏报修时请说明损坏部位并上传照片，工作人员会在核验后安排维修。');
