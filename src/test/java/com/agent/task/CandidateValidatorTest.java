package com.agent.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CandidateValidatorTest {
    private final CandidateValidator validator = new CandidateValidator();

    @Test
    void shouldAcceptAValidJUnitJupiterCandidate() {
        var result = validator.validate(TaskTestProjects.validTest(),
                "fixture.calculator", "CalculatorTest", "Calculator", null);

        assertTrue(result.passed(), result.summary());
        assertFalse(result.dangerous());
    }

    @Test
    void shouldAcceptDisplayNameBeforeOrAfterTestAnnotation() {
        String source = """
                package fixture.calculator;
                import org.junit.jupiter.api.DisplayName;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class CalculatorTest {
                    @Test
                    @DisplayName("adds values")
                    void addsValues() {
                        assertEquals(3, new Calculator().add(1, 2));
                    }

                    @DisplayName("rejects zero")
                    @Test
                    void rejectsZero() {
                        org.junit.jupiter.api.Assertions.assertThrows(
                                IllegalArgumentException.class,
                                () -> new Calculator().divide(1, 0));
                    }
                }
                """;

        var result = validator.validate(source, "fixture.calculator", "CalculatorTest",
                "Calculator", null);

        assertTrue(result.passed(), result.summary());
        assertTrue(result.testMethods().contains("addsValues"));
        assertTrue(result.testMethods().contains("rejectsZero"));
    }

    @Test
    void shouldExplainUnsupportedJUnitTestKinds() {
        String source = """
                package fixture.calculator;
                import org.junit.jupiter.params.ParameterizedTest;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class CalculatorTest {
                    @ParameterizedTest
                    void addsValues() {
                        assertEquals(3, new Calculator().add(1, 2));
                    }
                }
                """;

        var result = validator.validate(source, "fixture.calculator", "CalculatorTest",
                "Calculator", null);

        assertFalse(result.passed());
        assertTrue(result.summary().contains("第一版只支持普通 JUnit Jupiter @Test"));
    }

    @Test
    void shouldIgnoreDangerousWordsInCommentsButRejectEveryDangerousCodeCategory() {
        String harmless = TaskTestProjects.validTest().replace(
                "class CalculatorTest {", "// System.exit(1)\nclass CalculatorTest {");

        assertTrue(validator.validate(harmless, "fixture.calculator", "CalculatorTest",
                "Calculator", null).passed());

        String[] dangerousStatements = {
                "System.exit(1);",
                "Runtime.getRuntime().exec(\"echo unsafe\");",
                "new ProcessBuilder(\"echo\", \"unsafe\").start();",
                "new java.net.Socket(\"localhost\", 80);",
                "Thread.sleep(1000);",
                "java.nio.file.Files.delete(java.nio.file.Path.of(\"unsafe\"));"
        };
        for (String statement : dangerousStatements) {
            String dangerous = TaskTestProjects.validTest().replace(
                    "assertEquals(5, new Calculator().add(2, 3));",
                    statement + " assertEquals(5, new Calculator().add(2, 3));");
            var rejected = validator.validate(dangerous, "fixture.calculator", "CalculatorTest",
                    "Calculator", null);

            assertFalse(rejected.passed(), statement);
            assertTrue(rejected.dangerous(), statement);
        }
    }

    @Test
    void shouldAllowAndReportRemovedExistingTestMethods() {
        String baseline = TaskTestProjects.validTest();
        String changed = baseline.replaceAll(
                "(?s)\\s*@Test\\s*void rejectsZeroDivisor\\(\\) \\{.*?\\n    \\}", "");

        var result = validator.validate(changed, "fixture.calculator", "CalculatorTest",
                "Calculator", baseline);

        assertTrue(result.passed(), result.summary());
        assertTrue(result.removedMethods().contains("rejectsZeroDivisor"));
        assertTrue(result.summary().contains("移除已有测试方法"));
    }
}
