package org.asuka.cli;

import lombok.extern.slf4j.Slf4j;
import org.asuka.auth.SessionManager;
import org.asuka.core.Launcher;
import org.springframework.shell.standard.ShellCommandGroup;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

/** 启动器相关命令（内部分组 launcher）：login / logout */
@Slf4j
@ShellComponent
@ShellCommandGroup("launcher")
public class LauncherCommands {

	@ShellMethod(
			key = "login",
			value = "正版登录（设备码，首次使用执行）"
	)
	public void login() throws IOException, InterruptedException, TimeoutException {
		Launcher.run();
	}

	@ShellMethod(
			key = "logout",
			value = "清除本地登录缓存"
	)
	public void logout() {
		SessionManager.logout();
	}
}