package com.rnmd.tgu

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rnmd.tgu.proxy.ProxyDiagnostics
import com.rnmd.tgu.proxy.ProxyProfile
import com.rnmd.tgu.proxy.ProxyProfileStore
import com.rnmd.tgu.proxy.ProxyTestResult
import com.rnmd.tgu.proxy.ProxyType
import com.rnmd.tgu.proxy.UpstreamProxyConnector
import com.rnmd.tgu.proxy.UpstreamProxySettingsStore
import com.rnmd.tgu.proxy.LocalProxyRuntime
import com.rnmd.tgu.proxy.LocalProxyStatus
import com.rnmd.tgu.proxy.LocalProxyService
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ProxyPanel = Color(0xFF151515)
private val ProxyStroke = Color(0xFF333333)
private val ProxyText = Color(0xFFF5F5F5)
private val ProxySecondary = Color(0xFFA3A3A3)
private val ProxyGood = Color(0xFF9BD7AC)
private val ProxyBad = Color(0xFFE08D8D)
private val ProxyAccent = Color(0xFFE4C28A)

@Composable
fun ProxyManagerSection() {
    val context = LocalContext.current
    val store = remember(context) { ProxyProfileStore(context.applicationContext) }
    val upstreamStore = remember(context) { UpstreamProxySettingsStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf(emptyList<ProxyProfile>()) }
    var selectedUpstreamId by remember { mutableStateOf(upstreamStore.selectedProfileId()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<ProxyProfile?>(null) }
    var testingIds by remember { mutableStateOf(emptySet<String>()) }
    var results by remember { mutableStateOf<Map<String, ProxyTestResult>>(emptyMap()) }

    LaunchedEffect(store) {
        try {
            profiles = withContext(Dispatchers.IO) { store.load() }
        } catch (e: Exception) {
            loadError = e.message ?: "Could not decrypt saved profiles."
        } finally {
            loading = false
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = ProxyPanel),
        border = BorderStroke(1.dp, ProxyStroke)
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Мои proxy", color = ProxyText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Сохраняйте и проверяйте MTProto- и SOCKS5-профили. Выбранный upstream используется локальным движком TGU; Telegram продолжает подключаться к 127.0.0.1:1443.",
                color = ProxySecondary, fontSize = 11.sp, lineHeight = 16.sp
            )
            Text(
                "Секреты и пароли хранятся локально в зашифрованном виде (AES-GCM / Android Keystore) и не попадают в диагностические отчёты.",
                color = ProxySecondary, fontSize = 10.sp, lineHeight = 15.sp
            )
            when {
                loading -> Text("Загрузка зашифрованных профилей…", color = ProxySecondary, fontSize = 11.sp)
                loadError != null -> Text("Ошибка хранилища: $loadError", color = ProxyBad, fontSize = 10.sp)
                profiles.isEmpty() -> Text("Пока нет сохранённых proxy.", color = ProxySecondary, fontSize = 11.sp)
            }
            profiles.forEach { profile ->
                ProxyProfileCard(
                    profile, profile.id in testingIds, results[profile.id], profile.id == selectedUpstreamId,
                    onEdit = {
                        editingProfile = profile
                        showEditor = true
                    },
                    onUseAsUpstream = {
                        val unsupportedMtProto = profile.type == ProxyType.MTPROTO &&
                            !UpstreamProxyConnector.supportsMtProtoProfile(profile)
                        if (unsupportedMtProto) {
                            message = "WEB/FakeTLS MTProto secrets are saved, but cannot be used as an upstream yet."
                        } else {
                            try {
                                if (selectedUpstreamId == profile.id) {
                                    upstreamStore.clear()
                                    selectedUpstreamId = null
                                    message = "External upstream disabled. TGU returned to automatic routing."
                                } else {
                                    upstreamStore.select(profile.id)
                                    selectedUpstreamId = profile.id
                                    message = "Selected as TGU upstream. Telegram stays on 127.0.0.1:1443."
                                }
                                val engineStatus = LocalProxyRuntime.state.value.status
                                if (engineStatus == LocalProxyStatus.RUNNING || engineStatus == LocalProxyStatus.STARTING) {
                                    context.startService(
                                        Intent(context, LocalProxyService::class.java)
                                            .setAction(LocalProxyService.ACTION_RECONFIGURE)
                                    )
                                }
                            } catch (e: Exception) {
                                message = e.message ?: "Could not update the selected upstream."
                            }
                        }
                    },
                    onTest = {
                        if (profile.id !in testingIds) {
                            testingIds = testingIds + profile.id
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { ProxyDiagnostics.check(profile) }
                                results = results + (profile.id to result)
                                testingIds = testingIds - profile.id
                            }
                        }
                    },
                    onDelete = {
                        scope.launch {
                            try {
                                profiles = withContext(Dispatchers.IO) { store.delete(profile.id) }
                                results = results - profile.id
                                if (selectedUpstreamId == profile.id) {
                                    upstreamStore.clear()
                                    selectedUpstreamId = null
                                    val engineStatus = LocalProxyRuntime.state.value.status
                                    if (engineStatus == LocalProxyStatus.RUNNING || engineStatus == LocalProxyStatus.STARTING) {
                                        context.startService(
                                            Intent(context, LocalProxyService::class.java)
                                                .setAction(LocalProxyService.ACTION_RECONFIGURE)
                                        )
                                    }
                                }
                                message = "Профиль удалён."
                            } catch (e: Exception) {
                                message = e.message ?: "Could not delete profile."
                            }
                        }
                    }
                )
            }
            if (message != null) Text(message.orEmpty(), color = ProxyAccent, fontSize = 10.sp)
            Button(
                onClick = { editingProfile = null; showEditor = true },
                enabled = !loading && loadError == null,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ProxyText, contentColor = Color(0xFF090909))
            ) { Text("+ ДОБАВИТЬ PROXY", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
        }
    }

    if (showEditor) {
        ProxyProfileDialog(
            initialProfile = editingProfile,
            onDismiss = { showEditor = false; editingProfile = null },
            onSave = { profile ->
                scope.launch {
                    try {
                        profiles = withContext(Dispatchers.IO) { store.upsert(profile) }
                        val wasSelected = editingProfile?.id?.let { it == selectedUpstreamId } == true
                        if (wasSelected) {
                            upstreamStore.select(profile.id)
                            val engineStatus = LocalProxyRuntime.state.value.status
                            if (engineStatus == LocalProxyStatus.RUNNING || engineStatus == LocalProxyStatus.STARTING) {
                                context.startService(
                                    Intent(context, LocalProxyService::class.java)
                                        .setAction(LocalProxyService.ACTION_RECONFIGURE)
                                )
                            }
                        }
                        showEditor = false
                        editingProfile = null
                        message = if (wasSelected) "Профиль обновлён и переподключён." else "Профиль сохранён в зашифрованном хранилище."
                    } catch (e: Exception) {
                        message = e.message ?: "Could not save profile."
                    }
                }
            }
        )
    }
}

@Composable
private fun ProxyProfileCard(
    profile: ProxyProfile,
    testing: Boolean,
    result: ProxyTestResult?,
    isSelectedUpstream: Boolean,
    onUseAsUpstream: () -> Unit,
    onEdit: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(9.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1B1B)),
        border = BorderStroke(1.dp, ProxyStroke)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(profile.name, color = ProxyText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(profile.type.name + " · " + profile.host + ":" + profile.port, color = ProxySecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
                Row {
                    TextButton(onClick = onEdit) { Text("ИЗМЕНИТЬ", color = ProxySecondary, fontSize = 8.sp) }
                    TextButton(onClick = onDelete) { Text("УДАЛИТЬ", color = ProxyBad, fontSize = 8.sp) }
                }
            }
            OutlinedButton(
                onClick = onTest,
                enabled = !testing,
                modifier = Modifier.fillMaxWidth().height(40.dp),
                shape = RoundedCornerShape(7.dp)
            ) {
                Text(if (testing) "ПРОВЕРКА…" else "ПРОВЕРИТЬ", fontSize = 9.sp)
            }
            OutlinedButton(
                onClick = onUseAsUpstream,
                modifier = Modifier.fillMaxWidth().height(38.dp),
                shape = RoundedCornerShape(7.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (isSelectedUpstream) ProxyGood else ProxySecondary
                )
            ) {
                Text(
                    if (isSelectedUpstream) "ВЫБРАН В TGU · НАЖМИТЕ, ЧТОБЫ ОТКЛЮЧИТЬ" else "ВЫБРАТЬ UPSTREAM ДЛЯ TGU",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            if (result != null) {
                Text(result.title + " · " + result.elapsedMs + " ms", color = if (result.success) ProxyGood else ProxyBad, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Text(result.detail, color = ProxySecondary, fontSize = 10.sp, lineHeight = 15.sp)
            }
        }
    }
}

@Composable
private fun ProxyProfileDialog(
    initialProfile: ProxyProfile?,
    onDismiss: () -> Unit,
    onSave: (ProxyProfile) -> Unit,
) {
    var type by remember(initialProfile) { mutableStateOf(initialProfile?.type ?: ProxyType.MTPROTO) }
    var name by remember(initialProfile) { mutableStateOf(initialProfile?.name.orEmpty()) }
    var host by remember(initialProfile) { mutableStateOf(initialProfile?.host.orEmpty()) }
    var port by remember(initialProfile) { mutableStateOf(initialProfile?.port?.toString() ?: "443") }
    var secret by remember(initialProfile) { mutableStateOf(initialProfile?.secret.orEmpty()) }
    var username by remember(initialProfile) { mutableStateOf(initialProfile?.username.orEmpty()) }
    var password by remember(initialProfile) { mutableStateOf(initialProfile?.password.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialProfile == null) "Добавить proxy" else "Редактировать proxy", color = ProxyText, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            type = ProxyType.MTPROTO
                            username = ""
                            password = ""
                            if (port == "1080") port = "443"
                        },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(7.dp)
                    ) { Text("MTProto", color = if (type == ProxyType.MTPROTO) ProxyGood else ProxySecondary, fontSize = 11.sp) }
                    OutlinedButton(
                        onClick = {
                            type = ProxyType.SOCKS5
                            secret = ""
                            if (port == "443") port = "1080"
                        },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(7.dp)
                    ) { Text("SOCKS5", color = if (type == ProxyType.SOCKS5) ProxyGood else ProxySecondary, fontSize = 11.sp) }
                }
                ProxyInput(name, { name = it }, "Название профиля")
                ProxyInput(host, { host = it }, "Хост или IP-адрес")
                ProxyInput(port, { port = it.filter(Char::isDigit).take(5) }, "Port", KeyboardType.Number)
                if (type == ProxyType.MTPROTO) {
                    ProxyInput(secret, { secret = it.filterNot(Char::isWhitespace) }, "Секрет MTProto (hex)", secret = true)
                } else {
                    ProxyInput(username, { username = it }, "Имя пользователя SOCKS5 (необязательно)")
                    ProxyInput(password, { password = it }, "Пароль SOCKS5 (необязательно)", secret = true)
                }
                Text("Используйте данные только из доверенных источников. TGU шифрует сохранённые параметры на этом устройстве.", color = ProxySecondary, fontSize = 10.sp, lineHeight = 14.sp)
                if (error != null) Text(error.orEmpty(), color = ProxyBad, fontSize = 10.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsedPort = port.toIntOrNull()
                val profile = if (parsedPort == null) null else ProxyProfile(
                    id = initialProfile?.id ?: UUID.randomUUID().toString(),
                    name = name.trim().ifBlank { if (type == ProxyType.MTPROTO) "MTProto proxy" else "SOCKS5 proxy" },
                    type = type, host = host.trim(), port = parsedPort,
                    secret = secret.trim(), username = username, password = password
                )
                error = if (profile == null) "Enter a valid port." else profile.validationError()
                if (profile != null && error == null) onSave(profile)
            }) { Text(if (initialProfile == null) "СОХРАНИТЬ" else "СОХРАНИТЬ ИЗМЕНЕНИЯ", color = ProxyGood, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ОТМЕНА", color = ProxySecondary) } },
        containerColor = ProxyPanel,
        titleContentColor = ProxyText,
        textContentColor = ProxySecondary
    )
}

@Composable
private fun ProxyInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    secret: Boolean = false
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        label = { Text(label, fontSize = 11.sp) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None
    )
}
