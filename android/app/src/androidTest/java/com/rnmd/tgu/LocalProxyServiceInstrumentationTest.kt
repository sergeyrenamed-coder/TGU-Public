package com.rnmd.tgu

import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rnmd.tgu.proxy.AutoFailoverSettingsStore
import com.rnmd.tgu.proxy.LocalProxyRuntime
import com.rnmd.tgu.proxy.LocalProxyService
import com.rnmd.tgu.proxy.LocalProxySettingsStore
import com.rnmd.tgu.proxy.LocalProxyStatus
import java.net.InetSocketAddress
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalProxyServiceInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun foregroundServiceBindsLoopbackListenerAndStopsCleanly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val startIntent = Intent(context, LocalProxyService::class.java)
            .setAction(LocalProxyService.ACTION_START)
        val stopIntent = Intent(context, LocalProxyService::class.java)
            .setAction(LocalProxyService.ACTION_STOP)
        val failoverSettingsStore = AutoFailoverSettingsStore(context)
        val previousFailoverSettings = failoverSettingsStore.load()
                                                                               
                                                                                
        failoverSettingsStore.save(previousFailoverSettings.copy(enabled = false))

        composeRule.runOnIdle {
            LocalProxyRuntime.publish(LocalProxyStatus.STOPPED, "Test setup")
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(startIntent)
            } else {
                context.startService(startIntent)
            }

            composeRule.waitUntil(20_000) {
                LocalProxyRuntime.state.value.status in setOf(
                    LocalProxyStatus.RUNNING,
                    LocalProxyStatus.ERROR,
                )
            }
            assertEquals(
                "The local proxy service should reach RUNNING before accepting clients.",
                LocalProxyStatus.RUNNING,
                LocalProxyRuntime.state.value.status,
            )

            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(
                        LocalProxySettingsStore.LOCAL_HOST,
                        LocalProxySettingsStore.LOCAL_PORT,
                    ),
                    3_000,
                )
                assertTrue(socket.isConnected)
            }

            context.startService(stopIntent)
            composeRule.waitUntil(15_000) {
                LocalProxyRuntime.state.value.status == LocalProxyStatus.STOPPED
            }
            assertEquals(LocalProxyStatus.STOPPED, LocalProxyRuntime.state.value.status)
        } finally {
            context.startService(stopIntent)
            runCatching {
                composeRule.waitUntil(15_000) {
                    LocalProxyRuntime.state.value.status == LocalProxyStatus.STOPPED
                }
            }
            failoverSettingsStore.save(previousFailoverSettings)
        }
    }
}
