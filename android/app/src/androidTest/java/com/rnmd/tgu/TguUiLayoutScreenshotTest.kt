package com.rnmd.tgu

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import com.rnmd.tgu.proxy.LocalProxyRuntime
import com.rnmd.tgu.proxy.LocalProxySettingsStore
import com.rnmd.tgu.proxy.LocalProxyStatus
import com.rnmd.tgu.proxy.ProxyProfile
import com.rnmd.tgu.proxy.ProxyProfileStore
import com.rnmd.tgu.proxy.ProxyType
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TguUiLayoutScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun localProxySecretAndRestorePreferenceSurviveStoreRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = LocalProxySettingsStore(context)
        val previousRestoreState = store.shouldRestoreAfterUpdate()
        try {
            val secretBefore = store.telegramProxyUri().getQueryParameter("secret")
            store.setRestoreAfterUpdate(true)

            val reopenedStore = LocalProxySettingsStore(context)
            assertTrue(reopenedStore.shouldRestoreAfterUpdate())
            assertEquals(secretBefore, reopenedStore.telegramProxyUri().getQueryParameter("secret"))
        } finally {
            store.setRestoreAfterUpdate(previousRestoreState)
        }
    }

    @Test
    fun bottomNavigationIsCenteredAndPrimaryScreensRender() {
        composeRule.waitForIdle()
        assertBottomNavigationCenters()
        assertNoDemoData()
        composeRule.onNodeWithText("ПРИМЕНИТЬ PROXY В TELEGRAM").assertExists()
        composeRule.onNodeWithText("Нажмите для включения").assertExists()
        composeRule.onNodeWithText("Нажмите ⏻ для включения").assertDoesNotExist()
        captureScreenshot("01-home")

        composeRule.runOnIdle {
            LocalProxyRuntime.publish(LocalProxyStatus.RUNNING, "Test-only running state")
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Подключено").assertExists()
        composeRule.onNodeWithText("Локальный сервис").assertExists()
        composeRule.onNodeWithText("Запущен").assertExists()
        captureScreenshot("01-home-service-on")
        composeRule.runOnIdle {
            LocalProxyRuntime.publish(LocalProxyStatus.STOPPED, "Test-only stopped state")
        }
        composeRule.waitForIdle()

        clickBottomItem("Каталог")
        assertNoDemoData()
        composeRule.onNodeWithText("ПРИМЕНИТЬ PROXY В TELEGRAM").assertDoesNotExist()
        captureScreenshot("02-catalog")

        clickBottomItem("Диагностика")
        assertNoDemoData()
        composeRule.onNodeWithText("ПРИМЕНИТЬ PROXY В TELEGRAM").assertDoesNotExist()
        captureScreenshot("03-diagnostics")

        clickBottomItem("Аккаунт")
        assertNoDemoData()
        composeRule.onNodeWithText("ПРИМЕНИТЬ PROXY В TELEGRAM").assertDoesNotExist()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(
                "Единая учётная запись экосистемы RNMDTEAM",
                substring = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("ПРОДОЛЖИТЬ С TELEGRAM").assertExists()
        composeRule.onNodeWithText("TGU Free", substring = true).assertExists()
        captureScreenshot("04-account")

        composeRule.onNodeWithText("Настройки приложения").performClick()
        composeRule.waitForIdle()
        captureScreenshot("05-settings")

        composeRule.onNode(hasText("Мониторинг и автосмена", substring = true) and hasClickAction()).performClick()
        composeRule.waitForIdle()
        captureScreenshot("06-monitoring")
        composeRule.onNodeWithText("‹").performClick()
        composeRule.waitForIdle()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testProfileId = "tgu-ui-screenshot-profile"
        val profileStore = ProxyProfileStore(context)
        profileStore.upsert(
            ProxyProfile(
                id = testProfileId,
                name = "UI preview SOCKS5",
                type = ProxyType.SOCKS5,
                host = "127.0.0.1",
                port = 1080,
            )
        )

        composeRule.onNode(hasText("Менеджер proxy", substring = true) and hasClickAction()).performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("UI preview SOCKS5").fetchSemanticsNodes().isNotEmpty()
        }
        captureScreenshot("07-proxy-manager")

        composeRule.onNode(hasText("ИЗМЕНИТЬ", substring = true) and hasClickAction()).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Редактировать proxy").assertExists()
        captureScreenshot("08-profile-editor")

        composeRule.onNodeWithText("ОТМЕНА").performClick()
        composeRule.waitForIdle()
        clickBottomItem("Главная")
        composeRule.onNodeWithText("ПРИМЕНИТЬ PROXY В TELEGRAM").assertExists()
        captureScreenshot("09-home-live-empty-state")

        profileStore.delete(testProfileId)
    }

    private fun assertNoDemoData() {
        listOf(
            "Демонстрационные данные",
            "192.0.2.18:443",
            "198.51.100.24:1080",
            "203.0.113.42:443",
            "203.0.113.250",
            "@username",
        ).forEach { sample ->
            composeRule.onNodeWithText(sample, substring = true).assertDoesNotExist()
        }
    }

    private fun assertBottomNavigationCenters() {
        val rootWidth = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.width
        val labels = listOf("Главная", "Каталог", "Диагностика", "Аккаунт")
        val expectedFractions = listOf(0.125f, 0.375f, 0.625f, 0.875f)

        labels.forEachIndexed { index, label ->
            val bounds = composeRule.onNodeWithText(label, useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val actualFraction = bounds.center.x / rootWidth
            assertTrue(
                "Bottom item '$label' center is shifted: actual=$actualFraction expected=${expectedFractions[index]}",
                abs(actualFraction - expectedFractions[index]) < 0.045f,
            )
        }
    }

    private fun clickBottomItem(label: String) {
        composeRule.onNode(hasText(label, substring = true) and hasClickAction()).performClick()
        composeRule.waitForIdle()
    }

    private fun captureScreenshot(name: String) {
        composeRule.waitForIdle()
        require(name.matches(Regex("^[A-Za-z0-9_-]+$"))) { "Invalid screenshot name." }

                                                                                  
                                                                                       
        val bitmap = requireNotNull(
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        ) { "UiAutomation could not capture screenshot '$name'." }

        try {
            val buffer = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, buffer)) {
                "Android could not encode screenshot '$name' as PNG."
            }
            val png = buffer.toByteArray()
            val encoded = Base64.encodeToString(png, Base64.NO_WRAP)
            val chunks = encoded.chunked(LOG_CHUNK_CHARS)
            chunks.forEachIndexed { index, chunk ->
                Log.i(
                    SCREENSHOT_TAG,
                    "TGU_SCREENSHOT|$name|${index + 1}|${chunks.size}|$chunk",
                )
            }
            Log.i(
                SCREENSHOT_TAG,
                "TGU_SCREENSHOT_DONE|$name|${bitmap.width}|${bitmap.height}|${png.size}",
            )
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val SCREENSHOT_TAG = "TGU-UI-SCREENSHOT"
        const val LOG_CHUNK_CHARS = 2_000
    }
}
