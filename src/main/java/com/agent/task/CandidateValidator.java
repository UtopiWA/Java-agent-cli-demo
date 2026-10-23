package com.agent.task;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 在候选落盘前执行保守的 Java/JUnit 结构与危险行为检查。 */
public final class CandidateValidator {
    public static final int MAX_BYTES = 24 * 1024;
    private static final Pattern ANNOTATED_VOID_METHOD = Pattern.compile(
            "(?s)((?:@[\\p{javaJavaIdentifierStart}]"
                    + "[\\p{javaJavaIdentifierPart}.]*(?:\\s*\\([^)]*\\))?\\s*)+)"
                    + "(?:public|protected|private)?\\s*(?:final\\s+)?void\\s+"
                    + "([\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*)\\s*\\(");
    private static final Pattern TEST_ANNOTATION = Pattern.compile(
            "@(?:org\\.junit\\.jupiter\\.api\\.)?Test\\b");
    private static final Pattern UNSUPPORTED_TEST_ANNOTATION = Pattern.compile(
            "@(?:[\\p{javaJavaIdentifierPart}.]+\\.)?"
                    + "(?:ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\\b");
    private static final Pattern ASSERTION = Pattern.compile(
            "\\b(?:assertEquals|assertNotEquals|assertTrue|assertFalse|assertNull|"
                    + "assertNotNull|assertSame|assertNotSame|assertArrayEquals|assertIterableEquals|"
                    + "assertThrows|assertDoesNotThrow|fail)\\s*\\(");
    private static final List<Pattern> DANGEROUS = List.of(
            Pattern.compile("\\bSystem\\s*\\.\\s*exit\\s*\\("),
            Pattern.compile("\\bRuntime\\s*\\.\\s*getRuntime\\s*\\(\\s*\\)\\s*\\.\\s*exec\\s*\\("),
            Pattern.compile("\\bnew\\s+ProcessBuilder\\s*\\("),
            Pattern.compile("\\b(?:java\\.net|Socket|ServerSocket|HttpClient|URL)\\b"),
            Pattern.compile("\\bThread\\s*\\.\\s*sleep\\s*\\("),
            Pattern.compile("\\bFiles\\s*\\.\\s*(?:write|delete|move|copy|create)\\w*\\s*\\("));

    public Validation validate(
            String content,
            String packageName,
            String testClass,
            String targetSimpleName,
            String baselineContent) {
        List<String> errors = new ArrayList<>();
        List<String> dangerous = new ArrayList<>();
        if (content == null) {
            return new Validation(false, false, List.of("候选内容不能为空"), List.of(), List.of(), List.of());
        }
        int bytes = content.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_BYTES) {
            errors.add("候选超过 24 KiB，上限=" + MAX_BYTES + " bytes");
        }

        String code = stripNonCode(content);
        if (!packageName.isEmpty()
                && !Pattern.compile("(?m)^\\s*package\\s+" + Pattern.quote(packageName) + "\\s*;")
                        .matcher(code).find()) {
            errors.add("package 必须是 " + packageName);
        }
        if (packageName.isEmpty() && Pattern.compile("(?m)^\\s*package\\s+").matcher(code).find()) {
            errors.add("默认包目标的测试不应声明 package");
        }
        if (!Pattern.compile("\\bclass\\s+" + Pattern.quote(testClass) + "\\b")
                .matcher(code).find()) {
            errors.add("测试类名必须是 " + testClass);
        }
        if (!content.contains("org.junit.jupiter")) {
            errors.add("必须使用 JUnit Jupiter");
        }
        Set<String> methods = testMethods(code);
        if (UNSUPPORTED_TEST_ANNOTATION.matcher(code).find()) {
            errors.add("第一版只支持普通 JUnit Jupiter @Test 方法，"
                    + "不能使用 @ParameterizedTest、@RepeatedTest、@TestFactory 或 @TestTemplate");
        } else if (methods.isEmpty()) {
            errors.add("至少需要一个普通 JUnit Jupiter @Test 方法");
        }
        if (!Pattern.compile("\\b" + Pattern.quote(targetSimpleName) + "\\b").matcher(code).find()) {
            errors.add("候选未引用目标类 " + targetSimpleName);
        }
        if (!ASSERTION.matcher(code).find()) {
            errors.add("至少需要一个有效 JUnit 断言");
        }
        if (Pattern.compile("@Disabled\\b").matcher(code).find()) {
            errors.add("候选不能包含 @Disabled");
        }
        if (Pattern.compile("assertTrue\\s*\\(\\s*true\\s*[),]").matcher(code).find()
                || Pattern.compile("assertFalse\\s*\\(\\s*false\\s*[),]").matcher(code).find()
                || Pattern.compile("assertEquals\\s*\\(\\s*([^,]+)\\s*,\\s*\\1\\s*[),]")
                        .matcher(code).find()) {
            errors.add("候选包含明显恒真断言");
        }
        for (Pattern pattern : DANGEROUS) {
            if (pattern.matcher(code).find()) {
                dangerous.add("命中危险代码规则: " + pattern.pattern());
            }
        }

        Set<String> removed = new LinkedHashSet<>();
        if (baselineContent != null && !baselineContent.isBlank()) {
            removed.addAll(testMethods(stripNonCode(baselineContent)));
            removed.removeAll(methods);
        }
        return new Validation(errors.isEmpty() && dangerous.isEmpty(), !dangerous.isEmpty(),
                List.copyOf(errors), List.copyOf(dangerous), List.copyOf(methods), List.copyOf(removed));
    }

    static Set<String> testMethods(String code) {
        Set<String> methods = new LinkedHashSet<>();
        Matcher matcher = ANNOTATED_VOID_METHOD.matcher(code);
        while (matcher.find()) {
            if (TEST_ANNOTATION.matcher(matcher.group(1)).find()) {
                methods.add(matcher.group(2));
            }
        }
        return methods;
    }

    /** 保留换行和代码标记，清除注释、字符串、字符字面量与文本块。 */
    static String stripNonCode(String source) {
        StringBuilder result = new StringBuilder(source.length());
        State state = State.CODE;
        boolean escaped = false;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
            char third = index + 2 < source.length() ? source.charAt(index + 2) : '\0';
            switch (state) {
                case CODE -> {
                    if (current == '/' && next == '/') {
                        state = State.LINE_COMMENT;
                        result.append("  ");
                        index++;
                    } else if (current == '/' && next == '*') {
                        state = State.BLOCK_COMMENT;
                        result.append("  ");
                        index++;
                    } else if (current == '"' && next == '"' && third == '"') {
                        state = State.TEXT_BLOCK;
                        result.append("   ");
                        index += 2;
                    } else if (current == '"') {
                        state = State.STRING;
                        escaped = false;
                        result.append(' ');
                    } else if (current == '\'') {
                        state = State.CHARACTER;
                        escaped = false;
                        result.append(' ');
                    } else {
                        result.append(current);
                    }
                }
                case LINE_COMMENT -> {
                    if (current == '\n') {
                        state = State.CODE;
                        result.append('\n');
                    } else {
                        result.append(' ');
                    }
                }
                case BLOCK_COMMENT -> {
                    if (current == '*' && next == '/') {
                        state = State.CODE;
                        result.append("  ");
                        index++;
                    } else {
                        result.append(current == '\n' ? '\n' : ' ');
                    }
                }
                case STRING, CHARACTER -> {
                    result.append(current == '\n' ? '\n' : ' ');
                    if (escaped) {
                        escaped = false;
                    } else if (current == '\\') {
                        escaped = true;
                    } else if ((state == State.STRING && current == '"')
                            || (state == State.CHARACTER && current == '\'')) {
                        state = State.CODE;
                    }
                }
                case TEXT_BLOCK -> {
                    if (current == '"' && next == '"' && third == '"') {
                        state = State.CODE;
                        result.append("   ");
                        index += 2;
                    } else {
                        result.append(current == '\n' ? '\n' : ' ');
                    }
                }
            }
        }
        return result.toString();
    }

    private enum State {
        CODE, LINE_COMMENT, BLOCK_COMMENT, STRING, CHARACTER, TEXT_BLOCK
    }

    public record Validation(
            boolean passed,
            boolean dangerous,
            List<String> errors,
            List<String> dangerousFindings,
            List<String> testMethods,
            List<String> removedMethods) {
        public String summary() {
            if (passed) {
                return "静态校验通过，测试方法=" + testMethods.size()
                        + (removedMethods.isEmpty() ? ""
                                : "，移除已有测试方法=" + String.join(",", removedMethods));
            }
            List<String> all = new ArrayList<>(errors);
            all.addAll(dangerousFindings);
            return String.join("；", all);
        }
    }
}
