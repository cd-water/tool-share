package com.cdwater.toolshare.shared.html;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTML 富文本消毒器单元测试。
 * <p>
 * 测试范围：工具使用说明等用户富文本在入库/展示前的 XSS 清洗白名单。
 * 依赖环境：纯工具类，无 Spring 上下文。
 * 关键不变量：脚本、事件属性与 javascript 协议必须被移除；安全排版与相对图片地址保留。
 */
class HtmlSanitizerTest {

    /**
     * 场景：输入同时包含 script 标签、onclick/onerror 事件和 javascript: 链接的脏 HTML。
     * 前置条件：消毒器按白名单过滤标签与属性。
     * 断言目标：危险片段全部消失，正常文本内容仍保留。
     */
    @Test
    void removes_scripts_event_handlers_and_javascript_urls() {
        // 构造同时包含三类典型 XSS 载体的脏输入：
        // 1) 内联事件属性 onclick；2) script 标签；3) javascript: 协议。
        // 输入设计为任何一处过滤遗漏都会在下方断言中暴露。
        String dirty = """
                <p onclick="alert(1)">正常说明</p>
                <script>alert('xss')</script>
                <a href="javascript:alert(1)">坏链接</a>
                <img src="javascript:alert(1)" onerror="alert(2)" alt="图">
                """;

        // 消毒器只做白名单过滤，不抛异常，返回可直接渲染的安全 HTML 片段。
        String clean = HtmlSanitizer.clean(dirty);

        // script 标签及其脚本内容应整体移除。
        // 使用 toLowerCase 防止大小写变形绕过检查。
        // 脚本标签的内容可能包含换行与引号，仅删标签而保留内容同样不可接受。
        // 断言针对最终输出，而不是过滤过程的中间态。
        assertFalse(clean.toLowerCase().contains("<script"));
        // 事件属性即使挂在安全标签上也必须清除。
        // 过滤依据是属性白名单，而非字符串替换。
        // 输出中不应残留任何事件属性名称。
        assertFalse(clean.toLowerCase().contains("onclick"));
        // 链接与图片地址中的 javascript: 协议同样危险，需一并清除。
        // 同时覆盖 <a href> 与 <img src> 两处。
        assertFalse(clean.toLowerCase().contains("javascript:"));
        // 图片加载失败触发的 onerror 也是常见注入点。
        // 与 onclick 同属内联事件处理器，按同一规则剥离。
        assertFalse(clean.toLowerCase().contains("onerror"));
        // 清洗不应误删普通文本内容。
        // 文本节点不在标签白名单的过滤范围内。
        assertTrue(clean.contains("正常说明"));
    }

    /**
     * 场景：输入标题、加粗、列表与指向本站的相对图片地址。
     * 前置条件：这些标签与属性位于白名单内。
     * 断言目标：排版标签、图片 src/alt 等安全属性原样保留，保证使用说明可读。
     */
    @Test
    void keeps_safe_formatting_and_relative_images() {
        // 构造一份典型的使用说明：标题、段落、强调、列表与本站图片。
        // 期望消毒结果与输入的关键片段逐项对应，便于定位被误删的元素。
        String html = """
                <h2>使用步骤</h2>
                <p>用 <strong>力</strong> 拧紧，<em>勿</em>超载。</p>
                <ul><li>第一步</li></ul>
                <img src="/api/files/guide.png" alt="示意图" width="320">
                """;

        // 白名单内的标签与属性应被完整保留，消毒器不应重排或改写结构。
        String clean = HtmlSanitizer.clean(html);

        // 标题用于组织说明结构，必须原样保留。
        // 断言同时把 clean 作为失败信息输出，便于快速定位被误删的结构。
        assertTrue(clean.contains("<h2>使用步骤</h2>"), clean);
        // 加粗与斜体属于安全排版，保留标签与原文本。
        // 强调标签内的文字与标签本身都保持原样。
        assertTrue(clean.contains("<strong>力</strong>"), clean);
        // 列表项保留，保证操作步骤可读。
        // 嵌套在 ul 中的 li 也应完整保留，白名单需覆盖常见排版元素。
        assertTrue(clean.contains("<li>第一步</li>"), clean);
        // 相对地址 /api/files 指向本站文件服务，不属于外链风险。
        // 只允许安全协议与相对路径，外链协议仍需按白名单判断。
        assertTrue(clean.contains("src=\"/api/files/guide.png\""), clean);
        // alt 文本对无障碍访问重要，也应保留。
        // width 等展示属性同样在白名单内。
        assertTrue(clean.contains("alt=\"示意图\""), clean);
    }

    /**
     * 场景：输入是结构化步骤 JSON 而非 HTML 字符串。
     * 前置条件：内容以 [ 开头，不属于 HTML 片段。
     * 断言目标：消毒器不误改 JSON，输出与输入完全一致。
     */
    @Test
    void keeps_structured_step_json_untouched() {
        // 兼容旧数据：步骤也可能以结构化 JSON 字符串存储。
        // 内容以 [ 开头，不属于 HTML 片段。
        String json = "[{\"text\":\"检查电源\",\"imageUrl\":\"/api/files/a.png\"}]";
        // 非 HTML 内容应原样返回，不能因消毒而破坏 JSON 结构。
        // 否则前端解析步骤时会直接失败。
        // 消毒器通过首字符判断内容类型，JSON 不进入 HTML 解析分支。
        assertTrue(HtmlSanitizer.clean(json).equals(json));
    }
}
