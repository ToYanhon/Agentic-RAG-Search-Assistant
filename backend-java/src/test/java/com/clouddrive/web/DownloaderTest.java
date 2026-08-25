package com.clouddrive.web;

import com.clouddrive.file.Download;
import com.clouddrive.file.Record;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DownloaderTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 16, 12, 0, 0);

	private static final byte[] CONTENT = "0123456789".getBytes(StandardCharsets.UTF_8);

	private Record record() {
		return new Record(1, 7, null, "report.pdf", 10, "application/pdf", "m", "k", NOW);
	}

	private MockHttpServletResponse full() throws IOException {
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockHttpServletRequest request = new MockHttpServletRequest();
		Downloader.write(response, request, record(), new ByteArrayInputStream(CONTENT),
				(offset, length) -> ranged(offset, length));
		return response;
	}

	private Download ranged(long offset, long length) {
		return new Download(record(), new ByteArrayInputStream(CONTENT, (int) offset, (int) length));
	}

	@Test
	void noRangeReturnsFullContent() throws IOException {
		MockHttpServletResponse response = full();
		assertEquals(200, response.getStatus());
		assertEquals("application/pdf", response.getContentType());
		assertEquals("attachment; filename=\"report.pdf\"", response.getHeader("Content-Disposition"));
		assertEquals("bytes", response.getHeader("Accept-Ranges"));
		assertEquals("10", response.getHeader("Content-Length"));
		assertEquals("0123456789", response.getContentAsString());
	}

	private MockHttpServletResponse withRange(String range) throws IOException {
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("Range", range);
		Downloader.write(response, request, record(), new ByteArrayInputStream(CONTENT),
				(offset, length) -> ranged(offset, length));
		return response;
	}

	@Test
	void rangeBytes2To4() throws IOException {
		MockHttpServletResponse response = withRange("bytes=2-4");
		assertEquals(206, response.getStatus());
		assertEquals("3", response.getHeader("Content-Length"));
		assertEquals("bytes 2-4/10", response.getHeader("Content-Range"));
		assertEquals("234", response.getContentAsString());
	}

	@Test
	void rangeBytes8ToEnd() throws IOException {
		MockHttpServletResponse response = withRange("bytes=8-");
		assertEquals(206, response.getStatus());
		assertEquals("89", response.getContentAsString());
		assertEquals("bytes 8-9/10", response.getHeader("Content-Range"));
	}

	@Test
	void rangeTail() throws IOException {
		MockHttpServletResponse response = withRange("bytes=-3");
		assertEquals(206, response.getStatus());
		assertEquals("789", response.getContentAsString());
		assertEquals("bytes 7-9/10", response.getHeader("Content-Range"));
	}

	@Test
	void invalidRangeReturns416() throws IOException {
		MockHttpServletResponse response = withRange("bytes=10-12");
		assertEquals(416, response.getStatus());
		assertEquals("bytes */10", response.getHeader("Content-Range"));
		assertEquals("", response.getContentAsString());
	}

	@Test
	void multiRangeRejected() throws IOException {
		MockHttpServletResponse response = withRange("bytes=0-1,3-4");
		assertEquals(416, response.getStatus());
	}

}