package com.agent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.agent.IntentClassifier.IntentType;
import com.agent.llm.LLMClient;
import com.agent.task.AmbiguousTaskIdException;
import com.agent.task.ProjectInspector.TargetDescriptor;
import com.agent.task.ProjectLock;
import com.agent.task.TaskExecutionContext;
import com.agent.task.TaskManager;
import com.agent.task.TaskModels.Round;
import com.agent.task.TaskModels.RoundType;
import com.agent.task.TaskModels.Task;
import com.agent.tool.ToolRegistry;

/** Java 单元测试 Agent 的命令行命令和自然语言轮次入口。 */
public final class TestAgentCli {
    private final TaskManager tasks;
    private final LLMClient llm;
    private final PrintWriter output;
    private final ProjectLock projectLock;
    private boolean writable;
    private Task activeTask;

    public TestAgentCli(TaskManager tasks, LLMClient llm, PrintWriter output, boolean writable) {
        this(tasks, llm, output, writable, null);
    }

    public TestAgentCli(TaskManager tasks, LLMClient llm, PrintWriter output,
            ProjectLock projectLock) {
        this(tasks, llm, output, projectLock.acquired(), projectLock);
    }

    private TestAgentCli(TaskManager tasks, LLMClient llm, PrintWriter output,
            boolean writable, ProjectLock projectLock) {
        this.tasks = tasks;
        this.llm = llm;
        this.output = output;
        this.writable = writable;
        this.projectLock = projectLock;
    }

    public void run(BufferedReader input) throws IOException {
        output.println("Java 单元测试生成 Agent CLI");
        output.println("输入 help 查看命令。自然语言只作用于当前测试任务。");
        if (!writable) {
            output.println("警告：另一个 CLI 实例持有项目锁；当前只允许只读命令，"
                    + "写命令会自动重试获取锁。");
        }
        output.println();
        while (true) {
            output.print(prompt());
            output.flush();
            String line = input.readLine();
            if (line == null) {
                break;
            }
            String command = line.strip();
            if (command.isEmpty()) {
                continue;
            }
            if (command.equalsIgnoreCase("exit") || command.equalsIgnoreCase("quit")) {
                break;
            }
            output.println(handle(command));
            output.println();
        }
        output.println("再见！");
    }

    public String handle(String input) {
        try {
            List<String> parts = split(input);
            String command = parts.get(0).toLowerCase(Locale.ROOT);
            return switch (command) {
                case "help" -> help();
                case "history" -> history(parts);
                case "show" -> show(parts);
                case "status" -> status();
                case "new" -> requireWritable(() -> create(parts));
                case "resume" -> requireWritable(() -> resume(parts));
                case "rollback" -> requireWritable(() -> rollback(parts));
                case "delete" -> requireWritable(() -> delete(parts));
                default -> requireWritable(() -> runNaturalLanguage(input));
            };
        } catch (AmbiguousTaskIdException e) {
            return e.userMessage(commandName(input));
        } catch (Exception e) {
            return "操作失败: " + safeMessage(e);
        }
    }

    private String create(List<String> parts) throws IOException {
        if (parts.size() < 2) {
            return "用法: new <目标类路径或全限定类名> [--overwrite] [--fresh]";
        }
        boolean overwrite = false;
        boolean fresh = false;
        String target = null;
        for (int index = 1; index < parts.size(); index++) {
            switch (parts.get(index)) {
                case "--overwrite" -> overwrite = true;
                case "--fresh" -> fresh = true;
                default -> {
                    if (parts.get(index).startsWith("--") || target != null) {
                        return "new 参数无效: " + parts.get(index);
                    }
                    target = parts.get(index);
                }
            }
        }
        if (target == null) {
            return "用法: new <目标类路径或全限定类名> [--overwrite] [--fresh]";
        }
        TaskManager.CreateResult result = tasks.create(target, overwrite, fresh);
        if (result.created()) {
            activeTask = result.task();
        }
        return result.message();
    }

    private String resume(List<String> parts) throws IOException {
        if (parts.size() != 2) {
            return "用法: resume <task-id>";
        }
        Task loaded = tasks.load(parts.get(1));
        activeTask = loaded;
        String state = loaded.activeRound() == null
                ? "等待新的维护要求" : "将继续暂停轮次 " + loaded.activeRound();
        return "已恢复任务: " + tasks.displayId(loaded.id())
                + "\n目标类: " + loaded.targetClass()
                + "\n测试文件: " + loaded.testFile()
                + "\n状态: " + state;
    }

    private String delete(List<String> parts) throws IOException {
        if (parts.size() != 2) {
            return "用法: delete <task-id>";
        }
        Task deleting = tasks.store().load(parts.get(1));
        String displayId = tasks.displayId(deleting.id());
        tasks.delete(deleting.id());
        if (activeTask != null && activeTask.id().equals(deleting.id())) {
            activeTask = null;
        }
        return "已删除任务记录: " + displayId
                + "\n项目测试文件未删除。";
    }

    private String rollback(List<String> parts) throws IOException {
        if (activeTask == null) {
            return "当前没有活动任务。请先使用 new <目标类> 或 resume <task-id>。";
        }
        Integer sourceRound = null;
        if (parts.size() == 3 && "--round".equals(parts.get(1))) {
            sourceRound = Integer.parseInt(parts.get(2));
        } else if (parts.size() != 1) {
            return "用法: rollback [--round N]";
        }
        String result = tasks.rollback(activeTask, sourceRound, output);
        activeTask = tasks.store().load(activeTask.id());
        return result;
    }

    private String history(List<String> parts) throws IOException {
        int limit = 20;
        int offset = 0;
        for (int index = 1; index < parts.size(); index += 2) {
            if (index + 1 >= parts.size()) {
                return "用法: history [--limit N] [--offset N]";
            }
            int value = Integer.parseInt(parts.get(index + 1));
            if ("--limit".equals(parts.get(index))) {
                limit = value;
            } else if ("--offset".equals(parts.get(index))) {
                offset = value;
            } else {
                return "history 参数无效: " + parts.get(index);
            }
        }
        return tasks.history(offset, limit);
    }

    private String show(List<String> parts) throws IOException {
        if (parts.size() != 2 && parts.size() != 4) {
            return "用法: show <task-id> [--round N]";
        }
        Integer round = null;
        if (parts.size() == 4) {
            if (!"--round".equals(parts.get(2))) {
                return "用法: show <task-id> [--round N]";
            }
            round = Integer.parseInt(parts.get(3));
        }
        return tasks.show(parts.get(1), round);
    }

    private String status() {
        if (activeTask == null) {
            return accessMode()
                    + "\n当前没有活动任务。使用 new <目标> 创建，或 resume <task-id> 恢复。";
        }
        try {
            activeTask = tasks.store().load(activeTask.id());
            String round = activeTask.activeRound() == null
                    ? "无活动轮次"
                    : "活动轮次=" + activeTask.activeRound();
            String detail = "";
            if (activeTask.activeRound() != null) {
                Round activeRound = tasks.store().loadRound(
                        activeTask.id(), activeTask.activeRound());
                detail = "\n当前阶段: " + activeRound.status()
                        + "\n候选次数: " + activeRound.attemptCount() + "/3"
                        + "\n最近目标测试: " + (activeRound.targetResult() == null
                                ? "未执行" : activeRound.targetResult().status())
                        + "\n最近完整回归: " + (activeRound.regressionResult() == null
                                ? "未执行" : activeRound.regressionResult().status())
                        + "\n文件状态: " + (activeRound.fileState() == null
                                ? "未知" : activeRound.fileState().state());
            }
            return accessMode()
                    + "\n任务 ID: " + tasks.displayId(activeTask.id())
                    + "\n目标类: " + activeTask.targetClass()
                    + "\n测试文件: " + activeTask.testFile()
                    + "\n最新轮次: " + activeTask.latestRoundType() + '/'
                    + activeTask.latestRoundStatus()
                    + "\n" + round
                    + detail
                    + "\n候选次数和验证详情请使用 show " + tasks.displayId(activeTask.id());
        } catch (IOException e) {
            return "读取当前任务状态失败: " + safeMessage(e);
        }
    }

    private String runNaturalLanguage(String request) throws IOException {
        if (activeTask == null) {
            return "当前没有活动任务。请先使用 new <目标类> 或 resume <task-id>。";
        }
        if (llm == null) {
            return "未找到 GLM_API_KEY；本地任务命令可用，但不能启动模型轮次。";
        }

        activeTask = tasks.store().load(activeTask.id());
        IntentClassifier.Result classification = new IntentClassifier(llm).classify(request);
        output.println(classification.terminalLine());
        if (classification.fallback()) {
            output.println("[policy] 意图分类不可用，按 ANALYSIS 低权限轮次处理；"
                    + "不会写文件或执行 Maven。");
        }
        if (classification.type() == IntentType.CONSTRAINT) {
            activeTask = tasks.addPersistentConstraint(activeTask, request);
            return "已将本条要求加入任务持久约束。"
                    + "\n未创建执行轮次，未读取代码、修改文件或执行 Maven。";
        }
        RoundType requestedType = classification.type().roundType();
        Round round = tasks.startRound(activeTask, requestedType, request,
                classification.promptTokens(), classification.completionTokens());
        activeTask = tasks.store().load(activeTask.id());
        TargetDescriptor descriptor = tasks.inspector().inspect(activeTask.targetSource());
        TaskExecutionContext context = new TaskExecutionContext(
                tasks.store(), activeTask, round, descriptor, output);
        ToolRegistry registry = new ToolRegistry(context);
        String result = new TestTaskAgent(llm, registry, context, output).run(request);
        activeTask = tasks.store().load(activeTask.id());
        return result;
    }

    private String requireWritable(Action action) throws Exception {
        if (!writable && projectLock != null) {
            try {
                writable = projectLock.tryReacquire();
            } catch (IOException e) {
                return "当前为只读模式：重新获取项目锁失败: " + safeMessage(e);
            }
            if (writable) {
                output.println("[lock] 已取得项目写锁，已退出只读模式。");
            }
        }
        if (!writable) {
            return "当前为只读模式：另一个 CLI 实例仍持有项目锁。";
        }
        return action.run();
    }

    private String accessMode() {
        return "项目访问模式: " + (writable ? "可写" : "只读");
    }

    private String prompt() {
        if (activeTask == null) {
            return "test-agent[none]> ";
        }
        try {
            return "test-agent[" + tasks.displayId(activeTask.id()) + "]> ";
        } catch (IOException e) {
            return "test-agent[unknown]> ";
        }
    }

    private static List<String> split(String input) {
        String[] values = input.strip().split("\\s+");
        return new ArrayList<>(List.of(values));
    }

    private static String commandName(String input) {
        return input.strip().split("\\s+", 2)[0];
    }

    private static String help() {
        return """
                new <目标> [--overwrite] [--fresh]  创建并激活测试任务
                status                              查看当前任务和轮次状态
                history [--limit N] [--offset N]   分页列出测试任务
                show <task-id> [--round N]         查看任务或指定轮次
                resume <task-id>                   恢复同一目标类的维护任务
                rollback [--round N]               恢复更早的成功测试版本并重新验证
                delete <task-id>                   删除任务记录，不删除测试文件
                help                                显示帮助
                exit / quit                         安全退出

                自然语言轮次示例：
                分析这个类值得测试的边界
                验证当前测试并执行完整回归
                为公开方法生成 JUnit 5 测试

                第一版仅支持 Java 17、单模块 Maven、JUnit 5 和标准源码目录。
                Maven/JUnit 会执行本地项目代码，只应对可信仓库使用。
                """;
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName() : e.getMessage();
    }

    @FunctionalInterface
    private interface Action {
        String run() throws Exception;
    }
}
