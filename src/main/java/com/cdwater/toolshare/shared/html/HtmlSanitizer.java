package com.cdwater.toolshare.shared.html;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import org.springframework.util.StringUtils;

/**
 * 富文本使用说明白名单净化。
 * <p>只保留基础排版、列表、链接与图片；script、事件属性、javascript: URL 一律移除。
 */
public final class HtmlSanitizer {

    /** jsoup 只认可带协议的绝对 URL；先给站内相对路径加临时前缀，净化后再还原。 */
    private static final String RELATIVE_PREFIX = "https://toolshare.invalid";

    private static final Safelist SAFELIST = new Safelist()
            .addTags("p", "br", "strong", "em", "ul", "ol", "li", "h1", "h2", "h3", "h4", "a", "img")
            .addAttributes("a", "href", "title", "target", "rel")
            .addAttributes("img", "src", "alt", "width", "height")
            .addProtocols("a", "href", "http", "https", "mailto")
            .addProtocols("img", "src", "http", "https");

    private HtmlSanitizer() {
    }

    public static String clean(String html) {
        if (!StringUtils.hasText(html)) {
            return html;
        }
        String value = html.trim();
        // 管理端步骤编辑器保存的是 JSON 数组，前端按纯文本渲染，不当作 HTML 处理
        if (value.startsWith("[")) {
            return value;
        }
        Document document = Jsoup.parseBodyFragment(value);
        document.select("[src]").forEach(element -> absolutize(element, "src"));
        document.select("[href]").forEach(element -> absolutize(element, "href"));
        return Jsoup.clean(document.body().html(), SAFELIST).replace(RELATIVE_PREFIX, "");
    }

    private static void absolutize(Element element, String attribute) {
        String value = element.attr(attribute);
        if (value.startsWith("/") && !value.startsWith("//")) {
            element.attr(attribute, RELATIVE_PREFIX + value);
        }
    }
}
