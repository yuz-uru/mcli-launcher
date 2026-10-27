package org.asuka.core;

import lombok.extern.slf4j.Slf4j;
import org.asuka.download.Rules;
import org.asuka.download.TaskCollector;
import org.asuka.download.VanillaDownloader;
import org.asuka.download.model.VersionJson;
import org.asuka.files.AppPaths;
import org.asuka.utils.JsonUtil;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * version json 解析：加载继承链（inheritsFrom）并合并为统一启动模型。
 * PCL 版本隔离：versions/<id>/ 即该版本的游戏目录；libraries / assets 根目录共享。
 * 继承链的原版基底存放于 data/client/<id>/（优先级低于 versions/）。
 */
@Slf4j
public final class VersionResolver {

	private VersionResolver() {
	}

	/** 版本是否已安装（versions/<id>/<id>.json 存在） */
	public static boolean isInstalled(String id) {
		return Files.isRegularFile(AppPaths.versionJson(id));
	}

	public static ResolvedVersion resolve(String id, Map<String, Boolean> features)
			throws IOException, InterruptedException {
// 1) 继承链：chain[0] = 自身，向后依次为父版本
		List<VersionJson> chain = new ArrayList<>();
		String cur = id;
		while(cur != null) {
			chain.add(loadJson(cur));
			cur = chain.getLast().inheritsFrom;
		}

// 2) 子优先字段：mainClass / versionType / assetIndex / client jar / logging / javaVersion
		String mainClass = null;
		String versionType = null;
		String assetIndexId = null;
		String jarId = null;
		String clientJsonId = null;
		VersionJson.Logging.ClientLog logConfig = null;
		Integer javaMajor = null;
		for(VersionJson v : chain) {
			if(mainClass == null && v.mainClass != null) {
				mainClass = v.mainClass;
			}
			if(versionType == null && v.type != null) {
				versionType = v.type;
			}
			if(assetIndexId == null) {
				if(v.assetIndex != null && v.assetIndex.id != null) {
					assetIndexId = v.assetIndex.id;
				} else if(v.assets != null) {
					assetIndexId = v.assets;
				}
			}
			if(jarId == null && v.jar != null) {
				jarId = v.jar;
			}
			if(clientJsonId == null && v.downloads != null && v.downloads.containsKey("client")) {
				clientJsonId = v.id;
			}
			if(logConfig == null && v.logging != null && v.logging.client != null) {
				logConfig = v.logging.client;
			}
			if(javaMajor == null && v.javaVersion != null && v.javaVersion.majorVersion != null) {
				javaMajor = v.javaVersion.majorVersion;
			}
		}

// 3) libraries：按 name+path 去重（子版本优先），rules 过滤后产出 classpath 与 natives 清单
		LinkedHashMap<String, VersionJson.Library> libs = new LinkedHashMap<>();
		for(VersionJson v : chain) {
			for(VersionJson.Library lib : v.libraries == null ? List.<VersionJson.Library> of() : v.libraries) {
				if(lib.name != null) {
					libs.putIfAbsent(dedupKey(lib), lib);
				}
			}
		}
		List<Path> classpath = new ArrayList<>();
		List<Path> nativesJars = new ArrayList<>();
		String nativesKey = "natives-" + Rules.currentOs();
		for(VersionJson.Library lib : libs.values()) {
			if(!Rules.allowed(lib.rules, features)) {
				continue;
			}
			VersionJson.DownloadInfo artifact = lib.downloads == null ? null : lib.downloads.artifact;
			if(artifact != null) {
				String path = artifact.path != null ? artifact.path : TaskCollector.mavenPath(lib.name, null);
				Path libJar = AppPaths.gameLibraries()
				                      .resolve(path);
				if(!classpath.contains(libJar)) {
					classpath.add(libJar);
				}
				if(path.contains(nativesKey)) {
					nativesJars.add(libJar);
				}
			} else if(lib.downloads == null) {
				classpath.add(AppPaths.gameLibraries()
				                      .resolve(TaskCollector.mavenPath(lib.name, null)));
			}
			if(lib.downloads != null && lib.downloads.classifiers != null) {
				VersionJson.DownloadInfo natives = lib.downloads.classifiers.get(nativesKey);
				if(natives != null) {
					nativesJars.add(AppPaths.gameLibraries()
					                        .resolve(natives.path != null
							                                 ? natives.path :
							                                 TaskCollector.mavenPath(lib.name, nativesKey)));
				}
			}
		}

// 4) client jar（jar 字段指定 > 继承链中定义 downloads.client 的版本；基底可能在 data/client）
		String clientId = jarId != null ? jarId : clientJsonId;
		if(clientId == null) {
			throw new IOException("版本继承链中找不到 client jar 定义");
		}
		Path clientJar = AppPaths.versionDir(clientId)
		                         .resolve(clientId + ".jar");
		if(!Files.isRegularFile(clientJar)) {
			Path baseJar = AppPaths.clientBaseDir(clientId)
			                       .resolve(clientId + ".jar");
			if(Files.isRegularFile(baseJar)) {
				clientJar = baseJar;
			} else {
				// 基底客户端 jar 缺失（历史 json-only 残留 / 中断的下载）：自动补全下载原版基底
				log.info("客户端 jar 缺失，自动下载原版基底 {}（json + 客户端 jar + 库 + 资源）", clientId);
				VanillaDownloader.downloadClientBase(clientId);
				if(!Files.isRegularFile(baseJar)) {
					throw new IOException("客户端 jar 补全下载后仍缺失: " + baseJar + "（请先 download " + clientId + "）");
				}
				clientJar = baseJar;
			}
		}
		classpath.add(clientJar);

// 5) 启动参数：新式 arguments（父版本在前）或旧版 minecraftArguments
		boolean modern = chain.stream()
		                      .anyMatch(v -> v.arguments != null
		                                     && (v.arguments.jvm != null || v.arguments.game != null));
		List<String> jvmArgs = new ArrayList<>();
		List<String> gameArgs = new ArrayList<>();
		if(modern) {
			for(int i = chain.size() - 1; i >= 0; i--) {
				VersionJson v = chain.get(i);
				if(v.arguments == null) {
					continue;
				}
				collectArgs(v.arguments.jvm, features, jvmArgs);
				collectArgs(v.arguments.game, features, gameArgs);
			}
		} else {
			for(VersionJson v : chain) {
				if(v.minecraftArguments != null && !v.minecraftArguments.isBlank()) {
					gameArgs.addAll(Arrays.asList(v.minecraftArguments.trim()
					                                                  .split("\\s+")));
					break;
				}
			}
		}

		if(mainClass == null) {
			throw new IOException("版本继承链中缺少 mainClass");
		}
		return new ResolvedVersion(
				id, mainClass, versionType, assetIndexId, clientJar,
				List.copyOf(classpath), List.copyOf(nativesJars),
				List.copyOf(jvmArgs), List.copyOf(gameArgs), !modern, javaMajor, logConfig
		);
	}

	private static String dedupKey(VersionJson.Library lib) {
		String path = lib.downloads != null && lib.downloads.artifact != null && lib.downloads.artifact.path != null
				? lib.downloads.artifact.path : "";
		boolean hasClassifiers = lib.downloads != null && lib.downloads.classifiers != null;
// 同一 name+path 可能同时存在"主 jar 条目"与"带 classifiers 的 natives 条目"，必须区分
		return lib.name + "|" + path + "|" + hasClassifiers;
	}

	private static void collectArgs(List<Object> raw, Map<String, Boolean> features, List<String> out) {
		if(raw == null) {
			return;
		}
		for(Object element : raw) {
			if(element instanceof String s) {
				out.add(s);
				continue;
			}
			VersionJson.Arg a = JsonUtil.gson()
			                            .fromJson(
					                            JsonUtil.gson()
					                                    .toJsonTree(element), VersionJson.Arg.class
			                                     );
			if(!Rules.allowed(a.rules, features)) {
				continue;
			}
			if(a.value instanceof List<?> list) {
				list.forEach(x -> out.add(String.valueOf(x)));
			} else if(a.value != null) {
				out.add(String.valueOf(a.value));
			}
		}
	}

	/** json 查找顺序：versions/<id>/ → data/client/<id>/（继承链基底）→ 自动下载基底到 data/client */
	private static VersionJson loadJson(String id) throws IOException, InterruptedException {
		Path file = AppPaths.versionJson(id);
		if(!Files.isRegularFile(file)) {
			Path baseJson = AppPaths.clientBaseJson(id);
			if(!Files.isRegularFile(baseJson)) {
				log.info("版本 {} 的 json 缺失，自动下载原版基底到 data/client", id);
				VanillaDownloader.downloadClientBase(id);
			}
			file = baseJson;
		}
		try(Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			VersionJson vj = JsonUtil.gson()
			                         .fromJson(reader, VersionJson.class);
			if(vj == null || vj.id == null) {
				throw new IOException("版本 JSON 解析失败: " + file);
			}
			return vj;
		}
	}

	/**
	 * 确保 mod 继承链的原版基底可用：json 与客户端 jar 齐全才算完整（versions/ 优先，其次 data/client），
	 * 不完整则完整下载基底到 data/client（含客户端 jar / 库 / 资源；已存在且校验通过的文件自动跳过）。
	 */
	public static void ensureBaseAvailable(String mc) throws IOException, InterruptedException {
		if(baseComplete(mc)) {
			return;
		}
		log.info("原版基底 {} 不完整（缺 json 或客户端 jar），自动下载补全", mc);
		VanillaDownloader.downloadClientBase(mc);
	}

	/** 基底是否完整：versions/<id>/ 或 data/client/<id>/ 任一位置 json + 客户端 jar 齐全 */
	static boolean baseComplete(String mc) {
		return (isInstalled(mc) && Files.isRegularFile(AppPaths.versionDir(mc)
		                                                        .resolve(mc + ".jar")))
				|| (Files.isRegularFile(AppPaths.clientBaseJson(mc)) && Files.isRegularFile(AppPaths.clientBaseDir(mc)
				                                                               .resolve(mc + ".jar")));
	}

	/** 启动时：把 versions/ 中仅作为 inheritsFrom 基底的原版移入 data/client（重新 download 可找回） */
	public static void moveInheritedBasesToClientData() {
		try {
			if(!Files.isDirectory(AppPaths.gameVersions())) {
				return;
			}
			java.util.Set<String> bases = new java.util.HashSet<>();
			try(var stream = Files.list(AppPaths.gameVersions())) {
				for(Path json : stream.map(d -> d.resolve(d.getFileName()
				                                           .toString() + ".json"))
				                      .filter(Files :: isRegularFile)
				                      .toList()) {
					try(Reader reader = Files.newBufferedReader(json, StandardCharsets.UTF_8)) {
						VersionJson vj = JsonUtil.gson()
						                         .fromJson(reader, VersionJson.class);
						if(vj != null && vj.inheritsFrom != null && !vj.inheritsFrom.isBlank()) {
							bases.add(vj.inheritsFrom);
						}
					} catch(Exception e) {
						log.debug("基底版本候选 {} 解析失败: {}", json, e.getMessage());
					}
				}
			}
			for(String base : bases) {
				if(isInstalled(base)) {
					Path from = AppPaths.versionDir(base);
					Path to = AppPaths.clientBaseDir(base);
					Files.createDirectories(to.getParent());
					if(Files.isDirectory(to)) {
						// 目标已存在（例如此前已下载过基底）：合并而不是让 Files.move 永久失败告警。
						// mergeDir 是「目标优先」（同名文件跳过），随后清掉源目录残留，避免 versions/<base> 长期残留
						AppPaths.mergeDir(from, to);
						deleteRecursively(from);
						log.info("原版基底 {} 合并进既有 data/client 目录（重新 download 可找回）", base);
					} else {
						Files.move(from, to);
						log.info("原版基底 {} 移入 data/client（供继承链使用，重新 download 可找回）", base);
					}
				}
			}
		} catch(IOException e) {
			log.warn("基底迁移失败（忽略，下次启动重试）: {}", e.getMessage());
		}
	}

	/** 递归删除目录（基底合并后的源目录清理用） */
	private static void deleteRecursively(Path dir) throws IOException {
		if(!Files.exists(dir)) {
			return;
		}
		try(var walk = Files.walk(dir)) {
			for(Path p : walk.sorted(Comparator.reverseOrder())
			                  .toList()) {
				Files.deleteIfExists(p);
			}
		}
	}

	/** 版本重命名（--as）：目录、json 内容与 client jar 同步改名；用于 launch 直接启动 */

	public static void renameVersion(String oldId, String newId) throws IOException {
		if(oldId.equals(newId)) {
			return;
		}
		if(!isInstalled(oldId)) {
			throw new IOException("版本 " + oldId + " 未安装，无法重命名");
		}
		if(isInstalled(newId)) {
			throw new IOException("版本名 " + newId + " 已存在");
		}
		Path oldDir = AppPaths.versionDir(oldId);
		Path newDir = AppPaths.versionDir(newId);
		Files.move(oldDir, newDir);
		try {
			Path oldJson = newDir.resolve(oldId + ".json");
			VersionJson vj = JsonUtil.gson()
			                         .fromJson(Files.readString(oldJson, StandardCharsets.UTF_8), VersionJson.class);
			vj.id = newId;
			Files.writeString(
					newDir.resolve(newId + ".json"), JsonUtil.gson()
					                                         .toJson(vj), StandardCharsets.UTF_8
			                 );
			Files.deleteIfExists(oldJson);
			Path oldJar = newDir.resolve(oldId + ".jar");
			if(Files.exists(oldJar)) {
				Files.move(oldJar, newDir.resolve(newId + ".jar"));
			}
			log.info("版本已重命名: {} -> {}", oldId, newId);
		} catch(IOException e) {
// 回滚目录名，避免留下半成品
			Files.move(newDir, oldDir);
			throw e;
		}
	}

	/** 启动所需的统一版本模型（继承链已合并） */
	public record ResolvedVersion(String id, String mainClass, String versionType, String assetIndexId,
	                              Path clientJar, List<Path> classpath, List<Path> nativesJars,
	                              List<String> jvmArgs, List<String> gameArgs, boolean legacy,
	                              Integer javaMajor, VersionJson.Logging.ClientLog logConfig) {
	}
}