# Java AI Coding Agent CLI

这是一个按实验逐步演进的 Java 17 命令行项目，最终目标是构建能够通过 LLM 和 Tool 完成编码任务的 AI Coding Agent。

当前项目进度：

```text
Lab 01：Maven Project + Java CLI + Test + Git
Lab 02：ListFilesTool + ReadFileTool + SearchCodeTool
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
