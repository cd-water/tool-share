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
 * 种子 / 历史身份证在启动迁移后应统一为 v2 密文，且重复执行保持幂等。
 */
@SpringBootTest
class IdCardMigrationIntegrationTest extends AbstractIntegrationTest {

    private static final String SEED_ID = "310101199001011234";
    private static final String PLAIN_ID = "11010519491231002X";
    private static final String LEGACY =
            "enc:YErWaQle6y+o5klzLHIaMwENDTTb0Y6fOP8unFzDUYY=";

    @Autowired
    IdCardMigration migration;

    @Autowired
    IdCardCipher cipher;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void seed_id_card_is_encrypted_at_startup() {
        String stored = jdbcTemplate.queryForObject(
                "SELECT id_card FROM t_user WHERE id = 1", String.class);
        assertTrue(stored.startsWith("enc:v2:"), stored);
        assertEquals(SEED_ID, cipher.decrypt(stored));
    }

    @Test
    void migration_encrypts_plaintext_and_is_idempotent() {
        String phone = "13900002222";
        try {
            jdbcTemplate.update("INSERT INTO t_user (phone, nickname, real_name, id_card, status) "
                    + "VALUES (?, 'user_mig001', '李四', ?, 1)", phone, PLAIN_ID);

            migration.run(null);
            String first = idCardOf(phone);
            assertTrue(first.startsWith("enc:v2:"), first);
            assertEquals(PLAIN_ID, cipher.decrypt(first));

            migration.run(null);
            assertEquals(first, idCardOf(phone), "重复迁移不得改变已加密数据");
        } finally {
            jdbcTemplate.update("DELETE FROM t_user WHERE phone = ?", phone);
        }
    }

    @Test
    void migration_upgrades_legacy_ecb_ciphertext() {
        String phone = "13900003333";
        try {
            jdbcTemplate.update("INSERT INTO t_user (phone, nickname, real_name, id_card, status) "
                    + "VALUES (?, 'user_mig002', '王五', ?, 1)", phone, LEGACY);

            migration.run(null);
            String stored = idCardOf(phone);
            assertTrue(stored.startsWith("enc:v2:"), stored);
            assertEquals(SEED_ID, cipher.decrypt(stored));
        } finally {
            jdbcTemplate.update("DELETE FROM t_user WHERE phone = ?", phone);
        }
    }

    private String idCardOf(String phone) {
        return jdbcTemplate.queryForObject(
                "SELECT id_card FROM t_user WHERE phone = ?", String.class, phone);
    }
}
