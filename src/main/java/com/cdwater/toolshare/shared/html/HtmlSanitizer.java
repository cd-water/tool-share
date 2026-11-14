package com.cdwater.toolshare.shared.html;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import org.springframework.util.StringUtils;

/**
 * 富文本使用说明白名单净化。
 * <p>只保留基础排版、列表、链接与图片；script、事件属性、javascript: URL 一律移除。
 *
 * <p>采用白名单而不是黑名单：所有未显式允许的标签、属性与协议都会被
 * 丢弃。这样可以抵御新出现的标签混淆技巧，而不需要持续补充黑名单。</p>
 *
 * <p>jsoup 的 URL 协议校验只认可绝对地址，站内相对路径会被误删；因此
 * 净化前先给相对路径加临时前缀，净化后再去掉。前缀使用保留域名
 * {@code toolshare.invalid}，不会与真实站点冲突。</p>
 *
 * <p>管理端步骤编辑器保存的是 JSON 数组，前端按纯文本渲染，这类内容
 * 以 {@code [} 开头，直接原样返回，不按 HTML 处理。</p>
 */
public final class HtmlSanitizer {

    /** jsoup 只认可带协议的绝对 URL；先给站内相对路径加临时前缀，净化后再还原。 */
    private static final String RELATIVE_PREFIX = "https://toolshare.invalid";

    /** 允许的标签、属性与 URL 协议白名单。 */
    private static final Safelist SAFELIST = new Safelist()
            // 只保留基础排版、列表、标题、链接与图片。
            .addTags("p", "br", "strong", "em", "ul", "ol", "li", "h1", "h2", "h3", "h4", "a", "img")
            // 链接只允许 href/title/target/rel，不保留事件属性。
            .addAttributes("a", "href", "title", "target", "rel")
            // 图片只允许 src/alt/width/height，不保留 onerror 等属性。
            .addAttributes("img", "src", "alt", "width", "height")
            // 链接协议白名单：禁止 javascript: 与 data:。
            .addProtocols("a", "href", "http", "https", "mailto")
            // 图片协议白名单：只允许 http/https。
            .addProtocols("img", "src", "http", "https");

    /**
     * 工具类禁止实例化。
     */
    private HtmlSanitizer() {
    }

    /**
     * 净化一段富文本。
     *
     * @param html 原始 HTML，允许为空
     * @return 只包含白名单标签与属性的安全 HTML；输入为空时原样返回
     */
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
        // 先给站内相对路径补协议，否则 jsoup 会把它们当成非法 URL 删除。
        document.select("[src]").forEach(element -> absolutize(element, "src"));
        document.select("[href]").forEach(element -> absolutize(element, "href"));
        // 净化后再去掉临时前缀，恢复原始相对路径。
        return Jsoup.clean(document.body().html(), SAFELIST).replace(RELATIVE_PREFIX, "");
    }

    /**
     * 给以单个斜杠开头的站内路径补临时协议前缀。
     *
     * <p>{@code //example.com} 属于协议相对外链，不应加前缀，因此排除
     * 双斜杠开头的情况。</p>
     *
     * @param element   目标元素
     * @param attribute 属性名（src 或 href）
     */
    private static void absolutize(Element element, String attribute) {
        String value = element.attr(attribute);
        if (value.startsWith("/") && !value.startsWith("//")) {
            element.attr(attribute, RELATIVE_PREFIX + value);
        }
    }
}
