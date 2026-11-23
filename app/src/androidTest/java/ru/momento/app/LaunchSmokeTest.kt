package ru.momento.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchSmokeTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun launchesAndShowsAppTitle() {
        composeRule.onNodeWithText("MOMENTO").assertIsDisplayed()
    }
    @Test fun guestRecordingOpensSignInAndCanReturnToGuest() {
        composeRule.onNodeWithText("Войти как гость").performClick()
        composeRule.onNodeWithText("Войти, чтобы записать момент").performClick()
        composeRule.onNodeWithText("Чтобы записывать и публиковать моменты, сначала войди в аккаунт.").assertIsDisplayed()
        composeRule.onNodeWithText("Отмена — вернуться в гостевой режим").performClick()
        composeRule.onNodeWithText("Жизнь в моментах").assertIsDisplayed()
    }

    @Test fun guestNavigationKeepsItsPlaceAfterCancellingSignIn() {
        composeRule.onNodeWithText("Войти как гость").performClick()
        composeRule.onNodeWithContentDescription("Комнаты").performClick()
        composeRule.onNodeWithText("Открытые комнаты").assertIsDisplayed()
        composeRule.onNodeWithText("Публичные влоги пока недоступны").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Подписки").performClick()
        composeRule.onNodeWithText("Войти в аккаунт").performClick()
        composeRule.onNodeWithText("Отмена — вернуться в гостевой режим").performClick()
        composeRule.onNodeWithText("Войти в аккаунт").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Профиль").performClick()
        composeRule.onNodeWithText("Гостевой профиль").assertIsDisplayed()
        composeRule.onNodeWithText("Вернуться ко входу").performClick()
        composeRule.onNodeWithText("Войти как гость").assertIsDisplayed()
    }
}
