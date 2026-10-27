package org.asuka.download;

import lombok.extern.slf4j.Slf4j;
import org.asuka.download.model.VersionJson;
import org.asuka.files.AppPaths;
import org.asuka.utils.JsonUtil;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/**
 * legacy 资源虚拟目录：assetIndex 带 virtual / map_to_resources 标记时（pre-1.6 老版本），
 * 把 objects/<h2>/<hash> 硬链接（不支持则复制）到 assets/virtual/<indexId>/<相对路径>，
 * 供游戏按文件名直接读取音效、语言文件等资源。
 */
@Slf4j
public final class VirtualAssets {

	private VirtualAssets() {
	}

	/** 生成虚拟资源目录；返回本次新建（链接 / 复制）的文件数；非 virtual 索引返回 0 */
	public static int materialize(String indexId, Path indexFile) throws IOException {
		if(!Files.isRegularFile(indexFile)) {
			return 0;
		}
		VersionJson.AssetIndex index;
		try(Reader reader = Files.newBufferedReader(indexFile, StandardCharsets.UTF_8)) {
			index = JsonUtil.gson()
			                .fromJson(reader, VersionJson.AssetIndex.class);
		}
		if(index == null || index.objects == null) {
			return 0;
		}
		if(!Boolean.TRUE.equals(index.virtual) && !Boolean.TRUE.equals(index.map_to_resources)) {
			return 0;
		}

		Path objects = AppPaths.gameAssets()
		                       .resolve("objects");
		Path virtualRoot = AppPaths.gameAssets()
		                           .resolve("virtual")
		                           .resolve(indexId);
		int created = 0;
		int missing = 0;
		for(Map.Entry<String, VersionJson.AssetObject> e : index.objects.entrySet()) {
			VersionJson.AssetObject obj = e.getValue();
			String hash = obj == null ? null : obj.hash;
			if(hash == null || hash.length() < 2) {
				continue;
			}
			String relative = e.getKey()
			                   .replace('\\', '/');
			// 索引文件来自网络：与解压同理，拒绝绝对路径 / 上跳
			if(relative.startsWith("/") || relative.contains("..")) {
				log.warn("资源索引中存在非法路径，已跳过: {}", relative);
				continue;
			}
			Path src = objects.resolve(hash.substring(0, 2))
			                  .resolve(hash);
			if(!Files.isRegularFile(src)) {
				missing++;
				continue;
			}
			Path dst = virtualRoot.resolve(relative);
			if(Files.isRegularFile(dst) && Files.size(dst) == obj.size) {
				continue;
			}
			Files.createDirectories(dst.getParent());
			Files.deleteIfExists(dst);
			try {
				Files.createLink(dst, src);
			} catch(Exception linkFailed) {
				Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
			}
			created++;
		}
		log.info(
				"legacy 虚拟资源目录: {}（新建 {} 个{}）", virtualRoot, created,
				missing > 0 ? "，objects 中缺失 " + missing + " 个" : ""
		        );
		return created;
	}
}
