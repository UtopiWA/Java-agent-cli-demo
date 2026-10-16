package com.agent.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 以 UTF-8 和原子替换方式新建或覆盖普通文本文件。 */
public final class WriteFileTool {
    static final int MAX_CONTENT_BYTES = 1_000_000;

    public record WriteResult(boolean created, int bytesWritten) {
    }

    /** 写入完整文本；目标父目录不存在时自动创建。 */
    public WriteResult writeFile(Path path, String content) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("path 不能为空");
        }
        if (content == null) {
            throw new IllegalArgumentException("content 不能为空");
        }

        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_CONTENT_BYTES) {
            throw new IllegalArgumentException(
                    "写入内容超过上限（" + MAX_CONTENT_BYTES + " 字节）");
        }

        Path target = path.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(target)) {
            throw new SecurityException("拒绝写入符号链接: " + path);
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("写入目标不是普通文件: " + path);
        }

        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("写入目标缺少父目录: " + path);
        }
        Files.createDirectories(parent);

        boolean created = !Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        Path temporary = Files.createTempFile(parent, ".agent-write-", ".tmp");
        try {
            Files.write(temporary, bytes);
            moveIntoPlace(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return new WriteResult(created, bytes.length);
    }

    /** 优先原子替换；文件系统不支持时退化为同目录普通替换。 */
    private static void moveIntoPlace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
