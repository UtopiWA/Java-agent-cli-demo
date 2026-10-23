package com.agent.task;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** 同一项目只允许一个进程执行会改变状态的任务操作。 */
public final class ProjectLock implements AutoCloseable {
    private final FileChannel channel;
    private FileLock lock;

    private ProjectLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static ProjectLock tryAcquire(Path projectRoot) throws IOException {
        Path directory = projectRoot.toAbsolutePath().normalize().resolve(".agent");
        if (Files.isSymbolicLink(directory)
                || Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(".agent 必须是项目内普通目录，不能是符号链接");
        }
        Files.createDirectories(directory);
        FileChannel channel = FileChannel.open(directory.resolve("agent.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            return new ProjectLock(channel, lock);
        } catch (OverlappingFileLockException e) {
            return new ProjectLock(channel, null);
        } catch (IOException e) {
            channel.close();
            throw e;
        }
    }

    public synchronized boolean acquired() {
        return lock != null && lock.isValid();
    }

    /** 初次获取失败的只读实例可在写操作前再次尝试取得项目锁。 */
    public synchronized boolean tryReacquire() throws IOException {
        if (acquired()) {
            return true;
        }
        try {
            lock = channel.tryLock();
            return acquired();
        } catch (OverlappingFileLockException e) {
            return false;
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (lock != null && lock.isValid()) {
            lock.release();
        }
        channel.close();
    }
}
