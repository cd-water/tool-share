-- V4 · 管理员角色：STAFF（工作人员）/ SUPER_ADMIN（系统管理员）
ALTER TABLE `t_admin`
    ADD COLUMN `role` VARCHAR(20) NOT NULL DEFAULT 'STAFF' COMMENT '角色 STAFF/SUPER_ADMIN' AFTER `password`;

-- 迁移前管理员拥有全部管理端能力，统一升级为系统管理员
UPDATE `t_admin` SET `role` = 'SUPER_ADMIN';
