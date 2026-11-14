package com.cdwater.toolshare.shared.enums;

import java.util.Optional;

/**
 * 类型安全的业务字典：数据库存 {@link #code()}，接口展示 {@link #label()}。
 *
 * <p>所有业务枚举都实现本接口，从而复用统一的反查逻辑与字典构建逻辑
 * （见 {@code Dict}）。这样新增枚举时无需重复编写 fromCode 样板代码，
 * 也能保证代码与文案的对应关系集中在一个枚举内。</p>
 */
public interface CodeLabel {

    /**
     * 返回持久化代码。
     *
     * @return 写入数据库、参与接口传输的稳定代码
     */
    String code();

    /**
     * 返回中文展示名称。
     *
     * @return 面向最终用户的中文文案
     */
    String label();

    /**
     * 在指定枚举类型中按代码查找常量。
     *
     * <p>采用线性扫描：业务枚举规模很小（通常不超过十几个常量），
     * 线性查找比维护静态索引更简单且不会出现索引与常量失同步。</p>
     *
     * @param type 目标枚举类型，必须同时实现 {@link CodeLabel}
     * @param code 待查找的代码，允许为 null
     * @param <E>  枚举与字典接口的交集类型
     * @return 匹配的枚举常量；代码为空或没有匹配项时返回 {@link Optional#empty()}
     */
    static <E extends Enum<E> & CodeLabel> Optional<E> find(Class<E> type, String code) {
        if (code == null) {
            return Optional.empty();
        }
        for (E value : type.getEnumConstants()) {
            if (value.code().equals(code)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }
}
