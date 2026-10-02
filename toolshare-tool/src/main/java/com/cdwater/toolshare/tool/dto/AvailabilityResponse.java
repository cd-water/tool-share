package com.cdwater.toolshare.tool.dto;

import java.time.LocalDate;
import java.util.List;
import lombok.Data;

@Data
public class AvailabilityResponse {

    private LocalDate date;

    private List<TimeRange> busy;
}
