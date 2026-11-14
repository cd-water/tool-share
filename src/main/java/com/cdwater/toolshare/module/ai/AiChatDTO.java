package com.cdwater.toolshare.module.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AiChatDTO {

    @NotBlank(message = "请输入问题")
    @Size(max = 500, message = "问题不能超过 500 个字符")
    private String question;
}
