package com.rnmd.tgu

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rnmd.tgu.proxy.FreedomCheckerCatalogRepository
import com.rnmd.tgu.proxy.FreedomCheckerProxy
import com.rnmd.tgu.proxy.LocalProxyRuntime
import com.rnmd.tgu.proxy.LocalProxyService
import com.rnmd.tgu.proxy.LocalProxyStatus
import com.rnmd.tgu.proxy.ProxyDiagnostics
import com.rnmd.tgu.proxy.ProxyProfileStore
import com.rnmd.tgu.proxy.ProxyTestResult
import com.rnmd.tgu.proxy.UpstreamProxySettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CatalogPanel = Color(0xFF151515)
private val CatalogStroke = Color(0xFF333333)
private val CatalogText = Color(0xFFF5F5F5)
private val CatalogMuted = Color(0xFFA3A3A3)
private val CatalogGood = Color(0xFFF5F5F5)
private val CatalogWarn = Color(0xFFD4D4D4)

@Composable
fun FreedomCheckerSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(context) { FreedomCheckerCatalogRepository(context.applicationContext) }
    val initialSnapshot = remember(repository) { repository.cached() }
    var entries by remember { mutableStateOf(initialSnapshot?.entries.orEmpty()) }
    var lastFetch by remember { mutableStateOf(initialSnapshot?.fetchedAtEpochMs) }
    var loading by remember { mutableStateOf(false) }
    var usingCache by remember { mutableStateOf(false) }
    var selectedUpstreamId by remember { mutableStateOf(UpstreamProxySettingsStore(context.applicationContext).selectedProfileId()) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("ALL") }
    var query by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("ALL") }
    var checks by remember { mutableStateOf<Map<String, ProxyTestResult>>(emptyMap()) }
    var testingIds by remember { mutableStateOf(emptySet<String>()) }

    fun refreshCatalog() {
        if (loading) return
        loading = true
        error = null
        scope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) { repository.refresh() }
                entries = snapshot.entries
                lastFetch = snapshot.fetchedAtEpochMs
                usingCache = false
                message = "Каталог обновлён · ${snapshot.entries.size} записей"
            } catch (e: Exception) {
                error = e.message ?: "Не удалось обновить каталог."
                val cached = withContext(Dispatchers.IO) { repository.cached() }
                if (cached != null) {
                    entries = cached.entries
                    lastFetch = cached.fetchedAtEpochMs
                    usingCache = true
                    message = null
                }
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(repository) {
                                                                                                
        refreshCatalog()
    }

    val filtered = entries.filter { item ->
        val protocolMatches = when (filter) {
            "MT" -> item.typeLabel.contains("MTProto", true)
            "SOCKS" -> item.typeLabel.contains("SOCKS", true)
            "WEB" -> item.typeLabel.contains("WEB", true)
            else -> true
        }
        val localCheck = checks[item.id]
        val statusMatches = when (statusFilter) {
            "WORKING" -> item.serverVerified || localCheck?.success == true
            "FAILED" -> localCheck != null && !localCheck.success
            "UNSUPPORTED" -> !item.supported
            else -> true
        }
        val queryMatches = query.isBlank() ||
            item.endpoint.contains(query.trim(), true) ||
            item.typeLabel.contains(query.trim(), true)
        protocolMatches && statusMatches && queryMatches
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Поиск по адресу или порту", fontSize = 12.sp) },
            leadingIcon = { Text("⌕", color = CatalogMuted, fontSize = 18.sp) },
            shape = RoundedCornerShape(10.dp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = CatalogMuted,
                unfocusedBorderColor = CatalogStroke,
                focusedTextColor = CatalogText,
                unfocusedTextColor = CatalogText,
                focusedLabelColor = CatalogMuted,
                unfocusedLabelColor = CatalogMuted,
                cursorColor = CatalogText,
            ),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("ALL" to "Все протоколы", "MT" to "MTProto", "SOCKS" to "SOCKS5").forEach { (key, label) ->
                val selected = filter == key
                OutlinedButton(
                    onClick = { filter = key },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(7.dp),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        contentColor = if (selected) CatalogGood else CatalogMuted
                    )
                ) { Text(label, fontSize = 8.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf(
                "WORKING" to "Рабочие",
                "FAILED" to "Недоступные",
                "UNSUPPORTED" to "Не поддерж.",
            ).forEach { (key, label) ->
                val selected = statusFilter == key
                OutlinedButton(
                    onClick = { statusFilter = if (statusFilter == key) "ALL" else key },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, if (selected) CatalogMuted else CatalogStroke),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        contentColor = if (selected) CatalogText else CatalogMuted
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp),
                ) { Text(label, fontSize = 7.sp, maxLines = 1) }
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    lastFetch?.let {
                        "Каталог: " + java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(it))
                    } ?: "Каталог: дата обновления неизвестна",
                    color = CatalogMuted,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text("GitHub Raw · резерв Freedom Checker", color = CatalogMuted, fontSize = 8.sp)
            }
            OutlinedButton(
                onClick = { refreshCatalog() },
                enabled = !loading,
                modifier = Modifier.height(40.dp),
                shape = RoundedCornerShape(9.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            ) {
                Text(if (loading) "…" else "↻", fontSize = 20.sp)
            }
        }
        Text(
            "Метаданные каталога не гарантируют доступность в твоей сети. Перед выбором прокси TGU проверяет на этом телефоне.",
            color = CatalogMuted,
            fontSize = 9.sp,
            lineHeight = 13.sp,
        )
        if (usingCache) {
            InfoPanel(
                title = "Используется сохранённый каталог",
                body = "Не удалось получить свежий JSON. Показаны записи из локального кэша; доступность каждого proxy всё равно нужно проверить на телефоне.",
            )
        }
        if (error != null) Text(error.orEmpty(), color = Color(0xFFFF8D8D), fontSize = 10.sp, lineHeight = 15.sp)
        if (message != null) Text(message.orEmpty(), color = CatalogGood, fontSize = 10.sp)

        if (filtered.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 34.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("⌕", color = CatalogMuted, fontSize = 34.sp)
                Text(
                    if (entries.isEmpty()) "Каталог пока пуст" else "Ничего не найдено",
                    color = CatalogText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    when {
                        entries.isEmpty() && error != null -> "Каталог недоступен, а сохранённых записей нет. Проверьте подключение и повторите обновление."
                        entries.isEmpty() -> "Пока нет записей с полными параметрами подключения. TGU не подставляет выдуманные или неполные адреса."
                        else -> "Измени запрос или сбрось фильтры, чтобы увидеть другие записи."
                    },
                    color = CatalogMuted, fontSize = 10.sp, lineHeight = 15.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                if (query.isNotBlank() || filter != "ALL" || statusFilter != "ALL") {
                    OutlinedButton(
                        onClick = { query = ""; filter = "ALL"; statusFilter = "ALL" },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, CatalogStroke),
                    ) { Text("СБРОСИТЬ ПОИСК И ФИЛЬТРЫ", fontSize = 9.sp, color = CatalogText) }
                }
            }
        } else {
            filtered.take(100).forEach { item ->
                CatalogProxyCard(
                    item = item,
                    result = checks[item.id],
                    testing = item.id in testingIds,
                    isSelected = selectedUpstreamId == item.profile?.id,
                    onTest = {
                        val profile = item.profile ?: return@CatalogProxyCard
                        if (item.id !in testingIds) {
                            testingIds = testingIds + item.id
                            scope.launch {
                                val probe = withContext(Dispatchers.IO) { ProxyDiagnostics.check(profile) }
                                checks = checks + (item.id to probe)
                                testingIds = testingIds - item.id
                            }
                        }
                    },
                    onUse = {
                        val profile = item.profile ?: return@CatalogProxyCard
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    ProxyProfileStore(context.applicationContext).upsert(profile)
                                    UpstreamProxySettingsStore(context.applicationContext).select(profile.id)
                                }
                                selectedUpstreamId = profile.id
                                val status = LocalProxyRuntime.state.value.status
                                if (status == LocalProxyStatus.RUNNING || status == LocalProxyStatus.STARTING) {
                                    context.startService(
                                        Intent(context, LocalProxyService::class.java)
                                            .setAction(LocalProxyService.ACTION_RECONFIGURE)
                                    )
                                }
                                message = "Выбран ${profile.type} · ${profile.host}:${profile.port}"
                            } catch (e: Exception) {
                                error = e.message ?: "Не удалось выбрать прокси."
                            }
                        }
                    }
                )
            }
        }

        Text(
            "Основной источник: TGU Proxy Directory · резервный: Freedom Checker",
            color = Color(0xFF697381), fontSize = 9.sp, fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun CatalogProxyCard(
    item: FreedomCheckerProxy,
    result: ProxyTestResult?,
    testing: Boolean,
    isSelected: Boolean,
    onTest: () -> Unit,
    onUse: () -> Unit,
) {
    val status = when {
        !item.supported -> "Не поддерживается"
        testing -> "Проверка на телефоне…"
        result?.success == true -> "Доступен на этом телефоне · ${result.elapsedMs} мс"
        result != null -> "Недоступен из этой сети"
        item.serverVerified && item.serverRttMs != null -> "Проверен каталогом · ${item.serverRttMs} мс"
        item.serverVerified -> "Проверен каталогом · нужна проверка телефона"
        else -> "Не проверен"
    }
    val statusColor = when {
        !item.supported -> CatalogWarn
        testing -> CatalogMuted
        result?.success == true -> CatalogGood
        result != null -> Color(0xFFE08D8D)
        item.serverVerified -> CatalogText
        else -> CatalogMuted
    }
    val deviceLine = when {
        result?.success == true -> "На телефоне · проверен · ${result.elapsedMs} мс"
        result != null -> "На телефоне · проверка не пройдена"
        else -> "На телефоне · не проверен"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(11.dp),
        colors = CardDefaults.cardColors(containerColor = CatalogPanel),
        border = BorderStroke(1.dp, CatalogStroke),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = item.supported && !testing, onClick = onTest)
                .padding(horizontal = 12.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    item.endpoint, color = CatalogText, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(item.typeLabel, color = CatalogMuted, fontSize = 9.sp)
                    Text(status, color = statusColor, fontSize = 9.sp)
                }
                Text(deviceLine, color = CatalogMuted, fontSize = 8.sp)
                if (item.communityUniqueReporters >= 3 && item.communitySuccessRate != null) {
                    Text(
                        "Сообщество · ${item.communitySuccessRate}% успешно · ${item.communityUniqueReporters} устройств" +
                            (item.communityMedianRttMs?.let { " · медиана ${it} мс" } ?: ""),
                        color = CatalogMuted, fontSize = 8.sp, lineHeight = 12.sp,
                    )
                } else if (item.communityReports > 0) {
                    Text(
                        "Сообщество · мало независимых проверок (${item.communityUniqueReporters})",
                        color = CatalogMuted, fontSize = 8.sp,
                    )
                }
                if (item.lastChecked.isNotBlank()) {
                    Text("Каталог · ${item.lastChecked}", color = CatalogMuted, fontSize = 7.sp)
                }
                if (!item.supported) {
                    Text(
                        item.unsupportedReason ?: "Этот тип прокси не поддерживается движком TGU.",
                        color = CatalogWarn, fontSize = 8.sp, lineHeight = 12.sp,
                    )
                }
                if (result != null && !result.success) {
                    Text(result.detail, color = CatalogMuted, fontSize = 8.sp, lineHeight = 12.sp)
                }
                if (testing) {
                    Text("Проверяем соединение с Telegram DC…", color = CatalogMuted, fontSize = 8.sp)
                }
            }
            Text(
                when {
                    !item.supported -> "▣"
                    isSelected -> "◉"
                    else -> "○"
                },
                modifier = Modifier.clickable(
                    enabled = item.supported && result?.success == true,
                    onClick = onUse,
                ).padding(8.dp),
                color = when {
                    !item.supported -> CatalogWarn
                    isSelected -> CatalogGood
                    else -> CatalogMuted
                },
                fontSize = 20.sp,
            )
        }
    }
}


@Composable
private fun InfoPanel(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = CatalogPanel),
        border = BorderStroke(1.dp, CatalogStroke)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = CatalogText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = CatalogMuted, fontSize = 10.sp, lineHeight = 15.sp)
        }
    }
}
