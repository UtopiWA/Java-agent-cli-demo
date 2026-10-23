# Java 单元测试生成 Agent CLI

这是一个面向单模块 Maven 项目的垂直 Agent。模型通过统一的 Tool Calling 循环分析 Java 代码、生成或维护 JUnit 5 测试，并用真实的 Maven 定向测试和完整回归验证结果。

## 支持范围

- Java 17、Maven 3.9+、JUnit Jupiter；
- 普通 JUnit Jupiter `@Test` 方法；支持与 `@DisplayName` 等辅助注解组合且不限制注解顺序；
- 标准 `src/main/java` 和 `src/test/java` 目录；
- 源码相对路径或全限定类名作为目标；
- `ANALYSIS`、`VERIFY`、`CHANGE` 三类执行轮次，以及不创建轮次的 `CONSTRAINT` 持久约束入口；
- 每个任务固定对应一个目标类和一个测试文件，可以包含多轮维护；
- 每个修改轮次最多三个候选，失败后恢复到该轮开始时的测试文件。

暂不支持 Gradle、多模块 Maven、自动修改生产代码或 `pom.xml`、Spring 容器测试、数据库及网络集成测试。Mockito、参数化测试、重复测试、动态测试和 JaCoCo 属于后续扩展；`@ParameterizedTest`、`@RepeatedTest`、`@TestFactory` 和 `@TestTemplate` 不能代替普通 `@Test`。

## 构建与启动

```powershell
mvn clean package
java -jar target/agent-cli-0.1.0.jar
```

也可以使用自动构建脚本：

```powershell
.\run.ps1
.\run.ps1 -ProjectRoot D:\projects\my-maven-project
```

脚本会在 JAR 不存在或源码较新时执行 `mvn -q -DskipTests package`，之后以指定项目作为工作目录启动 CLI。日常修改 Agent 代码后无需手工判断是否重新构建；直接再次运行脚本即可。

在 Agent 项目或待测试项目根目录的 `.env` 中配置：

```dotenv
GLM_API_KEY=xxx
```

也可以使用进程环境变量。API Key 不会写入任务历史，也不会传给 Maven 子进程。未配置 Key 时仍可使用 `help`、`history`、`show` 等本地命令，但不能启动模型轮次。

## CLI 命令

```text
new <目标类路径或全限定类名> [--overwrite] [--fresh]
status
history [--limit N] [--offset N]
show <task-id> [--round N]
resume <task-id>
rollback [--round N]
delete <task-id>
help
exit / quit
```

命令提示符会显示当前任务：`test-agent[none]>` 表示尚未创建或恢复任务，
`test-agent[8f2c1a20]>` 表示后续自然语言输入将作用于该任务。任务标签默认取 UUID
前 8 位；如果前 8 位在当前项目中发生冲突，程序会自动增加位数，直到前缀可以唯一定位任务。
该标签可以直接用于 `show`、`resume` 和 `delete`。

`history` 使用相同的唯一前缀规则，并按当前页内容动态对齐 ASCII 列。若手工输入的
前缀仍命中多个任务，`show`、`resume` 和 `delete` 都会拒绝执行，列出每个候选的唯一
前缀、目标类和最新状态，并给出可直接复制的重试命令；程序不会自动选择其中一个任务。

```text
ID          ROUND               VALIDATION   COUNT  TARGET
12345678-1  CHANGE/SUCCEEDED    VALIDATED@2      3  com.example.Calculator
12345678-2  ANALYSIS/ANALYZED   UNVERIFIED       1  com.example.OrderService
```

示例：

```text
new com.example.Calculator
为 Calculator 生成正常、边界和除零异常测试
status
show 8f2c1a20
```

已有测试文件默认只读，只有创建任务时显式使用 `--overwrite` 才能接管。相同目标默认提示恢复原任务；`--fresh` 才会创建独立记录，并且不隐含覆盖许可。

同一项目同时只允许一个 CLI 实例执行写操作。未取得项目锁的实例进入只读模式；原持锁实例退出后，只读实例会在下一条 `new`、`resume`、`rollback`、`delete` 或自然语言请求前自动重试获取锁，成功后无需重启即可继续执行。`status` 会显示当前项目访问模式。

## 自然语言意图分类

每条自然语言要求在创建轮次前先经过一次不携带工具的轻量模型分类，结果只能是：

- `ANALYSIS`：只分析，不写文件、不运行 Maven；
- `VERIFY`：验证已有测试，不生成候选；
- `CHANGE`：生成、补充或修改测试；
- `CONSTRAINT`：只添加今后各轮持续生效的测试规则。

分类会结合否定表达和完整语义，例如“只分析，不生成或运行测试”应得到 `ANALYSIS`。只设置持久规则时，`CONSTRAINT` 会保存整条要求并立即返回命令提示符，不创建轮次、不读取项目文件、不执行 Maven；如果同一条输入还要求本次分析、验证或修改，应按实际动作分类，持久规则建议单独输入。终端会显示 `[intent]`、分类来源和该次调用的 Token 用量。分类调用失败或返回的不是单独一个合法枚举值时，程序按 `ANALYSIS` 降级；轮次创建后不允许正式 Agent 自行提升权限。该轻量调用不计入轮次内最多 15 次正式 Agent 响应额度，但应计入整体模型用量。

## 版本回滚

`rollback` 恢复当前任务中比当前版本更早的最近成功 `CHANGE` 版本；`rollback --round N` 恢复指定的成功 `CHANGE` 轮次。命令会建立一个模型调用数为 0 的新 `CHANGE` 轮次，依次执行命令前完整基线、原子恢复、目标测试和完整回归。验证失败时恢复命令执行前的文件；检测到外部修改时在写入前拒绝回滚。

模型可以根据测试设计自主删除或重命名已有测试方法，静态校验不再根据用户文本关键词授权。被移除的方法名会记录在候选、`show --round` 和最终报告中，用户可以通过 `rollback` 恢复历史成功版本。

## 五个模型工具

| 工具 | 领域行为 |
|---|---|
| `list_files` | 有深度、条目、字符和扫描上限的分页目录树 |
| `read_file` | 带行号的 UTF-8 分页读取；单文件最大 2 MiB，重复区间使用缓存标识 |
| `search_code` | 仅搜索 Java 文件，执行区分大小写的字面量匹配 |
| `write_file` | 只接收非空的完整测试源码；目标路径由当前任务固定，最大 24 KiB |
| `execute_command` | 只接受 `baseline_test`、`target_test`、`regression_test` 三种动作 |

`.env*`、`.git`、`.agent`、凭证文件和 `target` 构建产物不会进入模型上下文。四个文件工具统一遵守项目根目录的 `.agentignore`。任务记录保存在 `.agent/tasks/<task-id>/`，其中只保存有界结构化摘要；源码正文、`write_file.content` 和 Maven 原始日志不会写入任务 JSON。

空字符串、纯空白或参数结构错误会在建立候选前拒绝，不消耗三次候选额度。每次 `write_file` 的工具事件只记录参数来源、JSON/字段类型、字符数、UTF-8 字节数和 SHA-256，不保存测试正文。GLM 返回的 `arguments` 必须是字符串，客户端会原样保留，不能把异常类型静默替换成 `{}`；因此可以区分模型提交空内容、缺少字段、字段类型错误和正常源码被静态规则拒绝。

## 修改轮次

```text
读取目标和必要上下文
  -> 完整基线测试
  -> 候选静态校验与事务写入
  -> 目标测试
  -> 完整回归
  -> 提交；失败则有限修正或回滚
```

生成测试通过只说明实际执行的断言通过，不保证断言完整表达业务需求。最终结果仍需人工检查断言合理性、边界覆盖和实现耦合程度。

## 安全说明

路径限制、关键词扫描和环境变量清理不是操作系统级沙箱。Maven 构建与 JUnit 测试可以执行项目中的任意代码，只应对可信本地仓库使用本工具。对于不可信项目，应额外使用容器、低权限账户、网络隔离和资源限制。

---

以下内容保留早期课程实验的演进记录。

# Java AI Coding Agent CLI（历史实验记录）

这是一个按实验逐步演进的 Java 17 命令行项目，最终目标是构建能够通过 LLM 和 Tool 完成编码任务的 AI Coding Agent。

当前项目进度：

```text
Lab 01：Maven Project + Java CLI + Test + Git
Lab 02：ListFilesTool + ReadFileTool + SearchCodeTool
Lab 03：GLM Client + Tool Registry + ReAct Agent
Lab 04：WriteFileTool + ExecuteCommandTool + Safety Guard
Lab 05：Session Persistence + History Commands + Context Budget
```

## 环境要求

- JDK 17 或更高版本
- Maven 3.9 或更高版本

## 构建和测试

在 `agent-cli` 目录执行：

```shell
mvn clean test
mvn package
```

## Lab 01：Java Project Bootstrap

### 本次增量

Lab 01 建立 Java CLI 的工程基础。程序逐行读取标准输入并输出响应：

- 输入 `hello`（忽略首尾空白和大小写）时输出 `Hello, Agent!`；
- 输入其他内容时输出 `I heard: <输入>`；
- 输入 `quit`（忽略首尾空白和大小写）时退出；
- 使用 `--upper` 参数时，所有响应转换为大写。

核心类职责：

- `com.agent.Agent`：处理“输入 → 响应”的核心逻辑；
- `com.agent.Main`：解析参数，并完成 stdin/stdout 的 I/O 接线。

### 运行 CLI

以下命令记录 Lab 01 阶段的运行方式。当前版本中，无参数入口已经升级为 Lab 03 Agent CLI；`--upper` 仍保留 Lab 01 的大写响应演示模式。

编译后直接运行：

```shell
mvn -q -DskipTests compile
java -cp target/classes com.agent.Main
```

通过 Maven 运行：

```shell
mvn -q exec:java
mvn -q exec:java -Dexec.args="--upper"
```

运行可执行 JAR：

```shell
java -jar target/agent-cli-0.1.0.jar
java -jar target/agent-cli-0.1.0.jar --upper
```

程序支持标准输入管道。例如，在 PowerShell 中：

```powershell
"hello" | java -jar target/agent-cli-0.1.0.jar
"hello" | java -jar target/agent-cli-0.1.0.jar --upper
```

完成 Lab 01 后，项目形成可运行、可测试、可打包和可版本管理的基础框架。

## Lab 02：File Tools

### 本次增量

Lab 02 在 V1 上增加访问项目文件系统的能力：

- `ListFilesTool`：递归列出相对文件路径，支持最大深度和带连接线的紧凑树形输出；
- `ReadFileTool`：读取 UTF-8 文本，支持按行 `offset + limit` 分页；
- `SearchCodeTool`：按字面量或正则表达式搜索代码，返回相对路径、行号和命中内容；
- `FileToolsDemo`：从命令行依次演示文件列举、树形展示、读取和搜索；
- `ToolResultSerializer`：将不同 Tool 的返回值序列化为 JSON 文本，为后续统一 Tool 接口预演输出格式。

三个 Tool 目前保持独立方法签名，这是为了在后续实验中自然演进到统一的 Tool 抽象。

### 主要 API

| Tool | 基础方法 | 增强方法 |
|---|---|---|
| `ListFilesTool` | `listFiles(Path root)` | `listFiles(Path root, int maxDepth)`、`tree(...)` |
| `ReadFileTool` | `readFile(Path path)` | `readFile(Path path, long offset, long limit)` |
| `SearchCodeTool` | `searchCode(String keyword, Path root)` | `searchCode(Pattern pattern, Path root)` |

搜索结果使用 `SearchCodeTool.Match` 表示，包含：

```text
file + line + content
```

### 忽略规则

项目根目录的 `.agentignore` 使用类似 `.gitignore` 的规则，默认忽略：

- `target/`、`.git/` 和 `.idea/`；
- `.class`、`.jar`、图片和 PDF 等二进制文件。

代码搜索还会抽样检查文件内容。单个文件不可读、编码异常或被判断为二进制文件时会跳过，不会中断整个项目搜索。

### 运行 File Tools Demo

编译后运行说明书中的基础示例：

```shell
mvn -q -DskipTests compile
java -cp target/classes com.agent.tool.FileToolsDemo README.md TODO
```

三个参数依次表示：待读取文件、搜索表达式、可选的 `--regex` 标志。正则搜索示例：

```shell
java -cp target/classes com.agent.tool.FileToolsDemo README.md "TODO-\d+" --regex
```

Demo 输出分为以下部分：

```text
list_files         文件路径列表
tree               带 ├──、└──、│ 的紧凑目录树
read_file          指定文件内容
search_code        文件、行号和命中内容
serialized_matches JSON 格式的搜索结果
```

完成 Lab 02 后，项目在保留原有 Java CLI 的基础上，具备读取和搜索项目代码的能力。

## Lab 03：GLM Tool Calling Agent

### 本次增量

Lab 03 在 Lab 02 文件工具之上接入 GLM，把“用户直接调用 Tool”升级为“模型自主选择 Tool”：

- `Message`、`ToolCall`、`LLMResponse`：表示多轮消息、模型工具调用和模型响应；
- `LLMClient`：隔离模型供应商，便于使用 Fake 实现进行离线测试；
- `GlmClient`：使用 OkHttp + Jackson 调用 GLM Chat Completions 接口；
- `ToolRegistry`：向模型描述工具，并把模型产生的 JSON 参数适配到 Lab 02 的三个文件工具；
- `Agent`：维护 Message History，执行 Tool Call → Observation → Final Answer 的 ReAct 循环；
- `Main`：加载 API Key，组装组件并提供支持 `clear`、`exit` 和 `quit` 的交互式 CLI。

当前使用的模型和接口为：

```text
Model:    glm-5.3-flash
Endpoint: https://open.bigmodel.cn/api/paas/v4/chat/completions
```

完成 Lab 03 后，项目已经具备模型决策、工具调用和内存消息历史管理能力。文件写入、命令执行和持久化会话由后续增量补充。


### 核心组件

| 组件 | 职责 |
|---|---|
| `GlmClient` | 把 Java 消息和工具定义转换为 HTTP/JSON，并解析 `content`、`tool_calls` 和 Token 用量 |
| `ToolRegistry` | 生成工具 JSON Schema、校验项目路径、执行 Tool，并统一返回字符串 Observation |
| `Agent` | 保存多轮历史，根据模型响应决定执行工具、继续推理或返回最终回答 |
| `Main` | 加载配置、创建对象并处理终端命令，不包含 LLM 协议或文件操作逻辑 |

### Lab 03 阶段的 Agent CLI 运行流程

```text
用户输入
   ↓
Main 调用 Agent.run(input)
   ↓
Agent 将 user 消息加入 Message History
   ↓
GlmClient 发送 messages + tools ───────────────► GLM
   ↓                                            │
模型是否返回 tool_calls？ ◄──────────────────────┘
   ├─ 否：保存 assistant 消息并返回最终回答
   │
   └─ 是：保存 assistant(tool_calls)
           ↓
         ToolRegistry 按顺序执行工具
           ↓
         保存 role=tool + tool_call_id 的 Observation
           ↓
         携带完整历史再次请求 GLM
```

每次调用 `Agent.run()` 最多进行 10 轮模型请求。一次响应包含多个 `tool_calls` 时，Agent 会按照返回顺序全部执行并逐一回传。工具结果超过 12,000 字符时会先截断，避免上下文无限增长。

### Lab 03 阶段的工具定义与安全边界

Lab 03 阶段模型可以选择以下三个只读工具：

| 工具 | 必填参数 | 可选参数 |
|---|---|---|
| `list_files` | `path` | `max_depth` |
| `read_file` | `path` | `offset`、`limit` |
| `search_code` | `keyword`、`path` | `regex` |

`ToolRegistry` 会把参数描述为 JSON Schema。所有路径都被解析到程序启动时的项目根目录：指向根目录之外的绝对路径、`..` 路径穿越和符号链接都会被拒绝。`.env`、构建目录及版本控制目录也不会作为普通项目内容返回给模型。

工具失败不会直接终止 Agent，而是转换为错误 Observation 交给模型继续判断。`GlmClient` 还会区分 401 鉴权失败、429 请求受限、5xx 服务端错误和其他 HTTP 错误。

### 配置与运行

在 `agent-cli` 根目录创建 `.env`：

```dotenv
GLM_API_KEY=xxx
```

`.env` 已被 `.gitignore` 和 `.agentignore` 忽略。也可以通过系统环境变量 `GLM_API_KEY` 提供密钥。

构建并启动：

```shell
mvn clean package
java -jar target/agent-cli-0.1.0.jar
```

之后可以使用自然语言与模型交流，需要时模型将自动调用工具。

### GLM Chat Completions 与 Tool Calling 协议

模型的请求与响应结构遵循 GLM Chat Completions 的 Tool Calling 协议：程序通过 `tools` 提供函数名称、描述和参数 JSON Schema；模型可以返回普通 `content`，也可以在 `choices[].message.tool_calls` 中返回工具调用意图。

`function.arguments` 是包含 JSON 的字符串，而不是已经展开的 JSON 对象。程序执行工具后，必须使用同一个调用 ID 作为 `tool_call_id` 回传结果。模型据此把多个 Observation 与原始调用正确配对。相关字段可参考[智谱 AI 开放文档](https://docs.bigmodel.cn/)。

下面以“读取 `pom.xml` 并概括依赖”为例，展示一次较完整的数据交换。为便于阅读，请求中只展开 `read_file`；当前程序每轮会发送五个工具定义。

#### 1. Agent 发送首次请求

```json
{
  "model": "glm-5.3-flash",
  "messages": [
    {
      "role": "system",
      "content": "你是一个 Java 编程助手 Agent，可以读取、修改和验证用户的项目代码……"
    },
    {
      "role": "user",
      "content": "读取 pom.xml 并概括项目依赖"
    }
  ],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "read_file",
        "description": "读取项目内指定 UTF-8 文本文件的内容，可按行分页",
        "parameters": {
          "type": "object",
          "additionalProperties": false,
          "properties": {
            "path": {
              "type": "string",
              "description": "项目根目录内的文件路径",
              "minLength": 1
            },
            "offset": {
              "type": "integer",
              "description": "从第几行开始读取，默认 0",
              "minimum": 0
            },
            "limit": {
              "type": "integer",
              "description": "最多读取多少行，默认读取至文件末尾",
              "minimum": 1
            }
          },
          "required": ["path"]
        }
      }
    }
  ]
}
```

#### 2. GLM 返回 Tool Call

当模型决定先读取文件时，响应大致如下：

```json
{
  "id": "chatcmpl_example",
  "model": "glm-5.3-flash",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": null,
        "tool_calls": [
          {
            "id": "call_read_pom",
            "type": "function",
            "function": {
              "name": "read_file",
              "arguments": "{\"path\":\"pom.xml\"}"
            }
          }
        ]
      },
      "finish_reason": "tool_calls"
    }
  ],
  "usage": {
    "prompt_tokens": 320,
    "completion_tokens": 24,
    "total_tokens": 344
  }
}
```

`GlmClient` 将其中的 `content`、`tool_calls` 和用量解析为 `LLMResponse`。`Agent` 不把 Tool Call 当成最终答案，而是先把这条 assistant 消息完整加入历史。

#### 3. Agent 执行工具并回传 Observation

`ToolRegistry` 执行 `ReadFileTool` 后，Agent 在下一次请求中追加一条 `role=tool` 消息。下面省略了重复发送的 `tools` 数组：

```json
{
  "model": "glm-5.3-flash",
  "messages": [
    {
      "role": "system",
      "content": "你是一个 Java 编程助手 Agent，可以读取、修改和验证用户的项目代码……"
    },
    {
      "role": "user",
      "content": "读取 pom.xml 并概括项目依赖"
    },
    {
      "role": "assistant",
      "content": "",
      "tool_calls": [
        {
          "id": "call_read_pom",
          "type": "function",
          "function": {
            "name": "read_file",
            "arguments": "{\"path\":\"pom.xml\"}"
          }
        }
      ]
    },
    {
      "role": "tool",
      "tool_call_id": "call_read_pom",
      "content": "文件内容:\n<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<project>...</project>"
    }
  ]
}
```

这里的 `tool_call_id` 必须与模型返回的 `id` 完全一致。多轮对话时，先前的 `user`、`assistant` 和 `tool` 消息会按上下文预算继续发送；完整的安全摘要保存在当前会话中，输入 `clear` 会开始一个新的未保存会话。

#### 4. GLM 返回最终回答

工具结果进入上下文后，模型可以返回不含 `tool_calls` 的普通回答：

```json
{
  "id": "chatcmpl_example_2",
  "model": "glm-5.3-flash",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "该项目主要使用 Jackson 处理 JSON、OkHttp 发起 HTTP 请求，并使用 JUnit 5 进行测试。"
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 980,
    "completion_tokens": 42,
    "total_tokens": 1022
  }
}
```

此时 `LLMResponse.hasToolCalls()` 为 `false`，ReAct 循环结束，最终文本由 CLI 输出。

## Lab 04：受控写入、命令执行与安全收尾

### 本次增量

Lab 04 把 Lab 03 的只读分析 Agent 扩展为能够完成编码闭环的 Agent：

- `WriteFileTool`：以 UTF-8 新建或完整覆盖项目内普通文件，限制为 1 MB，并使用同目录临时文件替换；
- `ExecuteCommandTool`：通过参数数组运行 Java、Javac 或受限 Maven 命令，不接受任意 Shell 字符串；
- `SafetyGuard`：检查 Tool Call ID，并在连续第三次出现相同工具及规范化参数时终止本轮；
- `ToolRegistry`：新增 `write_file`、`execute_command`，并在执行前检查严格的 JSON 参数、项目路径和敏感目录；
- `Agent`：在失败、安全拒绝和最大轮数终止时回滚本轮内存消息。

当前五个模型工具如下：

| 工具 | 必填参数 | 可选参数 |
|---|---|---|
| `list_files` | `path` | `max_depth` |
| `read_file` | `path` | `offset`、`limit` |
| `search_code` | `keyword`、`path` | `regex` |
| `write_file` | `path`、`content` | 无 |
| `execute_command` | `program` | `arguments`、`working_directory`、`timeout_seconds` |

### 安全边界

- 文件操作被限制在程序启动时的项目根目录，拒绝路径逃逸和符号链接绕过；
- `.env`、`.env.*`、`.git` 和 `.agent` 不允许被模型文件工具访问或修改；
- 命令程序采用允许列表，Maven 参数另有严格允许列表，超时范围为 1～120 秒；
- 子进程不继承名称中包含 API Key、Token、Secret 或 Password 的环境变量；
- 命令标准输出和标准错误各最多保留 6,000 字节，统一工具 Observation 最多保留 12,000 字符；
- 内存回滚不会撤销已经完成的文件写入或命令副作用。

## Lab 05：聊天记录持久化与会话恢复

### 本次增量

Lab 05 在 ReAct Agent 外增加 `SessionManager`，把一次普通用户输入处理为“Agent 完成 + 历史保存”的提交单元：

- 每个正常完成且成功保存的轮次自动写入 `.agent/history/`；
- 第一轮通过独立的轻量 GLM 请求生成不超过 30 个字符的会话标题，失败时安全降级为本地标题；
- `history`、`sessions`、`load <id>`、`delete <id>` 和 `clear` 均由 CLI 本地处理，不发送给模型；
- 保存前脱敏普通文本，并将工具参数替换为 `{}`、工具结果替换为固定摘要；
- 加载时校验消息角色、序号、轮次数及 Tool Call/Observation 配对，并再次脱敏；
- 每次模型请求最多使用 32,000 的保守上下文预算，始终保留 system，并优先保留最新完整轮次。

历史文件名格式为：

```text
.agent/history/yyyyMMdd-HHmmss-<session-id>.json
```

保存内容保留会话元数据、普通消息、工具名称、调用 ID、顺序和状态，但不保存文件正文、写入内容、命令输出或其他工具原始载荷。例如：

```json
{
  "schema_version": 1,
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "title": "检查并修复项目测试",
  "turn_count": 1,
  "system_prompt_version": 1,
  "messages": [
    {
      "sequence": 2,
      "role": "assistant",
      "content": "",
      "tool_calls": [
        {
          "id": "call_1",
          "type": "function",
          "function": {"name": "read_file", "arguments": "{}"}
        }
      ]
    },
    {
      "sequence": 3,
      "role": "tool",
      "content": "[工具已调用，参数和结果未持久化]",
      "tool_call_id": "call_1",
      "tool_event": {
        "name": "read_file",
        "status": "success",
        "details_omitted": true
      }
    }
  ]
}
```

时间字段和其他普通消息在实际文件中仍会存在；示例仅突出工具载荷摘要规则。

当前版本的系统指令：

```text
history       查看当前会话摘要
sessions      列出全部有效历史会话
load <id>     加载指定会话并继续对话
delete <id>   删除指定会话
clear         开始新的会话，不删除旧记录
exit/quit     退出程序
```

## 当前完整运行流程

```text
用户输入
  -> Main 先识别本地会话命令
  -> SessionManager 保存本轮开始前的 Agent 快照
  -> Agent 把有界 messages + 五个 tools 发送给 GLM
  -> GLM 返回最终文本或 tool_calls
       ├─ tool_calls：SafetyGuard 校验
       │              -> ToolRegistry 执行真实工具
       │              -> 带 tool_call_id 的 Observation 回传 GLM
       │              -> 继续下一轮，最多 10 轮
       └─ 最终文本：Agent 返回 completed=true
  -> SessionManager 脱敏本轮增量并原子保存会话
       ├─ 保存成功：提交当前会话并输出回答
       └─ 保存失败：回滚内存历史，输出回答及未保存警告
```

模型调用、守卫拒绝或最大轮数导致的失败轮次不会进入会话历史。保存失败同样不会提交内存消息。
