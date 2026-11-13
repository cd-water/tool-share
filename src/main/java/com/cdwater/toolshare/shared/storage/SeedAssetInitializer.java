package com.cdwater.toolshare.shared.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 把种子工具图片写入对象存储，并把数据库里的占位图地址替换为 MinIO 路径。
 * 只更新仍为 github 占位图 / 空值的行，不覆盖后台真实上传的图片。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeedAssetInitializer implements ApplicationRunner {

    /** 工具编码 -> 种子图片文件名 */
    private static final Map<String, String> TOOL_ASSETS = Map.of(
            "SQ-G-WX-0001", "seed/tools/drill.jpg",
            "SQ-G-WX-0002", "seed/tools/wrench.jpg",
            "SQ-G-WX-0003", "seed/tools/drill.jpg",
            "SQ-G-YY-0001", "seed/tools/pruner.jpg",
            "SQ-G-YY-0002", "seed/tools/pruner.jpg",
            "SQ-G-QJ-0001", "seed/tools/washer.jpg",
            "SQ-G-QJ-0002", "seed/tools/vacuum.jpg",
            "SQ-G-QT-0001", "seed/tools/ladder.jpg"
    );

    private final StorageService storageService;
    private final JdbcTemplate jdbcTemplate;

    @Value("classpath*:seed/tools/*.jpg")
    private Resource[] toolAssets;

    @Value("classpath:seed/login-stage.jpg")
    private Resource loginStage;

    @Override
    public void run(ApplicationArguments args) {
        try {
            for (Resource resource : toolAssets) {
                String filename = resource.getFilename();
                if (filename == null) {
                    continue;
                }
                String key = "seed/tools/" + filename;
                if (!storageService.exists(key)) {
                    storageService.put(key, resource.getInputStream().readAllBytes(), "image/jpeg");
                }
            }
            if (loginStage.exists() && !storageService.exists("seed/login-stage.jpg")) {
                storageService.put("seed/login-stage.jpg", loginStage.getInputStream().readAllBytes(), "image/jpeg");
            }
            TOOL_ASSETS.forEach((code, key) -> {
                String url = storageService.url(key);
                jdbcTemplate.update(
                        "UPDATE t_tool SET image_url = ?, image_urls = ? "
                                + "WHERE code = ? AND (image_url IS NULL OR image_url = '' OR image_url LIKE '%github.com%')",
                        url, url, code);
            });
            log.info("种子工具图片已同步到对象存储");
        } catch (Exception e) {
            // 对象存储不可用时不阻塞应用启动，工具图片会回退到前端占位
            log.warn("种子工具图片同步失败: {}", e.getMessage());
        }
    }
}
