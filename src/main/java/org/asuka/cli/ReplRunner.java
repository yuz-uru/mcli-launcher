package org.asuka.cli;

import lombok.extern.slf4j.Slf4j;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import org.jline.reader.UserInterruptException;
import org.jline.utils.AttributedString;
import org.springframework.core.annotation.Order;
import org.springframework.shell.*;
import org.springframework.shell.context.InteractionMode;
import org.springframework.shell.context.ShellContext;
import org.springframework.shell.jline.PromptProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * 交互 ShellRunner（优先级高于内置 InteractiveShellRunner）：
 * 行为一致，额外实现"空行 / 纯空白输入 → 默认执行 help"。
 * 带参数时返回 false，交给非交互 runner（脚本化单次执行不受影响）。
 */
@Slf4j
@Component
@Order(-10)
public class ReplRunner implements ShellRunner {
	private final LineReader lineReader;
	private final PromptProvider promptProvider;
	private final Shell shell;
	private final ShellContext shellContext;

	private final GameCommands gameCommands;

	public ReplRunner(
			LineReader lineReader, PromptProvider promptProvider, Shell shell, ShellContext shellContext,
			GameCommands gameCommands
	                 ) {
		this.gameCommands = gameCommands;
		this.lineReader = lineReader;
		this.promptProvider = promptProvider;
		this.shell = shell;
		this.shellContext = shellContext;
	}

	@Override
	public boolean run(String[] args) throws Exception {
		if(args != null && args.length > 0) {
			return false; // 有命令参数：交给非交互 runner
		}
		shellContext.setInteractionMode(InteractionMode.INTERACTIVE);
		shell.run(new BlankHelpInputProvider(lineReader, promptProvider, gameCommands));
		return true;
	}

	/** 内置 JLineInputProvider 的等价实现 + 空行映射为 help + -list 统一为 --list */
	private record BlankHelpInputProvider(LineReader lineReader, PromptProvider promptProvider,
	                                      GameCommands gameCommands)
			implements InputProvider {

		@Override
		public Input readInput() {
			try {
				AttributedString prompt = promptProvider.getPrompt();
				lineReader.readLine(prompt.toAnsi(lineReader.getTerminal()));
			} catch(UserInterruptException e) {
				if(e.getPartialLine()
				    .isEmpty()) {
					throw new ExitRequest(1);
				}
				return Input.EMPTY;
			} catch(EndOfFileException e) {
				throw new ExitRequest(1);
			}
			ParsedLine parsed = lineReader.getParsedLine();
			List<String> words = parsed.words() == null ? List.of()
					: parsed.words()
					        .stream()
					        .filter(w -> w != null && !w.isBlank())
					        .map(OptionNormalizer :: normalize)
					        .toList();
			if(words.isEmpty()) {
				// 空对话行默认执行 help
				CliInputHolder.set(List.of("help"));
				return new WordsInput("help", List.of("help"));
			}
			CliInputHolder.set(words);
			String head = words.getFirst();
			if(head.equalsIgnoreCase("download") || head.equalsIgnoreCase("i")) {
// download / i 为全手工解析命令，绕过 Spring Shell 解析直接执行
				try {
					gameCommands.downloadRaw(words);
				} catch(IOException e) {
					log.error("I/O 错误: {}", e.getMessage());
				} catch(InterruptedException e) {
					Thread.currentThread()
					      .interrupt();
					log.error("操作被中断");
				}
				return Input.EMPTY; // 空输入让 Shell 循环继续
			}
			return new WordsInput(parsed.line(), words);
		}
	}

	private record WordsInput(String raw, List<String> words) implements Input {
		@Override
		public String rawText() {
			return raw;
		}

		@Override
		public List<String> words() {
			return words;
		}
	}
}