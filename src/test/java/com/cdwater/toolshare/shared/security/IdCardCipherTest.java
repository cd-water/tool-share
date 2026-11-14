package com.cdwater.toolshare.shared.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 身份证加解密工具测试。
 * <p>
 * 测试范围：AES 加密格式、随机 IV、历史 ECB 密文兼容、幂等与掩码展示。
 * 依赖环境：固定测试密钥，不访问数据库或密钥管理服务。
 * 关键不变量：新密文带 enc:v2: 前缀且可逆；同一明文每次密文不同；
 * 历史 enc: 密文与明文仍可读取；缺失或非法密钥启动即失败。
 */
class IdCardCipherTest {

    /** 测试专用 32 字节密钥，仅用于验证算法行为，不代表生产密钥。 */
    private static final String KEY = "0123456789abcdef0123456789abcdef";
    private static final String ID = "310101199001011234";

    /** 由 openssl AES-256-ECB/PKCS7 独立生成的旧格式密文（密钥即 KEY 的原始字节） */
    private static final String LEGACY =
            "enc:YErWaQle6y+o5klzLHIaMwENDTTb0Y6fOP8unFzDUYY=";

    /** 每个用例共享的加密器实例，内部无状态。 */
    private final IdCardCipher cipher = new IdCardCipher(KEY);

    /**
     * 场景：加密一个合法身份证号后再解密。
     * 前置条件：使用 32 字节测试密钥构造 cipher。
     * 断言目标：密文带 enc:v2: 版本前缀，解密结果与原文完全一致。
     */
    @Test
    void encrypts_with_versioned_prefix_and_round_trips() {
        // 先生成一份合法的 v2 密文，再将其作为 encrypt 的输入。
        // 用于验证加密入口对自身格式的幂等处理。
        // 对合法身份证号执行 v2 加密，返回值应自带版本信息，便于后续路由解密逻辑。
        // 加密与解密使用同一测试密钥，验证算法闭环而非密钥管理。
        String encrypted = cipher.encrypt(ID);
        // enc:v2: 前缀用于区分历史 ECB 密文与当前 AES-GCM/随机 IV 格式。
        // 前缀缺失会导致读取时无法选择正确的解密分支。
        // 断言值同时作为失败消息输出，便于直接看到实际密文格式。
        assertTrue(encrypted.startsWith("enc:v2:"), encrypted);
        // 往返一致是最核心的不变量：任何格式升级都必须保证可逆。
        assertEquals(ID, cipher.decrypt(encrypted));
    }

    /**
     * 场景：对同一明文连续加密两次。
     * 前置条件：v2 格式每次生成随机 IV。
     * 断言目标：两次密文不同，但都能解回同一明文，降低密文比对泄露风险。
     */
    @Test
    void random_iv_produces_different_ciphertexts_for_same_plaintext() {
        // 连续两次加密同一明文，用于验证随机 IV 是否生效。
        // 若实现退化为固定 IV，两次结果会完全相同。
        String a = cipher.encrypt(ID);
        // 第二次加密独立生成随机数，不依赖第一次的结果。
        String b = cipher.encrypt(ID);
        // 密文不同可降低通过比对密文推断身份证号的风险。
        // 断言失败信息直接说明安全目标，便于排障。
        assertNotEquals(a, b, "随机 IV 应使同一明文的密文不同");
        // 两次加密的结果都必须能解回原文。
        assertEquals(ID, cipher.decrypt(a));
        // 随机 IV 只影响密文外观，不影响可逆性。
        assertEquals(ID, cipher.decrypt(b));
    }

    /**
     * 场景：读取 openssl 生成的旧版 enc: 密文与历史明文。
     * 前置条件：兼容逻辑仍支持旧 ECB 格式。
     * 断言目标：旧密文可解密、旧明文原样返回，保证已有数据无需回填即可使用。
     */
    @Test
    void decrypts_legacy_ecb_ciphertext_and_plaintext() {
        // 历史数据以 enc: 前缀且无版本号存储，读取逻辑需保留旧算法分支。
        // 保证上线新格式后无需回填历史身份证数据。
        assertEquals(ID, cipher.decrypt(LEGACY), "历史 enc: 密文应可解密");
        // 更早的数据可能仍是明文，解密入口应识别并直接返回。
        assertEquals(ID, cipher.decrypt(ID), "历史明文应原样返回");
    }

    /**
     * 场景：把旧版 enc: 密文再次交给 encrypt。
     * 前置条件：输入是合法旧密文而非明文。
     * 断言目标：先解密再以 v2 重新加密，实现读取时的渐进升级。
     */
    @Test
    void encrypt_upgrades_legacy_ciphertext_to_v2() {
        // 把旧密文再次交给 encrypt，应理解为「读取并升级」而非二次加密。
        // 这样可以在更新资料时渐进迁移到 v2。
        String upgraded = cipher.encrypt(LEGACY);
        // 升级结果必须是当前版本格式。
        assertTrue(upgraded.startsWith("enc:v2:"));
        // 升级后仍可解回原文，迁移不改变业务数据。
        assertEquals(ID, cipher.decrypt(upgraded));
    }

    /**
     * 场景：对已是 v2 的密文再次加密。
     * 前置条件：encrypt 需识别自身格式。
     * 断言目标：返回原文不变，重复调用不会二次加密导致无法解密。
     */
    @Test
    void encrypt_is_idempotent_for_v2() {
        String encrypted = cipher.encrypt(ID);
        // 识别到已是 v2 时原样返回，避免二次加密导致无法解密。
        // 幂等性让调用方可以安全地重复执行加密流程。
        assertEquals(encrypted, cipher.encrypt(encrypted));
    }

    /**
     * 场景：加密或解密 null、纯空白字符串。
     * 前置条件：身份证字段允许为空。
     * 断言目标：统一返回 null，不抛异常，避免可选字段影响主流程。
     */
    @Test
    void blank_values_return_null() {
        // 身份证是可选字段，null 不应抛异常。
        assertNull(cipher.encrypt(null));
        // 纯空白等价于未填写，返回 null 便于数据库存空值。
        assertNull(cipher.encrypt("  "));
        // 解密侧同样对空值保持宽容，避免读历史数据时报错。
        assertNull(cipher.decrypt(null));
        // 空白密文视为无值。
        assertNull(cipher.decrypt("  "));
    }

    /**
     * 场景：用空串、空白与过短字符串作为密钥构造 cipher。
     * 前置条件：密钥必须为 32 字节。
     * 断言目标：构造阶段直接抛 IllegalStateException，阻止弱密钥上线。
     */
    @Test
    void refuses_missing_or_invalid_key_at_startup() {
        // 空密钥无法提供任何安全性，构造阶段直接失败。
        // 以下三个样例覆盖空串、空白与长度不足三类弱密钥。
        assertThrows(IllegalStateException.class, () -> new IdCardCipher(""));
        // 仅空白字符同样是无效密钥。
        assertThrows(IllegalStateException.class, () -> new IdCardCipher("  "));
        // AES-256 要求 32 字节密钥，长度不足必须拒绝，防止静默降级。
        assertThrows(IllegalStateException.class, () -> new IdCardCipher("too-short"));
    }

    /**
     * 场景：先加密再调用 mask 生成展示用掩码。
     * 前置条件：掩码逻辑需先解密再保留首尾四位。
     * 断言目标：输出 3101********1234，兼顾可读性与隐私保护。
     */
    @Test
    void mask_works_for_v2_ciphertext() {
        // 掩码先解密再保留首尾四位，中间统一用星号替代。
        // 展示层只暴露必要信息，兼顾可读性与隐私保护。
        assertEquals("3101********1234", cipher.mask(cipher.encrypt(ID)));
    }
}
