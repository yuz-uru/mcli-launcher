package org.asuka.modpack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.asuka.core.VersionResolver;
import org.asuka.download.DownloadConfig;
import org.asuka.download.DownloadPool;
import org.asuka.download.DownloadTask;
import org.asuka.files.AppPaths;
import org.asuka.utils.JsonUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 整合包安装：支持 Modrinth(.mrpack) / CurseForge(manifest.json) / MultiMC(mmc-pack.json) / 直铺 zip。
 * 流程：探测格式 → 解析元数据 → 基底安装 → 建整合包版本（继承基底）→ mod 下载 → overrides 解压。
 * 约定：内容全部落在 versions/&lt;版本名&gt;/（mods / resourcepacks / shaderpacks 随版本隔离），
 * 版本名默认取整合包文件名（去扩展名）；用「继承基底的薄版本」实现，不移动也不重命名共享的 loader 版本。
 */
@Slf4j
public final class ModpackInstaller {

	private ModpackInstaller() {
	}

	public static ModpackFormat detect(Path zipPath) throws IOException {
		try(ZipFile zip = new ZipFile(zipPath.toFile())) {
			if(zip.getEntry("modrinth.index.json") != null) {
				return ModpackFormat.MODRINTH;
			}
			ZipEntry mf = zip.getEntry("manifest.json");
			if(mf != null) {
				String content = new String(
						zip.getInputStream(mf)
						   .readAllBytes(), StandardCharsets.UTF_8
				);
				if(content.contains("projectID") && content.contains("fileID")) {
					return ModpackFormat.CURSEFORGE;
				}
			}
			if(zip.getEntry("mmc-pack.json") != null) {
				return ModpackFormat.MULTIMC;
			}
			if(zip.getEntry("mods/") != null || zip.getEntry("config/") != null) {
				return ModpackFormat.UNKNOWN;
			}
		}
		return ModpackFormat.UNKNOWN;
	}

	/** 安装整合包；asName 为空时默认用包文件名（去扩展名）作为安装后的版本名 */
	public static String install(Path modpackPath, String mcOverride, String loaderOverride, String asName)
			throws IOException, InterruptedException {
		if(!Files.isRegularFile(modpackPath)) {
			throw new IOException("整合包文件不存在: " + modpackPath);
		}
		String packName = asName == null || asName.isBlank() ? defaultPackName(modpackPath) : sanitizeVersionName(asName);
		if(packName.isBlank()) {
			throw new IOException("无法从文件名推导版本名，请用 --as 指定");
		}
		ModpackFormat format = detect(modpackPath);
		log.info("整合包格式: {} ({}) → 版本名 {}", format, modpackPath.getFileName(), packName);

		return switch(format) {
			case MODRINTH -> installModrinth(modpackPath, mcOverride, loaderOverride, packName);
			case CURSEFORGE -> installCurseForge(modpackPath, mcOverride, loaderOverride, packName);
			case MULTIMC -> installMultiMC(modpackPath, mcOverride, loaderOverride, packName);
			default -> installFlat(modpackPath, mcOverride, packName);
		};
	}

	/** 默认版本名 = 包文件名去扩展名（非法字符净化） */
	static String defaultPackName(Path modpackPath) {
		return defaultPackName(modpackPath.getFileName().toString());
	}

	static String defaultPackName(String fileName) {
		String name = fileName;
		int dot = name.lastIndexOf('.');
		if(dot > 0) {
			name = name.substring(0, dot);
		}
		String sanitized = sanitizeVersionName(name);
		return sanitized.isBlank() ? "modpack" : sanitized;
	}

	/** 版本名净化：替换路径非法字符，压掉多余空白，去掉首尾的点与空格 */
	static String sanitizeVersionName(String raw) {
		if(raw == null) {
			return "";
		}
		String s = raw.replaceAll("[\\\\/:*?\"<>|]", "-")
		              .replaceAll("\\s+", " ")
		              .trim();
		while(s.startsWith(".")) {
			s = s.substring(1);
		}
		while(s.endsWith(".") || s.endsWith(" ")) {
			s = s.substring(0, s.length() - 1)
			     .trim();
		}
		return s;
	}

	/**
	 * 建整合包自己的版本目录：写一个只含 id + inheritsFrom 的薄 version json，
	 * 解析时自动继承基底的 mainClass / libraries / 启动参数；游戏工作目录即 versions/&lt;版本名&gt;/，
	 * 于是 mods / resourcepacks / shaderpacks 天然按版本隔离。
	 */
	static String createPackVersion(String baseId, String packName) throws IOException {
		if(baseId == null || baseId.isBlank()) {
			throw new IOException("无法确定整合包基底版本");
		}
		if(packName.equals(baseId)) {
			return packName;
		}
		Path dir = AppPaths.versionDir(packName);
		Path json = dir.resolve(packName + ".json");
		if(Files.isRegularFile(json)) {
			log.warn("版本 {} 已存在，本次整合包内容将并入该目录", packName);
			return packName;
		}
		Files.createDirectories(dir);
		Files.writeString(
				json,
				"{\"id\":\"" + packName + "\",\"inheritsFrom\":\"" + baseId + "\"}" + System.lineSeparator(),
				StandardCharsets.UTF_8
		);
		log.info("整合包版本 {} 已创建（继承 {}；mods / resourcepacks / shaderpacks 随版本隔离）", packName, baseId);
		return packName;
	}

// ===== Modrinth =====

	private static String installModrinth(Path zipPath, String mcOv, String loaderOv, String packName)
			throws IOException, InterruptedException {
		try(ZipFile zip = new ZipFile(zipPath.toFile())) {
			JsonObject index = JsonUtil.gson()
			                           .fromJson(
				                           new String(
					                           zip.getInputStream(zip.getEntry("modrinth.index.json"))
					                              .readAllBytes(), StandardCharsets.UTF_8
				                           ),
				                           JsonObject.class
			                                    );
			if(index == null) {
				throw new IOException("modrinth.index.json 解析失败");
			}
			JsonObject deps = index.getAsJsonObject("dependencies");
			String mc = mcOv != null && !mcOv.isBlank() ? mcOv
					: deps != null && deps.has("minecraft") ? deps.get("minecraft")
					                                              .getAsString() : null;
			if(mc == null) {
				throw new IOException("modrinth.index.json 缺少 minecraft 版本");
			}

			String loaderType = "vanilla";
			String loaderVer = null;
			if(deps != null) {
				for(String key : List.of("fabric-loader", "forge", "neoforge")) {
					if(deps.has(key)) {
						loaderType = key.equals("fabric-loader") ? "fabric" : key;
						loaderVer = deps.get(key)
						                .getAsString();
						break;
					}
				}
			}
			if(loaderOv != null && !loaderOv.isBlank()) {
				loaderVer = loaderOv;
			}

			String baseId = installBase(mc, loaderType, loaderVer);
			String pid = createPackVersion(baseId, packName);
			Path target = AppPaths.versionDir(pid);

// mod / 资源包 / 光影包：按包内声明的相对路径落进版本目录（mods/xxx.jar 等）
			List<DownloadTask> tasks = new ArrayList<>();
			if(index.has("files") && index.get("files")
			                              .isJsonArray()) {
				for(JsonElement fe : index.getAsJsonArray("files")) {
					JsonObject fm = fe.getAsJsonObject();
					String path = fm.has("path") ? fm.get("path")
					                                 .getAsString() : null;
					if(path == null || !fm.has("downloads") || fm.getAsJsonArray("downloads")
					                                             .isEmpty()) {
						continue;
					}
					String url = fm.getAsJsonArray("downloads")
					               .get(0)
					               .getAsString();
					String sha1 = fm.has("hashes") && fm.getAsJsonObject("hashes")
					                                    .has("sha1")
							? fm.getAsJsonObject("hashes")
							    .get("sha1")
							    .getAsString() : null;
					String clientEnv = fm.has("env") && fm.getAsJsonObject("env")
					                                      .has("client")
							? fm.getAsJsonObject("env")
							    .get("client")
							    .getAsString() : "required";
					if("unsupported".equals(clientEnv)) {
						continue;
					}
					Path dst = target.resolve(path)
					                  .normalize();
					if(!dst.startsWith(target.normalize())) {
						log.warn("整合包内存在非法路径，已跳过: {}", path);
						continue;
					}
					tasks.add(new DownloadTask("mod " + path, url, dst, sha1, 0));
				}
			}
			new DownloadPool(DownloadConfig.get()).run(tasks);
			extractZipPrefix(zip, "overrides/", target);

			log.info("Modrinth 整合包安装完成: {}（{} 个文件任务）", pid, tasks.size());
			return pid;
		}
	}

// ===== CurseForge =====

	private static String installCurseForge(Path zipPath, String mcOv, String loaderOv, String packName)
			throws IOException, InterruptedException {
		try(ZipFile zip = new ZipFile(zipPath.toFile())) {
			JsonObject manifest = JsonUtil.gson()
			                              .fromJson(
				                              new String(
					                              zip.getInputStream(zip.getEntry("manifest.json"))
					                                 .readAllBytes(), StandardCharsets.UTF_8
				                              ),
				                              JsonObject.class
				                                       );
			if(manifest == null) {
				throw new IOException("manifest.json 解析失败");
			}
			JsonObject minecraftObj = manifest.getAsJsonObject("minecraft");
			String mc = mcOv != null && !mcOv.isBlank() ? mcOv
					: minecraftObj != null && minecraftObj.has("version") ? minecraftObj.get("version")
					                                                              .getAsString() : null;
			if(mc == null) {
				throw new IOException("manifest.json 缺少 minecraft.version");
			}

			String loaderType = "vanilla";
			String loaderVer = null;
			if(minecraftObj != null && minecraftObj.has("modLoaders")) {
				for(JsonElement mle : minecraftObj.getAsJsonArray("modLoaders")) {
					JsonObject ml = mle.getAsJsonObject();
					String id = ml.has("id") ? ml.get("id")
					                             .getAsString() : "";
					if(id.startsWith("forge-")) {
						loaderType = "forge";
						loaderVer = id.substring(6);
					} else if(id.startsWith("fabric-")) {
						loaderType = "fabric";
						loaderVer = id.substring(7);
					}
				}
			}
			if(loaderOv != null && !loaderOv.isBlank()) {
				loaderVer = loaderOv;
			}

			String baseId = installBase(mc, loaderType, loaderVer);
			String pid = createPackVersion(baseId, packName);
			Path target = AppPaths.versionDir(pid);

// mod 文件：cursemaven 重定向（落到版本目录的 mods/）
			List<DownloadTask> tasks = new ArrayList<>();
			if(manifest.has("files") && manifest.get("files")
			                                    .isJsonArray()) {
				for(JsonElement fe : manifest.getAsJsonArray("files")) {
					JsonObject fm = fe.getAsJsonObject();
					int projectId = fm.has("projectID") ? fm.get("projectID")
					                                        .getAsInt() : 0;
					int fileId = fm.has("fileID") ? fm.get("fileID")
					                                  .getAsInt() : 0;
					if(projectId == 0 || fileId == 0) {
						continue;
					}
					String url = "https://www.cursemaven.com/curse/maven/"
					             + "curse.maven-" + projectId + "/" + fileId + "/"
					             + "curse.maven-" + projectId + "-" + fileId + ".jar";
					String fileName = "mod-cf-" + projectId + "-" + fileId + ".jar";
					tasks.add(new DownloadTask(
							"CF mod " + projectId + "/" + fileId, url,
							target.resolve("mods")
							      .resolve(fileName), null, 0
					));
				}
			}
			new DownloadPool(DownloadConfig.get()).run(tasks);
			extractZipPrefix(zip, "overrides/", target);

			log.info("CurseForge 整合包安装完成: {}（{} 个文件任务）", pid, tasks.size());
			return pid;
		}
	}

// ===== MultiMC =====

	private static String installMultiMC(Path zipPath, String mcOv, String loaderOv, String packName)
			throws IOException, InterruptedException {
		try(ZipFile zip = new ZipFile(zipPath.toFile())) {
			JsonObject pack = JsonUtil.gson()
			                          .fromJson(
				                          new String(
					                          zip.getInputStream(zip.getEntry("mmc-pack.json"))
					                             .readAllBytes(), StandardCharsets.UTF_8
				                          ),
				                          JsonObject.class
				                                   );
			if(pack == null) {
				throw new IOException("mmc-pack.json 解析失败");
			}
			String mc = null;
			String loaderType = "vanilla";
			String loaderVer = null;
			if(pack.has("components") && pack.get("components")
			                                     .isJsonArray()) {
				for(JsonElement ce : pack.getAsJsonArray("components")) {
					JsonObject cm = ce.getAsJsonObject();
					String uid = cm.has("uid") ? cm.get("uid")
					                               .getAsString() : "";
					String version = cm.has("version") ? cm.get("version")
					                                       .getAsString() : null;
					if("net.minecraft".equals(uid) && version != null) {
						mc = version;
					}
					if("net.minecraftforge".equals(uid) && version != null) {
						loaderType = "forge";
						loaderVer = version;
					}
					if("net.fabricmc.fabric-loader".equals(uid) && version != null) {
						loaderType = "fabric";
						loaderVer = version;
					}
				}
			}
			if(mcOv != null && !mcOv.isBlank()) {
				mc = mcOv;
			}
			if(loaderOv != null && !loaderOv.isBlank()) {
				loaderVer = loaderOv;
			}
			if(mc == null || mc.isBlank()) {
				throw new IOException("mmc-pack.json 缺少 net.minecraft 组件，请用 --mc <游戏版本> 指定");
			}

			String baseId = installBase(mc, loaderType, loaderVer);
			String pid = createPackVersion(baseId, packName);
			Path target = AppPaths.versionDir(pid);
			extractZipPrefix(zip, "minecraft/", target);
			extractZipPrefix(zip, ".minecraft/", target);

			log.info("MultiMC 整合包安装完成: {}", pid);
			return pid;
		}
	}

// ===== 直铺 =====

	private static String installFlat(Path zipPath, String mcOverride, String packName) throws IOException {
		try(ZipFile zip = new ZipFile(zipPath.toFile())) {
			if(mcOverride == null || mcOverride.isBlank()) {
				int n = extractZipPrefix(zip, "", AppPaths.gameDir());
				log.warn("直铺整合包不含版本信息，{} 个文件已解压到 {}；未创建可启动版本（可用 --mc <游戏版本> 重试）", n, AppPaths.gameDir());
				return packName;
			}
			String pid = createPackVersion(mcOverride, packName);
			Path target = AppPaths.versionDir(pid);
			int n = extractZipPrefix(zip, ".minecraft/", target);
			if(n == 0) {
				n = extractZipPrefix(zip, "minecraft/", target);
			}
			if(n == 0) {
				n = extractZipPrefix(zip, "", target);
			}
			log.info("直铺整合包安装完成: {}（{} 个文件解压到版本目录）", pid, n);
			return pid;
		}
	}

// ===== 工具 =====

	/** 装基底：loader 返回其版本 id，vanilla 返回游戏版本本身（基底 json 在 data/client，由解析器回退查找） */
	private static String installBase(String mc, String loaderType, String loaderVer)
			throws IOException, InterruptedException {
		VersionResolver.ensureBaseAvailable(mc);
		return switch(loaderType) {
			case "forge" -> org.asuka.download.ModLoaderDownloader.forge(mc, loaderVer);
			case "neoforge" -> org.asuka.download.ModLoaderDownloader.neoforge(mc, loaderVer);
			case "fabric" -> org.asuka.download.ModLoaderDownloader.fabric(mc, loaderVer);
			default -> mc;
		};
	}

	/** 解压 zip 中以 prefix 开头的条目到 targetDir（防 zip slip）；返回解压文件数 */
	private static int extractZipPrefix(ZipFile zip, String prefix, Path targetDir) throws IOException {
		Path target = targetDir.toAbsolutePath()
		                       .normalize();
		int prefixLen = prefix.length();
		int count = 0;
		for(java.util.Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
			ZipEntry entry = e.nextElement();
			if(entry.isDirectory() || !entry.getName()
			                                .startsWith(prefix)) {
				continue;
			}
			String relPath = entry.getName()
			                      .substring(prefixLen);
			if(relPath.isBlank()) {
				continue;
			}
			Path dst = target.resolve(relPath)
			                 .normalize();
			if(!dst.startsWith(target)) {
				continue;
			}
			Files.createDirectories(dst.getParent());
			Files.copy(zip.getInputStream(entry), dst, StandardCopyOption.REPLACE_EXISTING);
			count++;
		}
		return count;
	}
}
