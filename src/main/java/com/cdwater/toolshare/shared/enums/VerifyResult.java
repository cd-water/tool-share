package com.cdwater.toolshare.shared.enums;

import java.util.Optional;

public enum VerifyResult implements CodeLabel {

    CONFIRMED("CONFIRMED", "确认损坏"),
    SUSPECTED("SUSPECTED", "疑似损坏"),
    FALSE("FALSE", "误报");

    private final String code;
    private final String label;

    VerifyResult(String code, String label) {
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

    public static Optional<VerifyResult> fromCode(String code) {
        return CodeLabel.find(VerifyResult.class, code);
    }
}
