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
 *
 * <p>为什么用 GCM：它同时提供机密性与完整性校验，密文被篡改时解密会
 * 失败，而不是返回一段错误明文。每次加密使用随机 12 字节 IV，避免相同
 * 明文产生相同密文，从而防止通过密文比对推断身份证号。</p>
 *
 * <p>兼容策略：历史 {@code enc:} 密文使用 AES/ECB，先按旧算法解开，再
 * 用 v2 重新加密；无前缀的明文原样返回。启动迁移会批量完成这一转换，
 * 但读取路径仍保留兼容，防止迁移未执行时数据不可读。</p>
 *
 * <p>密钥来自 {@code security.id-card-key}，必须是 16/24/32 字节的原始
 * 字符串。密钥长度不合法时应用启动即失败，避免带着错误配置运行。</p>
 *
 * <p>本类只负责密码学操作，不负责脱敏展示之外的业务规则；日志与接口
 * 输出不得直接使用解密后的明文。</p>
 *
 * <p>存储格式演进：v2 的 IV 与密文拼接后统一 Base64，数据库列只需
 * 普通字符串类型。前缀 {@code enc:v2:} 让读取端无需猜测格式，也为
 * 未来 v3 迁移留出空间。</p>
 *
 * <p>失败语义：解密失败返回 null 而不是抛异常，因为历史脏数据不应
 * 导致整个用户资料接口不可用；加密失败则抛出 IllegalStateException，
 * 因为此时绝不能落库明文。</p>
 */
@Service
public class IdCardCipher {

    /** 新密文前缀，用于区分 GCM 格式与历史格式。 */
    private static final String V2_PREFIX = "enc:v2:";

    /** 历史密文前缀，解密时走 AES/ECB 兼容路径。 */
    private static final String LEGACY_PREFIX = "enc:";

    /** GCM 变换名称，NoPadding 由 GCM 自身处理。 */
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /** GCM 推荐 IV 长度：12 字节。 */
    private static final int IV_BYTES = 12;

    /** 认证标签长度：128 位，提供完整的完整性校验强度。 */
    private static final int TAG_BITS = 128;

    /** AES 密钥，由构造器校验长度后生成。 */
    private final SecretKeySpec key;

    /** 密码学安全随机源，用于生成每次加密的 IV。 */
    private final SecureRandom random = new SecureRandom();

    /**
     * 构造加解密组件并校验密钥。
     *
     * @param key 原始密钥字符串，长度必须为 16/24/32 字节
     * @throws IllegalStateException 密钥为空或长度不合法时抛出
     */
    public IdCardCipher(@Value("${security.id-card-key}") String key) {
        if (!StringUtils.hasText(key)) {
            // 缺少密钥时直接失败，绝不使用空密钥或默认密钥。
            throw new IllegalStateException("security.id-card-key 未配置，请通过 ID_CARD_AES_KEY 注入");
        }
        byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
        if (bytes.length != 16 && bytes.length != 24 && bytes.length != 32) {
            // 分别对应 AES-128、AES-192、AES-256。
            throw new IllegalStateException("security.id-card-key 长度必须为 16/24/32 字节，当前为 " + bytes.length);
        }
        // SecretKeySpec 只接受原始字节，不做 KDF；配置应使用高熵密钥。
        this.key = new SecretKeySpec(bytes, "AES");
    }

    /** 加密明文；已是 v2 密文时原样返回，历史 enc: 密文会升级为 v2。 */
    public String encrypt(String raw) {
        if (!StringUtils.hasText(raw)) {
            // 空值不加密，保持 null 语义，便于数据库区分“未填写”。
            return null;
        }
        String value = raw.trim();
        if (value.startsWith(V2_PREFIX)) {
            // 已经是目标格式，重复加密应保持幂等。
            // 这也是启动迁移可以反复执行的原因。
            // 不做二次解密校验，避免无谓的密码学开销。
            return value;
        }
        if (value.startsWith(LEGACY_PREFIX)) {
            // 历史密文先解开再升级；解密失败则保留原值，避免破坏数据。
            // 保留原值意味着该行会在下次启动时重试迁移。
            String plain = decryptLegacy(value.substring(LEGACY_PREFIX.length()));
            return plain == null ? value : encryptV2(plain);
        }
        // 无前缀视为历史明文，直接升级为 v2。
        return encryptV2(value);
    }

    /** 解密 v2 / 历史 enc: 密文；无前缀的历史明文原样返回。 */
    public String decrypt(String stored) {
        if (!StringUtils.hasText(stored)) {
            // 空值统一返回 null，调用方无需区分 null 与空串。
            return null;
        }
        String value = stored.trim();
        if (value.startsWith(V2_PREFIX)) {
            // 新格式：去掉前缀后交给 GCM 解密。
            return decryptV2(value.substring(V2_PREFIX.length()));
        }
        if (value.startsWith(LEGACY_PREFIX)) {
            // 历史格式：去掉 enc: 后按 ECB 解密。
            // 解密失败返回 null，由展示层决定是否回退。
            return decryptLegacy(value.substring(LEGACY_PREFIX.length()));
        }
        // 无前缀说明是迁移前的明文，直接返回。
        // 该分支只在迁移尚未执行时出现，正常部署应很少命中。
        return value;
    }

    /**
     * 身份证脱敏展示：保留前 4 位与后 4 位，中间固定 8 个星号。
     *
     * <p>短于等于 8 位的数据无法保留有效首尾，统一返回四个星号，避免
     * 通过脱敏结果反推长度。</p>
     *
     * @param stored 密文、历史密文或明文
     * @return 脱敏后的身份证号；输入为空时返回 null
     */
    public String mask(String stored) {
        // 先解密再脱敏；若数据损坏，decrypt 返回 null，下方按空值处理。
        // 脱敏结果只用于展示，不能反推完整身份证号。
        String raw = decrypt(stored);
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        if (raw.length() <= 8) {
            // 异常长度不暴露任何字符。
            // 避免通过脱敏结果推断原值长度。
            return "****";
        }
        // 保留地区码前 4 位与顺序码后 4 位，中间固定 8 星。
        return raw.substring(0, 4) + "********" + raw.substring(raw.length() - 4);
    }

    /**
     * 手机号脱敏：保留前 3 位与后 4 位。
     *
     * <p>手机号本身不加密存储，仅在展示时脱敏。长度不足 7 位时原样
     * 返回，避免截取越界。</p>
     *
     * @param phone 手机号
     * @return 脱敏后的手机号；输入为空或过短时原样返回
     */
    public static String maskPhone(String phone) {
        if (!StringUtils.hasText(phone) || phone.length() < 7) {
            // 过短号码脱敏会越界，直接原样返回。
            return phone;
        }
        // 保留号段前 3 位与后 4 位，中间固定 4 星。
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /**
     * 使用 AES/GCM 加密明文。
     *
     * <p>输出结构为 IV + 密文 + 认证标签，再整体 Base64。IV 与密文一起
     * 存储，解密时从头部取回，无需额外字段。</p>
     *
     * @param plain 明文
     * @return 带 v2 前缀的密文
     * @throws IllegalStateException 加密失败时抛出（通常是运行时环境问题）
     */
    private String encryptV2(String plain) {
        try {
            // 每次加密都生成新的随机 IV，绝不复用。
            // SecureRandom 已由 JVM 初始化，无需每次重新播种。
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            // 拼接 IV || ciphertext(+tag)，解密方按固定长度切分。
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return V2_PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (Exception e) {
            // 加密失败属于系统故障，不能返回明文，必须显式失败。
            throw new IllegalStateException("身份证加密失败", e);
        }
    }

    /**
     * 解密 v2 密文。
     *
     * <p>解密失败（密钥不匹配、数据损坏、标签校验失败）时返回 null，
     * 由调用方决定如何展示。不抛异常是为了兼容迁移期的脏数据。</p>
     *
     * @param payload 去掉 v2 前缀后的 Base64 内容
     * @return 明文；无法解密时返回 null
     */
    private String decryptV2(String payload) {
        try {
            byte[] bytes = Base64.getDecoder().decode(payload);
            if (bytes.length <= IV_BYTES) {
                // 长度不足说明数据损坏，无法切出 IV 与密文。
                // 正常情况下至少包含 IV + 认证标签。
                return null;
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            // IV 位于头部，密文与标签从 IV_BYTES 开始。
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(bytes, IV_BYTES, bytes.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 认证失败或密钥错误：返回 null，不泄露异常细节。
            // GCM 认证失败也走这里，说明密文被篡改或密钥不匹配。
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
            // 历史数据可能已损坏，返回 null 让调用方保留原值。
            // ECB 无认证标签，无法区分密钥错误与数据损坏。
            return null;
        }
    }
}
