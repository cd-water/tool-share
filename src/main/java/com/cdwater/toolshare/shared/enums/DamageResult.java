package com.cdwater.toolshare.shared.enums;

import java.util.Optional;

public enum DamageResult implements CodeLabel {

    INTACT("INTACT", "完好"),
    MINOR("MINOR", "轻微损坏"),
    SEVERE("SEVERE", "严重损坏");

    private final String code;
    private final String label;

    DamageResult(String code, String label) {
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

    public static Optional<DamageResult> fromCode(String code) {
        return CodeLabel.find(DamageResult.class, code);
    }
}
