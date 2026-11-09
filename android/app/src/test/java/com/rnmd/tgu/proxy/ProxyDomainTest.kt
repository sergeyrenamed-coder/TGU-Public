package com.rnmd.tgu.proxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyDomainTest {
    private val classicSecret = "00112233445566778899aabbccddeeff"

    @Test
    fun acceptsValidMtProtoAndSocks5Profiles() {
        val mtproto = profile(
            type = ProxyType.MTPROTO,
            host = "proxy.example.org",
            port = 443,
            secret = classicSecret,
        )
        val socks5 = profile(
            type = ProxyType.SOCKS5,
            host = "127.0.0.1",
            port = 1080,
        )

        assertNull(mtproto.validationError())
        assertNull(socks5.validationError())
    }

    @Test
    fun rejectsInvalidProfileNamesHostsPortsAndMtProtoSecrets() {
        assertNotNull(profile(name = "   ").validationError())
        assertNotNull(profile(name = "x".repeat(49)).validationError())
        assertNotNull(profile(host = "https://proxy.example.org").validationError())
        assertNotNull(profile(host = "proxy example.org").validationError())
        assertNotNull(profile(port = 0).validationError())
        assertNotNull(profile(port = 65536).validationError())
        assertNotNull(profile(secret = "not-hex").validationError())
        assertNotNull(profile(secret = "0".repeat(31)).validationError())
    }

    @Test
    fun requiresSocks5CredentialsAsAPairAndLimitsUtf8Length() {
        val onlyUsername = profile(
            type = ProxyType.SOCKS5,
            username = "user",
            password = "",
        )
        val onlyPassword = profile(
            type = ProxyType.SOCKS5,
            username = "",
            password = "password",
        )
        val oversizedUtf8Username = profile(
            type = ProxyType.SOCKS5,
            username = "é".repeat(128),
            password = "password",
        )

        assertNotNull(onlyUsername.validationError())
        assertNotNull(onlyPassword.validationError())
        assertNotNull(oversizedUtf8Username.validationError())
        assertNull(
            profile(type = ProxyType.SOCKS5, username = "user", password = "password")
                .validationError()
        )
    }

    @Test
    fun acceptsClassicAndPaddedMtProtoSecretsButRejectsWebFakeTls() {
        val classic = UpstreamProxyConnector.mtProtoSecretBytes(classicSecret)
        val padded = UpstreamProxyConnector.mtProtoSecretBytes("dd$classicSecret")
        assertArrayEquals(classic, padded)
        assertEquals(16, classic.size)
        assertTrue(UpstreamProxyConnector.supportsMtProtoProfile(profile(secret = classicSecret)))
        assertTrue(UpstreamProxyConnector.supportsMtProtoProfile(profile(secret = "dd$classicSecret")))
        assertFalse(
            UpstreamProxyConnector.supportsMtProtoProfile(
                profile(secret = "ee" + "11".repeat(15))
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedMtProtoSecretBytes() {
        UpstreamProxyConnector.mtProtoSecretBytes("1234")
    }

    @Test
    fun parsesOnlyAllowedStrictDcRedirectEntries() {
        val parsed = ProxyConfig.parseDcRedirectsStrict(
            "1:149.154.175.50\n2:149.154.167.51"
        )

        assertEquals(
            mapOf(1 to "149.154.175.50", 2 to "149.154.167.51"),
            parsed,
        )
        assertNull(ProxyConfig.parseDcRedirectsStrict("9:149.154.175.50"))
        assertNull(ProxyConfig.parseDcRedirectsStrict("1:999.999.999.999"))
    }

    @Test
    fun selectsTheExpectedRuntimeRouteMode() {
        assertEquals(RuntimeRouteMode.WS_CF_TCP, ProxyConfig().runtimeRouteMode())
        assertEquals(
            RuntimeRouteMode.CF_WS_TCP,
            ProxyConfig(cfProxyFirst = true).runtimeRouteMode(),
        )
        assertEquals(
            RuntimeRouteMode.WS_TCP_CF,
            ProxyConfig(cfProxyPriority = false).runtimeRouteMode(),
        )
        assertEquals(
            RuntimeRouteMode.WS_TCP,
            ProxyConfig(cfProxyEnabled = false).runtimeRouteMode(),
        )
    }

    @Test
    fun generatesUniqueThirtyTwoCharacterHexSecrets() {
        val first = ProxyConfig.generateSecret()
        val second = ProxyConfig.generateSecret()

        assertTrue(first.matches(Regex("^[0-9a-f]{32}$")))
        assertTrue(second.matches(Regex("^[0-9a-f]{32}$")))
        assertTrue(first != second)
        assertArrayEquals(
            ByteArray(16) { index -> index.toByte() },
            ProxyConfig(secret = "000102030405060708090a0b0c0d0e0f").secretBytes(),
        )
    }

    @Test
    fun failoverPolicyHonorsEnableThresholdAndRetryWindow() {
        val settings = AutoFailoverSettings(
            enabled = true,
            failureThreshold = 3,
        )
        assertFalse(ProxyFailoverPolicy.shouldAttemptFailover(settings, 3, 99, 100))
        assertFalse(ProxyFailoverPolicy.shouldAttemptFailover(settings, 2, 100, 100))
        assertTrue(ProxyFailoverPolicy.shouldAttemptFailover(settings, 3, 100, 100))
        assertFalse(
            ProxyFailoverPolicy.shouldAttemptFailover(
                settings.copy(enabled = false),
                10,
                500,
                100,
            )
        )
    }

    @Test
    fun autoOptimizationRequiresAMeaningfulLatencyGain() {
        assertFalse(ProxyFailoverPolicy.isMeaningfullyFaster(120, 100))
        assertTrue(ProxyFailoverPolicy.isMeaningfullyFaster(120, 99))
        assertFalse(ProxyFailoverPolicy.isMeaningfullyFaster(60, 50))
        assertTrue(ProxyFailoverPolicy.isMeaningfullyFaster(60, 49))
        assertFalse(ProxyFailoverPolicy.isMeaningfullyFaster(0, 1))
        assertFalse(ProxyFailoverPolicy.isMeaningfullyFaster(60, -1))
    }

    @Test
    fun onlyCatalogOwnedProfilesCanBeAutomaticallyOptimized() {
        assertTrue(ProxyFailoverPolicy.isAutoManagedProfile(profile(name = "TGU Catalog · sample")))
        assertTrue(ProxyFailoverPolicy.isAutoManagedProfile(profile(name = "freedom checker · sample")))
        assertFalse(ProxyFailoverPolicy.isAutoManagedProfile(profile(name = "My personal proxy")))
    }

    private fun profile(
        name: String = "Test profile",
        type: ProxyType = ProxyType.MTPROTO,
        host: String = "proxy.example.org",
        port: Int = 443,
        secret: String = classicSecret,
        username: String = "",
        password: String = "",
    ) = ProxyProfile(
        id = "test-profile",
        name = name,
        type = type,
        host = host,
        port = port,
        secret = secret,
        username = username,
        password = password,
    )
}
