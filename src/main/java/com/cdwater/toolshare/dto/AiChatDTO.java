package com.cdwater.toolshare.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AiChatDTO {

    @NotBlank(message = "请输入问题")
    private String question;
}
