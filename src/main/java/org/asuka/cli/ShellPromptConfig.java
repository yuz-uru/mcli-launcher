package org.asuka.cli;

import org.jline.utils.AttributedString;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.shell.jline.PromptProvider;

/** 提示符定制：Spring Shell 3.3 起 interactive.pattern 属性已移除，只能用 PromptProvider Bean */
@Configuration
public class ShellPromptConfig {

	@Bean
	public PromptProvider promptProvider() {
		return () -> new AttributedString("launcher> ");
	}
}