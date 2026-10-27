package org.asuka.download;

import lombok.extern.slf4j.Slf4j;
import org.asuka.download.model.VersionJson;
import org.asuka.files.AppPaths;
import org.asuka.utils.JsonUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 纯净版下载（versions/<id>/ + 共享 libraries/assets）：version json -> client jar -> libraries -> assets */
@Slf4j
public final class VanillaDownloader {

	private VanillaDownloader() {
	}

	public static Path download(String id) throws IOException, InterruptedException {
		return downloadInto(AppPaths.versionDir(id), id);
	}

	/** 原版基底下载：写入 data/client/<id>/（仅供 mod 继承链使用） */
	public static Path downloadClientBase(String id) throws IOException, InterruptedException {
		return downloadInto(AppPaths.clientBaseDir(id), id);
	}

	private static Path downloadInto(Path dir, String id) throws IOException, InterruptedException {
		DownloadConfig cfg = DownloadConfig.get();
		VersionManifest.Entry entry = VersionManifest.find(id)
		                                             .orElseThrow(() -> new IllegalArgumentException(
				                                             "版本清单中未找到: " + id));

		log.info("开始下载 {}（{}）", entry.id(), entry.type());
		Files.createDirectories(dir);

		String body = Http.getString(cfg.source()
		                                .rewrite(entry.url()));
		VersionJson vj = JsonUtil.gson()
		                         .fromJson(body, VersionJson.class);
		if(vj == null || vj.id == null) {
			throw new IOException("版本 JSON 解析失败: " + entry.url());
		}
		Files.writeString(dir.resolve(vj.id + ".json"), body, StandardCharsets.UTF_8);

		List<DownloadTask> tasks = new ArrayList<>(TaskCollector.fromVersionJson(vj, dir, cfg));

		String indexId = null;
		Path indexFile = null;
		if(vj.assetIndex != null && vj.assetIndex.url != null) {
			indexId = vj.assetIndex.id != null ? vj.assetIndex.id
					: (vj.assets != null ? vj.assets : vj.id);
			indexFile = AppPaths.gameAssets()
			                         .resolve("indexes")
			                         .resolve(indexId + ".json");
			new DownloadPool(cfg).run(List.of(new DownloadTask(
					"资源索引 " + indexId,
					cfg.source()
					   .rewrite(vj.assetIndex.url), indexFile,
					vj.assetIndex.sha1, vj.assetIndex.size
			)));
			tasks.addAll(TaskCollector.fromAssetIndex(indexId, indexFile, cfg));
		}

		new DownloadPool(cfg).run(tasks);
		if(indexFile != null) {
			// pre-1.6 老版本：virtual 索引需要 assets/virtual/<indexId>/ 下的实体文件
			VirtualAssets.materialize(indexId, indexFile);
		}
		log.info("{} 下载完成: {}", id, dir.toAbsolutePath());
		return dir.resolve(vj.id + ".json");
	}
}
