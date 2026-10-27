package org.asuka.core;

import lombok.extern.slf4j.Slf4j;
import org.asuka.files.AppPaths;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 同版本实例锁：启动前对 versions/<id>/.lock 取文件独占锁（进程退出由操作系统自动释放），
 * 避免同一版本被并发启动导致 natives 目录与 saves 互踩；占用时报错并提示 --force 逃生口。
 */
@Slf4j
public final class InstanceLock implements AutoCloseable {

	private final FileChannel channel;
	private final FileLock lock;
	private final Path file;

	private InstanceLock(FileChannel channel, FileLock lock, Path file) {
		this.channel = channel;
		this.lock = lock;
		this.file = file;
	}

	/**
	 * 尝试锁定版本目录；已被其它实例占用时抛 IOException。
	 * force=true 表示明确要求强启：不锁定直接返回 null（调用方按无锁处理）。
	 */
	public static InstanceLock acquire(String versionId, boolean force) throws IOException {
		if(force) {
			log.warn("--force：跳过版本实例锁（并发启动同一版本可能损坏存档）");
			return null;
		}
		Path file = AppPaths.versionDir(versionId)
		                     .resolve(".lock");
		Files.createDirectories(file.getParent());
		FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		FileLock lock;
		try {
			lock = channel.tryLock();
		} catch(OverlappingFileLockException e) {
			lock = null; // 同一 JVM 内已持有该文件锁
		}
		if(lock == null) {
			channel.close();
			throw new IOException("版本 " + versionId + " 正在运行中（实例锁 " + file.toAbsolutePath()
			                    + "）；如需强制启动请加 --force");
		}
		log.debug("已获取版本实例锁: {}", file);
		return new InstanceLock(channel, lock, file);
	}

	@Override
	public void close() {
		try {
			if(lock != null && lock.isValid()) {
				lock.release();
			}
		} catch(IOException e) {
			log.debug("释放实例锁失败（忽略）: {}", e.getMessage());
		}
		try {
			channel.close();
		} catch(IOException e) {
			log.debug("关闭锁文件失败（忽略）: {}", e.getMessage());
		}
		log.debug("已释放版本实例锁: {}", file);
	}
}
