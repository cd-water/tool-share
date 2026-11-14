package com.cdwater.toolshare.module.review;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ReviewVO {

    private Long id;
    private Long rentalId;
    private Long toolId;
    private String toolName;
    private Long userId;
    private String userNickname;
    private Integer rating;
    private String content;
    private List<String> images;
    private LocalDateTime createTime;
}
