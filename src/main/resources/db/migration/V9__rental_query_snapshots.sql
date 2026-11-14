-- V9 · 租赁查询快照：身份证脱敏快照与录入/更新人
ALTER TABLE `t_rental`
    ADD COLUMN `renter_id_card_masked` VARCHAR(32) NULL COMMENT '租赁人身份证脱敏快照' AFTER `renter_phone`,
    ADD COLUMN `created_by_name`       VARCHAR(64) NULL COMMENT '录入人' AFTER `pickup_person`,
    ADD COLUMN `updated_by_name`       VARCHAR(64) NULL COMMENT '归还/取消经办人' AFTER `created_by_name`;
