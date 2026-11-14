package com.cdwater.toolshare.module.review;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class ReviewCreateDTO {

    @NotNull(message = "租赁单不能为空")
    private Long rentalId;

    @NotNull(message = "评分不能为空")
    @Min(value = 1, message = "评分不能低于 1 星")
    @Max(value = 5, message = "评分不能高于 5 星")
    private Integer rating;

    @Size(max = 500, message = "评价内容不能超过 500 字")
    private String content;

    @Size(max = 3, message = "最多上传 3 张图片")
    private List<@Size(max = 500, message = "图片地址过长") String> images;
}
