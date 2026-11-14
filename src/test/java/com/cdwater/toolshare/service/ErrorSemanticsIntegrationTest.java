package com.cdwater.toolshare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
import com.cdwater.toolshare.module.auth.User;
import com.cdwater.toolshare.module.auth.UserMapper;
import com.cdwater.toolshare.module.rental.Rental;
import com.cdwater.toolshare.module.rental.RentalMapper;
import com.cdwater.toolshare.module.tool.Tool;
import com.cdwater.toolshare.module.tool.ToolLog;
import com.cdwater.toolshare.module.tool.ToolLogMapper;
import com.cdwater.toolshare.module.tool.ToolMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 错误语义集成测试。
 * <p>
 * 测试范围：统一响应 envelope 与语义化 HTTP 状态码的对应关系，
 * 覆盖成功、未登录、被禁用、路由不存在、业务冲突、参数校验与错误方法等场景。
 * <p>
 * 依赖环境：随机端口启动的完整应用、测试数据库、内存对象存储与启动种子账号；
 * 所有断言都通过真实 HTTP 调用完成，确保全局异常处理器与过滤器链真实生效。
 * <p>
 * 关键不变量：
 * 1. 成功请求恒为 HTTP 200 + 业务码 A200，作为错误映射的对照基准；
 * 2. 业务错误码与 HTTP 状态一一映射：
 * C401->401、C403->403、C404->404、C409->409、C400 校验类->422；
 * 3. 未登录与被禁用必须可区分，前者 401、后者 403；
 * 4. 路由缺失返回 404，而不是被兜底成 500；
 * 5. 参数校验与方法不支持统一归入 400 家族，但校验失败使用 422 以区分语义；
 * 6. 前端可同时依据状态码与业务码做分支，不会出现两者矛盾。
 * <p>
 * 设计意图：把“HTTP 层语义”和“业务层语义”绑定成稳定契约，
 * 任何一端变更都会在这里被立即发现，避免前端出现难以解释的跳转或提示。
 * <p>
 * 维护提示：新增全局异常映射时，应在本类补充对应状态码用例；
 * 只修改状态码而不改业务码，或反之，都会被视为契约破坏。
 * 用例之间通过固定前缀与 finally 恢复来隔离，可安全重复执行。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestStorageConfig.class)
class ErrorSemanticsIntegrationTest extends AbstractIntegrationTest {

    /** 档期时间的请求格式，必须与后端 DTO 的解析格式完全一致。 */
    // 精确到秒并去掉纳秒，避免 Jackson 序列化时产生可变的小数位。
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    /** 本类创建的工具名称前缀，清理阶段据此精确回收测试数据。 */
    // 前缀同时承担“测试数据标记”和“清理条件”两个职责。
    private static final String PREFIX = "IT-错误语义-";
    /** 种子用户手机号，用于登录、禁用与资料校验等场景。 */
    private static final String USER1_PHONE = "13800000001";
    /** 种子用户与管理员共用的测试密码。 */
    private static final String PASSWORD = "Aa123456";

    /**
     * 随机端口，供测试 HTTP 客户端使用。
     * 使用随机端口可避免与本机其他服务冲突，也让测试可并行运行。
     * 该字段由框架在上下文启动后注入，不能提前用于构造静态客户端。
     */
    @LocalServerPort
    int port;

    /** 用户 Mapper，用于临时禁用与恢复种子用户。 */
    @Autowired
    // 仅用于构造“账号被禁用”的前置状态，业务请求本身仍走 HTTP。
    UserMapper userMapper;

    /** 工具 Mapper，用于创建与清理测试工具。 */
    @Autowired
    // 用于按前缀回收测试数据，避免污染其他集成测试。
    ToolMapper toolMapper;

    /** 租赁单 Mapper，清理工具时级联删除租赁。 */
    @Autowired
    // 工具被删除前必须先清掉引用它的租赁单，否则外键会阻止清理。
    RentalMapper rentalMapper;

    /** 工具日志 Mapper，清理工具前先删日志。 */
    @Autowired
    // 日志同样持有 toolId 引用，清理顺序必须在工具之前。
    ToolLogMapper toolLogMapper;

    /** 指向随机端口的 HTTP 客户端，在 setUp 中按实际端口构建。 */
    // 使用 RestClient 可精确控制状态码放行，避免异常掩盖错误响应。
    RestClient client;
    /** 响应体解析器，用于读取统一 envelope 中的 code 与 data 字段。 */
    // 与生产使用相同的 Jackson 实现，保证字段名与类型解析一致。
    final ObjectMapper om = new ObjectMapper();

    /**
     * 测试内使用的轻量响应载体。
     * 同时保留 HTTP 状态与解析后的 JSON，便于断言“状态码 + 业务码”双重契约。
     */
    record ApiResponse(int status, JsonNode body) {
    }

    /**
     * 每个用例前创建指向随机端口的 HTTP 客户端。
     * 端口只有运行时才确定，因此客户端不能作为静态字段提前构建。
     */
    @BeforeEach
    void setUp() {
        // baseUrl 必须带上实际随机端口，否则请求会打到默认端口而失败。
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /**
     * 用例结束后按工具名称前缀删除租赁、日志与工具，
     * 保证错误语义测试不残留业务数据。
     * <p>
     * 清理顺序遵循引用关系：租赁、日志引用工具，必须先删子记录再删主记录；
     * 只匹配本类固定前缀，避免误删其他集成测试或种子数据。
     * 即使某个用例中途断言失败，本方法仍会执行，保证测试库可重复使用。
     */
    @AfterEach
    void cleanUp() {
        // 按名称前缀检索本类创建的工具，前缀是整个清理逻辑的安全边界。
        List<Tool> tools = toolMapper.selectList(new LambdaQueryWrapper<Tool>()
                .likeRight(Tool::getName, PREFIX));
        for (Tool tool : tools) {
            // 先清租赁单，避免外键约束阻止工具删除。
            rentalMapper.delete(new LambdaQueryWrapper<Rental>().eq(Rental::getToolId, tool.getId()));
            // 再清工具日志，日志同样引用 toolId。
            toolLogMapper.delete(new LambdaQueryWrapper<ToolLog>().eq(ToolLog::getToolId, tool.getId()));
            // 最后删除工具本体，完成整条数据链回收。
            toolMapper.deleteById(tool.getId());
        }
    }

    /**
     * 场景：使用正确账号密码登录，验证成功路径的响应语义。
     * 前置条件：种子用户存在且处于启用状态，密码与种子一致。
     * 断言目标：
     * 1. HTTP 状态为 200，而不是 201/204 等其他成功码；
     * 2. 业务码为 A200，与 HTTP 状态保持正向一致；
     * 3. 本用例是后续所有错误映射的对照基准，不能省略。
     * 本用例只验证 envelope 的成功分支，不关心令牌内容与权限。
     * 它也是判断测试环境是否就绪的最快信号。
     * 若失败，后续错误用例的结论都不可信，应优先排查环境与种子数据。
     */
    @Test
    void success_still_returns_200_with_a200() {
        // 选择密码登录而非验证码登录，避免依赖短信模拟。
        // 请求体只包含必要字段，尽量缩小变量范围。
        // 匿名登录：不携带令牌，验证登录接口本身可公开访问。
        ApiResponse resp = call(HttpMethod.POST, "/api/user/login/by-pwd", null,
                Map.of("phone", USER1_PHONE, "password", PASSWORD));
        // 成功路径固定使用 200，确保全局异常处理器没有误伤正常响应。
        // 成功路径的 HTTP 语义必须稳定为 200。
        // 若这里漂移为 201，前端统一拦截器可能把它当作异常。
        assertEquals(200, resp.status());
        // 业务码 A200 与 HTTP 200 成对出现，构成成功契约。
        assertEquals("A200", resp.body().get("code").asString());
    }

    /**
     * 场景：未携带令牌访问受保护的用户信息接口。
     * 前置条件：Sa-Token 对该路径启用登录校验，请求不包含 Authorization 头。
     * 断言目标：
     * 1. HTTP 状态为 401 Unauthorized，语义上表示“未认证”；
     * 2. 业务码为 C401，前端可据此统一跳转登录页；
     * 该场景不构造任何业务数据，只依赖过滤器链的认证入口。
     * 若它失败，说明全局错误映射或 Sa-Token 异常处理出现了回归。
     * 3. 不能被误映射为 403 或 500，否则前端无法区分“去登录”和“无权限”。
     */
    @Test
    void unauthorized_returns_401_with_c401() {
        // 使用用户信息接口作为受保护资源，路径稳定且无需业务数据。
        // 不携带任何请求体，排除参数校验干扰。
        // token 传 null 表示完全匿名，模拟未登录用户直接访问受保护资源。
        ApiResponse resp = call(HttpMethod.GET, "/api/user/info", null, null);
        // 401 是认证缺失的标准语义，区别于 403 的权限不足。
        // 未认证属于客户端可通过登录修正的状态。
        // 状态码与业务码必须成对出现，避免前端只依赖其一。
        assertEquals(401, resp.status());
        // 业务码必须同步为 C401，保持前后端分支一致。
        assertEquals("C401", resp.body().get("code").asString());
    }

    /**
     * 场景：用户被管理员禁用后，仍使用正确的手机号与密码尝试登录。
     * 前置条件：临时把种子用户 status 改为 0，用例结束在 finally 中恢复为 1。
     * 断言目标：
     * 1. 返回 403/C403，表达“身份已识别但账号被限制”；
     * 2. 与 401 明确区分：401 是未认证，403 是已认证但禁止操作；
     * 该场景通过直接改库构造状态，避免依赖管理端禁用接口的额外契约。
     * 因此它只检验登录语义，不把禁用接口的成败混入判断。
     * 3. finally 中的恢复逻辑保证即使断言失败，后续用例仍能正常登录。
     */
    @Test
    void forbidden_returns_403_with_c403() {
        // 直接操作种子用户可避免依赖管理端接口的角色配置。
        // 状态变更前后都在同一线程完成，不存在并发覆盖风险。
        // 先取出种子用户，准备构造“被禁用”的前置状态。
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getPhone, USER1_PHONE));
        // 状态 0 表示禁用，登录服务应在密码校验前或后统一拦截。
        user.setStatus(0);
        userMapper.updateById(user);
        try {
            // 凭证本身仍然正确，因此 403 不是因为密码错误。
            ApiResponse resp = call(HttpMethod.POST, "/api/user/login/by-pwd", null,
                    Map.of("phone", USER1_PHONE, "password", PASSWORD));
            // 已认证但被禁止，使用 403 而不是 401。
            // 已识别身份但账号受限，语义上不能退回 401。
            // 403 提示用户联系管理员，而不是重新登录。
            assertEquals(403, resp.status());
            // 业务码同步为 C403，便于前端展示“账号被禁用”提示。
            assertEquals("C403", resp.body().get("code").asString());
        } finally {
            // 无论断言是否通过都恢复账号，避免污染同一测试上下文中的后续用例。
            User restored = userMapper.selectOne(
                    new LambdaQueryWrapper<User>().eq(User::getPhone, USER1_PHONE));
            restored.setStatus(1);
            userMapper.updateById(restored);
        }
    }

    /**
     * 场景：访问不存在的用户端路由。
     * 前置条件：该路径未注册任何控制器，且请求不带令牌。
     * 断言目标：
     * 1. 返回 404/C404，保持“资源不存在”的标准语义；
     * 2. 不能被兜底异常处理器映射成 500，否则监控会误报服务故障；
     * 该用例不创建任何工具或用户数据，属于纯路由层检查。
     * 若返回 500，通常意味着 NoHandlerFound 未被全局处理器识别。
     * 3. 404 应优先于认证检查，避免暴露哪些受保护路由真实存在。
     */
    @Test
    void unknown_route_returns_404_with_c404() {
        // 路径放在 /api/user 下，确保经过完整过滤器链而不是静态资源处理器。
        // 名称刻意不存在，避免与未来新增路由发生误判。
        // 故意使用不存在的路径，验证 NoHandlerFound 的映射策略。
        ApiResponse resp = call(HttpMethod.GET, "/api/user/nonexistent", null, null);
        // 路由缺失属于客户端可修正的问题，使用 404 而非 500。
        // 不存在的路由不应触发限流或认证副作用。
        // 统一返回 404 可减少对后端内部结构的探测。
        assertEquals(404, resp.status());
        // 业务码 C404 与状态码保持一一对应。
        assertEquals("C404", resp.body().get("code").asString());
    }

    /**
     * 场景：同一工具、同一档期连续创建两笔预约。
     * 前置条件：第一笔已成功占用档期，工具处于可借用状态并已审核通过。
     * 断言目标：
     * 1. 第一笔返回 200，确认档期被成功占用；
     * 2. 第二笔返回 409/C409，表达“资源当前状态与请求冲突”；
     * 3. 不使用 400，因为请求参数完全合法，冲突来自并发状态而不是格式；
     * 该用例依赖创建并审核工具的真实流程，覆盖了较完整的业务前置。
     * 若第一笔就失败，应先检查工具状态与档期格式，而不是冲突逻辑。
     * 4. 409 让前端可以提示“该时段已被预约”并引导用户改选时间。
     */
    @Test
    void booking_conflict_returns_409_with_c409() {
        // 两种令牌分别对应两个登录类型，不能混用。
        // 创建工具走管理端接口，预约走用户端接口，链路真实贴合前端操作。
        // 管理端令牌用于创建并审核工具，用户端令牌用于发起预约。
        String adminToken = adminLogin();
        String userToken = userLogin();
        long toolId = createTool(adminToken);
        // 选择未来档期并去掉纳秒，避免序列化格式差异导致的解析失败。
        String start = LocalDateTime.now().plusDays(2).withNano(0).format(FORMAT);
        String end = LocalDateTime.now().plusDays(3).withNano(0).format(FORMAT);
        // 两次请求复用完全相同的预约体，确保唯一变量是“档期是否已被占用”。
        Map<String, Object> booking = Map.of(
                "toolId", toolId, "startTime", start, "endTime", end, "pickupPerson", "王建国");

        // 第一笔必须成功，否则后续冲突断言失去意义。
        assertEquals(200, call(HttpMethod.POST, "/api/user/rentals", userToken, booking).status());
        // 第二笔在完全相同条件下应被状态机或占用检查拒绝。
        ApiResponse conflict = call(HttpMethod.POST, "/api/user/rentals", userToken, booking);
        // 409 Conflict 精确表达资源状态冲突。
        // 第一笔与第二笔使用同一用户与同一工具，排除权限差异。
        // 冲突判定依赖档期重叠，而非请求体格式。
        assertEquals(409, conflict.status());
        // 业务码同步为 C409，前端可据此展示档期冲突提示。
        assertEquals("C409", conflict.body().get("code").asString());
    }

    /**
     * 场景：向发送验证码接口提交非法手机号“12345”。
     * 前置条件：请求体为合法 JSON，但手机号不满足格式约束。
     * 断言目标：
     * 1. 返回 422 Unprocessable Entity，表示请求格式正确但内容不可处理；
     * 2. 业务码为 C400，与校验类错误保持统一分类；
     * 该用例只发一次请求，不依赖任何业务数据或登录状态。
     * 它是最小、最稳定的参数校验契约检查。
     * 3. 422 与 400 的分工要稳定，前端可据此把错误定位到具体字段。
     */
    @Test
    void validation_failure_returns_422_with_c400() {
        // 选择发送验证码接口，是因为它对手机号有明确的格式约束。
        // 请求体保持最小，仅包含触发校验所需的 phone 字段。
        // 匿名调用发送验证码接口，验证参数校验发生在业务发送之前。
        ApiResponse resp = call(HttpMethod.POST, "/api/user/send-code", null,
                Map.of("phone", "12345"));
        // 手机号是必填且格式严格，非法值应在发送短信前失败。
        // 这样既节省短信成本，也避免无效请求进入下游。
        // 校验失败使用 422，区别于请求体无法解析时的 400。
        assertEquals(422, resp.status());
        // 业务码统一为 C400 家族，便于前端复用校验错误展示逻辑。
        assertEquals("C400", resp.body().get("code").asString());
    }

    /**
     * 场景：管理员创建工具时名称超过 100 字上限。
     * 前置条件：已登录管理端，其他字段均合法，仅 name 超长。
     * 断言目标：
     * 1. 返回 422/C400，证明 Bean Validation 的长度约束真实生效；
     * 2. 超长字段不会进入数据库，避免列表页与导出功能被撑爆；
     * 借助管理端真实接口验证 DTO 约束，而不是直接调用校验器。
     * 因此它同时覆盖了鉴权、反序列化与 Bean Validation 三段链路。
     * 3. 只改一个字段即可触发，说明校验按字段独立执行、错误定位清晰。
     */
    @Test
    void oversized_field_returns_422() {
        // 使用合法管理员令牌，确保请求能走到 DTO 校验阶段。
        // 若鉴权失败，得到的 403 会掩盖真正的长度校验结果。
        // 管理端令牌用于通过接口鉴权，失败场景不应只测权限。
        String adminToken = adminLogin();
        // 名称恰好 101 个字符，只比上限多 1，精确验证边界值。
        ApiResponse resp = call(HttpMethod.POST, "/api/admin/tools", adminToken, Map.of(
                "name", "超".repeat(101),
                "model", "测试型号",
                "category", "WX",
                "damageLevel", "NEW",
                "deposit", 100));
        // 长度上限保护数据库列宽与前端渲染性能。
        // 只提交一个超长字段，可精确定位校验责任。
        // 字段级校验失败归入 422。
        assertEquals(422, resp.status());
        // 业务码仍为 C400，前端可统一渲染字段错误。
        assertEquals("C400", resp.body().get("code").asString());
    }

    /**
     * 场景：管理员创建工具时押金为负数。
     * 前置条件：金额字段要求非负，其余字段合法。
     * 断言目标：
     * 1. 返回 422/C400，阻止负押金写入工具表；
     * 2. 负押金会破坏结算与退款金额，必须在入口处拦截；
     * 与超长名称用例互补：一个测长度，一个测值域。
     * 二者共同验证“已鉴权用户的非法输入仍会被拒绝”。
     * 3. 即使请求已通过管理端鉴权，业务值域校验仍不可省略。
     */
    @Test
    void negative_amount_returns_422() {
        // 其余字段取合法值，让押金成为唯一失败原因。
        // 工具名称仍带测试前缀，即使意外创建成功也能被清理。
        // 合法管理员身份，确保失败原因只能是金额值域。
        String adminToken = adminLogin();
        // 押金取 -1，测试最接近边界的非法值。
        ApiResponse resp = call(HttpMethod.POST, "/api/admin/tools", adminToken, Map.of(
                "name", PREFIX + "负金额",
                "model", "测试型号",
                "category", "WX",
                "damageLevel", "NEW",
                "deposit", -1));
        // 负数押金在结算阶段会产生错误退款金额。
        // 在创建入口直接拒绝，比下游补偿更便宜。
        // 值域校验失败同样返回 422。
        assertEquals(422, resp.status());
        // 业务码 C400 与校验类错误保持一致。
        assertEquals("C400", resp.body().get("code").asString());
    }

    /**
     * 场景：更新用户资料时提交校验位错误的身份证号。
     * 前置条件：用户已登录；身份证校验器按国标（含校验位）判断失败。
     * 断言目标：
     * 1. 返回 422/C400，阻止错误实名信息落库；
     * 2. 校验必须包含最后一位校验码，而不只是长度或格式；
     * 使用一个长度正确、校验位错误的号码，专门验证国标算法而非正则。
     * 该用例不会写入资料，因此不会改变种子用户状态。
     * 3. 实名信息质量直接影响租赁与报修流程，入口校验不可放宽。
     */
    @Test
    void invalid_id_card_returns_422() {
        // 使用真实登录令牌，确保请求通过认证后再进入字段校验。
        // 只提交 idCard 一个字段，避免其他字段约束影响判断。
        // 登录用户才能访问资料更新接口。
        String userToken = userLogin();
        // 号码长度与格式看似合法，但最后一位校验位错误。
        ApiResponse resp = call(HttpMethod.PUT, "/api/user/profile", userToken,
                Map.of("idCard", "310101199001011235"));
        // 身份证校验位错误通常意味着用户输入笔误或伪造数据。
        // 拒绝写入可避免后续实名流程反复失败。
        // 国标校验失败归入 422。
        assertEquals(422, resp.status());
        // 业务码仍为 C400，前端可定位到身份证字段。
        assertEquals("C400", resp.body().get("code").asString());
    }

    /**
     * 场景：对只接受 POST 的验证码登录路径发起 GET 请求。
     * 前置条件：Spring MVC 抛出 HttpRequestMethodNotSupportedException。
     * 断言目标：
     * 1. 映射为 400/C400，表达“请求方式不正确”；
     * 2. 验证全局异常处理器的兜底分支，而不是默认的 405 或 500；
     * 该用例覆盖的是“路径存在但方法错误”，与不存在的路由不同。
     * 两者分别对应 400 与 404，边界清晰。
     * 3. 前端调用方式写错时能得到可读错误，而不是看似服务崩溃。
     */
    @Test
    void wrong_method_returns_400_with_c400() {
        // 验证码登录接口只注册了 POST，GET 请求会命中方法不支持分支。
        // 使用匿名请求，排除认证异常对状态码的干扰。
        // 路径存在但方法不匹配，触发 MVC 的方法不支持异常。
        ApiResponse resp = call(HttpMethod.GET, "/api/user/login/by-code", null, null);
        // 方法不支持本质上是客户端请求构造错误。
        // 归一为 400 可让前端复用同一套错误提示。
        // 全局处理器将其归一为 400，保持错误码家族一致。
        assertEquals(400, resp.status());
        // 业务码 C400 与状态码对应，前端无需专门处理方法不支持。
        assertEquals("C400", resp.body().get("code").asString());
    }

    /**
     * 辅助方法：发送可带令牌的 JSON 请求，返回状态码与解析后的响应体。
     * 不因 4xx/5xx 抛异常，便于直接断言错误契约；
     * 方法、路径、令牌与请求体都由调用方决定，覆盖本类全部场景。
     * <p>
     * 返回的 ApiResponse 同时保留原始状态码与 JSON，
     * 因此每个用例都能验证“HTTP 语义 + 业务码”两者是否一致。
     */
    private ApiResponse call(HttpMethod method, String url, String token, Object body) {
        // 以调用方指定的 HTTP 方法发起请求，支持 GET/POST/PUT/PATCH 等。
        // uri 直接使用相对路径，由 baseUrl 补全随机端口。
        RestClient.RequestBodySpec spec = client.method(method).uri(url);
        // token 非空时按 Sa-Token 约定附带 Bearer 令牌。
        if (token != null) {
            spec.header("Authorization", "Bearer " + token);
        }
        // 只有存在请求体时才声明 JSON 类型，避免 GET 请求携带空 body。
        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        // exchange 允许读取任意状态码的响应，不触发客户端异常。
        // 这是断言 4xx/5xx 契约的前提，否则客户端会先抛出 HttpStatusCodeException。
        return spec.exchange((request, response) -> {
            try (InputStream in = response.getBody()) {
                // 按 UTF-8 读取原始响应，保证中文错误信息不出现乱码。
                String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                // 空响应统一按空 JSON 对象处理，防止 readTree(null) 抛异常。
                return new ApiResponse(
                        response.getStatusCode().value(),
                        om.readTree(raw.isBlank() ? "{}" : raw));
            } catch (IOException e) {
                // 读取失败属于测试基础设施问题，包装为运行时异常让用例立即失败。
                throw new IllegalStateException(e);
            }
        });
    }

    /**
     * 辅助方法：创建并审核通过一个测试工具，返回工具 ID。
     * 名称使用本类固定前缀，便于清理；
     * 创建后必须再调用审核接口，否则工具不满足预约前置条件。
     * 两步都断言 200，失败时输出响应体以便定位字段或状态问题。
     */
    private long createTool(String adminToken) {
        // 创建工具：字段取最小合法集合，图片列表显式为空数组。
        ApiResponse resp = call(HttpMethod.POST, "/api/admin/tools", adminToken, Map.of(
                "name", PREFIX + "工具",
                "model", "测试型号",
                "category", "WX",
                "damageLevel", "NEW",
                "deposit", 100,
                "storageArea", "测试柜",
                "usageGuide", "测试说明",
                "imageUrls", List.of()));
        // 创建失败会直接影响后续预约用例，这里立即断言。
        assertEquals(200, resp.status(), resp.body().toString());
        // 取出新工具 ID，供审核与预约请求引用。
        // 从 data.id 读取而非响应头，保证与前端使用同一契约。
        long id = resp.body().get("data").get("id").asLong();
        // 审核通过后工具才可被用户端检索和预约。
        ApiResponse audit = call(HttpMethod.PATCH, "/api/admin/tools/" + id + "/audit",
                adminToken, Map.of("auditStatus", "APPROVED"));
        // 审核同样必须成功，否则预约会因状态不合法而失败。
        assertEquals(200, audit.status(), audit.body().toString());
        return id;
    }

    /**
     * 辅助方法：种子用户密码登录并返回令牌。
     * 登录失败时输出响应体，便于快速区分种子数据缺失与认证逻辑异常。
     */
    private String userLogin() {
        // 使用固定种子手机号与密码，避免用例依赖随机数据。
        ApiResponse resp = call(HttpMethod.POST, "/api/user/login/by-pwd", null,
                Map.of("phone", USER1_PHONE, "password", PASSWORD));
        // 登录必须成功，否则调用方拿不到可用令牌。
        assertEquals(200, resp.status(), resp.body().toString());
        // tokenValue 是 Sa-Token 返回的会话令牌，后续请求以此认证。
        // 断言字段存在可及早发现登录响应结构变更。
        return resp.body().get("data").get("tokenValue").asString();
    }

    /**
     * 辅助方法：种子管理员登录并返回令牌。
     * 管理端与用户端使用独立登录类型，令牌不能互相替代。
     */
    private String adminLogin() {
        // 管理端使用用户名而非手机号登录，与用户端形成明确区分。
        ApiResponse resp = call(HttpMethod.POST, "/api/admin/login", null,
                Map.of("username", "admin", "password", PASSWORD));
        // 登录失败会让所有管理端前置步骤失效，因此在这里统一断言。
        assertEquals(200, resp.status(), resp.body().toString());
        // 返回管理端令牌，供创建与审核工具使用。
        // 该令牌只对 /api/admin/** 有效，不能用于用户端接口。
        return resp.body().get("data").get("tokenValue").asString();
    }
}
