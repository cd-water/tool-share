package com.cdwater.toolshare.module.knowledge;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_knowledge_doc")
/**
 * 知识库文档持久化实体，对应表 {@code t_knowledge_doc}。
 * <p>content 保存解析后的完整纯文本，是切块与检索的唯一数据源；
 * indexed 表示该文档是否已成功写入向量库。索引失败时文档仍会保留，
 * <p>正文与文件名在创建后一般不再修改；重新上传同一文档会创建新记录，
 * 旧记录需要显式删除，避免向量索引残留。
 * <p>indexed 只表示是否成功写入向量库，不表示检索质量。
 * 由启动补建或定时任务重试，因此该字段允许为 false 或 NULL。
 */
public class KnowledgeDoc {
    // 状态字段只有 indexed，表示是否已完成向量索引。
    // 正文和标题在创建后一般不再修改。
    // 重新上传同一文档会创建新记录。

    @TableId(type = IdType.AUTO)
    /** 文档主键，数据库自增；同时作为向量 chunk 主键的派生输入。 */
    private Long id;

    /** 文档标题，默认取文件名；用于列表展示与检索结果来源标注。 */
    private String title;

    /** 原始文件名，保留扩展名用于展示与追溯。 */
    private String fileName;

    /** 解析后的完整纯文本，是切块与预览的数据源。 */
    private String content;

    /** 是否已成功建立向量索引；false 或 NULL 表示待索引。 */
    private Boolean indexed;

    /** 创建时间，列表默认按其倒序。 */
    private LocalDateTime createTime;

    /** 最近更新时间，索引状态变化也会刷新该字段。 */
    private LocalDateTime updateTime;
}
