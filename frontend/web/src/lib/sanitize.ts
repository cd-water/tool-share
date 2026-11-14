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

const ALLOWED_ATTRIBUTES: Record<string, string[]> = {
  A: ['href'],
  IMG: ['src'],
}

const DANGEROUS_TAGS = new Set(['SCRIPT', 'STYLE', 'IFRAME', 'OBJECT', 'EMBED', 'LINK', 'META'])

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
 */
export function sanitizeHtml(html?: string | null): string {
  if (!html) return ''
  const document = new DOMParser().parseFromString(html, 'text/html')
  for (const element of Array.from(document.body.querySelectorAll('*'))) {
    if (!element.isConnected) continue
    if (DANGEROUS_TAGS.has(element.tagName)) {
      element.remove()
      continue
    }
    if (!ALLOWED_TAGS.has(element.tagName)) {
      element.replaceWith(...Array.from(element.childNodes))
      continue
    }
    const allowed = ALLOWED_ATTRIBUTES[element.tagName] ?? []
    for (const attribute of Array.from(element.attributes)) {
      if (!allowed.includes(attribute.name.toLowerCase())) {
        element.removeAttribute(attribute.name)
      }
    }
    const href = element.tagName === 'A' ? element.getAttribute('href') : null
    if (href !== null) {
      const safe = safeUrl(href, true)
      if (safe === null) element.removeAttribute('href')
      else element.setAttribute('href', safe)
    }
    const src = element.tagName === 'IMG' ? element.getAttribute('src') : null
    if (src !== null) {
      const safe = safeUrl(src, false)
      if (safe === null) element.removeAttribute('src')
      else element.setAttribute('src', safe)
    }
  }
  return document.body.innerHTML
}
