package org.asuka.cli;

import lombok.extern.slf4j.Slf4j;
import org.asuka.Main;
import org.asuka.core.GameLauncher;
import org.asuka.core.VersionResolver;
import org.asuka.download.ModLoaderDownloader;
import org.asuka.download.VanillaDownloader;
import org.asuka.download.VersionManifest;
import org.asuka.download.model.VersionJson;
import org.asuka.files.AppPaths;
import org.asuka.modpack.ModpackInstaller;
import org.asuka.utils.JsonUtil;
import org.springframework.shell.component.flow.ComponentFlow;
import org.springframework.shell.component.flow.SelectItem;
import org.springframework.shell.standard.ShellCommandGroup;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/** 游戏相关命令（内部分组 game）：list=已安装 / download=直装或 loader 安装 / launch=启动 */
@Slf4j
@ShellComponent
@ShellCommandGroup("game")
public class GameCommands {

	private final org.jline.terminal.Terminal terminal;

	public GameCommands(org.jline.terminal.Terminal terminal) {
		this.terminal = terminal;
	}

	private static long modifiedTime(Path p) {
		try {
			return Files.getLastModifiedTime(p)
			            .toMillis();
		} catch(IOException e) {
			return 0;
		}
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s;
	}

	@ShellMethod(
			key = "list",
			value = "列举已安装的游戏版本（含类型）"
	)
	public void list() throws IOException {
		Path versions = AppPaths.gameVersions();
		if(!Files.isDirectory(versions)) {
			log.info("尚未安装任何版本，可用 download -list 选择安装");
			return;
		}
		record Installed(String id, Path json) {
		}
		List<Installed> installed = new ArrayList<>();
		try(var stream = Files.list(versions)) {
			stream.filter(Files :: isDirectory)
			      .map(d -> d.resolve(d.getFileName()
			                           .toString() + ".json"))
			      .filter(Files :: isRegularFile)
			      .forEach(json -> installed.add(new Installed(
					      json.getParent()
					          .getFileName()
					          .toString(), json
			      )));
		}
		if(installed.isEmpty()) {
			log.info("尚未安装任何版本，可用 download -list 选择安装");
			return;
		}
		installed.sort(Comparator.comparingLong((Installed i) -> modifiedTime(i.json()))
		                         .reversed());
		log.info("已安装 {} 个版本:", installed.size());
		for(Installed i : installed) {
			log.info("{} {}", i.id(), kind(i.json()));
		}
	}

	/**
	 * 参数全部从原始输入自行解析（框架的位置绑定不可控，见 实现方案.md 11.3 踩坑）。
	 * 语义：无 - 前缀的裸参数 = 游戏版本；-f/-nf/-fb 选择 loader（--v 为 loader 版本，
	 * 省略或 latest = 该游戏版本的最新 loader）；--as 指定安装后的版本名。
	 * 别名 i 与 download 等价；裸参数为已存在的 .zip/.mrpack 整合包文件时转入整合包安装（--mc/--loader 生效）。
	 */
	public void downloadRaw(List<String> tokens)
			throws IOException, InterruptedException {
		boolean listFlag = false;
		boolean all = false;
		String loaderType = null;   // forge / neoforge / fabric
		String loaderVerOpt = null; // --v（loader 版本；latest / 省略 = 最新）
		String asName = null;         // --as
		String mcOverride = null;     // --mc（整合包：覆盖包内声明的游戏版本）
		String loaderOverride = null; // --loader（整合包：覆盖包内声明的 loader 版本）
		List<String> bare = new ArrayList<>();

		for(int i = 0; i < tokens.size(); i++) {
			String t = tokens.get(i);
			if(t == null || t.isBlank()) {
				continue;
			}
			String low = t.toLowerCase(Locale.ROOT);
			if(low.equals("download") || low.equals("i")) {
				continue;
			}
			switch(low) {
				case "-list", "--list" -> listFlag = true;
				case "--all" -> all = true;
				case "-f", "--f" -> loaderType = "forge";
				case "-nf", "--nf" -> loaderType = "neoforge";
				case "-fb", "--fb" -> loaderType = "fabric";
				case "-v", "--v" -> {
					if(i + 1 < tokens.size()) {
						loaderVerOpt = tokens.get(++i);
					} else {
						log.error("--v 缺少 loader 版本");
						return;
					}
				}
				case "-as", "--as" -> {
					if(i + 1 < tokens.size()) {
						asName = tokens.get(++i);
					} else {
						log.error("--as 缺少版本名称");
						return;
					}
				}
				case "-mc", "--mc" -> {
					if(i + 1 < tokens.size()) {
						mcOverride = tokens.get(++i);
					} else {
						log.error("--mc 缺少游戏版本");
						return;
					}
				}
				case "-loader", "--loader" -> {
					if(i + 1 < tokens.size()) {
						loaderOverride = tokens.get(++i);
					} else {
						log.error("--loader 缺少 loader 版本");
						return;
					}
				}
				default -> {
					if(t.startsWith("-")) {
						log.warn("忽略未知参数: {}", t);
					} else {
						bare.add(t);
					}
				}
			}
		}

// 通用安装入口：裸参数为已存在的整合包文件（.zip / .mrpack）→ 转入整合包安装（download 与别名 i 共用）
		if(!bare.isEmpty()) {
			String first = bare.getFirst()
			                   .replace("\"", "")
			                   .replace("'", "")
			                   .trim();
			Path packFile = null;
			if(first.toLowerCase(Locale.ROOT)
			        .endsWith(".zip")
			   || first.toLowerCase(Locale.ROOT)
			           .endsWith(".mrpack")) {
				try {
					Path p = Path.of(first);
					if(Files.isRegularFile(p)) {
						packFile = p;
					}
				} catch(InvalidPathException ignored) {
				}
			}
			if(packFile != null) {
				if(bare.size() > 1) {
					log.warn("一次只能安装一个整合包文件，已忽略多余参数: {}", bare.subList(1, bare.size()));
				}
				if(loaderType != null || loaderVerOpt != null) {
					log.warn("-f/-nf/-fb 与 --v 仅用于游戏 / loader 安装，整合包安装已忽略");
				}
				String pid;
				try {
					pid = ModpackInstaller.install(packFile, mcOverride, loaderOverride, asName);
				} catch(InterruptedException e) {
					Thread.currentThread()
					      .interrupt();
					log.error("操作被中断");
					return;
				} catch(IOException e) {
					log.error("整合包安装失败: {}", e.getMessage());
					return;
				}
				if(!VersionResolver.isInstalled(pid)) {
					log.warn("整合包内容已就绪，但未生成可启动版本 {}（直铺包请用 --mc <游戏版本> 重试）", pid);
				} else {
					log.info("安装完成，launch {} 启动", pid);
				}
				return;
			}
		}

// -list：可安装版本 + 上下键选择安装
		if(listFlag) {
			List<VersionManifest.Entry> entries = VersionManifest.list(all);
			List<VersionManifest.Entry> shown = all ? entries
					: entries.subList(0, Math.min(20, entries.size()));
			log.info("可安装版本（显示 {} / 共 {} 个）:", shown.size(), entries.size());
			for(VersionManifest.Entry v : shown) {
				String date = v.releaseTime() != null && v.releaseTime()
				                                          .length() >= 10
						? v.releaseTime()
						   .substring(0, 10) : "";
				log.info("{} {} {}", v.id(), v.type(), date);
			}
			if(!Main.oneShot && System.console() != null) {
				String picked = pickVersion(shown);
				if(picked == null) {
					log.info("已取消，未安装任何版本");
				} else {
					install(picked, "vanilla", null, null);
				}
			} else {
				log.info("提示：交互终端下执行 download -list 可上下键选择并回车安装；直接安装用 download <版本id>");
			}
			return;
		}

// loader 安装：裸参数第一项 = 游戏版本；--v = loader 版本（latest/省略 = 最新）
		if(loaderType != null) {
			if(bare.isEmpty()) {
				log.error("需要提供游戏版本，例如: download -f 1.20.1");
				return;
			}
			if(bare.size() > 1) {
				log.error("一次只能为一个游戏版本安装 mod");
				return;
			}
			String mc = bare.getFirst();
			String loaderVer = ModLoaderDownloader.resolveLoaderVersion(loaderType, mc, loaderVerOpt);
			log.info("loader 版本: {}", loaderVer);
			String pid = install(mc, loaderType, loaderVer, asName);
			if(pid != null) {
				log.info("安装完成，launch {} 启动", pid);
			}
			return;
		}

// 纯净版直装：裸参数 = 游戏版本
		if(loaderVerOpt != null && !loaderVerOpt.isBlank()) {
			log.warn("--v 仅在 -f/-nf/-fb 安装 mod 时生效，已忽略");
		}
		if(mcOverride != null || loaderOverride != null) {
			log.warn("--mc / --loader 仅在安装整合包时生效，已忽略");
		}
		if(bare.isEmpty()) {
			log.error("用法: download(i) <版本id...> 直装纯净版 / -f|-nf|-fb <游戏版本> 安装 mod / -list 选择安装 / 直接给整合包文件安装整合包");
			return;
		}
		if(asName != null && !asName.isBlank() && bare.size() > 1) {
			log.error("--as 一次只能对应一个版本");
			return;
		}
		for(String id : bare) {
			String pid = install(id, "vanilla", null, asName);
			if(pid != null) {
				log.info("安装完成，launch {} 启动", pid);
			}
		}
	}

	@ShellMethod(
			key = "launch",
			value = "启动游戏（联机默认禁用：--allow-mp 或 launcher.toml 启用，参数优先；同版本在跑时用 --force 强启）"
	)
	public void play(
			@ShellOption(defaultValue = "") String id,
			@ShellOption(
					value = "--xmx",
					defaultValue = ""
			) String xmx,
			@ShellOption(
					value = "--width",
					defaultValue = "0"
			) Integer width,
			@ShellOption(
					value = "--height",
					defaultValue = "0"
			) Integer height,
			@ShellOption(
					value = "--java",
					defaultValue = ""
			) String javaPath,
			@ShellOption(
					value = "--dry-run",
					arity = 0,
					defaultValue = "false"
			) boolean dryRun,
			@ShellOption(
					value = "--allow-mp",
					arity = 0,
					defaultValue = "false"
			) boolean allowMp,
			@ShellOption(
					value = "--force",
					arity = 0,
					defaultValue = "false"
			) boolean force
	                )
			throws IOException, InterruptedException, TimeoutException {
		if(id == null || id.isBlank()) {
			log.error(
					"用法: launch <版本id> [--allow-mp] [--xmx 4G] [--width W --height H] [--java <路径>] " +
					"[--dry-run] [--force]　联机默认禁用，--allow-mp 优先于 launcher.toml；同一版本已在运行时用 --force 强启");
			return;
		}
		if(xmx != null && !xmx.isBlank() && !GameLauncher.isValidXmx(xmx)) {
			log.error("--xmx 值非法: {}（示例: 4G / 2048M / 2G）", xmx);
			return;
		}
		Integer w = (width == null || width <= 0) ? null : width;
		Integer h = (height == null || height <= 0) ? null : height;
		int code = GameLauncher.play(id, blankToNull(xmx), w, h, blankToNull(javaPath), dryRun, allowMp, force);
// 单次执行模式透传游戏退出码；交互模式回到提示符
		if(Main.oneShot) {
			System.exit(code);
		}
	}

	/** 统一安装入口：返回安装后的版本 id（--as 生效时为重命名后的名称；失败返回 null） */
	private String install(String id, String loaderType, String loaderVer, String asName)
			throws IOException, InterruptedException {
		if(VersionManifest.find(id)
		                  .isEmpty()) {
			log.error("版本清单中未找到: {}", id);
			return null;
		}
		try {
			String pid;
			switch(loaderType) {
				case "vanilla" -> {
					VanillaDownloader.download(id);
					pid = id;
				}
				case "fabric" -> pid = ModLoaderDownloader.fabric(
						id,
						ModLoaderDownloader.resolveLoaderVersion("fabric", id, loaderVer)
				                                                 );
				case "forge" -> pid = ModLoaderDownloader.forge(
						id,
						ModLoaderDownloader.resolveLoaderVersion("forge", id, loaderVer)
				                                               );
				case "neoforge" -> pid = ModLoaderDownloader.neoforge(
						id,
						ModLoaderDownloader.resolveLoaderVersion("neoforge", id, loaderVer)
				                                                     );
				default -> {
					log.error("未知的 loader 类型: {}", loaderType);
					return null;
				}
			}
			if(asName != null && !asName.isBlank() && !asName.equals(pid)) {
				VersionResolver.renameVersion(pid, asName);
				pid = asName;
			}
			return pid;
		} catch(IllegalArgumentException e) {
			log.error("{}", e.getMessage());
			return null;
		} catch(IOException e) {
			log.error("下载失败: {}", e.getMessage());
			return null;
		}
	}

	/** 上下键选择一个可安装版本（仅真实交互终端；Esc 取消返回 null） */
	private String pickVersion(List<VersionManifest.Entry> shown) {
		try {
			List<SelectItem> items = shown.stream()
			                              .map(v -> SelectItem.of(v.id() + "  [" + v.type() + "]", v.id()))
			                              .toList();
			ComponentFlow flow = ComponentFlow.builder()
			                                  .terminal(terminal)
			                                  .withSingleItemSelector("version")
			                                  .name("选择要安装的版本（上下键移动，回车确认，Esc 取消）")
			                                  .selectItems(items)
			                                  .max(15)
			                                  .and()
			                                  .build();
			return flow.run()
			           .getContext()
			           .get("version");
		} catch(Exception e) {
			log.warn("选择器不可用（{}），请改用 download <版本id> 直接安装", e.getMessage());
			return null;
		}
	}

	/** 依 mainClass / inheritsFrom 粗判版本类型 */
	private String kind(Path json) {
		try(Reader reader = Files.newBufferedReader(json, StandardCharsets.UTF_8)) {
			VersionJson vj = JsonUtil.gson()
			                         .fromJson(reader, VersionJson.class);
			if(vj == null) {
				return "损坏";
			}
			String mc = vj.mainClass == null ? "" : vj.mainClass.toLowerCase();
			if(mc.contains("fabric")) {
				return "fabric";
			}
			if(mc.contains("forge")) {
				return "forge";
			}
			if(vj.inheritsFrom != null) {
				return "衍生自 " + vj.inheritsFrom;
			}
			return "vanilla";
		} catch(Exception e) {
			return "损坏";
		}
	}
}
