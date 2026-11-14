package com.cdwater.toolshare.module.knowledge;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cdwater.toolshare.module.knowledge.KnowledgeDoc;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识库文档表的 MyBatis-Plus Mapper。
 * <p>除通用增删改查外，服务层使用条件构造器扫描 indexed=false 或 NULL
 * <p>列表查询按创建时间倒序，索引状态更新只写 indexed 字段。
 * <p>索引重试查询同时覆盖 indexed=false 与 indexed IS NULL 两种历史状态。
 * 的文档进行补索引，无需自定义 SQL。
 */
@Mapper
public interface KnowledgeDocMapper extends BaseMapper<KnowledgeDoc> {
}
