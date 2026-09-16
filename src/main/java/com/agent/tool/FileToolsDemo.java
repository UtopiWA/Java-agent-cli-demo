package com.agent.tool;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/** 命令行演示入口，串联验证三个相互独立的文件 Tool。 */
public class FileToolsDemo {
    /**
     * 参数依次为待读取文件、搜索表达式和可选的 --regex 标志。
     */
    public static void main(String[] args) throws Exception {
        Path root = Path.of(".");
        ListFilesTool lister = new ListFilesTool();
        ReadFileTool reader = new ReadFileTool();
        SearchCodeTool searcher = new SearchCodeTool();

        // 先展示扁平列表和树形结构，验证递归遍历及忽略规则。
        System.out.println("== list_files ==");
        List<String> files = lister.listFiles(root);
        files.stream().limit(20).forEach(System.out::println);

        System.out.println("== tree ==");
        System.out.println(lister.tree(root));

        if (args.length >= 1) {
            // 读取参数指定的文件；省略参数时仍可单独演示列举和搜索。
            System.out.println("== read_file " + args[0] + " ==");
            System.out.println(reader.readFile(Path.of(args[0])));
        }

        String keyword = args.length >= 2 ? args[1] : "TODO";
        boolean regex = args.length >= 3 && "--regex".equals(args[2]);
        // 同一演示入口可在字面量搜索和正则搜索之间切换。
        System.out.println("== search_code " + keyword + " ==");
        List<SearchCodeTool.Match> matches = regex
                ? searcher.searchCode(Pattern.compile(keyword), root)
                : searcher.searchCode(keyword, root);
        matches.forEach(match -> System.out.println(
                match.file() + ":" + match.line() + ": " + match.content()));

        // 展示未来统一为 String 返回值时的一种 JSON 序列化方案。
        System.out.println("== serialized_matches ==");
        System.out.println(ToolResultSerializer.serializeMatches(matches));
    }
}
