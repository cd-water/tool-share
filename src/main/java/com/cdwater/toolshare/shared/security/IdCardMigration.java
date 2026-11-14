package com.cdwater.toolshare.shared.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 启动迁移：把历史明文 / enc:（AES/ECB）身份证转为 enc:v2: 密文。
 * 幂等——已加密数据加密后不变，迁移失败的历史密文保持原样并跳过。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdCardMigration implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final IdCardCipher idCardCipher;

    @Override
    public void run(ApplicationArguments args) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, id_card FROM t_user WHERE id_card IS NOT NULL AND id_card <> ''");
        int migrated = 0;
        for (Map<String, Object> row : rows) {
            String stored = (String) row.get("id_card");
            String encrypted = idCardCipher.encrypt(stored);
            if (encrypted != null && !encrypted.equals(stored)) {
                jdbcTemplate.update("UPDATE t_user SET id_card = ? WHERE id = ?",
                        encrypted, row.get("id"));
                migrated++;
            }
        }
        if (migrated > 0) {
            log.info("身份证密文迁移完成：{} 条", migrated);
        }
    }
}
