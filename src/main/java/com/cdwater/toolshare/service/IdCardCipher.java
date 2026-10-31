package com.cdwater.toolshare.service;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.symmetric.AES;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 身份证 AES 加解密与脱敏。历史明文数据兼容读取。
 */
@Service
public class IdCardCipher {

    private static final String PREFIX = "enc:";
    private final AES aes;

    public IdCardCipher(@Value("${security.id-card-key}") String key) {
        this.aes = SecureUtil.aes(key.getBytes(StandardCharsets.UTF_8));
    }

    public String encrypt(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String value = raw.trim();
        if (value.startsWith(PREFIX)) {
            return value;
        }
        return PREFIX + Base64.getEncoder().encodeToString(aes.encrypt(value));
    }

    public String decrypt(String stored) {
        if (!StringUtils.hasText(stored)) {
            return null;
        }
        if (!stored.startsWith(PREFIX)) {
            return stored;
        }
        try {
            return aes.decryptStr(stored.substring(PREFIX.length()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    public String mask(String stored) {
        String raw = decrypt(stored);
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        if (raw.length() <= 8) {
            return "****";
        }
        return raw.substring(0, 4) + "********" + raw.substring(raw.length() - 4);
    }

    public static String maskPhone(String phone) {
        if (!StringUtils.hasText(phone) || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
