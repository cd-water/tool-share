package com.cdwater.toolshare.shared.enums;

import java.util.Optional;

/**
 * 报修单状态字典。
 *
 * <p>覆盖报修单从提交到完结的全生命周期：提交后由管理员核验，核验不通过
 * 则驳回；通过后进入维修，最终完成或报废。看板的“待处理报修”只统计
 * 已提交、已核验与维修中三个活动状态。</p>
 */
public enum ReportStatus implements CodeLabel {

    /**
     * 已提交：用户或管理员刚创建报修单，等待核验。
     */
    SUBMITTED("SUBMITTED", "已提交"),
    /**
     * 已核验：管理员确认损坏属实，可安排维修。
     */
    VERIFIED("VERIFIED", "已核验"),
    /**
     * 已驳回：核验认为无需报修或信息不足，流程终止。
     */
    REJECTED("REJECTED", "已驳回"),
    /**
     * 维修中：已进入维修作业，具体进度见 {@link RepairProgress}。
     */
    REPAIRING("REPAIRING", "维修中"),
    /**
     * 已完成：维修结束且工具恢复可用，报修单归档。
     */
    COMPLETED("COMPLETED", "已完成"),
    /**
     * 已报废：无法修复，工具退出租赁台账。
     */
    SCRAPPED("SCRAPPED", "已报废");

    /** 报修表中持久化的英文状态码。 */
    private final String code;

    /** 管理端与用户端展示的中文状态。 */
    private final String label;

    /**
     * 构造报修状态。
     *
     * @param code  英文状态码
     * @param label 中文显示名
     */
    ReportStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /**
     * 返回英文状态码。
     *
     * @return 持久化代码
     */
    @Override
    public String code() {
        return code;
    }

    /**
     * 返回中文状态名。
     *
     * @return 展示名称
     */
    @Override
    public String label() {
        return label;
    }

    /**
     * 按状态码反查枚举。
     *
     * @param code 英文状态码
     * @return 匹配项；代码为空或未知时返回 {@link Optional#empty()}
     */
    public static Optional<ReportStatus> fromCode(String code) {
        return CodeLabel.find(ReportStatus.class, code);
    }
}
