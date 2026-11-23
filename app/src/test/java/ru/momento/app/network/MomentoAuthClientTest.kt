package ru.momento.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MomentoAuthClientTest {
    @Test
    fun unconfiguredClientCanBeCreatedButDoesNotRequestIdentity() {
        val client = MomentoAuthClient("")
        val error = assertThrows(IllegalStateException::class.java) {
            client.authenticatedUserId("not-used")
        }
        assertEquals("MOMENTO API is not configured", error.message)
    }

    @Test
    fun configuredClientRejectsNonHttpsEndpoints() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            MomentoAuthClient("http://localhost")
        }
        assertEquals("MOMENTO API must use HTTPS", error.message)
    }
}
