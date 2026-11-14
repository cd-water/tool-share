package com.cdwater.toolshare.shared.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.util.StringUtils;

/**
 * 身份证校验位算法：GB 11643-1999。
 */
public class IdCardValidator implements ConstraintValidator<IdCard, String> {

    private static final int[] WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final char[] CHECK_CODES = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (!StringUtils.hasText(value)) {
            return true;
        }
        String id = value.trim();
        if (!id.matches("^\\d{17}[\\dXx]$")) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < WEIGHTS.length; i++) {
            sum += (id.charAt(i) - '0') * WEIGHTS[i];
        }
        return Character.toUpperCase(id.charAt(17)) == CHECK_CODES[sum % 11];
    }
}
