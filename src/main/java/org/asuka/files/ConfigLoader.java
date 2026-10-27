package org.asuka.files;

import org.apache.commons.configuration2.*;
import org.apache.commons.configuration2.builder.FileBasedConfigurationBuilder;
import org.apache.commons.configuration2.builder.fluent.Parameters;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class ConfigLoader {
	private ConfigLoader() {
	}

	public static Configuration load(Path path) throws ConfigurationException, IOException {
		String ext = extension(path.getFileName()
		                           .toString());

		if("toml".equals(ext)) {
			return TomlConfigLoader.load(path);
		}

		Class<? extends FileBasedConfiguration> configClass = switch(ext) {
			case "properties" -> PropertiesConfiguration.class;
			case "ini" -> INIConfiguration.class;
			case "yaml", "yml" -> YAMLConfiguration.class;
			case "json" -> JSONConfiguration.class;
			default -> throw new IllegalArgumentException("不支持的格式: " + ext);
		};

		return new FileBasedConfigurationBuilder<>(configClass)
				.configure(new Parameters().fileBased()
				                           .setPath(path.toString()))
				.getConfiguration();
	}

	/** 从 classpath 读取配置（resources 内的文件，目前支持 TOML） */
	public static Configuration loadFromResource(String name) throws IOException {
		if(!"toml".equals(extension(name))) {
			throw new IllegalArgumentException("classpath 配置暂仅支持 TOML: " + name);
		}
		InputStream in = ConfigLoader.class.getClassLoader()
		                                   .getResourceAsStream(name);
		if(in == null) {
			throw new IOException("classpath 中未找到配置: " + name);
		}
		// InputStreamReader 不支持 mark()，而 stripBom 需要 mark/reset → 用 BufferedReader 包一层
		try(Reader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			return TomlConfigLoader.parse(reader);
		}
	}

	private static String extension(String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		int dot = lower.lastIndexOf('.');
		return dot < 0 ? "" : lower.substring(dot + 1);
	}

	public static final class TomlConfigLoader {
		private TomlConfigLoader() {
		}

		public static Configuration load(Path path) throws IOException {
			try(Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				return parse(reader);
			}
		}

		public static Configuration parse(Reader reader) throws IOException {
			reader = stripBom(reader);
			TomlParseResult result = Toml.parse(reader);

			if(result.hasErrors()) {
				StringBuilder sb = new StringBuilder("TOML 解析失败:\n");
				result.errors()
				      .forEach(e -> sb.append(e)
				                      .append('\n'));
				throw new IllegalArgumentException(sb.toString());
			}

			Map<String, Object> flat = new LinkedHashMap<>();
			flatten("", toPlain(result.toMap()), flat);

			return new MapConfiguration(flat);
		}

		/** 记事本等编辑器保存的 UTF-8 文件可能带 BOM，tomlj 会把 BOM 当非法字符 → 解析前剥掉 */
		private static Reader stripBom(Reader reader) throws IOException {
			reader.mark(1);
			int first = reader.read();
			if(first != 0xFEFF) {
				reader.reset();
			}
			return reader;
		}

		/** tomlj 的 toMap() 嵌套层仍是 TomlTable/TomlArray，先递归转成纯 Map/List 再拍平 */
		@SuppressWarnings("unchecked")
		private static Map<String, Object> toPlain(Map<String, Object> src) {
			Map<String, Object> plain = new LinkedHashMap<>();
			src.forEach((k, v) -> {
				switch(v) {
					case TomlTable t -> plain.put(k, toPlain(t.toMap()));
					case TomlArray arr -> {
						// TOML 数组 -> 普通 List（元素中的嵌套表同样转纯 Map）
						List<Object> list = new ArrayList<>();
						for(int i = 0; i < arr.size(); i++) {
							Object item = arr.get(i);
							if(item instanceof TomlTable t2) {
								list.add(toPlain(t2.toMap()));
							} else {
								list.add(item);
							}
						}
						plain.put(k, list);
					}
					case Map map -> plain.put(k, toPlain((Map<String, Object>)v));
					case null, default -> plain.put(k, v);
				}
			});
			return plain;
		}

		/** 嵌套表拍平为 a.b.c 键；标量与数组保留原始类型（可直接 getList 取数组） */
		@SuppressWarnings("unchecked")
		private static void flatten(String prefix, Map<String, Object> source, Map<String, Object> target) {
			for(Map.Entry<String, Object> entry : source.entrySet()) {
				String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
				Object value = entry.getValue();
				if(value instanceof Map) {
					flatten(key, (Map<String, Object>)value, target);
				} else {
					target.put(key, value);
				}
			}
		}
	}
}