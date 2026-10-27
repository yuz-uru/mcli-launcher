package org.asuka.files;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 启动器运行时路径。版本隔离采用 PCL 格式：
 * versions/<版本>/ 即该版本的独立游戏目录（saves / mods / config / options.txt 随版本隔离），
 * libraries 与 assets 在根目录全版本共享。
 * 便携布局：<根>/mcli/ 内为 jar + launcher.toml + data/，<根>/.minecraft 与 <根>/log 在上一级。
 */
@Slf4j
public final class AppPaths {

	/**
	 * jar 目录锚点（mcli/）：launcher.toml 与 data/ 与 jar 平级。
	 * 打包运行由 Main 的静态块在日志系统初始化前设为 jar 所在目录（<根>/mcli/）；
	 * 开发运行（IDE / mvn）未设置 mcli.base 时 = 当前工作目录。可用 -Dmcli.base=<目录> 显式指定。
	 */
	public static final Path BASE_DIR = initBaseDir();

	private static Path initBaseDir() {
		String prop = System.getProperty("mcli.base");
		Path p = prop == null || prop.isBlank() ? Path.of("") : Path.of(prop);
		return p.toAbsolutePath()
		        .normalize();
	}

	/**
	 * 数据根目录（jar 目录的上一级 <根>）：<根>/.minecraft 与 <根>/log 锚定于此。
	 * 开发运行未设置 mcli.root 时 = BASE_DIR（工作目录，与旧开发布局一致）。可用 -Dmcli.root=<目录> 显式指定。
	 */
	public static final Path ROOT_DIR = initRootDir();

	private static Path initRootDir() {
		String prop = System.getProperty("mcli.root");
		if(prop == null || prop.isBlank()) {
			return BASE_DIR;
		}
		return Path.of(prop).toAbsolutePath()
		            .normalize();
	}

	/** launcher 自身数据目录（锚定基准目录） */
	public static final Path DATA_DIR = BASE_DIR.resolve("data");
	/** 官方版本清单缓存 */
	public static final Path VERSION_MANIFEST_CACHE = DATA_DIR.resolve("version_manifest.json");
	/** mod loader installer 缓存目录 */
	public static final Path INSTALLER_CACHE = DATA_DIR.resolve("installers");

	/** 启动器与游戏日志统一归档目录（锚定数据根目录） */
	public static final Path LOG_DIR = ROOT_DIR.resolve("log");
	/** 游戏运行日志归档（log/game/<版本id>/） */
	public static final Path GAME_LOG_DIR = LOG_DIR.resolve("game");

	/** Minecraft 根目录（libraries / assets 等共享资源所在）；可用系统属性 mcli.gameDir 或 overrideGameDir 覆盖 */
	private static volatile Path gameDir = initGameDir();

	private static final List<String> TYPE_DIRS = List.of("vanilla", "fabric", "forge", "neoforge");

	private AppPaths() {
	}

	private static Path initGameDir() {
		String prop = System.getProperty("mcli.gameDir");
		if(prop == null || prop.isBlank()) {
			return ROOT_DIR.resolve(".minecraft");
		}
		Path p = Path.of(prop);
		return p.isAbsolute() ? p : ROOT_DIR.resolve(p);
	}

	/** 当前 Minecraft 根目录（默认 ./.minecraft；启动器数据/libraries/assets 均在其下） */
	public static Path gameDir() {
		return gameDir;
	}

	/** 覆盖 Minecraft 根目录（单元测试 / 便携场景专用；正常启动不要调用） */
	public static void overrideGameDir(Path dir) {
		if(dir == null) { gameDir = initGameDir(); return; }
		gameDir = dir.isAbsolute() ? dir : ROOT_DIR.resolve(dir);
	}

	/** 登录会话缓存路径（默认 data/usercache.json；account.cache 可覆盖） */
	private static volatile Path sessionCachePath;
	/** 启动器配置文件路径（默认 ./launcher.toml；测试可覆盖） */
	private static volatile Path launcherConfigPath;
	/** 下载校验指纹缓存路径（默认 data/download-index.json；测试可覆盖） */
	private static volatile Path downloadIndexPath;

	public static Path sessionCache() {
		Path p = sessionCachePath;
		return p != null ? p : DATA_DIR.resolve("usercache.json");
	}

	/** 覆盖会话缓存路径（配置 / 测试专用） */
	public static void overrideSessionCache(Path path) {
		if(path == null) { sessionCachePath = null; return; }
		sessionCachePath = path.isAbsolute() ? path : BASE_DIR.resolve(path);
	}

	public static Path launcherConfig() {
		Path p = launcherConfigPath;
		return p != null ? p : BASE_DIR.resolve("launcher.toml");
	}

	/** 覆盖启动器配置文件路径（测试专用） */
	public static void overrideLauncherConfig(Path path) {
		if(path == null) { launcherConfigPath = null; return; }
		launcherConfigPath = path.isAbsolute() ? path : BASE_DIR.resolve(path);
	}

	/** 下载校验指纹缓存路径（data/download-index.json） */
	public static Path downloadIndex() {
		Path p = downloadIndexPath;
		return p != null ? p : DATA_DIR.resolve("download-index.json");
	}

	/** 覆盖下载校验指纹缓存路径（测试专用） */
	public static void overrideDownloadIndex(Path path) {
		if(path == null) { downloadIndexPath = null; return; }
		downloadIndexPath = path.isAbsolute() ? path : BASE_DIR.resolve(path);
	}

	/** 全版本共享的版本目录（PCL：每个子目录 = 一个版本的独立游戏目录） */
	public static Path gameVersions() {
		return gameDir().resolve("versions");
	}

	public static Path gameLibraries() {
		return gameDir().resolve("libraries");
	}

	public static Path gameAssets() {
		return gameDir().resolve("assets");
	}

	/** inheritsFrom 原版基底目录（data/client/<id>/，启动器内部管理，不作为独立版本展示） */
	public static Path clientBaseDir(String id) {
		return gameDir().resolve("client")
		               .resolve(id);
	}

	public static Path clientBaseJson(String id) {
		return clientBaseDir(id).resolve(id + ".json");
	}

	public static Path versionDir(String id) {
		return gameVersions().resolve(id);
	}

	public static Path versionJson(String id) {
		return versionDir(id).resolve(id + ".json");
	}

	/** 迁移：把此前的"类型隔离目录"（.minecraft/vanilla|fabric|.../）合并为 PCL 式根布局（一次性） */
	public static void migrateToVersionIsolation() {
		try {
			Files.createDirectories(gameVersions());
			for(String type : TYPE_DIRS) {
				Path typeDir = gameDir().resolve(type);
				if(!Files.isDirectory(typeDir)) {
					continue;
				}
				log.info("检测到类型隔离目录 {}，合并到版本隔离布局……", typeDir);
				mergeDir(typeDir.resolve("versions"), gameVersions());
				mergeDir(typeDir.resolve("libraries"), gameLibraries());
				mergeDir(typeDir.resolve("assets"), gameAssets());
				deleteEmptyDirs(typeDir);
			}
		} catch(IOException e) {
			log.warn("版本隔离布局迁移失败（不影响本次运行，下次启动重试）: {}", e.getMessage());
		}
	}

	/** 把 from 内所有条目并入 to（目标已存在则跳过），随后清理空目录 */
	public static void mergeDir(Path from, Path to) throws IOException {
		if(!Files.isDirectory(from)) {
			return;
		}
		Files.createDirectories(to);
		try(var stream = Files.list(from)) {
			for(Path src : stream.toList()) {
				Path dst = to.resolve(src.getFileName()
				                         .toString());
				if(Files.isDirectory(src)) {
					mergeDir(src, dst);
				} else if(!Files.exists(dst)) {
					Files.createDirectories(dst.getParent());
					Files.move(src, dst);
				}
			}
		}
		deleteEmptyDirs(from);
	}

	private static void deleteEmptyDirs(Path dir) throws IOException {
		if(!Files.isDirectory(dir) || dir.equals(gameDir())) {
			return;
		}
		try(var stream = Files.list(dir)) {
			for(Path child : stream.toList()) {
				if(Files.isDirectory(child)) {
					deleteEmptyDirs(child);
				}
			}
		}
		try(var stream = Files.list(dir)) {
			if(stream.findAny()
			         .isPresent()) {
				return;
			}
		}
		Files.deleteIfExists(dir);
	}
}