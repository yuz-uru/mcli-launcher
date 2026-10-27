package org.asuka.cli;

import java.util.List;

/** 当前命令的原始词序列（单次模式 = 进程参数；REPL = 当前行分词），供命令自行解析裸参数 */
public final class CliInputHolder {

	private static final ThreadLocal<List<String>> CURRENT = new ThreadLocal<>();

	private CliInputHolder() {
	}

	public static void set(List<String> tokens) {
		CURRENT.set(tokens == null ? List.of() : List.copyOf(tokens));
	}

	public static List<String> current() {
		List<String> v = CURRENT.get();
		return v == null ? List.of() : v;
	}
}