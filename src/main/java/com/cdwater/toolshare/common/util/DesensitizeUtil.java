package com.cdwater.toolshare.common.util;

/**
 * 脱敏工具（展示用，格式对齐 PRD 附录）
 */
public final class DesensitizeUtil {

    private DesensitizeUtil() {
    }

    /**
     * 手机号脱敏：保留前 3 后 4，如 138****6789
     */
    public static String maskPhone(String phone) {
        if (phone == null || phone.length() != 11) {
            return phone;
        }
        return phone.replaceAll("(\\d{3})\\d{4}(\\d{4})", "$1****$2");
    }

    /**
     * 身份证号脱敏：保留前 6 后 4，中间打码，如 310101********1234
     */
    public static String maskIdCard(String idCard) {
        if (idCard == null || idCard.length() < 11) {
            return idCard;
        }
        return idCard.substring(0, 6) + "*".repeat(idCard.length() - 10) + idCard.substring(idCard.length() - 4);
    }
}
