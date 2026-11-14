package com.cdwater.toolshare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
import com.cdwater.toolshare.module.auth.Admin;
import com.cdwater.toolshare.module.auth.AdminMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/*
 * 测试设计说明
 * ------------
 * 一、权限模型：
 *     管理端采用两级角色。SUPER_ADMIN 覆盖全部管理区域，
 *     STAFF 只覆盖日常运营区域（租赁、报修、消息、仪表盘）与文件上传。
 *     工具治理、知识库维护、管理员账号管理属于超级管理员职责。
 *
 * 二、为什么使用真实 HTTP：
 *     权限由 Sa-Token 注解与路由配置共同决定，
 *     只有发起真实请求才能验证鉴权拦截、HTTP 状态码与业务码的一致性。
 *
 * 三、测试账号策略：
 *     超级管理员复用迁移后的种子账号 admin；
 *     STAFF 由用例自行插入并清理，密码使用固定 BCrypt 哈希，
 *     保证每个用例都能独立登录，不受历史数据影响。
 *
 * 四、关键不变量：
 *     1. SUPER_ADMIN 访问所有管理区域均返回 200/A200；
 *     2. STAFF 访问运营区域成功，访问治理区域返回 403/C403；
 *     3. 角色修改后，新会话立即按新角色鉴权；
 *     4. 未定义角色返回 400/C400，不写入数据库；
 *     5. 未登录请求返回 401，身份与权限错误语义分离。
 *
 * 五、清理策略：
 *     每个用例前后都删除测试 STAFF，避免用户名唯一键冲突，
 *     也避免测试账号在本地数据库中残留。
 *
 * 六、断言策略：
 *     同时检查协议层状态码与业务响应码，
 *     防止“HTTP 200 但业务失败”或“业务码正确但状态码错误”的假通过。
 */
/**
 * 管理端权限集成测试。
 * <p>
 * 测试范围：SUPER_ADMIN 与 STAFF 两级管理角色对各管理区域的访问控制、
 * 管理员信息返回、角色修改与非法角色校验，以及工作人员上传能力。
 * 依赖环境：随机端口完整应用、测试数据库、内存对象存储与种子管理员。
 * 关键不变量：SUPER_ADMIN 可访问全部管理区域；
 * STAFF 只能访问运营区域（租赁、报修、消息、仪表盘）与上传，
 * 不能访问工具治理、知识库与管理员管理；角色修改后权限立即生效；
 * 未知角色返回 400/C400。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestStorageConfig.class)
class AdminPermissionIntegrationTest extends AbstractIntegrationTest {

    // 统一测试密码明文，与种子管理员及测试 STAFF 的哈希一致。
    // 登录接口只接受明文，因此用例中直接复用该常量。
    /** 测试统一密码。 */
    private static final String PASSWORD = "Aa123456";
    /** 测试 STAFF 账号的用户名，用例开始与结束都会清理。 */
    // 测试 STAFF 用户名的固定值：
    // setUp 先删后插，保证角色、密码、状态可控，
    // 又不会与真实管理员账号冲突。
    private static final String STAFF_USERNAME = "it_staff";
    // BCrypt 哈希对应明文 Aa123456，与迁移脚本中的种子哈希保持一致。
    // 直接写哈希而非常量计算，是为了让插入的管理员可立即用于登录。
    /** 与 V2 seed 相同，明文为 Aa123456 */
    private static final String PASSWORD_HASH =
            "$2a$10$GIo5IeU8h/hoUoXFt6sFOu91qMy.0QrjdS6zO0jcC9znCQ.5cIvpC";

    // 随机端口由 Spring Boot 启动时分配，
    // 测试客户端据此拼接完整 URL，避免与本地端口冲突。
    /** 随机端口，供测试 HTTP 客户端使用。 */
    @LocalServerPort
    int port;

    // 管理员表访问入口：
    // 用于插入测试 STAFF、按用户名查询其 ID，以及用例结束时清理。
    /** 管理员 Mapper，用于插入、查询与删除测试 STAFF。 */
    @Autowired
    AdminMapper adminMapper;

    // HTTP 客户端在 @BeforeEach 中初始化，
    // 复用随机端口并保持连接配置一致。
    RestClient client;
    final ObjectMapper om = new ObjectMapper();

    // 统一封装 HTTP 状态码与已解析的 JSON 响应体，
    // 让用例可以同时断言协议层状态与业务码。
    record ApiResponse(int status, JsonNode body) {
    }

    // 每个用例使用干净且可登录的 STAFF 账号。
    // 先删除残留账号再插入，避免上一次异常退出留下的数据
    // 造成用户名唯一键冲突或角色状态不可预期。
    /**
     * 每个用例前创建 HTTP 客户端，清理可能残留的 STAFF 账号，
     * 并插入一个已知密码哈希的 STAFF 管理员用于权限对比。
     */
    @BeforeEach
    void setUp() {
        // 客户端只负责发请求，不携带默认令牌；
        // 每个用例显式传入目标身份的令牌，避免权限串用。
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        // 清理可能存在的同名 STAFF，再重建本次用例所需的账号。
        deleteStaff();
        // 插入 STAFF 时使用固定密码哈希与启用状态，
        // 使 login(STAFF_USERNAME, PASSWORD) 在每个用例中都可成功。
        Admin staff = new Admin();
        staff.setUsername(STAFF_USERNAME);
        staff.setPassword(PASSWORD_HASH);
        staff.setStatus(1);
        staff.setRole("STAFF");
        adminMapper.insert(staff);
    }

    // 用例无论成功失败都删除测试 STAFF，
    // 防止测试账号残留影响后续运行或本地手工调试。
    /** 用例结束后删除测试 STAFF 账号，避免影响其他测试或种子数据。 */
    @AfterEach
    void tearDown() {
        deleteStaff();
    }

    // 辅助方法：按固定用户名删除测试 STAFF。
    // 使用精确匹配而不是模糊匹配，避免误删种子管理员。
    /** 辅助方法：按固定用户名删除测试 STAFF 账号。 */
    private void deleteStaff() {
        adminMapper.delete(new LambdaQueryWrapper<Admin>().eq(Admin::getUsername, STAFF_USERNAME));
    }

    // 覆盖点：种子管理员迁移与登录后的身份回显。
    // 业务背景：历史版本只有一个 admin 账号，
    // 引入两级角色后必须通过迁移把它提升为 SUPER_ADMIN，
    // 否则升级完成后没有账号能进入治理区域。
    // 前置条件：迁移脚本已执行，种子 admin 密码保持 Aa123456。
    // 验证路径：登录 -> 用令牌读取 info -> 断言用户名与角色。
    // 断言目标：HTTP 200、用户名 admin、角色 SUPER_ADMIN。
    // 失败后果：升级部署后管理端可能整体不可用。
    // 该用例同时覆盖登录接口与 info 接口的最小链路，
    // 是其余权限用例的基线。
    /**
     * 场景：种子管理员登录并读取自身信息。
     * 前置条件：迁移脚本已把 admin 提升为 SUPER_ADMIN。
     * 断言目标：登录成功；info 返回用户名 admin、角色 SUPER_ADMIN，
     * 验证历史种子账号的角色迁移结果。
     */
    @Test
    void seed_admin_migrated_to_super_admin_and_can_login() {
        // 使用种子账号与统一密码登录。
        // 登录成功是后续读取 info 的前提。
        ApiResponse login = call(HttpMethod.POST, "/api/admin/login", null,
                Map.of("username", "admin", "password", PASSWORD));
        assertEquals(200, login.status(), login.body().toString());
        String token = login.body().get("data").get("tokenValue").asString();

        // info 接口返回当前登录管理员的身份信息，
        // 前端据此渲染菜单与按钮权限。
        ApiResponse info = call(HttpMethod.GET, "/api/admin/info", token, null);
        assertEquals(200, info.status(), info.body().toString());
        assertEquals("admin", info.body().get("data").get("username").asString());
        assertEquals("SUPER_ADMIN", info.body().get("data").get("role").asString());
    }

    // 覆盖点：超级管理员的完整读权限。
    // 业务背景：超级管理员是治理区域的兜底角色，
    // 任何管理入口都不应因角色配置遗漏而被拒绝。
    // 前置条件：使用种子 admin 登录取得令牌。
    // 验证路径：逐项访问工具、知识库、管理员、租赁、
    // 报修、消息与仪表盘，分别断言 HTTP 200 与业务码 A200。
    // 断言目标：所有路径均成功，不留任何治理盲区。
    // 失败后果：某个模块的管理员被锁死，运营无法继续。
    // 路径列表与前端导航保持一致，
    // 新增一级入口时应同步补充到这里。
    /**
     * 场景：SUPER_ADMIN 依次访问工具、知识库、管理员、租赁、报修、消息与仪表盘。
     * 前置条件：这些路径都要求管理端登录，其中部分仅限超级管理员。
     * 断言目标：每个请求均返回 200/A200，证明超级管理员拥有完整管理权限。
     */
    @Test
    void super_admin_can_access_every_admin_area() {
        // 以超级管理员身份取得令牌。
        String token = login("admin", PASSWORD);

        // 路径覆盖工具、知识库、管理员、租赁、报修、消息与仪表盘，
        // 基本对应管理端导航中的全部一级入口。
        for (String path : List.of(
                "/api/admin/tools", "/api/admin/knowledge", "/api/admin/admins",
                "/api/admin/rentals", "/api/admin/damage-reports",
                "/api/admin/messages", "/api/admin/dashboard")) {
            // 逐个请求并在失败信息中带上路径，
            // 便于快速定位是哪个模块的权限配置回归。
            ApiResponse resp = call(HttpMethod.GET, path, token, null);
            // 协议层必须为 200。
            assertEquals(200, resp.status(), path + " -> " + resp.body());
            // 业务层必须为 A200，排除“HTTP 200 但业务异常”的伪成功。
            assertEquals("A200", resp.body().get("code").asString(), path);
        }
    }

    // 覆盖点：STAFF 的日常运营能力与上传能力。
    // 业务背景：工作人员需要处理租赁、报修、消息，
    // 查看仪表盘，并上传凭证或维修照片。
    // 前置条件：setUp 插入的 STAFF 账号可正常登录。
    // 验证路径：四个运营接口逐个断言 200/A200，
    // 再用真实 PNG 走 multipart 上传。
    // 断言目标：运营接口全部放行，上传返回 200。
    // 失败后果：一线人员无法完成日常工作，权限收得过紧。
    // 上传使用图片而非空文件，
    // 同时覆盖 multipart 解析与媒体类型校验。
    /**
     * 场景：STAFF 账号访问运营区域并上传凭证图片。
     * 前置条件：STAFF 角色被允许处理租赁、报修、消息与仪表盘，并能上传文件。
     * 断言目标：四个运营接口均 200/A200；上传真实 PNG 成功，
     * 说明工作人员的日常操作不被误拦。
     */
    @Test
    void staff_can_access_operational_areas_and_upload() throws IOException {
        // 以测试 STAFF 身份登录。
        String token = login(STAFF_USERNAME, PASSWORD);
        // 运营区域列表与超级管理员用例中的对应部分保持一致，
        // 差异只应体现在治理类接口上。
        for (String path : List.of(
                "/api/admin/rentals", "/api/admin/damage-reports",
                "/api/admin/messages", "/api/admin/dashboard")) {
            // 每个运营接口都应放行。
            ApiResponse resp = call(HttpMethod.GET, path, token, null);
            // HTTP 200 表示未触发鉴权拦截。
            assertEquals(200, resp.status(), path + " -> " + resp.body());
            // A200 表示业务处理正常，而不是仅通过鉴权后报错。
            assertEquals("A200", resp.body().get("code").asString(), path);
        }
        // 上传能力对 STAFF 开放：工作人员需要上传凭证、维修照片等材料。
        ApiResponse uploaded = upload(token);
        // 上传真实 PNG，同时覆盖 multipart 解析、图片校验与对象存储写入。
        assertEquals(200, uploaded.status(), uploaded.body().toString());
    }

    // 覆盖点：STAFF 的越权防护。
    // 业务背景：工具治理、知识库与管理员管理会改变
    // 资产状态、公共内容或账号权限，属于高风险操作。
    // 前置条件：STAFF 已登录，身份合法但角色不足。
    // 验证路径：以合法 STAFF 身份访问三个区域，
    // 断言 HTTP 403 与业务码 C403。
    // 断言目标：三个接口全部被拒绝。
    // 失败后果：工作人员可越权改工具或创建管理员，安全边界失效。
    // 403 而不是 401，说明系统正确区分了
    // “未登录”和“已登录但无权限”两种语义。
    /**
     * 场景：STAFF 账号访问工具治理、知识库与管理员管理。
     * 前置条件：这些区域属于超级管理员职责。
     * 断言目标：全部返回 403/C403，实现最小权限原则，
     * 防止工作人员越权改工具或管理账号。
     */
    @Test
    void staff_forbidden_from_tool_knowledge_and_admin_management() {
        // 仍以 STAFF 身份登录，验证的是“被拒绝”而不是“未登录”。
        String token = login(STAFF_USERNAME, PASSWORD);
        // 工具治理、知识库维护与管理员管理均属于高风险操作。
        for (String path : List.of(
                "/api/admin/tools", "/api/admin/knowledge", "/api/admin/admins")) {
            // 三个接口都应在鉴权阶段被拒绝。
            ApiResponse resp = call(HttpMethod.GET, path, token, null);
            // HTTP 403 表示身份合法但权限不足。
            assertEquals(403, resp.status(), path + " -> " + resp.body());
            // 业务码 C403 让前端可以统一提示“无权限”。
            assertEquals("C403", resp.body().get("code").asString(), path);
        }
    }

    // 覆盖点：角色修改的即时生效。
    // 业务背景：角色变更后如果仍使用旧会话中的权限快照，
    // 被提升的账号需要重新登录才能生效，容易造成误解。
    // 前置条件：测试 STAFF 已插入，超级管理员已登录。
    // 验证路径：超级管理员提升 STAFF -> 该账号重新登录 ->
    // 访问原本被禁止的工具管理接口。
    // 断言目标：修改接口 200，重新登录后工具管理返回 200。
    // 失败后果：权限变更延迟生效，甚至长期不生效。
    // 该用例只修改测试 STAFF，
    // 不会影响种子管理员或其他账号。
    /**
     * 场景：超级管理员把 STAFF 提升为 SUPER_ADMIN，再用该账号访问工具管理。
     * 前置条件：角色修改接口仅超级管理员可用。
     * 断言目标：修改成功；重新登录后该账号可访问 /api/admin/tools，
     * 证明权限变更立即生效而非依赖旧会话缓存。
     */
    @Test
    void super_admin_can_update_admin_role_and_permissions_take_effect() {
        // 由超级管理员发起角色变更。
        String token = login("admin", PASSWORD);
        // 查出测试 STAFF 的主键，角色修改接口以 ID 定位账号。
        Admin staff = adminMapper.selectOne(
                new LambdaQueryWrapper<Admin>().eq(Admin::getUsername, STAFF_USERNAME));

        // 把 STAFF 提升为 SUPER_ADMIN。
        ApiResponse updated = call(HttpMethod.PATCH,
                "/api/admin/admins/" + staff.getId() + "/role", token, Map.of("role", "SUPER_ADMIN"));
        // 修改接口本身应成功。
        assertEquals(200, updated.status(), updated.body().toString());

        // 重新登录以获取带有新角色的会话。
        // 即使旧令牌仍在，权限判断也应以数据库最新角色为准。
        String staffToken = login(STAFF_USERNAME, PASSWORD);
        // 原本被禁止的工具管理接口现在应返回 200，
        // 证明权限判断读取的是更新后的角色。
        assertEquals(200, call(HttpMethod.GET, "/api/admin/tools", staffToken, null).status());
    }

    // 覆盖点：非法角色值的入口校验。
    // 业务背景：权限判断依赖角色枚举白名单，
    // 写入未定义角色会让后续判断落入未定义分支。
    // 前置条件：测试 STAFF 已插入，超级管理员已登录。
    // 验证路径：提交 OWNER 角色，断言 400/C400。
    // 断言目标：请求被拒绝，且不产生任何角色变更。
    // 失败后果：数据库出现无法解释的角色，权限系统行为不可预测。
    // 校验必须发生在持久化之前，
    // 而不是写入后再由读取方兜底处理。
    /**
     * 场景：超级管理员把角色改为未定义的 OWNER。
     * 前置条件：角色枚举只允许已知值。
     * 断言目标：返回 400/C400，避免非法角色写入导致权限判断异常。
     */
    @Test
    void super_admin_cannot_set_unknown_role() {
        // 仍由超级管理员发起修改。
        String token = login("admin", PASSWORD);
        // 定位测试 STAFF。
        Admin staff = adminMapper.selectOne(
                new LambdaQueryWrapper<Admin>().eq(Admin::getUsername, STAFF_USERNAME));

        // OWNER 不在 AdminRole 枚举中，
        // 如果落库会让后续权限判断落入未定义分支。
        ApiResponse resp = call(HttpMethod.PATCH,
                "/api/admin/admins/" + staff.getId() + "/role", token, Map.of("role", "OWNER"));
        // 请求层拒绝非法角色。
        assertEquals(400, resp.status(), resp.body().toString());
        // 业务码 C400 表示参数校验失败。
        assertEquals("C400", resp.body().get("code").asString());
    }

    // 覆盖点：STAFF 身份信息回显。
    // 业务背景：前端根据 info 中的角色裁剪菜单与按钮，
    // 角色字段错误会导致界面展示与实际权限不一致。
    // 前置条件：测试 STAFF 已插入并可登录。
    // 验证路径：STAFF 登录后读取 info，断言 role=STAFF。
    // 断言目标：HTTP 200 且角色字段准确。
    // 失败后果：工作人员看到无权菜单，点击后连续 403。
    // 该接口是前端权限渲染的唯一依据，
    // 因此必须与数据库中的角色保持强一致。
    /**
     * 场景：STAFF 登录后读取自身信息。
     * 前置条件：测试 STAFF 账号已插入。
     * 断言目标：info 返回角色 STAFF，前端可据此隐藏无权菜单。
     */
    @Test
    void staff_info_returns_staff_role() {
        // 以测试 STAFF 登录。
        String token = login(STAFF_USERNAME, PASSWORD);
        // 读取自身信息。
        ApiResponse info = call(HttpMethod.GET, "/api/admin/info", token, null);
        // 协议层成功。
        assertEquals(200, info.status(), info.body().toString());
        // info 中的角色必须与数据库一致，前端据此裁剪界面。
        assertEquals("STAFF", info.body().get("data").get("role").asString());
    }

    // 上传辅助方法需要构造合法的图片字节，
    // 因此可能抛出 IO 异常，由调用方声明。
    // 返回原始状态码与解析后的响应体，供用例统一断言。
    /**
     * 辅助方法：生成 1x1 PNG 并以 multipart 上传到管理端上传接口。
     * 返回原始状态码与解析后的响应体。
     */
    private ApiResponse upload(String token) throws IOException {
        // 用 JDK 自带的 ImageIO 生成 1x1 PNG。
        // 不依赖测试资源文件，避免路径与打包问题。
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", png);
        // multipart 请求体包含文件字段、文件名与 MIME 类型，
        // 与应用实际接收的上传请求保持一致。
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", new ByteArrayResource(png.toByteArray()))
                .filename("proof.png")
                .contentType(MediaType.IMAGE_PNG);
        // 上传接口要求管理端令牌，
        // 这里显式添加 Bearer 头并解析 JSON 响应。
        return client.post().uri("/api/admin/upload")
                .header("Authorization", "Bearer " + token)
                .body(body.build())
                .exchange((request, response) -> {
                    try (InputStream in = response.getBody()) {
                        String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                        return new ApiResponse(response.getStatusCode().value(),
                                om.readTree(raw.isBlank() ? "{}" : raw));
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
    }

    // 登录辅助方法把“请求成功”与“取出令牌”合并，
    // 任一步失败都会让当前用例立即失败并输出响应体。
    // 返回的令牌供后续接口以 Bearer 方式使用。
    /** 辅助方法：用指定管理员账号登录并返回令牌，失败即让测试失败。 */
    private String login(String username, String password) {
        ApiResponse resp = call(HttpMethod.POST, "/api/admin/login", null,
                Map.of("username", username, "password", password));
        assertEquals(200, resp.status(), resp.body().toString());
        return resp.body().get("data").get("tokenValue").asString();
    }

    /** 辅助方法：发送可带令牌的 JSON 请求，返回状态码与响应体。 */
    // 通用请求辅助方法：
    // - 可选择是否携带 Bearer 令牌；
    // - 可选择是否发送 JSON 请求体；
    // - 统一把响应解析为 ApiResponse。
    private ApiResponse call(HttpMethod method, String url, String token, Object body) {
        RestClient.RequestBodySpec spec = client.method(method).uri(url);
        if (token != null) {
            spec.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            // 按方法创建请求，便于 GET/POST/PATCH 共用同一套断言逻辑。
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        // 只有需要认证的接口才添加 Authorization 头，
        // 未登录用例因此可以复用该方法。
        }
        return spec.exchange((request, response) -> {
            try (InputStream in = response.getBody()) {
                // GET 请求没有请求体，只有写操作才设置 Content-Type 与 body。
                String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                return new ApiResponse(response.getStatusCode().value(),
                        om.readTree(raw.isBlank() ? "{}" : raw));
            // 使用 exchange 读取原始响应流，
            // 保证 4xx 响应也能被解析并断言，而不是被客户端直接抛异常。
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
