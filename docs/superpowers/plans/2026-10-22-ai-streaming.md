# AI 问答流式输出 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 居民端 AI 问答改为 SSE 流式输出（新增 `/api/ai/chat/stream`），并完成知识库联通与 PRD 六模块全量回归。

**Architecture:** 后端先 `vectorStore.similaritySearch` 取片段拼入 system 提示词，再 `ChatClient.stream()` 输出增量，以 `Flux<ServerSentEvent<String>>` 按帧推送（sources → delta* → done）；前端 `fetch` + `ReadableStream` 手解 SSE 帧逐字渲染。原非流式端点与契约不动。

**Tech Stack:** Spring Boot 4.1 / Spring AI 2.0.1（Reactor `Flux`、Jackson 3 `tools.jackson`）/ Vue 3 + TypeScript

**Spec:** `docs/superpowers/specs/2026-10-22-ai-streaming-design.md`

## Global Constraints

- Spring Boot 4.1 / Java 21 / Spring AI 2.0.1；Jackson 一律用 `tools.jackson`（Jackson 3），不是 `com.fasterxml.jackson`
- 后端命令 `bash mvnw ...`（mvnw 无执行位）；前端在 `frontend/` 下用 `pnpm`
- `/api/ai/chat` 现有契约与实现保持不动，只新增端点；API.md 只增不改
- 后端 :9090，前端 :5050（vite 代理 `/api` → 9090）；测试账号 admin/lisi/zhangsan 密码均 `Aa123456`
- 每轮功能测试前重启 backend + frontend（中间件保持运行）
- 模块分层：controller 加端点、service 接口+实现加方法，遵循 `ai` 包既有风格

## Review Focus

1. DeepSeek 流中途异常/超时 → 客户端收到 `event: error` 帧而不是连接挂死（Task 1 的 onErrorResume + IT 断言）
2. 知识库为空时提问 → `sources` 为 `[]` 且回答正常生成，不报错（Task 1 live 验证）
3. 回答文本含换行/引号 → 后端逐帧 JSON 编码、前端 `JSON.parse` 还原，不破坏 SSE 分帧（Task 1 编码 + Task 2 解析对齐）
4. 前端收到非 200 / 网络中断 → 走 `onError` 渲染错误消息，不留空白气泡（Task 2）
5. 上传损坏/空文档 → `KbDocument.status=3` 且接口报「文档解析失败」，不产生脏向量（Task 3 回归确认既有行为）

---

### Task 1: 后端 SSE 端点 `/api/ai/chat/stream`

**Files:**
- Modify: `src/main/java/com/cdwater/toolshare/ai/service/AiService.java`（接口加方法）
- Modify: `src/main/java/com/cdwater/toolshare/ai/service/impl/AiServiceImpl.java`（实现 chatStream）
- Modify: `src/main/java/com/cdwater/toolshare/ai/controller/AiController.java`（加端点）
- Modify: `src/main/resources/application.yml`（加 async 超时）
- Modify: `docs/API.md`（AI 模块表加一行 + 协议说明）
- Test: `src/test/java/com/cdwater/toolshare/ai/AiStreamIT.java`（环境变量门控的集成测试）

**Interfaces:**
- Consumes: `VectorStore.similaritySearch(SearchRequest)` → `List<Document>`；`ChatClient.Builder.build().prompt().system(String).user(String).stream().content()` → `Flux<String>`；`ObjectMapper`（`tools.jackson.databind`）序列化
- Produces: `AiService.chatStream(ChatDTO): Flux<ServerSentEvent<String>>`；HTTP `POST /api/ai/chat/stream`，帧协议 `sources`(JSON 数组) / `delta`(JSON 字符串) / `done`(`[DONE]`) / `error`(JSON 字符串)，Task 2 前端按此解析

- [ ] **Step 1: 写失败的门控集成测试**

```java
package com.cdwater.toolshare.ai;

import cn.dev33.satoken.stp.StpUtil;
import com.cdwater.toolshare.user.dto.LoginDTO;
import com.cdwater.toolshare.user.mapper.UserMapper;
import com.cdwater.toolshare.user.entity.User;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流式问答集成测试（依赖 Qdrant/Ollama/DeepSeek，设 AI_IT=true 启用）
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "AI_IT", matches = "true")
class AiStreamIT {

    @Autowired
    WebTestClient webTestClient;

    @Autowired
    UserMapper userMapper;

    static String token;

    @BeforeAll
    static void login(@Autowired WebTestClient client) {
        // 登录走非流式端点拿 token（sa-token 拦截器要求 /api/** 携带）
    }

    @Test
    void stream_emitsSourcesDeltaDone() {
        // 见 Step 4 断言逻辑：先 login 得 token，再 POST /api/ai/chat/stream
    }
}
```

注意：这是骨架占位——实际编写时按本文件 Step 4 的完整断言版本一次写全（`login` 用 `userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, "zhangsan"))` 拿 userId 后 `StpUtil.login(...)` 不可用（无 Web 上下文），改为直接调用 `POST /api/user/login`：`webTestClient.post().uri("/api/user/login").bodyValue(Map.of("username","zhangsan","password","Aa123456")).exchange()` 从 JSON 提取 `data.token`）。

- [ ] **Step 2: 运行确认失败**

Run: `AI_IT=true bash mvnw test -Dtest=AiStreamIT`
Expected: FAIL — `chatStream` 方法不存在导致编译失败

- [ ] **Step 3: 实现 service 方法**

`AiService.java` 接口加：

```java
Flux<ServerSentEvent<String>> chatStream(ChatDTO dto);
```

import：`org.springframework.http.codec.ServerSentEvent`、`reactor.core.publisher.Flux`。

`AiServiceImpl.java` 加字段与实现（构造注入已有 `chatClientBuilder`/`vectorStore`，补 `ObjectMapper`）：

```java
private final ObjectMapper objectMapper;   // tools.jackson.databind.ObjectMapper

private static final String SYSTEM_TEMPLATE = """
        你是社区共享工具租赁系统的智能助手，请依据以下知识库资料回答居民问题，
        资料中没有的内容请如实说明没有相关资料。资料：
        %s
        """;

@Override
public Flux<ServerSentEvent<String>> chatStream(ChatDTO dto) {
    return Flux.defer(() -> {
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder().topK(TOP_K).build());
        String context = contextOf(docs);
        Flux<String> answer = chatClientBuilder.build().prompt()
                .system(SYSTEM_TEMPLATE.formatted(context))
                .user(dto.getQuestion())
                .stream()
                .content();
        return Flux.concat(
                Flux.just(frame("sources", jsonOf(toSources(docs)))),
                answer.map(chunk -> frame("delta", jsonOf(chunk))),
                Flux.just(frame("done", "[DONE]")));
    }).onErrorResume(e -> {
        log.error("流式问答失败: {}", dto.getQuestion(), e);
        return Flux.just(frame("error", jsonOf("回答生成失败，请稍后重试")));
    });
}

private ServerSentEvent<String> frame(String event, String data) {
    return ServerSentEvent.builder(data).event(event).build();
}

/** 检索片段拼上下文，与 toSources 同源 */
private String contextOf(List<Document> docs) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < docs.size(); i++) {
        Document doc = docs.get(i);
        Object fileName = doc.getMetadata().get("fileName");
        sb.append("【片段").append(i + 1)
          .append(fileName == null ? "" : "·" + fileName)
          .append("】\n").append(doc.getText()).append("\n\n");
    }
    return sb.toString();
}

/** 与 chat() 的 sourcesOf 同构（复用其逻辑可抽私有方法共享） */
private List<ChatSourceVO> toSources(List<Document> docs) { /* 同 sourcesOf 的 map 逻辑 */ }

private String jsonOf(Object value) {
    try {
        return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
        return "\"\"";
    }
}
```

（`toSources` 直接搬现有 `sourcesOf` 里 Document→ChatSourceVO 的映射体，参数从 response.context 换成 `List<Document>`；原 `sourcesOf` 可改为调用它。若 `sourcesOf` 已含相同映射则抽公共私有方法，避免两份。）

- [ ] **Step 4: 补全 IT 断言（完整版测试体）**

```java
@Test
void stream_emitsSourcesDeltaDone() {
    String body = webTestClient.post().uri("/api/user/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("username", "zhangsan", "password", "Aa123456"))
            .exchange().expectStatus().isOk()
            .expectBody(String.class).returnResult().getResponseBody();
    token = JsonPath.read(body, "$.data.token");   // 无 json-path 依赖则用 indexOf 截取
    byte[] stream = webTestClient.post().uri("/api/ai/chat/stream")
            .header("Authorization", token)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("question", "梯子能承重多少？"))
            .exchange().expectStatus().isOk()
            .expectBody(byte[].class).returnResult().getResponseBody();
    String text = new String(stream, StandardCharsets.UTF_8);
    assertTrue(text.contains("event:sources"), "首帧应为 sources: " + text);
    assertTrue(text.contains("event:delta"), "应有 delta 帧: " + text);
    assertTrue(text.contains("event:done"), "应有 done 帧: " + text);
}
```

（`token` 提取若引入不了 JsonPath，就用 `body.split("\"token\":\"")[1].split("\"")[0]`。）

- [ ] **Step 5: Controller 加端点**

```java
/** RAG 流式问答（SSE：sources → delta* → done） */
@PostMapping(value = "/chat/stream")
public Flux<ServerSentEvent<String>> chatStream(@Valid @RequestBody ChatDTO dto) {
    return aiService.chatStream(dto);
}
```

import：`org.springframework.http.codec.ServerSentEvent`、`reactor.core.publisher.Flux`。

- [ ] **Step 6: application.yml 加异步超时**

在 `spring:` 键下（`mvc:` 不存在，新增）：

```yaml
  mvc:
    async:
      request-timeout: 120s
```

- [ ] **Step 7: API.md 增补（只增不改）**

在 `## 6. AI 问答模块` 表格追加一行：

```
| POST | /api/ai/chat/stream | RAG 流式问答（SSE，text/event-stream） | 居民/工作人员 | body: `{question}` | 帧：`sources`(JSON数组) → `delta`(JSON字符串，多帧) → `done`(`[DONE]`)；异常发 `error` 帧 |
```

- [ ] **Step 8: 编译 + 跑门控 IT + curl 实测**

Run: `bash mvnw -q compile && AI_IT=true bash mvnw test -Dtest=AiStreamIT`
Expected: PASS

重启后端后 curl 实测（中间件保持，后端重启）：

```bash
make backend-down && sleep 2 && make backend
T=$(curl -s -X POST http://localhost:9090/api/user/login -H 'Content-Type: application/json' -d '{"username":"zhangsan","password":"Aa123456"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')
curl -N --max-time 120 -X POST http://localhost:9090/api/ai/chat/stream -H "Authorization: $T" -H 'Content-Type: application/json' -d '{"question":"你好"}'
```

Expected: 依次看到 `event:sources`（data 为 `[]`，空库）、多条 `event:delta`、`event:done`。

- [ ] **Step 9: 提交**

```bash
git add -A && git commit -m "feat: 新增 /api/ai/chat/stream SSE 流式问答端点"
```

---

### Task 2: 前端流式渲染

**Files:**
- Modify: `frontend/src/api/ai.ts`（加 chatStream）
- Modify: `frontend/src/views/ai/index.vue`（ask() 改流式）

**Interfaces:**
- Consumes: Task 1 的 SSE 帧协议（`sources`/`delta`/`done`/`error`，data 均为 JSON）；`ChatSourceVO` 类型已在 `frontend/src/api/types.ts`；token 在 `stores/user` 的 `userStore.token`
- Produces: `chatStream(question: string, handlers: ChatStreamHandlers): Promise<void>`

- [ ] **Step 1: api/ai.ts 加 chatStream**

```typescript
export interface ChatStreamHandlers {
  onSources?: (sources: ChatSourceVO[]) => void
  onDelta?: (text: string) => void
  onDone?: () => void
  onError?: (message: string) => void
}

/** RAG 流式问答：fetch + ReadableStream 手解 SSE（EventSource 不支持 POST） */
export async function chatStream(question: string, handlers: ChatStreamHandlers) {
  const userStore = useUserStore()
  let resp: Response
  try {
    resp = await fetch('/api/ai/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: userStore.token },
      body: JSON.stringify({ question }),
    })
  } catch {
    handlers.onError?.('网络异常，请稍后重试')
    return
  }
  if (!resp.ok || !resp.body) {
    handlers.onError?.(`请求失败（${resp.status}）`)
    return
  }
  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const frames = buffer.split('\n\n')
    buffer = frames.pop() ?? ''
    for (const frame of frames) {
      let event = 'message'
      let data = ''
      for (const line of frame.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim()
        else if (line.startsWith('data:')) data += line.slice(5).trimStart()
      }
      try {
        if (event === 'sources') handlers.onSources?.(JSON.parse(data))
        else if (event === 'delta') handlers.onDelta?.(JSON.parse(data))
        else if (event === 'error') handlers.onError?.(JSON.parse(data))
        else if (event === 'done') handlers.onDone?.()
      } catch {
        // 单帧脏数据只丢帧，不断流
      }
    }
  }
}
```

文件头补 `import { useUserStore } from '../stores/user'` 与 `ChatSourceVO` 类型导入。

- [ ] **Step 2: ai/index.vue 的 ask() 改流式**

替换现有 `ask` 函数（保留函数签名与 sending/saveHistory/scrollToBottom 逻辑）：

```typescript
async function ask(question: string) {
  sending.value = true
  scrollToBottom()
  messages.value.push({ role: 'ai', text: '', sources: [] })
  const target = messages.value[messages.value.length - 1]
  let failed = false
  await chatStream(question, {
    onSources: (sources) => { target.sources = sources ?? [] },
    onDelta: (text) => { target.text += text },
    onError: (message) => {
      failed = true
      target.text = ''
      target.error = true
      target.question = question
      ElMessage.error(message)
    },
  })
  if (!failed && !target.text && !target.error) {
    // 服务端无任何帧即断开，按失败处理，保留重试入口
    target.error = true
    target.question = question
  }
  sending.value = false
  saveHistory()
  scrollToBottom()
}
```

同时把文件顶部 `import { chat } from '../../api/ai'` 改为 `import { chatStream } from '../../api/ai'`（确认 `chat` 无其他引用后移除）。模板已有 `error`/`question`/重试渲染（`retry()` 保留不动）。

- [ ] **Step 3: 构建验证**

Run: `cd frontend && pnpm build`
Expected: 无类型错误，构建成功

- [ ] **Step 4: 重启前端 + 浏览器实测**

```bash
cd .. && make frontend-down && make frontend
```

Playwright：登录 zhangsan → `/ai` → 输入「你好，你是谁？」发送。
Expected: 回答**逐字渐进**出现（而非整段弹出）；完成后刷新页面会话仍在（saveHistory）；断网场景可用 `playwright-cli route "**/api/ai/chat/stream" --status=500` 验证 onError 渲染错误气泡。

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat: 前端 AI 问答改为 SSE 流式渲染"
```

---

### Task 3: 知识库联通 + PRD 六模块全量回归

**Files:**
- Create: `docs/kb/电钻使用手册.md`（测试用知识库文档，内容含：换钻头步骤、安全警示、承重/保养参数——足以让 SUGGESTIONS 里的提问命中）

**Interfaces:**
- Consumes: Task 1/2 的完整链路；管理员账号 admin
- Produces: 回归结论（写入最终汇报，不改代码）

- [ ] **Step 1: 造知识库文档并上传**

写一份 Markdown 手册（≥500 字，含「手电钻换钻头：1.断电 2.反转松夹头…」「电钻承重/保养」「押金与逾期规则」段落），admin 登录后：

```bash
T=$(curl -s -X POST http://localhost:9090/api/user/login -H 'Content-Type: application/json' -d '{"username":"admin","password":"Aa123456"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')
curl -s -X POST http://localhost:9090/api/ai/documents -H "Authorization: $T" -F "file=@docs/kb/电钻使用手册.md"
```

Expected: 返回 `status=2`（完成）且 `chunkCount>0`；`GET /api/ai/documents` 可见记录。
再上传一个空文档 `: > /tmp/empty.txt && curl -s -X POST ... -F "file=@/tmp/empty.txt"`，Expected: 报「文档解析失败」且记录 `status=3`（Review Focus 5）。

- [ ] **Step 2: 浏览器验证 RAG 命中**

Playwright：zhangsan → `/ai` → 问「电钻怎么换钻头？」。
Expected: sources 非空且 documentName 为手册文件名；回答引用手册步骤；流式逐字渲染。

- [ ] **Step 3: PRD 六模块回归（每轮重启 backend+frontend）**

对每个模块：`make backend-down && make backend && make frontend-down && make frontend` 后在浏览器用对应角色操作：

1. **用户**（admin/lisi）：注册新居民 zhangsan6 → lisi 在 `/admin/users` 改角色为工作人员 → 停用/启用
2. **工具**（lisi/admin）：`/admin/tools` 录入工具（编号 SQ-G-XX-YYYY 自动生成）→ admin 在 `/admin/audit` 审核通过 → 改状态 → 查状态日志
3. **租赁**（zhangsan + lisi）：工具详情选时段预约 → 支付押金 → lisi 取件登记 → 归还验收（选验收结果/逾期费）→ zhangsan `/rentals` 看记录 → lisi 导出 Excel → 逾期筛选
4. **报修**（zhangsan + lisi）：已取件订单提交报修（传图可用 file 接口）→ lisi 核验 → 更新进度 → 验收上架 → 工具恢复可约
5. **消息**：预约后检查 zhangsan 消息中心有取件提醒；全部已读；lisi 改提醒渠道配置
6. **AI**：Step 2 已覆盖流式问答 + 来源

每轮记录：通过/失败 + 现象。发现缺陷当场修（小缺陷直接修后重启复测；大缺陷停下汇报）。

- [ ] **Step 4: 全量测试收尾**

Run: `bash mvnw test`
Expected: 全部通过（既有 99 + 新增）

- [ ] **Step 5: 提交测试文档与收尾**

```bash
git add -A && git commit -m "test: AI 流式问答联通与 PRD 全量回归通过"
```

推送单独征求用户确认后再执行 `git push origin main`。
