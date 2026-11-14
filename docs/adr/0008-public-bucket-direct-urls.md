# 文件改为公开桶直链，保留后端代理兜底

ADR-0003 选择公开桶，但所有图片仍通过 `/api/files/**` 由后端读取 MinIO 再转发。种子图片、工具图片和用户头像增多后，这种纯代理方式与真实对象存储/CDN 的部署方式不一致，也给后端增加无意义的图片流量。决定改为：

- 桶保持公开读；应用启动时通过 S3 `PutBucketPolicy` 设置整桶匿名 `GetObject`。设置失败时记录错误，`StorageService.url()` 回退为 `/api/files/<key>`，应用继续启动。
- 新增 `minio.public-endpoint`（`MINIO_PUBLIC_ENDPOINT`）表示浏览器可访问的地址；`minio.endpoint` 继续供后端 S3 客户端使用，两者可以不同。
- `StorageService.url()` 返回 `${publicEndpoint}/${bucket}/${key}`。上传接口返回该直链，种子初始化也写入直链，数据库继续存完整 URL。
- 启动时幂等规范化旧数据：把 `t_tool.image_url`、`t_tool.image_urls`、`t_user.avatar`、`t_damage_report.images`、`t_tool_review.images` 中的 `/api/files/` 前缀替换为直链前缀。
- 保留 `/api/files/**` 作为旧数据和公开读策略失败时的兜底；登录页背景继续走代理。

## Considered Options

- **私有桶 + 短时预签名 URL**：更安全，但 URL 会过期，VO 每次响应都要生成签名，改动覆盖上传、种子、前端与测试。
- **后端 302 到 MinIO**：保留稳定 URL，但多一次跳转，且最终仍需要公开桶或预签名。
- **继续纯代理**：实现最简单，但图片流量全部压在后端，不像真实对象存储用法。

## Consequences

- 桶内对象对任何知道 URL 的人可读；报修照片与头像同样公开，与 ADR-0003 的可见性假设一致。
- 生产环境必须把 `MINIO_PUBLIC_ENDPOINT` 配成浏览器可达的域名或反向代理地址，并保证对象存储端口可达。
- 直链写入数据库；更换公开端点时需要对已有直链做数据迁移。启动规范化只处理旧 `/api/files/` 前缀，不会重写已直链的域名。
- 上传安全仍依赖图片重编码与扩展名/MIME 白名单；直链不再经过 `PublicFileController` 的 `nosniff` 响应头，但桶内图片已是重编码后的 JPEG/PNG，不能作为 HTML/SVG 执行。
