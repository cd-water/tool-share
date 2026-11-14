/**
 * 富文本白名单净化。
 *
 * 威胁模型：后端可能透传用户填写的富文本（工具描述、评价等），
 * 直接 `dangerouslySetInnerHTML` 会引入 XSS。这里用 DOMParser 在惰性文档中
 * 解析（不会执行脚本、不会请求图片），再按白名单裁剪 DOM，最后序列化。
 *
 * 白名单原则：
 * - 只保留基础排版、列表、标题、链接、图片；
 * - 链接仅允许 http(s)、mailto 和站内绝对路径，阻断 javascript:/data:；
 * - 图片仅允许 http(s) 和站内路径；
 * - 所有事件属性（onclick 等）、style、class 等一律删除；
 * - script/style/iframe 等危险容器连内容一起删除，而不是展开子节点。
 *
 * 与后端 `HtmlSanitizer` 共享同一套白名单，前端净化是纵深防御的第二层。
 */

/**
 * 允许保留的标签（大写，与 DOM 的 tagName 对齐）。
 *
 * 未列入白名单的普通标签会被「解包」——删除标签本身但保留其文本子节点，
 * 这样 `<div>hello</div>` 仍能显示 hello，而不是整段内容消失。
 */
const ALLOWED_TAGS = new Set([
  'P',
  'BR',
  'STRONG',
  'EM',
  'UL',
  'OL',
  'LI',
  'H1',
  'H2',
  'H3',
  'H4',
  'A',
  'IMG',
])

/**
 * 每个允许标签可保留的属性。
 *
 * 仅链接保留 href、图片保留 src；没有列出的标签会移除全部属性。
 * 属性名比较前会转小写，避免 `HREF` 之类绕过。
 */
const ALLOWED_ATTRIBUTES: Record<string, string[]> = {
  A: ['href'],
  IMG: ['src'],
}

/**
 * 危险容器标签。
 *
 * 与普通未知标签不同，这些标签连同子树一起删除：
 * - SCRIPT/STYLE 内含可执行代码或样式；
 * - IFRAME/OBJECT/EMBED 可加载外部内容；
 * - LINK/META 可在文档层面引入资源或改变行为。
 */
const DANGEROUS_TAGS = new Set(['SCRIPT', 'STYLE', 'IFRAME', 'OBJECT', 'EMBED', 'LINK', 'META'])

/**
 * 校验并规范化 URL 协议。
 *
 * @param value 原始属性值
 * @param allowMailto 链接允许 mailto:，图片不允许
 * @returns 安全的 URL；不安全时返回 null
 *
 * 安全点：
 * - 只接受显式 http(s)，`javascript:`、`data:`、`vbscript:` 等都会被拒绝；
 * - 站内路径必须以单个 `/` 开头，`//evil.com` 这种协议相对地址会被拒绝，
 *   防止被解析成跨域请求；
 * - 先 trim，避免前置空白绕过正则。
 */
function safeUrl(value: string, allowMailto: boolean): string | null {
  const url = value.trim()
  if (/^https?:\/\//i.test(url)) return url
  if (allowMailto && /^mailto:/i.test(url)) return url
  // 站内相对路径（但不允许协议相对 //evil）
  if (url.startsWith('/') && !url.startsWith('//')) return url
  return null
}

/**
 * 前端富文本白名单净化：与后端 HtmlSanitizer 保持同一白名单。
 * 仅保留基础排版、列表、链接与图片；脚本、事件属性与 javascript: URL 一律移除。
 *
 * @param html 后端返回的富文本；空值直接返回空串
 * @returns 可安全用于 innerHTML 的 HTML 字符串
 *
 * 处理顺序（顺序很重要）：
 * 1. 解析为惰性文档；
 * 2. 广度遍历所有元素：危险标签直接 remove，未知标签解包；
 * 3. 对白名单标签裁剪属性；
 * 4. 单独校验 A.href 与 IMG.src 的协议；
 * 5. 序列化 body.innerHTML。
 *
 * 由于先删除危险标签再处理子节点，SCRIPT 内的文本不会残留在结果里。
 */
export function sanitizeHtml(html?: string | null): string {
  if (!html) return ''
  // DOMParser 生成的文档是惰性的：不会执行脚本，也不会加载图片资源。
  const document = new DOMParser().parseFromString(html, 'text/html')
  // 复制为数组再遍历：净化过程中会删除/解包节点，避免 live NodeList 失效。
  for (const element of Array.from(document.body.querySelectorAll('*'))) {
    // 父节点已被删除时跳过，防止对游离节点继续操作。
    if (!element.isConnected) continue
    if (DANGEROUS_TAGS.has(element.tagName)) {
      // 危险标签连内容一起丢弃。
      element.remove()
      continue
    }
    if (!ALLOWED_TAGS.has(element.tagName)) {
      // 未知标签只去掉外壳，保留文本/子元素，尽量不破坏排版语义。
      element.replaceWith(...Array.from(element.childNodes))
      continue
    }
    // 白名单标签：移除未授权属性（事件、style、class 等）。
    const allowed = ALLOWED_ATTRIBUTES[element.tagName] ?? []
    for (const attribute of Array.from(element.attributes)) {
      if (!allowed.includes(attribute.name.toLowerCase())) {
        element.removeAttribute(attribute.name)
      }
    }
    // 链接地址协议校验：不安全的 href 直接删除，而不是替换成空串。
    const href = element.tagName === 'A' ? element.getAttribute('href') : null
    if (href !== null) {
      const safe = safeUrl(href, true)
      if (safe === null) element.removeAttribute('href')
      else element.setAttribute('href', safe)
    }
    // 图片地址协议校验：图片额外禁止 mailto。
    const src = element.tagName === 'IMG' ? element.getAttribute('src') : null
    if (src !== null) {
      const safe = safeUrl(src, false)
      if (safe === null) element.removeAttribute('src')
      else element.setAttribute('src', safe)
    }
  }
  return document.body.innerHTML
}
