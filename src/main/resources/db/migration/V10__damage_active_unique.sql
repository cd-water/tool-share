-- V10 · 同一租赁单同一时刻只允许一张活动报修单
-- 活动状态（SUBMITTED/VERIFIED/REPAIRING）下 active_key 等于 rental_id，终态为 NULL；
-- MySQL 唯一索引允许多个 NULL，从而只约束活动报修。
ALTER TABLE `t_damage_report`
    ADD COLUMN `active_key` BIGINT GENERATED ALWAYS AS (
        CASE
            WHEN `status` IN ('SUBMITTED', 'VERIFIED', 'REPAIRING') THEN `rental_id`
            ELSE NULL
        END
        ) STORED COMMENT '活动报修唯一键：活动时等于 rental_id，终态为 NULL',
    ADD UNIQUE KEY `uk_damage_active_rental` (`active_key`);
