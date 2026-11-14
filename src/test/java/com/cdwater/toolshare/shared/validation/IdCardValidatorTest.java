package com.cdwater.toolshare.shared.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdCardValidatorTest {

    private final IdCardValidator validator = new IdCardValidator();

    @Test
    void accepts_valid_checksum_and_lowercase_x() {
        assertTrue(validator.isValid("310101199001011234", null));
        assertTrue(validator.isValid("11010519491231002X", null), "大写 X 校验位应合法");
        assertTrue(validator.isValid("11010519491231002x", null), "小写 x 校验位应合法");
        assertTrue(validator.isValid(" 310101199001011234 ", null), "首尾空白应忽略");
    }

    @Test
    void blank_is_valid_so_optional_field_can_be_omitted() {
        assertTrue(validator.isValid(null, null));
        assertTrue(validator.isValid("", null));
        assertTrue(validator.isValid("   ", null));
    }

    @Test
    void rejects_wrong_checksum_length_and_format() {
        assertFalse(validator.isValid("310101199001011235", null), "校验位错误");
        assertFalse(validator.isValid("31010119900101123", null), "17 位");
        assertFalse(validator.isValid("3101011990010112345", null), "19 位");
        assertFalse(validator.isValid("31010119900101123A", null), "非法字符");
        assertFalse(validator.isValid("31010119900101123X", null), "大写 X 校验位错误");
    }
}
