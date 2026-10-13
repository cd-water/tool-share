package com.cdwater.toolshare.ai.vo;

import lombok.Data;

/**
 * RAG 知识库来源片段
 */
@Data
public class ChatSourceVO {

    /** 命中的知识库片段内容 */
    private String content;

    /** 来源文档名称 */
    private String documentName;
}
