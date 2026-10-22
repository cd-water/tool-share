package com.cdwater.toolshare.common.result;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cdwater.toolshare.common.dto.PageQuery;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 分页包装与分页参数测试。
 */
class PageResultTest {

    @Test
    void of_mapsPageMetadataAndRecords() {
        Page<String> page = new Page<>(2, 20);
        page.setTotal(45);
        page.setRecords(List.of("a", "b"));

        PageResult<String> result = PageResult.of(page);

        assertEquals(45, result.getTotal());
        assertEquals(2, result.getPage());
        assertEquals(20, result.getSize());
        assertSame(page.getRecords(), result.getRecords());
    }

    @Test
    void pageQuery_defaultsAndToPage() {
        PageQuery query = new PageQuery();

        assertEquals(1, query.getPage());
        assertEquals(10, query.getSize());
        Page<Object> page = query.toPage();
        assertEquals(1, page.getCurrent());
        assertEquals(10, page.getSize());
    }
}
