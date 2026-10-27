package org.asuka.download.model;

import java.util.List;
import java.util.Map;

/**
 * Mojang version json 的 Gson 模型（下载 + 启动所需字段；
 * Fabric meta 的 profile json 同样适用：多 inheritsFrom/libraries，无 downloads.client）。
 */
public class VersionJson {
	public String id;
	public String inheritsFrom;
	public String type;
	public String mainClass;
	public String jar;
	public String assets;
	public Map<String, DownloadInfo> downloads;
	public AssetIndexRef assetIndex;
	public List<Library> libraries;
	public Arguments arguments;
	public String minecraftArguments;
	public Logging logging;
	public JavaVersion javaVersion;

	public static class DownloadInfo {
		public String url;
		public String sha1;
		public long size;
		public String path;
	}

	public static class AssetIndexRef {
		public String id;
		public String url;
		public String sha1;
		public long size;
		public long totalSize;
	}

	public static class Library {
		public String name;
		public String url;
		public LibraryDownloads downloads;
		public List<Rule> rules;
	}

	public static class LibraryDownloads {
		public DownloadInfo artifact;
		public Map<String, DownloadInfo> classifiers;
	}

	public static class Rule {
		public String action;
		public Os os;
		public Map<String, Boolean> features;
	}

	public static class Os {
		public String name;
		public String arch;
	}

	/** arguments.jvm / arguments.game 的元素：value 为字符串或字符串数组，可带 rules */
	public static class Arg {
		public List<Rule> rules;
		public Object value;
	}

	public static class Arguments {
		/** 元素为字符串或 {rules, value} 对象的混合数组，由 VersionResolver 归一化 */
		public List<Object> jvm;
		public List<Object> game;
	}

	public static class Logging {
		public ClientLog client;

		public static class ClientLog {
			public String argument;
			public LogFile file;
		}

		public static class LogFile {
			public String id;
			public String url;
			public String sha1;
			public long size;
		}
	}

	public static class JavaVersion {
		public String component;
		public Integer majorVersion;
	}

	/** assetIndex 指向的资源索引文件结构 */
	public static class AssetIndex {
		public Map<String, AssetObject> objects;
		/** legacy 索引标记：true 时游戏按 assets/virtual/<indexId>/ 相对路径读取资源 */
		public Boolean virtual;
		public Boolean map_to_resources;
	}

	public static class AssetObject {
		public String hash;
		public long size;
	}
}