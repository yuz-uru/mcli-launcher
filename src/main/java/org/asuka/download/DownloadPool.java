package org.asuka.download;

import lombok.extern.slf4j.Slf4j;
import org.asuka.files.AppPaths;
import org.asuka.utils.LauncherInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 并发下载器（高稳定版）：
 * - 小文件多线程直下；大文件按 Range 分段并行（各段独立重试），失败回退单流（支持断点续传）
 * - 4xx（除 408/429）快速失败；指数退避 + 抖动；主源耗尽后自动切换镜像源（failover）
 * - 运行前清扫历史 .part 临时文件；已存在且校验通过的文件跳过（幂等）
 */
@Slf4j
public final class DownloadPool {

	private final DownloadConfig cfg;

	public DownloadPool(DownloadConfig cfg) {
		this.cfg = cfg;
	}

	/** 目标文件已存在且校验通过（sha1 / size） */
	public static boolean isValid(Path p, String sha1, long size) throws IOException {
		if(!Files.isRegularFile(p) || Files.size(p) == 0) {
			return false;
		}
		long actualSize = Files.size(p);
		if(size > 0 && actualSize != size) {
			return false;
		}
		if(sha1 == null || sha1.isBlank()) {
			return true;
		}
		long mtimeMs = Files.getLastModifiedTime(p)
		                    .toMillis();
		if(FingerprintCache.matches(p, sha1, actualSize, mtimeMs)) {
			return true;
		}
		String actual = sha1Hex(p);
		if(!sha1.equalsIgnoreCase(actual)) {
			return false;
		}
		FingerprintCache.record(p, actual, actualSize, mtimeMs);
		return true;
	}


	public static String sha1Hex(Path p) throws IOException {
		MessageDigest md;
		try {
			md = MessageDigest.getInstance("SHA-1");
		} catch(NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
		try(InputStream in = Files.newInputStream(p)) {
			byte[] buf = new byte[256 * 1024];
			int n;
			while((n = in.read(buf)) != -1) {
				md.update(buf, 0, n);
			}
		}
		return HexFormat.of()
		                .formatHex(md.digest());
	}

	public static String formatBytes(long bytes) {
		if(bytes < 1024) {
			return bytes + " B";
		}
		double v = bytes;
		for(String unit : new String[]{"KB", "MB", "GB"}) {
			v /= 1024;
			if(v < 1024) {
				return String.format(Locale.ROOT, "%.1f %s", v, unit);
			}
		}
		return String.format(Locale.ROOT, "%.1f TB", v);
	}

	/**
	 * 请求超时按期望大小动态计算：128 KB/s 速率下限 + 30 秒缓冲（下限 60 秒、上限 1 小时）。
	 * 固定 10 分钟对大文件慢链路必然假超时（400MB 在 2MB/s 以下就会超）→ 假超时-重试死循环；
	 * 期望大小未知时沿用 10 分钟；连接建立超时由共享 HttpClient 的 connectTimeout（15 秒）单独控制。
	 */
	private static Duration requestTimeout(long expectedBytes) {
		if(expectedBytes <= 0) {
			return Duration.ofMinutes(10);
		}
		long seconds = expectedBytes / (128L * 1024L) + 30;
		return Duration.ofSeconds(Math.clamp(seconds, 60L, 3600L));
	}

	public void run(List<DownloadTask> taskList) throws IOException, InterruptedException {
		if(taskList == null || taskList.isEmpty()) {
			return;
		}
		List<DownloadTask> tasks = taskList.stream()
		                                   .collect(java.util.stream.Collectors.collectingAndThen(
				                                   java.util.stream.Collectors.toMap(
						                                   DownloadTask :: target, t -> t, (a, b) -> {
							                                   log.debug(
									                                   "目标重复，保留先出现的任务: {}（丢弃 {}）",
									                                   a.target(), b.url()
							                                            );
							                                   return a;
						                                   },
						                                   java.util.LinkedHashMap :: new
				                                                                    ),
				                                   m -> new ArrayList<>(m.values())
		                                                                                         ));
		int parallelism = Math.min(cfg.threads(), tasks.size());
		long totalBytes = tasks.stream()
		                       .mapToLong(t -> Math.max(0, t.size()))
		                       .sum();
		int step = Math.max(1, tasks.size() / 10);

		ensureDiskSpace(pendingBytes(tasks));

		sweepPartFiles(tasks);

		AtomicInteger done = new AtomicInteger();
		AtomicInteger skipped = new AtomicInteger();
		AtomicInteger failed = new AtomicInteger();
		Queue<String> failures = new ConcurrentLinkedQueue<>();

		log.info("待处理 {} 个文件（约 {}），并发 {} 线程", tasks.size(), formatBytes(totalBytes), parallelism);
		// 分段下载用独立线程池（不占用 ForkJoinPool.commonPool：那是阻塞 HTTP IO）
		try(
				ExecutorService pool = Executors.newFixedThreadPool(parallelism);
				ExecutorService segPool = Executors.newFixedThreadPool(Math.max(1, cfg.bigFileSegments()))
		) {
			List<Callable<Void>> callables = tasks.stream()
			                                      .<Callable<Void>> map(t -> () -> {
				                                      try {
					                                      if(isValid(t.target(), t.sha1(), t.size())) {
						                                      skipped.incrementAndGet();
					                                      } else {
						                                      downloadOne(t, segPool);
						                                      done.incrementAndGet();
					                                      }
				                                      } catch(Exception e) {
					                                      failed.incrementAndGet();
					                                      failures.add(t.name() + ": " + e.getMessage());
				                                      }
				                                      int processed = done.get() + skipped.get() + failed.get();
				                                      if(processed % step == 0 || processed == tasks.size()) {
					                                      log.info(
							                                      "进度 {}/{}（成功 {}, 跳过 {}, 失败 {}）",
							                                      processed, tasks.size(), done.get(), skipped.get(),
							                                      failed.get()
					                                              );
				                                      }
				                                      return null;
			                                      })
			                                      .toList();
			for(Future<Void> f : pool.invokeAll(callables)) {
				f.get();
			}
		} catch(ExecutionException e) {
			throw new IOException("下载线程异常: " + e.getMessage(), e);
		} finally {
			// 批次结束统一落盘校验指纹，避免逐文件写盘
			FingerprintCache.saveIfDirty();
		}

		log.info("下载结束: 成功 {}, 跳过 {}, 失败 {}", done.get(), skipped.get(), failed.get());
		if(!failures.isEmpty()) {
			StringBuilder sb = new StringBuilder().append(failures.size())
			                                      .append(" 个文件下载失败:");
			failures.stream()
			        .limit(5)
			        .forEach(msg -> sb.append("\n  - ")
			                          .append(msg));
			if(failures.size() > 5) {
				sb.append("\n  ... 共 ")
				  .append(failures.size())
				  .append(" 个");
			}
			throw new IOException(sb.toString());
		}
	}

	/** 待下载字节数（已存在且校验通过的文件不计入） */
	private static long pendingBytes(List<DownloadTask> tasks) {
		long pending = 0;
		for(DownloadTask t : tasks) {
			try {
				if(!isValid(t.target(), t.sha1(), t.size())) {
					pending += Math.max(0, t.size());
				}
			} catch(IOException e) {
				pending += Math.max(0, t.size());
			}
		}
		return pending;
	}

	/** 预检所需空间：待下载字节 + 20% 余量（文件系统元数据 / 碎片） */
	static long requiredSpace(long pendingBytes) {
		return pendingBytes + pendingBytes / 5;
	}

	/** 下载前磁盘空间预检：不足时直接拒绝，避免下到 90% 磁盘爆掉产生一批半成品 */
	private static void ensureDiskSpace(long pending) throws IOException {
		if(pending <= 0) {
			return;
		}
		Path root = AppPaths.gameDir();
		try {
			Files.createDirectories(root);
		} catch(IOException e) {
			log.debug("创建游戏目录失败（忽略）: {}", e.getMessage());
		}
		FileStore store;
		try {
			store = Files.getFileStore(root);
		} catch(IOException e) {
			log.warn("无法获取磁盘可用空间，跳过预检: {}", e.getMessage());
			return;
		}
		long usable = store.getUsableSpace();
		long required = requiredSpace(pending);
		if(usable < required) {
			throw new IOException("磁盘空间不足: 需要约 " + formatBytes(required) + "（待下载 " + formatBytes(pending)
			                      + "），可用 " + formatBytes(usable) + " @" + root.toAbsolutePath());
		}
		log.debug("磁盘预检通过: 可用 {}, 需要 {}", formatBytes(usable), formatBytes(required));
	}

	/** 清扫任务目标目录下的历史 .part / .part.N 临时文件（上次中断遗留） */

	private void sweepPartFiles(List<DownloadTask> tasks) {
		Set<Path> dirs = new HashSet<>();
		for(DownloadTask t : tasks) {
			Path parent = t.target()
			               .getParent();
			if(parent != null) {
				dirs.add(parent);
			}
		}
		for(Path d : dirs) {
			try(var stream = Files.list(d)) {
				for(Path f : stream.filter(Files :: isRegularFile)
				                   .toList()) {
					String n = f.getFileName()
					            .toString();
					if(n.endsWith(".part") || n.contains(".part.")) {
						Files.deleteIfExists(f);
					}
				}
			} catch(IOException e) {
				log.debug("清扫 .part 失败（忽略）: {}", e.getMessage());
			}
		}
	}

	private void downloadOne(DownloadTask t, ExecutorService segPool) throws IOException, InterruptedException {
		Files.createDirectories(t.target()
		                         .getParent());
		Path tmp = t.target()
		            .resolveSibling(t.target()
		                             .getFileName() + ".part");

		IOException last = null;
// 候选地址：主源 + 镜像源（failover）
		for(String url : candidateUrls(t.url())) {
			try {
				downloadFrom(url, t, tmp, segPool);
				verifyAndMove(tmp, t);
				return;
			} catch(HttpPermanentException e) {
				log.warn("不可重试的错误，切换下载源: {}（{}）", t.name(), e.getMessage());
				last = e;
			} catch(IOException e) {
				log.warn("下载源失败，尝试下一来源: {}（{}）", t.name(), e.getMessage());
				last = e;
			}
		}
		Files.deleteIfExists(tmp);
		throw new IOException("下载失败: " + t.url() + (last == null ? "" : "（" + last.getMessage() + "）"), last);
	}

	/** 候选地址：主源 + 镜像源（failover 开启且可映射时） */
	private List<String> candidateUrls(String primaryUrl) {
		List<String> urls = new ArrayList<>();
		urls.add(primaryUrl);
		String mirror = cfg.failoverUrl(primaryUrl);
		if(mirror != null && !mirror.equals(primaryUrl)) {
			urls.add(mirror);
		}
		return urls;
	}

	/** 把文件取到 tmp（大文件分段并行，失败回退单流断点续传）；校验与挪位由调用方执行 */
	private void downloadFrom(String url, DownloadTask t, Path tmp, ExecutorService segPool)
			throws IOException, InterruptedException {
		if(cfg.bigFileSegments() > 1 && t.size() >= cfg.bigFileThresholdBytes()) {
			try {
				if(downloadSegmented(url, t, tmp, segPool)) {
					return;
				}
			} catch(IOException e) {
				log.debug("分段下载回退单流: {}（{}）", t.name(), e.getMessage());
			}
		}
		IOException last = null;
		for(int attempt = 1; attempt <= cfg.retries(); attempt++) {
			try {
				long resumeFrom = attempt > 1 && Files.exists(tmp) ? Files.size(tmp) : -1;
				fetchToFile(url, tmp, resumeFrom, t.size());
				return;
			} catch(HttpPermanentException e) {
				throw e;
			} catch(IOException e) {
				last = e;
				if(attempt < cfg.retries()) {
					long backoff = 400L * (1L << (attempt - 1)) + ThreadLocalRandom.current()
					                                                               .nextLong(300);
					log.warn("重试 {}/{}: {}（{}）", attempt, cfg.retries(), t.name(), e.getMessage());
					Thread.sleep(backoff);
				}
			}
		}
		throw last != null ? last : new IOException("未知下载错误");
	}

	/**
	 * 大文件分段并行：并行发射全部段，失败段按重试次数逐段重试；全部成功后拼接为 tmp。
	 * 任一段最终失败抛 IOException（调用方回退单流）；分段文件在 finally 中清理。
	 * 大文件分段并行：预分配目标文件，各段用 FileChannel 按偏移直写（省掉段文件与二次合并拷贝），
	 * 失败段按重试次数逐段重试；任一段最终失败抛 IOException（调用方回退单流）。
	 */
	private boolean downloadSegmented(String url, DownloadTask t, Path tmp, ExecutorService segPool)
			throws IOException, InterruptedException {
		long size = t.size();
		int segments = Math.clamp(size / (1024L * 1024L), 1, cfg.bigFileSegments());
		if(segments <= 1) {
			return false;
		}
		long chunk = size / segments;
		List<Segment> segs = new ArrayList<>();
		long start = 0;
		for(int i = 0; i < segments; i++) {
			long end = (i == segments - 1) ? size - 1 : start + chunk - 1;
			segs.add(new Segment(start, end - start + 1, tmp));
			start = end + 1;
		}

		boolean ok = false;
		try {
			// 预分配为完整大小（稀疏文件），各段 position(offset) 直写 → 磁盘 IO 减半
			try(RandomAccessFile raf = new RandomAccessFile(tmp.toFile(), "rw")) {
				raf.setLength(size);
			}
			List<Segment> pending = new ArrayList<>(segs);
			for(int attempt = 0; attempt < cfg.retries() && !pending.isEmpty(); attempt++) {
				List<Future<Void>> futures = new ArrayList<>();
				for(Segment seg : pending) {
					futures.add(segPool.submit(() -> {
						fetchSegment(url, seg);
						return null;
					}));
				}
				List<Segment> still = new ArrayList<>();
				Iterator<Segment> it = pending.iterator();
				for(Future<Void> f : futures) {
					Segment seg = it.next();
					try {
						f.get();
					} catch(ExecutionException e) {
						seg.error = e.getCause() instanceof IOException ioe
								? ioe : new IOException(String.valueOf(e.getCause()));
					}
					if(seg.error != null) {
						still.add(seg);
					}
				}
				pending = still;
				if(!pending.isEmpty() && attempt + 1 < cfg.retries()) {
					Thread.sleep(500L * (attempt + 1));
				}
			}
			if(!pending.isEmpty()) {
				Segment bad = pending.getFirst();
				throw new IOException("分段下载失败（" + pending.size() + " 段）: " + bad.error.getMessage(), bad.error);
			}
			ok = true;
			return true;
		} finally {
			if(!ok) {
				// 预分配文件留着会误导单流续传（大小已等于完整大小），必须清掉
				Files.deleteIfExists(tmp);
			}
		}
	}

	/** 单段下载（Range），206 视为成功；4xx 快速失败 */
	/** 单段下载（Range 直写预分配文件的偏移区间），206 视为成功；4xx 快速失败 */
	private void fetchSegment(String url, Segment seg) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			                                 .timeout(requestTimeout(seg.length))
			                                 .header("User-Agent", LauncherInfo.USER_AGENT)
			                                 .header(
					                                 "Range",
					                                 "bytes=" + seg.start + "-" + (seg.start + seg.length - 1)
			                                        )
			                                 .GET()
			                                 .build();
			HttpResponse<InputStream> resp = Http.client()
			                                     .send(request, HttpResponse.BodyHandlers.ofInputStream());
			int sc = resp.statusCode();
			if(sc != 206) {
				seg.error = sc >= 400 && sc < 500 && sc != 408 && sc != 429
						? new HttpPermanentException("HTTP " + sc)
						: new IOException("HTTP " + sc + "（分段要求 206）");
				return;
			}
			try(
					InputStream in = resp.body();
					FileChannel ch = FileChannel.open(seg.file, StandardOpenOption.WRITE)
			) {
				ch.position(seg.start);
				byte[] buf = new byte[64 * 1024];
				long written = 0;
				int n;
				while((n = in.read(buf)) != -1) {
					ByteBuffer bb = ByteBuffer.wrap(buf, 0, n);
					while(bb.hasRemaining()) {
						int ignored = ch.write(bb);
					}
					written += n;
				}
				if(written != seg.length) {
					seg.error = new IOException("分段长度不符: 期望 " + seg.length + "，实际 " + written);
				}
			}
		} catch(IOException e) {
			seg.error = e;
		} catch(InterruptedException e) {
			Thread.currentThread()
			      .interrupt();
			seg.error = new IOException("中断", e);
		}
	}

	/**
	 * 单流下载；resumeFrom >= 0 时尝试断点续传（Range 追加），
	 * 服务器忽略 Range 返回 200 则整文件重下。
	 */
	private void fetchToFile(String url, Path tmp, long resumeFrom, long expectedBytes)
			throws IOException, InterruptedException {
		boolean resume = resumeFrom >= 0 && Files.isRegularFile(tmp) && Files.size(tmp) > 0;
		long expect = expectedBytes > 0 && resume ? expectedBytes - resumeFrom : expectedBytes;
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
		                                         .timeout(requestTimeout(expect))
		                                         .header("User-Agent", LauncherInfo.USER_AGENT);
		if(resume) {
			builder.header("Range", "bytes=" + resumeFrom + "-");
		}
		HttpResponse<Path> resp = Http.client()
		                              .send(
				                              builder.GET()
				                                     .build(),
				                              HttpResponse.BodyHandlers.ofFile(
						                              tmp, resume
								                              ? new StandardOpenOption[]{
								                              StandardOpenOption.CREATE, StandardOpenOption.APPEND,
								                              StandardOpenOption.WRITE
						                              }
								                              : new StandardOpenOption[]{
								                              StandardOpenOption.CREATE,
								                              StandardOpenOption.TRUNCATE_EXISTING,
								                              StandardOpenOption.WRITE
						                              }
				                                                              )
		                                   );
		int sc = resp.statusCode();
		if(resume && sc == 200) {
			fetchToFile(url, tmp, -1, expectedBytes); // 服务器不支持续传：整文件重下
			return;
		}
		if(sc >= 200 && sc < 300) {
			return;
		}
		if(sc >= 400 && sc < 500 && sc != 408 && sc != 429) {
			throw new HttpPermanentException("HTTP " + sc);
		}
		throw new IOException("HTTP " + sc);
	}

	/** 校验 sha1 / 大小后从 .part 原子挪到目标位置 */
	private void verifyAndMove(Path tmp, DownloadTask t) throws IOException {
		if(t.sha1() != null && !t.sha1()
		                         .isBlank() && !t.sha1()
		                                         .equalsIgnoreCase(sha1Hex(tmp))) {
			throw new IOException("sha1 不匹配");
		}
		if(t.size() > 0 && Files.size(tmp) != t.size()) {
			throw new IOException("文件大小不符（期望 " + t.size() + "，实际 " + Files.size(tmp) + "）");
		}
		Files.move(tmp, t.target(), StandardCopyOption.REPLACE_EXISTING);
		if(t.sha1() != null && !t.sha1()
		                         .isBlank()) {
			try {
				Path target = t.target();
				FingerprintCache.record(
						target, t.sha1(), Files.size(target), Files.getLastModifiedTime(target)
						                                           .toMillis()
				                       );
			} catch(IOException e) {
				log.debug("指纹记录失败（忽略）: {}", e.getMessage());
			}
		}
	}

	/** 单个分段（可变 error 供并行重试回写
	 * 单个分段（可变 error 供并行重试回写；各段共享同一个预分配目标文件，按 start 偏移直写）
	 */
	private static final class Segment {
		final long start;
		final long length;
		final Path file;
		volatile IOException error;

		Segment(long start, long length, Path file) {
			this.start = start;
			this.length = length;
			this.file = file;
		}
	}
}