package org.asuka.cli;

import lombok.extern.slf4j.Slf4j;
import org.asuka.download.DownloadPool;
import org.asuka.files.AppPaths;
import org.springframework.shell.standard.ShellCommandGroup;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** 日志管理命令（launcher 分组）：查看 / 报错定位 / 清理 / 打开目录 */
@Slf4j
@ShellComponent
@ShellCommandGroup("launcher")
public class LogCommands {

	private static long errorCount(Path f) {
		try {
			return Files.readAllLines(f)
			            .stream()
			            .filter(l -> l.contains("ERROR"))
			            .count();
		} catch(IOException e) {
			return 0;
		}
	}

	private static long modifiedTime(Path p) {
		try {
			return Files.getLastModifiedTime(p)
			            .toMillis();
		} catch(IOException e) {
			return 0;
		}
	}

	@ShellMethod(
			key = "log",
			value = "日志管理：-clear 清理旧日志 / -err [--last] 游戏报错 / -open 打开目录 / -path 显示路径"
	)
	public void log(
			@ShellOption(
					value = "--clear",
					arity = 0,
					defaultValue = "false"
			) boolean clear,
			@ShellOption(
					value = "--err",
					arity = 0,
					defaultValue = "false"
			) boolean err,
			@ShellOption(
					value = "--last",
					arity = 0,
					defaultValue = "false"
			) boolean last,
			@ShellOption(
					value = "--open",
					arity = 0,
					defaultValue = "false"
			) boolean open,
			@ShellOption(
					value = "--path",
					arity = 0,
					defaultValue = "false"
			) boolean pathFlag
	               ) {
		if(pathFlag) {
			log.info("日志目录: {}", AppPaths.LOG_DIR.toAbsolutePath());
			return;
		}
		if(open) {
			openLogDir();
			return;
		}
		if(clear) {
			clear();
			return;
		}
		if(err) {
			showErrors(last);
			return;
		}
		overview();
	}

	/** 概览：启动器日志 + 游戏日志归档数量 */
	private void overview() {
		try {
			Path launcherLog = AppPaths.LOG_DIR.resolve("launcher.log");
			if(Files.isRegularFile(launcherLog)) {
				log.info(
						"启动器日志: {}（{}）", launcherLog.toAbsolutePath(),
						DownloadPool.formatBytes(Files.size(launcherLog))
				        );
			} else {
				log.info("启动器日志尚未生成（{}）", launcherLog.toAbsolutePath());
			}
			List<Path> gameLogs = gameLogs();
			log.info("游戏日志归档: {} 个（{}）", gameLogs.size(), AppPaths.GAME_LOG_DIR.toAbsolutePath());
		} catch(IOException e) {
			log.warn("读取日志目录失败: {}", e.getMessage());
		}
		log.info("可用操作: log -err [--last] 查看游戏报错 / log -clear 清理旧日志 / log -open 打开目录 / log -path");
	}

	/** 清理：启动器滚动日志 + 游戏日志归档（保留当前 launcher.log） */
	private void clear() {
		int count = 0;
		long bytes = 0;
		try {
			if(Files.isDirectory(AppPaths.LOG_DIR)) {
				try(Stream<Path> s = Files.list(AppPaths.LOG_DIR)) {
					for(Path f : s.filter(Files :: isRegularFile)
					              .toList()) {
						String name = f.getFileName()
						               .toString();
						if(name.equals("launcher.log")) {
							continue; // 当前正在写入的启动器日志保留
						}
						bytes += Files.size(f);
						Files.deleteIfExists(f);
						count++;
					}
				}
			}
			if(Files.isDirectory(AppPaths.GAME_LOG_DIR)) {
				try(Stream<Path> s = Files.walk(AppPaths.GAME_LOG_DIR)) {
					for(Path f : s.filter(Files :: isRegularFile)
					              .toList()) {
						bytes += Files.size(f);
						Files.deleteIfExists(f);
						count++;
					}
				}
			}
			log.info(
					"已清理 {} 个日志文件，释放 {}", count,
					DownloadPool.formatBytes(bytes)
			        );
		} catch(IOException e) {
			log.error("清理日志失败: {}", e.getMessage());
		}
	}

	/** 游戏报错：-err 列出含报错的日志；-err --last 打开最近一次的报错内容 */
	private void showErrors(boolean last) {
		List<Path> logs = gameLogs();
		if(logs.isEmpty()) {
			log.info("暂无游戏日志，启动一次游戏后自动归档到 {}", AppPaths.GAME_LOG_DIR.toAbsolutePath());
			return;
		}
		Path newest = logs.getFirst();
		if(!last) {
			log.info("游戏日志（按时间倒序，最多 10 个）:");
			int shown = 0;
			for(Path f : logs) {
				if(shown++ >= 10) {
					break;
				}
				log.info(
						"{} {}", f.getParent()
						          .getFileName() + "/" + f.getFileName(), errorCount(f) + " 处报错"
				        );
			}
			log.info("使用 log -err --last 查看最近一次的报错内容");
			return;
		}
		printErrors(newest);
	}

	/** 打开最近一份游戏日志的报错摘录（ERROR / Exception / 堆栈行），无报错则提示 */
	private void printErrors(Path gameLog) {
		log.info("=== 最近游戏报错（{}）===", gameLog);
		List<String> errors = new ArrayList<>();
		try {
			for(String line : Files.readAllLines(gameLog)) {
				if(line.contains("ERROR") || line.contains("Exception")
				   || line.contains("Caused by") || line.trim()
				                                        .startsWith("at ")) {
					errors.add(line);
					if(errors.size() >= 100) {
						break;
					}
				}
			}
		} catch(IOException e) {
			log.error("读取日志失败: {}", e.getMessage());
			return;
		}
		if(errors.isEmpty()) {
			log.info("该日志未发现报错记录");
			return;
		}
		errors.forEach(line -> log.info("  {}", line));
	}

	/** 收集游戏日志归档（log/game/**），按修改时间倒序 */
	private List<Path> gameLogs() {
		List<Path> logs = new ArrayList<>();
		if(!Files.isDirectory(AppPaths.GAME_LOG_DIR)) {
			return logs;
		}
		try(Stream<Path> s = Files.walk(AppPaths.GAME_LOG_DIR)) {
			s.filter(Files :: isRegularFile)
			 .forEach(logs :: add);
		} catch(IOException e) {
			log.warn("扫描游戏日志失败: {}", e.getMessage());
		}
		logs.sort(Comparator.comparingLong(LogCommands :: modifiedTime)
		                    .reversed());
		return logs;
	}

	private void openLogDir() {
		Path abs = AppPaths.LOG_DIR.toAbsolutePath();
		try {
			Files.createDirectories(abs);
			String os = System.getProperty("os.name", "")
			                  .toLowerCase();
			if(os.contains("win")) {
				new ProcessBuilder("explorer.exe", abs.toString()).start();
			} else if(os.contains("mac")) {
				new ProcessBuilder("open", abs.toString()).start();
			} else {
				new ProcessBuilder("xdg-open", abs.toString()).start();
			}
			log.info("已打开日志目录: {}", abs);
		} catch(IOException e) {
			log.error("打开日志目录失败: {}（路径: {}）", e.getMessage(), abs);
		}
	}
}
