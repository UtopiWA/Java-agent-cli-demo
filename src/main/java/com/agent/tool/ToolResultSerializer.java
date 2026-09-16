package com.agent.tool;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 将三个Tool方法的不同返回类型序列化为 JSON 文本，为后续统一 Tool 接口做准备。
 */
public final class ToolResultSerializer {
    private ToolResultSerializer() {
    }

    /** 将文件路径列表序列化为 JSON 数组。 */
    public static String serializePaths(List<String> paths) {
        return paths.stream()
                .map(ToolResultSerializer::quote)
                .collect(Collectors.joining(",", "[", "]"));
    }

    /** 将文件内容序列化为 JSON 字符串。 */
    public static String serializeText(String text) {
        return quote(text);
    }

    /** 将搜索命中记录序列化为 JSON 对象数组。 */
    public static String serializeMatches(List<SearchCodeTool.Match> matches) {
        return matches.stream()
                .map(match -> "{\"file\":" + quote(match.file())
                        + ",\"line\":" + match.line()
                        + ",\"content\":" + quote(match.content()) + "}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    /** 对引号、反斜杠、换行和其他控制字符执行 JSON 转义。 */
    private static String quote(String value) {
        if (value == null) {
            return "null";
        }

        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            switch (current) {
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (current < 0x20) {
                        result.append(String.format("\\u%04x", (int) current));
                    } else {
                        result.append(current);
                    }
                }
            }
        }
        return result.append('"').toString();
    }
}
