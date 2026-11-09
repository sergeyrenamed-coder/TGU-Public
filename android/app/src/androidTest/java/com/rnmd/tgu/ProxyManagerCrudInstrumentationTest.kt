package com.rnmd.tgu

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rnmd.tgu.proxy.ProxyProfileStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProxyManagerCrudInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var store: ProxyProfileStore

    @Before
    fun prepareStore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        store = ProxyProfileStore(context)
        store.load()
            .filter { it.name.startsWith("UI CRUD", ignoreCase = true) }
            .forEach { store.delete(it.id) }
    }

    @After
    fun cleanStore() {
        store.load()
            .filter { it.name.startsWith("UI CRUD", ignoreCase = true) }
            .forEach { store.delete(it.id) }
    }

    @Test
    fun profileCanBeCreatedEditedAndDeletedThroughTheUi() {
        composeRule.waitForIdle()
        composeRule.onNode(hasText("Аккаунт") and hasClickAction()).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Настройки приложения").performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasText("Менеджер proxy", substring = true) and hasClickAction()).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("+ ДОБАВИТЬ PROXY").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Добавить proxy").assertExists()

        composeRule.onNodeWithText("Название профиля").performTextInput("UI CRUD MTProto")
        composeRule.onNodeWithText("Хост или IP-адрес").performTextInput("127.0.0.1")
        composeRule.onNodeWithText("Секрет MTProto (hex)")
            .performTextInput("00112233445566778899aabbccddeeff")
        composeRule.onNodeWithText("СОХРАНИТЬ").performClick()

        // Text in the editor is not proof that the asynchronous save reached storage.
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Добавить proxy").fetchSemanticsNodes().isEmpty()
        }
        composeRule.waitUntil(10_000) {
            store.load().any { it.name == "UI CRUD MTProto" }
        }
        val created = requireNotNull(store.load().firstOrNull { it.name == "UI CRUD MTProto" }) {
            "The profile editor closed, but the new profile was not persisted."
        }
        assertEquals("127.0.0.1", created.host)
        assertEquals(443, created.port)

        composeRule.onNode(hasText("ИЗМЕНИТЬ") and hasClickAction()).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Редактировать proxy").assertExists()
        composeRule.onNode(hasSetTextAction() and hasText("UI CRUD MTProto"))
            .performTextReplacement("UI CRUD Edited")
        composeRule.onNode(hasSetTextAction() and hasText("127.0.0.1"))
            .performTextReplacement("127.0.0.2")
        composeRule.onNodeWithText("СОХРАНИТЬ ИЗМЕНЕНИЯ").performClick()

        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Редактировать proxy").fetchSemanticsNodes().isEmpty()
        }
        composeRule.waitUntil(10_000) {
            store.load().any { it.id == created.id && it.name == "UI CRUD Edited" && it.host == "127.0.0.2" }
        }
        val edited = requireNotNull(store.load().firstOrNull { it.id == created.id }) {
            "The edited profile was not persisted."
        }
        assertEquals("UI CRUD Edited", edited.name)
        assertEquals("127.0.0.2", edited.host)

        composeRule.onNodeWithText("УДАЛИТЬ").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("UI CRUD Edited").fetchSemanticsNodes().isEmpty() &&
                store.load().none { it.id == created.id }
        }
        assertFalse("Deleted profile must not remain in encrypted storage.", store.load().any { it.id == created.id })
    }
}
