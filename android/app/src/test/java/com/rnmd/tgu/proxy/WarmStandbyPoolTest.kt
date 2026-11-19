package com.rnmd.tgu.proxy

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WarmStandbyPoolTest {
    private fun entry(id: String) = FreedomCheckerProxy(
        id = id, typeLabel = "SOCKS5", endpoint = "$id.test:1080",
        lastChecked = "", supported = true,
        profile = ProxyProfile(id = id, name = id, type = ProxyType.SOCKS5, host = "1.1.1.1", port = 1080),
    )
    private fun success() = ProxyTestResult(
        success = true, title = "success", detail = "handshake",
        elapsedMs = 20,
    )
    private fun failure() = ProxyTestResult(
        success = false, title = "failed", detail = "timeout",
        elapsedMs = 0,
    )

    @Test fun backupMustBeReverifiedBeforeFailover() = runBlocking {
        var calls = 0
        val pool = WarmStandbyPool(probe = { _, _, _ -> calls++; success() })
        pool.scan(listOf(entry("active"), entry("backup")), "active")
        assertEquals(1, pool.size())
        assertEquals("backup", pool.findValidBackup(setOf("active"))?.id)
        assertEquals(4, calls)
    }

    @Test fun deadBackupIsNeverSelected() = runBlocking {
        var calls = 0
        val pool = WarmStandbyPool(probe = { _, _, _ ->
            calls++
            if (calls <= 2) success() else failure()
        })
        pool.scan(listOf(entry("backup")), "active")
        assertNull(pool.findValidBackup(emptySet()))
        assertEquals(0, pool.size())
    }
}
