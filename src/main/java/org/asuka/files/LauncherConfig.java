package org.asuka.files;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.configuration2.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * launcher.toml（与 jar 同级，用户可编辑）配置模型：运行时与打包内置的 download.toml 合并，外部优先。
 * 键位：launcher.gameDir / javaPath / xmx / threads / source / allowMultiplayer；account.offlineName / cache。
 * 缺失的键一律返回 null，由调用方决定默认值（避免默认值写死两遍）。
 */
@Slf4j
public final class LauncherConfig {

	/** 离线占位账号名默认值 */
	public static final String DEFAULT_OFFLINE_NAME = "Player";

	private static LauncherConfig instance;

	private final String gameDir;
	private final String javaPath;
	private final String xmx;
	private final Integer threads;
	private final String source;
	private final Boolean allowMultiplayer;
	private final String offlineName;
	private final String accountCache;

	private LauncherConfig(Configuration c) {
		this.gameDir = getString(c, "launcher.gameDir", null);
		this.javaPath = getString(c, "launcher.javaPath", null);
		this.xmx = getString(c, "launcher.xmx", null);
		this.threads = getInteger(c, "launcher.threads");
		this.source = getString(c, "launcher.source", null);
		this.allowMultiplayer = getBoolean(c, "launcher.allowMultiplayer");
		this.offlineName = getString(c, "account.offlineName", DEFAULT_OFFLINE_NAME);
		this.accountCache = getString(c, "account.cache", null);
	}

	public static synchronized LauncherConfig get() {
		if(instance == null) {
			instance = load();
		}
		return instance;
	}

	/** 测试用：丢弃缓存的配置（下次 get() 重新读盘） */
	public static synchronized void reset() {
		instance = null;
	}

	private static LauncherConfig load() {
		Path file = AppPaths.launcherConfig();
		Configuration c = null;
		if(Files.isRegularFile(file)) {
			try {
				c = ConfigLoader.load(file);
			} catch(Exception e) {
				log.warn("读取启动器配置失败（按默认值继续）: {}（{}）", file, e.getMessage());
			}
		} else {
			log.debug("未找到启动器配置 {}，使用默认值", file);
		}
		return new LauncherConfig(c);
	}

	/** 影响全局路径的键（gameDir / account.cache）：启动最早处调用一次 */
	public void applyToPaths() {
		if(gameDir != null) {
			AppPaths.overrideGameDir(Path.of(gameDir));
			log.info("按配置覆盖游戏目录: {}", AppPaths.gameDir().toAbsolutePath());
		}
		if(accountCache != null) {
			AppPaths.overrideSessionCache(Path.of(accountCache));
			log.info("按配置覆盖会话缓存: {}", AppPaths.sessionCache().toAbsolutePath());
		}
	}

	public String gameDir() {
		return gameDir;
	}

	public String javaPath() {
		return javaPath;
	}

	public String xmx() {
		return xmx;
	}

	public Integer threads() {
		return threads;
	}

	public String source() {
		return source;
	}

	public Boolean allowMultiplayer() {
		return allowMultiplayer;
	}

	public String offlineName() {
		return offlineName;
	}

	public String accountCache() {
		return accountCache;
	}

	private static String getString(Configuration c, String key, String def) {
		if(c == null) {
			return def;
		}
		try {
			String v = c.getString(key, null);
			return v == null || v.isBlank() ? def : v.trim();
		} catch(Exception e) {
			log.warn("配置项 {} 取值失败（用默认值）: {}", key, e.getMessage());
			return def;
		}
	}

	private static Integer getInteger(Configuration c, String key) {
		if(c == null) {
			return null;
		}
		try {
			return c.getInteger(key, null);
		} catch(Exception e) {
			log.warn("配置项 {} 不是合法整数（忽略）: {}", key, e.getMessage());
			return null;
		}
	}

	private static Boolean getBoolean(Configuration c, String key) {
		if(c == null) {
			return null;
		}
		try {
			return c.getBoolean(key, null);
		} catch(Exception e) {
			log.warn("配置项 {} 不是合法布尔值（忽略）: {}", key, e.getMessage());
			return null;
		}
	}
}
