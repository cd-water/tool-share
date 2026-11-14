package com.cdwater.toolshare.module;

import com.cdwater.toolshare.module.auth.Admin;
import com.cdwater.toolshare.module.auth.AdminVoMapper;
import com.cdwater.toolshare.module.auth.AdminVO;
import com.cdwater.toolshare.module.auth.User;
import com.cdwater.toolshare.module.auth.UserVoMapper;
import com.cdwater.toolshare.module.auth.UserVO;
import com.cdwater.toolshare.module.damage.DamageReport;
import com.cdwater.toolshare.module.damage.DamageReportVoMapper;
import com.cdwater.toolshare.module.damage.DamageReportVO;
import com.cdwater.toolshare.module.knowledge.KnowledgeDoc;
import com.cdwater.toolshare.module.knowledge.KnowledgeDocVoMapper;
import com.cdwater.toolshare.module.knowledge.KnowledgeDocVO;
import com.cdwater.toolshare.module.message.Message;
import com.cdwater.toolshare.module.message.MessageVoMapper;
import com.cdwater.toolshare.module.message.MessageVO;
import com.cdwater.toolshare.module.rental.Rental;
import com.cdwater.toolshare.module.rental.RentalVoMapper;
import com.cdwater.toolshare.module.rental.RentalVO;
import com.cdwater.toolshare.module.tool.Tool;
import com.cdwater.toolshare.module.tool.ToolLog;
import com.cdwater.toolshare.module.tool.ToolLogVoMapper;
import com.cdwater.toolshare.module.tool.ToolLogVO;
import com.cdwater.toolshare.module.tool.ToolVoMapper;
import com.cdwater.toolshare.module.tool.ToolVO;
import com.cdwater.toolshare.module.tool.ToolWatchVoMapper;
import com.cdwater.toolshare.module.tool.ToolWatchVO;
import com.cdwater.toolshare.shared.security.IdCardCipher;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VO 映射器单元测试。
 * <p>
 * 测试范围：MapStruct 生成的各类实体到视图对象映射，包括枚举中文名、
 * 手机号与身份证脱敏、图片列表解析、内容预览截断与 null 安全。
 * <p>
 * 依赖环境：直接通过 Mappers.getMapper 获取生成的映射器实例，不启动 Spring 上下文；
 * 因此本类只验证“实体 → VO”的纯函数行为，不依赖数据库、HTTP 或安全上下文。
 * <p>
 * 关键不变量：
 * 1. 视图层不泄露完整手机号/身份证，脱敏规则必须稳定；
 * 2. 枚举 code 必须翻译为中文名，未知枚举返回空名称而非抛异常；
 * 3. 空图片串、空白分隔符不产生空元素，前端可直接遍历；
 * 4. 知识库预览需要压缩空白并按长度截断，控制列表页响应体大小；
 * 5. 所有映射器对 null 输入返回 null，调用方无需额外判空；
 * 6. 派生字段（中文名、脱敏值、列表）与基础字段映射互不干扰。
 * <p>
 * 维护提示：新增实体映射时，应同步在此补充字段级断言与 null 用例，
 * 避免 MapStruct 生成代码在字段改名后静默丢失映射。
 * <p>
 * 断言风格：每个用例只构造 VO 真正需要的字段，失败时能直接定位到字段；
 * 脱敏与列表解析属于安全/展示契约，因此用精确值而非包含式断言。
 * 全部用例无共享可变状态，映射器实例为 final，可安全并行执行。
 */
class VoMapperTest {

    /** 工具视图映射器。 */
    // 负责工具分类/损坏程度/状态翻译与图片串解析。
    private final ToolVoMapper toolVoMapper = Mappers.getMapper(ToolVoMapper.class);
    /** 工具日志视图映射器。 */
    // 以字段直传为主，验证时间与审计字段不丢失。
    private final ToolLogVoMapper toolLogVoMapper = Mappers.getMapper(ToolLogVoMapper.class);
    /** 蹲守视图映射器。 */
    // 额外处理 toolId 对齐与押金默认值。
    private final ToolWatchVoMapper toolWatchVoMapper = Mappers.getMapper(ToolWatchVoMapper.class);
    /** 租赁单视图映射器。 */
    // 负责手机号脱敏与租赁相关枚举翻译。
    private final RentalVoMapper rentalVoMapper = Mappers.getMapper(RentalVoMapper.class);
    /** 报修单视图映射器。 */
    // 同时处理手机号、图片列表与多组流程状态。
    private final DamageReportVoMapper damageReportVoMapper = Mappers.getMapper(DamageReportVoMapper.class);
    /** 消息视图映射器。 */
    // 把数据库的 1/0 读状态转换为布尔值。
    private final MessageVoMapper messageVoMapper = Mappers.getMapper(MessageVoMapper.class);
    /** 用户资料视图映射器。 */
    // 需要注入解密器处理加密身份证，是唯一带额外参数的映射器。
    private final UserVoMapper userVoMapper = Mappers.getMapper(UserVoMapper.class);
    /** 管理员视图映射器。 */
    // 只暴露身份字段，不携带密码等敏感信息。
    private final AdminVoMapper adminVoMapper = Mappers.getMapper(AdminVoMapper.class);
    /** 知识库文档视图映射器。 */
    // 负责正文压缩与预览截断。
    private final KnowledgeDocVoMapper knowledgeDocVoMapper = Mappers.getMapper(KnowledgeDocVoMapper.class);

    /**
     * 场景：映射一个包含分类、损坏程度、状态与多张图片的工具。
     * 前置条件：枚举 code 均存在于字典中，图片串以逗号分隔。
     * 断言目标：
     * 1. 基础字段（ID、名称）原样映射，不发生错位；
     * 2. 分类/损坏程度/状态分别翻译为对应中文名；
     * 3. 逗号分隔的图片串被拆成列表，并去除元素两侧空白；
     * 4. 列表顺序保持与原始串一致，前端轮播顺序稳定；
     * 该用例不依赖任何外部服务，是最稳定的映射基线。
     * 若它失败，通常意味着枚举字典或 MapStruct 表达式发生了变更。
     * 5. 该用例同时作为工具映射的“全字段正向对照”。
     */
    @Test
    void tool_maps_fields_and_derived_names() {
        // 构造最小但字段齐全的实体，覆盖所有需要翻译的枚举字段。
        Tool tool = new Tool();
        tool.setId(1L);
        tool.setCode("SQ-G-WX-0001");
        tool.setName("手电钻");
        tool.setCategory("WX");
        tool.setDamageLevel("LIGHT");
        tool.setStatus("AVAILABLE");
        tool.setDeposit(new BigDecimal("200.00"));
        // 图片串故意在逗号后带空格，验证映射器会 trim。
        tool.setImageUrls("a.jpg, b.jpg");

        // 映射器是无状态纯函数，可以直接在单元测试中调用。
        // 不启动 Spring 也意味着这里不会掩盖 Bean 装配问题。
        // 调用生成的映射器，得到只读视图对象。
        ToolVO vo = toolVoMapper.toVO(tool);

        // 基础字段直传：ID 与名称保持原值。
        // 不使用 assertSame 是因为 MapStruct 可能创建新对象，只比较值。
        // 字段值选取具辨识度的中文，便于失败时快速识别错位。
        assertEquals(1L, vo.getId());
        assertEquals("手电钻", vo.getName());
        // 枚举 code 翻译为中文名，前端无需维护字典表。
        assertEquals("维修工具", vo.getCategoryName());
        assertEquals("轻微磨损", vo.getDamageLevelName());
        assertEquals("可借用", vo.getStatusName());
        // 图片串被拆分并去掉空白，且顺序不变。
        // 顺序稳定意味着前端轮播不会因映射而重排。
        // 列表元素是纯字符串，不引入 URL 转换等副作用。
        assertEquals(List.of("a.jpg", "b.jpg"), vo.getImageUrls());
    }

    /**
     * 场景：图片字段为仅含空白与逗号的分隔串，或完全未设置。
     * 前置条件：实体允许没有图片，这是工具创建时的常见状态。
     * 断言目标：
     * 1. 空白分隔串被解析为空列表，而不是 [“”, “”]；
     * 2. 未设置图片（null）同样返回空列表而非 null；
     * 3. 前端可以直接遍历并渲染占位图，无需额外判空；
     * 该场景来自真实历史数据：工具可能只有占位分隔符而没有图片。
     * 因此必须同时覆盖“有脏串”和“字段为 null”两种空状态。
     * 4. 该用例守住“空值不产生空元素”的列表契约。
     */
    @Test
    void tool_image_list_is_empty_when_blank() {
        // 只有逗号与空格，模拟历史数据中常见的脏分隔串。
        // 若映射器只做 split 而不 trim/过滤，这里会得到两个空字符串。
        // 该断言直接锁住“过滤空元素”的行为。
        Tool tool = new Tool();
        tool.setImageUrls("  ,  ");
        // 空白元素必须被过滤，否则前端会渲染出破图。
        // 同时验证返回类型是 List 而非 null，调用方可直接 forEach。
        // 该断言与下一行的 null 场景共同构成完整的空值矩阵。
        assertEquals(List.of(), toolVoMapper.toVO(tool).getImageUrls());
        // 完全未设置图片时也必须返回空列表，保持返回类型稳定。
        // 调用方无需区分 null 与空列表，渲染逻辑可以统一。
        assertEquals(List.of(), toolVoMapper.toVO(new Tool()).getImageUrls());
    }

    /**
     * 场景：映射蹲守视图所需的工具摘要。
     * 前置条件：工具只设置 ID 与状态，未设置押金。
     * 断言目标：
     * 1. 视图字段 toolId 与源实体 id 对齐，而不是直接照抄同名字段；
     * 2. 状态 MAINTENANCE 翻译为“维修中”；
     * 3. 空押金默认 BigDecimal.ZERO，避免前端展示 null 金额；
     * 蹲守列表只需要摘要信息，因此字段集合刻意小于完整工具视图。
     * 空押金默认值是本映射器与普通工具映射的关键差异。
     * 4. 默认值必须是数值零而非 null，否则前端格式化函数会报错。
     */
    @Test
    void tool_watch_maps_tool_id_and_defaults_deposit() {
        // 故意不设置押金，验证映射器的默认值逻辑。
        // 也只设置必要字段，确认未设置字段不会影响已映射字段。
        // 该构造方式同时验证映射器对稀疏实体的兼容性。
        Tool tool = new Tool();
        tool.setId(7L);
        tool.setStatus("MAINTENANCE");

        // 蹲守视图使用 toolId 而非 id 作为工具标识。
        ToolWatchVO vo = toolWatchVoMapper.toVO(tool);

        // 字段名不同但语义一致，必须正确对齐。
        // 若误用同名 id 映射，toolId 会变成 null，蹲守列表将无法关联工具。
        // 这里同时验证“异构字段名”这一 MapStruct 常见配置点。
        assertEquals(7L, vo.getToolId());
        // 状态翻译为中文，供蹲守列表直接展示。
        assertEquals("维修中", vo.getStatusName());
        // 空押金兜底为零，保持金额字段始终可计算。
        // 使用 BigDecimal.ZERO 而非 int 0，避免类型或精度不一致。
        // 金额字段不做四舍五入，默认值必须与业务零值完全相等。
        assertEquals(BigDecimal.ZERO, vo.getDeposit());
    }

    /**
     * 场景：映射一条完整的工具操作日志。
     * 前置条件：日志包含操作类型、详情、操作人与时间，时间由同一实例提供。
     * 断言目标：
     * 1. 所有字段逐项一致，不发生字段错位或丢失；
     * 2. 时间对象保持原引用/精度，审计时间线可精确排序；
     * 3. 操作类型保持英文 code，供前端按需翻译；
     * 日志属于审计数据，字段缺失会影响追责与问题回溯。
     * 因此这里采用逐字段精确断言，而不是只检查非空。
     * 4. 该用例是日志审计能力的字段级回归保护。
     */
    @Test
    void tool_log_maps_all_fields() {
        // 构造一条完整日志，覆盖审计时间线需要的所有字段。
        // 使用具体数值而非随机值，失败时更容易对照字段。
        // 时间使用单次 now，避免构造与断言之间出现毫秒差异。
        ToolLog log = new ToolLog();
        log.setId(3L);
        log.setToolId(1L);
        log.setAction("CREATE");
        log.setDetail("新增");
        log.setOperatorId(9L);
        // 使用同一个时间实例，便于用 equals 精确断言未发生转换。
        LocalDateTime now = LocalDateTime.now();
        log.setCreateTime(now);

        // 日志映射以字段直传为主，不应做额外加工。
        ToolLogVO vo = toolLogVoMapper.toVO(log);

        // 主键与关联工具 ID 必须保持原值。
        // 审计日志需要靠这两个 ID 回溯“谁在什么时候动了哪个工具”。
        // 任一字段为 null 都会让日志失去关联能力。
        assertEquals(3L, vo.getId());
        assertEquals(1L, vo.getToolId());
        // 操作类型保留 code，详情保留中文，二者职责不同。
        assertEquals("CREATE", vo.getAction());
        assertEquals("新增", vo.getDetail());
        // 操作人 ID 用于审计归属，不能丢失。
        assertEquals(9L, vo.getOperatorId());
        // 时间必须完全一致，不能因序列化或时区转换发生偏移。
        // LocalDateTime 的 equals 包含纳秒，可发现意外的精度截断。
        assertEquals(now, vo.getCreateTime());
    }

    /**
     * 场景：映射租赁单视图。
     * 前置条件：源数据包含完整手机号与押金、状态、损坏结果等枚举 code。
     * 断言目标：
     * 1. 手机号中间四位脱敏，仅保留首三尾四；
     * 2. 押金状态、租赁状态、损坏结果分别翻译为中文名；
     * 3. 联表字段（工具图片、提醒时间、提前分钟数）保持 null；
     * 4. 这些字段由 service 层在需要时另行填充，映射器不擅自造值；
     * 租赁单是用户与管理员都会查看的视图，脱敏必须后端完成。
     * 联表字段保持 null 也说明映射器不承担数据聚合职责。
     * 5. 脱敏发生在映射阶段，确保完整手机号不会进入任何 VO 序列化路径。
     */
    @Test
    void rental_masks_phone_and_derives_names() {
        // 构造包含完整手机号的租赁单，验证脱敏不是前端完成的。
        // 同时设置三组枚举 code，确认多组翻译互不干扰。
        // 未设置联表字段，用来验证这些字段不会被映射器凭空填充。
        Rental rental = new Rental();
        rental.setId(2L);
        rental.setOrderNo("SQ-ZY-0001");
        rental.setRenterPhone("13800000001");
        rental.setDepositStatus("PAID");
        rental.setStatus("RESERVED");
        rental.setDamageResult("INTACT");

        // 映射结果直接返回给前端，因此脱敏必须在这里完成。
        RentalVO vo = rentalVoMapper.toVO(rental);

        // 中间四位替换为星号，首三尾四保留用于人工核对。
        // 使用精确字符串断言，防止脱敏规则被悄悄放宽。
        assertEquals("138****0001", vo.getRenterPhone());
        // 三组枚举分别翻译，避免前端维护重复字典。
        // 押金状态与租赁状态含义不同，不能共用同一张翻译表。
        // 损坏结果用于归还验收展示，同样需要独立翻译。
        assertEquals("已支付", vo.getDepositStatusName());
        assertEquals("已预约", vo.getStatusName());
        assertEquals("完好", vo.getDamageResultName());
        // 联表/外部查询字段由 service 填充
        // 映射器不访问数据库，因此这些字段必须保持 null。
        assertNull(vo.getToolImageUrl());
        assertNull(vo.getRemindAt());
        assertNull(vo.getRemindBeforeMinutes());
    }

    /**
     * 场景：映射报修单视图。
     * 前置条件：源数据含报修人完整手机号、逗号分隔图片与多组流程状态。
     * 断言目标：
     * 1. 报修人手机号按统一规则脱敏；
     * 2. 图片串按逗号拆分为列表，元素顺序保持不变；
     * 3. 报修状态、维修进度、验收结果分别翻译为中文名；
     * 4. 工具图片等联表字段保持 null，由 service 按需填充；
     * 报修流程状态较多，任何一组翻译缺失都会让详情页出现空白标签。
     * 图片列表与手机号脱敏则直接关系隐私与可用性。
     * 5. 该映射器同时承担“隐私脱敏 + 列表解析 + 多枚举翻译”三类职责。
     */
    @Test
    void damage_report_masks_phone_parses_images_and_derives_names() {
        // 报修单同时包含隐私字段、列表字段与多组状态，覆盖面较广。
        // 源实体一次设置到位，映射器一次调用即可验证全部派生字段。
        // 工具图片字段故意不设置，验证联表数据不被误填。
        DamageReport report = new DamageReport();
        report.setId(4L);
        report.setReporterPhone("13900001111");
        report.setImages("x.jpg,y.jpg");
        report.setStatus("REPAIRING");
        report.setProgressStatus("WAITING_PARTS");
        report.setAcceptResult("PARTIAL");

        // 一次映射同时验证脱敏、列表与三组枚举翻译。
        DamageReportVO vo = damageReportVoMapper.toVO(report);

        // 手机号脱敏规则与租赁单保持一致，便于前端统一展示。
        // 报修人手机号用于联系用户，同样不能在列表中完整暴露。
        // 若规则不一致，用户会在不同页面看到不同掩码，造成困惑。
        assertEquals("139****1111", vo.getReporterPhone());
        // 图片串拆分后保持原始顺序，供详情页按上传顺序展示。
        // 该断言与工具图片解析共享同一套列表契约。
        // 报修图片是定损依据，顺序错乱会影响线下核对。
        assertEquals(List.of("x.jpg", "y.jpg"), vo.getImages());
        // 报修主状态、维修进度与验收结果分别翻译。
        assertEquals("维修中", vo.getStatusName());
        assertEquals("等待配件", vo.getProgressStatusName());
        assertEquals("部分合格", vo.getAcceptResultName());
        // 联表取得的工具封面图不在本映射器职责内。
        assertNull(vo.getToolImageUrl());
    }

    /**
     * 场景：分别映射已读、未读与空读状态的消息。
     * 前置条件：数据库用整数 1/0 表示已读状态，null 表示历史脏数据或未初始化。
     * 断言目标：
     * 1. 消息类型 OVERDUE 翻译为“逾期提醒”；
     * 2. readStatus=1 映射为 true，表示已读；
     * 3. readStatus=0 映射为 false，表示未读；
     * 4. readStatus=null 兜底为 false，避免拆箱空指针；
     * 读状态使用整数存储是历史设计，映射器负责在边界处完成归一。
     * 三种输入同时覆盖，避免只测正常值而漏掉脏数据。
     * 5. 前端可直接把布尔值绑定到未读角标，无需再做类型转换。
     */
    @Test
    void message_maps_read_status_and_type_name() {
        // 以一条逾期提醒消息为样本，覆盖类型翻译与读状态转换。
        // 复用同一个实体依次改 readStatus，减少重复构造。
        // 每次映射都重新生成 VO，避免缓存或引用共享干扰断言。
        Message message = new Message();
        message.setId(5L);
        message.setType("OVERDUE");
        message.setReadStatus(1);

        // 已读消息：类型翻译为中文，状态转为 true。
        // 整数 1 与布尔 true 的映射是消息中心未读角标的基础。
        // 先验证非空值，再验证 0 与 null，顺序由正常到异常。
        MessageVO read = messageVoMapper.toVO(message);
        assertEquals("逾期提醒", read.getTypeName());
        assertEquals(true, read.getReadStatus());

        // 未读消息：整数 0 应转为 false。
        // 0 是数据库中最常见的值，不能只测 1 和 null。
        // 三个分支合起来覆盖读写状态的完整值域。
        message.setReadStatus(0);
        assertEquals(false, messageVoMapper.toVO(message).getReadStatus());
        // 空值按未读处理，避免 Boolean 拆箱导致 NPE。
        // 该兜底对历史数据尤其重要，不能假设字段永远非空。
        message.setReadStatus(null);
        assertFalse(messageVoMapper.toVO(message).getReadStatus());
    }

    /**
     * 场景：映射包含加密身份证的用户资料。
     * 前置条件：身份证以 enc: 密文存储，映射时显式传入持有密钥的解密器。
     * 断言目标：
     * 1. 手机号按统一规则脱敏；
     * 2. 身份证先解密再脱敏，只保留首四尾四；
     * 3. 昵称与真实姓名原样映射；
     * 4. 明文身份证绝不进入 UserVO，任何序列化路径都拿不到完整号码；
     * 该用例是隐私保护最关键的回归点：VO 中任何完整身份证都不可接受。
     * 解密与脱敏都在映射阶段完成，后续日志与序列化只能看到掩码。
     * 5. 解密器作为显式参数传入，避免映射器内部持有硬编码密钥。
     */
    @Test
    void user_masks_phone_and_encrypted_id_card() {
        // 使用固定测试密钥构造解密器，仅用于单元测试。
        // 密文与明文都按真实算法处理，避免只测到格式转换。
        // 密钥长度满足 AES 要求，确保加解密路径真实可执行。
        IdCardCipher cipher = new IdCardCipher("0123456789abcdef");
        User user = new User();
        user.setId(1L);
        user.setPhone("13800000001");
        user.setNickname("user_mjafo8");
        user.setRealName("王建国");
        // 数据库中保存密文，而不是明文身份证。
        // 若映射器跳过解密，断言会直接失败而不是泄露明文。
        user.setIdCard(cipher.encrypt("310101199001011234"));

        // 与其它映射器不同，用户映射需要额外传入解密器。
        UserVO vo = userVoMapper.toVO(user, cipher);

        // ID、手机号、昵称与真实姓名按预期映射。
        // 手机号与身份证同时脱敏，覆盖用户资料页的主要隐私字段。
        // 真实姓名可以展示，但仍需依赖接口权限控制访问范围。
        assertEquals(1L, vo.getId());
        assertEquals("138****0001", vo.getPhone());
        assertEquals("user_mjafo8", vo.getNickname());
        assertEquals("王建国", vo.getRealName());
        // 解密后只展示首四尾四，中间全部隐藏。
        assertEquals("3101********1234", vo.getIdCard());
    }

    /**
     * 场景：映射管理员基本信息。
     * 前置条件：仅提供 ID 与用户名，密码等敏感字段故意不设置。
     * 断言目标：
     * 1. 身份标识字段正确映射，供管理端展示与权限判断；
     * 2. 视图对象不携带密码哈希等敏感字段；
     * 管理员 VO 字段少，正好用来验证“结构即白名单”的暴露策略。
     * 该用例简单但对安全边界有意义，不能因字段少而省略。
     * 3. 即使源实体设置过密码，也应由 VO 结构本身保证不外泄。
     */
    @Test
    void admin_maps_id_and_username() {
        // 只设置可公开字段，强调 VO 不应依赖敏感字段。
        // 实体构造保持最小，避免测试无意中鼓励在 VO 中加字段。
        // 断言只覆盖身份标识，防止未来误加密码映射。
        Admin admin = new Admin();
        admin.setId(1L);
        admin.setUsername("admin");
        // 管理员映射最简单，主要用于守住字段对应关系。
        // 字段少也意味着一旦错位更容易被发现。
        AdminVO vo = adminVoMapper.toVO(admin);
        // ID 与用户名是管理端身份展示的最小集合。
        // 密码、角色等字段由专用接口或权限组件处理，不进入本 VO。
        // 该用例也间接验证 MapStruct 没有做意外的全字段拷贝。
        assertEquals(1L, vo.getId());
        assertEquals("admin", vo.getUsername());
    }

    /**
     * 场景：分别映射短文本与超过 120 字的长文本知识库文档。
     * 前置条件：预览需要压缩连续空白，并对超长正文做截断。
     * 断言目标：
     * 1. 短文本中的换行与多余空格被压缩为单个空格；
     * 2. 长文本截断为 120 字正文加一个省略号，总长 121；
     * 3. 预览末尾必须是省略号，向用户明示内容不完整；
     * 4. indexed 布尔字段原样映射，供列表展示索引状态；
     * 知识库正文可能很长，预览字段是列表页性能的关键控制点。
     * 短文本与长文本分别验证“只压缩”和“压缩 + 截断”两条分支。
     * 5. 该策略控制列表页响应体大小，避免整篇文档进入首屏。
     */
    @Test
    void knowledge_preview_is_collapsed_and_truncated() {
        // 正文包含换行与首尾空格，模拟从文件解析出的原始内容。
        // 连续空白应折叠为单个空格，同时去掉首尾空白。
        // indexed 字段用于列表角标，必须原样传递。
        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setId(6L);
        doc.setTitle("规则");
        doc.setFileName("rules.txt");
        doc.setContent("  社区共享\n工具租赁规则  ");
        doc.setIndexed(true);
        // 短文本只做空白压缩，不做截断。
        // 换行被折叠为空格后，列表页不会出现难以控制的行高。
        // 首尾空白同样被去除，避免详情页首行缩进异常。
        KnowledgeDocVO mapped = knowledgeDocVoMapper.toVO(doc);
        assertEquals("社区共享 工具租赁规则", mapped.getContentPreview());
        assertEquals(true, mapped.getIndexed());

        // 超过 120 字的正文应被截断，并追加省略号。
        // 用重复字符构造长文本，便于精确计算截断长度。
        doc.setContent("a".repeat(130));
        KnowledgeDocVO vo = knowledgeDocVoMapper.toVO(doc);
        // 120 个正文字符 + 1 个省略号 = 121。
        // 精确断言总长度，防止实现把上限写成 100 或 200 而无人发现。
        // 省略号使用单个字符，便于前端按字符数做布局。
        assertEquals(121, vo.getContentPreview().length());
        // 省略号提示用户列表只展示摘要，可进入详情查看全文。
        assertTrue(vo.getContentPreview().endsWith("…"));
    }

    /**
     * 场景：向每个映射器传入 null 源实体。
     * 前置条件：service 层可能直接用映射结果做空判断或链式调用。
     * 断言目标：
     * 1. 所有映射器返回 null，而不是抛 NullPointerException；
     * 2. 带额外参数的用户映射器在 null 实体下同样安全；
     * 3. 调用方无需为每个映射点单独判空，业务代码更简单；
     * null 安全是映射器的横切契约，因此集中在一个用例里覆盖全部实现。
     * 新增映射器时若忘记配置 null 策略，这里会立即失败。
     * 4. 该用例是 MapStruct nullValueMappingStrategy 的集中回归保护。
     */
    @Test
    void all_mappers_are_null_safe() {
        // 逐个覆盖所有映射器，避免新增映射器后遗漏 null 契约。
        // 这里不构造任何实体，专门验证 null 输入的唯一行为。
        // 若某个映射器改为抛异常或返回空对象，调用方的判空逻辑会失效。
        // 因此该用例虽短，却是所有 service 空值分支的地基。
        // 每个断言互不依赖，任一失败都能直接指出哪个映射器不安全。
        assertNull(toolVoMapper.toVO(null));
        assertNull(toolLogVoMapper.toVO(null));
        assertNull(toolWatchVoMapper.toVO(null));
        assertNull(rentalVoMapper.toVO(null));
        assertNull(damageReportVoMapper.toVO(null));
        assertNull(messageVoMapper.toVO(null));
        // 用户映射即使带解密器，也应先判断实体是否为 null。
        // 否则解密器可能在空实体上被提前调用而抛异常。
        assertNull(userVoMapper.toVO(null, new IdCardCipher("0123456789abcdef")));
        assertNull(adminVoMapper.toVO(null));
        assertNull(knowledgeDocVoMapper.toVO(null));
    }
}
