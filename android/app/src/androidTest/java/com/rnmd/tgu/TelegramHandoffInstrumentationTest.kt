package com.rnmd.tgu

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rnmd.tgu.proxy.LocalProxyRuntime
import com.rnmd.tgu.proxy.LocalProxySettingsStore
import com.rnmd.tgu.proxy.LocalProxyStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TelegramHandoffInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun telegramReceivesProxyUriOnlyAfterExplicitApplyButtonTap() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val applyButton = composeRule.onNodeWithText("ПРИМЕНИТЬ PROXY В TELEGRAM")

        composeRule.runOnIdle {
            LocalProxyRuntime.publish(LocalProxyStatus.STOPPED, "Test-only stopped state")
        }
        composeRule.waitForIdle()

        Intents.init()
        try {
            intending(hasAction(Intent.ACTION_VIEW)).respondWith(
                Instrumentation.ActivityResult(Activity.RESULT_OK, null)
            )

            applyButton.assertIsNotEnabled()

            // Navigating through the app must not open Telegram.
            composeRule.onNode(hasText("Каталог") and hasClickAction()).performClick()
            composeRule.waitForIdle()
            composeRule.onNode(hasText("Главная") and hasClickAction()).performClick()
            composeRule.waitForIdle()
            assertTrue(Intents.getIntents().none { it.action == Intent.ACTION_VIEW })

            // Other navigation and proxy-management screens must never hand off a URI.
            composeRule.onNode(hasText("Диагностика") and hasClickAction()).performClick()
            composeRule.waitForIdle()
            composeRule.onNode(hasText("Аккаунт") and hasClickAction()).performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Настройки приложения").performClick()
            composeRule.waitForIdle()
            composeRule.onNode(hasText("Менеджер proxy", substring = true) and hasClickAction()).performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("‹").performClick()
            composeRule.waitForIdle()
            composeRule.onNode(hasText("Мониторинг и автосмена", substring = true) and hasClickAction()).performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("‹").performClick()
            composeRule.waitForIdle()
            composeRule.onNode(hasText("Главная") and hasClickAction()).performClick()
            composeRule.waitForIdle()
            assertTrue(
                "Catalog, diagnostics, account, settings, profiles and monitoring must not open Telegram.",
                Intents.getIntents().none { it.action == Intent.ACTION_VIEW },
            )

            // The apply action becomes available only while the local engine is RUNNING.
            composeRule.runOnIdle {
                LocalProxyRuntime.publish(LocalProxyStatus.RUNNING, "Test-only running state")
            }
            composeRule.waitForIdle()
            applyButton.assertIsEnabled()
            applyButton.performClick()
            composeRule.waitForIdle()

            val viewIntents = Intents.getIntents().filter { it.action == Intent.ACTION_VIEW }
            assertEquals("Exactly one explicit Telegram handoff is expected.", 1, viewIntents.size)

            val uri = viewIntents.single().data
            assertNotNull(uri)
            assertEquals("tg", uri!!.scheme)
            assertEquals("proxy", uri.authority)
            assertEquals(LocalProxySettingsStore.LOCAL_HOST, uri.getQueryParameter("server"))
            assertEquals(LocalProxySettingsStore.LOCAL_PORT.toString(), uri.getQueryParameter("port"))
            val secret = uri.getQueryParameter("secret")
            assertNotNull(secret)
            assertTrue(secret!!.matches(Regex("^dd[0-9a-fA-F]{32}$")))
        } finally {
            composeRule.runOnIdle {
                LocalProxyRuntime.publish(LocalProxyStatus.STOPPED, "Test cleanup")
            }
            Intents.release()
        }
    }
}
