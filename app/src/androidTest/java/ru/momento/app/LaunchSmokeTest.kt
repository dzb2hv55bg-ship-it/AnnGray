package ru.momento.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
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
        composeRule.onNodeWithText("Войти как гость").performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.onNodeWithText("Войти, чтобы записать момент").performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.onNodeWithText("Чтобы записывать и публиковать моменты, сначала войди в аккаунт.").assertIsDisplayed()
        composeRule.onNodeWithText("Отмена — вернуться в гостевой режим").performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.onNodeWithText("Жизнь в моментах").assertIsDisplayed()
    }

    @Test fun guestNavigationKeepsItsPlaceAfterCancellingSignIn() {
        composeRule.onNodeWithText("Войти как гость").performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.onNodeWithContentDescription("Комнаты").performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.onNodeWithText("Открытые комнаты").assertIsDisplayed()
        composeRule.onNodeWithText("Публичные влоги пока недоступны").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Подписки").performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.onNodeWithText("Войти в аккаунт").performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.onNodeWithText("Отмена — вернуться в гостевой режим").performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.onNodeWithText("Войти в аккаунт").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Профиль").performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.onNodeWithText("Гостевой профиль").assertIsDisplayed()
        composeRule.onNodeWithText("Вернуться ко входу").performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.onNodeWithText("Войти как гость").assertIsDisplayed()
    }
}
