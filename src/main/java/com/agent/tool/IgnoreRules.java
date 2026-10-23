package com.agent.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 读取并匹配项目根目录中的 .agentignore 规则。 */
public final class IgnoreRules {
    private static final String CONFIG_FILE = ".agentignore";

    private final List<Rule> rules;

    private IgnoreRules(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    /**
     * 加载项目级忽略配置；配置不存在时使用空规则。
     */
    public static IgnoreRules load(Path root) throws IOException {
        Path config = root.resolve(CONFIG_FILE);
        if (!Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS)) {
            return new IgnoreRules(List.of());
        }

        List<Rule> rules = new ArrayList<>();
        List<String> lines = Files.readAllLines(config, StandardCharsets.UTF_8);
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (index == 0 && line.startsWith("\uFEFF")) {
                line = line.substring(1);
            }
            String value = line.trim();
            if (!value.isEmpty() && !value.startsWith("#")) {
                rules.add(Rule.parse(value));
            }
        }
        return new IgnoreRules(rules);
    }

    /** 判断给定路径或其任一父目录是否命中忽略规则。 */
    public boolean isIgnored(Path root, Path path, boolean directory) {
        String relative = normalize(root.relativize(path));
        return rules.stream().anyMatch(rule -> rule.matches(relative, directory));
    }

    /** 同时应用项目级忽略配置和工具不可访问的固定目录。 */
    public boolean isExcluded(Path projectRoot, Path path, boolean directory) {
        return isAlwaysExcluded(projectRoot, path) || isIgnored(projectRoot, path, directory);
    }

    /** 敏感配置、内部状态和构建产物不进入模型上下文。 */
    public static boolean isAlwaysExcluded(Path projectRoot, Path path) {
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(normalizedRoot)) {
            return true;
        }

        for (Path part : normalizedRoot.relativize(normalizedPath)) {
            String name = part.toString().toLowerCase(Locale.ROOT);
            if (name.equals(".git") || name.equals(".agent") || name.equals("target")
                    || name.equals(".env") || name.startsWith(".env.")
                    || name.equals("credentials") || name.equals("credentials.json")
                    || name.equals("id_rsa") || name.endsWith(".pem")
                    || name.endsWith(".p12") || name.endsWith(".jks")) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    /** 三个参数分别表示：转换后的正则表达式、规则是否以'/'结尾、规则中间是否包含'/' */
    private record Rule(Pattern pattern, boolean directoryOnly, boolean pathPattern) {
        /** 将类似 .gitignore 的简化 glob 规则预编译成正则表达式。 */
        private static Rule parse(String value) {
            boolean directoryOnly = value.endsWith("/");
            String normalized = value.replace('\\', '/');
            if (directoryOnly) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            if (normalized.startsWith("/")) {
                normalized = normalized.substring(1);
            }
            boolean pathPattern = normalized.contains("/");
            return new Rule(Pattern.compile(globToRegex(normalized)), directoryOnly, pathPattern);
        }

        private boolean matches(String relative, boolean directory) {
            if (pathPattern) {
                // 逐级检查路径前缀
                String[] prefixes = relative.split("/");
                StringBuilder candidate = new StringBuilder();
                for (int i = 0; i < prefixes.length; i++) {
                    if (i > 0) {
                        candidate.append('/');
                    }
                    candidate.append(prefixes[i]);
                    if (pattern.matcher(candidate).matches()
                            && (!directoryOnly || directory || i < prefixes.length - 1)) {
                        return true;
                    }
                }
                return false;
            }

            // 不含斜杠的规则可匹配任意层级中的单个路径片段
            String[] segments = relative.split("/");
            for (int i = 0; i < segments.length; i++) {
                if (pattern.matcher(segments[i]).matches()
                        && (!directoryOnly || directory || i < segments.length - 1)) {
                    return true;
                }
            }
            return false;
        }

        /** 支持 *、** 和 ? 三种常用 glob 通配符。 */
        private static String globToRegex(String glob) {
            StringBuilder regex = new StringBuilder("^");
            for (int i = 0; i < glob.length(); i++) {
                char current = glob.charAt(i);
                switch (current) {
                    case '*' -> {
                        if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') { // ** 转为 .*
                            regex.append(".*");
                            i++;
                        } else { // * 转为 [^/]*
                            regex.append("[^/]*");
                        }
                    }
                    case '?' -> regex.append("[^/]");
                    default -> {
                        if (".()[]{}+$^|".indexOf(current) >= 0) { // 对 . ( ) [ ] { } + $ ^ | 进行转义
                            regex.append('\\');
                        }   regex.append(current);
                    }
                }
            }
            return regex.append('$').toString();
        }
    }
}
