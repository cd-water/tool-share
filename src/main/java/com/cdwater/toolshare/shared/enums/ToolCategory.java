package com.cdwater.toolshare.shared.enums;

import java.util.Optional;

public enum ToolCategory implements CodeLabel {

    REPAIR("WX", "维修工具"),
    GARDEN("YY", "园艺工具"),
    CLEANING("QJ", "清洁工具"),
    OTHER("QT", "其他工具");

    private final String code;
    private final String label;

    ToolCategory(String code, String label) {
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

    public static Optional<ToolCategory> fromCode(String code) {
        return CodeLabel.find(ToolCategory.class, code);
    }
}
