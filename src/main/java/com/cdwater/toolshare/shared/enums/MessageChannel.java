package com.cdwater.toolshare.shared.enums;

import java.util.Optional;

public enum MessageChannel implements CodeLabel {

    SYSTEM("SYSTEM", "站内消息"),
    SMS("SMS", "Mock 短信");

    private final String code;
    private final String label;

    MessageChannel(String code, String label) {
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

    public static Optional<MessageChannel> fromCode(String code) {
        return CodeLabel.find(MessageChannel.class, code);
    }
}
