package com.cdwater.toolshare.shared.enums;

import java.util.Optional;

/**
 * 类型安全的业务字典：数据库存 {@link #code()}，接口展示 {@link #label()}。
 */
public interface CodeLabel {

    String code();

    String label();

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
