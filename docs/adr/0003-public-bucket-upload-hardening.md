# 文件保持公开桶，靠白名单与响应头消除 XSS

原上传接口不校验类型、按客户端 Content-Type 回显，`/api/files/**` 可直出 HTML/SVG，构成同源存储型 XSS。决定保持公开桶以维持现有 `<img>` 展示，改为：扩展名与 MIME 白名单、图片重编码、响应统一 `X-Content-Type-Options: nosniff` 与安全的 Content-Type。

## Considered Options

- 私有桶 + 短时签名 URL：更安全，但改动覆盖上传、读取、种子资源与两个前端，当前阶段收益不抵成本。

## Consequences

- 报修照片与头像仍对任何知道 URL 的人可读；如后续有隐私要求，升级为私有桶 + 签名 URL。
