package com.clouddrive.file;

/**
 * 文本内容视图，对应 Go file.ContentView。
 */
public record ContentView(String content, int totalLines, boolean truncated) {
}