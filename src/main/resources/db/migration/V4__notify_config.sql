-- 默认提醒方式配置（单行配置表，主键固定为 1）
CREATE TABLE `notify_config`
(
    `id`          BIGINT   NOT NULL COMMENT '固定为 1（单行配置表）',
    `channel`     TINYINT  NOT NULL DEFAULT 1 COMMENT '默认提醒渠道：1系统内 2短信',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '默认提醒方式配置';

INSERT INTO `notify_config` (`id`, `channel`)
VALUES (1, 1);
