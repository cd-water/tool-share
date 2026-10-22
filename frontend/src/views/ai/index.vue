<template>
  <div class="ai-page">
    <div class="page-head">
      <div>
        <h1>AI 问答</h1>
        <p class="sub">基于社区工具知识库的智能助手 · 解答工具使用、安全操作与租赁规则问题</p>
      </div>
      <div class="ph-actions">
        <el-button class="clear-btn" @click="clearChat">清空会话</el-button>
      </div>
    </div>

    <div class="chat-wrap">
      <div class="chat-head">
        <span class="chat-title">
          <svg
            class="bot-icon"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="1.6"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <rect x="4" y="8" width="16" height="12" rx="3" />
            <path d="M12 8V4M8 4h8" />
            <circle cx="9" cy="14" r="1" fill="currentColor" stroke="none" />
            <circle cx="15" cy="14" r="1" fill="currentColor" stroke="none" />
          </svg>
          工具服务助手
        </span>
        <span class="chat-sub">知识库：使用手册 · 安全指引 · 租赁规则</span>
      </div>

      <div ref="msgsRef" class="chat-msgs">
        <template v-if="!messages.length">
          <div class="ai-hello">
            <svg
              class="hello-icon"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="1.4"
              stroke-linecap="round"
              stroke-linejoin="round"
              aria-hidden="true"
            >
              <rect x="4" y="8" width="16" height="12" rx="3" />
              <path d="M12 8V4M8 4h8" />
              <circle cx="9" cy="14" r="1" fill="currentColor" stroke="none" />
              <circle cx="15" cy="14" r="1" fill="currentColor" stroke="none" />
            </svg>
            <div class="hello-title">你好，我是工具服务助手</div>
            <div class="hello-sub">关于工具使用、安全操作、押金与租赁规则，都可以问我</div>
          </div>
          <div class="sugg">
            <button
              v-for="question in SUGGESTIONS"
              :key="question"
              type="button"
              @click="send(question)"
            >
              {{ question }}
            </button>
          </div>
        </template>

        <template v-else>
          <div
            v-for="(msg, index) in messages"
            :key="index"
            class="msg"
            :class="{ user: msg.role === 'user' }"
          >
            <div class="avatar">
              <svg
                v-if="msg.role === 'user'"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="1.6"
                stroke-linecap="round"
                aria-hidden="true"
              >
                <circle cx="12" cy="8" r="4" />
                <path d="M4 21a8 8 0 0 1 16 0" />
              </svg>
              <svg
                v-else
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="1.6"
                stroke-linecap="round"
                stroke-linejoin="round"
                aria-hidden="true"
              >
                <rect x="4" y="8" width="16" height="12" rx="3" />
                <path d="M12 8V4M8 4h8" />
                <circle cx="9" cy="14" r="1" fill="currentColor" stroke="none" />
                <circle cx="15" cy="14" r="1" fill="currentColor" stroke="none" />
              </svg>
            </div>
            <div class="msg-main">
              <div class="bubble">
                <template v-if="msg.error">
                  <template v-if="msg.text">{{ msg.text }}</template>
                  回答失败，请稍后重试
                  <button type="button" class="retry-btn" @click="retry(index)">点击重试</button>
                </template>
                <template v-else>{{ msg.text }}</template>
              </div>
              <div
                v-if="msg.sources?.length"
                class="sources"
                :class="{ open: msg.open }"
              >
                <button type="button" class="sources-head" @click="msg.open = !msg.open">
                  <span>知识库来源（{{ msg.sources.length }}）</span>
                  <span>{{ msg.open ? '收起 ‹' : '展开 ›' }}</span>
                </button>
                <div v-if="msg.open" class="sources-body">
                  <div v-for="(src, i) in msg.sources" :key="i" class="source">
                    <b>{{ src.documentName }}</b>
                    <div class="source-content">{{ src.content }}</div>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <div v-if="sending" class="msg">
            <div class="avatar">
              <svg
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="1.6"
                stroke-linecap="round"
                stroke-linejoin="round"
                aria-hidden="true"
              >
                <rect x="4" y="8" width="16" height="12" rx="3" />
                <path d="M12 8V4M8 4h8" />
                <circle cx="9" cy="14" r="1" fill="currentColor" stroke="none" />
                <circle cx="15" cy="14" r="1" fill="currentColor" stroke="none" />
              </svg>
            </div>
            <div class="bubble typing">
              <i /><i /><i />
            </div>
          </div>
        </template>
      </div>

      <div class="chat-input">
        <textarea
          ref="inputRef"
          v-model="input"
          rows="1"
          placeholder="输入问题，Enter 发送，Shift+Enter 换行"
          @input="resizeInput"
          @keydown="onKeydown"
        />
        <el-button class="send-btn" type="primary" :loading="sending" @click="send()">
          发 送
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { chatStream } from '../../api/ai'
import type { ChatSourceVO } from '../../api/types'

interface ChatMessage {
  role: 'user' | 'ai'
  text: string
  sources?: ChatSourceVO[]
  error?: boolean
  /** 失败重试时使用 */
  question?: string
  open?: boolean
}

const STORAGE_KEY = 'toolshare_ai_chat_v1'
const SUGGESTIONS = [
  '电钻怎么换钻头？',
  '押金是多少、怎么退？',
  '逾期费用怎么算？',
  '工具损坏了怎么办？',
  '割草机使用注意什么？',
  '取件需要带什么？',
]

const messages = ref<ChatMessage[]>([])
const input = ref('')
const sending = ref(false)
const msgsRef = ref<HTMLElement>()
const inputRef = ref<HTMLTextAreaElement>()

function loadHistory() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return
    const list = JSON.parse(raw)
    if (!Array.isArray(list)) return
    messages.value = list
      .filter(
        (m): m is ChatMessage =>
          !!m && (m.role === 'user' || m.role === 'ai') && typeof m.text === 'string',
      )
      .slice(-20)
  } catch {
    messages.value = []
  }
}

function saveHistory() {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(messages.value.slice(-20)))
  } catch {
    // 忽略存储失败（隐私模式 / 配额不足）
  }
}

function scrollToBottom() {
  nextTick(() => {
    const el = msgsRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

watch(messages, scrollToBottom, { deep: true, flush: 'post' })

function resizeInput() {
  const el = inputRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, 120)}px`
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    send()
  }
}

async function ask(question: string) {
  sending.value = true
  scrollToBottom()
  messages.value.push({ role: 'ai', text: '', sources: [] })
  const target = messages.value[messages.value.length - 1]
  let doneReceived = false
  await chatStream(question, {
    onSources: (sources) => {
      target.sources = sources ?? []
    },
    onDelta: (text) => {
      target.text += text
    },
    onDone: () => {
      doneReceived = true
    },
    onError: (message) => {
      ElMessage.error(message)
    },
  })
  // done 帧是唯一完成判据：请求失败、断流、超时一律进错误气泡（保留已到文本与重试入口）
  if (!doneReceived) {
    target.error = true
    target.question = question
  }
  sending.value = false
  saveHistory()
  scrollToBottom()
}

function send(preset?: string) {
  if (sending.value) return
  const question = (preset ?? input.value).trim()
  if (!question) {
    ElMessage.warning('请输入问题')
    return
  }
  messages.value.push({ role: 'user', text: question })
  input.value = ''
  nextTick(resizeInput)
  saveHistory()
  ask(question)
}

function retry(index: number) {
  const msg = messages.value[index]
  if (!msg?.error || !msg.question || sending.value) return
  messages.value.splice(index, 1)
  saveHistory()
  ask(msg.question)
}

async function clearChat() {
  try {
    await ElMessageBox.confirm('确定清空当前会话记录吗？', '清空会话', {
      type: 'warning',
      confirmButtonText: '清空',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  messages.value = []
  localStorage.removeItem(STORAGE_KEY)
  ElMessage.success('会话已清空')
}

onMounted(() => {
  loadHistory()
  scrollToBottom()
})
</script>

<style scoped>
.ai-page {
  --primary: #1c3a35;
  --primary-2: #24443e;
  --primary-3: #2e5149;
  --primary-5: #6e8a82;
  --primary-7: #a8bcb5;
  --primary-tint: #eef3f0;
  --surface: #fff;
  --surface-2: #f8faf8;
  --border: #e6eae6;
  --border-2: #d4dbd6;
  --text: #1e2b26;
  --text-2: #5a6a63;
  --text-3: #93a29b;
  --danger: #c0392b;
  --r-2: 6px;
  --r-3: 8px;
  --r-4: 12px;
  --shadow-1: 0 1px 2px rgba(16, 24, 40, 0.05);

  display: flex;
  flex-direction: column;
  height: calc(100vh - 90px);
  min-height: 480px;
  color: var(--text);
}

.page-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}
.page-head h1 {
  margin: 0 0 3px;
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.011em;
  line-height: 1.35;
}
.page-head .sub {
  margin: 0;
  font-size: 12.5px;
  color: var(--text-2);
  line-height: 1.45;
}
.clear-btn {
  height: 28px;
  padding: 0 10px;
  border-radius: var(--r-2);
  border-color: var(--border-2);
  color: var(--text-2);
  font-size: 12.5px;
}
.clear-btn:hover {
  color: var(--primary);
  border-color: var(--primary-7);
}

/* ---------- 会话容器 ---------- */
.chat-wrap {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  overflow: hidden;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-4);
  box-shadow: var(--shadow-1);
}
.chat-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex: none;
  padding: 11px 18px;
  background: var(--surface-2);
  border-bottom: 1px solid var(--border);
}
.chat-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
  letter-spacing: -0.005em;
}
.bot-icon {
  width: 17px;
  height: 17px;
  color: var(--primary-3);
}
.chat-sub {
  font-size: 11.5px;
  color: var(--text-3);
}
.chat-msgs {
  flex: 1;
  overflow-y: auto;
  padding: 20px 22px;
  scroll-behavior: smooth;
}

/* ---------- 空态 ---------- */
.ai-hello {
  padding: 30px 10px 6px;
  text-align: center;
}
.hello-icon {
  width: 40px;
  height: 40px;
  margin: 0 auto 8px;
  color: var(--primary-5);
}
.hello-title {
  font-size: 16px;
  font-weight: 600;
  letter-spacing: -0.011em;
}
.hello-sub {
  margin-top: 4px;
  font-size: 12.5px;
  color: var(--text-2);
}
.sugg {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: center;
  margin: 16px 0 4px;
}
.sugg button {
  padding: 6px 14px;
  font-family: inherit;
  font-size: 12.5px;
  color: var(--text-2);
  background: var(--surface);
  border: 1px solid var(--border-2);
  border-radius: 99px;
  cursor: pointer;
  transition: all 120ms;
}
.sugg button:hover {
  color: var(--primary);
  background: var(--primary-tint);
  border-color: var(--primary-5);
  transform: translateY(-1px);
}

/* ---------- 消息气泡 ---------- */
.msg {
  display: flex;
  gap: 10px;
  margin-bottom: 18px;
  animation: msgIn 240ms cubic-bezier(0.2, 0.7, 0.3, 1);
}
@keyframes msgIn {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
}
.msg.user {
  flex-direction: row-reverse;
}
.avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: none;
  width: 32px;
  height: 32px;
  color: var(--primary-3);
  background: var(--primary-tint);
  border: 1px solid var(--border);
  border-radius: 50%;
}
.avatar svg {
  width: 16px;
  height: 16px;
}
.msg.user .avatar {
  color: #fff;
  background: var(--primary);
  border-color: var(--primary);
}
.msg-main {
  max-width: 74%;
  min-width: 0;
}
.msg.user .msg-main {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}
.bubble {
  padding: 10px 14px;
  font-size: 13.5px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: var(--r-3);
}
.msg.user .bubble {
  color: #fff;
  background: var(--primary);
  border-color: var(--primary);
}
.retry-btn {
  margin-left: 6px;
  padding: 0;
  font-family: inherit;
  font-size: 13px;
  color: var(--primary-3);
  text-decoration: underline;
  background: none;
  border: none;
  cursor: pointer;
}
.typing {
  display: inline-flex;
  gap: 4px;
  padding: 5px 2px;
}
.typing i {
  width: 6px;
  height: 6px;
  background: var(--primary-5);
  border-radius: 50%;
  animation: tp 1s infinite;
}
.typing i:nth-child(2) {
  animation-delay: 0.15s;
}
.typing i:nth-child(3) {
  animation-delay: 0.3s;
}
@keyframes tp {
  0%,
  60%,
  100% {
    transform: translateY(0);
    opacity: 0.45;
  }
  30% {
    transform: translateY(-4px);
    opacity: 1;
  }
}

/* ---------- 来源 ---------- */
.sources {
  max-width: 100%;
  margin-top: 8px;
  overflow: hidden;
  font-size: 12.5px;
  line-height: 1.7;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-3);
}
.sources-head {
  display: flex;
  justify-content: space-between;
  width: 100%;
  padding: 7px 12px;
  font-family: inherit;
  font-size: 12.5px;
  color: var(--text-2);
  background: var(--surface-2);
  border: none;
  cursor: pointer;
  user-select: none;
  transition: color 120ms;
}
.sources-head:hover {
  color: var(--primary);
}
.sources-body {
  padding: 10px 12px;
  border-top: 1px solid var(--border);
}
.source + .source {
  margin-top: 8px;
}
.source b {
  font-weight: 600;
}
.source-content {
  color: var(--text-2);
}

/* ---------- 输入区 ---------- */
.chat-input {
  display: flex;
  flex: none;
  gap: 10px;
  align-items: flex-end;
  padding: 11px 16px;
  background: var(--surface-2);
  border-top: 1px solid var(--border);
}
.chat-input textarea {
  flex: 1;
  min-height: 42px;
  max-height: 120px;
  padding: 9px 12px;
  font-family: inherit;
  font-size: 13.5px;
  line-height: 1.7;
  color: var(--text);
  background: var(--surface);
  border: 1px solid var(--border-2);
  border-radius: var(--r-3);
  outline: none;
  resize: none;
  transition: border-color 120ms, box-shadow 120ms;
}
.chat-input textarea:focus {
  border-color: var(--primary-5);
  box-shadow: 0 0 0 3px rgba(28, 58, 53, 0.08);
}
.chat-input textarea::placeholder {
  color: var(--text-3);
}
.send-btn {
  height: 44px;
  padding: 0 20px;
  border-radius: var(--r-2);
  font-size: 13.5px;
  font-weight: 500;
  --el-button-bg-color: var(--primary);
  --el-button-border-color: var(--primary);
  --el-button-hover-bg-color: var(--primary-2);
  --el-button-hover-border-color: var(--primary-2);
  --el-button-active-bg-color: var(--primary-3);
  --el-button-active-border-color: var(--primary-3);
}

@media (max-width: 768px) {
  .ai-page {
    height: calc(100vh - 80px);
    min-height: 420px;
  }
  .page-head h1 {
    font-size: 18px;
  }
  .chat-sub {
    display: none;
  }
  .chat-msgs {
    padding: 16px 14px;
  }
  .msg-main {
    max-width: 86%;
  }
  .send-btn {
    padding: 0 14px;
  }
}
</style>
