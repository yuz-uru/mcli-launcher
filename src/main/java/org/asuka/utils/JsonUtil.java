package org.asuka.utils;

import com.google.gson.*;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
public final class JsonUtil {

	private static final Gson GSON = new GsonBuilder()
			.setPrettyPrinting()
			.disableHtmlEscaping()
			.create();

	private JsonUtil() {
	}

	/** 共享 Gson 实例（download 模块解析 version json / 清单等复用） */
	public static Gson gson() {
		return GSON;
	}

	public static <T> T parseJson(Path path, Class<T> type) throws IOException {
		try(var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			return GSON.fromJson(reader, type);
		}
	}

	/** 读取整个 JSON 对象（登录会话等结构化缓存用，避免按类强转） */
	public static JsonObject parseJsonObject(Path path) throws IOException {
		try(var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			return JsonParser.parseReader(reader)
			                 .getAsJsonObject();
		} catch(JsonParseException | IllegalStateException e) {
			throw new IOException("JSON 解析失败: " + path, e);
		}
	}

	public static String toJson(Object src) {
		return GSON.toJson(src);
	}

	/** 写入指定路径；失败会记录日志并返回 false（不再静默吞异常） */
	public static <T> boolean save2Json(T src, Path target) {
		try {
			if(target.getParent() != null) {
				Files.createDirectories(target.getParent());
			}
			Files.writeString(target, toJson(src), StandardCharsets.UTF_8);
			return true;
		} catch(IOException e) {
			log.error("JSON 写入失败: {}", target, e);
			return false;
		}
	}
}