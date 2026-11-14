package com.cdwater.toolshare.shared.enums;

import java.util.Optional;

public enum ToolStatus implements CodeLabel {

    AVAILABLE("AVAILABLE", "可借用"),
    RENTED("RENTED", "已租出"),
    MAINTENANCE("MAINTENANCE", "维修中"),
    SCRAPPED("SCRAPPED", "已报废");

    private final String code;
    private final String label;

    ToolStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String label() {
        return label;
    }

    public static Optional<ToolStatus> fromCode(String code) {
        return CodeLabel.find(ToolStatus.class, code);
    }
}
