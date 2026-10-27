package org.asuka.core;

import lombok.extern.slf4j.Slf4j;
import org.asuka.download.Rules;
import org.asuka.files.AppPaths;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipFile;

/**
 * natives 解压（带指纹缓存）：versions/<id>/natives/ 目录 + .fingerprint 指纹文件。
 * natives jar 未变化时直接跳过（不再每次启动重复解压），并清理历史 natives-<时间戳> 目录。
 */
@Slf4j
public final class NativesExtractor {

	private static final String MARKER = ".fingerprint";

	private NativesExtractor() {
	}

	public static Path extract(String versionId, List<Path> nativesJars) throws IOException {
		Path dir = AppPaths.versionDir(versionId)
		                   .resolve("natives");
		String fingerprint = fingerprint(nativesJars);
		Path marker = dir.resolve(MARKER);

		if(Files.isRegularFile(marker) && Files.readString(marker)
		                                       .equals(fingerprint) && hasNativeFiles(dir)) {
			log.info("natives 未变化，跳过解压: {}", dir.toAbsolutePath());
			return dir;
		}

		cleanupLegacyNativeDirs(versionId); // 历史 natives-<时间戳> 目录
		deleteRecursively(dir);             // 指纹变化：清空后重新解压
		Files.createDirectories(dir);

		String ext = switch(Rules.currentOs()) {
			case "windows" -> ".dll";
			case "osx" -> ".dylib";
			default -> ".so";
		};
		for(Path jar : nativesJars) {
			try(ZipFile zip = new ZipFile(jar.toFile())) {
				zip.stream()
				   .filter(e -> !e.isDirectory())
				   .filter(e -> !e.getName()
				                  .contains("META-INF"))
				   .filter(e -> e.getName()
				                 .toLowerCase(Locale.ROOT)
				                 .endsWith(ext))
				   .forEach(entry -> {
					   Path target = dir.resolve(entry.getName())
					                    .normalize();
					   if(!target.startsWith(dir)) {
						   return; // 防 zip slip
					   }
					   try {
						   Files.createDirectories(target.getParent());
						   Files.copy(zip.getInputStream(entry), target, StandardCopyOption.REPLACE_EXISTING);
					   } catch(IOException e) {
						   throw new UncheckedIOException(e);
					   }
				   });
			}
		}
		Files.writeString(marker, fingerprint);
		log.info("natives 解压完成: {}（{} 个 jar）", dir.toAbsolutePath(), nativesJars.size());
		return dir;
	}

	/** 指纹 = 各 natives jar 的文件名 + 大小 + 修改时间 */
	private static String fingerprint(List<Path> jars) {
		StringBuilder sb = new StringBuilder();
		for(Path p : jars) {
			sb.append(p.getFileName())
			  .append(':');
			try {
				sb.append(Files.size(p))
				  .append(':')
				  .append(Files.getLastModifiedTime(p)
				               .toMillis());
			} catch(IOException e) {
				sb.append('?');
			}
			sb.append(';');
		}
		return sb.toString();
	}

	private static boolean hasNativeFiles(Path dir) throws IOException {
		if(!Files.isDirectory(dir)) {
			return false;
		}
		try(var s = Files.list(dir)) {
			return s.anyMatch(f -> Files.isRegularFile(f) && !f.getFileName()
			                                                   .toString()
			                                                   .startsWith("."));
		}
	}

	/** 清理历史遗留的 natives-<时间戳> 目录 */
	private static void cleanupLegacyNativeDirs(String versionId) throws IOException {
		Path vdir = AppPaths.versionDir(versionId);
		if(!Files.isDirectory(vdir)) {
			return;
		}
		try(var s = Files.list(vdir)) {
			for(Path d : s.filter(Files :: isDirectory)
			              .toList()) {
				String name = d.getFileName()
				               .toString();
				if(name.matches("natives-\\d+")) {
					deleteRecursively(d);
					log.debug("已清理历史 natives 目录: {}", name);
				}
			}
		}
	}

	private static void deleteRecursively(Path dir) throws IOException {
		if(!Files.isDirectory(dir)) {
			Files.deleteIfExists(dir);
			return;
		}
		try(var s = Files.list(dir)) {
			for(Path child : s.toList()) {
				deleteRecursively(child);
			}
		}
		Files.deleteIfExists(dir);
	}
}