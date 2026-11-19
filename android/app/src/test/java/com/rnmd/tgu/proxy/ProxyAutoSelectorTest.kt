package com.rnmd.tgu.proxy

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ProxyAutoSelectorTest {
    @Test
    fun emptyCatalogReturnsNoSelectionWithoutProbing() = runBlocking {
        var probeCalls = 0
        val result = ProxyAutoSelector(probe = { _, _, _ ->
            probeCalls++
            success(1)
        }).findBest(emptyList())

        assertNull(result.selected)
        assertEquals(0, result.totalCandidates)
        assertEquals(0, probeCalls)
        assertFalse(ProxyAutoSelectionRuntime.state.value.isRunning)
    }

    @Test
    fun filtersUnsupportedStaleAndIncompleteEntriesAndRechecksOnlyFastestFive() = runBlocking {
        val latencies = (1..6).associate { "candidate-$it" to it.toLong() * 10L }
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        val candidates = (1..6).map { candidate("candidate-$it") } + listOf(
            candidate("unsupported", supported = false),
            candidate("stale", stale = true),
            candidate("incomplete", profile = null),
        )

        val result = ProxyAutoSelector(probe = { profile, _, _ ->
            calls.computeIfAbsent(profile.id) { AtomicInteger() }.incrementAndGet()
            success(latencies.getValue(profile.id))
        }).findBest(candidates)

        assertEquals("candidate-1", result.selected?.profile?.id)
        assertEquals(6, result.totalCandidates)
        assertEquals(6, result.firstPassAvailable)
        assertEquals(10L, result.verifiedLatencyMs)
        (1..5).forEach { id ->
            assertEquals("Fast finalist $id should be probed three times.", 3, calls["candidate-$id"]?.get())
        }
        assertEquals("The sixth candidate only participates in the first sweep.", 1, calls["candidate-6"]?.get())
        assertFalse(ProxyAutoSelectionRuntime.state.value.isRunning)
    }

    @Test
    fun unstableFastCandidateIsRejectedInFavorOfRepeatedlyVerifiedCandidate() = runBlocking {
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        val entries = listOf(candidate("flaky"), candidate("stable"))

        val result = ProxyAutoSelector(probe = { profile, _, _ ->
            val attempt = calls.computeIfAbsent(profile.id) { AtomicInteger() }.incrementAndGet()
            when {
                profile.id == "flaky" && attempt > 1 -> failure()
                profile.id == "flaky" -> success(1)
                else -> success(20)
            }
        }).findBest(entries)

        assertEquals("stable", result.selected?.profile?.id)
        assertEquals(2, result.firstPassAvailable)
        assertEquals(3, calls["stable"]?.get())
        assertEquals(3, calls["flaky"]?.get())
    }

    @Test
    fun ruCandidateWinsOnlyWhenItPassedLocalChecksAndIsCompetitive() = runBlocking {
        val result = ProxyAutoSelector(probe = { profile, _, _ ->
            if (profile.id == "ru") success(105) else success(100)
        }).findBest(listOf(candidate("global"), candidate("ru").copy(ruVerified = true, ruLatencyMs = 70)))
        assertEquals("ru", result.selected?.profile?.id)
        assertEquals(2, result.firstPassAvailable)
    }

    @Test
    fun unreachableRuCandidateNeverBeatsLocallyWorkingGlobal() = runBlocking {
        val result = ProxyAutoSelector(probe = { profile, _, _ ->
            if (profile.id == "ru") failure() else success(100)
        }).findBest(listOf(candidate("ru").copy(ruVerified = true, ruLatencyMs = 10), candidate("global")))
        assertEquals("global", result.selected?.profile?.id)
    }

    @Test
    fun fastConnectStopsAtFirstLocallyVerifiedRuProxy() = runBlocking {
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        val candidates = listOf(
            candidate("ru-1").copy(ruVerified = true, ruLatencyMs = 20),
            candidate("ru-2").copy(ruVerified = true, ruLatencyMs = 30),
            candidate("global"),
        )
        val selector = ProxyAutoSelector(probe = { profile, _, _ ->
            calls.computeIfAbsent(profile.id) { AtomicInteger() }.incrementAndGet()
            if (profile.id == "ru-1") success(40) else failure()
        })
        val result = selector.findFirstVerifiedRu(candidates)
        assertEquals("ru-1", result?.selected?.profile?.id)
        assertEquals(2, calls["ru-1"]?.get())
        assertEquals(null, calls["global"]?.get())
        assertEquals(null, calls["ru-2"]?.get())
    }

    @Test
    fun fastConnectRequiresTwoSuccessfulLocalChecks() = runBlocking {
        val calls = AtomicInteger()
        val selector = ProxyAutoSelector(probe = { _, _, _ ->
            if (calls.incrementAndGet() == 1) success(20) else failure()
        })
        val result = selector.findFirstVerifiedRu(
            listOf(candidate("ru").copy(ruVerified = true, ruLatencyMs = 10))
        )
        assertNull(result)
    }

    @Test
    fun noSuccessfulProbeNeverSelectsAnUpstream() = runBlocking {
        var calls = 0
        val result = ProxyAutoSelector(probe = { _, _, _ ->
            calls++
            failure()
        }).findBest(listOf(candidate("dead")))

        assertNull(result.selected)
        assertEquals(1, calls)
        assertEquals(0, result.firstPassAvailable)
    }

    private fun candidate(
        id: String,
        supported: Boolean = true,
        stale: Boolean = false,
        profile: ProxyProfile? = ProxyProfile(
            id = id,
            name = "Test $id",
            type = ProxyType.SOCKS5,
            host = "$id.example.org",
            port = 1080,
        ),
    ) = FreedomCheckerProxy(
        id = id,
        typeLabel = "SOCKS5",
        endpoint = "$id.example.org:1080",
        lastChecked = "",
        profile = profile,
        supported = supported,
        stale = stale,
    )

    private fun success(latencyMs: Long) = ProxyTestResult(
        success = true,
        title = "Success",
        detail = "Injected deterministic success",
        elapsedMs = latencyMs,
    )

    private fun failure() = ProxyTestResult(
        success = false,
        title = "Unavailable",
        detail = "Injected deterministic failure",
        elapsedMs = 0L,
    )
}
