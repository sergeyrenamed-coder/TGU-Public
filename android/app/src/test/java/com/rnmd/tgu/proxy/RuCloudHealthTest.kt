package com.rnmd.tgu.proxy

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuCloudHealthTest {
    private val now = Instant.parse("2026-11-19T02:00:00Z").toEpochMilli()
    private val id = "400e228c511c694acb13"
    private fun feed(
        generated: String = "2026-11-19T01:00:00Z",
        checked: String = "2026-11-19T01:30:00Z",
        status: String = "working",
        latency: String = "203",
    ) = """
        {"schema_version":1,"checker":"yandex_cloud_ru","generated_at":"$generated",
         "status":"active","ttl_hours":24,"checks":[
          {"proxy_id":"$id","status":"$status","checked_at":"$checked","latency_ms":$latency}
         ]}
    """.trimIndent()

    @Test fun freshSuccessIsOnlyPriorityEvidence() {
        assertEquals(203L, RuCloudHealth.verified(feed(), now)[id]?.latencyMs)
    }
    @Test fun staleAndFutureChecksNeverVerify() {
        assertTrue(RuCloudHealth.verified(feed(checked = "2026-11-17T01:30:00Z"), now).isEmpty())
        assertTrue(RuCloudHealth.verified(feed(checked = "2026-11-20T01:30:00Z"), now).isEmpty())
        assertFalse(RuCloudHealth.isFreshFeed(feed(generated = "2026-11-17T01:00:00Z"), now))
    }
    @Test fun failedOrInvalidChecksNeverVerify() {
        assertTrue(RuCloudHealth.verified(feed(status = "failed", latency = "null"), now).isEmpty())
        assertTrue(RuCloudHealth.verified(feed(latency = "-5"), now).isEmpty())
        assertTrue(RuCloudHealth.verified(feed().replace("yandex_cloud_ru", "other"), now).isEmpty())
    }
}
