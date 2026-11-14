package com.cdwater.toolshare.shared.web;

import com.cdwater.toolshare.shared.enums.AcceptResult;
import com.cdwater.toolshare.shared.enums.CodeLabel;
import com.cdwater.toolshare.shared.enums.DamageLevel;
import com.cdwater.toolshare.shared.enums.DamageResult;
import com.cdwater.toolshare.shared.enums.DepositStatus;
import com.cdwater.toolshare.shared.enums.MessageType;
import com.cdwater.toolshare.shared.enums.RentalStatus;
import com.cdwater.toolshare.shared.enums.RepairProgress;
import com.cdwater.toolshare.shared.enums.ReportStatus;
import com.cdwater.toolshare.shared.enums.ToolCategory;
import com.cdwater.toolshare.shared.enums.ToolStatus;
import com.cdwater.toolshare.shared.enums.VerifyResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 业务字典与中文文案。数据库存英文代码，接口同时返回代码与名称。
 * <p>字典由 {@link CodeLabel} 枚举生成，旧调用方继续使用 Map 常量。
 *
 * <p>Map 在类加载时一次性构建并做重复代码检查，之后只读。这样既避免
 * 每次请求都反射枚举，也能在启动阶段发现枚举中重复的 code。</p>
 *
 * <p>未识别的代码在查询时回退为原值，而不是返回 null，便于前端展示
 * 历史数据而不至于出现空白。</p>
 */
public final class Dict {

    /**
     * 工具类禁止实例化。
     */
    private Dict() {
    }

    /** 工具分类：分类代码 -> 中文名。 */
    public static final Map<String, String> TOOL_CATEGORY = mapOf(ToolCategory.class);

    /** 损坏程度：程度代码 -> 中文名。 */
    public static final Map<String, String> DAMAGE_LEVEL = mapOf(DamageLevel.class);

    /** 工具实物状态：状态代码 -> 中文名。 */
    public static final Map<String, String> TOOL_STATUS = mapOf(ToolStatus.class);

    /** 租赁单状态：状态代码 -> 中文名。 */
    public static final Map<String, String> RENTAL_STATUS = mapOf(RentalStatus.class);

    /** 押金状态：状态代码 -> 中文名。 */
    public static final Map<String, String> DEPOSIT_STATUS = mapOf(DepositStatus.class);

    /** 损坏结论：结论代码 -> 中文名。 */
    public static final Map<String, String> DAMAGE_RESULT = mapOf(DamageResult.class);

    /** 报修单状态：状态代码 -> 中文名。 */
    public static final Map<String, String> REPORT_STATUS = mapOf(ReportStatus.class);

    /** 报修核验结果：结果代码 -> 中文名。 */
    public static final Map<String, String> VERIFY_RESULT = mapOf(VerifyResult.class);

    /** 维修进度：进度代码 -> 中文名。 */
    public static final Map<String, String> PROGRESS_STATUS = mapOf(RepairProgress.class);

    /** 归还验收结果：结果代码 -> 中文名。 */
    public static final Map<String, String> ACCEPT_RESULT = mapOf(AcceptResult.class);

    /** 消息类型：类型代码 -> 中文名。 */
    public static final Map<String, String> MESSAGE_TYPE = mapOf(MessageType.class);

    /**
     * 由枚举常量构建只读字典。
     *
     * <p>使用 LinkedHashMap 保留枚举声明顺序，再复制为不可变 Map。
     * 重复 code 会抛出 IllegalStateException，在启动阶段暴露配置错误。</p>
     *
     * @param type 实现 {@link CodeLabel} 的枚举类型
     * @param <E>  枚举与字典接口的交集类型
     * @return 代码到中文名的不可变映射
     */
    private static <E extends Enum<E> & CodeLabel> Map<String, String> mapOf(Class<E> type) {
        Map<String, String> map = new LinkedHashMap<>();
        for (E value : type.getEnumConstants()) {
            if (map.put(value.code(), value.label()) != null) {
                // 同一字典内 code 必须唯一，否则前端无法区分。
                throw new IllegalStateException("重复的字典代码: " + value.code());
            }
        }
        return Map.copyOf(map);
    }

    /**
     * 查询代码对应的中文名称。
     *
     * @param dict 字典映射
     * @param code 业务代码，允许为空
     * @return 中文名称；代码为空时返回 null；未知代码回退为原值
     */
    public static String label(Map<String, String> dict, String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        // 未知代码保留原值，避免历史数据展示为空白。
        return dict.getOrDefault(code, code);
    }

    /**
     * 判断字典是否包含某个代码。
     *
     * @param dict 字典映射
     * @param code 业务代码，允许为空
     * @return 代码非空且存在时返回 true
     */
    public static boolean containsKey(Map<String, String> dict, String code) {
        return code != null && dict.containsKey(code);
    }
}
