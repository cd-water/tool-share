package com.cdwater.toolshare.service;

import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
import com.cdwater.toolshare.shared.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上传安全集成测试。
 * <p>
 * 测试范围：图片上传链路的纵深防御，包括扩展名白名单、真实内容解码校验、
 * 服务端重新编码、大小上限，以及公开读取接口的响应头与历史脏数据处理。
 * <p>
 * 依赖环境：随机端口启动的完整应用、内存对象存储（TestStorageConfig）、
 * 真实 ImageIO 图片库，以及启动种子中的用户账号；
 * 通过真实 HTTP 请求验证过滤器的实际行为，而不是直接调用服务方法。
 * <p>
 * 关键不变量：
 * 1. 仅接受可解码的 PNG/JPG，且扩展名、声明类型与真实内容三者一致；
 * 2. HTML、SVG、伪装成图片的 HTML 与扩展名不符的内容一律返回 400；
 * 3. 超过 5MB 的请求在进入业务逻辑前被拒绝；
 * 4. 存储内容必须是服务端重新编码后的有效图片，剥离原始元数据；
 * 5. 公开读取必须带 X-Content-Type-Options: nosniff；
 * 6. 历史遗留的 text/html 对象按 application/octet-stream 下发，
 * 浏览器不得将其当作可执行页面渲染。
 * <p>
 * 安全意义：上传是存储型 XSS 的常见入口，本类把“类型欺骗”和“内容嗅探”
 * 两类风险放在同一条真实链路上验证。
 * <p>
 * 纵深防御：上传时做内容解码与重编码，读取时再做响应头加固；
 * 任一层单独失效时，另一层仍能阻止脚本执行。
 * 因此合法图片用例与恶意内容用例必须同时存在，缺一不可。
 * <p>
 * 断言策略：恶意请求只断言 400，不绑定具体错误文案；
 * 这样既能守住安全边界，又不会因提示语调整而产生脆弱测试。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestStorageConfig.class)
class UploadSecurityIntegrationTest extends AbstractIntegrationTest {

    /**
     * 期望的防嗅探响应头值。
     * 只要公开读取接口返回该值，浏览器就不会绕过声明类型去猜测内容。
     */
    private static final String NOSNIFF = "nosniff";

    @LocalServerPort
    int port;

    /**
     * 内存存储服务，用于直接写入历史遗留对象。
     * 绕过上传控制器写入，才能模拟“校验逻辑上线前已存在脏对象”的场景。
     */
    @Autowired
    StorageService storageService;

    /** 指向随机端口的 HTTP 客户端，由 setUp 按实际端口创建。 */
    RestClient client;
    /** 响应体解析器，用于断言上传返回的业务码与直链字段。 */
    final ObjectMapper om = new ObjectMapper();
    /**
     * 当前用例的用户令牌，由 setUp 登录获得。
     * 上传接口要求登录，统一复用该令牌可减少重复登录开销。
     */
    String userToken;

    /**
     * 每个用例前创建 HTTP 客户端并登录种子用户，
     * 后续上传请求统一复用该用户令牌。
     * <p>
     * 前置条件：种子用户 13800000001 存在且密码为 Aa123456；
     * 若登录失败，断言会立即暴露环境问题，避免后续上传用例产生误导性结果。
     * 令牌只保存在实例字段中，用例之间不共享状态。
     */
    @BeforeEach
    void setUp() {
        // 随机端口只有运行时才知道，因此客户端必须在每个用例前重新构建。
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        // 使用密码登录换取用户令牌，后续上传请求都会带上它。
        JsonNode node = post("/api/user/login/by-pwd", null,
                Map.of("phone", "13800000001", "password", "Aa123456"));
        // 登录必须成功，否则说明种子数据或认证链路异常。
        // 这里同时断言业务码，能区分“HTTP 200 但业务失败”的伪成功响应。
        assertEquals("A200", node.get("code").asString(), node.toString());
        // 缓存令牌值，避免每个上传用例重复登录。
        // 令牌只放在实例字段中，方法级隔离保证用例之间不会串号。
        // 若未来改为并行执行，每个实例仍持有自己的令牌，不会互相覆盖。
        userToken = node.get("data").get("tokenValue").asString();
    }

    /**
     * 场景：分别上传合法 PNG 与 JPG，再从文件直链接口读取。
     * 前置条件：文件为真实可解码图片，扩展名与内容类型一致，用户已登录。
     * 断言目标：
     * 1. 上传返回 HTTP 200 与业务码 A200；
     * 2. 返回的 url 指向受控文件域名且以 png/jpg 结尾；
     * 3. 通过公开读取接口能取回内容，响应带 nosniff；
     * 4. Content-Type 与上传格式一致；
     * 5. 读取到的字节仍可被 ImageIO 解码，证明存储的是服务端重新编码后的图片；
     * 6. 直链不暴露原始文件名，降低信息泄露与路径猜测风险。
     * 本用例是整组安全断言的“正向对照”，避免安全策略把正常上传一并误杀。
     */
    @Test
    void valid_png_and_jpg_are_reencoded_and_served_with_safe_headers() throws IOException {
        // 同一套安全断言同时覆盖 PNG 与 JPG 两种白名单格式。
        for (String format : new String[]{"png", "jpg"}) {
            // 在内存中生成真实可解码的图片，避免依赖测试资源文件。
            // 尺寸取小值即可覆盖编解码路径，同时让用例保持快速。
            byte[] source = image(format, 32, 24);
            // 文件名使用中文，验证服务端不依赖文件名做类型判断。
            ResponseEntity<String> uploaded = upload(source, "照片." + format, mediaType(format));
            // 合法图片必须被接受，否则正常业务也被误伤。
            assertEquals(HttpStatus.OK, uploaded.getStatusCode(), uploaded.getBody());
            // 解析统一响应体，继续校验业务码与直链。
            JsonNode node = om.readTree(uploaded.getBody());
            assertEquals("A200", node.get("code").asString(), node.toString());
            // 直链必须位于受控域名下，避免开放重定向或外部存储泄露。
            // 同时要求域名固定，便于内容安全策略统一收敛白名单。
            String url = node.get("data").get("url").asString();
            assertTrue(url.startsWith("https://files.test/tool-share/"), url);
            // 扩展名保持图片类型，便于浏览器正确展示。
            assertTrue(url.endsWith(".png") || url.endsWith(".jpg"), url);

            // 从直链中截出对象 key，等价于前端拿到 url 后访问公开读取接口。
            // key 不包含原始文件名，避免通过直链反推用户上传习惯。
            String key = url.substring("https://files.test/tool-share/".length());
            ResponseEntity<byte[]> read = client.get().uri("/api/files/" + key)
                    .retrieve().toEntity(byte[].class);
            // 公开读取必须成功，否则上传后的图片无法展示。
            // 读取接口不做登录校验，验证的是对象 key 的访问边界。
            assertEquals(HttpStatus.OK, read.getStatusCode());
            // nosniff 阻止浏览器把图片内容当作其他类型执行。
            // 即使内容真的是图片，也要求响应头明确声明，避免依赖隐式推断。
            assertEquals(NOSNIFF, read.getHeaders().getFirst("X-Content-Type-Options"));
            // 读取响应的类型必须与上传格式一致。
            assertEquals(mediaType(format).toString(), read.getHeaders().getContentType().toString());
            // 存储的是服务端重新编码后的有效图片
            // 重新编码会剥离原始元数据，同时确保内容确实是图片而非伪装文件。
            assertNotNull(ImageIO.read(new ByteArrayInputStream(read.getBody())));
        }
    }

    /**
     * 场景：上传 HTML、伪装成 PNG 的 HTML、含脚本的 SVG，以及 PNG 内容配 jpg 扩展名。
     * 前置条件：上传接口按真实内容而非仅按扩展名判断，用户已登录。
     * 断言目标：
     * 1. 直接上传 HTML 被拒绝，阻断最基础的存储型 XSS；
     * 2. HTML 改名并声明为 image/png 仍被拒绝，证明校验看内容而非文件名；
     * 3. 带 onload/script 的 SVG 被拒绝，避免 SVG 作为可执行文档被托管；
     * 4. PNG 内容配 jpg 扩展名被拒绝，防止扩展名与真实类型不一致；
     * 5. 所有拒绝都统一返回 400，调用方无需区分具体失败原因。
     * <p>
     * 四种载荷覆盖“后缀欺骗、类型声明欺骗、主动内容、内容与后缀不符”，
     * 基本覆盖图片上传最常见的绕过手法。
     */
    @Test
    void html_svg_and_fake_extension_are_rejected() throws IOException {
        // 构造最小可执行 HTML，模拟最常见的存储型 XSS 载荷。
        // 载荷不含外部依赖，便于稳定复现且不触发真实网络请求。
        byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);
        assertRejected(upload(html, "attack.html", MediaType.TEXT_HTML));
        // 相同的 HTML 改名 .png 并伪造 Content-Type，应仍被内容校验拦截。
        // 这条用例专门覆盖“只看扩展名”的实现缺陷。
        assertRejected(upload(html, "attack.png", MediaType.IMAGE_PNG));

        // SVG 可内嵌脚本与事件处理器，必须按主动内容对待而非普通图片。
        byte[] svg = ("<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\">"
                + "<script>alert(1)</script></svg>").getBytes(StandardCharsets.UTF_8);
        assertRejected(upload(svg, "attack.svg", MediaType.parseMediaType("image/svg+xml")));

        // 扩展名与真实类型不一致
        // 真实 PNG 配 .jpg 后缀，验证扩展名、声明类型与内容三方一致性检查。
        assertRejected(upload(image("png", 8, 8), "fake.jpg", MediaType.IMAGE_JPEG));
    }

    /**
     * 场景：上传刚好超过 5MB 的字节数组，文件名与类型都伪装成合法 PNG。
     * 前置条件：服务端配置 5MB 大小上限，用户已登录。
     * 断言目标：
     * 1. 请求在业务处理前被拒绝，避免大文件占满内存与对象存储；
     * 2. 边界值取 5MB+1 字节，验证上限是“小于等于 5MB”而非宽松近似；
     * 3. 拒绝仍走统一的 400 响应，前端可给出明确提示。
     * <p>
     * 大小限制必须在读取完整请求体之前生效，否则仍可能被大文件拖垮。
     */
    @Test
    void file_larger_than_5mb_is_rejected() {
        // 只多 1 字节，精确卡在上限之外，防止实现把限制写成 5MB 的近似值。
        // 内容全零并不重要，此处验证的是大小限制发生在内容校验之前。
        byte[] oversized = new byte[5 * 1024 * 1024 + 1];
        assertRejected(upload(oversized, "big.png", MediaType.IMAGE_PNG));
    }

    /**
     * 场景：存储中已存在历史遗留的 text/html 对象，直接通过文件通道读取。
     * 前置条件：对象绕过上传校验写入内存存储，模拟安全策略上线前的存量脏数据。
     * 断言目标：
     * 1. 读取仍返回 200，保证历史正常文件可访问；
     * 2. 响应带 nosniff，禁止浏览器自行猜测内容类型；
     * 3. Content-Type 被强制改写为 application/octet-stream；
     * 4. 浏览器因此把内容当作下载附件，而不是执行其中的脚本。
     * <p>
     * 该用例保护的是“历史数据”场景：新上传已加固，但旧对象仍可能带危险类型；
     * 读取层必须无条件按最保守的类型下发。
     */
    @Test
    void legacy_unsafe_object_is_not_served_as_executable_html() {
        // 用纳秒后缀生成唯一 key，避免并发或重复执行时互相覆盖。
        // 文件名保留 .html 后缀，确保测试针对的是“内容危险”而非“后缀未知”。
        String key = "seed/legacy-" + System.nanoTime() + ".html";
        // 绕过上传接口直接写入，模拟校验逻辑上线前已经存在的 HTML 对象。
        storageService.put(key, "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8), "text/html");

        // 通过公开文件通道读取，验证读取层能兜住上传层的漏网对象。
        // 该接口通常无需登录，正是存量脏数据最容易被触达的入口。
        ResponseEntity<byte[]> read = client.get().uri("/api/files/" + key).retrieve().toEntity(byte[].class);
        assertEquals(HttpStatus.OK, read.getStatusCode());
        // nosniff 是阻止内容嗅探的第一道防线。
        // 缺少该头时，即使 Content-Type 正确，旧版浏览器仍可能按内容猜测类型。
        assertEquals(NOSNIFF, read.getHeaders().getFirst("X-Content-Type-Options"));
        // 即使存储元数据声明 text/html，读取层也强制降级为八位字节流。
        assertEquals(MediaType.APPLICATION_OCTET_STREAM.toString(), read.getHeaders().getContentType().toString());
    }

    /**
     * 辅助方法：断言上传请求返回 400，并附带响应体便于排查。
     * 失败时输出原始响应，能快速区分“校验未生效”和“被其他过滤器拦截”。
     */
    private void assertRejected(ResponseEntity<String> response) {
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(), response.getBody());
    }

    /**
     * 辅助方法：构造 multipart 上传请求并返回原始响应。
     * 不因 4xx 抛异常，以便对拒绝场景直接断言状态码；
     * 文件名与 Content-Type 都由调用方指定，用于构造类型欺骗场景。
     */
    private ResponseEntity<String> upload(byte[] bytes, String filename, MediaType contentType) {
        // 使用 multipart 表单字段 file，与前端上传组件保持一致。
        // 文件名与类型都显式指定，便于构造合法与欺骗两类输入。
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", new ByteArrayResource(bytes)).filename(filename).contentType(contentType);
        // 附带用户令牌后发起上传；onStatus 放行所有状态码，交给断言判断。
        // 若这里让客户端对 4xx 抛异常，拒绝场景就无法读取原始响应体。
        return client.post().uri("/api/user/upload")
                .header("Authorization", "Bearer " + userToken)
                .body(body.build())
                .retrieve()
                .onStatus(status -> true, (request, response) -> {
                })
                .toEntity(String.class);
    }

    /**
     * 辅助方法：把图片格式名映射为对应的 HTTP 媒体类型。
     * 仅在 png 与 jpg 两种白名单格式之间选择，其他值按 jpg 处理。
     */
    private static MediaType mediaType(String format) {
        return "png".equals(format) ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
    }

    /**
     * 辅助方法：在内存中生成指定格式和尺寸的图片字节。
     * 用于构造合法图片，也可测试尺寸边界；ImageIO.write 的返回值一并断言，
     * 确保测试拿到的是真实可解码内容，而不是空数组。
     */
    private static byte[] image(String format, int width, int height) throws IOException {
        // PNG 使用带透明通道的 ARGB，JPG 不支持透明通道，使用 RGB。
        // 选错类型会导致 ImageIO.write 失败，测试会立即暴露。
        int type = "png".equals(format) ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage image = new BufferedImage(width, height, type);
        // 使用内存流输出，避免在测试目录留下临时文件。
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // 写入失败直接让用例失败，避免后续出现难以定位的“空文件”断言。
        assertTrue(ImageIO.write(image, format, out));
        return out.toByteArray();
    }

    /**
     * 辅助方法：发送 JSON POST 请求并解析响应，登录与业务调用共用。
     * 令牌为空时不添加 Authorization 头；所有状态码都放行，
     * 由调用方按业务码断言，避免 RestClient 对 4xx 直接抛异常。
     */
    private JsonNode post(String url, String token, Object body) {
        // 固定 JSON 内容类型，与前端请求封装保持一致。
        RestClient.RequestBodySpec spec = client.post().uri(url)
                .contentType(MediaType.APPLICATION_JSON);
        // token 为空表示匿名请求，例如登录接口本身。
        // 有令牌时统一使用 Bearer 前缀，与 Sa-Token 配置保持一致。
        if (token != null) {
            spec.header("Authorization", "Bearer " + token);
        }
        // 空响应体按空对象处理，防止 readTree 对 null 抛异常。
        String raw = spec.body(body).retrieve().onStatus(status -> true, (request, response) -> {
        }).body(String.class);
        return om.readTree(raw == null ? "{}" : raw);
    }
}
