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

class CodeLabelTest {

    private static final List<Class<? extends Enum<?>>> TYPES = List.of(
            ToolCategory.class, DamageLevel.class, ToolStatus.class, RentalStatus.class,
            DepositStatus.class, DamageResult.class, ReportStatus.class, VerifyResult.class,
            RepairProgress.class, AcceptResult.class, MessageType.class);

    @Test
    void every_enum_has_code_label_and_no_duplicate_codes() {
        for (Class<? extends Enum<?>> type : TYPES) {
            Set<String> codes = new HashSet<>();
            for (Enum<?> value : type.getEnumConstants()) {
                CodeLabel item = (CodeLabel) value;
                assertFalse(item.code().isBlank(), type.getSimpleName() + " code 不能为空");
                assertFalse(item.label().isBlank(), type.getSimpleName() + " label 不能为空");
                assertTrue(codes.add(item.code()), type.getSimpleName() + " 存在重复 code: " + item.code());
            }
        }
    }

    @Test
    void from_code_round_trips_every_value_and_rejects_unknown() {
        for (Class<? extends Enum<?>> type : TYPES) {
            for (Enum<?> value : type.getEnumConstants()) {
                CodeLabel item = (CodeLabel) value;
                Optional<? extends Enum<?>> found = fromCode(type, item.code());
                assertTrue(found.isPresent(), type.getSimpleName() + " 未能解析 " + item.code());
                assertSame(value, found.get());
            }
            assertTrue(fromCode(type, "NOT_A_CODE").isEmpty(), type.getSimpleName() + " 应拒绝未知 code");
            assertTrue(fromCode(type, null).isEmpty(), type.getSimpleName() + " 应拒绝 null");
        }
    }

    @Test
    void dict_maps_are_generated_from_enums() {
        assertDict(ToolCategory.class, Dict.TOOL_CATEGORY, ToolCategory::fromCode);
        assertDict(DamageLevel.class, Dict.DAMAGE_LEVEL, DamageLevel::fromCode);
        assertDict(ToolStatus.class, Dict.TOOL_STATUS, ToolStatus::fromCode);
        assertDict(RentalStatus.class, Dict.RENTAL_STATUS, RentalStatus::fromCode);
        assertDict(DepositStatus.class, Dict.DEPOSIT_STATUS, DepositStatus::fromCode);
        assertDict(DamageResult.class, Dict.DAMAGE_RESULT, DamageResult::fromCode);
        assertDict(ReportStatus.class, Dict.REPORT_STATUS, ReportStatus::fromCode);
        assertDict(VerifyResult.class, Dict.VERIFY_RESULT, VerifyResult::fromCode);
        assertDict(RepairProgress.class, Dict.PROGRESS_STATUS, RepairProgress::fromCode);
        assertDict(AcceptResult.class, Dict.ACCEPT_RESULT, AcceptResult::fromCode);
        assertDict(MessageType.class, Dict.MESSAGE_TYPE, MessageType::fromCode);
    }

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
        for (E value : type.getEnumConstants()) {
            assertEquals(value.label(), dict.get(value.code()), type.getSimpleName() + " 字典不一致");
            assertEquals(value, fromCode.apply(value.code()).orElseThrow());
        }
        assertEquals(type.getEnumConstants().length, dict.size());
    }

    private Optional<? extends Enum<?>> fromCode(Class<? extends Enum<?>> type, String code) {
        try {
            return (Optional<? extends Enum<?>>) type.getMethod("fromCode", String.class).invoke(null, code);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
