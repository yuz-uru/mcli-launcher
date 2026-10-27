package org.asuka.auth;

import com.google.gson.JsonObject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import net.lenni0451.commons.httpclient.HttpClient;
import net.lenni0451.commons.httpclient.exceptions.HttpRequestException;
import net.raphimc.minecraftauth.MinecraftAuth;
import net.raphimc.minecraftauth.java.JavaAuthManager;
import net.raphimc.minecraftauth.java.model.MinecraftProfile;
import net.raphimc.minecraftauth.java.model.MinecraftToken;
import org.asuka.files.AppPaths;
import org.asuka.utils.JsonUtil;
import org.asuka.utils.LauncherInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * 登录会话的加载 / 校验 / 持久化 / 登出。
 * <p>MinecraftAuth 5.x 没有 manager.refresh()：令牌以 Holder 懒加载方式管理，
 * 调用 {@code getMinecraftToken().getUpToDate()} 即可在过期时用 refresh_token 静默续期，
 * 并自动补全 XBL / XSTS / MC 令牌链路。</p>
 */
@Slf4j
public final class SessionManager {

	private static HttpClient httpClient;

	private SessionManager() {
	}

	/** 共享 HttpClient（登录与续期复用同一实例） */
	static synchronized HttpClient httpClient() {
		if(httpClient == null) {
			httpClient = MinecraftAuth.createHttpClient(LauncherInfo.USER_AGENT);
		}
		return httpClient;
	}

	/** 本地是否存在可解析的会话缓存（不做网络校验） */
	public static boolean isAuthed() {
		return loadManager() != null;
	}

	/**
	 * 拿到可用会话：无缓存走设备码登录；有缓存则静默续期，
	 * 续期失败（令牌吊销/断网等）回退到重新登录。
	 */
	public static Session loadOrLogin() throws IOException, InterruptedException, TimeoutException {
		JavaAuthManager manager = loadManager();
		if(manager == null) {
			log.info("未检测到本地会话，开始正版登录");
			manager = MicrosoftOAuth.auth();
			return toSession(manager);
		}
		return toSession(refreshOrRelogin(manager));
	}

	/**
	 * 已有本地会话：静默续期先做短退避重试；仅认证类失败（服务端 HTTP 4xx 明确拒绝凭据）
	 * 才回退设备码重登，网络类错误原样上抛——网络抖动不应让用户平白重走一遍浏览器验证。
	 */
	private static JavaAuthManager refreshOrRelogin(JavaAuthManager manager)
			throws IOException, InterruptedException, TimeoutException {
		final int attempts = 3;
		IOException last = null;
		for(int attempt = 1; attempt <= attempts; attempt++) {
			try {
				manager.getMinecraftToken()
				       .getUpToDate();
				log.info("本地会话有效");
				save(manager);
				return manager;
			} catch(IOException e) {
				last = e;
				if(authRejected(e)) {
					log.warn("本地会话已被服务端拒绝（{}），回退到重新登录", e.getMessage());
					break;
				}
				log.warn("本地会话续期失败（第 {}/{} 次，{}）", attempt, attempts, e.getMessage());
				if(attempt < attempts) {
					Thread.sleep(1500L * attempt);
				}
			}
		}
		if(!authRejected(last)) {
			// 网络类错误：上抛让用户稍后重试，而不是清掉会话重走设备码
			throw last;
		}
		return MicrosoftOAuth.auth();
	}

	/** 失败特征是否为认证类：HTTP 4xx（除 408/429）说明服务端明确拒绝该凭据，重试无意义 */
	private static boolean authRejected(Throwable e) {
		for(Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
			if(t instanceof HttpRequestException hre) {
				int sc = hre.getResponse() == null ? -1 : hre.getResponse().getStatusCode();
				return sc >= 400 && sc < 500 && sc != 408 && sc != 429;
			}
		}
		return false;
	}

	/** 清除本地登录缓存 */
	public static void logout() {
		try {
			if(Files.deleteIfExists(AppPaths.sessionCache())) {
				log.info("已清除本地登录缓存: {}", AppPaths.sessionCache().toAbsolutePath());
			} else {
				log.info("本地无登录缓存");
			}
// 注意：MinecraftAuth 5.0.2 未提供 MSA revoke API（MsaAuthService 只有 acquireToken 系列），
			// 微软侧刷新令牌在过期前仍然有效；如需真正吊销，需另行确认端点与 client_id 后单独实现
		} catch(IOException e) {
			log.error("清除登录缓存失败", e);
		}
	}

	/** 会话存盘（登录成功 / 续期后调用） */
	public static void save(JavaAuthManager manager) {
		if(!JsonUtil.save2Json(JavaAuthManager.toJson(manager), AppPaths.sessionCache())) {
			log.error("会话写入失败: {}", AppPaths.sessionCache().toAbsolutePath());
		}
	}

	private static JavaAuthManager loadManager() {
		if(!Files.isRegularFile(AppPaths.sessionCache())) {
			return null;
		}
		try {
			JsonObject json = JsonUtil.parseJsonObject(AppPaths.sessionCache());
			return JavaAuthManager.fromJson(httpClient(), json);
		} catch(IOException | RuntimeException e) {
			log.warn("本地会话缓存损坏，忽略（{}）", e.getMessage());
			return null;
		}
	}

	private static Session toSession(JavaAuthManager manager) throws IOException {
		MinecraftToken token = manager.getMinecraftToken()
		                              .getUpToDate();
		MinecraftProfile profile = manager.getMinecraftProfile()
		                                  .getUpToDate();
		return new Session(profile.getName(), profile.getId(), token.getToken(), token.getExpireTimeMs(), Session.Mode.MSA);
	}

	/** 启动期可用的会话凭据（供后续启动游戏拼参数使用） */
	public record Session(String name, UUID uuid, String accessToken, long expireAtMs, Mode mode) {

		/** 会话类型：显式区分离线占位 / 正版登录 / dry-run（不再以 accessToken=="0" 之类的魔法值推断） */
		public enum Mode {OFFLINE, MSA, DRY_RUN}
		@NonNull
		@Override
		public String toString() {
			return name + "(" + uuid + ")";
		}
	}
}