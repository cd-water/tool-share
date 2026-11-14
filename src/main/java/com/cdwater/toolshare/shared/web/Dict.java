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
 */
public final class Dict {

    private Dict() {
    }

    public static final Map<String, String> TOOL_CATEGORY = mapOf(ToolCategory.class);

    public static final Map<String, String> DAMAGE_LEVEL = mapOf(DamageLevel.class);

    public static final Map<String, String> TOOL_STATUS = mapOf(ToolStatus.class);

    public static final Map<String, String> RENTAL_STATUS = mapOf(RentalStatus.class);

    public static final Map<String, String> DEPOSIT_STATUS = mapOf(DepositStatus.class);

    public static final Map<String, String> DAMAGE_RESULT = mapOf(DamageResult.class);

    public static final Map<String, String> REPORT_STATUS = mapOf(ReportStatus.class);

    public static final Map<String, String> VERIFY_RESULT = mapOf(VerifyResult.class);

    public static final Map<String, String> PROGRESS_STATUS = mapOf(RepairProgress.class);

    public static final Map<String, String> ACCEPT_RESULT = mapOf(AcceptResult.class);

    public static final Map<String, String> MESSAGE_TYPE = mapOf(MessageType.class);

    private static <E extends Enum<E> & CodeLabel> Map<String, String> mapOf(Class<E> type) {
        Map<String, String> map = new LinkedHashMap<>();
        for (E value : type.getEnumConstants()) {
            if (map.put(value.code(), value.label()) != null) {
                throw new IllegalStateException("重复的字典代码: " + value.code());
            }
        }
        return Map.copyOf(map);
    }

    public static String label(Map<String, String> dict, String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return dict.getOrDefault(code, code);
    }

    public static boolean containsKey(Map<String, String> dict, String code) {
        return code != null && dict.containsKey(code);
    }
}
