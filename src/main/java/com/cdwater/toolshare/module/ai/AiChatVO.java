package com.cdwater.toolshare.module.ai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
public class AiChatVO {

    private String answer;

    private List<SourceVO> sources;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SourceVO {
        private String title;
        private String snippet;
    }
}
