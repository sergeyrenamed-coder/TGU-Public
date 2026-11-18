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


    @Test
    fun trustedCommunityCandidatesAreCheckedFirstAndVerifiedLocally() = runBlocking {
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        val entries = (1..30).map { candidate("ordinary-$it") } + candidate(
            "trusted", communityReports = 20, communityUniqueReporters = 5,
            communitySuccessRate = 95.0, communityMedianRttMs = 65L,
        )

        val result = ProxyAutoSelector(probe = { profile, _, _ ->
            calls.computeIfAbsent(profile.id) { AtomicInteger() }.incrementAndGet()
            success(55)
        }).findBest(entries)

        assertEquals("trusted", result.selected?.profile?.id)
        assertEquals(1, result.totalCandidates)
        assertEquals(3, calls["trusted"]?.get())
        assertEquals(1, calls.size)
        assertFalse(ProxyAutoSelectionRuntime.state.value.isRunning)
    }

    @Test
    fun failedCommunityPriorityFallsBackToOriginalFullCatalog() = runBlocking {
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        val entries = listOf(
            candidate("trusted", communityReports = 30, communityUniqueReporters = 5,
                communitySuccessRate = 99.0),
            candidate("ordinary"),
        )
        val result = ProxyAutoSelector(probe = { profile, _, _ ->
            calls.computeIfAbsent(profile.id) { AtomicInteger() }.incrementAndGet()
            if (profile.id == "trusted") failure() else success(45)
        }).findBest(entries)

        assertEquals("ordinary", result.selected?.profile?.id)
        assertEquals(2, result.totalCandidates)
        assertEquals(3, calls["ordinary"]?.get())
        assertFalse(ProxyAutoSelectionRuntime.state.value.isRunning)
    }

    @Test
    fun weakCommunitySignalsDoNotBypassFullCatalog() = runBlocking {
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        val entries = listOf(
            candidate("weak", communityReports = 1, communityUniqueReporters = 1,
                communitySuccessRate = 100.0),
            candidate("normal"),
        )
        ProxyAutoSelector(probe = { profile, _, _ ->
            calls.computeIfAbsent(profile.id) { AtomicInteger() }.incrementAndGet()
            success(if (profile.id == "normal") 10 else 100)
        }).findBest(entries)
        assertEquals(3, calls["normal"]?.get())
        assertEquals(3, calls["weak"]?.get())
    }

    private fun candidate(
        id: String,
        supported: Boolean = true,
        stale: Boolean = false,
        communityReports: Int = 0,
        communityUniqueReporters: Int = 0,
        communitySuccessRate: Double? = null,
        communityMedianRttMs: Long? = null,
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
        communityReports = communityReports,
        communityUniqueReporters = communityUniqueReporters,
        communitySuccessRate = communitySuccessRate,
        communityMedianRttMs = communityMedianRttMs,
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
