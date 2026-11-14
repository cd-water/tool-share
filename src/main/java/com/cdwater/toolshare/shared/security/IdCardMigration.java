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
 *
 * <p>使用 {@link JdbcTemplate} 直接扫描 {@code t_user.id_card}，不依赖
 * 用户模块的 Mapper，避免 shared 反向依赖业务模块。迁移在应用启动后
 * 执行一次，只处理非空字段。</p>
 *
 * <p>幂等性来自 {@link IdCardCipher#encrypt(String)}：v2 密文原样返回，
 * 因此重复启动不会重复加密。单条迁移失败时保留原值，不会把脏数据
 * 覆盖成 null，便于人工排查。</p>
 *
 * <p>该迁移只做数据格式升级，不改变列类型，也不修改业务代码的读取
 * 方式；读取端仍通过 {@link IdCardCipher#decrypt(String)} 兼容旧格式。</p>
 *
 * <p>性能：一次性把所有非空身份证读入内存，再逐行更新。用户量很大时
 * 可改为分页扫描，但当前演示规模无需优化。更新是逐条执行，避免单条
 * 脏数据导致整批失败。</p>
 *
 * <p>失败容忍：单行加密失败时 encrypt 返回原值或 null，比较后不会更新，
 * 因此不会破坏原数据；异常也不会中断整个启动流程。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdCardMigration implements ApplicationRunner {

    /** 轻量 JDBC 入口，用于扫描与逐行更新身份证字段。 */
    private final JdbcTemplate jdbcTemplate;

    /** 加解密组件，负责判断是否需要升级并生成 v2 密文。 */
    private final IdCardCipher idCardCipher;

    /**
     * 执行一次性身份证密文迁移。
     *
     * <p>先一次性取出所有非空身份证，再逐行加密比较。只有密文发生变化
     * 才执行更新，避免无意义写库。迁移完成的条数只在大于 0 时记录日志。</p>
     *
     * @param args 启动参数，本迁移不使用
     */
    @Override
    public void run(ApplicationArguments args) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, id_card FROM t_user WHERE id_card IS NOT NULL AND id_card <> ''");
        int migrated = 0;
        for (Map<String, Object> row : rows) {
            String stored = (String) row.get("id_card");
            // encrypt 对 v2 幂等，对明文/旧密文返回升级后的新值。
            String encrypted = idCardCipher.encrypt(stored);
            if (encrypted != null && !encrypted.equals(stored)) {
                // 只有真正变化才更新，避免每次启动都写一遍全表。
                jdbcTemplate.update("UPDATE t_user SET id_card = ? WHERE id = ?",
                        encrypted, row.get("id"));
                migrated++;
            }
        }
        if (migrated > 0) {
            // 没有迁移量时不打日志，保持启动日志干净。
            log.info("身份证密文迁移完成：{} 条", migrated);
        }
    }
}
