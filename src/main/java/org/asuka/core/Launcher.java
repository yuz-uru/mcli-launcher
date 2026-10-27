package org.asuka.core;

import lombok.extern.slf4j.Slf4j;
import org.asuka.auth.SessionManager;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.TimeoutException;

/**
 * 启动编排入口：会话闭环（无缓存登录 / 过期静默续期）→ 后续接入版本下载与启动。
 */
@Slf4j
public final class Launcher {
	private Launcher() {
	}

	public static void run() throws IOException, InterruptedException, TimeoutException {
		SessionManager.Session session = SessionManager.loadOrLogin();
		log.info("当前正版账号: {} ({})", session.name(), session.uuid());
		log.info(
				"访问令牌有效期至: {}", Instant.ofEpochMilli(session.expireAtMs())
				                               .atZone(ZoneId.systemDefault())
		        );
// 版本下载与游戏启动分别由 download / play 命令提供
	}
}