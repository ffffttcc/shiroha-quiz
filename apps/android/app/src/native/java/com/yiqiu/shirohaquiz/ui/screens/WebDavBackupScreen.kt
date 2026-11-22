package com.yiqiu.shirohaquiz.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.yiqiu.shirohaquiz.security.BackupEncryption
import com.yiqiu.shirohaquiz.state.QuizRepository
import com.yiqiu.shirohaquiz.sync.sync.SyncScheduler
import com.yiqiu.shirohaquiz.sync.sync.SyncStateStore
import com.yiqiu.shirohaquiz.sync.webdav.*
import com.yiqiu.shirohaquiz.ui.components.GlassCard
import com.yiqiu.shirohaquiz.ui.components.NoticeCard
import com.yiqiu.shirohaquiz.ui.components.ShirohaHeader
import com.yiqiu.shirohaquiz.ui.theme.ShirohaSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 冲突副本的命名标记，与 SyncIdentity 的副本命名保持一致；只用于展示，不参与同步判定。 */
private const val CONFLICT_COPY_MARK = "（冲突副本"

/** 失败原因可能很长（异常消息+堆栈首行），截断以免撑破布局，但保留开头原文不改写。 */
private fun truncateForDisplay(text: String, maxChars: Int = 120): String =
    if (text.length <= maxChars) text else text.take(maxChars) + "…"

@Composable
fun WebDavBackupScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val manager = remember { WebDavBackupManager.getInstance(context) }
    val settings by manager.settings.collectAsState()
    val task by manager.task.collectAsState()
    val entries by manager.backups.collectAsState()
    val prepared by manager.preparedRestore.collectAsState()
    val downloaded by manager.downloadedFile.collectAsState()
    val scope = rememberCoroutineScope()

    var endpoint by rememberSaveable { mutableStateOf(settings.baseUrl.ifBlank { CloudSyncUiModel.DEFAULT_BASE_URL }) }
    var username by rememberSaveable { mutableStateOf(settings.username) }
    var directory by rememberSaveable {
        mutableStateOf(settings.remoteDirectory.ifBlank { CloudSyncUiModel.DEFAULT_DIRECTORY })
    }
    var rememberPassword by rememberSaveable { mutableStateOf(true) }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var deleteEntry by remember { mutableStateOf<WebDavBackupEntry?>(null) }
    var restoreMode by remember { mutableStateOf(WebDavRestoreMode.IMPORT_COPY) }
    var confirmOverwrite by remember { mutableStateOf(false) }
    var saveFileName by remember { mutableStateOf("shiroha_cloud_backup.zip") }
    val syncStore = remember { SyncStateStore(context) }
    var syncEnabled by remember { mutableStateOf(syncStore.isEnabled()) }
    var syncInterval by remember { mutableStateOf(syncStore.intervalMinutes()) }
    var syncWifiOnly by remember { mutableStateOf(syncStore.wifiOnly()) }
    var lastSyncAt by remember { mutableStateOf(syncStore.lastSyncAt()) }
    var lastSyncResult by remember { mutableStateOf(syncStore.lastSyncResult()) }
    var lastError by remember { mutableStateOf(syncStore.lastError()) }
    var lastErrorAt by remember { mutableStateOf(syncStore.lastErrorAt()) }
    val localConflictCopies = QuizRepository.banks.map { it.name }.filter { it.contains(CONFLICT_COPY_MARK) }
    var encryptionEnabled by remember { mutableStateOf(manager.isEncryptionEnabled()) }
    var recoveryCode by remember { mutableStateOf<String?>(null) }

    // —— 账号式界面的壳（新增）——
    var signedIn by rememberSaveable {
        mutableStateOf(
            settings.baseUrl.isNotBlank() && settings.username.isNotBlank() && manager.hasPassword()
        )
    }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    var changePasswordOpen by remember { mutableStateOf(false) }
    var oldPasswordInput by remember { mutableStateOf("") }
    var newPasswordInput by remember { mutableStateOf("") }
    var customMinutesInput by rememberSaveable { mutableStateOf("") }
    var syncAppSettings by remember { mutableStateOf(AppSettingsSyncPrefs.isEnabled(context)) }
    var confirmLogout by remember { mutableStateOf(false) }

    val clipboard = LocalClipboardManager.current
    val busy = task?.stage == WebDavTaskStage.RUNNING || task?.stage == WebDavTaskStage.CANCELLING
    val saveDownload = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val source = downloaded
        if (uri != null && source != null) scope.launch {
            message = try {
                withContext(Dispatchers.IO) {
                    require(source.isFile) { "下载缓存已过期，请重新下载。" }
                    val output = context.contentResolver.openOutputStream(uri) ?: error("无法写入所选位置。")
                    output.use { out -> source.inputStream().use { it.copyTo(out) } }
                }
                "已保存云端备份到所选位置。"
            } catch (error: Exception) { "保存失败：${error.message}" }
        }
    }
    LaunchedEffect(downloaded?.absolutePath) {
        if (downloaded != null) saveDownload.launch(saveFileName)
    }
    LaunchedEffect(prepared?.token) { restoreMode = WebDavRestoreMode.IMPORT_COPY; confirmOverwrite = false }
    LaunchedEffect(signedIn) { if (signedIn) manager.refreshBackups() }

    fun refreshSyncState() {
        lastSyncAt = syncStore.lastSyncAt()
        lastSyncResult = syncStore.lastSyncResult()
        lastError = syncStore.lastError()
        lastErrorAt = syncStore.lastErrorAt()
    }

    fun run(action: () -> Boolean) {
        try { if (!action()) message = "有任务正在运行，稍后再试。" }
        catch (error: Exception) { message = CloudSyncUiModel.friendlyFailure(error.message) }
    }

    fun signIn() {
        if (username.isBlank() || password.isBlank()) {
            message = "填写用户名和密码就能登录。"
            return
        }
        val target = endpoint.ifBlank { CloudSyncUiModel.DEFAULT_BASE_URL }
        val dir = directory.ifBlank { CloudSyncUiModel.DEFAULT_DIRECTORY }
        scope.launch {
            message = "正在登录…"
            if (!manager.saveSettings(WebDavSettings(target, username.trim(), dir, true), password)) {
                message = "有任务正在运行，稍后再试。"
                return@launch
            }
            endpoint = target
            directory = dir
            rememberPassword = true
            val report = manager.syncNow()
            refreshSyncState()
            if (!report.ok) {
                message = CloudSyncUiModel.signInError(report.message)
                return@launch
            }
            password = ""
            signedIn = true
            message = "登录成功，正在读取云端备份。"
            manager.refreshBackups()
        }
    }

    fun changePassword() {
        if (oldPasswordInput.isBlank() || newPasswordInput.isBlank()) {
            message = "当前密码和新密码都要填。"
            return
        }
        if (newPasswordInput.length < 8) {
            message = "新密码至少 8 位。"
            return
        }
        scope.launch {
            message = "正在把新密码告诉服务器…"
            val admin = WebDavAdminClient(endpoint)
            val added = admin.rotate(WebDavRotation.MODE_ADD, username, oldPasswordInput, newPasswordInput)
            if (!added.ok) {
                message = "没改成。现在这套密码还能继续用，稍后再试。"
                return@launch
            }
            if (!manager.saveSettings(WebDavSettings(endpoint, username, directory, rememberPassword), newPasswordInput)) {
                message = "有任务正在运行。新密码已在服务器生效，当前密码仍然可用，稍后重试即可。"
                return@launch
            }
            message = "正在用新密码重新加密云端数据…"
            val report = manager.syncNow()
            refreshSyncState()
            if (!WebDavRotation.canFinalize(added.ok, report.ok)) {
                message = "新密码已生效，但云端数据还没重加密完。旧密码仍然可用，稍后再点一次。"
                return@launch
            }
            message = "正在完成切换…"
            val finalized = admin.rotate(WebDavRotation.MODE_FINALIZE, username, oldPasswordInput, newPasswordInput)
            if (finalized.ok) {
                oldPasswordInput = ""
                newPasswordInput = ""
                changePasswordOpen = false
                message = "密码已更新。"
            } else {
                message = "数据已重加密，但旧密码还没摘掉。现在新旧密码都能用。"
            }
        }
    }

    fun applyInterval(minutes: Int) {
        syncInterval = minutes
        syncStore.setIntervalMinutes(minutes)
        SyncScheduler.schedule(context, syncStore)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ShirohaSpacing.Xl),
        verticalArrangement = Arrangement.spacedBy(ShirohaSpacing.Lg)
    ) {
        ShirohaHeader(
            kicker = "Cloud",
            title = "云端备份",
            subtitle = if (signedIn) "已登录：${username}" else "用一个账号，在任何设备上接着刷。"
        )
        message?.let { NoticeCard(it, warning = it.contains("失败") || it.contains("没")) }

        if (!signedIn) {
            SignInCard(
                username = username,
                onUsernameChange = { username = it },
                password = password,
                onPasswordChange = { password = it },
                enabled = !busy,
                onSignIn = ::signIn
            )
            AdvancedCard(
                open = advancedOpen,
                onToggle = { advancedOpen = !advancedOpen },
                summary = "服务器地址、目录等"
            ) {
                ConnectionFields(
                    endpoint = endpoint, onEndpointChange = { endpoint = it },
                    directory = directory, onDirectoryChange = { directory = it },
                    rememberPassword = rememberPassword, onRememberPasswordChange = { rememberPassword = it },
                    enabled = !busy
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !busy, onClick = { run(manager::testConnection) }) { Text("测试连接") }
                    TextButton(enabled = !busy, onClick = { run(manager::createDirectoryByUserRequest) }) { Text("创建备份目录") }
                }
                if (!lastError.isNullOrBlank()) {
                    Text("技术细节：${truncateForDisplay(lastError)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LegacyRecoveryCodeSection(
                    enabled = !busy,
                    onReveal = {
                        scope.launch {
                            recoveryCode = try { manager.recoveryCode() } catch (error: Exception) {
                                message = "暂时读不出来，稍后再试。"
                                null
                            }
                        }
                    },
                    hasRecoveryCode = manager.hasRecoveryCode()
                )
            }
        } else {
            task?.let { current ->
                GlassCard {
                    Text(webDavActionLabel(current.kind), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    if (busy) LinearProgressIndicator(progress = {
                        if ((current.totalBytes ?: 0) > 0) (current.transferredBytes.toFloat() / current.totalBytes!!).coerceIn(0f, 1f) else 0f
                    }, modifier = Modifier.fillMaxWidth())
                    Text(current.message.ifBlank { "正在处理，请稍候……" }, style = MaterialTheme.typography.bodyMedium)
                    if (busy) TextButton(onClick = { manager.cancelCurrentTask() }) { Text("取消当前任务") }
                    else TextButton(onClick = { manager.dismissFinishedTask() }) { Text("收起任务提示") }
                }
            }

            CloudLatestBackupCard(
                entries = entries,
                busy = busy,
                onRefresh = { run(manager::refreshBackups) },
                onRestore = { entry -> run { manager.prepareRestore(entry) } }
            )

            GlassCard {
                Text("自动同步", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("开着就自动把改动传到云端，换设备、重装都不丢。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(syncEnabled, { checked ->
                        syncEnabled = checked
                        syncStore.setEnabled(checked)
                        SyncScheduler.schedule(context, syncStore)
                        message = if (checked) "已开启自动同步。" else "已关闭自动同步。"
                    }, enabled = !busy)
                    Text("开启自动同步", style = MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(15, 30, 60).forEach { minutes ->
                        TextButton(enabled = !busy && syncEnabled, onClick = { applyInterval(minutes) }) {
                            Text(if (minutes == syncInterval) "· ${CloudSyncUiModel.intervalLabel(minutes)}" else CloudSyncUiModel.intervalLabel(minutes))
                        }
                    }
                    TextButton(enabled = !busy && syncEnabled, onClick = { customMinutesInput = "" }) {
                        Text(if (syncInterval !in listOf(15, 30, 60)) "· 自定义" else "自定义")
                    }
                }
                if (syncInterval !in listOf(15, 30, 60)) {
                    Text("当前：${CloudSyncUiModel.intervalLabel(syncInterval)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = customMinutesInput,
                        onValueChange = { customMinutesInput = it },
                        label = { Text("自定义间隔（分钟）") },
                        enabled = !busy && syncEnabled,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(0.6f)
                    )
                    TextButton(enabled = !busy && syncEnabled && CloudSyncUiModel.parseCustomMinutes(customMinutesInput) != null,
                        onClick = {
                            CloudSyncUiModel.parseCustomMinutes(customMinutesInput)?.let {
                                applyInterval(it)
                                customMinutesInput = ""
                                message = "同步间隔已设为 ${CloudSyncUiModel.intervalLabel(it)}。"
                            }
                        }) { Text("应用") }
                }
                TextButton(enabled = !busy, onClick = {
                    scope.launch {
                        message = "正在同步…"
                        val report = manager.syncNow()
                        refreshSyncState()
                        message = if (report.ok) {
                            "同步好了：上传 ${report.uploaded} / 下载 ${report.downloaded}。"
                        } else {
                            CloudSyncUiModel.friendlyFailure(report.message)
                        }
                        if (report.ok) manager.refreshBackups()
                    }
                }) { Text("立即同步") }
                SyncStatusLines(
                    lastSyncAt = lastSyncAt,
                    lastSyncResult = lastSyncResult,
                    lastError = lastError,
                    lastErrorAt = lastErrorAt,
                    backupCount = entries.size,
                    conflictCopies = localConflictCopies,
                    deviceTag = syncStore.deviceId().take(8)
                )
            }

            GlassCard {
                Text("更改密码", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("改完不用重新登录，云端数据会自动用新密码重新加密。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!changePasswordOpen) {
                    TextButton(enabled = !busy, onClick = { changePasswordOpen = true }) { Text("更改密码") }
                } else {
                    OutlinedTextField(oldPasswordInput, { oldPasswordInput = it }, label = { Text("当前密码") },
                        enabled = !busy, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(newPasswordInput, { newPasswordInput = it }, label = { Text("新密码（至少 8 位）") },
                        enabled = !busy, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = !busy, onClick = ::changePassword) { Text("确认更改") }
                        TextButton(enabled = !busy, onClick = {
                            changePasswordOpen = false; oldPasswordInput = ""; newPasswordInput = ""
                        }) { Text("取消") }
                    }
                }
            }

            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(syncAppSettings, { checked ->
                        syncAppSettings = checked
                        AppSettingsSyncPrefs.setEnabled(context, checked)
                        message = if (checked) "之后会把应用设置一起同步。" else "不再同步应用设置。"
                    }, enabled = !busy)
                    Text("同步应用设置", style = MaterialTheme.typography.bodyLarge)
                }
                Text("把外观、刷题偏好这类设置也同步到其他设备。密钥和账号信息不会上传。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            AdvancedCard(
                open = advancedOpen,
                onToggle = { advancedOpen = !advancedOpen },
                summary = "地址、目录、完整备份列表、备份加密"
            ) {
                ConnectionFields(
                    endpoint = endpoint, onEndpointChange = { endpoint = it },
                    directory = directory, onDirectoryChange = { directory = it },
                    rememberPassword = rememberPassword, onRememberPasswordChange = { rememberPassword = it },
                    enabled = !busy
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !busy, onClick = { run(manager::testConnection) }) { Text("测试连接") }
                    TextButton(enabled = !busy, onClick = { run(manager::createDirectoryByUserRequest) }) { Text("创建备份目录") }
                    TextButton(enabled = !busy, onClick = { run(manager::uploadBackup) }) { Text("上传当前备份") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(encryptionEnabled, { checked ->
                        if (manager.setEncryptionEnabled(checked)) {
                            encryptionEnabled = checked
                            message = if (checked) "之后上传的备份会先加密再传。" else "之后上传的备份将不再加密。"
                        } else {
                            message = "有任务正在运行，稍后再试。"
                        }
                    }, enabled = !busy)
                    Text("备份加密（服务端只存密文）", style = MaterialTheme.typography.bodyMedium)
                }
                Text("完整备份 · ${entries.size} 份", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("这里只统计 Backups/ 下的完整备份包。增量同步对象（shiroha-sync- 前缀）与它们同目录，但不在本列表计数。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                entries.forEach { entry ->
                    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(CloudSyncUiModel.backupTitle(entry), style = MaterialTheme.typography.titleSmall)
                        Text(CloudSyncUiModel.backupSummary(entry),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row {
                            TextButton(enabled = !busy && entry.integrityAvailable,
                                onClick = { saveFileName = entry.fileName; run { manager.downloadBackup(entry) } }) { Text("下载到本地") }
                            TextButton(enabled = !busy, onClick = { deleteEntry = entry }) { Text("删除") }
                        }
                    }
                }
                LegacyRecoveryCodeSection(
                    enabled = !busy,
                    onReveal = {
                        scope.launch {
                            recoveryCode = try { manager.recoveryCode() } catch (error: Exception) {
                                message = "暂时读不出来，稍后再试。"
                                null
                            }
                        }
                    },
                    hasRecoveryCode = manager.hasRecoveryCode()
                )
                key(settings) { WebDavAutoBackupCard(enabled = !busy) }
            }

            TextButton(enabled = !busy, onClick = { confirmLogout = true }) { Text("退出登录") }
        }

        TextButton(onClick = onBack) { Text("返回数据管理") }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("退出登录？") },
            text = { Text("退出后本机不再保存这个账号的密码，云端数据不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    if (manager.clearSettings()) {
                        signedIn = false
                        password = ""
                        username = ""
                        endpoint = CloudSyncUiModel.DEFAULT_BASE_URL
                        directory = CloudSyncUiModel.DEFAULT_DIRECTORY
                        message = "已退出登录。"
                    } else {
                        message = "有任务正在运行，稍后再退出。"
                    }
                }) { Text("退出") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } }
        )
    }

    recoveryCode?.let { code ->
        AlertDialog(
            onDismissRequest = { recoveryCode = null },
            title = { Text("旧版备份密码") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("只有打开很早以前的云端备份时才用得到。请不要只存在本机。")
                    Text(BackupEncryption.formatRecoveryCode(code),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("它只保存在本机的安全存储里，不会上传、也不会写进备份。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(BackupEncryption.formatRecoveryCode(code)))
                    message = "已复制到剪贴板。"
                    recoveryCode = null
                }) { Text("复制") }
            },
            dismissButton = { TextButton(onClick = { recoveryCode = null }) { Text("关闭") } }
        )
    }
    prepared?.let { value ->
        if (!confirmOverwrite) AlertDialog(onDismissRequest = { manager.discardPreparedRestore() }, title = { Text("怎么恢复这份备份？") },
            text = { Column {
                val preview = value.preview
                Text("${preview.bankCount} 个题库、${preview.questionCount} 道题、${preview.wrongCount} 条错题、${preview.favoriteCount} 条收藏、${preview.recordCount} 条记录。")
                listOf(WebDavRestoreMode.IMPORT_COPY to "合并进来：保留现在的内容", WebDavRestoreMode.REPLACE_CONTENT_KEEP_SETTINGS to "整体替换：用备份覆盖题库").forEach { (mode, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(restoreMode == mode, onClick = { restoreMode = mode }, enabled = !busy)
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } }, confirmButton = { TextButton(enabled = !busy, onClick = {
                if (restoreMode == WebDavRestoreMode.REPLACE_CONTENT_KEEP_SETTINGS) confirmOverwrite = true
                else run { manager.restorePrepared(value.token, restoreMode, userConfirmed = true) }
            }) { Text("继续") } }, dismissButton = { TextButton(enabled = !busy, onClick = { manager.discardPreparedRestore() }) { Text("取消") } })
        else AlertDialog(onDismissRequest = { confirmOverwrite = false }, title = { Text("用备份覆盖当前题库？") },
            text = { Text("当前题库、错题、收藏、斩题和学习记录会被备份内容替换。系统会先保存本地安全备份，失败时回滚；外观与服务配置保持不变。") },
            confirmButton = { TextButton(enabled = !busy, onClick = { run { manager.restorePrepared(value.token, WebDavRestoreMode.REPLACE_CONTENT_KEEP_SETTINGS, true) } }) { Text("确认覆盖恢复") } },
            dismissButton = { TextButton(onClick = { confirmOverwrite = false }) { Text("返回选择") } })
    }
    deleteEntry?.let { entry ->
        AlertDialog(onDismissRequest = { deleteEntry = null }, title = { Text("删除这份云端备份？") },
            text = { Text("只删云端这一份，本地题库不会变。") },
            confirmButton = { TextButton(onClick = { deleteEntry = null; run { manager.deleteBackup(entry, true) } }) { Text("确认删除") } },
            dismissButton = { TextButton(onClick = { deleteEntry = null }) { Text("取消") } })
    }
}

@Composable
private fun SignInCard(
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    enabled: Boolean,
    onSignIn: () -> Unit
) {
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("登录云端账号", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(username, onUsernameChange, label = { Text("用户名") }, enabled = enabled,
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, onPasswordChange, label = { Text("密码") }, enabled = enabled,
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth())
            Button(onClick = onSignIn, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("登录") }
        }
    }
}

@Composable
private fun ConnectionFields(
    endpoint: String,
    onEndpointChange: (String) -> Unit,
    directory: String,
    onDirectoryChange: (String) -> Unit,
    rememberPassword: Boolean,
    onRememberPasswordChange: (Boolean) -> Unit,
    enabled: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(endpoint, onEndpointChange, label = { Text("HTTPS 服务地址") }, enabled = enabled,
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(directory, onDirectoryChange, label = { Text("远程目录") }, enabled = enabled,
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(rememberPassword, onRememberPasswordChange, enabled = enabled)
            Text("在本机记住密码", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun LegacyRecoveryCodeSection(enabled: Boolean, onReveal: () -> Unit, hasRecoveryCode: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(enabled = enabled, onClick = onReveal) {
            Text(if (hasRecoveryCode) "查看旧版备份密码" else "生成旧版备份密码")
        }
        Text("仅用于打开很早以前加密的云端备份；现在换了设备只填账号密码就能解密。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CloudLatestBackupCard(
    entries: List<WebDavBackupEntry>,
    busy: Boolean,
    onRefresh: () -> Unit,
    onRestore: (WebDavBackupEntry) -> Unit
) {
    val latest = entries.maxByOrNull { it.createdAt ?: 0L }
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("云端最新备份", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                TextButton(enabled = !busy, onClick = onRefresh) { Text("刷新") }
            }
            if (latest == null) {
                Text("云端还没有完整备份。点「高级 → 上传当前备份」可以传一份。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(CloudSyncUiModel.backupTitle(latest), style = MaterialTheme.typography.titleMedium)
                Text(CloudSyncUiModel.backupSummary(latest),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!latest.integrityAvailable) {
                    Text("这份备份缺少校验信息，可能无法安全恢复。", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                Button(enabled = !busy && latest.integrityAvailable, onClick = { onRestore(latest) },
                    modifier = Modifier.fillMaxWidth()) { Text("恢复这份备份") }
            }
        }
    }
}

@Composable
private fun SyncStatusLines(
    lastSyncAt: Long,
    lastSyncResult: String?,
    lastError: String?,
    lastErrorAt: Long,
    backupCount: Int,
    conflictCopies: List<String>,
    deviceTag: String
) {
    val stamp = if (lastSyncAt > 0) SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(lastSyncAt)) else "还没同步过"
    Text("上次同步：$stamp", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("云端备份：$backupCount 份", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("本机标识：$deviceTag", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    val errorText = lastError
    if (!errorText.isNullOrBlank()) {
        val errorStamp = if (lastErrorAt > 0) SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(lastErrorAt)) else "时间未知"
        val current = lastErrorAt > lastSyncAt
        Text(
            if (current) "上次失败：${CloudSyncUiModel.friendlyFailure(errorText)} · $errorStamp"
            else "之前失败过（后来已同步成功）· $errorStamp",
            style = MaterialTheme.typography.bodySmall,
            color = if (current) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else if (!lastSyncResult.isNullOrBlank()) {
        Text("上次结果：$lastSyncResult", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (conflictCopies.isNotEmpty()) {
        Text("有两台设备改过同一份数据，已保留副本：${conflictCopies.joinToString("、")}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AdvancedCard(open: Boolean, onToggle: () -> Unit, summary: String, content: @Composable () -> Unit) {
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("高级", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onToggle) { Text(if (open) "收起" else "展开") }
            }
            if (!open) {
                Text(summary, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                content()
            }
        }
    }
}

internal fun webDavActionLabel(kind: WebDavTaskKind): String = when (kind) {
    WebDavTaskKind.TEST_CONNECTION -> "正在连接服务器"
    WebDavTaskKind.CREATE_DIRECTORY -> "正在创建云端目录"
    WebDavTaskKind.LIST -> "正在读取云端备份"
    WebDavTaskKind.UPLOAD -> "正在上传完整备份"
    WebDavTaskKind.PREVIEW -> "正在下载并校验备份"
    WebDavTaskKind.DOWNLOAD -> "正在下载到本地"
    WebDavTaskKind.RESTORE -> "正在恢复题库内容"
    WebDavTaskKind.DELETE -> "正在删除云端备份"
    WebDavTaskKind.CLEAN_CACHE -> "正在清理临时缓存"
}
