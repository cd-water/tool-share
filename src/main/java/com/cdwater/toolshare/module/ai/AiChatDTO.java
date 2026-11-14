package com.cdwater.toolshare.module.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
/**
 * 居民知识库问答的请求参数。
 * <p>问题会直接作为向量检索的 query，并拼接到大模型 prompt 中。
 * 长度上限 500 字符，既控制检索开销，也降低提示词注入与超长上下文
 * <p>空问题在进入检索前就会被校验拦截，避免无意义的外部调用。
 * <p>问题长度上限与提示词预算共同控制单次问答的成本。
 * 带来的成本风险；真正的输入清洗与来源约束由服务层负责。
 */
public class AiChatDTO {

    /**
     * 居民问题，不能为空，最多 500 个字符。
     * <p>服务端会基于该文本检索知识库片段，并在回答中附上来源。
     */
    @NotBlank(message = "请输入问题")
    @Size(max = 500, message = "问题不能超过 500 个字符")
    private String question;
}
