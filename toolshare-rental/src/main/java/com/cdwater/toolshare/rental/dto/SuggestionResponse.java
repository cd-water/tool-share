package com.cdwater.toolshare.rental.dto;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 冲突后的最近可约时段推荐
 */
@Data
public class SuggestionResponse {

    private boolean suggested;

    private LocalDateTime startTime;

    private LocalDateTime endTime;
}
