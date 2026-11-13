package com.rnmd.tgu

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.rnmd.tgu.diagnostics.NetworkDiagnostics
import com.rnmd.tgu.diagnostics.NetworkReport
import com.rnmd.tgu.proxy.AutoFailoverSettings
import com.rnmd.tgu.proxy.AutoFailoverSettingsStore
import com.rnmd.tgu.proxy.ProxyProfileStore
import com.rnmd.tgu.proxy.ProxyAutoSelectionRuntime
import com.rnmd.tgu.proxy.ProxyAutoSelector
import com.rnmd.tgu.proxy.FreedomCheckerCatalogRepository
import com.rnmd.tgu.proxy.TguCommunityFeedbackClient
import com.rnmd.tgu.proxy.LocalProxyRuntime
import com.rnmd.tgu.proxy.LocalProxyService
import com.rnmd.tgu.proxy.LocalProxySettingsStore
import com.rnmd.tgu.proxy.LocalProxySnapshot
import com.rnmd.tgu.proxy.LocalProxyStatus
import com.rnmd.tgu.proxy.ProxyMonitorRuntime
import com.rnmd.tgu.proxy.ProxyMonitorSnapshot
import com.rnmd.tgu.proxy.ProxyProfile
import com.rnmd.tgu.proxy.UpstreamProxySettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import com.rnmd.tgu.account.RnmdAccountRepository
import com.rnmd.tgu.account.RnmdAccountSnapshot
import com.rnmd.tgu.account.RnmdIdClient
import com.rnmd.tgu.account.TelegramNativeLogin
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TguBackground = Color(0xFF090909)
private val TguPanel = Color(0xFF151515)
private val TguPanelRaised = Color(0xFF202020)
private val TguStroke = Color(0xFF333333)
private val TguText = Color(0xFFF5F5F5)
private val TguMuted = Color(0xFFA3A3A3)
private val TguGreen = Color(0xFFF5F5F5)
private val TguBlue = Color(0xFFD4D4D4)
private val TguRed = Color(0xFFE0E0E0)
private val TguAmber = Color(0xFFD4D4D4)

class MainActivity : ComponentActivity() {
    private lateinit var rnmdClient: SupabaseClient
    private val accountScreenRequested = mutableStateOf(false)
    private val authJob = SupervisorJob()
    private val authScope = CoroutineScope(Dispatchers.Main.immediate + authJob)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rnmdClient = RnmdIdClient.get(applicationContext)
        handleAuthRedirect(intent)
        window.statusBarColor = android.graphics.Color.rgb(9, 9, 9)
        window.navigationBarColor = android.graphics.Color.rgb(9, 9, 9)
        setContent { TguTheme { TguScreen(rnmdClient, accountScreenRequested.value) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (::rnmdClient.isInitialized) handleAuthRedirect(intent)
    }

    private fun handleAuthRedirect(intent: Intent) {
        val data = intent.data ?: return

        if (TelegramNativeLogin.isCallback(data)) {
            accountScreenRequested.value = true
            authScope.launch {
                try {
                    TelegramNativeLogin.complete(this@MainActivity, data, rnmdClient)
                    Toast.makeText(
                        this@MainActivity,
                        "Вход в RNMD ID выполнен.",
                        Toast.LENGTH_SHORT,
                    ).show()
                } catch (_: Exception) {
                    Toast.makeText(
                        this@MainActivity,
                        "Не удалось завершить вход Telegram. Вернись в аккаунт и попробуй ещё раз.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            return
        }

                                                                          
        if (data.scheme != "tgu" || data.host != "auth" || data.path != "/callback") return
        accountScreenRequested.value = true

        val oauthError = data.getQueryParameter("error_description")
            ?: data.getQueryParameter("error")
        if (!oauthError.isNullOrBlank()) {
            Toast.makeText(this, "Вход через Telegram не завершён.", Toast.LENGTH_LONG).show()
            return
        }

        val code = data.getQueryParameter("code") ?: return
        authScope.launch {
            try {
                rnmdClient.auth.exchangeCodeForSession(code)
                Toast.makeText(this@MainActivity, "Вход в RNMD ID выполнен.", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(this@MainActivity, "Не удалось подтвердить вход. Попробуй ещё раз.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        authJob.cancel()
        super.onDestroy()
    }
}

@Composable
private fun TguTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = TguText,
            onPrimary = TguBackground,
            background = TguBackground,
            onBackground = TguText,
            surface = TguPanel,
            onSurface = TguText,
            secondary = TguMuted,
            error = TguRed,
        ),
        content = content,
    )
}

private enum class TguPage {
    HOME, CATALOG, DIAGNOSTICS, ACCOUNT, SETTINGS, AUTO_SELECT, PROXY_MANAGER, MONITORING
}

@Composable
private fun TguScreen(rnmdClient: SupabaseClient, openAccount: Boolean) {
    var page by remember { mutableStateOf(if (openAccount) TguPage.ACCOUNT else TguPage.HOME) }
    LaunchedEffect(openAccount) {
        if (openAccount) page = TguPage.ACCOUNT
    }
    val selectedTab = when (page) {
        TguPage.HOME, TguPage.AUTO_SELECT -> TguPage.HOME
        TguPage.CATALOG -> TguPage.CATALOG
        TguPage.DIAGNOSTICS -> TguPage.DIAGNOSTICS
        else -> TguPage.ACCOUNT
    }

    Column(Modifier.fillMaxSize().background(TguBackground)) {
        Box(Modifier.weight(1f)) {
            when (page) {
                TguPage.HOME -> HomeScreen(
                    onSettings = { page = TguPage.SETTINGS },
                    onCatalog = { page = TguPage.CATALOG },
                )
                TguPage.CATALOG -> Column(Modifier.fillMaxSize()) {
                    ScreenHeader("Каталог proxy", "TGU PROXY DIRECTORY")
                    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        FreedomCheckerSection()
                    }
                }
                TguPage.DIAGNOSTICS -> Column(Modifier.fillMaxSize()) {
                    ScreenHeader("Диагностика", "ПРОВЕРКА МАРШРУТА TELEGRAM")
                    DiagnosticsScreen()
                }
                TguPage.ACCOUNT -> Column(Modifier.fillMaxSize()) {
                    ScreenHeader("RNMD ID", "RNMDTEAM / IDENTITY")
                    AccountScreen(onSettings = { page = TguPage.SETTINGS }, supabase = rnmdClient)
                }
                TguPage.SETTINGS -> Column(Modifier.fillMaxSize()) {
                    ScreenHeader("Настройки", "TGU / КОНФИГУРАЦИЯ")
                    SettingsScreen(
                        onDiagnostics = { page = TguPage.DIAGNOSTICS },
                        onProxyManager = { page = TguPage.PROXY_MANAGER },
                        onCatalog = { page = TguPage.CATALOG },
                        onMonitoring = { page = TguPage.MONITORING },
                    )
                }
                TguPage.PROXY_MANAGER -> Column(Modifier.fillMaxSize()) {
                    ScreenHeader("Мои proxy", "НАСТРОЙКИ / ПРОФИЛИ", onBack = { page = TguPage.SETTINGS })
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
                        ProxyManagerSection()
                    }
                }
                TguPage.MONITORING -> Column(Modifier.fillMaxSize()) {
                    ScreenHeader("Мониторинг", "НАСТРОЙКИ / АВТОСМЕНА", onBack = { page = TguPage.SETTINGS })
                    MonitoringSettingsScreen()
                }
                TguPage.AUTO_SELECT -> Column(Modifier.fillMaxSize()) {
                    ScreenHeader("Автоподбор", "TGU / АВТОМАТИЧЕСКИЙ ВЫБОР", onBack = { page = TguPage.HOME })
                    AutoSelectionScreen(onBack = { page = TguPage.HOME })
                }
            }
        }
        TguBottomBar(selected = selectedTab, onSelect = { page = it })
    }
}

@Composable
private fun TguBottomBar(selected: TguPage, onSelect: (TguPage) -> Unit) {
    Column(Modifier.fillMaxWidth().background(TguBackground).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(TguStroke))
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 8.dp)
        ) {
            val itemWidth = maxWidth / 4
            val iconSize = when {
                itemWidth < 78.dp -> 16.dp
                itemWidth < 102.dp -> 18.dp
                else -> 20.dp
            }
            val labelSize = when {
                itemWidth < 78.dp -> 8.sp
                itemWidth < 88.dp -> 9.sp
                else -> 10.sp
            }
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                TguBottomItem(Modifier.weight(1f), "power", "Главная", selected == TguPage.HOME, iconSize, labelSize) { onSelect(TguPage.HOME) }
                TguBottomItem(Modifier.weight(1f), "catalog", "Каталог", selected == TguPage.CATALOG, iconSize, labelSize) { onSelect(TguPage.CATALOG) }
                TguBottomItem(Modifier.weight(1f), "diagnostics", "Диагностика", selected == TguPage.DIAGNOSTICS, iconSize, labelSize) { onSelect(TguPage.DIAGNOSTICS) }
                TguBottomItem(Modifier.weight(1f), "account", "Аккаунт", selected == TguPage.ACCOUNT, iconSize, labelSize) { onSelect(TguPage.ACCOUNT) }
            }
        }
    }
}

@Composable
private fun TguBottomItem(
    modifier: Modifier,
    icon: String,
    label: String,
    selected: Boolean,
    iconSize: Dp,
    labelSize: TextUnit,
    onClick: () -> Unit,
) {
    val iconColor = if (selected) TguText else TguMuted
    Column(
        modifier = modifier.fillMaxSize().clickable(onClick = onClick).padding(top = 8.dp, bottom = 5.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.width(iconSize).height(iconSize)) {
            val scaleX = size.width / 20f
            val scaleY = size.height / 20f
            scale(scaleX = scaleX, scaleY = scaleY, pivot = Offset.Zero) {
                val center = Offset(10f, 10f)
                val stroke = Stroke(width = 1.7f, cap = StrokeCap.Round)
                when (icon) {
                    "power" -> {
                        drawArc(
                            color = iconColor,
                            startAngle = 315f,
                            sweepAngle = 270f,
                            useCenter = false,
                            topLeft = Offset(2.5f, 2.5f),
                            size = Size(15f, 15f),
                            style = stroke,
                        )
                        drawLine(iconColor, Offset(center.x, 1f), Offset(center.x, 11f), strokeWidth = 1.8f, cap = StrokeCap.Round)
                    }
                    "catalog" -> {
                        drawLine(iconColor, Offset(2f, 5f), Offset(18f, 5f), strokeWidth = 1.7f, cap = StrokeCap.Round)
                        drawLine(iconColor, Offset(4f, 11f), Offset(16f, 11f), strokeWidth = 1.7f, cap = StrokeCap.Round)
                        drawLine(iconColor, Offset(7f, 17f), Offset(13f, 17f), strokeWidth = 1.7f, cap = StrokeCap.Round)
                    }
                    "diagnostics" -> {
                        val points = listOf(
                            Offset(1f, center.y),
                            Offset(6f, center.y),
                            Offset(9f, 5f),
                            Offset(13f, 17f),
                            Offset(16f, 8f),
                            Offset(19f, 8f),
                        )
                        points.zipWithNext().forEach { (start, end) ->
                            drawLine(iconColor, start, end, strokeWidth = 1.7f, cap = StrokeCap.Round)
                        }
                    }
                    "account" -> {
                        drawCircle(iconColor, radius = 3f, center = Offset(center.x, 6f), style = stroke)
                        drawArc(
                            color = iconColor,
                            startAngle = 180f,
                            sweepAngle = 180f,
                            useCenter = false,
                            topLeft = Offset(3f, 9f),
                            size = Size(14f, 10f),
                            style = stroke,
                        )
                    }
                }
            }
        }
        Text(
            label,
            modifier = Modifier.fillMaxWidth(),
            color = iconColor,
            fontSize = labelSize,
            lineHeight = labelSize * 1.2f,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun ScreenHeader(
    title: String,
    subtitle: String,
    onBack: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().height(68.dp).background(TguBackground)
            .padding(start = if (onBack == null) 20.dp else 8.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (onBack != null) {
            Box(
                Modifier.width(44.dp).height(48.dp).clickable { onBack() },
                contentAlignment = Alignment.CenterStart,
            ) {
                Text("‹", color = TguText, fontSize = 30.sp, lineHeight = 32.sp)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = TguText, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp)
            Text(subtitle, color = TguMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace, letterSpacing = 0.5.sp)
        }
        if (onMore != null) {
            Box(
                Modifier.width(40.dp).height(48.dp).clickable { onMore.invoke() },
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text("⋯", color = TguMuted, fontSize = 22.sp)
            }
        }
    }
}

@Composable
private fun TguSlidersGlyph(color: Color) {
    Canvas(Modifier.width(22.dp).height(22.dp)) {
        val strokeWidth = 1.7f
        val rows = listOf(
            Triple(3f, size.width - 3f, size.width * 0.36f),
            Triple(3f, size.width - 3f, size.width * 0.68f),
            Triple(3f, size.width - 3f, size.width * 0.48f),
        )
        rows.forEachIndexed { index, row ->
            val y = 4f + index * 7f
            drawLine(color, Offset(row.first, y), Offset(row.second, y), strokeWidth, cap = StrokeCap.Round)
            drawCircle(color, radius = 2.1f, center = Offset(row.third, y))
        }
    }
}

@Composable
private fun TguPowerGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val diameter = minOf(size.width, size.height)
        val inset = diameter * 0.15f
        val arcDiameter = diameter - inset * 2f
        val strokeWidth = diameter * 0.065f
        val center = Offset(size.width / 2f, size.height / 2f)
        drawArc(
            color = color,
            startAngle = 315f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(center.x - arcDiameter / 2f, center.y - arcDiameter / 2f),
            size = Size(arcDiameter, arcDiameter),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )
        drawLine(
            color = color,
            start = Offset(center.x, center.y - diameter * 0.43f),
            end = Offset(center.x, center.y),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun HomeScreen(
    onSettings: () -> Unit,
    onCatalog: () -> Unit,
) {
    val context = LocalContext.current
    val engine by LocalProxyRuntime.state.collectAsState()
    val monitor by ProxyMonitorRuntime.state.collectAsState()
    val autoSelection by ProxyAutoSelectionRuntime.state.collectAsState()
    var savedProfile by remember { mutableStateOf<ProxyProfile?>(null) }

    val running = engine.status == LocalProxyStatus.RUNNING
    val busy = engine.status == LocalProxyStatus.STARTING || engine.status == LocalProxyStatus.STOPPING

    LaunchedEffect(monitor.activeProfileId, engine.status) {
        savedProfile = withContext(Dispatchers.IO) {
            runCatching {
                UpstreamProxySettingsStore(context.applicationContext).selectedProfile()
            }.getOrNull()
        }
    }

    val activeName = monitor.activeName ?: savedProfile?.name
    val activeType = monitor.activeType ?: savedProfile?.type?.name
    val activeEndpoint = monitor.activeEndpoint ?: savedProfile?.let { it.host + ":" + it.port }

    fun toggleEngine() {
        val action = if (running || busy) LocalProxyService.ACTION_STOP else LocalProxyService.ACTION_START
        val intent = Intent(context, LocalProxyService::class.java).setAction(action)
        if (action == LocalProxyService.ACTION_START && Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().height(68.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text("TGU", color = TguText, fontSize = 27.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text("TELEGRAM UNLOCKER", color = TguMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace, letterSpacing = 0.4.sp)
            }
            Box(
                Modifier.width(48.dp).height(48.dp).clickable(onClick = onSettings),
                contentAlignment = Alignment.Center,
            ) {
                TguSlidersGlyph(TguMuted)
            }
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConnectionPowerControl(
                status = engine.status,
                detail = engine.detail,
                onPowerClick = ::toggleEngine,
            )

                                                                        
            OutlinedButton(
                onClick = {
                    try {
                        val uri = LocalProxySettingsStore(context.applicationContext).telegramProxyUri()
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (e: Exception) {
                        Toast.makeText(
                            context,
                            "Не удалось открыть Telegram: " + e.javaClass.simpleName,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = running,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, TguStroke),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = TguPanel,
                    contentColor = TguText,
                    disabledContentColor = TguMuted,
                    disabledContainerColor = TguPanel,
                ),
            ) {
                Text("ПРИМЕНИТЬ PROXY В TELEGRAM", fontSize = 13.sp, fontWeight = FontWeight.Normal)
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = TguPanel),
                border = BorderStroke(1.dp, TguStroke),
            ) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("ВЫБРАННЫЙ UPSTREAM", color = TguMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text("Сменить →", modifier = Modifier.clickable(onClick = onCatalog),
                            color = TguText, fontSize = 11.sp)
                    }
                    Text(
                        activeEndpoint ?: activeName ?: "Upstream не выбран",
                        color = TguText,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            if (activeType != null) "$activeType · на этом телефоне"
                            else "Ожидает автоматического выбора",
                            color = TguMuted,
                            fontSize = 11.sp,
                        )
                        Text(monitor.latencyMs?.let { "$it мс" } ?: "— мс",
                            color = TguText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Локальный сервис", color = TguMuted, fontSize = 11.sp)
                        Text(
                            when {
                                running -> "Запущен"
                                engine.status == LocalProxyStatus.STARTING -> "Запускается"
                                engine.status == LocalProxyStatus.STOPPING -> "Останавливается"
                                engine.status == LocalProxyStatus.ERROR -> "Ошибка"
                                else -> "Остановлен"
                            },
                            color = if (running) TguText else TguMuted,
                            fontSize = 11.sp,
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Смен upstream за сессию", color = TguMuted, fontSize = 11.sp)
                        Text(monitor.switchCount.toString(), color = TguText,
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    if (autoSelection.isRunning) {
                        Text(autoSelection.phase + " · " + autoSelection.completed + "/" + autoSelection.total,
                            color = TguMuted, fontSize = 9.sp, lineHeight = 14.sp)
                    } else if (monitor.status.isNotBlank()) {
                        Text(monitor.status, color = TguMuted, fontSize = 9.sp, lineHeight = 14.sp)
                    }
                }
            }

            Spacer(Modifier.weight(1f))
            Row(
                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("➤", color = TguMuted, fontSize = 14.sp)
                Text("Telegram: статус не подтверждён", color = TguMuted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ConnectionPowerControl(
    status: LocalProxyStatus,
    detail: String,
    onPowerClick: () -> Unit,
) {
    val running = status == LocalProxyStatus.RUNNING
    val starting = status == LocalProxyStatus.STARTING
    val stopping = status == LocalProxyStatus.STOPPING
    val canToggle = !starting && !stopping
    val hint = when {
        running -> "Подключено"
        starting -> "Подключение..."
        stopping -> "Остановка..."
        status == LocalProxyStatus.ERROR -> "Не удалось подключиться. Повторить"
        detail.isNotBlank() && status != LocalProxyStatus.STOPPED -> detail
        else -> "Нажмите для включения"
    }

    Column(
        Modifier.fillMaxWidth().padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Card(
            modifier = Modifier.width(76.dp).height(76.dp)
                .clickable(enabled = canToggle, onClick = onPowerClick),
            shape = RoundedCornerShape(100.dp),
            colors = CardDefaults.cardColors(containerColor = TguPanelRaised),
            border = BorderStroke(1.dp, TguMuted),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                TguPowerGlyph(TguText, Modifier.width(30.dp).height(30.dp))
            }
        }
        Text(
            hint,
            color = TguMuted,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 2,
        )
    }
}

@Composable
private fun AccountScreen(
    onSettings: () -> Unit,
    supabase: SupabaseClient,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authStatus by supabase.auth.sessionStatus.collectAsState()
    val session = (authStatus as? SessionStatus.Authenticated)?.session
    val repository = remember { RnmdAccountRepository(context.applicationContext) }
    val currentDeviceKey = remember { repository.getOrCreateDeviceKey() }

    var snapshot by remember { mutableStateOf<RnmdAccountSnapshot?>(null) }
    var loading by remember { mutableStateOf(false) }
    var loginBusy by remember { mutableStateOf(false) }
    var busyDeviceId by remember { mutableStateOf<String?>(null) }
    var pendingRevokeDevice by remember { mutableStateOf<com.rnmd.tgu.account.RnmdDevice?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var appLinkTrouble by remember { mutableStateOf(false) }
    var refreshCounter by remember { mutableStateOf(0) }

    LaunchedEffect(session?.accessToken, session?.user?.id, refreshCounter) {
        val currentSession = session
        val userId = currentSession?.user?.id
        if (currentSession == null || userId.isNullOrBlank()) {
            snapshot = null
            loading = authStatus is SessionStatus.Initializing
            return@LaunchedEffect
        }

        loading = true
        errorMessage = null
        try {
            snapshot = withContext(Dispatchers.IO) {
                repository.registerCurrentDevice(currentSession.accessToken, "0.9.6")
                repository.loadAccount(currentSession.accessToken, userId)
            }
        } catch (_: Exception) {
            errorMessage = "Не удалось загрузить аккаунт. Проверь подключение и попробуй обновить данные."
        } finally {
            loading = false
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            authStatus is SessionStatus.Initializing && session == null -> {
                AccountPanel {
                    Text("RNMD ID", color = TguText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Проверяем сохранённую сессию…", color = TguMuted, fontSize = 11.sp)
                }
            }

            session == null -> {
                AccountPanel {
                    Text("RNMD ID", color = TguText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Единая учётная запись экосистемы RNMDTEAM. Она объединяет данные профиля, подключённые устройства, покупки и лицензии в одном месте. Вход выполняется через Telegram. Регистрация необязательна — основные функции TGU доступны без аккаунта.",
                        color = TguMuted,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                    if (authStatus is SessionStatus.RefreshFailure) {
                        Text("Сессия истекла или не обновилась. Войди снова.", color = TguMuted, fontSize = 10.sp)
                    }
                    errorMessage?.let { Text(it, color = TguRed, fontSize = 10.sp, lineHeight = 15.sp) }
                    if (appLinkTrouble) {
                        OutlinedButton(
                            onClick = {
                                try {
                                    TelegramNativeLogin.openAppLinkSettings(context)
                                } catch (_: Exception) {
                                    errorMessage = "Открой Настройки Android → TGU → Открывать по умолчанию."
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                        ) {
                            Text("НАСТРОИТЬ ССЫЛКИ TGU", fontSize = 10.sp)
                        }
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                loginBusy = true
                                errorMessage = null
                                appLinkTrouble = false
                                try {
                                    TelegramNativeLogin.start(context)
                                } catch (exception: TelegramNativeLogin.AppLinkUnavailableException) {
                                    appLinkTrouble = true
                                    errorMessage = exception.message
                                } catch (exception: Exception) {
                                    errorMessage = exception.message ?: "Не удалось открыть нативный вход Telegram."
                                } finally {
                                    loginBusy = false
                                }
                            }
                        },
                        enabled = !loginBusy,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(11.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TguText,
                            contentColor = TguBackground,
                            disabledContainerColor = TguPanelRaised,
                            disabledContentColor = TguMuted,
                        ),
                    ) {
                        Text(
                            if (loginBusy) "ОТКРЫВАЕМ TELEGRAM…" else "ПРОДОЛЖИТЬ С TELEGRAM",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        "Подтверждение проходит в приложении Telegram. Внешний браузер не используется.",
                        color = TguMuted,
                        fontSize = 9.sp,
                        lineHeight = 14.sp,
                    )
                }
            }

            else -> {
                val profile = snapshot?.profile
                val displayName = profile?.displayName
                    ?: profile?.telegramUsername
                    ?: session.user?.userMetadata?.get("name")?.toString()?.trim('"')
                    ?: "Пользователь RNMD"
                val username = profile?.telegramUsername?.let { "@$it" } ?: "Telegram username не указан"
                val shortId = session.user?.id?.take(8)?.uppercase() ?: "—"
                val hasPlus = snapshot?.hasTguPlus == true

                AccountPanel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("АККАУНТ RNMD ID", color = TguMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Text(displayName, color = TguText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text(username, color = TguMuted, fontSize = 11.sp)
                        }
                        Text(if (hasPlus) "TGU+" else "FREE", color = TguText, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(TguStroke))
                    Text("RNMD ID", color = TguMuted, fontSize = 9.sp)
                    Text(shortId, color = TguText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text(
                        if (hasPlus) "Лицензия TGU+ активна." else "К аккаунту привязан TGU Free. Платные возможности появятся после релиза TGU+.",
                        color = TguMuted,
                        fontSize = 10.sp,
                        lineHeight = 15.sp,
                    )
                }

                if (loading) {
                    AccountPanel {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Загружаем данные аккаунта…", color = TguMuted, fontSize = 11.sp)
                        }
                    }
                }

                errorMessage?.let {
                    AccountPanel {
                        Text("Не удалось синхронизировать RNMD ID", color = TguText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(it, color = TguMuted, fontSize = 10.sp)
                        OutlinedButton(
                            onClick = { refreshCounter += 1 },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text("ПОВТОРИТЬ", fontSize = 10.sp)
                        }
                    }
                }

                AccountPanel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Устройства", Modifier.weight(1f), color = TguText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text((snapshot?.devices?.size ?: 0).toString(), color = TguMuted, fontSize = 11.sp)
                    }
                    if (snapshot?.devices.isNullOrEmpty()) {
                        Text("Список устройств пока пуст.", color = TguMuted, fontSize = 10.sp)
                    } else {
                        snapshot?.devices?.forEach { device ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(device.name, color = TguText, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        device.platform.uppercase() + " · " + formatAccountDate(device.lastSeenAt),
                                        color = TguMuted,
                                        fontSize = 9.sp,
                                    )
                                }
                                if (device.deviceKey == currentDeviceKey) {
                                    Text(
                                        "ЭТО УСТРОЙСТВО",
                                        color = TguMuted,
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                } else {
                                    OutlinedButton(
                                        onClick = { pendingRevokeDevice = device },
                                        enabled = busyDeviceId == null,
                                        shape = RoundedCornerShape(9.dp),
                                    ) {
                                        Text(
                                            if (busyDeviceId == device.id) "…" else "Отозвать",
                                            fontSize = 9.sp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                AccountPanel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Покупки", Modifier.weight(1f), color = TguText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text((snapshot?.orders?.size ?: 0).toString(), color = TguMuted, fontSize = 11.sp)
                    }
                    if (snapshot?.orders.isNullOrEmpty()) {
                        Text("Покупок пока нет.", color = TguMuted, fontSize = 10.sp)
                    } else {
                        snapshot?.orders?.forEach { order ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(order.productCode, color = TguText, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                                    Text(formatAccountDate(order.createdAt), color = TguMuted, fontSize = 9.sp)
                                }
                                Text(
                                    (order.amountMinor / 100.0).toString() + " " + order.currency + " · " + accountOrderStatus(order.status),
                                    color = TguMuted,
                                    fontSize = 9.sp,
                                )
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            try {
                                supabase.auth.signOut()
                                snapshot = null
                                errorMessage = null
                            } catch (_: Exception) {
                                errorMessage = "Не удалось завершить сессию. Попробуй ещё раз."
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(11.dp),
                ) {
                    Text("ВЫЙТИ ИЗ RNMD ID", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        AccountPanel {
            Row(
                Modifier.fillMaxWidth().height(44.dp).clickable(onClick = onSettings),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TguSlidersGlyph(TguMuted)
                Text("Настройки приложения", Modifier.weight(1f).padding(start = 12.dp), color = TguText, fontSize = 12.sp)
                Text("›", color = TguMuted, fontSize = 22.sp)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(TguStroke))
            Row(
                Modifier.fillMaxWidth().height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("О приложении", Modifier.weight(1f), color = TguText, fontSize = 12.sp)
                Text("TGU 0.9.6 · RNMDTEAM", color = TguMuted, fontSize = 9.sp)
            }
        }
    }

    pendingRevokeDevice?.let { device ->
        AlertDialog(
            onDismissRequest = {
                if (busyDeviceId == null) pendingRevokeDevice = null
            },
            title = { Text("Отозвать устройство?") },
            text = {
                Text(
                    "Устройство «${device.name}» будет отключено от RNMD ID. Его серверная сессия будет удалена, а доступ к профилю, покупкам и лицензиям заблокирован. Для повторного входа потребуется авторизация через Telegram."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = busyDeviceId == null,
                    onClick = {
                        scope.launch {
                            val accessToken = session?.accessToken
                            if (accessToken.isNullOrBlank()) {
                                pendingRevokeDevice = null
                                return@launch
                            }
                            busyDeviceId = device.id
                            errorMessage = null
                            try {
                                val revoked = withContext(Dispatchers.IO) {
                                    repository.revokeDevice(accessToken, device.id)
                                }
                                if (!revoked) {
                                    throw IllegalStateException("Сессия устройства уже отсутствует.")
                                }
                                pendingRevokeDevice = null
                                refreshCounter += 1
                            } catch (_: Exception) {
                                errorMessage = "Не удалось отозвать устройство. Обнови список и попробуй ещё раз."
                            } finally {
                                busyDeviceId = null
                            }
                        }
                    },
                ) {
                    Text(if (busyDeviceId == device.id) "ОТЗЫВ…" else "ОТЗВАТЬ")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = busyDeviceId == null,
                    onClick = { pendingRevokeDevice = null },
                ) {
                    Text("ОТМЕНА")
                }
            },
        )
    }
}

@Composable
private fun AccountPanel(content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = TguPanel),
        border = BorderStroke(1.dp, TguStroke),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
            content = content,
        )
    }
}

private fun formatAccountDate(value: String): String {
    if (value.isBlank()) return "—"
    return runCatching {
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.parse(value))
    }.getOrDefault(value)
}

private fun accountOrderStatus(value: String): String = when (value) {
    "pending" -> "ожидает"
    "paid" -> "оплачено"
    "cancelled" -> "отменён"
    "refunded" -> "возврат"
    "expired" -> "истёк"
    "failed" -> "ошибка"
    else -> value
}


@Composable
private fun AutoSelectionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selection by ProxyAutoSelectionRuntime.state.collectAsState()
    val feedbackClient = remember(context) { TguCommunityFeedbackClient(context.applicationContext) }
    var stage by remember { mutableStateOf("READY") }
    var selectedProfile by remember { mutableStateOf<ProxyProfile?>(null) }
    var verifiedLatencyMs by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun startSelection() {
        if (stage == "SCANNING" || stage == "APPLYING") return
        stage = "SCANNING"
        error = null
        selectedProfile = null
        scope.launch {
            try {
                val repository = FreedomCheckerCatalogRepository(context.applicationContext)
                val snapshot = withContext(Dispatchers.IO) {
                    try { repository.refresh() }
                    catch (refreshError: Exception) { repository.cached() ?: throw refreshError }
                }
                val result = ProxyAutoSelector(
                    onScanCompleted = { scanId, reports -> feedbackClient.submitScan(scanId, reports) },
                ).findBest(snapshot.entries)
                val profile = result.selected?.profile ?: throw IllegalStateException(result.message)
                stage = "APPLYING"
                selectedProfile = profile
                verifiedLatencyMs = result.verifiedLatencyMs

                withContext(Dispatchers.IO) {
                    val profileStore = ProxyProfileStore(context.applicationContext)
                    val existing = profileStore.load()
                    if (existing.size >= 50 && existing.none { it.id == profile.id }) {
                        val oldCatalogProfile = existing.firstOrNull {
                            it.name.startsWith("TGU Catalog", ignoreCase = true) ||
                                it.name.startsWith("Freedom Checker", ignoreCase = true)
                        }
                        if (oldCatalogProfile != null) profileStore.delete(oldCatalogProfile.id)
                    }
                    profileStore.upsert(profile)
                    UpstreamProxySettingsStore(context.applicationContext).select(profile.id)
                }

                var serviceStatus = LocalProxyRuntime.state.value.status
                if (serviceStatus == LocalProxyStatus.STOPPING) {
                    withTimeout(15_000L) {
                        while (LocalProxyRuntime.state.value.status != LocalProxyStatus.STOPPED) delay(100L)
                    }
                    serviceStatus = LocalProxyRuntime.state.value.status
                }
                if (serviceStatus == LocalProxyStatus.RUNNING) {
                    context.startService(Intent(context, LocalProxyService::class.java).setAction(LocalProxyService.ACTION_RECONFIGURE))
                } else if (serviceStatus == LocalProxyStatus.STARTING) {
                    withTimeout(20_000L) {
                        while (LocalProxyRuntime.state.value.status != LocalProxyStatus.RUNNING) delay(100L)
                    }
                    context.startService(Intent(context, LocalProxyService::class.java).setAction(LocalProxyService.ACTION_RECONFIGURE))
                } else {
                    val startIntent = Intent(context, LocalProxyService::class.java).setAction(LocalProxyService.ACTION_START)
                    if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(startIntent)
                    else context.startService(startIntent)
                }
                withTimeout(20_000L) {
                    while (LocalProxyRuntime.state.value.status != LocalProxyStatus.RUNNING) delay(100L)
                }
                delay(500L)
                stage = "SUCCESS"
            } catch (exception: Exception) {
                error = exception.message ?: "Не удалось завершить автоматический подбор."
                stage = "ERROR"
            }
        }
    }

    val progressFraction = if (selection.total > 0)
        (selection.completed.toFloat() / selection.total.toFloat()).coerceIn(0f, 1f) else 0f

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("АВТОМАТИЧЕСКИЙ ПОДБОР", color = TguMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
        Text(
            when (stage) {
                "SCANNING" -> "Проверяем серверы"
                "APPLYING" -> "Применяем результат"
                "SUCCESS" -> "Лучший сервер найден"
                "ERROR" -> "Не удалось завершить подбор"
                else -> "Найдём лучший маршрут"
            },
            color = TguText, fontSize = 25.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp,
        )
        Text(
            when (stage) {
                "SCANNING" -> "Сравниваю MTProto и SOCKS5 через сеть, которой сейчас пользуется телефон."
                "APPLYING" -> "Сохраняю профиль и перенастраиваю локальный движок. Telegram останется без изменений до твоего нажатия на кнопку применения."
                "SUCCESS" -> "TGU проверил upstream и запустил локальный движок. Чтобы передать настройки, вернись на главный экран и нажми «Применить Proxy в Telegram». "
                "ERROR" -> "Проверь подключение и попробуй ещё раз. При неудачном подборе текущий профиль не удаляется."
                else -> "TGU проверит поддерживаемые записи каталога, затем повторно проверит пять самых быстрых вариантов."
            },
            color = TguMuted, fontSize = 11.sp, lineHeight = 17.sp,
        )
        when (stage) {
            "READY" -> {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = TguPanel),
                    border = BorderStroke(1.dp, TguStroke), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AutoStepRow("01", "Загрузить каталог", "TGU Proxy Directory + резервный источник")
                        AutoStepRow("02", "Проверить доступность", "Проверяем серверы и качество соединения с Telegram DC")
                        AutoStepRow("03", "Перепроверить лидеров", "Пять быстрых серверов проходят дополнительные проверки")
                        AutoStepRow("04", "Применить лучший", "Сохранить upstream и настроить локальный движок")
                    }
                }
                InfoCard("Проверка именно с этого устройства",
                    "Результат зависит от текущей сети. Для честного замера мобильного интернета отключи VPN перед запуском.")
            }
            "SCANNING", "APPLYING" -> {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = TguPanel),
                    border = BorderStroke(1.dp, TguStroke), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            StatusPill(if (stage == "SCANNING") "СКАНИРОВАНИЕ" else "ПРИМЕНЕНИЕ",
                                if (stage == "SCANNING") TguAmber else TguBlue)
                            Text(if (selection.total > 0) "${selection.completed.coerceAtMost(selection.total)}/${selection.total}" else "—",
                                color = TguText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { if (stage == "APPLYING") 1f else progressFraction },
                            modifier = Modifier.fillMaxWidth().height(5.dp),
                            color = TguGreen, trackColor = TguPanelRaised,
                        )
                        Text(selection.phase, color = TguText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MetricChip("ПРОВЕРЕНО", selection.completed.toString())
                            MetricChip("ДОСТУПНО", selection.available.toString())
                        }
                        selection.currentEndpoint?.let { Text("Сейчас: ${it}", color = TguMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace) }
                        selection.bestEndpoint?.let {
                            Text("Лидер: ${it} · ${selection.bestLatencyMs ?: "—"} мс",
                                color = TguGreen, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
            "SUCCESS" -> {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = TguPanel),
                    border = BorderStroke(1.dp, TguGreen.copy(alpha = 0.65f)), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatusPill("UPSTREAM АКТИВИРОВАН", TguGreen)
                        Text(selectedProfile?.name ?: "Лучший proxy", color = TguText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(selectedProfile?.let { "${it.host}:${it.port}" } ?: "Адрес не определён",
                            color = TguMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            selectedProfile?.let { MetricChip("ТИП", it.type.name) }
                            verifiedLatencyMs?.let { MetricChip("ЗАДЕРЖКА", "${it} мс") }
                        }
                    }
                }
                InfoCard(
                    "Telegram не изменён",
                    "Upstream выбран и локальный сервис готов. Настройки Telegram будут переданы только после отдельного нажатия кнопки «Применить Proxy в Telegram» на главном экране."
                )
            }
            "ERROR" -> InfoCard("Подбор не завершён", error ?: selection.message ?: "Неизвестная ошибка.")
        }
        when (stage) {
            "READY", "ERROR" -> Button(
                onClick = { startSelection() }, modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(11.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TguGreen, contentColor = Color(0xFF07120B)),
            ) { Text(if (stage == "ERROR") "ПОВТОРИТЬ ПОДБОР" else "НАЧАТЬ ПРОВЕРКУ", fontSize = 10.sp, fontWeight = FontWeight.Black) }
            "SUCCESS" -> {
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = TguPanelRaised, contentColor = TguText)) {
                    Text("ГОТОВО", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun AutoStepRow(number: String, title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Text(number, color = TguGreen, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = TguText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = TguMuted, fontSize = 9.sp, lineHeight = 14.sp)
        }
        Text("✓", color = TguGreen, fontSize = 12.sp)
    }
}

@Composable
private fun SettingsScreen(
    onDiagnostics: () -> Unit,
    onProxyManager: () -> Unit,
    onCatalog: () -> Unit,
    onMonitoring: () -> Unit,
) {
    val context = LocalContext.current
    val communityFeedback = remember(context) { TguCommunityFeedbackClient(context.applicationContext) }
    var feedbackEnabled by remember { mutableStateOf(communityFeedback.isEnabled()) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = TguPanel),
            border = BorderStroke(1.dp, TguStroke),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(Modifier.padding(horizontal = 15.dp, vertical = 5.dp)) {
                SettingsOptionRow("Мониторинг и автосмена", "Интервал, порог сбоев, сервис", "↻", onMonitoring)
                Box(Modifier.fillMaxWidth().height(1.dp).background(TguStroke))
                SettingsOptionRow("Каталог proxy", "Источники и обновление", "▤", onCatalog)
                Box(Modifier.fillMaxWidth().height(1.dp).background(TguStroke))
                SettingsOptionRow("Менеджер proxy", "Сохранённые профили", "◇", onProxyManager)
                Box(Modifier.fillMaxWidth().height(1.dp).background(TguStroke))
                SettingsOptionRow("Диагностика", "Проверка пути до Telegram DC", "⌁", onDiagnostics)
                Box(Modifier.fillMaxWidth().height(1.dp).background(TguStroke))
                SettingsOptionRow("О приложении", "TGU 0.9.6 · RNMDTEAM", "ⓘ", null)
            }
        }

        if (communityFeedback.isConfigured()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = TguPanel),
                border = BorderStroke(1.dp, TguStroke),
                shape = RoundedCornerShape(14.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Помогать улучшать каталог", color = TguText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Отправлять анонимные результаты сканирования, чтобы ускорять подбор другим пользователям. Без IP и данных Telegram.",
                            color = TguMuted, fontSize = 9.sp, lineHeight = 14.sp,
                        )
                    }
                    Switch(
                        checked = feedbackEnabled,
                        onCheckedChange = {
                            feedbackEnabled = it
                            communityFeedback.setEnabled(it)
                        },
                    )
                }
            }
        } else {
            Text(
                "TGU Community Network: адрес RNMDSTORE API пока не настроен в сборке.",
                color = TguMuted, fontSize = 9.sp, lineHeight = 14.sp,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("КАТАЛОГ TGU PROXY DIRECTORY", color = TguMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
            Text(
                "Сбор, дедупликация и нормализация публичных источников в единый JSON. Обновление примерно каждые 15 минут; при сбое используется кэш.",
                color = TguMuted,
                fontSize = 10.sp,
                lineHeight = 15.sp,
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("TGU · Telegram Unlocker", color = TguMuted, fontSize = 10.sp)
                Text("RNMDTEAM / ANDROID", color = TguMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
            }
            Text("0.9.6", color = TguText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun SettingsOptionRow(
    title: String,
    subtitle: String,
    symbol: String,
    onClick: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().height(59.dp)
            .clickable(enabled = onClick != null) { onClick?.invoke() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(symbol, color = TguMuted, fontSize = 20.sp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = TguText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = TguMuted, fontSize = 9.sp)
        }
        if (onClick != null) Text("›", color = TguMuted, fontSize = 22.sp)
    }
}


@Composable
private fun DiagnosticsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf<ProxyProfile?>(null) }
    var result by remember { mutableStateOf<com.rnmd.tgu.proxy.ProxyTestResult?>(null) }
    var checkedAt by remember { mutableStateOf<Long?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val probeResult = result
    val selectedTypeLabel = profile?.type?.name ?: "НЕ ВЫБРАН"

    LaunchedEffect(Unit) {
        profile = withContext(Dispatchers.IO) {
            runCatching { UpstreamProxySettingsStore(context.applicationContext).selectedProfile() }.getOrNull()
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = TguPanel),
            border = BorderStroke(1.dp, TguStroke),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("МАРШРУТ  ·  НЕ VPN", color = TguMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                Text("Wi-Fi  →  Upstream  →  Telegram DC", color = TguText, fontSize = 12.sp)
                Text("Проверяем выбранный прокси напрямую, а не весь трафик устройства.", color = TguMuted, fontSize = 10.sp, lineHeight = 15.sp)
            }
        }
        Text("ВЫБРАННЫЙ PROXY / $selectedTypeLabel", color = TguMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
        Text(profile?.let { "${it.host}:${it.port}" } ?: "Upstream не выбран", color = TguText, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = TguPanel),
            border = BorderStroke(1.dp, TguStroke),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DiagnosticRouteRow("TCP-соединение", probeResult?.success, if (probeResult == null) "Ожидает проверки" else if (probeResult.success) "Установлено" else "Не пройдено")
                DiagnosticRouteRow(
                    "MTProto obfuscated2 handshake",
                    if (profile?.type?.name == "MTPROTO") probeResult?.success else null,
                    when {
                        profile == null -> "Ожидает выбора upstream"
                        profile?.type?.name != "MTPROTO" -> "Не применяется · выбран SOCKS5"
                        probeResult == null -> "Ожидает проверки"
                        probeResult.success -> "Рукопожатие подтверждено Telegram DC"
                        else -> "Проверка не пройдена"
                    },
                )
                DiagnosticRouteRow("Ответ Telegram DC", probeResult?.success, when {
                    probeResult == null -> "Ожидает проверки"
                    probeResult.success -> "Получен и проверен"
                    else -> "Ответ не подтверждён"
                })
                DiagnosticRouteRow(
                    "SOCKS5 CONNECT → Telegram DC",
                    if (profile?.type?.name == "SOCKS5") probeResult?.success else null,
                    when {
                        profile == null -> "Ожидает выбора upstream"
                        profile?.type?.name != "SOCKS5" -> "Не применяется · выбран MTProto"
                        probeResult == null -> "Ожидает проверки"
                        probeResult.success -> "Туннель до Telegram DC открыт"
                        else -> "CONNECT не пройден"
                    },
                )
                Spacer(Modifier.fillMaxWidth().height(1.dp).background(TguStroke))
                DiagnosticMetricRow("Результат на этом телефоне", probeResult?.let { if (it.success) "${it.elapsedMs} мс" else "Ошибка" } ?: "Не измерен")
                DiagnosticMetricRow(
                    "Последняя проверка",
                    checkedAt?.let { java.text.SimpleDateFormat("dd.MM · HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it)) } ?: "—",
                )
            }
        }
        if (error != null) InfoCard("Проверка не выполнена", error.orEmpty())
        probeResult?.let {
            InfoCard(
                if (it.success) "Путь до Telegram DC проверен" else "Доступность Telegram не подтверждена",
                it.detail,
            )
        }
        Button(
            onClick = {
                val selected = profile ?: return@Button
                if (!loading) {
                    loading = true
                    error = null
                    scope.launch {
                        try {
                            result = withContext(Dispatchers.IO) { com.rnmd.tgu.proxy.ProxyDiagnostics.check(selected) }
                            checkedAt = System.currentTimeMillis()
                        } catch (exception: Exception) {
                            error = "${exception.javaClass.simpleName}: ${exception.message.orEmpty()}"
                        } finally {
                            loading = false
                        }
                    }
                }
            },
            enabled = !loading && profile != null,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = TguText, contentColor = TguBackground),
        ) { Text(if (loading) "ПРОВЕРКА…" else "ПРОВЕРИТЬ НА ЭТОМ ТЕЛЕФОНЕ", fontSize = 10.sp) }
        if (profile == null) Text("Сначала выберите upstream в каталоге или менеджере proxy.", color = TguMuted, fontSize = 10.sp)
    }
}

@Composable
private fun DiagnosticRouteRow(title: String, success: Boolean?, subtitle: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(
            when (success) { true -> "✓"; false -> "⊗"; null -> "○" },
            color = when (success) { true -> TguGreen; false -> TguRed; null -> TguMuted },
            fontSize = 16.sp,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = TguText, fontSize = 11.sp)
            Text(subtitle, color = TguMuted, fontSize = 9.sp)
        }
    }
}

@Composable
private fun DiagnosticMetricRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = TguMuted, fontSize = 10.sp)
        Text(value, color = TguText, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}
@Composable
private fun DiagnosticEndpointCard(host: String, tcp: Boolean, tls: Boolean, detail: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = TguPanel),
        border = BorderStroke(1.dp, TguStroke),
        shape = RoundedCornerShape(9.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(host, color = TguText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (tcp && tls) "TCP + TLS OK" else if (tcp) "TCP ONLY" else "FAILED",
                    color = if (tcp && tls) TguGreen else if (tcp) TguAmber else TguRed,
                    fontSize = 8.sp, fontWeight = FontWeight.Bold,
                )
            }
            Text(detail, color = TguMuted, fontSize = 9.sp, lineHeight = 13.sp)
        }
    }
}

@Composable
private fun MonitoringSettingsScreen() {
    val context = LocalContext.current
    val store = remember(context) { AutoFailoverSettingsStore(context.applicationContext) }
    var settings by remember { mutableStateOf(store.load()) }
    val monitor by ProxyMonitorRuntime.state.collectAsState()
    val engine by LocalProxyRuntime.state.collectAsState()
    var intervalExpanded by remember { mutableStateOf(false) }
    var thresholdExpanded by remember { mutableStateOf(false) }
    val serviceRunning = engine.status == LocalProxyStatus.RUNNING

    fun update(value: AutoFailoverSettings) {
        settings = value
        store.save(value)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = TguPanel),
            border = BorderStroke(1.dp, TguStroke),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(Modifier.padding(horizontal = 15.dp, vertical = 5.dp)) {
                Row(
                    Modifier.fillMaxWidth().height(65.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Автосмена upstream", color = TguText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("При повторных сбоях проверки", color = TguMuted, fontSize = 9.sp)
                    }
                    Switch(checked = settings.enabled, onCheckedChange = { update(settings.copy(enabled = it)) })
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(TguStroke))
                Row(
                    Modifier.fillMaxWidth().height(72.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Интервал активных проверок", color = TguText, fontSize = 11.sp)
                        Text("Проверка пути до Telegram DC", color = TguMuted, fontSize = 9.sp)
                    }
                    Box {
                        Text(
                            settings.checkIntervalSeconds.toString() + " с  ⌄",
                            modifier = Modifier.clickable(enabled = settings.enabled) { intervalExpanded = true }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            color = TguText,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        DropdownMenu(
                            expanded = intervalExpanded,
                            onDismissRequest = { intervalExpanded = false },
                        ) {
                            listOf(30, 45, 60, 90, 120, 180).forEach { seconds ->
                                DropdownMenuItem(
                                    text = { Text("$seconds с") },
                                    onClick = {
                                        update(settings.copy(checkIntervalSeconds = seconds))
                                        intervalExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(TguStroke))
                Row(
                    Modifier.fillMaxWidth().height(72.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Порог сбоев подряд", color = TguText, fontSize = 11.sp)
                        Text("До поиска другого upstream", color = TguMuted, fontSize = 9.sp)
                    }
                    Box {
                        Text(
                            settings.failureThreshold.toString() + "  ⌄",
                            modifier = Modifier.clickable(enabled = settings.enabled) { thresholdExpanded = true }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            color = TguText,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        DropdownMenu(
                            expanded = thresholdExpanded,
                            onDismissRequest = { thresholdExpanded = false },
                        ) {
                            (2..5).forEach { count ->
                                DropdownMenuItem(
                                    text = { Text(count.toString()) },
                                    onClick = {
                                        update(settings.copy(failureThreshold = count))
                                        thresholdExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = TguPanel),
            border = BorderStroke(1.dp, TguStroke),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (serviceRunning) "✓" else "○", color = if (serviceRunning) TguText else TguMuted, fontSize = 16.sp)
                    Text(
                        if (serviceRunning) "Foreground-сервис запущен" else "Foreground-сервис остановлен",
                        color = TguText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Text(
                    if (serviceRunning) {
                        "Мониторинг активен, пока TGU работает с постоянным уведомлением Android."
                    } else {
                        "Включи TGU на главном экране, чтобы запустить локальный мониторинг."
                    },
                    color = TguMuted,
                    fontSize = 10.sp,
                    lineHeight = 15.sp,
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(TguStroke))
                Text("АКТИВНЫЙ UPSTREAM / " + (monitor.activeType ?: "НЕ ВЫБРАН"), color = TguMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                Text(monitor.activeEndpoint ?: "Upstream не выбран", color = TguText, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Смен за текущую сессию сервиса", color = TguMuted, fontSize = 10.sp)
                    Text(monitor.switchCount.toString(), color = TguText, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
                if (monitor.failureStreak > 0) {
                    Text(
                        "Последовательных сбоев: " + monitor.failureStreak,
                        color = TguAmber,
                        fontSize = 9.sp,
                    )
                }
            }
        }

        InfoCard(
            "Сначала проверка, затем переключение",
            "После заданного числа ошибок TGU ищет рабочий upstream и перенастраивает локальный сервис. Без запущенного foreground-сервиса мониторинг не работает.",
        )
        Text(
            "Статус Telegram не подтверждается автоматической сменой upstream.",
            color = TguMuted,
            fontSize = 9.sp,
            lineHeight = 13.sp,
        )
    }
}


@Composable
private fun SectionLabel(text: String) {
    Text(text, color = TguMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.13f), shape = RoundedCornerShape(6.dp)) {
        Text(text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), color = color, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
    }
}

@Composable
private fun MetricChip(label: String, value: String) {
    Surface(color = TguPanelRaised, shape = RoundedCornerShape(6.dp)) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = TguMuted, fontSize = 7.sp, fontWeight = FontWeight.Bold)
            Text(value, color = TguText, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = TguPanel),
        border = BorderStroke(1.dp, TguStroke),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = TguText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = TguMuted, fontSize = 10.sp, lineHeight = 15.sp)
        }
    }
}
