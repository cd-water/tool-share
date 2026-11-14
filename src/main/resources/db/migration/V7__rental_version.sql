-- V7 · 租赁单乐观锁版本号
ALTER TABLE `t_rental`
    ADD COLUMN `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号' AFTER `status`;
