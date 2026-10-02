package com.cdwater.toolshare.tool.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

@Data
public class ToolImageRequest {

    @NotEmpty(message = "图片 URL 不能为空")
    @Size(max = 5, message = "最多 5 张图片")
    private List<String> urls;
}
