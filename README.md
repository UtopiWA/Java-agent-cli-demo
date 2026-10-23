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

命令提示符会显示当前任务：`test-agent[none]>` 表示尚未创建或恢复任务，`test-agent[8f2c1a20]>` 表示后续自然语言输入将作用于该任务。任务标签默认取 UUID 前 8 位；如果前 8 位在当前项目中发生冲突，程序会自动增加位数，直到前缀可以唯一定位任务。该标签可以直接用于 `show`、`resume` 和 `delete`。

`history` 使用相同的唯一前缀规则，并按当前页内容动态对齐 ASCII 列。若手工输入的前缀仍命中多个任务，`show`、`resume` 和 `delete` 都会拒绝执行，列出每个候选的唯一前缀、目标类和最新状态，并给出可直接复制的重试命令；程序不会自动选择其中一个任务。

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
