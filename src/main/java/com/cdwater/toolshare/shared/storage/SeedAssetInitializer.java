package com.cdwater.toolshare.shared.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 把种子工具图片写入对象存储，并把数据库里的占位图地址替换为 MinIO 路径。
 * 只更新仍为 github 占位图 / 空值的行，不覆盖后台真实上传的图片。
 *
 * <p>执行顺序排在演示数据初始化之后（{@code @Order(20)}），因为需要先
 * 有工具与用户记录才能回填图片地址。流程分三步：上传 classpath 中的
 * 种子图片、按编码/手机号回填地址、把历史代理地址规范化为直链。</p>
 *
 * <p>所有上传都是“存在则跳过”，因此重复启动不会重复写入对象。对象存储
 * 不可用时只记录警告，不阻塞应用启动；页面会自然回退到前端占位图。</p>
 *
 * <p>数据库更新带有条件，只覆盖空值或 github 占位地址，管理员后台上传
 * 的真实图片永远不会被种子流程覆盖。</p>
 *
 * <p>为什么用固定映射而不是扫描数据库：种子图片与工具编码/用户手机号
 * 的对应关系是演示数据的一部分，显式写在代码中可避免猜测与误配。
 * 新增种子工具时，需要同时更新 {@code demo-data.sql} 与本类映射。</p>
 *
 * <p>本类只处理 classpath 中的种子资源，不接收用户上传；用户上传由
 * {@link UploadController} 处理。两者共用 {@link StorageService}，但
 * key 命名空间不同（{@code seed/} 与随机 UUID 前缀）。</p>
 * <p>初始化只读取 classpath 资源，不会修改仓库中的原始图片文件。</p>
 *
 * <p>执行失败不会阻止应用启动：演示环境宁可缺少图片，也不应因为
 * 对象存储暂时不可用而整体不可用。失败原因会写入 warn 日志。</p>
 *
 * <p>运行时机：作为 {@link ApplicationRunner} 在容器启动完成后执行，
 * 因此此时数据源、对象存储客户端与演示数据都已就绪。它不参与请求
 * 处理，也不会影响健康检查结果。</p>
 *
 * <p>与 {@code DemoDataInitializer} 的职责边界：后者只负责写入关系型
 * 数据与知识库语料，本类只负责二进制图片。两者通过 {@code @Order}
 * 保证先后顺序，但互不直接调用。</p>
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class SeedAssetInitializer implements ApplicationRunner {

    /** 用户手机号 -> 种子头像文件名 */
    /*
     * <p>使用手机号而不是用户 ID，因为种子 SQL 中手机号是稳定业务键，
     * 自增 ID 可能随初始化顺序变化。</p>
     */
    private static final Map<String, String> USER_AVATARS = Map.of(
            "13800000001", "seed/avatars/wang-jianguo.jpg"
    );

    /** 工具编码 -> 种子图片文件名 */
    /*
     * <p>key 与 {@code seed/demo-data.sql} 中的工具编码一一对应；编码由
     * 序号服务规则生成，初始化后保持稳定。</p>
     */
    private static final Map<String, String> TOOL_ASSETS = Map.of(
            "SQ-G-WX-0001", "seed/tools/drill.jpg",
            "SQ-G-WX-0002", "seed/tools/wrench.jpg",
            "SQ-G-WX-0003", "seed/tools/rotary-hammer.jpg",
            "SQ-G-YY-0001", "seed/tools/pruner.jpg",
            "SQ-G-YY-0002", "seed/tools/lawn-mower.jpg",
            "SQ-G-QJ-0001", "seed/tools/washer.jpg",
            "SQ-G-QJ-0002", "seed/tools/vacuum.jpg",
            "SQ-G-QT-0001", "seed/tools/ladder.jpg"
    );

    /** 对象存储服务，负责上传种子图片并生成访问地址。 */
    private final StorageService storageService;

    /** 轻量 JDBC 入口，用于按编码/手机号回填图片地址。 */
    private final JdbcTemplate jdbcTemplate;

    /** classpath 下全部种子工具图片。 */
    @Value("classpath*:seed/images/tools/*.jpg")
    private Resource[] toolAssets;

    /** classpath 下全部种子头像图片。 */
    @Value("classpath*:seed/images/avatars/*.jpg")
    private Resource[] avatarAssets;

    /** 登录页背景图，单独维护因为不属于工具或头像。 */
    @Value("classpath:seed/images/ui/login-stage.jpg")
    private Resource loginStage;

    /**
     * 应用启动后同步种子图片。
     *
     * <p>先上传工具图与头像，再回填数据库，最后规范化历史地址。任何
     * 异常都被捕获并降级为警告，避免对象存储故障导致整个应用无法启动。</p>
     *
     * <p>上传阶段逐文件判断是否存在，已存在则跳过；数据库回填阶段使用
     * 条件更新，只有空值或 github 占位地址会被替换。两个阶段都幂等，
     * 因此可以安全地重复执行，也可以在每次启动时运行。</p>
     *
     * <p>如果某个种子文件在 classpath 中缺失，对应的上传循环会自然跳过，
     * 数据库仍可能被回填为不可访问的地址。因此新增映射前应确认资源存在。</p>
     *
     * <p>历史地址规范化必须在公开读可用时执行，否则会生成无法访问的
     * 直链。该方法内部会再次检查前缀并在不可用时直接跳过。</p>
     *
     * @param args 启动参数，本初始化器不使用
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            // 工具图：classpath 扫描顺序不保证，但 key 由文件名决定，顺序无关。
            // 每个文件独立上传，单个失败会由外层 catch 统一降级处理。
            // 已存在的对象直接跳过，避免重复写入相同内容。
            for (Resource resource : toolAssets) {
                String filename = resource.getFilename();
                if (filename == null) {
                    // 无法取得文件名时跳过，避免生成 seed/tools/null 之类的 key。
                    continue;
                }
                // 统一使用 seed/tools/ 前缀，与数据库中的 key 保持一致。
                // 文件名来自 classpath，属于可信输入，无需额外清洗。
                String key = "seed/tools/" + filename;
                // 固定 key 已存在则跳过，保证幂等且不覆盖同名对象。
                if (!storageService.exists(key)) {
                    storageService.put(key, resource.getInputStream().readAllBytes(), "image/jpeg");
                }
            }
            // 头像单独使用 seed/avatars/ 前缀，避免与工具图重名。
            // 头像数量很少，逐个上传的开销可以忽略。
            // 如果未来头像较多，可考虑并行上传，但当前无需优化。
            for (Resource resource : avatarAssets) {
                String filename = resource.getFilename();
                if (filename == null) {
                    continue;
                }
                String key = "seed/avatars/" + filename;
                // 头像与工具图使用不同前缀，避免 key 冲突。
                // 头像均为 JPEG，Content-Type 固定，不依赖文件名猜测。
                if (!storageService.exists(key)) {
                    storageService.put(key, resource.getInputStream().readAllBytes(), "image/jpeg");
                }
            }
            if (loginStage.exists() && !storageService.exists("seed/login-stage.jpg")) {
                // 登录页背景图使用固定 key，便于前端直接引用。
                // 先判断 classpath 资源存在，避免打包缺失时读取异常中断整个初始化。
                storageService.put("seed/login-stage.jpg", loginStage.getInputStream().readAllBytes(), "image/jpeg");
            }
            // 数据库回填：url 可能是直链或代理地址，取决于桶公开读状态。
            TOOL_ASSETS.forEach((code, key) -> {
                // 每个编码对应一张主图，url 由当前存储配置决定。
                String url = storageService.url(key);
                // 只覆盖空值或 github 占位图，保留后台上传的真实图片。
                // image_url 与 image_urls 同时回填：前者用于列表，后者用于详情图集。
                jdbcTemplate.update(
                        "UPDATE t_tool SET image_url = ?, image_urls = ? "
                                + "WHERE code = ? AND (image_url IS NULL OR image_url = '' OR image_url LIKE '%github.com%')",
                        url, url, code);
            });
            // 头像回填使用手机号定位用户，条件同样只覆盖占位值。
            USER_AVATARS.forEach((phone, key) -> {
                String url = storageService.url(key);
                // 用户头像同样只填补空值与占位图。
                // 条件中的 github.com 是旧演示数据的占位图来源。
                jdbcTemplate.update(
                        "UPDATE t_user SET avatar = ? "
                                + "WHERE phone = ? AND (avatar IS NULL OR avatar = '' OR avatar LIKE '%github.com%')",
                        url, phone);
            });
            normalizeLegacyUrls();
            log.info("种子工具图片已同步到对象存储");
        } catch (Exception e) {
            // 对象存储不可用时不阻塞应用启动，工具图片会回退到前端占位
            log.warn("种子工具图片同步失败: {}", e.getMessage());
        }
    }

    /**
     * 把历史上经过后端代理的文件地址统一改为对象存储直链。
     * 桶公开读未启用时跳过，继续由 /api/files/** 提供兜底。
     *
     * <p>覆盖工具主图、工具图集、用户头像、报修图片与评价图片五处。
     * 只替换 URL 中的 {@code /api/files/} 前缀，不改变 key 本身。</p>
     *
     * <p>历史数据可能把完整代理路径存进单个字段，也可能存进逗号分隔的
     * 多图字段，因此两类字段都要替换。REPLACE 只命中前缀，图片 key 中
     * 即使包含相似字符串也不会被误改。</p>
     *
     * <p>每张表独立更新并累加影响行数，便于确认迁移规模；如果某张表
     * 不存在或字段为空，对应更新影响 0 行，不会中断其它表。</p>
     */
    private void normalizeLegacyUrls() {
        String prefix = storageService.publicUrlPrefix();
        if (prefix == null) {
            // 无公开直链前缀时保持代理地址，避免生成不可访问的 URL。
            // 这不是错误：代理模式本来就是受支持的运行方式。
            log.warn("对象存储公开读未启用，跳过旧文件地址规范化");
            return;
        }
        int changed = 0;
        // 每类表分别统计受影响行数，便于日志观察迁移规模。
        // 更新语句只替换前缀，不触碰 key 的其余部分，可重复执行。
        // 五条 UPDATE 各自带 LIKE 条件，未命中的表不会产生写放大。
        changed += jdbcTemplate.update(
                "UPDATE t_tool SET image_url = REPLACE(image_url, '/api/files/', ?) "
                        + "WHERE image_url LIKE '/api/files/%'", prefix);
        changed += jdbcTemplate.update(
                "UPDATE t_tool SET image_urls = REPLACE(image_urls, '/api/files/', ?) "
                        + "WHERE image_urls LIKE '%/api/files/%'", prefix);
        changed += jdbcTemplate.update(
                "UPDATE t_user SET avatar = REPLACE(avatar, '/api/files/', ?) "
                        + "WHERE avatar LIKE '/api/files/%'", prefix);
        changed += jdbcTemplate.update(
                "UPDATE t_damage_report SET images = REPLACE(images, '/api/files/', ?) "
                        + "WHERE images LIKE '%/api/files/%'", prefix);
        changed += jdbcTemplate.update(
                "UPDATE t_tool_review SET images = REPLACE(images, '/api/files/', ?) "
                        + "WHERE images LIKE '%/api/files/%'", prefix);
        if (changed > 0) {
            // 只有真正发生替换时才输出 info，减少空跑日志。
            // 该日志同时可用于确认历史数据迁移是否已执行。
            log.info("已将 {} 处旧文件地址规范化为对象存储直链", changed);
        }
    }
}
