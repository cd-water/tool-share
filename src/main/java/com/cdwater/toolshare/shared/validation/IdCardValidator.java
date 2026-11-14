package com.cdwater.toolshare.shared.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.util.StringUtils;

/**
 * 身份证校验位算法：GB 11643-1999。
 *
 * <p>校验收紧到 17 位数字 + 1 位数字或 X。前 17 位分别乘以固定权重，
 * 求和后对 11 取模，查表得到第 18 位校验码。X 表示数值 10，大小写
 * 均可接受。</p>
 *
 * <p>空值直接返回合法，与 {@link IdCard} 的“留空表示不修改”约定一致。
 * 校验器不访问数据库，也不做行政区划校验，因此是纯函数。</p>
 */
public class IdCardValidator implements ConstraintValidator<IdCard, String> {

    /** 前 17 位的加权因子，顺序固定。 */
    private static final int[] WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};

    /** 模 11 对应的校验码表，下标为 sum % 11。 */
    private static final char[] CHECK_CODES = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

    /**
     * 判断身份证号是否合法。
     *
     * @param value   待校验值，允许为 null 或空白
     * @param context 校验上下文，本实现不使用
     * @return 为空或校验位正确时返回 true
     */
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (!StringUtils.hasText(value)) {
            // 空值放行：便于“留空表示不修改”的更新语义。
            return true;
        }
        String id = value.trim();
        if (!id.matches("^\\d{17}[\\dXx]$")) {
            // 长度、字符集或最后一位格式不符。
            return false;
        }
        int sum = 0;
        for (int i = 0; i < WEIGHTS.length; i++) {
            // 字符 '0' 的 ASCII 码偏移得到数字值。
            sum += (id.charAt(i) - '0') * WEIGHTS[i];
        }
        // 第 18 位统一转大写后与查表结果比较。
        return Character.toUpperCase(id.charAt(17)) == CHECK_CODES[sum % 11];
    }
}
