package ru.momento.app

import android.Manifest
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.momento.app.data.LocalMomentDatabase
import ru.momento.app.network.MomentoAuthSession
import ru.momento.app.network.MomentoSessionVault
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CameraCaptureTest {
    @get:Rule val cameraPermission = GrantPermissionRule.grant(Manifest.permission.CAMERA)
    @get:Rule val composeRule = createEmptyComposeRule()

    @Test fun recordingStopsAndSavesPlayableLocalVideo() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = UUID.randomUUID().toString()
        val databaseName = LocalMomentDatabase.databaseNameForAccount(owner)
        val vault = MomentoSessionVault(context)
        vault.save(MomentoAuthSession("test-access", "test-refresh", "Bearer", 600), owner)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                composeRule.onNodeWithContentDescription("Камера").performClick()
                val ready = hasContentDescription("Начать запись") and isEnabled()
                composeRule.waitUntil(20_000) { composeRule.onAllNodes(ready).fetchSemanticsNodes().isNotEmpty() }
                composeRule.onNode(ready).performClick()
                LocalMomentDatabase(context, owner).use { database ->
                    composeRule.waitUntil(20_000) { database.getMoments().isNotEmpty() }
                    val clip = database.getMoments().single()
                    assertTrue(clip.file.isFile)
                    assertTrue(clip.durationMillis in 2_000L..5_000L)
                    assertEquals(clip.durationMillis, LocalMomentDatabase.readCaptureDuration(clip.file))
                    assertEquals(LocalMomentDatabase.UPLOAD_LOCAL_ONLY, clip.uploadState)
                }
            }
        } finally {
            vault.clear()
            context.deleteDatabase(databaseName)
            context.deleteDatabase("spaces_$databaseName")
            File(context.filesDir, "moments_${databaseName.removeSuffix(".db")}").deleteRecursively()
        }
    }
}
