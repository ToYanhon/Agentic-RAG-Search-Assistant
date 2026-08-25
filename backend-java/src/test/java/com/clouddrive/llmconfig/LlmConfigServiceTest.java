package com.clouddrive.llmconfig;

import com.clouddrive.common.Errors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmConfigServiceTest {

	private final Repository configs = mock(Repository.class);

	private final Secret secrets = mock(Secret.class);

	private final LlmConfigService service = new LlmConfigService(configs, secrets);

	@BeforeEach
	void setUp() {
		when(secrets.encrypt("sk-123")).thenReturn("v1:encrypted");
	}

	@Test
	void upsertRequiresProvider() {
		assertThrows(Errors.ProviderRequired.class, () -> service.upsert(1, "   ", "url", "key", "model"));
	}

	@Test
	void upsertProviderTooLong() {
		assertThrows(Errors.ProviderTooLong.class, () -> service.upsert(1, "p".repeat(65), "url", "key", "model"));
	}

	@Test
	void upsertPreservesExistingKeyWhenApiKeyEmpty() {
		when(configs.find(1, "openai")).thenReturn(new Stored("openai", "base", "v1:old", "gpt", NOW()));
		service.upsert(1, "openai", "https://x/v1", "", "new-model");
		verify(configs).upsert(1, "openai", "https://x/v1", "v1:old", "new-model");
		verify(secrets, never()).encrypt(anyString());
	}

	@Test
	void upsertEncryptsNewApiKey() {
		when(configs.find(1, "openai")).thenThrow(new Errors.LlmConfigNotFound("llm config not found"));
		service.upsert(1, "openai", "https://x/v1", "sk-123", "gpt");
		verify(configs).upsert(1, "openai", "https://x/v1", "v1:encrypted", "gpt");
	}

	@Test
	void listMasksAndConfigures() {
		when(configs.findAll(1))
			.thenReturn(List.of(new Stored("openai", "https://api.example.com", "v1:enc", "gpt-4o", NOW()),
					new Stored("other", "", "", "", NOW())));
		List<View> views = service.list(1);
		assertEquals(2, views.size());
		View first = views.get(0);
		assertEquals("openai", first.provider());
		assertEquals("******", first.apiKeyMasked());
		assertTrue(first.configured());
		View second = views.get(1);
		assertEquals("", second.apiKeyMasked());
		assertFalse(second.configured());
	}

	@Test
	void resolveReturnsNoneWhenDecryptFails() {
		when(configs.find(1, "openai")).thenReturn(new Stored("openai", "url", "v1:enc", "gpt", NOW()));
		when(secrets.decrypt("v1:enc")).thenThrow(new IllegalArgumentException("bad"));
		Resolved resolved = service.resolve(1, "openai");
		assertFalse(resolved.ok());
	}

	@Test
	void normalizeBaseUrl() {
		assertEquals("https://api.example.com/v1", LlmConfigService.normalizeBaseUrl("https://api.example.com/v1/"));
		assertEquals("https://api.example.com/v1",
				LlmConfigService.normalizeBaseUrl("https://api.example.com/v1/chat/completions"));
		assertEquals("https://api.example.com",
				LlmConfigService.normalizeBaseUrl("https://api.example.com/chat/completions"));
		assertEquals("", LlmConfigService.normalizeBaseUrl("/chat/completions"));
		assertEquals("https://api.example.com/v1",
				LlmConfigService.normalizeBaseUrl("https://api.example.com/v1?key=1"));
		assertEquals("https://api.example.com", LlmConfigService.normalizeBaseUrl("https://api.example.com"));
	}

	private static LocalDateTime NOW() {
		return LocalDateTime.of(2026, 8, 16, 12, 0, 0);
	}

}