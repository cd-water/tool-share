# AI 问答流式输出设计（RAG 联通 + 全系统回归）

日期：2026-10-22　范围：`ai` 包 + 前端 `ai` 页 + API.md 增补

## 背景

后端 B 已实现完整 RAG 链路（Tika 解析 → TokenTextSplitter 切片 → bge-m3 向量化入 Qdrant → QuestionAnswerAdvisor → DeepSeek），`/api/ai/chat` 非流式已验证可用（DeepSeek key 已配置，空库时正确回答「无相关内容」）。剩余工作：前端问答改流式、知识库实际传文档联通、PRD 六模块全量回归。

## 1. 后端：新增 `POST /api/ai/chat/stream`（SSE）

- 返回 `Flux<ServerSentEvent<String>>`（Spring MVC servlet 异步原生支持），入参同 `ChatDTO`
- 事件协议：
  - `event: sources`，data 为 `List<ChatSourceVO>` 的 JSON —— 连接后先发一次
  - `event: delta`，data 为增量文本 —— 多帧
  - `event: done`，data `[DONE]` —— 终止帧
  - `event: error`，data 为错误消息 —— 流中途异常
- **实现不走 QuestionAnswerAdvisor**：流式下 advisor 的检索结果出现时机不可靠。改为先 `vectorStore.similaritySearch(SearchRequest.topK(4))` 取片段，拼入 system 提示词（要求仅依据资料回答），再 `chatClient.prompt().system(...).user(question).stream()`。sources 与上下文同源、仅检索一次
- 原 `/api/ai/chat` 保持不动；API.md 增补 stream 端点一节（契约只增不改）
- 配置 `spring.mvc.async.request-timeout: 120s`
- 权限：登录即可（与非流式一致）

## 2. 前端：流式渲染

- `api/ai.ts` 新增 `chatStream(question, handlers)`：`fetch` POST + `ReadableStream` + `TextDecoder`，按空行分帧解析 `event:`/`data:` 行（EventSource 不支持 POST）
- `ai/index.vue`：发送后先插入空 assistant 消息，`onDelta` 追加并自动滚动，`onSources` 挂载来源片段，`onError` 展示错误；完成后按原有 `STORAGE_KEY` 逻辑持久化会话

## 3. 知识库联通 + PRD 全量回归

- admin 上传一份工具使用手册（Markdown，Tika 可解析）→ 校验解析状态与切片数 → 居民提问命中：sources 非空、答案引用手册内容
- PRD 六模块回归（三角色 admin / lisi / zhangsan 浏览器实操）：
  1. 用户：注册、登录、工作人员改角色、停用/启用
  2. 工具：录入（编号自动生成）、管理员审核、状态更新、状态日志
  3. 租赁：时段查询、预约、押金支付、取消、取件、归还、记录导出、逾期筛选
  4. 报修：提交、核验、进度更新、验收上架
  5. 消息：MQ 延迟提醒落库、已读/全部已读、提醒渠道配置
  6. AI：文档上传、流式问答命中知识库
- 每轮测试重启 backend + frontend；中间件保持运行（业务数据本身是被测状态）
- `mvn test` 全绿；完成后提交，推送前单独征求确认

## 明确不做

- 不改 `/api/ai/chat` 现有契约与实现
- 不做多轮会话上下文（PRD 仅要求单问单答 + 来源展示）
- 不做打字机动画之外的前端美化
