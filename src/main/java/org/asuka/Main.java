package org.asuka;

import org.asuka.cli.CliInputHolder;
import org.asuka.cli.GameCommands;
import org.asuka.cli.OptionNormalizer;
import org.asuka.core.VersionResolver;
import org.asuka.files.AppPaths;
import org.asuka.files.LauncherConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * CLI 入口（Spring Shell 3.x）：
 * 无参启动 → 交互式 REPL（提示符见 application.yml）；
 * 带参启动 → Spring Shell 非交互模式执行单条命令后自动退出（脚本化兼容）。
 * 便携布局：<根>/mcli/jar 与 <根>/mcli/launcher.toml、<根>/mcli/data/ 同级，<根>/.minecraft、<根>/log 在上一级。
 */
@SpringBootApplication
public class Main {

	static {
		// 便携布局锚点：按 jar 位置推导并写入系统属性，必须先于任何日志初始化
		// （logback 日志路径、AppPaths、Spring Shell 历史文件都以 mcli.base / mcli.root 为锚）。
		// jar 在 <根>/mcli/ 内 → mcli.base=<根>/mcli（toml/data 锚点），mcli.root=<根>（.minecraft/log 锚点）；
		// 非 jar 运行（IDE / mvn）不设置，各路径按工作目录解析。可用 -Dmcli.base / -Dmcli.root 显式覆盖。
		try {
			Path src = Path.of(Main.class.getProtectionDomain()
			                                  .getCodeSource()
			                                  .getLocation()
			                                  .toURI());
			if(Files.isRegularFile(src) && src.getFileName()
			                                  .toString()
			                                  .endsWith(".jar")) {
				Path jarDir = src.toAbsolutePath()
				                 .getParent();
				if(jarDir != null && jarDir.getParent() != null) {
					System.setProperty("mcli.base", jarDir.toString());
					System.setProperty("mcli.root", jarDir.getParent()
					                                      .normalize()
					                                      .toString());
				}
			}
		} catch(Exception ignored) {
			// 定位失败不致命：各路径回退到工作目录解析
		}
	}

	/** 是否单次执行模式（game play 用它决定是否透传游戏退出码） */
	public static volatile boolean oneShot = false;

	public static void main(String[] args) throws IOException, InterruptedException {
		assert args != null;

		// launcher.toml 的 gameDir / account.cache 影响所有后续路径，必须在最早处应用
		LauncherConfig.get()
		              .applyToPaths();
		AppPaths.migrateToVersionIsolation();
		VersionResolver.moveInheritedBasesToClientData();

		oneShot = args.length > 0;
// Spring Shell 不支持单横多字符选项键，这里把用户习惯的 -list 映射为 --list
		String[] mapped = Arrays.stream(args)
		                        .map(OptionNormalizer :: normalize)
		                        .toArray(String[] :: new);
		CliInputHolder.set(Arrays.asList(mapped));
		if(mapped.length > 0 && ("download".equalsIgnoreCase(mapped[0]) || "i".equalsIgnoreCase(mapped[0]))) {
// download / i 为全手工解析命令：单次模式直接执行，无需启动 Spring 容器
			new GameCommands(null).downloadRaw(Arrays.asList(mapped));
			return;
		}
		SpringApplication.run(Main.class, mapped);
	}
}