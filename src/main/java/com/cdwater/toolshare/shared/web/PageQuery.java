package com.cdwater.toolshare.shared.web;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

/**
 * 分页参数统一收敛：页码最小 1，每页条数 1–100，避免各服务各自硬编码。
 */
public final class PageQuery {

    public static final long MAX_SIZE = 100;

    private PageQuery() {
    }

    public static long page(long page) {
        return Math.max(page, 1);
    }

    public static long size(long size) {
        return Math.min(Math.max(size, 1), MAX_SIZE);
    }

    public static <T> Page<T> of(long page, long size) {
        return new Page<>(page(page), size(size));
    }
}
