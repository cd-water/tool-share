package com.cdwater.toolshare.shared.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdCardCipherTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef";
    private static final String ID = "310101199001011234";

    /** 由 openssl AES-256-ECB/PKCS7 独立生成的旧格式密文（密钥即 KEY 的原始字节） */
    private static final String LEGACY =
            "enc:YErWaQle6y+o5klzLHIaMwENDTTb0Y6fOP8unFzDUYY=";

    private final IdCardCipher cipher = new IdCardCipher(KEY);

    @Test
    void encrypts_with_versioned_prefix_and_round_trips() {
        String encrypted = cipher.encrypt(ID);
        assertTrue(encrypted.startsWith("enc:v2:"), encrypted);
        assertEquals(ID, cipher.decrypt(encrypted));
    }

    @Test
    void random_iv_produces_different_ciphertexts_for_same_plaintext() {
        String a = cipher.encrypt(ID);
        String b = cipher.encrypt(ID);
        assertNotEquals(a, b, "随机 IV 应使同一明文的密文不同");
        assertEquals(ID, cipher.decrypt(a));
        assertEquals(ID, cipher.decrypt(b));
    }

    @Test
    void decrypts_legacy_ecb_ciphertext_and_plaintext() {
        assertEquals(ID, cipher.decrypt(LEGACY), "历史 enc: 密文应可解密");
        assertEquals(ID, cipher.decrypt(ID), "历史明文应原样返回");
    }

    @Test
    void encrypt_upgrades_legacy_ciphertext_to_v2() {
        String upgraded = cipher.encrypt(LEGACY);
        assertTrue(upgraded.startsWith("enc:v2:"));
        assertEquals(ID, cipher.decrypt(upgraded));
    }

    @Test
    void encrypt_is_idempotent_for_v2() {
        String encrypted = cipher.encrypt(ID);
        assertEquals(encrypted, cipher.encrypt(encrypted));
    }

    @Test
    void blank_values_return_null() {
        assertNull(cipher.encrypt(null));
        assertNull(cipher.encrypt("  "));
        assertNull(cipher.decrypt(null));
        assertNull(cipher.decrypt("  "));
    }

    @Test
    void refuses_missing_or_invalid_key_at_startup() {
        assertThrows(IllegalStateException.class, () -> new IdCardCipher(""));
        assertThrows(IllegalStateException.class, () -> new IdCardCipher("  "));
        assertThrows(IllegalStateException.class, () -> new IdCardCipher("too-short"));
    }

    @Test
    void mask_works_for_v2_ciphertext() {
        assertEquals("3101********1234", cipher.mask(cipher.encrypt(ID)));
    }
}
