package org.asuka.download;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.configuration2.Configuration;
import org.asuka.files.ConfigLoader;
import org.asuka.files.LauncherConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 下载配置（src/main/resources/download.toml）：并发 / 重试 / 清单缓存 / 镜像源 /
 * 大文件分段参数 / 镜像源自动故障切换（failover）。
 */
@Slf4j
public final class DownloadConfig {

	private static DownloadConfig instance;
	private final int threads;
	private final int retries;
	private final int manifestCacheMinutes;
	private final int bigFileSegments;
	private final long bigFileThresholdBytes;
	private final boolean failover;
	private final Source source;
	private final Source failoverSource;

	private DownloadConfig(
			int threads, int retries, int manifestCacheMinutes, int bigFileSegments,
			long bigFileThresholdBytes, boolean failover, Source source, Source failoverSource
	                      ) {
		this.threads = threads;
		this.retries = retries;
		this.manifestCacheMinutes = manifestCacheMinutes;
		this.bigFileSegments = bigFileSegments;
		this.bigFileThresholdBytes = bigFileThresholdBytes;
		this.failover = failover;
		this.source = source;
		this.failoverSource = failoverSource;
	}

	public static synchronized DownloadConfig get() {
		if(instance == null) {
			instance = load();
		}
		return instance;
	}

	/** 测试用：丢弃缓存的配置（下次 get() 重新读盘） */
	public static synchronized void reset() {
		instance = null;
	}

	private static DownloadConfig load() {
		try {
			Configuration c = ConfigLoader.loadFromResource("download.toml");
			// 外部 launcher.toml 优先：改并发 / 换镜像不需要重新打包
			LauncherConfig external = LauncherConfig.get();
			int threads = external.threads() != null ? Math.max(1, external.threads()) : Math.max(1, c.getInt("download.threads", 16));
			int retries = Math.max(1, c.getInt("download.retries", 3));
			int cacheMinutes = Math.max(0, c.getInt("download.manifestCacheMinutes", 60));
			int segments = Math.max(1, c.getInt("download.bigFileSegments", 4));
			long threshold = Math.max(1, c.getInt("download.bigFileThresholdMB", 8)) * 1024L * 1024L;
			boolean failover = c.getBoolean("download.failover", true);

// 解析全部已知下载源
			Map<String, Source> sources = new LinkedHashMap<>();
			for(String name : List.of("official", "bmclapi")) {
				String prefix = "sources." + name + ".";
				if(!c.containsKey(prefix + "manifest")) {
					continue;
				}
				List<String> hosts = c.getList(prefix + "rewriteHosts")
				                      .stream()
				                      .map(String :: valueOf)
				                      .toList();
				sources.put(
						name, new Source(
								name,
								c.getString(prefix + "manifest"),
								ensureSlash(c.getString(prefix + "libraries")),
								ensureSlash(c.getString(prefix + "resources")),
								hosts,
								c.getString(prefix + "mirrorHost", "")
						)
				           );
			}

			String name = (external.source() != null ? external.source() : c.getString("download.source", "official"))
			               .toLowerCase(Locale.ROOT);
			Source source = sources.get(name);
			if(source == null) {
				throw new IllegalArgumentException(
						"未知下载源: " + name + "（可选: " + String.join(", ", sources.keySet())
						+ "；可用 launcher.toml 的 [launcher].source 覆盖）");
			}
// 故障切换源：已知源里第一个非当前源
			Source failoverSource = null;
			if(failover) {
				failoverSource = sources.values()
				                        .stream()
				                        .filter(s -> !s.name()
				                                       .equals(name))
				                        .findFirst()
				                        .orElse(null);
			}

			log.info(
					"下载配置: source={}, threads={}, retries={}, failover={}{}",
					name, threads, retries, failoverSource != null ? failoverSource.name() : "off",
					external.source() != null || external.threads() != null ? "（launcher.toml 覆盖）" : ""
			        );
			return new DownloadConfig(
					threads, retries, cacheMinutes, segments, threshold,
					failover, source, failoverSource
			);
		} catch(Exception e) {
			throw new IllegalStateException("读取下载配置失败(download.toml): " + e.getMessage(), e);
		}
	}

	private static String ensureSlash(String url) {
		if(url == null || url.endsWith("/")) {
			return url;
		}
		return url + "/";
	}

	/**
	 * 计算同一资源在故障切换源上的等价地址：
	 * libraries / resources 按基址前缀替换；其余（piston 等 Mojang 域名）按镜像 rewrite 规则。
	 * 无法映射时返回 null。
	 */
	public String failoverUrl(String url) {
		if(!failover || failoverSource == null || url == null) {
			return null;
		}
		Source primary = source;
		Source fb = failoverSource;
		if(primary.librariesBase() != null && url.startsWith(primary.librariesBase())
		   && fb.librariesBase() != null) {
			return fb.librariesBase() + url.substring(primary.librariesBase()
			                                                 .length());
		}
		if(primary.resourcesBase() != null && url.startsWith(primary.resourcesBase())
		   && fb.resourcesBase() != null) {
			return fb.resourcesBase() + url.substring(primary.resourcesBase()
			                                                 .length());
		}
		String rewritten = fb.rewrite(url);
		return rewritten.equals(url) ? null : rewritten;
	}

	public int threads() {
		return threads;
	}

	public int retries() {
		return retries;
	}

	public int manifestCacheMinutes() {
		return manifestCacheMinutes;
	}

	public int bigFileSegments() {
		return bigFileSegments;
	}

	public long bigFileThresholdBytes() {
		return bigFileThresholdBytes;
	}

	public boolean failover() {
		return failover;
	}

	public Source source() {
		return source;
	}

	public Source failoverSource() {
		return failoverSource;
	}

	public record Source(String name, String manifest, String librariesBase, String resourcesBase,
	                     List<String> rewriteHosts, String mirrorHost) {

		/** 按镜像规则改写 URL（镜像源把 rewriteHosts 中的域名替换为 mirrorHost） */
		public String rewrite(String url) {
			if(url == null || mirrorHost == null || mirrorHost.isBlank()) {
				return url;
			}
			for(String host : rewriteHosts) {
				url = url.replace("https://" + host, mirrorHost)
				         .replace("http://" + host, mirrorHost);
			}
			return url;
		}
	}
}