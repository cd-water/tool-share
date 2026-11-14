package com.cdwater.toolshare.shared.enums;

import java.util.Optional;

/**
 * 归还验收结果字典。
 *
 * <p>描述租赁工具归还时的整单验收结论：合格可直接入库，部分合格需要结合
 * 损坏明细继续处理，不合格通常进入报修或赔偿流程。数据库与接口统一使用
 * {@link #code()}，管理端展示使用 {@link #label()}，二者由 {@link CodeLabel}
 * 的约定保证一一对应。</p>
 */
public enum AcceptResult implements CodeLabel {

    /**
     * 验收通过：工具状态可以恢复为“可借用”，不产生赔偿或维修工单。
     */
    QUALIFIED("QUALIFIED", "合格"),
    /**
     * 验收未通过：存在影响再次出租的损坏或缺失，需要维修或赔偿处理。
     */
    UNQUALIFIED("UNQUALIFIED", "不合格"),
    /**
     * 部分合格：整单既有合格项也有问题项，需结合损坏明细与费用流水结算。
     */
    PARTIAL("PARTIAL", "部分合格");

    /** 持久化到数据库、并在接口中传输的英文代码。 */
    private final String code;

    /** 接口返回给前端的中文名称，仅用于展示，不参与判断。 */
    private final String label;

    /**
     * 构造验收结果常量。
     *
     * @param code  英文代码，需在枚举内唯一
     * @param label 中文显示名
     */
    AcceptResult(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /**
     * 返回英文代码，用于数据库存储与接口传参。
     *
     * @return 该常量的英文代码
     */
    @Override
    public String code() {
        return code;
    }

    /**
     * 返回中文名称，用于管理端与用户端展示。
     *
     * @return 该常量的中文显示名
     */
    @Override
    public String label() {
        return label;
    }

    /**
     * 按英文代码反查枚举。
     *
     * @param code 数据库或请求中的英文代码
     * @return 匹配的枚举；代码为空或未知时返回 {@link Optional#empty()}
     */
    public static Optional<AcceptResult> fromCode(String code) {
        return CodeLabel.find(AcceptResult.class, code);
    }
}
