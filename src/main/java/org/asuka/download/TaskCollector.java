package org.asuka.download;

import org.asuka.download.model.VersionJson;
import org.asuka.files.AppPaths;
import org.asuka.utils.JsonUtil;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 把 version json / 资源索引展开为下载任务（libraries/assets 全版本共享） */
public final class TaskCollector {

	private TaskCollector() {
	}

	/** client jar（落在指定版本目录：纯净版 = versions/<id>/，原版基底 = data/client/<id>/）+ libraries（含 natives-<os> classifier） */
	public static List<DownloadTask> fromVersionJson(VersionJson v, Path versionDir, DownloadConfig cfg) {
		List<DownloadTask> tasks = new ArrayList<>();
		String versionId = v.id == null ? "unknown" : v.id;

		VersionJson.DownloadInfo client = v.downloads == null ? null : v.downloads.get("client");
		if(client != null && client.url != null) {
			tasks.add(new DownloadTask(
					"客户端 " + versionId,
					cfg.source()
					   .rewrite(client.url),
					versionDir.resolve(versionId + ".jar"),
					client.sha1, client.size
			));
		}

		for(VersionJson.Library lib : v.libraries == null ? List.<VersionJson.Library> of() : v.libraries) {
			if(!Rules.allowed(lib.rules)) {
				continue;
			}
			if(lib.downloads != null && lib.downloads.artifact != null) {
				VersionJson.DownloadInfo a = lib.downloads.artifact;
				String path = a.path != null ? a.path : mavenPath(lib.name, null);
				tasks.add(new DownloadTask(
						"库 " + lib.name,
						cfg.source()
						   .rewrite(a.url),
						AppPaths.gameLibraries()
						        .resolve(path),
						a.sha1, a.size
				));
			} else if(lib.downloads == null && lib.name != null) {
				String path = mavenPath(lib.name, null);
				String base = lib.url != null ? lib.url : cfg.source()
				                                             .librariesBase();
				tasks.add(new DownloadTask(
						"库 " + lib.name,
						cfg.source()
						   .rewrite(base + path),
						AppPaths.gameLibraries()
						        .resolve(path),
						null, 0
				));
			}
			if(lib.downloads != null && lib.downloads.classifiers != null) {
				String nativesKey = "natives-" + Rules.currentOs();
				VersionJson.DownloadInfo natives = lib.downloads.classifiers.get(nativesKey);
				if(natives != null && natives.url != null) {
					String path = natives.path != null ? natives.path : mavenPath(lib.name, nativesKey);
					tasks.add(new DownloadTask(
							"本地库 " + lib.name,
							cfg.source()
							   .rewrite(natives.url),
							AppPaths.gameLibraries()
							        .resolve(path),
							natives.sha1, natives.size
					));
				}
			}
		}
		return tasks;
	}

	/** 资源索引文件展开为资源文件任务（索引文件需由调用方先下载到位） */
	public static List<DownloadTask> fromAssetIndex(String indexId, Path indexFile, DownloadConfig cfg)
			throws IOException {
		List<DownloadTask> tasks = new ArrayList<>();
		VersionJson.AssetIndex index;
		try(Reader reader = Files.newBufferedReader(indexFile, StandardCharsets.UTF_8)) {
			index = JsonUtil.gson()
			                .fromJson(reader, VersionJson.AssetIndex.class);
		}
		if(index == null || index.objects == null) {
			throw new IOException("资源索引解析失败: " + indexId);
		}
		for(Map.Entry<String, VersionJson.AssetObject> e : index.objects.entrySet()) {
			String hash = e.getValue().hash;
			if(hash == null || hash.length() < 2) {
				continue;
			}
			String h2 = hash.substring(0, 2);
			tasks.add(new DownloadTask(
					"资源 " + e.getKey(),
					cfg.source()
					   .resourcesBase() + h2 + "/" + hash,
					AppPaths.gameAssets()
					        .resolve("objects")
					        .resolve(h2)
					        .resolve(hash),
					hash, e.getValue().size
			));
		}
		return tasks;
	}

	/** maven 坐标 -> 相对路径，可带 classifier（g:a:v[:c] -> g/a/v/a-v[-c].jar） */
	public static String mavenPath(String coord, String classifier) {
		String[] p = coord.split(":");
		if(p.length < 3) {
			throw new IllegalArgumentException("非法 maven 坐标: " + coord);
		}
		String cls = p.length >= 4 ? p[3] : classifier;
		String c = (cls == null || cls.isBlank()) ? "" : "-" + cls;
		return p[0].replace('.', '/') + "/" + p[1] + "/" + p[2] + "/" + p[1] + "-" + p[2] + c + ".jar";
	}
}