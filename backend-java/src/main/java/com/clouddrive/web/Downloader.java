package com.clouddrive.web;

import com.clouddrive.file.Download;
import com.clouddrive.file.Record;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 下载响应写入，对应 Go httpapi/download.go。支持单字节 Range（206）。
 */
public final class Downloader {

	private static final int BUFFER = 32 * 1024;

	private Downloader() {
	}

	@FunctionalInterface
	public interface RangeSupplier {

		Download apply(long offset, long length);

	}

	public static void write(HttpServletResponse response, HttpServletRequest request, Record record, InputStream body,
			RangeSupplier rangeSupplier) {
		String mime = record.mimeType();
		if (mime == null || mime.isEmpty()) {
			mime = "application/octet-stream";
		}
		response.setContentType(mime);
		response.setHeader("Content-Disposition", "attachment; filename=" + quote(record.name()));
		response.setHeader("Accept-Ranges", "bytes");
		String rangeHeader = request.getHeader("Range");
		try {
			if (rangeHeader == null || rangeHeader.isEmpty()) {
				response.setHeader("Content-Length", Long.toString(record.size()));
				copy(body, response.getOutputStream());
				return;
			}
			long[] range = parseSingleRange(rangeHeader, record.size());
			if (range == null) {
				response.setHeader("Content-Range", "bytes */" + record.size());
				response.setStatus(416);
				return;
			}
			long start = range[0];
			long end = range[1];
			Download ranged = rangeSupplier.apply(start, end - start + 1);
			response.setHeader("Content-Length", Long.toString(end - start + 1));
			response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + ranged.record().size());
			response.setStatus(206);
			copy(ranged.body(), response.getOutputStream());
		}
		catch (IOException e) {
			throw new IllegalStateException("download failed", e);
		}
	}

	private static long[] parseSingleRange(String value, long size) {
		if (!value.startsWith("bytes=") || value.contains(",") || size <= 0) {
			return null;
		}
		String rest = value.substring("bytes=".length());
		int dash = rest.indexOf('-');
		if (dash < 0) {
			return null;
		}
		String startRaw = rest.substring(0, dash);
		String endRaw = rest.substring(dash + 1);
		if (startRaw.isEmpty()) {
			long tail;
			try {
				tail = Long.parseLong(endRaw);
			}
			catch (NumberFormatException e) {
				return null;
			}
			if (tail <= 0) {
				return null;
			}
			if (tail > size) {
				tail = size;
			}
			return new long[] { size - tail, size - 1 };
		}
		long start;
		try {
			start = Long.parseLong(startRaw);
		}
		catch (NumberFormatException e) {
			return null;
		}
		if (start < 0 || start >= size) {
			return null;
		}
		long end = size - 1;
		if (!endRaw.isEmpty()) {
			try {
				end = Long.parseLong(endRaw);
			}
			catch (NumberFormatException e) {
				return null;
			}
			if (end < start) {
				return null;
			}
			if (end >= size) {
				end = size - 1;
			}
		}
		return new long[] { start, end };
	}

	private static void copy(InputStream in, OutputStream out) throws IOException {
		byte[] buffer = new byte[BUFFER];
		int read;
		try (in) {
			while ((read = in.read(buffer)) != -1) {
				out.write(buffer, 0, read);
			}
		}
		out.flush();
	}

	private static String quote(String name) {
		if (name == null) {
			name = "";
		}
		return "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

}