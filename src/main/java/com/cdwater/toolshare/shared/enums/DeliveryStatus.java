package com.cdwater.toolshare.shared.enums;

import java.util.Optional;

public enum DeliveryStatus implements CodeLabel {

    PENDING("PENDING", "待发送"),
    SENT("SENT", "已发送"),
    DELIVERED("DELIVERED", "已送达"),
    FAILED("FAILED", "发送失败");

    private final String code;
    private final String label;

    DeliveryStatus(String code, String label) {
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

    public static Optional<DeliveryStatus> fromCode(String code) {
        return CodeLabel.find(DeliveryStatus.class, code);
    }
}
