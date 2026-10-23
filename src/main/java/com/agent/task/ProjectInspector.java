package com.agent.task;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import com.agent.tool.IgnoreRules;
import com.agent.tool.ExecuteCommandTool;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** 确定性校验 Maven/JUnit 项目并解析唯一目标类和测试文件。 */
public final class ProjectInspector {
    private static final long MAX_SOURCE_BYTES = 2L * 1024 * 1024;
    private static final Pattern FQCN = Pattern.compile(
            "[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*"
                    + "(?:\\.[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*)+");
    private static final Pattern PACKAGE = Pattern.compile(
            "(?m)^\\s*package\\s+([\\p{javaJavaIdentifierPart}.]+)\\s*;");

    private final Path projectRoot;
    private static volatile MavenCheck mavenCheck;

    public ProjectInspector(Path projectRoot) {
        try {
            this.projectRoot = projectRoot.toAbsolutePath().normalize().toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("项目根目录不可访问: " + projectRoot, e);
        }
    }

    public TargetDescriptor inspect(String target) throws IOException {
        validateProject();
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("目标类不能为空");
        }

        Path sourceRelative = resolveTarget(target.trim());
        Path source = projectRoot.resolve(sourceRelative).normalize();
        ensureInsideSourceRoot(source);
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(source)) {
            throw new IOException("目标源码不存在或不是普通文件: " + sourceRelative);
        }
        if (Files.size(source) > MAX_SOURCE_BYTES) {
            throw new IOException("目标源码超过 2 MiB");
        }

        String content = StandardCharsets.UTF_8.newDecoder()
                .decode(ByteBuffer.wrap(Files.readAllBytes(source))).toString();
        String simpleName = removeJavaSuffix(source.getFileName().toString());
        Matcher packageMatcher = PACKAGE.matcher(content);
        String packageName = packageMatcher.find() ? packageMatcher.group(1) : "";
        Pattern declaration = Pattern.compile(
                "\\b(?:class|record|enum|interface)\\s+" + Pattern.quote(simpleName) + "\\b");
        if (!declaration.matcher(stripCommentsAndStrings(content)).find()) {
            throw new IOException("源码中未找到与文件名一致的顶级类型: " + simpleName);
        }

        Path expected = Path.of("src/main/java");
        if (!packageName.isEmpty()) {
            expected = expected.resolve(packageName.replace('.', '/'));
        }
        expected = expected.resolve(simpleName + ".java").normalize();
        if (!normalize(expected).equals(normalize(sourceRelative))) {
            throw new IOException("package、类名与源码路径不一致，期望: " + normalize(expected));
        }

        String targetClass = packageName.isEmpty() ? simpleName : packageName + '.' + simpleName;
        String testSimpleName = simpleName + "Test";
        Path testRelative = Path.of("src/test/java");
        if (!packageName.isEmpty()) {
            testRelative = testRelative.resolve(packageName.replace('.', '/'));
        }
        testRelative = testRelative.resolve(testSimpleName + ".java").normalize();
        Path testPath = projectRoot.resolve(testRelative);
        rejectSymlinkPath(testPath);
        if (Files.exists(testPath, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(testPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("推导出的测试路径不是普通文件: " + normalize(testRelative));
        }

        IgnoreRules rules = IgnoreRules.load(projectRoot);
        rejectIgnored(rules, projectRoot.resolve("pom.xml"), false, "pom.xml");
        rejectIgnored(rules, source, false, normalize(sourceRelative));
        rejectIgnored(rules, testPath, false, normalize(testRelative));

        return new TargetDescriptor(
                targetClass,
                normalize(sourceRelative),
                testSimpleName,
                normalize(testRelative),
                packageName,
                Files.exists(projectRoot.resolve(testRelative), LinkOption.NOFOLLOW_LINKS));
    }

    public void validateProject() throws IOException {
        if (Runtime.version().feature() < 17) {
            throw new IOException("需要 Java 17 或更高版本");
        }
        Path pom = projectRoot.resolve("pom.xml");
        if (!Files.isRegularFile(pom, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(pom)) {
            throw new IOException("当前目录不是受支持的 Maven 项目：缺少 pom.xml");
        }
        if (!Files.isDirectory(projectRoot.resolve("src/main/java"), LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(projectRoot.resolve("src/test/java"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("项目必须使用 src/main/java 和 src/test/java 标准目录");
        }
        if (!hasJUnitJupiter(pom)) {
            throw new IOException("pom.xml 未声明 test 作用域的 JUnit Jupiter 依赖");
        }
        verifyMaven();
    }

    private Path resolveTarget(String target) {
        if (target.endsWith(".java") || target.contains("/") || target.contains("\\")) {
            Path path = Path.of(target);
            if (path.isAbsolute() || target.contains("*") || target.contains("?")) {
                throw new IllegalArgumentException("目标源码必须是项目内相对 Java 路径");
            }
            for (Path part : path) {
                if (part.toString().equals("..")) {
                    throw new IllegalArgumentException("目标路径不能包含 ..");
                }
            }
            if (!target.endsWith(".java")) {
                throw new IllegalArgumentException("目标源码路径必须以 .java 结尾");
            }
            return path.normalize();
        }
        if (!FQCN.matcher(target).matches()) {
            throw new IllegalArgumentException("全限定类名格式无效: " + target);
        }
        return Path.of("src/main/java").resolve(target.replace('.', '/') + ".java");
    }

    private void ensureInsideSourceRoot(Path source) throws IOException {
        Path sourceRoot = projectRoot.resolve("src/main/java").toRealPath();
        if (!source.startsWith(sourceRoot)) {
            throw new SecurityException("目标类必须位于 src/main/java");
        }
        Path real = source.toRealPath();
        if (!real.startsWith(sourceRoot)) {
            throw new SecurityException("目标源码通过符号链接逃逸");
        }
    }

    private static boolean hasJUnitJupiter(Path pom) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setExpandEntityReferences(false);
            Element root = factory.newDocumentBuilder().parse(pom.toFile()).getDocumentElement();
            NodeList dependencies = root.getElementsByTagName("dependency");
            for (int index = 0; index < dependencies.getLength(); index++) {
                Element dependency = (Element) dependencies.item(index);
                String artifact = childText(dependency, "artifactId");
                String scope = childText(dependency, "scope");
                if (artifact.startsWith("junit-jupiter") && "test".equals(scope)) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("无法安全解析 pom.xml: " + e.getMessage(), e);
        }
    }

    private void verifyMaven() throws IOException {
        MavenCheck cached = mavenCheck;
        if (cached == null) {
            synchronized (ProjectInspector.class) {
                cached = mavenCheck;
                if (cached == null) {
                    String program = System.getProperty("os.name", "")
                            .toLowerCase(Locale.ROOT).contains("win") ? "mvn.cmd" : "mvn";
                    try {
                        ExecuteCommandTool.CommandResult result = new ExecuteCommandTool().execute(
                                projectRoot, program, List.of("--version"), Duration.ofSeconds(15));
                        String text = result.standardOutput() + '\n' + result.standardError();
                        Matcher matcher = Pattern.compile("Apache Maven\\s+(\\d+)\\.(\\d+)")
                                .matcher(text);
                        if (result.timedOut() || result.exitCode() != 0 || !matcher.find()) {
                            cached = new MavenCheck(false, "无法执行 mvn --version");
                        } else {
                            int major = Integer.parseInt(matcher.group(1));
                            int minor = Integer.parseInt(matcher.group(2));
                            cached = major > 3 || major == 3 && minor >= 9
                                    ? new MavenCheck(true, "")
                                    : new MavenCheck(false, "需要 Maven 3.9+，当前=" + major + '.' + minor);
                        }
                    } catch (Exception e) {
                        cached = new MavenCheck(false, "Maven 不可用: " + e.getMessage());
                    }
                    mavenCheck = cached;
                }
            }
        }
        if (!cached.available()) {
            throw new IOException(cached.error());
        }
    }

    private static String childText(Element parent, String name) {
        NodeList nodes = parent.getElementsByTagName(name);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }

    private void rejectSymlinkPath(Path path) throws IOException {
        Path current = projectRoot;
        for (Path part : projectRoot.relativize(path.toAbsolutePath().normalize())) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new IOException("测试路径包含符号链接: " + normalize(projectRoot.relativize(current)));
            }
        }
    }

    private static void rejectIgnored(
            IgnoreRules rules, Path path, boolean directory, String display) throws IOException {
        Path root = path.toAbsolutePath().normalize();
        while (root.getParent() != null && !Files.exists(root.resolve("pom.xml"))) {
            root = root.getParent();
        }
        if (rules.isIgnored(root, path.toAbsolutePath().normalize(), directory)) {
            throw new IOException("任务关键路径被 .agentignore 排除: " + display);
        }
    }

    private static String stripCommentsAndStrings(String source) {
        StringBuilder result = new StringBuilder(source.length());
        boolean lineComment = false;
        boolean blockComment = false;
        boolean string = false;
        boolean character = false;
        boolean escaped = false;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
            if (lineComment) {
                if (current == '\n') {
                    lineComment = false;
                    result.append('\n');
                } else {
                    result.append(' ');
                }
            } else if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false;
                    result.append("  ");
                    index++;
                } else {
                    result.append(current == '\n' ? '\n' : ' ');
                }
            } else if (string || character) {
                result.append(current == '\n' ? '\n' : ' ');
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if ((string && current == '"') || (character && current == '\'')) {
                    string = false;
                    character = false;
                }
            } else if (current == '/' && next == '/') {
                lineComment = true;
                result.append("  ");
                index++;
            } else if (current == '/' && next == '*') {
                blockComment = true;
                result.append("  ");
                index++;
            } else if (current == '"') {
                string = true;
                result.append(' ');
            } else if (current == '\'') {
                character = true;
                result.append(' ');
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }

    private static String removeJavaSuffix(String name) {
        return name.substring(0, name.length() - ".java".length());
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    public record TargetDescriptor(
            String targetClass,
            String targetSource,
            String testClass,
            String testFile,
            String packageName,
            boolean testExists) {
        public String qualifiedTestClass() {
            return packageName.isEmpty() ? testClass : packageName + '.' + testClass;
        }
    }

    private record MavenCheck(boolean available, String error) {
    }
}
