package com.cdwater.toolshare.shared.enums;

import java.util.Optional;

public enum ReportStatus implements CodeLabel {

    SUBMITTED("SUBMITTED", "已提交"),
    VERIFIED("VERIFIED", "已核验"),
    REJECTED("REJECTED", "已驳回"),
    REPAIRING("REPAIRING", "维修中"),
    COMPLETED("COMPLETED", "已完成"),
    SCRAPPED("SCRAPPED", "已报废");

    private final String code;
    private final String label;

    ReportStatus(String code, String label) {
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

    public static Optional<ReportStatus> fromCode(String code) {
        return CodeLabel.find(ReportStatus.class, code);
    }
}
