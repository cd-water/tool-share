-- 用户表补充启用/停用状态（DEFAULT 会同时回填存量数据为 1 启用）
ALTER TABLE `user`
    ADD COLUMN `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1启用 0停用' AFTER `role`;
