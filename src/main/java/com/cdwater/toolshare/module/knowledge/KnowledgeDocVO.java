package com.cdwater.toolshare.module.knowledge;

import lombok.Data;

import java.time.LocalDateTime;

@Data
/**
 * 知识库文档视图对象。
 * <p>列表接口不返回完整正文，只提供截断后的 contentPreview，避免大文档
 * <p>indexed=false 的文档仍会出现在列表里，便于管理员发现索引异常。
 * <p>预览已压缩空白并截断，适合列表展示；完整正文仍保存在数据库中。
 * 撑大响应；indexed 让管理端可以直接看出哪些文档尚未建立索引。
 */
public class KnowledgeDocVO {

    /** 文档主键。 */
    private Long id;
    /** 文档标题。 */
    private String title;
    /** 原始文件名。 */
    private String fileName;
    /** 正文预览：压缩空白后截断到 120 个字符，超出部分以省略号结尾。 */
    private String contentPreview;
    /** 是否已建立向量索引。 */
    private Boolean indexed;
    /** 创建时间。 */
    private LocalDateTime createTime;
}
