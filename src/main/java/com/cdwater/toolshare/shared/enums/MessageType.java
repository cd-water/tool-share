package com.cdwater.toolshare.shared.enums;

import java.util.Optional;

public enum MessageType implements CodeLabel {

    RESERVATION("RESERVATION", "预约通知"),
    PICKUP("PICKUP", "取件提醒"),
    RENT_DUE("RENT_DUE", "到期提醒"),
    OVERDUE("OVERDUE", "逾期提醒"),
    REPAIR("REPAIR", "报修通知"),
    TOOL_AVAILABLE("TOOL_AVAILABLE", "蹲守到货提醒"),
    SYSTEM("SYSTEM", "系统消息");

    private final String code;
    private final String label;

    MessageType(String code, String label) {
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

    public static Optional<MessageType> fromCode(String code) {
        return CodeLabel.find(MessageType.class, code);
    }
}
