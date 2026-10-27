<p align="center">
  <img src="assets/banner.svg" alt="PolyGate — 自托管多模型 API 网关" width="100%">
</p>

<p align="center">
  <a href="README.md">English</a> · <b>简体中文</b>
</p>

<p align="center">
  <strong>所有模型，同一道闸门。</strong> 把 Anthropic Messages 与 OpenAI Chat Completions 收进同一套鉴权、路由与计费边界，管理台直接内嵌在可执行 JAR 里。
</p>

<p align="center">
  <a href="https://github.com/97Three/polygate/actions/workflows/verify-release.yml"><img src="https://img.shields.io/github/actions/workflow/status/97Three/polygate/verify-release.yml?style=flat-square&label=build" alt="Build"></a>
  <img src="https://img.shields.io/badge/Java-21-E76F00?style=flat-square&logo=openjdk&logoColor=white" alt="Java 21">
  <img src="https://img.shields.io/badge/Spring_Boot-3.5-6DB33F?style=flat-square&logo=springboot&logoColor=white" alt="Spring Boot 3.5">
  <img src="https://img.shields.io/badge/PostgreSQL-18-4169E1?style=flat-square&logo=postgresql&logoColor=white" alt="PostgreSQL 18">
  <img src="https://img.shields.io/badge/React-19-087EA4?style=flat-square&logo=react&logoColor=white" alt="React 19">
  <img src="https://img.shields.io/badge/TypeScript-6-3178C6?style=flat-square&logo=typescript&logoColor=white" alt="TypeScript 6">
  <img src="https://img.shields.io/badge/License-MIT-C2536B?style=flat-square" alt="MIT">
</p>

---

## 为什么需要它

同时使用多家模型服务时，难点通常不是"调通 API"，而是：密钥散落在各个客户端、用量无法归集、预算没人兜底、出问题查不到链路。PolyGate 把这些收敛到一个进程：

- **一个入口** —— 客户端只认一套 `POLYGATE_*` 凭证，上游密钥留在服务端，支持轮换与故障切换。
- **两种协议** —— 原生支持 Anthropic Messages 与 OpenAI Chat Completions，共用同一条路由、配额与日志链路。
- **可结算** —— 每次调用都有价格快照、预留、结算和只追加账本；超预算的请求在进入上游之前就被拒绝。
- **可治理** —— 租户、项目、环境三级作用域，模型与 Provider 授权，IP 策略，双人审批，审计与数据留存。
- **可观测** —— Prometheus 指标、请求级追踪、运维事件；管理台 11 个页面全部连接真实 API。

## 架构

<p align="center">
  <img src="assets/architecture.svg" alt="请求链路：客户端 → 鉴权 → 协议转换 → 配额 → 路由 → 上游；账本与指标写入 PostgreSQL" width="100%">
</p>

每个请求都经过同一条流水线：鉴权与租户策略、协议转换、配额与预算预留，然后是带健康检查与故障切换的路由。账本、审计与用量记录写入 PostgreSQL，指标和结构化日志在同一条链路上产生。

整个网关是一个 Spring Boot 进程。React 管理台在打包阶段构建，由 JAR 直接提供，生产环境不需要 Node 运行时，也没有独立的前端容器。

## 快速开始

### Docker Compose（推荐）

```bash
cp .env.example .env
# 替换所有 replace-with-* 占位值：数据库密码、管理员初始密码、上游密钥
docker compose up -d --build
```

启动后：

- 管理台与 API：<http://127.0.0.1:18080>
- 健康检查：<http://127.0.0.1:18080/actuator/health>
- 首次启动会用 `POLYGATE_ADMIN_BOOTSTRAP_PASSWORD` 创建 `admin` 账号

### 本地构建

```bash
# 前端
cd dashboard && npm ci && npm run build && cd ..

# 后端（前端产物会打进 JAR）
./mvnw -B verify
java -jar target/polygate-1.0.0.jar
```

Windows 上可直接执行 `.\scripts\build.ps1`，脚本会把 JDK、Node 与 Maven 缓存固定到项目内的 `tools/`、`.m2/`、`.npm/`，不污染系统环境。

> 数据库默认关闭。只跑网关、不接 PostgreSQL 时设置 `POLYGATE_DATABASE_ENABLED=false`；启用后 Flyway 会自动执行 `src/main/resources/db/migration` 下的迁移。

## 客户端接入

```bash
# Anthropic Messages 协议
curl http://127.0.0.1:18080/v1/messages \
  -H "x-api-key: $POLYGATE_CLIENT_TOKEN" \
  -H "anthropic-version: 2023-06-01" \
  -H "content-type: application/json" \
  -d '{"model":"deepseek:deepseek-v4-flash","max_tokens":256,
       "messages":[{"role":"user","content":"hello"}]}'

# OpenAI Chat Completions 协议
curl http://127.0.0.1:18080/v1/chat/completions \
  -H "Authorization: Bearer $POLYGATE_CLIENT_TOKEN" \
  -H "content-type: application/json" \
  -d '{"model":"deepseek:deepseek-v4-flash","stream":true,
       "messages":[{"role":"user","content":"hello"}]}'
```

两条通道都支持 SSE 流式输出、工具调用、图片与推理字段；不能无损转换的字段会明确报错，而不是静默丢弃。

| 端点 | 说明 |
|---|---|
| `POST /v1/messages` | Anthropic Messages（含流式） |
| `POST /v1/messages/count_tokens` | 上游 token 计数 |
| `POST /v1/chat/completions` | OpenAI Chat Completions（含流式） |
| `GET /v1/models` | 当前凭证可见的模型目录 |
| `GET /v1/effective-policy` | 当前调用方的有效策略 |
| `GET /livez` `/readyz` `/health` | 存活与就绪探针 |
| `GET /metrics` | Prometheus 指标（Bearer token 保护） |

## 配置

所有配置通过环境变量注入，完整清单见 [`.env.example`](.env.example)。

| 变量 | 默认值 | 说明 |
|---|---|---|
| `POLYGATE_BIND` | `127.0.0.1` | 监听地址，容器内需设为 `0.0.0.0` |
| `POLYGATE_PORT` | `18080` | HTTP 端口 |
| `POLYGATE_DATABASE_ENABLED` | `false` | 是否启用 PostgreSQL 持久化 |
| `POLYGATE_DATABASE_URL` | — | JDBC URL，库名必须是 `polygate` 或 `polygate_<suffix>` |
| `POLYGATE_ADMIN_BOOTSTRAP_PASSWORD` | — | 空库首次启动时创建 `admin` 的密码 |
| `POLYGATE_CLIENT_TOKEN` | — | 客户端共享令牌（建议改为按用户发放 API Key） |
| `POLYGATE_UPSTREAM_URL` | `http://127.0.0.1:19090` | 默认上游地址 |
| `POLYGATE_UPSTREAM_KEY` | — | 默认上游密钥 |
| `POLYGATE_UPSTREAM_PROTOCOL` | `anthropic` | `anthropic` 或 `openai_compat` |
| `POLYGATE_APPROVED_UPSTREAM_HOSTS` | 空 | 额外放行的上游主机，逗号分隔 |
| `POLYGATE_TRUSTED_PROXIES` | 空 | 可信反代 CIDR，为空时忽略所有转发头 |
| `POLYGATE_OIDC_*` | 空 | 企业单点登录，未配置时自动关闭 |

## 管理控制台

管理台由同一 JAR 提供，覆盖：

- **概览** —— 请求量、延迟、错误率、成本趋势与健康状态
- **模型接入** —— Provider、凭证池、模型目录、别名、灰度与影子路由
- **用户与密钥** —— 用户、团队、API Key 生命周期与轮换、权限作用域
- **配额与预算** —— 日/周/月配额、预算预留与结算、调整流水
- **请求日志** —— 按状态、模型、延迟、成本过滤，单请求下钻
- **企业** —— 成本中心、预算事件、租户维度用量
- **治理** —— 项目策略、出站边界、双人审批、审计与留存
- **运维** —— 事件、反馈、恢复动作、受预算约束的模型分析（默认关闭）
- **设置** —— 运行时配置、Provider 连通性测试、配置热加载

## 可观测性

`deploy/observability/` 提供 Prometheus 抓取配置、告警规则和 Grafana 面板；`/metrics` 暴露请求量、token、延迟分位、拒绝原因、账本操作等指标。排障步骤见 [RUNBOOK](deploy/observability/RUNBOOK.md)。

## 项目结构

```text
src/main/java/dev/polygate/      网关、路由、计费、治理、管理端 API
src/main/resources/db/migration/ Flyway 迁移（V1 → V23）
src/main/resources/schemas/      Runtime Adapter 的 JSON Schema
dashboard/                       React 19 + Vite 控制台
deploy/observability/            Prometheus / Grafana / Runbook
scripts/                         构建、部署、备份、验收脚本
```

## 开发与测试

```bash
./mvnw -B verify                 # Java 单测 + 集成测试（内嵌 PostgreSQL）
cd dashboard && npm run check    # 类型检查 + ESLint + Vitest + 生产构建
```

端到端验收用 Playwright，可对真实 Java 后端逐页验证：

```bash
cd dashboard
PLAYWRIGHT_BASE_URL=http://127.0.0.1:18080 \
PLAYWRIGHT_SKIP_WEBSERVER=1 npm run e2e:java
```

`scripts/verify-*.ps1` 系列在隔离库和本地模拟上游上验收权限、预算、审批、传输、回退与运维流程。

## 安全基线

- 容器以非 root 用户运行、只读根文件系统、丢弃全部 capability
- 管理端密码使用 Argon2id，登录限速、CSRF、会话 Cookie 与可选 OIDC
- 客户端凭证只存哈希；上游密钥通过环境变量引用，不落库
- 请求体大小、并发、超时与重试均有硬上限
- 高风险变更（预算、策略、凭证）支持双人审批与完整审计

## License

[MIT](LICENSE)
