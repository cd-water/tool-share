package com.cdwater.toolshare.assistant.dto;

import java.util.List;
import lombok.Data;

@Data
public class ChatResponse {

    private String answer;

    private List<SourceItem> sources;
}
