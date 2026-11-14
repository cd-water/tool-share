-- V6 · 工具审核意见与结构化操作日志
ALTER TABLE `t_tool`
    ADD COLUMN `audit_remark` VARCHAR(500) NULL COMMENT '审核意见' AFTER `audit_status`,
    ADD COLUMN `reviewed_by` BIGINT NULL COMMENT '审核管理员' AFTER `audit_remark`,
    ADD COLUMN `reviewed_at` DATETIME NULL COMMENT '审核时间' AFTER `reviewed_by`;

ALTER TABLE `t_tool_log`
    ADD COLUMN `from_status` VARCHAR(20) NULL COMMENT '变更前状态（状态变更/审核时为原状态码）' AFTER `action`,
    ADD COLUMN `to_status` VARCHAR(20) NULL COMMENT '变更后状态' AFTER `from_status`,
    ADD COLUMN `operator_name` VARCHAR(64) NULL COMMENT '操作人名称' AFTER `operator_id`,
    ADD COLUMN `remark` VARCHAR(500) NULL COMMENT '备注/审核意见' AFTER `operator_name`;
