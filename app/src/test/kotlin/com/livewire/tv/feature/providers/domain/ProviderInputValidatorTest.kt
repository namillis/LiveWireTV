package com.livewire.tv.feature.providers.domain

import com.livewire.tv.feature.providers.domain.ProviderInputValidator.Field
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderInputValidatorTest {

    @Test
    fun acceptsHttpAndHttpsWithPort() {
        assertNull(ProviderInputValidator.validate("http://provider.example:8080", "u", "p"))
        assertNull(ProviderInputValidator.validate("  https://provider.example/  ", "u", "p"))
    }

    @Test
    fun rejectsBlankUrl() {
        assertEquals(Field.URL, ProviderInputValidator.validate("   ", "u", "p")?.field)
    }

    @Test
    fun rejectsMissingOrUnsupportedScheme() {
        assertEquals(Field.URL, ProviderInputValidator.validate("provider.example:8080", "u", "p")?.field)
        assertEquals(Field.URL, ProviderInputValidator.validate("ftp://provider.example", "u", "p")?.field)
    }

    @Test
    fun rejectsMalformedUrl() {
        assertEquals(Field.URL, ProviderInputValidator.validate("http://", "u", "p")?.field)
        assertEquals(Field.URL, ProviderInputValidator.validate("http://bad host:80", "u", "p")?.field)
    }

    @Test
    fun requiresCredentials() {
        assertEquals(Field.USERNAME, ProviderInputValidator.validate("http://h", " ", "p")?.field)
        assertEquals(Field.PASSWORD, ProviderInputValidator.validate("http://h", "u", "")?.field)
    }
}
