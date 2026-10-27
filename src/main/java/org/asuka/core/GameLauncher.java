package org.asuka.core;

import lombok.extern.slf4j.Slf4j;
import org.asuka.auth.SessionManager;
import org.asuka.download.*;
import org.asuka.download.model.VersionJson;
import org.asuka.files.AppPaths;
import org.asuka.files.LauncherConfig;
import org.asuka.utils.LauncherInfo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 游戏启动（PCL 版本隔离）：
 * 工作目录 = versions/<id>/（saves / mods / config 随版本隔离），libraries / assets 用根目录共享。
 * dry-run 模式打印完整命令但不登录不启动（排障用）。
 */
@Slf4j
public final class GameLauncher {

	private static final Pattern VAR = Pattern.compile("\\$\\{([^}]+)}");
	/** 默认最大堆 */
	private static final String DEFAULT_XMX = "2G";
	/** 合法堆内存写法：数字 + 可选单位（k/m/g/t，大小写不限） */
	private static final Pattern XMX = Pattern.compile("[0-9]+[kKmMgGtT]?");

	private GameLauncher() {
	}

	public static int play(
			String versionId, String xmx, Integer width, Integer height,
			String javaPath, boolean dryRun, boolean allowMp, boolean force
	                      ) throws IOException, InterruptedException, TimeoutException {
		LauncherConfig lc = LauncherConfig.get();
		Map<String, Boolean> features = new HashMap<>();
		if(width != null && height != null) {
			features.put("has_custom_resolution", true);
		}

		if(!VersionResolver.isInstalled(versionId)) {
			if(VersionManifest.find(versionId)
			                  .isPresent()) {
				log.info("版本 {} 尚未安装，自动按纯净版下载", versionId);
				VanillaDownloader.download(versionId);
			} else {
				throw new IllegalArgumentException("版本 " + versionId
				                                   +
				                                   " 未安装，且官方清单中不存在（mod 版本请先用 download --as ... 安装）");
			}
		}
// PCL 版本隔离：该版本的独立游戏目录
		Path versionHome = AppPaths.versionDir(versionId)
		                           .toAbsolutePath();
		initGameDir(versionHome);

		VersionResolver.ResolvedVersion rv = VersionResolver.resolve(versionId, features);
		log.info(
				"版本解析完成: [{}] mainClass={}, 库 {} 个, 要求 Java {}",
				versionHome, rv.mainClass(), rv.classpath()
				                               .size(),
				rv.javaMajor() != null ? rv.javaMajor() : "?"
		        );
		if(rv.javaMajor() != null) {
			int current = Runtime.version()
			                     .feature();
			if(rv.javaMajor() > current) {
				log.warn("当前 Java {} 低于版本要求的 {}，可能无法启动", current, rv.javaMajor());
			}
		}

		// 联机许可决策：命令行参数 > 配置文件 > 默认禁用，决策来源透出到日志
		MpDecision mp = resolveMultiplayer(allowMp);
		SessionManager.Session session;
		if(dryRun) {
			session = new SessionManager.Session(
					lc.offlineName(),
					UUID.nameUUIDFromBytes("dry-run".getBytes(StandardCharsets.UTF_8)), "DRY-RUN-TOKEN", 0, SessionManager.Session.Mode.DRY_RUN
			);
			log.warn(
					"dry-run 模式：使用占位凭据，仅打印命令不真实登录 / 启动（联机许可: {}，来源: {}）",
					mp.allowed() ? "启用" : "禁用", mp.source()
			        );
		} else if(!mp.allowed()) {
// 禁用联机：legacy 身份 + --disableMultiplayer → 游戏内联机按钮置灰；加入在线服务器亦因会话校验失败被拒
			log.info("多人联机已禁用（来源: {}）", mp.source());
			session = new SessionManager.Session(
					lc.offlineName(),
					UUID.nameUUIDFromBytes("offline".getBytes(StandardCharsets.UTF_8)), "0", 0, SessionManager.Session.Mode.OFFLINE
			);
		} else {
			log.info("多人联机已启用（来源: {}）", mp.source());
			session = SessionManager.loadOrLogin();
		}

		// 实例锁：natives 解压与游戏运行期间独占该版本目录（进程退出由 OS 自动释放；dry-run 不加锁）
		try(InstanceLock ignored = dryRun ? null : InstanceLock.acquire(versionId, force)) {
			Path nativesDir = rv.nativesJars()
			                    .isEmpty() ? null
					: NativesExtractor.extract(versionId, rv.nativesJars());
			Path logConfig = ensureLogConfig(rv);

			Map<String, String> vars = buildVars(rv, session, versionHome, nativesDir, logConfig, width, height);
			// 优先级：命令行 > launcher.toml > 默认（buildCommand 内 -Xmx 兜底 2G、javaExe 兜底当前 JVM）
			String java = (javaPath == null || javaPath.isBlank()) ? lc.javaPath() : javaPath;
			String mem = (xmx == null || xmx.isBlank()) ? lc.xmx() : xmx;
			List<String> cmd = buildCommand(rv, vars, mem, java, !mp.allowed());

			if(dryRun) {
				log.info("[dry-run] 完整命令（{} 个参数）:", cmd.size());
				cmd.forEach(a -> log.info("  {}", a));
				return 0;
			}

			log.info("启动游戏: {}（版本隔离目录 {}）", versionId, versionHome);
			long since = System.currentTimeMillis();
			Process process = new ProcessBuilder(cmd)
					.directory(versionHome.toFile())
					.inheritIO()
					.start();
			int code;
			try {
				code = process.waitFor();
			} catch(InterruptedException e) {
				// REPL 里 Ctrl+C 中断的是等待线程：主动终止游戏进程，避免其成为孤儿继续占用版本目录与文件锁
				log.warn("等待游戏进程时被中断，终止游戏进程");
				process.destroy();
				try {
					if(!process.waitFor(10, TimeUnit.SECONDS)) {
						log.warn("游戏进程 10 秒内未退出，强制结束");
						process.destroyForcibly();
						process.waitFor(10, TimeUnit.SECONDS);
					}
				} catch(InterruptedException ie) {
					process.destroyForcibly();
				}
				Thread.currentThread()
				      .interrupt();
				copyGameLogs(versionId, since);
				return -1;
			}
			log.info("游戏已退出，退出码 {}", code);
			copyGameLogs(versionId, since);
			return code;
		}
	}

	/** 初始化版本游戏目录（PCL 隔离布局）：mods/saves/config 等标准目录 */
	static void initGameDir(Path versionHome) throws IOException {
		for(String d : List.of("mods", "saves", "config", "resourcepacks", "shaderpacks", "logs")) {
			Files.createDirectories(versionHome.resolve(d));
		}
	}

	/** 游戏结束后：归档 latest.log 与本次会话产生的 crash-report 到 log/game/<版本id>/ */
	private static void copyGameLogs(String versionId, long since) {
		try {
			Path srcLog = AppPaths.versionDir(versionId)
			                      .resolve("logs")
			                      .resolve("latest.log");
			if(Files.isRegularFile(srcLog)) {
				String ts = java.time.LocalDateTime.now()
				                                   .format(java.time.format.DateTimeFormatter.ofPattern(
						                                   "yyyyMMdd-HHmmss"));
				Path dst = AppPaths.GAME_LOG_DIR.resolve(versionId)
				                                .resolve("game-" + ts + ".log");
				Files.createDirectories(dst.getParent());
				Files.copy(srcLog, dst, StandardCopyOption.REPLACE_EXISTING);
			}
			Path crashes = AppPaths.versionDir(versionId)
			                       .resolve("crash-reports");
			if(Files.isDirectory(crashes)) {
				try(var stream = Files.list(crashes)) {
					for(Path report : stream.filter(Files :: isRegularFile)
					                        .toList()) {
						if(Files.getLastModifiedTime(report)
						        .toMillis() >= since - 60_000) {
							Path dst = AppPaths.GAME_LOG_DIR.resolve(versionId)
							                                .resolve(report.getFileName()
							                                               .toString());
							Files.createDirectories(dst.getParent());
							Files.copy(report, dst, StandardCopyOption.REPLACE_EXISTING);
						}
					}
				}
			}
		} catch(IOException e) {
			log.warn("游戏日志归档失败: {}", e.getMessage());
		}
	}

	/** logging.client 指向的 log4j 配置（下载后修补 LegacyXMLLayout，见 11.3 踩坑⑥） */
	private static Path ensureLogConfig(VersionResolver.ResolvedVersion rv) throws IOException, InterruptedException {
		if(rv.logConfig() == null || rv.logConfig().file == null || rv.logConfig().file.url == null) {
			return null;
		}
		VersionJson.Logging.LogFile f = rv.logConfig().file;
		Path target = AppPaths.gameAssets()
		                      .resolve("log_configs")
		                      .resolve(f.id != null ? f.id : "client-log.xml");
		Path patched = target.resolveSibling(target.getFileName() + ".patched");
		if(!(Files.isRegularFile(target) && Files.isRegularFile(patched))) {
			new DownloadPool(DownloadConfig.get()).run(List.of(new DownloadTask(
					"日志配置 " + f.id,
					DownloadConfig.get()
					              .source()
					              .rewrite(f.url), target, f.sha1, f.size
			)));
			String content = Files.readString(target, StandardCharsets.UTF_8);
			String patchedContent = content.replaceAll(
					"<LegacyXMLLayout\\s*/>",
					"<PatternLayout pattern=\"[%d{HH:mm:ss}] [%t/%level]: %msg{nolookups}%n\" />"
			                                          );
			if(!patchedContent.equals(content)) {
				Files.writeString(target, patchedContent, StandardCharsets.UTF_8);
				log.info("log4j 控制台布局已从 XML 事件改为纯文本（Mojang 原配置面向官方启动器）");
			}
			Files.createFile(patched);
		}
		return target;
	}

	private static Map<String, String> buildVars(
			VersionResolver.ResolvedVersion rv, SessionManager.Session session,
			Path versionHome, Path nativesDir, Path logConfig, Integer width, Integer height
	                                            ) {
		Map<String, String> vars = new HashMap<>();
		Path assetsRoot = AppPaths.gameAssets()
		                          .toAbsolutePath();
		vars.put("auth_player_name", session.name());
		vars.put(
				"auth_uuid", session.uuid()
				                    .toString()
		        );
		vars.put("auth_access_token", session.accessToken());
		vars.put("auth_session", "token:" + session.accessToken() + ":" + session.uuid());
		// 离线占位会话用 legacy 身份（游戏视作离线用户 → 联机按钮置灰）；正版登录为 msa
		vars.put("user_type", session.mode() == SessionManager.Session.Mode.OFFLINE ? "legacy" : "msa");
		vars.put("version_name", rv.id());
		vars.put("version_type", rv.versionType() == null ? "release" : rv.versionType());
		vars.put("game_directory", versionHome.toString());
		vars.put("assets_root", assetsRoot.toString());
		String indexId = rv.assetIndexId() == null ? "legacy" : rv.assetIndexId();
		vars.put("assets_index_name", indexId);
		vars.put(
				"game_assets", assetsRoot.resolve("virtual")
				                         .resolve(indexId)
				                         .toString()
		        );
		vars.put(
				"natives_directory", nativesDir == null ? versionHome.toString() : nativesDir.toAbsolutePath()
				                                                                             .toString()
		        );
		vars.put("launcher_name", LauncherInfo.NAME);
		vars.put("launcher_version", LauncherInfo.VERSION);
		vars.put(
				"classpath", rv.classpath()
				               .stream()
				               .map(p -> p.toAbsolutePath()
				                          .toString())
				               .collect(Collectors.joining(java.io.File.pathSeparator))
		        );
		vars.put("classpath_separator", java.io.File.pathSeparator);
		vars.put(
				"library_directory", AppPaths.gameLibraries()
				                             .toAbsolutePath()
				                             .toString()
		        );
		vars.put("clientid", "");
		vars.put("auth_xuid", "");
		vars.put("auth_token_type", "");
		if(logConfig != null) {
			vars.put(
					"path", logConfig.toAbsolutePath()
					                 .toString()
			        );
		}
		if(width != null) {
			vars.put("resolution_width", String.valueOf(width));
		}
		if(height != null) {
			vars.put("resolution_height", String.valueOf(height));
		}
		return vars;
	}

	static String substitute(String arg, Map<String, String> vars) {
		Matcher m = VAR.matcher(arg);
		StringBuilder sb = new StringBuilder();
		while(m.find()) {
			String key = m.group(1);
			String v = vars.get(key);
			if(v == null) {
				throw new IllegalArgumentException("未知的模板变量: ${" + key + "}");
			}
			m.appendReplacement(sb, Matcher.quoteReplacement(v));
		}
		m.appendTail(sb);
		return sb.toString();
	}

	static List<String> buildCommand(
			VersionResolver.ResolvedVersion rv, Map<String, String> vars,
			String xmx, String javaPath, boolean disableMultiplayer
	                                ) {
		String classpath = vars.get("classpath");
		String natives = vars.get("natives_directory");
		List<String> cmd = new ArrayList<>();
		cmd.add(javaExe(javaPath));
		String heap = xmx == null || xmx.isBlank() ? DEFAULT_XMX : xmx.trim();
		if(!isValidXmx(heap)) {
			log.warn("内存值非法，已回退默认 {}（示例: 4G / 2048M）: {}", DEFAULT_XMX, heap);
			heap = DEFAULT_XMX;
		}
		cmd.add("-Xmx" + heap);

		if(rv.legacy()) {
			if(rv.logConfig() != null && rv.logConfig().argument != null) {
				cmd.add(substitute(rv.logConfig().argument, vars));
			}
			cmd.add("-Djava.library.path=" + natives);
			cmd.add("-cp");
			cmd.add(classpath);
		} else {
			for(String a : rv.jvmArgs()) {
				cmd.add(substitute(a, vars));
			}
			if(rv.logConfig() != null && rv.logConfig().argument != null) {
				cmd.add(substitute(rv.logConfig().argument, vars));
			}
			if(cmd.stream()
			      .noneMatch("-cp" :: equals)) {
				cmd.add("-cp");
				cmd.add(classpath);
			}
			if(cmd.stream()
			      .noneMatch(a -> a.startsWith("-Djava.library.path="))) {
				cmd.add("-Djava.library.path=" + natives);
			}
		}

		cmd.add(rv.mainClass());
		for(String a : rv.gameArgs()) {
			cmd.add(substitute(a, vars));
		}

// 联机禁用：官方启动器家长控制同款参数，游戏据此置灰多人按钮；旧版本 Main 允许未知参数，会忽略

		if(disableMultiplayer) {

			cmd.add("--disableMultiplayer");

		}

// Forge BootstrapLauncher 按 ignoreList 过滤模块路径：client jar 必须列入，否则会作为自动模块导致包冲突
		String clientJarName = rv.clientJar()
		                         .getFileName()
		                         .toString();
		for(int i = 0; i < cmd.size(); i++) {
			String a = cmd.get(i);
			if(a.startsWith("-DignoreList=") && !a.endsWith("," + clientJarName)) {
				cmd.set(i, a + "," + clientJarName);
			}
		}

// Windows 单进程命令行上限 32767 字符，超长改用 @argfile（JDK 9+）
		int total = cmd.stream()
		               .mapToInt(String :: length)
		               .sum();
		if(total > 24000) {
			Path argfile = AppPaths.versionDir(rv.id())
			                       .resolve("argfile.txt");
			writeArgfile(argfile, cmd.subList(1, cmd.size()));
			log.info("命令行过长（{} 字符），改用 argfile: {}", total, argfile.toAbsolutePath());
			return List.of(cmd.getFirst(), "@" + argfile.toAbsolutePath());
		}
		return cmd;
	}

	private static void writeArgfile(Path file, List<String> args) {
		StringBuilder sb = new StringBuilder();
		for(String a : args) {
			sb.append(argfileToken(a))
			  .append('\n');
		}
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
		} catch(IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** argfile 参数写法：含空格 / 制表符 / 引号时加引号，内部引号转义，避免破坏参数边界 */
	private static String argfileToken(String a) {
		if(a.indexOf(' ') < 0 && a.indexOf('\t') < 0 && a.indexOf('"') < 0) {
			return a;
		}
		return "\"" + a.replace("\"", "\\\"") + "\"";
	}

	/** 合法的堆内存值：数字 + 可选单位（k/m/g/t）；非法值绝不能拼进 -Xmx（否则 JVM 直接启动失败） */
	public static boolean isValidXmx(String value) {
		return value != null && XMX.matcher(value.trim())
		                              .matches();
	}

	private static String javaExe(String configured) {
		if(configured != null && !configured.isBlank()) {
			return configured;
		}
		String current = ProcessHandle.current()
		                              .info()
		                              .command()
		                              .orElse(null);
		if(current != null && !current.isBlank()) {
			return current;
		}
		String javaHome = System.getenv("JAVA_HOME");
		if(javaHome != null && !javaHome.isBlank()) {
			Path p = Path.of(javaHome, "bin", "java.exe");
			if(Files.isRegularFile(p)) {
				return p.toString();
			}
			p = Path.of(javaHome, "bin", "java");
			if(Files.isRegularFile(p)) {
				return p.toString();
			}
		}
		return "java";
	}

	/**
	 * 多人联机许可决策，优先级：命令行参数 > 配置文件 > 默认禁用。
	 * ① 参数含 --allow-mp → 启用（参数优先，覆盖配置文件中的 false）；
	 * ② 否则读 launcher.toml 的 launcher.allowMultiplayer（true = 启用）；
	 * ③ 配置缺失或读取失败 → 禁用（安全默认值）。
	 */
	private static MpDecision resolveMultiplayer(boolean allowMpFromArgs) {
		if(allowMpFromArgs) {
			return new MpDecision(true, "命令行参数 --allow-mp");
		}
		Boolean allowed = LauncherConfig.get()
		                                .allowMultiplayer();
		if(allowed == null) {
			return new MpDecision(false, "默认禁用（" + AppPaths.launcherConfig() + " 未配置 allowMultiplayer）");
		}
		return new MpDecision(allowed, "配置文件 " + AppPaths.launcherConfig() + " allowMultiplayer=" + allowed);
	}

	/** 联机许可决策结果：allowed + 决策来源（用于日志透出） */
	private record MpDecision(boolean allowed, String source) {
	}
}
