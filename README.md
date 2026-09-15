# Java AI Coding Agent CLI

Lab 01 的 Java 17 命令行项目。程序逐行读取标准输入并输出响应：

- 输入 `hello`（忽略首尾空白和大小写）时输出 `Hello, Agent!`；
- 输入其他内容时输出 `I heard: <输入>`；
- 输入 `quit`（忽略首尾空白和大小写）时退出；
- 使用 `--upper` 参数时，所有响应转换为大写。

## 环境要求

- JDK 17 或更高版本
- Maven 3.9 或更高版本

## 构建和测试

```shell
mvn clean test
mvn package
```

## 运行

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
