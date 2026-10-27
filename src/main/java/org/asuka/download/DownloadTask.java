package org.asuka.download;

import java.nio.file.Path;

/** 单个文件的下载任务 */
public record DownloadTask(String name, String url, Path target, String sha1, long size) {
}