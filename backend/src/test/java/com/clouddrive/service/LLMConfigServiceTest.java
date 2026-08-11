package com.clouddrive.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * LLMConfigService.normalizeBaseURL 测试（对齐 Go：去尾部 /chat/completions、/、query）。
 */
class LLMConfigServiceTest {

    @Test
    void stripsChatCompletionsSuffix() {
        assertThat(LLMConfigService.normalizeBaseURL("https://api.deepseek.com/v1/chat/completions"))
                .isEqualTo("https://api.deepseek.com/v1");
    }

    @Test
    void stripsTrailingSlashAndQuery() {
        assertThat(LLMConfigService.normalizeBaseURL("https://api.deepseek.com/v1/?x=1"))
                .isEqualTo("https://api.deepseek.com/v1");
    }

    @Test
    void caseInsensitiveSuffix() {
        assertThat(LLMConfigService.normalizeBaseURL("https://a.com/v1/CHAT/COMPLETIONS/"))
                .isEqualTo("https://a.com/v1");
    }

    @Test
    void rootKeptAsIs() {
        assertThat(LLMConfigService.normalizeBaseURL("https://api.deepseek.com"))
                .isEqualTo("https://api.deepseek.com");
        assertThat(LLMConfigService.normalizeBaseURL(null)).isEmpty();
    }
}
