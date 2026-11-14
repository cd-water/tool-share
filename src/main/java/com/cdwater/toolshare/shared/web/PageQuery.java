package com.cdwater.toolshare.shared.web;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

/**
 * 分页参数统一收敛：页码最小 1，每页条数 1–100，避免各服务各自硬编码。
 *
 * <p>所有分页接口都应通过本类构造 {@link Page}，而不是直接使用前端
 * 传入的 page/size。这样即使调用方传入负数或超大值，也不会生成非法
 * SQL 或一次性拉取过多数据。</p>
 */
public final class PageQuery {

    /** 单页最大条数，防止一次查询返回过多记录。 */
    public static final long MAX_SIZE = 100;

    /**
     * 工具类禁止实例化。
     */
    private PageQuery() {
    }

    /**
     * 校正页码。
     *
     * @param page 原始页码
     * @return 小于 1 时返回 1，否则原样返回
     */
    public static long page(long page) {
        return Math.max(page, 1);
    }

    /**
     * 校正每页条数。
     *
     * @param size 原始条数
     * @return 限制在 1 到 {@link #MAX_SIZE} 之间
     */
    public static long size(long size) {
        return Math.min(Math.max(size, 1), MAX_SIZE);
    }

    /**
     * 构造已校正的 MyBatis-Plus 分页对象。
     *
     * @param page 原始页码
     * @param size 原始每页条数
     * @param <T>  记录类型
     * @return 分页对象
     */
    public static <T> Page<T> of(long page, long size) {
        // 先校正再构造，保证进入 SQL 的参数始终合法。
        return new Page<>(page(page), size(size));
    }
}
