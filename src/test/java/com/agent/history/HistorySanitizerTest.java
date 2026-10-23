package com.agent.history;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HistorySanitizerTest {
    private final HistorySanitizer sanitizer = new HistorySanitizer();

    @Test
    void shouldRedactCommonCredentialForms() {
        String input = "GLM_API_KEY=abc Authorization: Bearer xyz password='p' "
                + "{\"access_token\":\"secret-value\"}";

        String result = sanitizer.sanitizeText(input);

        assertFalse(result.contains("abc"));
        assertFalse(result.contains("xyz"));
        assertFalse(result.contains("secret-value"));
        assertTrue(result.contains("[REDACTED]"));
    }
}
