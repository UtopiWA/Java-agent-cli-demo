package com.agent.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeMap;
import java.util.stream.Stream;

/** 提供递归文件列举、深度限制和树形展示能力 */
public class ListFilesTool {
    public static final int MAX_VISITED_ENTRIES = 10_000;
    public static final int MAX_TREE_CHARS = 8_000;

    public record TreePage(
            String tree,
            int returnedEntries,
            boolean hasMore,
            Integer nextOffset,
            boolean scanLimited,
            int visitedEntries) {
    }

    private record Entry(Path path, boolean directory) {
    }

    private record Pending(Path path, int depth) {
    }

    /**
     * 生成可分页的目录树。offset 只计算真实条目，分页时补出的祖先目录不占游标。
     */
    public TreePage treePage(
            Path projectRoot,
            Path requestedRoot,
            int maxDepth,
            int offset,
            int limit) throws IOException {
        validateRoot(projectRoot);
        validateRoot(requestedRoot);
        validateMaxDepth(maxDepth);
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }

        IgnoreRules ignoreRules = IgnoreRules.load(projectRoot);
        ScanState state = new ScanState();
        List<Entry> entries = new ArrayList<>();
        long requestedEntries = Math.min((long) MAX_VISITED_ENTRIES + 1,
                (long) offset + limit + 1);
        collectEntries(projectRoot, requestedRoot, maxDepth,
                ignoreRules, state, entries, (int) requestedEntries);

        int start = Math.min(offset, entries.size());
        List<Entry> selected = new ArrayList<>();
        for (int index = start; index < entries.size() && selected.size() < limit; index++) {
            selected.add(entries.get(index));
            String candidate = renderPageRoot(projectRoot, requestedRoot, selected);
            if (candidate.length() > MAX_TREE_CHARS) {
                selected.remove(selected.size() - 1);
                break;
            }
        }

        int nextIndex = start + selected.size();
        boolean hasMore = nextIndex < entries.size() || state.limited;
        return new TreePage(
                renderPageRoot(projectRoot, requestedRoot, selected),
                selected.size(),
                hasMore,
                nextIndex < entries.size() ? nextIndex : null,
                state.limited,
                state.visited);
    }

    private static void collectEntries(
            Path projectRoot,
            Path requestedRoot,
            int maxDepth,
            IgnoreRules ignoreRules,
            ScanState state,
            List<Entry> entries,
            int requiredEntries) throws IOException {
        if (maxDepth == 0) {
            return;
        }
        PriorityQueue<Pending> pending = new PriorityQueue<>(Comparator.comparing(
                item -> normalize(projectRoot.relativize(item.path()))));
        addChildren(projectRoot, requestedRoot, 1, ignoreRules, state, pending);
        while (!pending.isEmpty() && entries.size() < requiredEntries) {
            Pending current = pending.remove();
            boolean directory = isDirectory(current.path());
            entries.add(new Entry(requestedRoot.relativize(current.path()), directory));
            if (directory && current.depth() < maxDepth) {
                addChildren(projectRoot, current.path(), current.depth() + 1,
                        ignoreRules, state, pending);
            }
        }
    }

    private static void addChildren(
            Path projectRoot,
            Path directory,
            int depth,
            IgnoreRules ignoreRules,
            ScanState state,
            PriorityQueue<Pending> pending) throws IOException {
        try (Stream<Path> stream = Files.list(directory)) {
            for (Path child : stream.toList()) {
                if (state.visited >= MAX_VISITED_ENTRIES) {
                    state.limited = true;
                    return;
                }
                state.visited++;
                boolean directoryChild = isDirectory(child);
                boolean regularFile = Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS);
                if ((directoryChild || regularFile)
                        && !ignoreRules.isExcluded(projectRoot, child, directoryChild)) {
                    pending.add(new Pending(child, depth));
                }
            }
        }
    }

    private static String renderPageRoot(
            Path projectRoot, Path requestedRoot, List<Entry> entries) {
        DisplayNode root = new DisplayNode("", true);
        for (Entry entry : entries) {
            DisplayNode current = root;
            int index = 0;
            int count = entry.path().getNameCount();
            for (Path part : entry.path()) {
                index++;
                boolean directory = index < count || entry.directory();
                current = current.children.computeIfAbsent(
                        part.toString(), name -> new DisplayNode(name, directory));
                if (directory) {
                    current.directory = true;
                }
            }
        }

        String relativeRoot = normalize(projectRoot.relativize(requestedRoot));
        StringBuilder result = new StringBuilder(relativeRoot.isEmpty() ? "." : relativeRoot)
                .append('/');
        renderDisplayChildren(root, "", result);
        return result.toString();
    }

    private static void renderDisplayChildren(
            DisplayNode parent, String prefix, StringBuilder result) {
        List<DisplayNode> children = new ArrayList<>(parent.children.values());
        for (int index = 0; index < children.size(); index++) {
            DisplayNode child = children.get(index);
            boolean last = index == children.size() - 1;
            result.append(System.lineSeparator())
                    .append(prefix)
                    .append(last ? "└── " : "├── ");

            DisplayNode end = child;
            StringBuilder display = new StringBuilder(child.name);
            while (end.directory && end.children.size() == 1) {
                DisplayNode only = end.children.values().iterator().next();
                if (!only.directory) {
                    break;
                }
                display.append('/').append(only.name);
                end = only;
            }
            result.append(display);
            if (end.directory) {
                result.append('/');
                renderDisplayChildren(end, prefix + (last ? "    " : "│   "), result);
            }
        }
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static final class ScanState {
        private int visited;
        private boolean limited;
    }

    private static final class DisplayNode {
        private final String name;
        private boolean directory;
        private final Map<String, DisplayNode> children = new TreeMap<>();

        private DisplayNode(String name, boolean directory) {
            this.name = name;
            this.directory = directory;
        }
    }

    /** 不跟随符号链接，避免树形递归进入目录环。 */
    private static boolean isDirectory(Path path) {
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
    }

    /** 在遍历前给出明确的“不存在”或“不是目录”错误。 */
    private static void validateRoot(Path root) throws IOException {
        if (!Files.exists(root)) {
            throw new NoSuchFileException(root.toString());
        }
        if (!Files.isDirectory(root)) {
            throw new NotDirectoryException(root.toString());
        }
    }

    private static void validateMaxDepth(int maxDepth) {
        if (maxDepth < 0) {
            throw new IllegalArgumentException("maxDepth must not be negative");
        }
    }
}
