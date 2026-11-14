package com.cdwater.toolshare.shared.enums;

import java.util.Optional;

public enum AcceptResult implements CodeLabel {

    QUALIFIED("QUALIFIED", "合格"),
    UNQUALIFIED("UNQUALIFIED", "不合格"),
    PARTIAL("PARTIAL", "部分合格");

    private final String code;
    private final String label;

    AcceptResult(String code, String label) {
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

    public static Optional<AcceptResult> fromCode(String code) {
        return CodeLabel.find(AcceptResult.class, code);
    }
}
