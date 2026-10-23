package com.agent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.agent.llm.GlmClient;
import com.agent.llm.LLMClient;
import com.agent.task.ProjectLock;
import com.agent.task.TaskManager;

/** Java 单元测试生成 Agent CLI 的应用入口。 */
public class Main {
    public static void main(String[] args) throws IOException {
        if (args.length > 0) {
            throw new IllegalArgumentException("不支持命令行参数，请在 CLI 中输入任务命令");
        }
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintWriter writer = new PrintWriter(System.out, true, StandardCharsets.UTF_8);

        Path projectRoot = Path.of(".").toAbsolutePath().normalize();
        String apiKey = loadApiKey(projectRoot.resolve(".env"), System.getenv());
        LLMClient client = apiKey == null || apiKey.isBlank() ? null : new GlmClient(apiKey);
        try (ProjectLock lock = ProjectLock.tryAcquire(projectRoot)) {
            new TestAgentCli(new TaskManager(projectRoot), client, writer, lock)
                    .run(reader);
        }
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
}
