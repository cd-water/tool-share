# tool-share 前端

Vue 3 + Vite + TypeScript 单页应用。

## 技术栈

- Vue 3 (`<script setup>` SFC)
- Vite + TypeScript
- Element Plus(自动按需引入)+ @element-plus/icons-vue
- Pinia + pinia-plugin-persistedstate
- Vue Router
- Axios
- @vueuse/core / dayjs

## 脚本

```bash
pnpm install      # 安装依赖
pnpm dev          # 启动 dev server(http://localhost:5800)
pnpm build        # 类型检查 + 生产构建
pnpm preview      # 预览构建产物
```

## 与后端的连接

Vite dev server 通过 `/api` 代理到后端 `http://localhost:8800`(`vite.config.ts`)。所有 HTTP 响应都是 `Result<T>` 信封,Axios 层负责解包 `data` 并把非 `A200` 的 `code/message` 抛到上层处理。
