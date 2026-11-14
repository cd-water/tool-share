package com.cdwater.toolshare.shared.enums;

import java.util.Optional;

/**
 * 报修核验结果字典。
 *
 * <p>管理员对报修单进行核验后给出结论：确认损坏则进入维修，疑似损坏可
 * 继续观察或补充材料，误报则驳回。该结果只描述核验判断，不直接等价于
 * 报修单最终状态。</p>
 */
public enum VerifyResult implements CodeLabel {

    /**
     * 确认损坏：证据充分，报修单可进入维修流程。
     */
    CONFIRMED("CONFIRMED", "确认损坏"),
    /**
     * 疑似损坏：存在疑点，需要补充信息或进一步检查。
     */
    SUSPECTED("SUSPECTED", "疑似损坏"),
    /**
     * 误报：经核验不存在需要处理的损坏，报修单可驳回。
     */
    FALSE("FALSE", "误报");

    /** 核验记录中持久化的英文代码。 */
    private final String code;

    /** 管理端展示的中文核验结论。 */
    private final String label;

    /**
     * 构造核验结果。
     *
     * @param code  英文代码
     * @param label 中文显示名
     */
    VerifyResult(String code, String label) {
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
     * 返回中文核验结论。
     *
     * @return 展示名称
     */
    @Override
    public String label() {
        return label;
    }

    /**
     * 按代码反查核验结果。
     *
     * @param code 英文代码
     * @return 匹配项；代码为空或未知时返回 {@link Optional#empty()}
     */
    public static Optional<VerifyResult> fromCode(String code) {
        return CodeLabel.find(VerifyResult.class, code);
    }
}
