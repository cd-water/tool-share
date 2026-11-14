package com.cdwater.toolshare.shared.enums;

import java.util.Optional;

public enum DamageLevel implements CodeLabel {

    NEW("NEW", "全新"),
    LIGHT("LIGHT", "轻微磨损"),
    MEDIUM("MEDIUM", "中度磨损"),
    SEVERE("SEVERE", "严重损坏");

    private final String code;
    private final String label;

    DamageLevel(String code, String label) {
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

    public static Optional<DamageLevel> fromCode(String code) {
        return CodeLabel.find(DamageLevel.class, code);
    }
}
