package ru.momento.app.data

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import ru.momento.app.data.LocalMomentDatabase.Companion.LOCAL_PREVIEW_OWNER
import ru.momento.app.data.LocalMomentDatabase.Companion.databaseNameForAccount

class LocalPreviewStorageTest {
    @Test
    fun previewStorageIsStableAndSeparateFromAccountArchives() {
        val preview = databaseNameForAccount(LOCAL_PREVIEW_OWNER)
        assertEquals("momento_preview.db", preview)
        assertNotEquals(preview, databaseNameForAccount(UUID.randomUUID().toString()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun arbitraryInvalidAccountIdentifierStillFails() {
        databaseNameForAccount("another-local-user")
    }
}
