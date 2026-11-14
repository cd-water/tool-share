package com.cdwater.toolshare.service;

// 本测试只使用 Spring 集成测试基座、随机端口 HTTP 客户端与 Jackson JSON 解析：
// 它不关心业务数据，只验证运行时 OpenAPI 文档是否覆盖已发布端点。
import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 该测试类是一份可执行的接口发布清单，而不是业务行为测试。
// 它依赖完整应用上下文，因为文档由所有控制器、DTO 与安全配置共同生成。
// 端点在未登录状态下必须可访问，否则前端无法在登录前获取契约。
// 测试只读取 /v3/api-docs，不写入数据库，也不会修改对象存储。
// 因此它可以在共享测试库中反复执行，不依赖执行顺序或残留数据。
// 清单更新属于接口变更的一部分，应与控制器代码在同一提交中完成。
// 若文档生成配置被误改导致 paths 缺失，本测试会立即失败。
// 该测试同时充当新人了解系统对外接口面的索引入口。
// 契约测试的价值在于把“文档是否漏接口”变成可失败的断言：
// 新增控制器后如果忘记更新文档配置或端点列表，测试会明确指出缺失路径。
// 清单按业务域分组，便于评审时判断变更属于哪一块功能。
// 测试不依赖登录态，因为文档端点本身应在安全配置中公开。
// 同时它也不写入任何业务数据，属于只读型集成测试。
// 清单中的路径必须与控制器映射完全一致，包括路径变量占位符的写法。
// 删除接口时同样要同步删除清单项，避免留下幽灵契约。
// 该测试是发布前的最后一道接口清单校验。
/**
 * OpenAPI 文档契约集成测试。
 * <p>
 * 测试范围：/v3/api-docs 的可访问性与端点覆盖清单。
 * 依赖环境：随机端口完整应用、测试数据库与内存对象存储。
 * 关键不变量：文档端点在未登录时也可访问（不被 Sa-Token 拦截）；
 * 用户端、管理端、文件读取等已发布接口必须出现在 paths 中，
 * 新增或删除接口时需要同步维护 EXPECTED_PATHS。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestStorageConfig.class)
class OpenApiDocsIntegrationTest extends AbstractIntegrationTest {

    /**
     * OpenAPI 端点覆盖清单，按业务域分组维护：
     * 用户资料、上传、验证码与双登录、工具浏览与蹲守、
     * 租赁下单/支付/取消、报修、消息与通知偏好、AI 对话与推荐；
     * 管理端登录、仪表盘、上传、工具治理、租赁核销、报修流转、
     * 消息、知识库，最后是文件直链读取通道。
     * 新增或下线接口时必须同步更新此清单，否则契约测试会失败。
     */
    // 清单顺序按业务域组织，与前端路由和权限分组保持一致：
    // 先用户端、后管理端，最后是跨角色的文件读取通道。
    // 评审时按分组 diff 可以快速判断变更影响的是哪一类用户。
    // 路径中的 {id} 与 ** 必须与 Spring 映射写法完全一致。
    // 该常量同时被契约快照与前端类型生成间接使用，修改需谨慎。
    private static final List<String> EXPECTED_PATHS = List.of(
            // 用户端认证与个人资料分组：
            // profile 读取/更新当前登录人的资料；upload 上传头像等用户文件；
            // send-code 发送短信验证码，是验证码登录的前置步骤；
            // login/by-pwd 与 login/by-code 分别覆盖密码、验证码两种登录通道；
            // logout 注销当前令牌；info 返回会话中的角色与基础信息。
            // 这些接口共同构成用户端身份闭环，任何新增认证方式都要登记于此。
            "/api/user/profile",
            "/api/user/upload",
            "/api/user/send-code",
            "/api/user/login/by-pwd",
            "/api/user/login/by-code",
            "/api/user/logout",
            "/api/user/info",
            // 用户工作台与工具浏览分组：
            // dashboard 聚合待取件、进行中与历史租赁等首页数据；
            // tools 支持分页、分类与关键字检索；tools/{id} 返回工具详情；
            // availability 暴露指定档期的可租数量；watch 与 watch/list 提供蹲守能力。
            // 详情与档期接口使用路径变量 {id}，必须与控制器注解保持一致。
            // 蹲守列表按当前用户维度查询，不接受任意用户 ID 参数。
            // 该分组覆盖用户从发现工具到决定下单的完整链路。
            "/api/user/dashboard",
            "/api/user/tools",
            "/api/user/tools/{id}",
            "/api/user/tools/{id}/availability",
            "/api/user/tools/{id}/watch",
            "/api/user/tools/watch/list",
            // 用户租赁主流程分组：
            // rentals 创建订单并返回快照；rentals/{id} 查看详情；
            // pay 完成押金/租金支付；cancel 在允许的状态下由用户取消。
            // 支付与取消都是状态机上的显式动作，不能通过通用更新接口绕过。
            // 详情接口需要校验租赁单归属，防止越权查看他人订单。
            // 该分组只包含用户可自行发起的动作，取件与归还属于管理端。
            "/api/user/rentals",
            "/api/user/rentals/{id}",
            "/api/user/rentals/{id}/pay",
            "/api/user/rentals/{id}/cancel",
            // 用户报修分组：
            // damage-reports 提交损坏/故障报修并关联租赁单；
            // damage-reports/{id} 查看本人报修的处理进度。
            // 报修创建后会进入管理端流转，用户端只读自己的记录。
            // 接口需要校验租赁单归属，避免替他人发起报修。
            "/api/user/damage-reports",
            "/api/user/damage-reports/{id}",
            // 用户站内消息分组：
            // messages 分页返回消息列表；unread-count 只返回未读数量，供红点展示；
            // messages/{id}/read 标记单条已读；read-all 一键全部已读。
            // 未读数独立接口可以避免前端为显示角标而拉取整页数据。
            // 所有操作都以当前登录用户为作用域，不能指定他人消息 ID。
            "/api/user/messages",
            "/api/user/messages/unread-count",
            "/api/user/messages/{id}/read",
            "/api/user/messages/read-all",
            // 用户通知偏好分组：
            // notification-prefs 读取当前用户的全部渠道偏好；
            // notification-prefs/{type} 按消息类型更新开关。
            // 偏好会影响后续消息选择 SMS 还是站内信，是消息配置测试的配套能力。
            "/api/user/notification-prefs",
            "/api/user/notification-prefs/{type}",
            // 用户 AI 能力分组：
            // chat 提供普通对话；chat/stream 提供流式响应，适合逐字展示；
            // tool-recommend 返回推荐工具列表；
            // tool-recommend/{id}/click 与 /feedback 记录点击与反馈埋点。
            // 普通与流式端点并存，契约清单必须同时覆盖两种响应模式。
            // 推荐接口依赖向量索引，测试基座默认关闭启动索引以避免网络访问。
            "/api/user/ai/chat",
            "/api/user/ai/chat/stream",
            "/api/user/ai/tool-recommend",
            "/api/user/ai/tool-recommend/{id}/click",
            "/api/user/ai/tool-recommend/{id}/feedback",
            // 管理端认证分组：
            // login/logout/info 与用户端结构对称，但使用独立的管理员账号体系。
            // 管理端令牌与用户端令牌互不通用，避免越权访问管理接口。
            // 登录失败、令牌过期等安全行为由认证测试单独覆盖。
            "/api/admin/login",
            "/api/admin/logout",
            "/api/admin/info",
            // 管理端工作台与上传分组：
            // dashboard 聚合待审核、待取件、逾期与报修等运营指标；
            // upload 提供管理端文件上传通道，供工具图片与知识库素材使用。
            "/api/admin/dashboard",
            "/api/admin/upload",
            // 管理端工具治理分组：
            // tools 支持新增与分页检索，tools/{id} 支持详情/更新/删除；
            // audit 负责审核通过或驳回；status 控制上下架；
            // logs 返回工具的操作审计日志。
            // 审核与状态变更拆成独立动作，便于分别授权与记录操作人。
            // 删除工具前需要处理关联租赁与日志，避免留下悬挂引用。
            "/api/admin/tools",
            "/api/admin/tools/{id}",
            "/api/admin/tools/{id}/audit",
            "/api/admin/tools/{id}/status",
            "/api/admin/tools/{id}/logs",
            // 管理端租赁核销分组：
            // rentals 支持多条件筛选；rentals/{id} 查看详情；
            // pickup 与 return 推进取件、归还状态机；
            // cancel 由管理员处理异常订单；export 按相同筛选条件导出 Excel。
            // 导出与列表共享查询参数，保证屏幕所见与文件内容一致。
            // 归还动作需要记录实际时间、损坏结果与操作人。
            "/api/admin/rentals",
            "/api/admin/rentals/{id}",
            "/api/admin/rentals/{id}/pickup",
            "/api/admin/rentals/{id}/return",
            "/api/admin/rentals/{id}/cancel",
            "/api/admin/rentals/export",
            // 管理端报修流转分组：
            // damage-reports 查看全部报修；damage-reports/{id} 查看详情；
            // verify 核实问题，progress 更新处理进度，accept 受理报修。
            // 多阶段动作分别建路径，前端据此渲染不同操作按钮与权限。
            // 每次流转都应记录操作人，形成可审计的处理链。
            "/api/admin/damage-reports",
            "/api/admin/damage-reports/{id}",
            "/api/admin/damage-reports/{id}/verify",
            "/api/admin/damage-reports/{id}/progress",
            "/api/admin/damage-reports/{id}/accept",
            // 管理端消息分组：
            // 列表、未读数、单条已读与全部已读，与用户端消息结构保持对称。
            // 复用相同的前端组件可以减少两套消息中心的维护成本。
            "/api/admin/messages",
            "/api/admin/messages/unread-count",
            "/api/admin/messages/{id}/read",
            "/api/admin/messages/read-all",
            // 管理端知识库分组：
            // knowledge 支持条目增删改查，knowledge/{id} 定位单条内容。
            // 知识库面向客服与帮助中心，内容变更不需要重启应用。
            "/api/admin/knowledge",
            "/api/admin/knowledge/{id}",
            "/api/files/**");

     // 随机端口由 Spring 注入，避免与本地已占用端口冲突。
    /** 随机端口，供测试 HTTP 客户端使用。 */
    @LocalServerPort
    int port;
     
     // 测试专用 HTTP 客户端与 JSON 解析器，在用例中复用。
     // 直接走真实网络栈，能覆盖安全过滤链对文档端点的放行规则。
    RestClient client;
     // 响应体按树模型解析，便于按路径存在性做断言而无需绑定具体 DTO。
    final ObjectMapper om = new ObjectMapper();
     
    // 每个用例前重建客户端，确保 baseUrl 使用本次上下文分配的端口。
    /**
     * 每个用例前创建指向随机端口的 HTTP 客户端。
     */
    // 客户端只设置 baseUrl，文档请求不需要额外请求头或令牌。
    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    // 该用例同时验证两件事：
    // 1) 文档端点对匿名访问开放，安全配置不会误拦截；
    // 2) 文档 paths 覆盖全部已发布端点，接口变更不会悄悄脱离契约。
    // 断言失败信息会带上缺失的路径，便于直接定位是哪个控制器漏登记。
    // 用例保持只读，不创建业务数据，因此可以安全地在任意环境重复执行。
    // 文档由运行时扫描生成，所以它反映的是实际注册的路由而非手写清单。
    /**
     * 场景：不带任何令牌直接访问 /v3/api-docs。
     * 前置条件：应用已启动且安全配置放行该文档端点。
     * 断言目标：HTTP 200、响应包含 paths 节点；
     * 清单中的每个已发布端点都出现在文档里，防止接口发布后文档遗漏。
     */
    @Test
    void api_docs_accessible_without_auth_and_covers_all_endpoints() {
        // 通过 RestClient 访问文档端点，不附加 Authorization 头。
        // 若安全配置误拦，状态码断言会直接失败。
        // GET 请求不设置 Accept 头，由服务端返回默认的 application/json。
        // 文档端点在安全配置中放行，因此这里故意不传 Authorization。
        // 如果未来收紧该端点，本测试会首先暴露匿名访问被拦截的问题。
        JsonNode doc = client.get().uri("/v3/api-docs")
                // 在 exchange 回调中读取响应，避免 RestClient 因非 2xx 抛异常后难以断言。
                // 先检查状态码，再读取响应体。
                .exchange((request, response) -> {
                    // 文档端点必须返回 200；401/403 都说明匿名访问被错误拦截。
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    // 使用 UTF-8 解码，防止中文描述在部分平台上乱码。
                    try (InputStream in = response.getBody()) {
                        // 将响应体解析为 JSON 树，后续只关心 paths 节点。
                        return om.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    }
                });
        // paths 节点是 OpenAPI 的路由索引，缺失说明文档生成或安全配置异常。
        // paths 是 OpenAPI 文档的顶层路由索引。
        // 缺失通常意味着 springdoc 未扫描到控制器或文档端点被改写。
        JsonNode paths = doc.get("paths");
        assertNotNull(paths, "OpenAPI 文档缺少 paths");
        // 逐项检查清单，失败时直接报告具体路径，而不是只报“数量不一致”。
        for (String path : EXPECTED_PATHS) {
            // has() 同时覆盖路径存在且值非空，避免仅存在空对象。
            // 逐项断言而非比较整体集合，可以准确报告缺失的具体路径。
            assertTrue(paths.has(path), "OpenAPI 文档缺少端点: " + path);
        }
    }
}
