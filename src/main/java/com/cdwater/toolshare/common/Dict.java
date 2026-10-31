package com.cdwater.toolshare.common;

import java.util.Map;

/**
 * 业务字典与中文文案。数据库存英文代码，接口同时返回代码与名称。
 */
public final class Dict {

    private Dict() {
    }

    public static final Map<String, String> TOOL_CATEGORY = Map.of(
            "WX", "维修工具",
            "YY", "园艺工具",
            "QJ", "清洁工具",
            "QT", "其他工具"
    );

    public static final Map<String, String> DAMAGE_LEVEL = Map.of(
            "NEW", "全新",
            "LIGHT", "轻微磨损",
            "MEDIUM", "中度磨损",
            "SEVERE", "严重损坏"
    );

    public static final Map<String, String> TOOL_STATUS = Map.of(
            "AVAILABLE", "可用",
            "RENTED", "已租出",
            "MAINTENANCE", "维修中",
            "SCRAPPED", "已报废"
    );

    public static final Map<String, String> RENTAL_STATUS = Map.of(
            "PENDING_PAYMENT", "待支付",
            "RESERVED", "已预约",
            "PICKED_UP", "已取件",
            "RETURNED", "已归还",
            "CANCELLED", "已取消",
            "OVERDUE", "已逾期"
    );

    public static final Map<String, String> DEPOSIT_STATUS = Map.of(
            "UNPAID", "未支付",
            "PAID", "已支付",
            "REFUNDED", "已退款"
    );

    public static final Map<String, String> DAMAGE_RESULT = Map.of(
            "INTACT", "完好",
            "MINOR", "轻微损坏",
            "SEVERE", "严重损坏"
    );

    public static final Map<String, String> REPORT_STATUS = Map.of(
            "SUBMITTED", "待核验",
            "VERIFIED", "已核验",
            "REJECTED", "已驳回",
            "REPAIRING", "维修中",
            "COMPLETED", "已完成",
            "SCRAPPED", "已报废"
    );

    public static final Map<String, String> PROGRESS_STATUS = Map.of(
            "WAITING_PARTS", "等待配件",
            "REPAIRING", "维修中",
            "COMPLETED", "维修完成",
            "FAILED", "维修失败"
    );

    public static final Map<String, String> ACCEPT_RESULT = Map.of(
            "QUALIFIED", "合格",
            "UNQUALIFIED", "不合格",
            "PARTIAL", "部分合格"
    );

    public static final Map<String, String> MESSAGE_TYPE = Map.of(
            "RESERVATION", "预约通知",
            "PICKUP", "取件提醒",
            "RENT_DUE", "到期提醒",
            "OVERDUE", "逾期提醒",
            "REPAIR", "报修通知",
            "SYSTEM", "系统消息"
    );

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
