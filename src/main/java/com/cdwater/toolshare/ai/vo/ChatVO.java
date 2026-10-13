package com.cdwater.toolshare.ai.vo;

import lombok.Data;

import java.util.List;

/**
 * AI 问答出参
 */
@Data
public class ChatVO {

    /** 回答内容 */
    private String answer;

    /** 知识库来源片段 */
    private List<ChatSourceVO> sources;
}
