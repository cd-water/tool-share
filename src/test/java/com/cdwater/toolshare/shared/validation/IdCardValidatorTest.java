package com.cdwater.toolshare.shared.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 身份证号校验器单元测试。
 * <p>
 * 测试范围：18 位身份证的长度、字符集与校验位规则。
 * 依赖环境：纯校验器实例，无数据库与网络依赖。
 * 关键不变量：校验位按国标算法判断；空值视为合法以支持可选字段，非法格式一律拒绝。
 */
class IdCardValidatorTest {

    // 校验器无状态，跨用例共享一个实例即可，避免重复构造。
    private final IdCardValidator validator = new IdCardValidator();

    /**
     * 场景：输入校验位正确的号码，覆盖大写 X、小写 x 与首尾空白。
     * 前置条件：号码本身满足 18 位与校验位算法。
     * 断言目标：大小写 x 与首尾空白均被接受，避免用户输入习惯造成误拒。
     */
    @Test
    void accepts_valid_checksum_and_lowercase_x() {
        // 基准样例：校验位按国标加权算法计算，末位为 4。
        // 四个样例共同说明：合法输入不应因表现形式的细微差异被误拒。
        assertTrue(validator.isValid("310101199001011234", null));
        // 校验位为 X 是常见情况，必须接受。
        assertTrue(validator.isValid("11010519491231002X", null), "大写 X 校验位应合法");
        // 用户常输入小写 x，应等价于大写 X。
        assertTrue(validator.isValid("11010519491231002x", null), "小写 x 校验位应合法");
        // 表单前后空白不改变号码本身，先 trim 再校验。
        // 注意：仅去除首尾空白，中间夹空格仍应判为非法。
        assertTrue(validator.isValid(" 310101199001011234 ", null), "首尾空白应忽略");
    }

    /**
     * 场景：输入 null、空串与纯空白。
     * 前置条件：身份证号在业务上可能是可选字段。
     * 断言目标：空值返回合法，交由必填校验单独控制，避免重复校验冲突。
     */
    @Test
    void blank_is_valid_so_optional_field_can_be_omitted() {
        // null 表示用户未填写，是否必填由上层注解决定。
        assertTrue(validator.isValid(null, null));
        // 空串同样视为「未填写」。
        assertTrue(validator.isValid("", null));
        // 纯空白等同于未填写，避免前端空值绕过校验。
        // 三类空值走同一条短路逻辑，不进入加权计算。
        assertTrue(validator.isValid("   ", null));
    }

    /**
     * 场景：输入校验位错误、17/19 位以及含非法字符的号码。
     * 前置条件：这些输入均不符合国标格式。
     * 断言目标：全部返回非法，防止脏数据进入实名与租赁流程。
     */
    @Test
    void rejects_wrong_checksum_length_and_format() {
        // 末位校验位不匹配，说明号码被篡改或输入错误。
        // 以下样例分别覆盖校验位、长度与字符集三类失败原因。
        assertFalse(validator.isValid("310101199001011235", null), "校验位错误");
        // 长度不足 18 位，无法进行完整校验。
        assertFalse(validator.isValid("31010119900101123", null), "17 位");
        // 长度超过 18 位，同样拒绝。
        assertFalse(validator.isValid("3101011990010112345", null), "19 位");
        // 第 17 位出现字母 A，不符合字符集规则。
        assertFalse(validator.isValid("31010119900101123A", null), "非法字符");
        // 即使末位是合法的 X，整体校验位仍需通过加权计算。
        assertFalse(validator.isValid("31010119900101123X", null), "大写 X 校验位错误");
    }
}
