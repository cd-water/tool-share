-- 知识库文档增加向量索引标记，用于启动时补建索引与删除时清理向量
ALTER TABLE `t_knowledge_doc`
    ADD COLUMN `indexed` TINYINT NOT NULL DEFAULT 0 COMMENT '是否已建立向量索引（0-否 1-是）' AFTER `content`;
