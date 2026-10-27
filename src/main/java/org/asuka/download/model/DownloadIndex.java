package org.asuka.download.model;

import java.util.Map;

/**
 * data/download-index.json 的 Gson 模型（public 字段 = JSON 键，勿改）；
 * 放在 download.model 包内，与 VersionJson 一样排除混淆器的成员重命名。
 */
public class DownloadIndex {

	public int version;
	public Map<String, Entry> entries;

	/** 单条校验指纹 */
	public static class Entry {
		public long size;
		public long mtimeMs;
		public String sha1;

		public Entry() {
		}

		public Entry(long size, long mtimeMs, String sha1) {
			this.size = size;
			this.mtimeMs = mtimeMs;
			this.sha1 = sha1;
		}
	}
}
