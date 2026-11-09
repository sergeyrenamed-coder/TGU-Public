package com.rnmd.tgu

import android.content.Context
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rnmd.tgu.proxy.AutoFailoverSettings
import com.rnmd.tgu.proxy.AutoFailoverSettingsStore
import com.rnmd.tgu.proxy.LocalProxySettingsStore
import com.rnmd.tgu.proxy.ProxyProfile
import com.rnmd.tgu.proxy.ProxyProfileStore
import com.rnmd.tgu.proxy.ProxyType
import com.rnmd.tgu.proxy.UpstreamProxySettingsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProxyStorageInstrumentationTest {
    private lateinit var context: Context

    @Before
    fun clearTestState() {
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        clearPreferences()
    }

    @After
    fun cleanUpTestState() {
        clearPreferences()
    }

    @Test
    fun encryptedProfilesRoundTripWithoutPlaintextCredentials() {
        val profile = ProxyProfile(
            id = "encrypted-round-trip",
            name = "Private SOCKS",
            type = ProxyType.SOCKS5,
            host = "127.0.0.1",
            port = 1080,
            username = "private-user",
            password = "private-password-value",
        )
        val store = ProxyProfileStore(context)

        val saved = store.upsert(profile)
        assertEquals(listOf(profile), saved)
        assertEquals(listOf(profile), ProxyProfileStore(context).load())

        val encryptedValue = context
            .getSharedPreferences("tgu_encrypted_proxy_profiles", Context.MODE_PRIVATE)
            .getString("profiles", null)
        assertNotNull(encryptedValue)
        assertFalse(encryptedValue!!.contains(profile.username))
        assertFalse(encryptedValue.contains(profile.password))
        assertFalse(encryptedValue.contains(profile.host))
    }

    @Test
    fun selectedUpstreamResolvesProfileAndClearsStaleSelection() {
        val profile = ProxyProfile(
            id = "selected-upstream",
            name = "Selected SOCKS",
            type = ProxyType.SOCKS5,
            host = "127.0.0.1",
            port = 1080,
        )
        val profiles = ProxyProfileStore(context)
        val selection = UpstreamProxySettingsStore(context)

        profiles.upsert(profile)
        selection.select(profile.id)
        assertEquals(profile.id, selection.selectedProfileId())
        assertEquals(profile, selection.selectedProfile())

        profiles.delete(profile.id)
        assertNull(selection.selectedProfile())
        assertNull(selection.selectedProfileId())
    }

    @Test
    fun localTelegramUriUsesLoopbackAndEncryptedStableSecret() {
        val store = LocalProxySettingsStore(context)
        val firstSecret = store.getOrCreateSecret()
        val secondSecret = LocalProxySettingsStore(context).getOrCreateSecret()
        val uri = store.telegramProxyUri()

        assertTrue(firstSecret.matches(Regex("^[0-9a-fA-F]{32}$")))
        assertEquals(firstSecret, secondSecret)
        assertEquals("tg", uri.scheme)
        assertEquals("proxy", uri.authority)
        assertEquals(LocalProxySettingsStore.LOCAL_HOST, uri.getQueryParameter("server"))
        assertEquals(LocalProxySettingsStore.LOCAL_PORT.toString(), uri.getQueryParameter("port"))
        assertEquals("dd$firstSecret", uri.getQueryParameter("secret"))

        val encryptedValue = context
            .getSharedPreferences("tgu_local_proxy_settings", Context.MODE_PRIVATE)
            .getString("encrypted_mtproto_secret", null)
        assertNotNull(encryptedValue)
        assertFalse(encryptedValue!!.contains(firstSecret))
    }

    @Test
    fun corruptedEncryptedLocalSecretIsRejectedInsteadOfRotated() {
        LocalProxySettingsStore(context).getOrCreateSecret()
        val preferences = context.getSharedPreferences(
            "tgu_local_proxy_settings",
            Context.MODE_PRIVATE,
        )
        val encoded = preferences.getString("encrypted_mtproto_secret", null)
        assertNotNull(encoded)

        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertTrue(
            preferences.edit()
                .putString("encrypted_mtproto_secret", Base64.encodeToString(bytes, Base64.NO_WRAP))
                .commit()
        )

        var rejected = false
        try {
            LocalProxySettingsStore(context).getOrCreateSecret()
        } catch (_: Exception) {
            rejected = true
        }
        assertTrue(
            "A corrupted stored MTProto secret must fail closed; it must not silently rotate credentials.",
            rejected,
        )
    }

    @Test
    fun encryptedProfileTamperingIsRejected() {
        val store = ProxyProfileStore(context)
        store.upsert(
            ProxyProfile(
                id = "tamper-check",
                name = "Tamper check",
                type = ProxyType.MTPROTO,
                host = "proxy.example.org",
                port = 443,
                secret = "00112233445566778899aabbccddeeff",
            )
        )
        val preferences = context.getSharedPreferences(
            "tgu_encrypted_proxy_profiles",
            Context.MODE_PRIVATE,
        )
        val encoded = preferences.getString("profiles", null)
        assertNotNull(encoded)

        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertTrue(
            preferences.edit()
                .putString("profiles", Base64.encodeToString(bytes, Base64.NO_WRAP))
                .commit()
        )

        var rejected = false
        try {
            store.load()
        } catch (_: Exception) {
            rejected = true
        }
        assertTrue("AES-GCM must reject modified ciphertext.", rejected)
    }

    @Test
    fun failoverSettingsAreClampedWhenSavedAndLoaded() {
        val store = AutoFailoverSettingsStore(context)
        store.save(
            AutoFailoverSettings(
                enabled = false,
                checkIntervalSeconds = 1,
                failureThreshold = 99,
                catalogRefreshMinutes = 100,
            )
        )

        assertEquals(
            AutoFailoverSettings(
                enabled = false,
                checkIntervalSeconds = 30,
                failureThreshold = 5,
                catalogRefreshMinutes = 30,
            ),
            store.load(),
        )
    }

    private fun clearPreferences() {
        listOf(
            "tgu_encrypted_proxy_profiles",
            "tgu_upstream_route",
            "tgu_local_proxy_settings",
            "tgu_auto_failover",
        ).forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }
}
