package com.cdwater.toolshare.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 脱敏工具测试
 */
class DesensitizeUtilTest {

    @Test
    void maskPhone_keepsFirst3AndLast4() {
        assertEquals("138****6789", DesensitizeUtil.maskPhone("13812346789"));
    }

    @Test
    void maskPhone_returnsAsIsWhenNot11Digits() {
        assertEquals("12345", DesensitizeUtil.maskPhone("12345"));
        assertNull(DesensitizeUtil.maskPhone(null));
    }

    @Test
    void maskIdCard_keepsFirst6AndLast4() {
        assertEquals("310101********1234", DesensitizeUtil.maskIdCard("310101199001011234"));
    }

    @Test
    void maskIdCard_returnsAsIsWhenTooShort() {
        assertEquals("110101", DesensitizeUtil.maskIdCard("110101"));
        assertNull(DesensitizeUtil.maskIdCard(null));
    }
}
