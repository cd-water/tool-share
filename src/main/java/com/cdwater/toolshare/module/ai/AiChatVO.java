package com.cdwater.toolshare.module.ai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
/**
 * 知识库问答的响应对象。
 * <p>answer 是最终回答，可能来自大模型，也可能是知识库兜底文案；
 * sources 是本次检索命中的知识片段，前端可在回答下方展示参考来源。
 * <p>answer 在服务层保证非空：模型无输出时使用知识库兜底文案。
 * sources 最多 4 条，按向量相似度或关键词得分排序。
 * 两者一一对应：回答中的 [1]、[2] 编号即 sources 的数组下标加一。
 */
public class AiChatVO {

    /** 最终回答文本，保证非空；大模型不可用时为知识库兜底文案。 */
    private String answer;

    /** 本次检索命中的知识来源，最多 4 条，按相关性排序。 */
    /* 来源片段使用纯文本，不包含 HTML 标签；前端负责安全渲染。
     * 来源片段可能以省略号开头或结尾，表示正文被截断。
     * 回答与来源都在服务层保证非空，前端无需处理空数组以外的异常情况。
     * 来源数量上限来自 AiService.MAX_SOURCES，避免回答下方列表过长。
     */
    private List<SourceVO> sources;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    /**
     * 单条知识来源。
     * <p>title 来自文档标题元数据，snippet 是围绕命中关键词截取的正文片段；
     * 片段长度受限，避免把整篇文档返回给前端。
     */
    public static class SourceVO {
        /** 来源文档标题。 */
        private String title;
        /** 围绕命中词截取的正文片段，可能以省略号开头或结尾。 */
        private String snippet;
    }
}
