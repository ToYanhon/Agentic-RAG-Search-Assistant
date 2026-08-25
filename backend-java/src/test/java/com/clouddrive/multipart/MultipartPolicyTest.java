package com.clouddrive.multipart;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultipartPolicyTest {

	@Test
	void totalChunks() {
		assertEquals(1, MultipartPolicy.totalChunks(5 * 1024 * 1024, 5 * 1024 * 1024));
		assertEquals(2, MultipartPolicy.totalChunks(5 * 1024 * 1024 + 1, 5 * 1024 * 1024));
		assertEquals(3, MultipartPolicy.totalChunks(10 * 1024 * 1024 + 1, 5 * 1024 * 1024));
		assertThrows(IllegalArgumentException.class, () -> MultipartPolicy.totalChunks(0, 1024));
		assertThrows(IllegalArgumentException.class, () -> MultipartPolicy.totalChunks(100, 0));
	}

	@Test
	void validIndex() {
		assertTrue(MultipartPolicy.validIndex(0, 2));
		assertTrue(MultipartPolicy.validIndex(1, 2));
		assertFalse(MultipartPolicy.validIndex(2, 2));
		assertFalse(MultipartPolicy.validIndex(-1, 2));
		assertFalse(MultipartPolicy.validIndex(0, 0));
	}

	@Test
	void contiguous() {
		assertTrue(MultipartPolicy.contiguous(List.of(0, 1), 2));
		assertFalse(MultipartPolicy.contiguous(List.of(0, 2), 3));
		assertFalse(MultipartPolicy.contiguous(List.of(0, 1), 3));
		assertFalse(MultipartPolicy.contiguous(List.of(), 0));
	}

	@Test
	void validPartSize() {
		assertTrue(MultipartPolicy.validPartSize(0, 2, 5 * 1024 * 1024));
		assertFalse(MultipartPolicy.validPartSize(0, 2, 4 * 1024 * 1024));
		assertTrue(MultipartPolicy.validPartSize(1, 2, 1));
		assertFalse(MultipartPolicy.validPartSize(1, 2, 0));
	}

}