package org.asuka.download;

import org.asuka.download.model.VersionJson;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** version json 中 rules 规则（os / features）的求值 */
public final class Rules {

	private Rules() {
	}

	/** 下载期：不开启任何 feature */
	public static boolean allowed(List<VersionJson.Rule> rules) {
		return allowed(rules, Map.of());
	}

	/** 逐条匹配，最后一条命中的规则决定 allow / disallow */
	public static boolean allowed(List<VersionJson.Rule> rules, Map<String, Boolean> features) {
		if(rules == null || rules.isEmpty()) {
			return true;
		}
		boolean allowed = false;
		for(VersionJson.Rule rule : rules) {
			if(applies(rule, features)) {
				allowed = "allow".equals(rule.action);
			}
		}
		return allowed;
	}

	private static boolean applies(VersionJson.Rule rule, Map<String, Boolean> features) {
		VersionJson.Os os = rule.os;
		if(os != null) {
			if(os.name != null && !os.name.equals(currentOs())) {
				return false;
			}
			if(os.arch != null && !archMatches(os.arch)) {
				return false;
			}
		}
		Map<String, Boolean> ruleFeatures = rule.features;
		if(ruleFeatures != null) {
			for(Map.Entry<String, Boolean> e : ruleFeatures.entrySet()) {
				boolean enabled = features != null && Boolean.TRUE.equals(features.get(e.getKey()));
				boolean required = Boolean.TRUE.equals(e.getValue());
				if(enabled != required) {
					return false;
				}
			}
		}
		return true;
	}

	public static String currentOs() {
		String os = System.getProperty("os.name", "")
		                  .toLowerCase(Locale.ROOT);
		if(os.contains("win")) {
			return "windows";
		}
		if(os.contains("mac") || os.contains("osx")) {
			return "osx";
		}
		return "linux";
	}

	private static boolean archMatches(String ruleArch) {
		String arch = System.getProperty("os.arch", "")
		                    .toLowerCase(Locale.ROOT);
		boolean is32Bit = arch.equals("x86") || arch.startsWith("i3") || arch.startsWith("i5") || arch.startsWith(
				"i8");
		return "x86".equalsIgnoreCase(ruleArch) == is32Bit;
	}
}