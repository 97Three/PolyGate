# PolyGate 管理台

React 19 + TypeScript + Vite 控制台。生产构建（`npm run build`）输出到 `dashboard/dist`，由根项目 Maven 打进可执行 JAR，不需要单独的 nginx 容器。

## 本地开发

```bash
npm ci
npm run dev -- --host 127.0.0.1
```

开发服务器通过 `vite.config.ts` 把 `/admin`、`/v1`、`/actuator` 代理到 `http://127.0.0.1:18080`，可用 `POLYGATE_VITE_PROXY_TARGET` 覆盖。

## 质量检查

```bash
npm run check    # 类型检查 → ESLint → Vitest → 生产构建
```

## 端到端验收

```bash
# 对真实 Java 后端逐页验证
PLAYWRIGHT_BASE_URL=http://127.0.0.1:18080 \
PLAYWRIGHT_SKIP_WEBSERVER=1 \
npm run e2e:java
```

已安装 Edge 时可加 `PLAYWRIGHT_CHANNEL=msedge`。`npm run e2e` 会跑全部 Playwright 用例，需要可用的后端与测试数据。

## 目录

```text
src/components/ui       基础组件
src/components/layout   布局、导航、权限路由
src/components/shared   通用展示组件
src/features            按业务域拆分的纯逻辑与组件
src/pages               页面
src/services            API 客户端
src/stores              Zustand 状态
src/types               类型定义
src/mock                本地 mock 数据
e2e                     Playwright 用例
```
