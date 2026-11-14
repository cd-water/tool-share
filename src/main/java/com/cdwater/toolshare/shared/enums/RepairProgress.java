package com.cdwater.toolshare.shared.enums;

import java.util.Optional;

/**
 * 维修进度字典。
 *
 * <p>用于描述报修单进入维修阶段后的内部进度，与 {@link ReportStatus}
 * 的区别在于：ReportStatus 覆盖报修单全生命周期，RepairProgress 只关注
 * 维修作业本身，便于管理员在不改变报修单主状态的前提下更新维修细节。</p>
 */
public enum RepairProgress implements CodeLabel {

    /**
     * 等待配件：已确认维修方案，但缺少更换部件，暂不能开工。
     */
    WAITING_PARTS("WAITING_PARTS", "等待配件"),
    /**
     * 维修中：配件齐备，正在执行维修作业。
     */
    REPAIRING("REPAIRING", "维修中"),
    /**
     * 维修完成：工具修复，可转入验收或恢复可借用状态。
     */
    COMPLETED("COMPLETED", "维修完成"),
    /**
     * 维修失败：无法修复，需评估报废或另行处理。
     */
    FAILED("FAILED", "维修失败");

    /** 维修记录中持久化的英文代码。 */
    private final String code;

    /** 管理端展示的中文进度名。 */
    private final String label;

    /**
     * 构造维修进度。
     *
     * @param code  英文代码
     * @param label 中文显示名
     */
    RepairProgress(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /**
     * 返回英文代码。
     *
     * @return 持久化代码
     */
    @Override
    public String code() {
        return code;
    }

    /**
     * 返回中文进度名。
     *
     * @return 展示名称
     */
    @Override
    public String label() {
        return label;
    }

    /**
     * 按代码反查维修进度。
     *
     * @param code 英文代码
     * @return 匹配项；代码为空或未知时返回 {@link Optional#empty()}
     */
    public static Optional<RepairProgress> fromCode(String code) {
        return CodeLabel.find(RepairProgress.class, code);
    }
}
