package org.asuka.utils;

/**
 * 启动器自身标识（对外统一）：下载 / 认证的 User-Agent、游戏参数里的 launcher_name 与 launcher_version。
 * 名称与 pom.xml 的 artifactId / finalName 保持一致；部分镜像与 CDN 按 UA 区分流量策略，统一便于排障。
 */
public final class LauncherInfo {

	/** 启动器名（= pom.xml artifactId / finalName） */
	public static final String NAME = "mcli-launcher";
	/** 启动器版本（= pom.xml version） */
	public static final String VERSION = "1.0-SNAPSHOT";
	/** 对外 HTTP User-Agent：DownloadPool / Http 下载与 MinecraftAuth 登录共用同一标识 */
	public static final String USER_AGENT = NAME + "/" + VERSION;

	private LauncherInfo() {
	}
}
