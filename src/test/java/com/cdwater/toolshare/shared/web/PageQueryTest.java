package com.cdwater.toolshare.shared.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PageQueryTest {

    @Test
    void page_is_at_least_one() {
        assertEquals(1, PageQuery.page(0));
        assertEquals(1, PageQuery.page(-5));
        assertEquals(3, PageQuery.page(3));
    }

    @Test
    void size_is_clamped_to_one_hundred_and_at_least_one() {
        assertEquals(1, PageQuery.size(0));
        assertEquals(1, PageQuery.size(-5));
        assertEquals(50, PageQuery.size(50));
        assertEquals(100, PageQuery.size(101));
        assertEquals(100, PageQuery.size(200));
    }

    @Test
    void builds_page_with_clamped_values() {
        assertEquals(1, PageQuery.of(0, 0).getCurrent());
        assertEquals(1, PageQuery.of(0, 0).getSize());
        assertEquals(100, PageQuery.of(2, 500).getSize());
    }
}
