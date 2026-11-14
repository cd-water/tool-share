package com.cdwater.toolshare.shared.enums;

import java.util.Optional;

public enum RepairProgress implements CodeLabel {

    WAITING_PARTS("WAITING_PARTS", "等待配件"),
    REPAIRING("REPAIRING", "维修中"),
    COMPLETED("COMPLETED", "维修完成"),
    FAILED("FAILED", "维修失败");

    private final String code;
    private final String label;

    RepairProgress(String code, String label) {
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

    public static Optional<RepairProgress> fromCode(String code) {
        return CodeLabel.find(RepairProgress.class, code);
    }
}
