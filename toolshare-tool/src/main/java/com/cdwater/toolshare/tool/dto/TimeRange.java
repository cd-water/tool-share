package com.cdwater.toolshare.tool.dto;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class TimeRange {

    private LocalDateTime start;

    private LocalDateTime end;
}
