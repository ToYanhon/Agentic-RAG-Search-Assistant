package com.clouddrive.file;

import java.util.Locale;
import java.util.Set;

/**
 * 文本文件扩展名策略，对应 Go file/text_policy.go。
 */
public final class TextPolicy {

	private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "md", "markdown", "csv", "json", "xml", "yml",
			"yaml", "ini", "log", "js", "ts", "tsx", "jsx", "html", "css", "py", "go", "java", "c", "h", "cpp", "cc",
			"cxx", "hpp", "sh", "bat", "sql");

	private TextPolicy() {
	}

	public static boolean isTextFile(String name) {
		int dot = name.lastIndexOf('.');
		if (dot < 0 || dot == name.length() - 1) {
			return false;
		}
		return TEXT_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
	}

}