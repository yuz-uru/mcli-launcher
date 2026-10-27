package org.asuka.download;

import java.util.Locale;

/** 游戏本体下载类型（决定 mod loader 安装管线；目录布局与类型无关，均为 PCL 版本隔离） */
public enum DownloadType {
	VANILLA,
	FABRIC,
	FORGE,
	NEOFORGE;

	public static DownloadType of(String s) {
		return switch(s.toLowerCase(Locale.ROOT)) {
			case "vanilla" -> VANILLA;
			case "fabric" -> FABRIC;
			case "forge" -> FORGE;
			case "neoforge" -> NEOFORGE;
			default -> throw new IllegalArgumentException(
					"不支持的下载类型: " + s + "（可选 vanilla / fabric / forge / neoforge）");
		};
	}
}