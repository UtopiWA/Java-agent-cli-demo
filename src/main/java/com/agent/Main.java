package com.agent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.agent.history.ChatHistoryStore;
import com.agent.history.HistoryContextBuilder;
import com.agent.history.HistorySanitizer;
import com.agent.history.SessionManager;
import com.agent.history.TitleGenerator;
import com.agent.llm.GlmClient;
import com.agent.tool.ToolRegistry;

/** 应用入口：无参数时启动 Lab03 Agent CLI，--upper 保留 Lab01 演示模式。 */
public class Main {
    public static void main(String[] args) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintWriter writer = new PrintWriter(System.out, true, StandardCharsets.UTF_8);

        if (args.length > 0) {
            run(reader, writer, parseUpperOption(args));
            return;
        }

        String apiKey = loadApiKey(Path.of(".env"), System.getenv());
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("错误：未找到 GLM_API_KEY");
            System.err.println("请在 .env 或环境变量中设置 GLM_API_KEY");
            return;
        }

        GlmClient client = new GlmClient(apiKey);
        HistorySanitizer sanitizer = new HistorySanitizer();
        HistoryContextBuilder contextBuilder = new HistoryContextBuilder();
        Agent agent = new Agent(client, new ToolRegistry(), contextBuilder);
        SessionManager sessions = new SessionManager(
                agent,
                new ChatHistoryStore(Path.of(".")),
                sanitizer,
                contextBuilder,
                new TitleGenerator(client, sanitizer));
        runAgentCli(reader, writer, sessions);
    }

    /** 保留 Lab01 的逐行输入模式，供原有测试与 --upper 参数继续使用。 */
    static void run(BufferedReader reader, PrintWriter writer, boolean upper) throws IOException {
        Agent agent = new Agent();
        String line;
        while ((line = reader.readLine()) != null) {
            if ("quit".equalsIgnoreCase(line.trim())) { // quit 退出
                return;
            }
            writer.println(agent.respond(line, upper));
        }
    }

    /** 运行 Lab03 多轮交互，处理 clear、exit 和 quit 三个本地命令。 */
    static void runAgentCli(
            BufferedReader reader, PrintWriter writer, Agent agent) throws IOException {
        writer.println("输入问题；clear 清空历史；exit/quit 退出。");
        writer.println();

        while (true) {
            writer.print("你：");
            writer.flush();
            String line = reader.readLine();
            if (line == null) {
                break;
            }

            String input = line.trim();
            if (input.isEmpty()) {
                continue;
            }
            if ("exit".equalsIgnoreCase(input) || "quit".equalsIgnoreCase(input)) {
                break;
            }
            if ("clear".equalsIgnoreCase(input)) {
                agent.clearHistory();
                writer.println("历史已清空。");
                writer.println();
                continue;
            }

            writer.println();
            writer.println("Agent：" + agent.run(input));
            writer.println();
        }
        writer.println("再见！");
    }

    /** 运行带持久化会话管理的 CLI，并在本地拦截历史相关命令。 */
    static void runAgentCli(
            BufferedReader reader, PrintWriter writer, SessionManager sessions) throws IOException {
        writer.println("输入问题；history 查看当前会话；sessions 列出历史；"
                + "load <id> 加载；delete <id> 删除；clear 新建会话；exit/quit 退出。");
        writer.println();

        while (true) {
            writer.print("你：");
            writer.flush();
            String line = reader.readLine();
            if (line == null) {
                break;
            }
            String input = line.trim();
            if (input.isEmpty()) {
                continue;
            }
            if ("exit".equalsIgnoreCase(input) || "quit".equalsIgnoreCase(input)) {
                break;
            }
            if ("clear".equalsIgnoreCase(input)) {
                sessions.clear();
                writer.println("已开始新会话。");
                writer.println();
                continue;
            }
            if ("history".equalsIgnoreCase(input)) {
                writer.println(sessions.history());
                writer.println();
                continue;
            }
            if ("sessions".equalsIgnoreCase(input)) {
                writer.println(sessions.sessions());
                writer.println();
                continue;
            }
            if (input.regionMatches(true, 0, "load ", 0, 5)) {
                writer.println(sessions.load(input.substring(5).trim()));
                writer.println();
                continue;
            }
            if ("load".equalsIgnoreCase(input)) {
                writer.println("用法: load <id>");
                writer.println();
                continue;
            }
            if (input.regionMatches(true, 0, "delete ", 0, 7)) {
                writer.println(sessions.delete(input.substring(7).trim()));
                writer.println();
                continue;
            }
            if ("delete".equalsIgnoreCase(input)) {
                writer.println("用法: delete <id>");
                writer.println();
                continue;
            }

            writer.println();
            writer.println("Agent：" + sessions.runTurn(input));
            writer.println();
        }
        writer.println("再见！");
    }

    /** 从 .env 读取密钥；若为空则会到进程环境变量中寻找。 */
    static String loadApiKey(Path envFile, Map<String, String> environment) {
        if (Files.isRegularFile(envFile)) {
            try {
                for (String line : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
                    String value = line.replace("\uFEFF", "").trim();
                    if (value.startsWith("GLM_API_KEY=")) {
                        String fileKey = value.substring("GLM_API_KEY=".length()).trim();
                        if (!fileKey.isBlank()) {
                            return fileKey;
                        }
                        break;
                    }
                }
            } catch (IOException e) {
                System.err.println("读取 .env 失败: " + e.getMessage());
            }
        }

        String environmentKey = environment.get("GLM_API_KEY");
        return environmentKey == null ? null : environmentKey.trim();
    }

    private static boolean parseUpperOption(String[] args) {
        boolean upper = false;
        for (String arg : args) {
            if ("--upper".equals(arg)) {
                upper = true;
            } else {
                throw new IllegalArgumentException("Unknown option: " + arg);
            }
        }
        return upper;
    }
}
