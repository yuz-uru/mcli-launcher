package org.asuka.cli;

import lombok.extern.slf4j.Slf4j;
import org.jline.reader.History;
import org.jline.reader.impl.history.DefaultHistory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * CLI 历史记录策略：上限 200 条；超过后从顶部（最旧）依次移出 50 条。
 * 通过提供自定义 History Bean 顶替 Spring Shell 默认的 DefaultHistory（@ConditionalOnMissingBean）。
 */
@Slf4j
@Configuration
public class HistoryConfig {

	/** 历史记录上限 */
	public static final int MAX_ENTRIES = 200;
	/** 触发上限后每次从顶部移出的条数 */
	public static final int TRIM_CHUNK = 50;

	@Bean
	public History history() {
		return new ChunkedHistory();
	}

	public static class ChunkedHistory extends DefaultHistory {

		@Override
		public void add(Instant time, String line) {
			super.add(time, line);
			trimChunks();
		}

		@Override
		public void load() throws IOException {
			super.load();
			trimChunks(); // 启动加载历史文件后同样执行策略
		}

		/** 超过 MAX_ENTRIES 后，按 TRIM_CHUNK 的倍数从顶部移出，直到 ≤ MAX_ENTRIES */
		private void trimChunks() {
			int over = size() - MAX_ENTRIES;
			if(over <= 0) {
				return;
			}
			int toRemove = ((over + TRIM_CHUNK - 1) / TRIM_CHUNK) * TRIM_CHUNK;
			List<Entry> kept = new ArrayList<>();

			this.forEach(kept::add);

			kept = new ArrayList<>(kept.subList(Math.min(toRemove, kept.size()), kept.size()));
			try {
				purge();
				for(Entry e : kept) {
					super.add(e.time(), e.line());
				}
				log.debug("历史记录裁剪：移出 {} 条，保留 {} 条", toRemove, size());
			} catch(IOException e) {
				log.warn("历史记录裁剪失败: {}", e.getMessage());
			}
		}
	}
}