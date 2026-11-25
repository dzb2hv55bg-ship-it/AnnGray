package ru.momento.app

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchSmokeTest {
    @get:Rule val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test fun launchesAndShowsAppTitle() {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.elapsedRealtime() + 15_000
        do {
            if (ui.rootInActiveWindow?.containsTitle() == true) return
            SystemClock.sleep(250)
        } while (SystemClock.elapsedRealtime() < deadline)
        fail("The MOMENTO title was not exposed by the launched activity")
    }

    private fun AccessibilityNodeInfo.containsTitle(): Boolean {
        if (text?.toString() == "MOMENTO") return true
        return (0 until childCount).any { getChild(it)?.containsTitle() == true }
    }
}
