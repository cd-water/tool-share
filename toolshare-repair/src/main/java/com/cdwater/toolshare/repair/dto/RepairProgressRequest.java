package com.cdwater.toolshare.repair.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RepairProgressRequest {

    @NotBlank(message = "进度说明不能为空")
    private String content;
}
