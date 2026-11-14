# 初始化数据资源

本目录集中管理演示/初始化数据所需的资源，避免文档、图片和 SQL 分散在仓库各处。

## 目录结构

```text
seed/
├── demo-data.sql          # 初始关系型数据（用户、管理员、工具）
├── knowledge/             # 知识库语料，28 篇 Markdown
├── images/
│   ├── tools/             # 工具图片，启动时上传到 MinIO
│   ├── avatars/           # 用户头像，启动时上传到 MinIO
│   └── ui/                # 登录页等 UI 图片，启动时上传到 MinIO
└── README.md
```

测试专用数据仍在 `src/test/resources/db/test-seed.sql`，由 Testcontainers 测试基座使用，不进入 Flyway。

## 知识库文档

`knowledge/*.md` 每篇第一行是 `# 标题`，后续为正文。启动种子器按文件名排序扫描该目录，把首行标题写入 `t_knowledge_doc.title`，文件名写入 `file_name`，全文写入 `content`，再由 `KnowledgeService` 建立向量索引。知识库表非空时跳过，避免重复导入。

## 工具图片

图片格式为 JPEG，建议 800×600。启动时由 `SeedAssetInitializer` 上传到 MinIO，key 使用 `seed/tools/<文件名>`，再把数据库中的占位图地址替换为 `/api/files/seed/tools/<文件名>`。

| 工具编码 | 工具 | 当前图片 | 说明 |
| --- | --- | --- | --- |
| SQ-G-WX-0001 | 手电钻 | `images/tools/drill.jpg` | 充电钻图片，暂作手电钻占位 |
| SQ-G-WX-0002 | 家用工具箱 | `images/tools/wrench.jpg` | 套筒工具组，暂作工具箱占位 |
| SQ-G-WX-0003 | 电锤 | `images/tools/rotary-hammer.jpg` | 电锤图片，与手电钻区分 |
| SQ-G-YY-0001 | 园艺修枝剪 | `images/tools/pruner.jpg` | 匹配 |
| SQ-G-YY-0002 | 手推式割草机 | `images/tools/lawn-mower.jpg` | 割草机图片，车型与手推式略有差异 |
| SQ-G-QJ-0001 | 高压清洗机 | `images/tools/washer.jpg` | 匹配 |
| SQ-G-QJ-0002 | 吸尘器 | `images/tools/vacuum.jpg` | 桶式吸尘器，暂作戴森 V8 占位 |
| SQ-G-QT-0001 | 折叠梯 | `images/tools/ladder.jpg` | 直梯图片，暂作折叠梯占位 |

`grinder.jpg`（角磨机）和 `dolly.jpg`（手推车）目前未被 seed 工具使用，保留给后续扩充工具台账。

登录页背景 `images/ui/login-stage.jpg` 也由启动流程上传到 MinIO，key 为 `seed/login-stage.jpg`。

用户头像 `images/avatars/wang-jianguo.jpg` 对应演示居民 `13800000001`，启动时上传到 `seed/avatars/wang-jianguo.jpg` 并回填 `t_user.avatar`；没有头像文件的用户保持空值，由前端显示默认头像。

## SQL 脚本

`demo-data.sql` 只包含关系型初始化数据，不创建表。后端启动时，`DemoDataInitializer` 会在用户、管理员、工具三张表都为空时自动执行它，然后导入知识库语料；`SeedAssetInitializer` 随后上传图片并回填工具图片与用户头像。重复启动不会重复写入。

只有在关闭自动种子（`toolshare.demo.seed-enabled=false`）时，才需要手工执行：

```bash
mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" \
  < src/main/resources/seed/demo-data.sql
```

有密码账号的密码明文均为 `Aa123456`，SQL 中存储的是 BCrypt 哈希。
