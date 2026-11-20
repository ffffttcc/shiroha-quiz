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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalContext
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
    var endpoint by rememberSaveable { mutableStateOf(settings.baseUrl) }
    var username by rememberSaveable { mutableStateOf(settings.username) }
    var directory by rememberSaveable { mutableStateOf(settings.remoteDirectory) }
    var rememberPassword by rememberSaveable { mutableStateOf(settings.rememberPassword) }
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
    // 冲突副本被引擎落成普通本地题库（名字带「（冲突副本 …）」），读题库列表就能列名字，不需要引擎暴露。
    val localConflictCopies = QuizRepository.banks.map { it.name }.filter { it.contains(CONFLICT_COPY_MARK) }
    var encryptionEnabled by remember { mutableStateOf(manager.isEncryptionEnabled()) }
    var recoveryCode by remember { mutableStateOf<String?>(null) }
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
    fun run(action: () -> Boolean) {
        try { if (!action()) message = "当前任务尚未结束，请稍后再试。" }
        catch (error: Exception) { message = error.message ?: "操作失败，请检查配置。" }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ShirohaSpacing.Xl),
        verticalArrangement = Arrangement.spacedBy(ShirohaSpacing.Lg)) {
        ShirohaHeader(kicker = "Cloud", title = "云端备份", subtitle = "通过自己的 WebDAV 保存与恢复完整备份。")
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("连接配置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(endpoint, { endpoint = it }, label = { Text("HTTPS 服务地址") }, enabled = !busy,
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(username, { username = it }, label = { Text("用户名") }, enabled = !busy,
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text(if (manager.hasPassword()) "应用密码（留空保留已保存密码）" else "应用密码") },
                    enabled = !busy, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(directory, { directory = it }, label = { Text("远程目录") }, enabled = !busy,
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(rememberPassword, { rememberPassword = it }, enabled = !busy)
                    Text("加密保存应用密码到本机", style = MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !busy, onClick = { run {
                        val saved = manager.saveSettings(WebDavSettings(endpoint, username, directory, rememberPassword), password.takeIf { it.isNotEmpty() })
                        if (saved) {
                            password = ""
                            if (!rememberPassword || !manager.hasPassword()) {
                                val automatic = WebDavAutoBackup.load(context)
                                if (automatic.intervalDays > 0) WebDavAutoBackup.save(context, automatic.copy(intervalDays = 0))
                            }
                            message = "配置已保存。"
                        }
                        saved
                    } }) { Text("保存配置") }
                    TextButton(enabled = !busy, onClick = { run(manager::testConnection) }) { Text("测试连接") }
                    TextButton(enabled = !busy, onClick = { run(manager::createDirectoryByUserRequest) }) { Text("创建备份目录") }
                }
                TextButton(enabled = !busy, onClick = { run {
                    val cleared = manager.clearSettings()
                    if (cleared) {
                        endpoint = ""; username = ""; directory = WebDavSettings.DEFAULT_REMOTE_DIRECTORY
                        password = ""; rememberPassword = false
                        WebDavAutoBackup.save(context, WebDavAutoBackupSettings())
                        message = "配置已清除，自动备份已关闭。"
                    }
                    cleared
                } }) { Text("清除云端配置") }
                Text("建议使用服务商应用专用密码。请先保存配置；未勾选保存密码时，仅本次应用运行可用。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        message?.let { NoticeCard(it, warning = it.contains("失败")) }
        task?.let { current ->
            GlassCard {
                Text(webDavActionLabel(current.kind), style = MaterialTheme.typography.titleLarge)
                if (busy) LinearProgressIndicator(progress = {
                    if ((current.totalBytes ?: 0) > 0) (current.transferredBytes.toFloat() / current.totalBytes!!).coerceIn(0f, 1f) else 0f
                }, modifier = Modifier.fillMaxWidth())
                Text(current.message.ifBlank { "正在处理，请稍候……" }, style = MaterialTheme.typography.bodyMedium)
                if (busy) TextButton(onClick = { manager.cancelCurrentTask() }) { Text("取消当前任务") }
                else TextButton(onClick = { manager.dismissFinishedTask() }) { Text("收起任务提示") }
            }
        }
        GlassCard {
            Text("完整备份", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("包含已保存的题库、图片、错题、收藏和学习记录。OCR 草稿与服务密钥不上传。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !busy, onClick = { run(manager::uploadBackup) }) { Text("上传当前备份") }
                TextButton(enabled = !busy, onClick = { run(manager::refreshBackups) }) { Text("刷新云端列表") }
            }
        }
        GlassCard {
            Text("备份加密", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("开启后，备份包在本机加密再上传，服务端只存密文；旧的不加密备份仍可正常下载和恢复。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(encryptionEnabled, { checked ->
                    if (manager.setEncryptionEnabled(checked)) {
                        encryptionEnabled = checked
                        message = if (checked) "之后上传的备份会先加密再传。" else "之后上传的备份将不再加密。"
                    } else {
                        message = "当前任务尚未结束，请稍后再试。"
                    }
                }, enabled = !busy)
                Text("备份加密（服务端只存密文）", style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(enabled = !busy, onClick = {
                scope.launch {
                    recoveryCode = try {
                        manager.recoveryCode()
                    } catch (error: Exception) {
                        message = "读取恢复码失败：${error.message}"
                        null
                    }
                }
            }) { Text(if (manager.hasRecoveryCode()) "查看恢复码" else "生成恢复码") }
            Text("恢复码丢失后，已加密上传的云端备份将永久无法解密，也无法找回。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        GlassCard {
            Text("自动同步", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("后台按设定间隔增量同步题库：只传内容变过的库，失败不会改动本地数据。",
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(syncWifiOnly, { checked ->
                    syncWifiOnly = checked
                    syncStore.setWifiOnly(checked)
                    SyncScheduler.schedule(context, syncStore)
                }, enabled = !busy && syncEnabled)
                Text("仅在 Wi-Fi 下同步", style = MaterialTheme.typography.bodyMedium)
            }
            Text("同步频率", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(15 to "15 分钟", 30 to "30 分钟", 60 to "60 分钟", 0 to "仅手动").forEach { (minutes, label) ->
                    TextButton(enabled = !busy && syncEnabled, onClick = {
                        syncInterval = minutes
                        syncStore.setIntervalMinutes(minutes)
                        SyncScheduler.schedule(context, syncStore)
                    }) { Text(if (minutes == syncInterval) "· $label" else label) }
                }
            }
            val syncStamp = if (lastSyncAt > 0) {
                SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(lastSyncAt))
            } else {
                "从未同步"
            }
            Text("上次同步 $syncStamp · ${lastSyncResult ?: "无记录"}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // 失败原因原样露出（截断但不改写）。失败晚于上次成功才算「当前失败」，否则标为历史失败，避免红字压着成功记录误导。
            val errorText = lastError
            if (!errorText.isNullOrBlank()) {
                val errorStamp = if (lastErrorAt > 0) {
                    SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(lastErrorAt))
                } else {
                    "时间未知"
                }
                val errorIsCurrent = lastErrorAt > lastSyncAt
                Text(
                    if (errorIsCurrent) "上次失败：${truncateForDisplay(errorText)} · $errorStamp"
                    else "历史失败（此后已同步成功）：${truncateForDisplay(errorText)} · $errorStamp",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (errorIsCurrent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (localConflictCopies.isNotEmpty()) {
                Text("冲突副本（已保留为本地题库）：${localConflictCopies.joinToString("、")}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(enabled = !busy, onClick = {
                scope.launch {
                    message = "正在同步…"
                    val report = manager.syncNow()
                    lastSyncAt = syncStore.lastSyncAt()
                    lastSyncResult = syncStore.lastSyncResult()
                    lastError = syncStore.lastError()
                    lastErrorAt = syncStore.lastErrorAt()
                    message = if (report.ok) {
                        val copies = QuizRepository.banks.map { it.name }.filter { it.contains(CONFLICT_COPY_MARK) }
                        val conflictNote = when {
                            report.conflicts <= 0 -> ""
                            copies.isEmpty() -> "冲突副本已保留，但本地题库里没找到副本名（需要引擎侧暴露）。"
                            else -> "冲突副本：${copies.joinToString("、")}"
                        }
                        "同步完成：上传 ${report.uploaded} / 下载 ${report.downloaded} / 冲突 ${report.conflicts}。$conflictNote"
                    } else {
                        "同步失败：${report.message}"
                    }
                }
            }) { Text("立即同步") }
        }
        GlassCard {
            Text("完整备份 · ${entries.size} 份", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("这里只统计完整备份（Backups/ 目录）。增量同步的题库与记录存在 shiroha-sync/，不计入本列表——显示 0 份不代表同步没生效。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (entries.isEmpty()) Text("点击刷新读取远程备份。首次使用时，请先测试连接并创建备份目录。")
            entries.forEach { entry ->
                Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.createdAt?.let { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(it)) } ?: "时间未知",
                        style = MaterialTheme.typography.titleMedium)
                    val sourceLabel = when (entry.source) { "android" -> "Android 原生"; "web" -> "Web"; else -> "来源未知" }
                    Text("${"%.1f".format(entry.sizeBytes / 1048576.0)} MB · $sourceLabel · ${entry.appVersion}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(entry.fileName, style = MaterialTheme.typography.bodySmall)
                    if (!entry.integrityAvailable) Text("旧备份缺少完整校验信息，无法安全恢复。", color = MaterialTheme.colorScheme.error)
                    Row {
                        TextButton(enabled = !busy && entry.integrityAvailable, onClick = { run { manager.prepareRestore(entry) } }) { Text("恢复") }
                        TextButton(enabled = !busy && entry.integrityAvailable, onClick = { saveFileName = entry.fileName; run { manager.downloadBackup(entry) } }) { Text("下载到本地") }
                        TextButton(enabled = !busy, onClick = { deleteEntry = entry }) { Text("删除") }
                    }
                }
            }
        }
        key(settings) { WebDavAutoBackupCard(enabled = !busy) }
        TextButton(onClick = onBack) { Text("返回数据管理") }
    }
    recoveryCode?.let { code ->
        AlertDialog(
            onDismissRequest = { recoveryCode = null },
            title = { Text("备份恢复码") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("请立刻抄写或复制保存，不要只存在本机。")
                    Text(BackupEncryption.formatRecoveryCode(code),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("恢复码只保存在本机的安全存储里，不会上传、也不会写进备份。一旦丢失，" +
                        "服务端上的加密备份将永久无法解密，我们也无法帮你找回。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(BackupEncryption.formatRecoveryCode(code)))
                    message = "恢复码已复制到剪贴板。"
                    recoveryCode = null
                }) { Text("复制恢复码") }
            },
            dismissButton = { TextButton(onClick = { recoveryCode = null }) { Text("关闭") } }
        )
    }
    prepared?.let { value ->
        if (!confirmOverwrite) AlertDialog(onDismissRequest = { manager.discardPreparedRestore() }, title = { Text("确认恢复方式") },
            text = { Column {
                val preview = value.preview
                Text("${preview.bankCount} 个题库、${preview.questionCount} 道题、${preview.wrongCount} 条错题、${preview.favoriteCount} 条收藏、${preview.recordCount} 条记录。")
                listOf(WebDavRestoreMode.IMPORT_COPY to "导入为副本：保留当前数据", WebDavRestoreMode.REPLACE_CONTENT_KEEP_SETTINGS to "覆盖恢复：替换题库内容，保留本机设置").forEach { (mode, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(restoreMode == mode, onClick = { restoreMode = mode }, enabled = !busy)
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } }, confirmButton = { TextButton(enabled = !busy, onClick = {
                if (restoreMode == WebDavRestoreMode.REPLACE_CONTENT_KEEP_SETTINGS) confirmOverwrite = true
                else run { manager.restorePrepared(value.token, restoreMode, userConfirmed = true) }
            }) { Text("继续") } }, dismissButton = { TextButton(enabled = !busy, onClick = { manager.discardPreparedRestore() }) { Text("取消") } })
        else AlertDialog(onDismissRequest = { confirmOverwrite = false }, title = { Text("覆盖当前题库内容？") },
            text = { Text("当前题库、错题、收藏、斩题和学习记录会被备份内容替换。系统会先保存本地安全备份，失败时回滚；外观与服务配置保持不变。") },
            confirmButton = { TextButton(enabled = !busy, onClick = { run { manager.restorePrepared(value.token, WebDavRestoreMode.REPLACE_CONTENT_KEEP_SETTINGS, true) } }) { Text("确认覆盖恢复") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { confirmOverwrite = false }) { Text("返回选择") } })
    }
    deleteEntry?.let { entry ->
        AlertDialog(onDismissRequest = { deleteEntry = null }, title = { Text("删除云端备份？") }, text = { Text("将删除 ${entry.fileName}，本地题库不会改变。") },
            confirmButton = { TextButton(onClick = { deleteEntry = null; run { manager.deleteBackup(entry, true) } }) { Text("确认删除") } },
            dismissButton = { TextButton(onClick = { deleteEntry = null }) { Text("取消") } })
    }
}

internal fun webDavActionLabel(kind: WebDavTaskKind): String = when (kind) {
    WebDavTaskKind.TEST_CONNECTION -> "测试连接"
    WebDavTaskKind.CREATE_DIRECTORY -> "创建远程目录"
    WebDavTaskKind.LIST -> "读取云端备份"
    WebDavTaskKind.UPLOAD -> "上传完整备份"
    WebDavTaskKind.PREVIEW -> "下载与校验备份"
    WebDavTaskKind.DOWNLOAD -> "下载到本地"
    WebDavTaskKind.RESTORE -> "恢复题库内容"
    WebDavTaskKind.DELETE -> "删除云端备份"
    WebDavTaskKind.CLEAN_CACHE -> "清理临时缓存"
}
