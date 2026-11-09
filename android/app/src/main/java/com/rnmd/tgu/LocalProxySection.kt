package com.rnmd.tgu

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rnmd.tgu.proxy.LocalProxyRuntime
import com.rnmd.tgu.proxy.LocalProxyService
import com.rnmd.tgu.proxy.LocalProxyStatus

@Composable
fun LocalProxySection() {
    val context = LocalContext.current
    val state by LocalProxyRuntime.state.collectAsState()
    val statusColor = when (state.status) {
        LocalProxyStatus.RUNNING -> MaterialTheme.colorScheme.primary
        LocalProxyStatus.ERROR -> MaterialTheme.colorScheme.error
        LocalProxyStatus.STARTING, LocalProxyStatus.STOPPING -> MaterialTheme.colorScheme.tertiary
        LocalProxyStatus.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val canStart = state.status == LocalProxyStatus.STOPPED || state.status == LocalProxyStatus.ERROR
    val canStop = state.status == LocalProxyStatus.RUNNING || state.status == LocalProxyStatus.STARTING
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("LOOPBACK FRONTEND", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(state.status.name, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = statusColor, fontFamily = FontFamily.Monospace)
            }
            Text("127.0.0.1:1443", fontSize = 23.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
            Text(state.detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 17.sp)
            Text("Telegram always connects to 127.0.0.1:1443. TGU can route outbound sessions through a selected classic MTProto or SOCKS5 upstream from Freedom Checker. WEB/FakeTLS entries are not supported as upstream yet.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 15.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val startIntent = Intent(context, LocalProxyService::class.java).setAction(LocalProxyService.ACTION_START)
                        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(startIntent) else context.startService(startIntent)
                    },
                    modifier = Modifier.weight(1f).height(46.dp),
                    enabled = canStart,
                    shape = RoundedCornerShape(8.dp)
                ) { Text(if (state.status == LocalProxyStatus.ERROR) "RESTART ENGINE" else "START ENGINE", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                OutlinedButton(
                    onClick = {
                        context.startService(Intent(context, LocalProxyService::class.java).setAction(LocalProxyService.ACTION_STOP))
                    },
                    modifier = Modifier.weight(1f).height(46.dp),
                    enabled = canStop,
                    shape = RoundedCornerShape(8.dp)
                ) { Text("STOP", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            }
            Text(
                "Передача настроек в Telegram доступна только кнопкой «Применить Proxy в Telegram» на главном экране.",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp,
            )
        }
    }
}