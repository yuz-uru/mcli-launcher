package org.asuka.cli;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** 用户习惯的单横选项（-list/-clear/-err/...）统一映射为 Spring Shell 的双横键 */
public final class OptionNormalizer {

	private static final Set<String> SINGLE_DASH =
			Set.of("-list", "-clear", "-err", "-last", "-open", "-path", "-f", "-nf", "-fb", "-allow-mp");

	private OptionNormalizer() {
	}

	public static String normalize(String token) {
		return token != null && SINGLE_DASH.contains(token) ? "--" + token.substring(1) : token;
	}

	public static List<String> normalize(List<String> words) {
		return words.stream()
		            .map(OptionNormalizer :: normalize)
		            .toList();
	}

	public static String[] normalize(String[] args) {
		return Arrays.stream(args)
		             .map(OptionNormalizer :: normalize)
		             .toArray(String[] :: new);
	}
}