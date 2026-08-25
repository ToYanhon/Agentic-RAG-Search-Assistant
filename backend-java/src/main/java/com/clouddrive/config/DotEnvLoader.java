package com.clouddrive.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * godotenv 风格 .env 加载器。优先级与 Go 后端一致：真实环境变量 > .env 文件 > 代码默认值。 仅当对应环境变量不存在时，才将 .env
 * 中的值写入系统属性（系统属性优先级高于 application.yml 默认值）。
 */
public final class DotEnvLoader {

	private static final Logger log = LoggerFactory.getLogger(DotEnvLoader.class);

	private DotEnvLoader() {
	}

	/**
	 * 尝试加载 backend-java/.env、backend/.env 与仓库根 .env（按顺序，后面的不覆盖先加载的同名键）。
	 */
	public static void load() {
		String workdir = System.getProperty("user.dir", ".");
		List<Path> candidates = new ArrayList<>(
				List.of(Paths.get("backend-java", ".env"), Paths.get(".env"), Paths.get("backend", ".env")));
		List<String> loaded = new ArrayList<>();
		for (Path candidate : candidates) {
			Path absolute = candidate.isAbsolute() ? candidate : Paths.get(workdir).resolve(candidate).normalize();
			if (!Files.isRegularFile(absolute)) {
				continue;
			}
			try {
				int count = apply(Files.readAllLines(absolute, StandardCharsets.UTF_8));
				loaded.add(absolute + " (" + count + " keys)");
			}
			catch (IOException e) {
				log.warn("dotenv 读取失败 {}：{}", absolute, e.getMessage());
			}
		}
		if (!loaded.isEmpty()) {
			log.info("dotenv 已加载：{}", String.join(", ", loaded));
		}
	}

	private static int apply(List<String> lines) {
		int count = 0;
		for (String raw : lines) {
			String line = raw.strip();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			int eq = line.indexOf('=');
			if (eq <= 0) {
				continue;
			}
			String key = line.substring(0, eq).trim();
			String value = line.substring(eq + 1).strip();
			if (key.isEmpty() || System.getenv(key) != null) {
				continue;
			}
			value = unquote(value);
			System.setProperty(key, value);
			count++;
		}
		return count;
	}

	private static String unquote(String value) {
		if (value.length() >= 2) {
			char first = value.charAt(0);
			char last = value.charAt(value.length() - 1);
			if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
				return value.substring(1, value.length() - 1);
			}
		}
		return value;
	}

}
