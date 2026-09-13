# DeepSeek Harness 使用指南

> 2026-09-10 整理。基于官方 README、官方 subagent 文档、及多篇社区实测报告交叉核实。

## 1. 是什么

DeepSeek Harness（`dsh`）是 DeepSeek 于 2026-08-13 开源的 AI Agent 运行时框架，MIT 协议，GitHub 仓库 `deepseek-ai/deepseek-harness`。

官方公式：**Agent = Model + Harness**
- 模型负责思考推理
- Harness 负责实际执行：上下文管理、工具调用、文件读写、任务规划、子 Agent 调度、会话持久化

底层基于 **Cordis 插件元框架**，核心理念"一切皆插件"：模型适配器、工具注册、会话存储、沙箱、Agent 循环本身，全都是可替换插件 [$TRAE_REF](https://juejin.cn/post/7673522589587177526)。

**当前状态**：Developer Preview v0.1，官方警告"THERE WILL BE COMPATIBILITY-BREAKING CHANGES" [$TRAE_REF](https://beta.hyper.ai/en/stories/4642f3eb645603d9cdaf7e7b10dd60f6)。

## 2. 四种运行模式

| 模式 | 说明 | 适用场景 |
|---|---|---|
| **Standard** | 完整工具链：文件编辑、Shell、搜索、Skills、规划、子 Agent、工作流 | 日常开发 |
| **PTC** | 程序化工具调用：模型生成 TypeScript 程序批量调用工具，多步合一 | 多步骤、有分支/并行的任务 |
| **Minimal** | 仅 bash + 文件编辑器 | 基准测试、受控评测 |
| **Creative** | Standard 全家桶 + 运行时插件试验 | 开发新插件、构建自定义预设 |

```bash
npx @deepseek-ai/dsh web                      # Standard（默认）
npx @deepseek-ai/dsh web --mode ptc           # PTC 模式
npx @deepseek-ai/dsh web --mode minimal       # Minimal
npx @deepseek-ai/dsh web --mode creative      # Creative
```
[$TRAE_REF](https://deepseek.csdn.net/6a8919b7662f9a54cb9f60a9.html)

## 3. 三种使用入口

| 入口 | 命令 | 适用 |
|---|---|---|
| **Web UI** | `npx @deepseek-ai/dsh web` → `http://127.0.0.1:3080` | 交互式开发 |
| **Headless CLI** | `dsh --profile headless "任务描述"` | 脚本/CI，跑完即退 |
| **Python SDK** | `pip install deepseek-harness-sdk`（Python 3.10+） | 嵌入 Python 应用 |

[$TRAE_REF](https://juejin.cn/post/7676104122034487359)

## 4. 安装与配置

### 4.1 前置要求
- Node.js `^22.19.0 || >=24.0.0`（推荐 v24）
- 无需安装 Electron，通过浏览器访问 Web UI

### 4.2 快速启动
```bash
# 最快方式：npx 直接跑，无需安装
npx @deepseek-ai/dsh web

# 配置模型：环境变量
export DEEPSEEK_API_KEY=your_key
npx @deepseek-ai/dsh web --model deepseek-coder

# 或用 OpenAI 兼容模型
export OPENAI_API_KEY=your_key
npx @deepseek-ai/dsh web --model gpt-4o
```

### 4.3 模型配置（settings.yaml）
dsh 模型无关（Model-Agnostic），支持 DeepSeek、OpenAI、Anthropic、Bedrock、Azure、Gemini、Kimi 及任意 OpenAI 兼容端点（含 Ollama）。

```yaml
llm-pi-ai:
  providers:
    my-gateway:                    # 自定义 provider
      apiKeyEnv: GATEWAY_API_KEY
      api: openai-completions
      baseURL: https://gateway.example/v1
      models:
        - id: legacy-chat
        - id: vision-preview
          input: [text, image]    # 视觉模型需声明模态
```
[$TRAE_REF](https://juejin.cn/post/7673522589587177526)

### 4.4 源码安装（开发插件时需要）
```bash
git clone https://github.com/deepseek-ai/deepseek-harness.git
cd deepseek-harness
corepack enable
pnpm install
pnpm run build    # 不能省略，否则 Web 端缺构建产物
pnpm dsh web
```
[$TRAE_REF](https://juejin.cn/post/7676104122034487359)

## 5. 子 Agent 与多 Agent 编排

### 5.1 内置 subagent 能力
dsh 的 subagent 能力由 `@dsh/plugin-subagents` 提供，支持**多种 provider 后端共存**（按名称注册到 `ctx.subagents`）：

| Provider | 说明 |
|---|---|
| `dsh-subagent-spawn-in-process` | 同进程派生子 Agent |
| `dsh-subagent-fork-in-process` | 同进程 fork |
| `dsh-subagent-acp` | ACP 协议远程 Agent |
| `dsh-subagent-codex` | 调用 OpenAI Codex CLI 作为子 Agent |
| `dsh-subagent-claude-code` | 调用 Claude Code CLI 作为子 Agent |
| `dsh-subagent-dsh-sdk` | 通过 Python SDK 派生 |

[$TRAE_REF](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/subagent.md)

### 5.2 RC.8 的 Profile Bundle：dsh 调度 + Claude Code/Codex 执行
v0.1.0-rc.8 把 Claude Code 和 Codex 做成可按需安装的 **Profile Bundle**：
- dsh 当**调度层**：拆任务、分配
- Claude Code / Codex 当**执行层**：各自干专业活
- 前提：本机已安装对应 CLI 并已登录（dsh 从 PATH 找二进制）

[$TRAE_REF](https://juejin.cn/post/7676104122034487359)

### 5.3 社区多 Agent 插件

| 插件 | 功能 |
|---|---|
| `dsh-agent-teams` | 队长式多 Agent 团队编排：自动创建子 Agent、任务按依赖推进（DAG 可视化）、实时面板 |
| `dsh-expert-mode` | 首席协调官 + 17 位领域专家子 Agent 预设 |
| `dsh-plugin-task-coordinator` | Codex 式跨任务协调：list/inspect/spawn/message/steer |
| `dsh-plan-execute` | 双模型路由：plan 用推理模型、execute 用执行模型 |
| `dsh-gsd-bundle` | spec/discuss/plan/execute/verify/ship 阶段循环 |

[$TRAE_REF](https://github.com/0xsline/awesome-deepseek-harness) [$TRAE_REF](https://github.com/Asher-2000/dsh-expert-mode)

### 5.4 子 Agent 核心特性
- **隔离上下文**：子 Agent 有独立上下文窗口，只把摘要返回主会话
- **可续接（Continuable）**：子 Agent 会话可跨轮续接
- **相邻 Agent 消息**：子 Agent 之间可发消息（`send_message` / `interrupt_agent` / `list_agents`）
- **深度限制**：`depthLimit` 控制嵌套层数
- **工具过滤**：`toolFilter` 限制子 Agent 可用工具集
- **persona**：自定义子 Agent 人设

[$TRAE_REF](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/subagent.md)

## 6. PTC 模式详解（对本项目最有价值）

PTC（Program That Calls）模式下，模型不逐步调用工具，而是**生成一段 TypeScript 程序**，用完整编程构造组合调用工具：

```typescript
// PTC 模式下模型生成的程序示例
async function analyzeProject() {
  const files = await listFiles({ pattern: "src/**/*.ts" });
  const analyses = await Promise.all(
    files.map(f => readFile({ path: f }))
  );
  const hasTests = files.some(f => f.includes(".test."));
  if (!hasTests) {
    await createFile({
      path: "src/__tests__/basic.test.ts",
      content: generateTestTemplate(analyses)
    });
  }
  return summarize(analyses);
}
```

- 支持 `if/else`、`for/while`、`try/catch`、`Promise.all` 并行
- 多步工具调用压缩为一次执行，省 token、省延迟
- **对本项目**：适合批量创建文件、条件化生成代码、并行处理多模块

[$TRAE_REF](https://deepseek.csdn.net/6a8919b7662f9a54cb9f60a9.html)

## 7. Trajectory 轨迹溯源

所有会话日志采用 **Append-only** 格式，记录：
- 系统提示词完整内容
- 模型思维链（Chain of Thought）
- 每次工具调用：函数名、参数、返回值
- 子 Agent 调度：哪个父 Agent 启动了哪个子 Agent
- 每次上下文注入：从哪个插件注入了什么信息

```bash
dsh resume --session <id> --from-step 15   # 从某步恢复
dsh fork --session <id> --at-step 10        # 从某步分叉
dsh replay --session <id>                   # 重放整个会话
dsh trajectory --session <id> --step 8      # 查看某步完整上下文来源
```

[$TRAE_REF](https://deepseek.csdn.net/6a8919b7662f9a54cb9f60a9.html)

## 8. 插件开发（自定义工具时需要）

### 8.1 极简工具插件（约 20 行）
```typescript
import type { Context } from '@deepseek-ai/cordis'
import { defineTool } from '@deepseek-ai/dsh-tools'

export const name = 'greet-tool'
export const inject = ['tools']

export function apply(ctx: Context) {
  ctx.tools.register(defineTool({
    name: 'greet',
    description: 'Greet someone by name.',
    parameters: {
      name: { type: 'string', required: true, description: 'The name to greet' }
    },
    async execute(args) {
      return `你好, ${args.name}!`
    }
  }))
}
```

### 8.2 挂载到 Web 服务
```yaml
# cordis.yml
- insert:
  - id: greet-tool
    name: "/绝对路径/deepseek-harness/scratch-plugin/src/greet-tool.ts"
```
```bash
pnpm dsh web --patch ./scratch-plugin/cordis.yml
```

### 8.3 生产级插件硬规则
- 必须用具名导出 `name / inject / Config / apply`，**禁止 default export**
- `inject` 只声明硬依赖，可选服务用 `ctx.get(name)`
- 工具必须走 `defineTool`，参数校验 + 输出 Schema
- 密钥永远不写进 patch（`--dump-config` 会全打出来）
- 排错第一手段：`dsh --profile web --dump-config`

[$TRAE_REF](https://juejin.cn/post/7676104122034487359)

### 8.4 安装现成插件
```bash
dsh plugin --profile web add @dsh-external/dsh-git-workflow
dsh plugin --profile web update          # 更新全部
dsh plugin --profile web remove <name>  # 卸载
dsh restart web                          # 重启生效
```
社区插件发现：GitHub topic `dsh-plugin` [$TRAE_REF](https://github.com/0xsline/awesome-deepseek-harness)

## 9. 安全与沙箱

- 文件操作分三级：只读 / 工作区写入 / 完全访问
- 敏感操作可配置人工审批
- 沙箱：Linux 用 Landlock（~300 行 C11），macOS 用 Seatbelt，Windows 用 ACL restricted-token
- 未选工作区时输入框禁用

[$TRAE_REF](https://juejin.cn/post/7676104122034487359)

## 10. 对本项目的适用性分析

### 10.1 适合做什么
| 本项目任务 | dsh 能力 | 模式 |
|---|---|---|
| 批量生成 app/ Kotlin 文件 | 文件编辑 + PTC 批量 | Standard 或 PTC |
| 生成 Go 信令服务代码 | 文件编辑 + Shell 编译 | Standard |
| 生成 native/ C++ 代码 | 文件编辑 + 代码风格遵守 | Standard |
| SSH 到云主机部署 coturn | Shell 工具（持久化） | Standard |
| SSH 到云主机编译 libwebrtc | Shell 工具 | Standard |
| 拆分多模块并行开发 | 子 Agent / dsh-agent-teams | Standard + subagent |

### 10.2 关键约束
1. **Node.js 依赖**：dsh 本身是 TypeScript/Node.js，需在开发机（Windows）装 Node 24+
2. **模型 Key 必配**：需 DeepSeek API Key 或其他兼容模型 Key
3. **Developer Preview 风险**：API 可能有破坏性变更
4. **子 Agent 调 Claude Code/Codex 需本机已装对应 CLI**：rc.8 的 Profile Bundle 功能
5. **插件安装后需重启**：不能热生效（Creative 模式除外）

### 10.3 建议的工作方式

**方案 A：单 Agent + PTC 模式（最简）**
- 一个 dsh Web UI 会话，工作区指向 `e:\code\project\webrt-demo`
- 给 dsh 读 `doc/` 全部文档作为上下文
- 用 PTC 模式让它批量生成文件、条件化创建代码
- 适合：一个人从头到尾推进，不并行

**方案 B：多 Agent + dsh-agent-teams（并行）**
- 装 `dsh-agent-teams` 插件
- 主 Agent 当队长，拆任务给子 Agent：
  - Agent A：`app/` Kotlin 开发
  - Agent B：`signaling/` Go 开发
  - Agent C：SSH 到云主机部署 coturn
  - Agent D：SSH 到云主机编译 libwebrtc
- DAG 依赖：A 依赖 D（需 .a 产物）、A 依赖 B（需协议定义）
- 适合：并行推进多模块，但需要协调依赖

**方案 C：dsh 调度 + Claude Code 执行（最强但最贵）**
- rc.8 Profile Bundle，dsh 拆任务，Claude Code 执行
- 前提：本机已装 Claude Code CLI + 已登录
- 适合：已有 Claude Code 订阅，想用最强执行层

## 11. 参考来源

- [官方 GitHub 仓库](https://github.com/deepseek-ai/deepseek-harness)
- [掘金：DeepSeek Harness 深度调研报告](https://juejin.cn/post/7676104122034487359)
- [掘金：一切皆插件的 AI Agent 编程框架](https://juejin.cn/post/7673522589587177526)
- [CSDN：开源项目第194期 deepseek-harness](https://deepseek.csdn.net/6a8919b7662f9a54cb9f60a9.html)
- [官方 subagent 文档](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/subagent.md)
- [Awesome DeepSeek Harness](https://github.com/0xsline/awesome-deepseek-harness)
- [Hyper.ai：DeepSeek Releases Open Source Harness](https://beta.hyper.ai/en/stories/4642f3eb645603d9cdaf7e7b10dd60f6)
