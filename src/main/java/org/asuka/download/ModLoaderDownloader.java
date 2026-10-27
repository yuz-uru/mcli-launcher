package org.asuka.download;

import lombok.extern.slf4j.Slf4j;
import org.asuka.core.VersionResolver;
import org.asuka.download.model.VersionJson;
import org.asuka.files.AppPaths;
import org.asuka.utils.JsonUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * mod loader 安装（PCL 布局：版本进 versions/<id>/，库进共享 libraries/）：
 * - Fabric：先确保原版基底，再取 profile json 下载 loader 库
 * - Forge / NeoForge：下载官方 installer，对 .minecraft 根执行 --installClient
 */
@Slf4j
public final class ModLoaderDownloader {

	private static final String FABRIC_META = "https://meta.fabricmc.net/v2/versions";
	private static final String FORGE_MAVEN = "https://maven.minecraftforge.net/net/minecraftforge/forge/";
	private static final String NEOFORGE_MAVEN = "https://maven.neoforged.net/releases/net/neoforged/neoforge/";

	private ModLoaderDownloader() {
	}

	public static String fabric(String mc, String loaderVer)
			throws IOException, InterruptedException {
		VersionResolver.ensureBaseAvailable(mc); // 原版基底（data/client，共享 libraries/assets）

		String loader = (loaderVer == null || loaderVer.isBlank()) ? latestFabricLoader(mc) : loaderVer;
		log.info("Fabric: loader={}", loader);

		String profileUrl = FABRIC_META + "/loader/" + mc + "/" + loader + "/profile/json";
		String body = Http.getString(profileUrl);
		VersionJson vj = JsonUtil.gson()
		                         .fromJson(body, VersionJson.class);

		String pid = (vj != null && vj.id != null && !vj.id.isBlank()) ? vj.id : mc + "-fabric";
		Path dir = AppPaths.versionDir(pid);
		Files.createDirectories(dir);
		Files.writeString(dir.resolve(pid + ".json"), body, StandardCharsets.UTF_8);

		assert vj != null;
		new DownloadPool(DownloadConfig.get()).run(TaskCollector.fromVersionJson(vj, dir, DownloadConfig.get()));
		log.info("Fabric 版本 {} 安装完成", pid);
		return pid;
	}

	/**
	 * 解析指定 loader 在该游戏版本下的最新版本；loaderVer 为 "latest"（忽略大小写）时生效，
	 * 否则原样返回。
	 */
	public static String resolveLoaderVersion(String type, String mc, String loaderVer)
			throws IOException, InterruptedException {
		if(loaderVer == null || loaderVer.isBlank() || "latest".equalsIgnoreCase(loaderVer)) {
			return switch(type) {
				case "fabric" -> latestFabricLoader(mc);
				case "forge" -> latestForgeLoader(mc);
				case "neoforge" -> latestNeoForgeLoader(mc);
				default -> throw new IllegalArgumentException("未知的 loader 类型: " + type);
			};
		}
		return loaderVer;
	}

	/** Forge 最新版：files.minecraftforge.net 的 promotions 清单（<mc>-latest） */
	public static String latestForgeLoader(String mc) throws IOException, InterruptedException {
		String body = Http.getString("https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json");
		com.google.gson.JsonObject root = JsonUtil.gson()
		                                          .fromJson(body, com.google.gson.JsonObject.class);
		com.google.gson.JsonObject promos = root == null ? null : root.getAsJsonObject("promos");
		if(promos != null && promos.has(mc + "-latest")) {
			return promos.get(mc + "-latest")
			             .getAsString();
		}
		throw new IOException("未找到 Forge " + mc + " 的最新版本（promotions 清单无该条目）");
	}

	/** NeoForge 最新版：maven-metadata.xml 升序取最后一个匹配该游戏版本前缀的条目 */
	public static String latestNeoForgeLoader(String mc) throws IOException, InterruptedException {
		String body = Http.getString("https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml");
		java.util.regex.Matcher m = java.util.regex.Pattern
				.compile("<version>([^<]+)</version>")
				.matcher(body);
		String shortPrefix = mc.startsWith("1.") ? mc.substring(2) : mc; // 1.21.1 -> 21.1（新命名规则）
		String best = null;
		while(m.find()) {
			String v = m.group(1);
			if(v.startsWith(mc + "-") || v.startsWith(mc + ".") || v.startsWith(shortPrefix + ".")) {
				best = v;
			}
		}
		if(best == null) {
			throw new IOException("未找到 NeoForge " + mc + " 的版本");
		}
		return best;
	}

	public static String forge(String mc, String loaderVer) throws IOException, InterruptedException {
// promotions 清单返回纯构建号（如 47.4.28），maven 路径需要 <mc>-<build> 前缀
		String v = loaderVer;
		if(v != null && !v.isBlank() && !v.startsWith(mc + "-")) {
			v = mc + "-" + v;
		}
		return viaInstaller("forge", mc, v, FORGE_MAVEN);
	}

	public static String neoforge(String mc, String loaderVer) throws IOException, InterruptedException {
		return viaInstaller("neoforge", mc, loaderVer, NEOFORGE_MAVEN);
	}

	private static String viaInstaller(String name, String mc, String loaderVer, String mavenBase)
			throws IOException, InterruptedException {
		if(loaderVer == null || loaderVer.isBlank()) {
			throw new IllegalArgumentException(name + " 需要通过 --loader 指定版本"
			                                   + "（如 forge: 1.20.4-49.0.46，neoforge: 21.1.95 或 1.20.1-47.1.106）");
		}
		VersionResolver.ensureBaseAvailable(mc);

		String installerName = name + "-" + loaderVer + "-installer.jar";
		String url = mavenBase + loaderVer + "/" + installerName;
		Path installerJar = AppPaths.INSTALLER_CACHE.resolve(installerName);
		new DownloadPool(DownloadConfig.get())
				.run(List.of(new DownloadTask(name + " installer", url, installerJar, null, 0)));

// Forge/NeoForge 安装器要求目标目录存在启动器档案文件（官方启动器创建），第三方需提供桩文件
		Path profiles = AppPaths.gameDir().resolve("launcher_profiles.json");
		if(!Files.isRegularFile(profiles)) {
			Files.writeString(profiles, "{\"profiles\":{},\"settings\":{},\"version\":3}");
		}

		Set<String> before = existingVersions();
		log.info("运行 {} installer（installer 需联网拉取依赖，请耐心等待）……", name);
		Process process = new ProcessBuilder(
				javaExe(), "-jar",
				installerJar.toAbsolutePath()
				            .toString(),
				"--installClient", AppPaths.gameDir().toAbsolutePath()
				                                    .toString()
		)
				.directory(AppPaths.gameDir().toFile())
				.inheritIO()
				.start();
		int code = process.waitFor();
		if(code != 0) {
			throw new IOException(name + " installer 异常退出，退出码 " + code);
		}

		List<String> added = existingVersions().stream()
		                                       .filter(v -> !before.contains(v))
		                                       .sorted()
		                                       .toList();
		if(added.isEmpty()) {
			throw new IOException("installer 执行完成但未发现新版本目录");
		}
		String pid = added.getFirst();
		log.info("{} 版本 {} 安装完成", name, pid);
		return pid;
	}

	private static String latestFabricLoader(String mc) throws IOException, InterruptedException {
		LoaderEntry[] entries = JsonUtil.gson()
		                                .fromJson(Http.getString(FABRIC_META + "/loader/" + mc), LoaderEntry[].class);
		if(entries == null || entries.length == 0 || entries[0].loader() == null) {
			throw new IOException("Fabric meta 未返回 " + mc + " 的 loader 列表");
		}
		return Arrays.stream(entries)
		             .filter(e -> e.loader() != null && e.loader()
		                                                 .stable())
		             .map(e -> e.loader()
		                        .version())
		             .findFirst()
		             .orElse(entries[0].loader()
		                               .version());
	}

	private static Set<String> existingVersions() throws IOException {
		if(!Files.isDirectory(AppPaths.gameVersions())) {
			return Set.of();
		}
		try(var stream = Files.list(AppPaths.gameVersions())) {
			return stream.filter(Files :: isDirectory)
			             .map(p -> p.getFileName()
			                        .toString())
			             .collect(Collectors.toSet());
		}
	}

	private static String javaExe() {
		return ProcessHandle.current()
		                    .info()
		                    .command()
		                    .orElse("java");
	}

	public record LoaderEntry(Loader loader) {
		public record Loader(String version, boolean stable) {
		}
	}
}
