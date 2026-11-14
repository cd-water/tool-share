package com.cdwater.toolshare.service;

import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.shared.security.IdCardCipher;
import com.cdwater.toolshare.shared.security.IdCardMigration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 身份证历史数据迁移集成测试。
 * <p>
 * 测试范围：启动迁移后种子数据、明文身份证与旧版 ECB 密文的统一升级。
 * 依赖环境：完整 Spring 上下文、测试数据库、真实加密器与迁移组件。
 * 关键不变量：所有身份证最终都以 enc:v2: 前缀保存且可正确解密；
 * 重复执行迁移不得改变已加密数据，保证迁移幂等、可安全重跑。
 */
@SpringBootTest
class IdCardMigrationIntegrationTest extends AbstractIntegrationTest {

    /** 种子用户的身份证明文，用于解密对照。 */
    // 种子用户 1 的身份证在测试库初始化时写入，
    // 迁移组件应把它从明文或旧格式统一升级为 v2 密文。
    // 该常量用于解密后逐字对照，确认没有数据损坏。
    private static final String SEED_ID = "310101199001011234";
    /** 历史明文测试号码。 */
    // 历史明文号码特别选择带 X 校验位的形式，
    // 覆盖非纯数字身份证在填充、加密与解密时的边界。
    private static final String PLAIN_ID = "11010519491231002X";
    /** openssl 生成的旧版 ECB 密文，用于兼容升级测试。 */
    // 旧版 ECB 密文由 openssl 按历史算法生成，
    // 迁移必须能识别该前缀并升级，而不是当成非法数据跳过。
    // 固定密文让用例结果可重复，不依赖运行时随机数。
    private static final String LEGACY =
            "enc:YErWaQle6y+o5klzLHIaMwENDTTb0Y6fOP8unFzDUYY=";

    // 被测迁移组件由 Spring 注入，
    // 这样测试运行的应用启动迁移与生产路径完全一致。
    // 不手工 new，避免遗漏依赖的加解密配置。
    /** 被测迁移组件。 */
    @Autowired
    IdCardMigration migration;

    // 使用真实加密器解密数据库列，
    // 只有能还原出预期明文，才能证明密文格式与密钥配置都正确。
    /** 真实加密器，用于解密并验证迁移结果。 */
    @Autowired
    IdCardCipher cipher;

    // JdbcTemplate 直接读写 t_user：
    // 用例需要插入临时用户并在 finally 中清理，避免污染其他测试。
    // 绕过业务服务也便于精确构造历史格式数据。
    /** 直接操作数据库，插入与清理迁移测试数据。 */
    @Autowired
    JdbcTemplate jdbcTemplate;

    /**
     * 场景：应用启动迁移完成后读取种子用户 1 的身份证列。
     * 前置条件：种子数据在迁移前可能是明文或旧格式。
     * 断言目标：数据库值为 enc:v2: 前缀且可解回原始号码，
     * 证明启动迁移已覆盖历史种子数据。
     */
    @Test
    void seed_id_card_is_encrypted_at_startup() {
        // 从数据库读取种子用户实际保存的身份证列；
        // 这里不经过实体映射，直接观察落库值，避免缓存或转换掩盖问题。
        String stored = jdbcTemplate.queryForObject(
                // 查询条件固定为种子 ID 1，
                // 结果类型为 String，便于直接检查密文前缀。
                // 迁移在应用启动阶段完成，测试只需读取结果。
                "SELECT id_card FROM t_user WHERE id = 1", String.class);
        // 所有身份证都必须带 enc:v2: 前缀：
        // 旧明文或旧前缀会暴露未迁移数据，也可能无法被当前解密器读取。
        // 失败信息直接输出实际值，便于定位格式异常。
        assertTrue(stored.startsWith("enc:v2:"), stored);
        // 解密结果必须等于种子用户的原始号码，
        // 证明迁移只改变了存储格式，没有破坏数据内容。
        // 前缀与明文两条断言共同覆盖“格式正确 + 内容可逆”。
        assertEquals(SEED_ID, cipher.decrypt(stored));
    }

    /**
     * 场景：插入一条明文身份证用户，连续执行两次迁移。
     * 前置条件：首次迁移应加密，第二次应识别已加密并跳过。
     * 断言目标：首次结果为 v2 密文且可解密；第二次后密文完全不变，
     * 验证迁移可重复执行且不会重复加密。用例在 finally 中删除测试用户。
     */
    @Test
    void migration_encrypts_plaintext_and_is_idempotent() {
        // 使用一个其他用例不会复用的手机号，
        // 避免唯一索引冲突，也便于 finally 精准清理。
        // 手机号同时作为查询键，规避自增 ID 不确定性。
        String phone = "13900002222";
        // 插入明文身份证用户，模拟迁移上线前遗留的历史数据；
        // 昵称使用可识别前缀，方便排查残留数据。
        // 事务并非本用例重点，清理放在 finally 中保证一定执行。
        try {
            // 只填充迁移关心的列：手机号、昵称、真实姓名、身份证与状态。
            // 其余列使用表默认值，减少无关字段对用例的干扰。
            // 身份证以明文写入，迁移应自动识别并加密。
            jdbcTemplate.update("INSERT INTO t_user (phone, nickname, real_name, id_card, status) "
                    + "VALUES (?, 'user_mig001', '李四', ?, 1)", phone, PLAIN_ID);

            // 第一次执行迁移：明文应被识别并替换为 v2 密文。
            // 传入 null 表示由组件自行获取数据源，与启动迁移路径一致。
            migration.run(null);
            // 迁移后按手机号读回身份证列，
            // 立即检查格式，确认这一步确实发生了升级。
            // 读取放在同一次迁移后，避免与其他用例交叉。
            String first = idCardOf(phone);
            // 第一次迁移结果必须是 v2 格式；
            // 若仍是明文，说明迁移没有覆盖新增的历史数据。
            assertTrue(first.startsWith("enc:v2:"), first);
            // 解密后应还原为原始明文，
            // 保证加密过程没有截断或多填充字节。
            // 这一步同时验证了迁移与解密器的兼容性。
            assertEquals(PLAIN_ID, cipher.decrypt(first));

            // 再次执行迁移，模拟发布脚本或启动流程重复运行。
            // 幂等性要求已加密数据保持不变。
            migration.run(null);
            // 第二次迁移后的密文必须与第一次完全相同：
            // 若不同，说明组件对已加密数据又加密了一遍，历史数据会被破坏。
            // 错误信息直接点明幂等语义。
            // 这是可安全重跑的关键证据。
            assertEquals(first, idCardOf(phone), "重复迁移不得改变已加密数据");
        // finally 中删除临时用户，
        // 无论断言是否失败都执行，避免脏数据影响后续测试。
        // 按手机号删除，不依赖插入生成的 ID。
        } finally {
            // 清理语句与插入使用同一手机号，
            // 保证只删除本用例创建的数据。
            // 测试库中的种子数据不受影响。
            jdbcTemplate.update("DELETE FROM t_user WHERE phone = ?", phone);
        }
    }

    /**
     * 场景：插入一条旧版 enc: ECB 密文用户并执行迁移。
     * 前置条件：旧密文由历史版本加密器生成。
     * 断言目标：迁移后升级为 v2 密文且能解回正确身份证号，
     * 保证老数据无需人工回填即可继续使用。
     */
    @Test
    void migration_upgrades_legacy_ecb_ciphertext() {
        // 同样使用独立手机号，避免与明文迁移用例互相干扰。
        // 临时数据会在 finally 中删除。
        String phone = "13900003333";
        // 插入一条身份证为旧版 enc: ECB 密文的用户，
        // 模拟历史版本加密器留下的数据。
        // 明文内容仍是 SEED_ID，迁移后应能解回同一号码。
        try {
            // 与明文用例相同的字段集合，仅身份证列换成旧密文，
            // 这样能单独观察“旧格式升级”这一分支。
            jdbcTemplate.update("INSERT INTO t_user (phone, nickname, real_name, id_card, status) "
                    + "VALUES (?, 'user_mig002', '王五', ?, 1)", phone, LEGACY);

            // 执行迁移：组件应识别旧前缀并重新加密为 v2，
            // 而不是把旧密文当作不可处理的数据跳过。
            // 迁移入口与生产启动流程一致。
            migration.run(null);
            // 读取迁移后的列值，准备检查前缀与可解密性。
            // 按手机号查询，避免依赖自增 ID。
            String stored = idCardOf(phone);
            // 迁移后必须统一为 v2 前缀：
            // 旧 ECB 密文即使还能解密，也不应继续留在库中。
            // 统一格式便于后续轮换密钥与审计。
            assertTrue(stored.startsWith("enc:v2:"), stored);
            // 解密结果必须是原始身份证号：
            // 旧密文的填充与编码处理若有偏差，这里会直接失败。
            // 由此证明历史数据无需人工回填即可继续使用。
            assertEquals(SEED_ID, cipher.decrypt(stored));
        } finally {
            // 无论升级成功与否都清理临时用户，
            // 保持测试库可重复运行。
            // 按手机号定位数据，删除范围精确。
            jdbcTemplate.update("DELETE FROM t_user WHERE phone = ?", phone);
        }
    }

    /** 辅助方法：按手机号读取当前身份证列，避免依赖自增 ID。 */
    private String idCardOf(String phone) {
        // 辅助方法：通过手机号读取身份证列，
        // 避免依赖数据库自增 ID 或实体查询缓存。
        return jdbcTemplate.queryForObject(
                // 使用 queryForObject 精确返回单列字符串；
                // 查不到数据会失败，正好提示用例准备阶段有问题。
                "SELECT id_card FROM t_user WHERE phone = ?", String.class, phone);
    }
}
