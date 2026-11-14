package com.cdwater.toolshare.shared.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 分页参数归一化单元测试。
 * <p>
 * 测试范围：页码与每页条数的边界收敛规则。
 * 依赖环境：纯静态工具方法，无外部依赖。
 * 关键不变量：页码最小为 1，每页条数限制在 1 到 100 之间，防止异常分页拖垮查询。
 */
class PageQueryTest {

    /**
     * 场景：传入 0、负数与正常页码。
     * 前置条件：调用 PageQuery.page 归一化。
     * 断言目标：非正数一律收敛为第 1 页，正常值原样保留。
     */
    @Test
    void page_is_at_least_one() {
        // 0 与负数都视为「未指定页码」，统一回到第 1 页，避免 SQL offset 为负。
        // 示例：page(0) 与 page(-5) 都应等价于首页。
        assertEquals(1, PageQuery.page(0));
        // 负数页码按默认首页处理。
        assertEquals(1, PageQuery.page(-5));
        // 合法页码原样保留，归一化不应改变正常请求。
        assertEquals(3, PageQuery.page(3));
    }

    /**
     * 场景：传入 0、负数、正常值与超大每页条数。
     * 前置条件：调用 PageQuery.size 归一化。
     * 断言目标：下界为 1、上界为 100，正常值不变。
     */
    @Test
    void size_is_clamped_to_one_hundred_and_at_least_one() {
        // 条数下界：0 或负数没有业务含义，收敛为 1 条，避免构造空结果集。
        // 负数条数还可能被驱动转成极大值，必须在入口拦截。
        assertEquals(1, PageQuery.size(0));
        // 负数条数同样收敛到 1。
        assertEquals(1, PageQuery.size(-5));
        // 合法条数保持不变，尊重调用方的分页意图。
        assertEquals(50, PageQuery.size(50));
        // 上界：超过 100 的请求可能拖垮数据库，统一截断到 100。
        // 示例：101 与 200 都会被压到上限。
        assertEquals(100, PageQuery.size(101));
        // 极端大值同样被截断，保证单次查询成本可预期。
        assertEquals(100, PageQuery.size(200));
    }

    /**
     * 场景：直接用原始页码与条数构造分页对象。
     * 前置条件：PageQuery.of 内部复用 page/size 的收敛逻辑。
     * 断言目标：构造出的分页对象已带归一化后的页码与条数。
     */
    @Test
    void builds_page_with_clamped_values() {
        // of 组合页码与条数，两项都应先经过各自的归一化。
        // 首页与 1 条共同构成最小的安全分页。
        assertEquals(1, PageQuery.of(0, 0).getCurrent());
        // 条数同样被归一化到 1。
        assertEquals(1, PageQuery.of(0, 0).getSize());
        // 页码合法但条数超限时，只截断条数，不影响页码。
        assertEquals(100, PageQuery.of(2, 500).getSize());
    }
}
