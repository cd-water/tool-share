package com.cdwater.toolshare.shared.enums;

import com.cdwater.toolshare.shared.web.Dict;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 枚举编码与字典一致性测试。
 * <p>
 * 测试范围：所有实现 CodeLabel 的业务枚举及其生成的前端字典。
 * 依赖环境：纯枚举与静态字典，无 Spring 上下文。
 * 关键不变量：每个枚举项的 code/label 非空且 code 唯一；
 * code 可无损反查枚举；Dict 映射与枚举逐项一致，旧工具方法保持兼容。
 */
class CodeLabelTest {

    /** 参与一致性校验的领域枚举清单，新增 CodeLabel 枚举时应同步加入。 */
    // 受管枚举清单：所有实现 CodeLabel 的领域枚举都必须登记在此，
    // 新增枚举若漏登记，一致性测试就会静默跳过，因此清单本身也是契约。
    // 顺序与业务模块大致对应：工具、租赁、押金、损坏、举报、维修、消息与权限。
    // 断言会遍历这里的每一个类型。
    private static final List<Class<? extends Enum<?>>> TYPES = List.of(
            // 这些枚举同时被后端字典接口与前端下拉框使用，
            // code 一旦重复或为空，前端就会出现选项错乱。
            // 因此把它们作为同一组受测对象。
            ToolCategory.class, DamageLevel.class, ToolStatus.class, RentalStatus.class,
            DepositStatus.class, DamageResult.class, ReportStatus.class, VerifyResult.class,
            RepairProgress.class, AcceptResult.class, MessageType.class, AdminRole.class);

    /**
     * 场景：遍历全部受管枚举的每个常量。
     * 前置条件：TYPES 列表完整覆盖领域枚举。
     * 断言目标：code 与 label 均非空，且同一枚举内 code 不重复，防止字典键冲突。
     */
    @Test
    void every_enum_has_code_label_and_no_duplicate_codes() {
        // 逐个枚举类型独立校验，避免不同枚举的 code 空间互相干扰。
        // 每个类型内部维护一个 code 集合，用于发现重复键。
        // 循环顺序无关紧要，断言只依赖各自类型内的数据。
        for (Class<? extends Enum<?>> type : TYPES) {
            // 使用 Set 记录已出现的 code；
            // add 返回 false 即表示当前 code 与之前的某项冲突。
            Set<String> codes = new HashSet<>();
            // getEnumConstants 按声明顺序返回全部常量，
            // 覆盖测试不依赖手工维护的列表，新增常量会自动纳入。
            // 这是防止“新增枚举项忘记补测试”的关键。
            // 空枚举会退化为零次断言，但业务枚举不应为空。
            for (Enum<?> value : type.getEnumConstants()) {
                // 所有受管枚举都实现 CodeLabel，
                // 因此可以统一提升为接口类型，读取 code 与 label。
                // 类型转换基于上面的清单约定成立。
                // 若未来有枚举未实现该接口，测试会在这里快速失败。
                CodeLabel item = (CodeLabel) value;
                // code 与 label 都必须有实际内容：
                // code 用于持久化与跨端传输，label 用于界面展示。
                // 空白值会造成字典键缺失或界面显示空选项。
                assertFalse(item.code().isBlank(), type.getSimpleName() + " code 不能为空");
                assertFalse(item.label().isBlank(), type.getSimpleName() + " label 不能为空");
                // 同一枚举内 code 必须唯一，
                // 否则字典 Map 会覆盖前一项，导致部分状态无法展示。
                // codes.add 的返回值正好承担去重断言。
                assertTrue(codes.add(item.code()), type.getSimpleName() + " 存在重复 code: " + item.code());
            }
        }
    }

    /**
     * 场景：对每个枚举项用自身 code 反查，并尝试未知 code 与 null。
     * 前置条件：枚举提供静态 fromCode 方法。
     * 断言目标：反查结果与原始常量同一实例；未知值返回空 Optional 而非默认值。
     */
    @Test
    void from_code_round_trips_every_value_and_rejects_unknown() {
        // 对每个类型分别验证 fromCode 的反查能力；
        // 反查是前端把历史 code 还原为枚举实例的统一入口。
        // 循环覆盖所有常量，避免只测少数代表值。
        for (Class<? extends Enum<?>> type : TYPES) {
            // 遍历该类型的全部枚举常量，
            // 用常量自身的 code 作为查询条件，验证往返一致性。
            // 这是“编码 -> 解码”不丢失信息的基本要求。
            for (Enum<?> value : type.getEnumConstants()) {
                // 取出接口视图，便于同时访问 code 与 label，
                // 不依赖各枚举自己的方法签名。
                CodeLabel item = (CodeLabel) value;
                // 通过反射调用静态 fromCode，
                // 让同一套测试逻辑可以复用于所有 CodeLabel 枚举。
                // 这样可以避免为每个枚举再写一份重复用例。
                Optional<? extends Enum<?>> found = fromCode(type, item.code());
                // 已知 code 必须能解析出结果；
                // 返回空 Optional 意味着存在无法反查的历史值，属于数据兼容性缺陷。
                // 失败信息包含类型名与 code，便于定位。
                assertTrue(found.isPresent(), type.getSimpleName() + " 未能解析 " + item.code());
                // 解析结果必须与原始常量是同一个枚举实例，
                // 使用 assertSame 而不是 equals，确保没有产生替身或错误映射。
                // 枚举实例天然单例，身份比较最严格。
                assertSame(value, found.get());
            }
            // 未知 code 必须返回空 Optional，而不是回退到某个默认枚举；
            // 静默兜底会把脏数据伪装成合法状态，掩盖上游问题。
            // 这是接口层与迁移脚本共同依赖的安全语义。
            assertTrue(fromCode(type, "NOT_A_CODE").isEmpty(), type.getSimpleName() + " 应拒绝未知 code");
            // null 同样是非法输入，应返回空 Optional，
            // 避免在字典查询或数据迁移时抛出空指针。
            // 各枚举的 fromCode 实现需保持一致的空值处理。
            assertTrue(fromCode(type, null).isEmpty(), type.getSimpleName() + " 应拒绝 null");
        }
    }

    /**
     * 场景：逐个核对 Dict 中暴露的枚举字典。
     * 前置条件：Dict 字典由枚举统一生成。
     * 断言目标：字典键值、条目数量与枚举完全一致，前端展示不会出现缺失或多余项。
     */
    @Test
    void dict_maps_are_generated_from_enums() {
    /**
     * 辅助方法：对某个枚举校验其 Dict 映射与 fromCode 反查。
     * 同时断言字典条目数等于枚举常量数，防止手工维护字典时漏项。
     */
        // 逐个核对 Dict 暴露给前端的字典映射；
        // 每个枚举都有对应的静态 Map，键为 code、值为中文标签。
        // 这些断言保证接口返回值与枚举定义同步，不出现手工维护的偏差。
        // 传入 fromCode 方法引用，用于同时校验反查路径。
        assertDict(ToolCategory.class, Dict.TOOL_CATEGORY, ToolCategory::fromCode);
        // 工具分类：与工具列表筛选、发布表单的分类下拉框一致。
        // 后续各项分别对应损坏、状态、租赁、押金等业务字典。
        assertDict(DamageLevel.class, Dict.DAMAGE_LEVEL, DamageLevel::fromCode);
        assertDict(ToolStatus.class, Dict.TOOL_STATUS, ToolStatus::fromCode);
        assertDict(RentalStatus.class, Dict.RENTAL_STATUS, RentalStatus::fromCode);
        assertDict(DepositStatus.class, Dict.DEPOSIT_STATUS, DepositStatus::fromCode);
        assertDict(DamageResult.class, Dict.DAMAGE_RESULT, DamageResult::fromCode);
        assertDict(ReportStatus.class, Dict.REPORT_STATUS, ReportStatus::fromCode);
        assertDict(VerifyResult.class, Dict.VERIFY_RESULT, VerifyResult::fromCode);
        assertDict(RepairProgress.class, Dict.PROGRESS_STATUS, RepairProgress::fromCode);
        assertDict(AcceptResult.class, Dict.ACCEPT_RESULT, AcceptResult::fromCode);
        // 消息类型与管理员角色也走同一套字典机制，
        // 保证通知文案与权限展示在前后端使用同一份编码。
        // 所有字典都纳入循环校验后，接口层才不会漏掉某一类枚举。
        assertDict(MessageType.class, Dict.MESSAGE_TYPE, MessageType::fromCode);
    }

    /**
     * 场景：调用旧的 label/containsKey 辅助方法。
     * 前置条件：历史代码仍依赖这些方法，包括未知 code 原样返回的旧行为。
     * 断言目标：已知项返回中文标签、未知项原样返回、null 返回 null，兼容性不被破坏。
     */
    @Test
    void legacy_dict_helpers_still_work() {
        assertEquals("维修工具", Dict.label(Dict.TOOL_CATEGORY, "WX"));
        // 未识别代码保持旧行为：原样返回
        assertEquals("MYSTERY", Dict.label(Dict.TOOL_CATEGORY, "MYSTERY"));
        assertEquals(null, Dict.label(Dict.TOOL_CATEGORY, null));
        assertTrue(Dict.containsKey(Dict.TOOL_STATUS, "AVAILABLE"));
        assertFalse(Dict.containsKey(Dict.TOOL_STATUS, "MYSTERY"));
    }

    private <E extends Enum<E> & CodeLabel> void assertDict(
            Class<E> type, Map<String, String> dict, Function<String, Optional<E>> fromCode) {
        // 辅助方法：对一个枚举同时校验两层映射。
        // 第一层是 Dict 字典中的中文标签，第二层是由 code 反查枚举实例；
        // 两层都通过才算前后端契约一致。
        // 泛型约束 E 同时要求是枚举且实现 CodeLabel。
        for (E value : type.getEnumConstants()) {
            // 标签必须与枚举声明完全相同，
            // 否则前端展示的文案会与后端业务语义词不符。
            assertEquals(value.label(), dict.get(value.code()), type.getSimpleName() + " 字典不一致");
            // 反查结果必须存在且等于当前常量，
            // orElseThrow 在缺少映射时直接让测试失败。
            // 这一步覆盖字典与 fromCode 的双向一致性。
            assertEquals(value, fromCode.apply(value.code()).orElseThrow());
        }
        // 条目数量也要相等：
        // 多余条目通常是历史遗留未清理，缺少条目则是新增枚举忘记同步。
        // 数量校验能发现“键值都对但多一项/少一项”的问题。
        assertEquals(type.getEnumConstants().length, dict.size());
    }

    /**
     * 辅助方法：通过反射调用枚举的静态 fromCode，统一处理受检异常。
     * 这样同一套断言可以覆盖所有枚举类型。
     */
    private Optional<? extends Enum<?>> fromCode(Class<? extends Enum<?>> type, String code) {
        // 通过反射统一调用各枚举的静态 fromCode(String) 方法，
        // 避免测试为每个枚举写重复的适配代码。
        // 反射失败会被包装成 AssertionError，保持测试失败语义。
        try {
            // 调用约定为 public static Optional<枚举> fromCode(String code)，
            // 这里按同一签名查找并 invoke，null 也会原样传入。
            // 方法不存在或签名不符会进入下方的异常包装。
            return (Optional<? extends Enum<?>>) type.getMethod("fromCode", String.class).invoke(null, code);
        // 受检异常统一转为 AssertionError：
        // 测试失败信息应指向契约破坏，而不是暴露反射细节。
        // 这样上层用例无需声明 throws。
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
