package com.cdwater.toolshare.ai.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库文档表（向量本体在 Qdrant，本表管理上传记录）
 */
@Data
@TableName("kb_document")
public class KbDocument {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 文档名称 */
    private String fileName;

    /** 文档地址（MinIO） */
    private String fileUrl;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 切分片段数（解析完成后回填） */
    private Integer chunkCount;

    /** 解析状态：1解析中 2完成 3失败 */
    private Integer status;

    /** 上传人 user.id */
    private Long uploadBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
