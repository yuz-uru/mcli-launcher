package org.asuka.cli;

import lombok.extern.slf4j.Slf4j;
import org.asuka.core.VersionResolver;
import org.asuka.modpack.ModpackInstaller;
import org.springframework.shell.standard.ShellCommandGroup;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/** 整合包安装命令（game 分组）：pack install / pack list */
@Slf4j
@ShellComponent
@ShellCommandGroup("game")
public class ModpackCommands {

	@ShellMethod(
			key = "pack",
			value = "安装整合包：pack install <文件|拖拽路径> [--as <版本名>]（版本名默认 = 包文件名去扩展名）"
	)
	public void pack(
			@ShellOption(defaultValue = "") String action,
			@ShellOption(defaultValue = "") String modpackPath,
			@ShellOption(
					value = "--as",
					defaultValue = ""
			) String asName,
			@ShellOption(
					value = "--mc",
					defaultValue = ""
			) String mcOverride,
			@ShellOption(
					value = "--loader",
					defaultValue = ""
			) String loaderOverride
	                )
			throws IOException, InterruptedException {
		String act = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
		if(!"install".equals(act) && !"i".equals(act)) {
			log.info("用法: pack install(i) <文件路径|拖拽到终端> [--as <版本名>] [--mc <游戏版本>] [--loader <loader版本>]；"
			         + "版本名默认取包文件名，mods / resourcepacks / shaderpacks 随该版本隔离");
			return;
		}
		if(modpackPath == null || modpackPath.isBlank()) {
			log.error("用法: pack install <文件路径> [--as <名称>]");
			return;
		}
// 拖拽到终端时路径可能被引号包裹
		String clean = modpackPath.replace("\"", "")
		                          .replace("'", "")
		                          .trim();
		String pid = ModpackInstaller.install(Path.of(clean), mcOverride, loaderOverride, asName);
		if(!VersionResolver.isInstalled(pid)) {
			log.warn("整合包内容已就绪，但未生成可启动版本 {}（直铺包请用 --mc <游戏版本> 重试）", pid);
			return;
		}
		log.info("整合包安装完成，launch {} 启动（mods / resourcepacks / shaderpacks 在该版本目录内）", pid);
	}
}