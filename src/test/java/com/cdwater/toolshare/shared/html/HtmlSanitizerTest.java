package com.cdwater.toolshare.shared.html;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlSanitizerTest {

    @Test
    void removes_scripts_event_handlers_and_javascript_urls() {
        String dirty = """
                <p onclick="alert(1)">正常说明</p>
                <script>alert('xss')</script>
                <a href="javascript:alert(1)">坏链接</a>
                <img src="javascript:alert(1)" onerror="alert(2)" alt="图">
                """;

        String clean = HtmlSanitizer.clean(dirty);

        assertFalse(clean.toLowerCase().contains("<script"));
        assertFalse(clean.toLowerCase().contains("onclick"));
        assertFalse(clean.toLowerCase().contains("javascript:"));
        assertFalse(clean.toLowerCase().contains("onerror"));
        assertTrue(clean.contains("正常说明"));
    }

    @Test
    void keeps_safe_formatting_and_relative_images() {
        String html = """
                <h2>使用步骤</h2>
                <p>用 <strong>力</strong> 拧紧，<em>勿</em>超载。</p>
                <ul><li>第一步</li></ul>
                <img src="/api/files/guide.png" alt="示意图" width="320">
                """;

        String clean = HtmlSanitizer.clean(html);

        assertTrue(clean.contains("<h2>使用步骤</h2>"), clean);
        assertTrue(clean.contains("<strong>力</strong>"), clean);
        assertTrue(clean.contains("<li>第一步</li>"), clean);
        assertTrue(clean.contains("src=\"/api/files/guide.png\""), clean);
        assertTrue(clean.contains("alt=\"示意图\""), clean);
    }

    @Test
    void keeps_structured_step_json_untouched() {
        String json = "[{\"text\":\"检查电源\",\"imageUrl\":\"/api/files/a.png\"}]";
        assertTrue(HtmlSanitizer.clean(json).equals(json));
    }
}
