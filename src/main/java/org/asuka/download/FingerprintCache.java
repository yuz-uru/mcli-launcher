package org.asuka.download;

import lombok.extern.slf4j.Slf4j;
import org.asuka.download.model.DownloadIndex;
import org.asuka.files.AppPaths;
import org.asuka.utils.JsonUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 下载校验指纹缓存（data/download-index.json）：记录已校验通过的 (path, size, mtime, sha1)，
 * isValid 命中即跳过全量哈希重算 —— 上千文件的整合包重复安装场景不必把整库重读一遍。
 * 文件被替换 / 修改会因 size 或 mtime 变化自动失效，只是退化为重新计算哈希。
 */
@Slf4j
public final class FingerprintCache {

	private static final Map<String, DownloadIndex.Entry> cache = new ConcurrentHashMap<>();
	private static final AtomicBoolean initialized = new AtomicBoolean();
	private static final AtomicBoolean modified = new AtomicBoolean();
	/** 超过此规模才做一次失效条目清理，避免每次落盘都 stat 全表 */
	private static final int PRUNE_THRESHOLD = 50_000;

	private FingerprintCache() {
	}

	/** 指纹命中（路径 + 大小 + mtime + sha1 全一致）时返回 true，可跳过哈希计算 */
	public static boolean matches(Path file, String sha1, long size, long mtimeMs) {
		if(sha1 == null || sha1.isBlank()) {
			return false;
		}
		loadOnce();
		DownloadIndex.Entry e = cache.get(key(file));
		return e != null && e.size == size && e.mtimeMs == mtimeMs && sha1.equalsIgnoreCase(e.sha1);
	}

	/** 记录一个已校验通过的文件 */
	public static void record(Path file, String sha1, long size, long mtimeMs) {
		if(sha1 == null || sha1.isBlank()) {
			return;
		}
		loadOnce();
		cache.put(key(file), new DownloadIndex.Entry(size, mtimeMs, sha1.toLowerCase(Locale.ROOT)));
		modified.set(true);
	}

	/** 落盘（仅在脏时写）；由下载批次结束时调用一次 */
	public static synchronized void saveIfDirty() {
		if(!modified.compareAndSet(true, false)) {
			return;
		}
		if(cache.size() > PRUNE_THRESHOLD) {
			pruneMissing();
		}
		try {
			Path file = AppPaths.downloadIndex();
			Files.createDirectories(file.getParent());
			DownloadIndex index = new DownloadIndex();
			index.version = 1;
			index.entries = new HashMap<>(cache);
			Files.writeString(file, JsonUtil.gson().toJson(index), StandardCharsets.UTF_8);
			log.debug("校验指纹已落盘: {}（{} 条）", file, index.entries.size());
		} catch(IOException e) {
			log.warn("校验指纹落盘失败（忽略，下次重算）: {}", e.getMessage());
		}
	}

	/** 测试用：清空内存缓存并允许重新加载 */
	public static void reset() {
		cache.clear();
		initialized.set(false);
		modified.set(false);
	}

	private static void loadOnce() {
		if(!initialized.compareAndSet(false, true)) {
			return;
		}
		Path file = AppPaths.downloadIndex();
		if(!Files.isRegularFile(file)) {
			return;
		}
		try {
			DownloadIndex index = JsonUtil.gson()
			                            .fromJson(Files.readString(file, StandardCharsets.UTF_8), DownloadIndex.class);
			if(index != null && index.entries != null) {
				cache.putAll(index.entries);
			}
			log.debug("校验指纹缓存已加载: {} 条", cache.size());
		} catch(Exception e) {
			log.warn("校验指纹缓存损坏，忽略（将重新计算）: {}", e.getMessage());
		}
	}

	private static void pruneMissing() {
		cache.keySet()
	     .removeIf(k -> !Files.isRegularFile(Path.of(k)));
	}

	private static String key(Path file) {
		return file.toAbsolutePath()
		           .normalize()
		           .toString();
	}
}
