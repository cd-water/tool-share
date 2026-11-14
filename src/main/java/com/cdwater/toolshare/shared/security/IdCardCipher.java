package com.cdwater.toolshare.shared.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 身份证加解密与脱敏。
 *
 * <p>新数据使用 AES/GCM + 随机 IV，格式 {@code enc:v2:base64(iv || ciphertext+tag)}；
 * 解密兼容历史 {@code enc:}（AES/ECB）密文与明文，便于存量数据平滑迁移。</p>
 */
@Service
public class IdCardCipher {

    private static final String V2_PREFIX = "enc:v2:";
    private static final String LEGACY_PREFIX = "enc:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public IdCardCipher(@Value("${security.id-card-key}") String key) {
        if (!StringUtils.hasText(key)) {
            throw new IllegalStateException("security.id-card-key 未配置，请通过 ID_CARD_AES_KEY 注入");
        }
        byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
        if (bytes.length != 16 && bytes.length != 24 && bytes.length != 32) {
            throw new IllegalStateException("security.id-card-key 长度必须为 16/24/32 字节，当前为 " + bytes.length);
        }
        this.key = new SecretKeySpec(bytes, "AES");
    }

    /** 加密明文；已是 v2 密文时原样返回，历史 enc: 密文会升级为 v2。 */
    public String encrypt(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String value = raw.trim();
        if (value.startsWith(V2_PREFIX)) {
            return value;
        }
        if (value.startsWith(LEGACY_PREFIX)) {
            String plain = decryptLegacy(value.substring(LEGACY_PREFIX.length()));
            return plain == null ? value : encryptV2(plain);
        }
        return encryptV2(value);
    }

    /** 解密 v2 / 历史 enc: 密文；无前缀的历史明文原样返回。 */
    public String decrypt(String stored) {
        if (!StringUtils.hasText(stored)) {
            return null;
        }
        String value = stored.trim();
        if (value.startsWith(V2_PREFIX)) {
            return decryptV2(value.substring(V2_PREFIX.length()));
        }
        if (value.startsWith(LEGACY_PREFIX)) {
            return decryptLegacy(value.substring(LEGACY_PREFIX.length()));
        }
        return value;
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

    private String encryptV2(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return V2_PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("身份证加密失败", e);
        }
    }

    private String decryptV2(String payload) {
        try {
            byte[] bytes = Base64.getDecoder().decode(payload);
            if (bytes.length <= IV_BYTES) {
                return null;
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(bytes, IV_BYTES, bytes.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** 历史格式：AES/ECB/PKCS5Padding，密钥直接使用配置的原始字节。 */
    private String decryptLegacy(String payload) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, key);
            byte[] plain = cipher.doFinal(Base64.getDecoder().decode(payload));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
