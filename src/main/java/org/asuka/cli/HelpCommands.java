package org.asuka.cli;

import lombok.extern.slf4j.Slf4j;
import org.springframework.shell.standard.ShellCommandGroup;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import java.util.List;
import java.util.Locale;

/**
 * 自定义 help：静态命令注册表驱动（Spring Shell 内置 help 已禁用）。
 * 输出规范：全部参数出现在用法中，可选参数以 [] 包裹并置于末尾；help <命令> 查看参数详情。
 */
@Slf4j
@ShellComponent
@ShellCommandGroup("launcher")
public class HelpCommands {

	private static final List<Cmd> COMMANDS = List.of(
			new Cmd("list", "", "game", "列举已安装的游戏版本（含类型）", "list", List.of()), new Cmd(
					"download", "i", "game", "下载游戏本体（纯净版直装 / mod loader 安装 / 列举选择；别名 i）",
					"download / i <版本id...> [-f|-nf|-fb] [--v <游戏版本>] [loader版本|latest] [--as <版本名>] [-list] [--all]",
					List.of(
							new Opt("<版本id...>", "无 - 前缀的参数一律视为游戏版本，逐个直装纯净版；未找到则报错"),
							new Opt("<整合包文件>", "裸参数为已存在的 .zip / .mrpack 整合包文件时直接安装整合包"),
							new Opt("-f / -nf / -fb", "安装 Forge / NeoForge / Fabric（三选一）"),
							new Opt("--v <游戏版本>", "mod 对应的游戏版本（使用 -f/-nf/-fb 时必需）"), new Opt(
									"loader版本 / latest",
									"可省略的位置参数：指定 loader 版本；latest 或省略 = 该游戏版本的最新 loader"
							), new Opt("--as <版本名>", "指定安装后的版本名称，可直接 launch <名称> 启动"),
							new Opt("--mc <游戏版本>", "整合包用：覆盖包内声明的游戏版本（直铺包必填）"),
							new Opt("--loader <loader版本>", "整合包用：覆盖包内声明的 loader 版本"),
							new Opt("-list / --all", "列举可安装版本（-list 限 20，--all 全量）并上下键选择回车安装")
					       )
			), new Cmd(
					"launch", "", "game", "启动游戏（PCL 版本隔离：工作目录 = versions/<id>/）",
					"launch <版本id> [--allow-mp] [--xmx <内存>] [--width <宽> --height <高>] [--java <路径>] [--dry-run] " +
					"[--force]", List.of(
					new Opt("--xmx <内存>", "最大堆内存（默认 2G）"),
					new Opt("--width <宽> --height <高>", "窗口分辨率（两者需同时提供）"),
					new Opt("--java <路径>", "指定 Java 可执行文件（默认自动探测）"),
					new Opt("--allow-mp", "启用多人联机（默认禁用；优先级：本参数 > launcher.toml allowMultiplayer）"),
					new Opt("--dry-run", "仅打印启动命令，不实际登录 / 启动"),
					new Opt("--force", "跳过同版本实例锁（同一版本已在运行时默认拒绝启动）")
					                    )
			), new Cmd(
					"pack", "", "game", "安装整合包（Modrinth / CurseForge / MultiMC / 直铺 zip；install 可简写 i）",
					"pack install / i <文件路径> [--as <版本名>] [--mc <游戏版本>] [--loader <loader版本>]", List.of(
					new Opt("install", "固定动作：安装整合包（可简写 i）"),
					new Opt("<文件路径>", "整合包文件（可直接拖拽到终端）"),
					new Opt("--as <版本名>", "安装后的版本名（默认 = 包文件名去扩展名）"),
					new Opt("--mc <游戏版本>", "覆盖包内声明的游戏版本（直铺包必填）"),
					new Opt("--loader <loader版本>", "覆盖包内声明的 loader 版本")
					                                                                                                )
			), new Cmd("login", "", "launcher", "正版登录（设备码，首次使用执行）", "login", List.of()),
			new Cmd("logout", "", "launcher", "清除本地登录缓存", "logout", List.of()), new Cmd(
					"log", "", "launcher", "日志管理", "log [-clear] [-err] [--last] [-open] [-path]", List.of(
					new Opt("-clear", "清理旧日志（保留当前 launcher.log）"),
					new Opt("-err", "列出含报错的游戏日志（按时间倒序）"),
					new Opt("-err --last", "打开最近一次游戏日志的报错摘录"),
					new Opt("-open", "在资源管理器中打开 log 目录"), new Opt("-path", "显示日志目录路径")
					                                                                                          )
			), new Cmd("help", "", "launcher", "显示本帮助", "help [命令]", List.of())
	                                                 );

	@ShellMethod(
			key = "help",
			value = "显示帮助：help [命令] 查看参数详情（可选参数以 [] 包裹置于末尾）"
	)
	public void help(@ShellOption(defaultValue = "") String command) {
		if(command == null || command.isBlank()) {
			overview();
			return;
		}
		detail(command.toLowerCase(Locale.ROOT));
	}

	private void overview() {
		log.warn("本启动器不提供离线账户登录，仅支持正版账号；可选参数以 [] 包裹置于末尾");
		String lastGroup = null;
		for(Cmd cmd : COMMANDS) {
			if(!cmd.group()
			       .equals(lastGroup)) {
				lastGroup = cmd.group();
				log.info(lastGroup.equals("game") ? "game（游戏相关）:" : "launcher（启动器相关）:");
			}
			log.info("{}", cmd.synopsis());
			log.info("      {}", cmd.desc());
		}
		log.info("exit / quit 退出；使用 help <命令> 查看参数详情");
	}

	private void detail(String name) {
		Cmd cmd = COMMANDS.stream()
		                  .filter(c -> c.name()
		                                .equals(name) || name.equals(c.alias()))
		                  .findFirst()
		                  .orElse(null);
		if(cmd == null) {
			log.warn("未知命令: {}（help 查看全部）", name);
			return;
		}
		log.info("{} - {}", cmd.name(), cmd.desc());
		log.info("用法: {}", cmd.synopsis());
		if(!cmd.options()
		       .isEmpty()) {
			log.info("参数:");
			for(Opt o : cmd.options()) {
				log.info("      {} {}", o.syntax(), o.desc());
			}
		}
	}

	private record Opt(String syntax, String desc) {}

	private record Cmd(String name, String alias, String group, String desc, String synopsis, List<Opt> options) {}
}