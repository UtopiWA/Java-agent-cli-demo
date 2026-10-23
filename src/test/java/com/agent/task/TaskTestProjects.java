package com.agent.task;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import com.agent.task.TaskModels.RoundType;
import com.agent.tool.ToolRegistry;

public final class TaskTestProjects {
    private TaskTestProjects() {
    }

    public static void calculator(Path root) throws IOException {
        Files.createDirectories(root.resolve("src/main/java/fixture/calculator"));
        Files.createDirectories(root.resolve("src/test/java/fixture/calculator"));
        Files.writeString(root.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>fixture</groupId><artifactId>calculator</artifactId><version>1.0</version>
                  <properties><maven.compiler.release>17</maven.compiler.release>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                  <dependencies><dependency><groupId>org.junit.jupiter</groupId>
                    <artifactId>junit-jupiter</artifactId><version>5.10.2</version>
                    <scope>test</scope></dependency></dependencies>
                  <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId><version>3.2.5</version>
                  </plugin></plugins></build>
                </project>
                """);
        Files.writeString(root.resolve("src/main/java/fixture/calculator/Calculator.java"), """
                package fixture.calculator;

                public class Calculator {
                    public int add(int left, int right) { return left + right; }
                    public int divide(int value, int divisor) {
                        if (divisor == 0) throw new IllegalArgumentException("zero");
                        return value / divisor;
                    }
                }
                """);
    }

    public static String validTest() {
        return """
                package fixture.calculator;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.junit.jupiter.api.Assertions.assertThrows;

                class CalculatorTest {
                    @Test
                    void addsTwoNumbers() {
                        assertEquals(5, new Calculator().add(2, 3));
                    }

                    @Test
                    void rejectsZeroDivisor() {
                        assertThrows(IllegalArgumentException.class,
                                () -> new Calculator().divide(4, 0));
                    }
                }
                """;
    }

    /** 为工具协议测试建立一个真实的分析轮次，避免绕过领域任务边界。 */
    public static ToolRegistry toolRegistry(Path root) throws Exception {
        calculator(root);
        TaskManager manager = new TaskManager(root);
        var created = manager.create("fixture.calculator.Calculator", false, true);
        var round = manager.startRound(created.task(), RoundType.ANALYSIS, "工具协议测试");
        var task = manager.store().load(created.task().id());
        var context = new TaskExecutionContext(
                manager.store(), task, round,
                manager.inspector().inspect(task.targetSource()),
                new PrintWriter(new StringWriter(), true));
        return new ToolRegistry(context);
    }
}
