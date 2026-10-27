package org.asuka.download;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import org.asuka.files.AppPaths;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 官方版本清单（带本地缓存，缓存时长见 download.toml 的 manifestCacheMinutes） */
@Slf4j
public final class VersionManifest {

	private static final Gson GSON = new Gson();
	private static List<Entry> cache;

	private VersionManifest() {
	}

	public static synchronized List<Entry> list(boolean forceRefresh) throws IOException, InterruptedException {
		if(cache != null && !forceRefresh) {
			return cache;
		}
		DownloadConfig cfg = DownloadConfig.get();
		Path cacheFile = AppPaths.VERSION_MANIFEST_CACHE;

		if(!forceRefresh && Files.isRegularFile(cacheFile)) {
			long ageMinutes = (System.currentTimeMillis() - Files.getLastModifiedTime(cacheFile)
			                                                     .toMillis()) / 60000L;
			if(ageMinutes < cfg.manifestCacheMinutes()) {
				try(Reader reader = Files.newBufferedReader(cacheFile, StandardCharsets.UTF_8)) {
					Manifest m = GSON.fromJson(reader, Manifest.class);
					if(m != null && m.versions() != null) {
						log.info(
								"使用本地清单缓存: {} 个版本", m.versions()
								                                .size()
						        );
						cache = m.versions();
						return cache;
					}
				} catch(RuntimeException e) {
					log.warn("清单缓存损坏，重新拉取（{}）", e.getMessage());
				}
			}
		}

		String body = Http.getString(cfg.source()
		                                .rewrite(cfg.source()
		                                            .manifest()));
		Files.createDirectories(cacheFile.getParent());
		Files.writeString(cacheFile, body, StandardCharsets.UTF_8);
		Manifest m = GSON.fromJson(body, Manifest.class);
		if(m == null || m.versions() == null) {
			throw new IOException("版本清单解析失败");
		}
		log.info(
				"版本清单已更新: {} 个版本（最新正式版 {}）", m.versions()
				                                             .size(),
				m.latest() != null ? m.latest()
				                      .get("release") : "?"
		        );
		cache = m.versions();
		return cache;
	}

	public static Optional<Entry> find(String id) throws IOException, InterruptedException {
		return list(false).stream()
		                  .filter(e -> e.id()
		                                .equals(id))
		                  .findFirst();
	}

	public record Entry(String id, String type, String url, String sha1, String releaseTime) {
	}

	private record Manifest(Map<String, String> latest, List<Entry> versions) {
	}
}