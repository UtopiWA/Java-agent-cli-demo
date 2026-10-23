package com.agent.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** 读取 UTF-8 文本文件，并支持按行分页。 */
public class ReadFileTool {
    public static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    public static final int MAX_PAGE_CHARS = 12_000;

    public record ReadPage(
            String status,
            String content,
            int totalLines,
            int startLine,
            int endLine,
            boolean hasMore,
            Integer nextOffset,
            String fingerprint,
            Integer oversizedLine,
            Integer oversizedLineLength) {
    }

    /** 按行号输出有界页面，并提供继续读取所需的稳定游标。 */
    public ReadPage readPage(Path path, int offset, int limit) throws IOException {
        validateFile(path);
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        long size = Files.size(path);
        if (size > MAX_FILE_BYTES) {
            throw new IOException("文件超过 2 MiB 读取上限: " + size + " bytes");
        }

        byte[] bytes = Files.readAllBytes(path);
        if (!isProbablyText(bytes)) {
            throw new IOException("文件不是可读取的 UTF-8 文本或疑似二进制文件");
        }
        String text = StandardCharsets.UTF_8.newDecoder()
                .decode(ByteBuffer.wrap(bytes)).toString();
        List<String> lines = text.lines().toList();
        String fingerprint = sha256(bytes);
        if (offset >= lines.size()) {
            return new ReadPage("OK", "", lines.size(), 0, 0,
                    false, null, fingerprint, null, null);
        }

        StringBuilder content = new StringBuilder();
        int index = offset;
        int returned = 0;
        while (index < lines.size() && returned < limit) {
            String rendered = (index + 1) + " | " + lines.get(index);
            int extraChars = rendered.length()
                    + (content.isEmpty() ? 0 : System.lineSeparator().length());
            if (rendered.length() > MAX_PAGE_CHARS && content.isEmpty()) {
                int nextOffset = index + 1;
                return new ReadPage(
                        "LINE_TOO_LONG", "", lines.size(), 0, 0,
                        nextOffset < lines.size(),
                        nextOffset < lines.size() ? nextOffset : null,
                        fingerprint, index + 1, lines.get(index).length());
            }
            if (content.length() + extraChars > MAX_PAGE_CHARS) {
                break;
            }
            if (!content.isEmpty()) {
                content.append(System.lineSeparator());
            }
            content.append(rendered);
            index++;
            returned++;
        }

        boolean hasMore = index < lines.size();
        return new ReadPage(
                "OK", content.toString(), lines.size(), offset + 1,
                offset + returned, hasMore, hasMore ? index : null,
                fingerprint, null, null);
    }

    private static void validateFile(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new NoSuchFileException("Not a regular file or does not exist: " + path);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }

    private static boolean isProbablyText(byte[] bytes) {
        int sample = Math.min(bytes.length, 8_192);
        int controls = 0;
        for (int index = 0; index < sample; index++) {
            int value = Byte.toUnsignedInt(bytes[index]);
            if (value == 0) {
                return false;
            }
            if (value < 0x20 && value != '\n' && value != '\r' && value != '\t' && value != '\f') {
                controls++;
            }
        }
        return sample == 0 || controls * 10 <= sample * 3;
    }
}
