package com.cdwater.toolshare.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class AvailabilityVO {

    private boolean available;

    private String message;

    private List<String> suggestions;
}
