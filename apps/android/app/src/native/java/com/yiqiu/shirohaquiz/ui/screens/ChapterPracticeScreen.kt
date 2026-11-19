package com.yiqiu.shirohaquiz.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yiqiu.shirohaquiz.state.DEFAULT_BANK_GROUP_NAME
import com.yiqiu.shirohaquiz.state.QuizBank
import com.yiqiu.shirohaquiz.state.QuizRepository
import com.yiqiu.shirohaquiz.ui.components.ActionPillButton
import com.yiqiu.shirohaquiz.ui.components.GlassCard
import com.yiqiu.shirohaquiz.ui.components.ShirohaHeader
import com.yiqiu.shirohaquiz.ui.components.shirohaNoRippleClickable
import com.yiqiu.shirohaquiz.ui.theme.ShirohaSpacing

/**
 * 独立的「章节练习」页：按分组列出全部题库（章节），点一行就设为当前并直接开刷。
 *
 * 只做选择和开局，答题界面仍复用练习页那套流程：
 * setActiveBank -> startPracticeSession -> onStarted（跳回练习页）。
 * 分组默认全部收起；顶部一行操作条提供 继续 / 上一章 / 下一章。
 */
@Composable
fun ChapterPracticeScreen(
    onBack: () -> Unit,
    onStarted: () -> Unit
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    // 只记录"已展开"的分组，默认空 = 全部收起。
    var expandedGroups by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var notice by remember { mutableStateOf<String?>(null) }

    val allBanks = QuizRepository.banks
    val currentBank = QuizRepository.activeBank()
    val scopeSummary = QuizRepository.currentPracticeScopeSummary()

    // 「上一章 / 下一章」：在当前题库所属分组内按名称排序（章节名带 01/02 前缀，天然是教材顺序）。
    val currentGroupName = currentBank?.groupName?.ifBlank { DEFAULT_BANK_GROUP_NAME }
    val siblings = if (currentGroupName == null) {
        emptyList()
    } else {
        allBanks
            .filter { it.groupName.ifBlank { DEFAULT_BANK_GROUP_NAME } == currentGroupName }
            .sortedBy { it.name }
    }
    val currentIndex = siblings.indexOfFirst { it.id == currentBank?.id }
    val previousBank = if (currentIndex > 0) siblings[currentIndex - 1] else null
    val nextBank = if (currentIndex >= 0 && currentIndex < siblings.size - 1) siblings[currentIndex + 1] else null

    val normalizedQuery = query.trim()
    val filteredBanks = allBanks.filter { bank ->
        normalizedQuery.isBlank() ||
            bank.name.contains(normalizedQuery, ignoreCase = true) ||
            bank.groupName.contains(normalizedQuery, ignoreCase = true)
    }
    val groupedBanks = filteredBanks
        .groupBy { it.groupName.ifBlank { DEFAULT_BANK_GROUP_NAME } }
        .entries
        .sortedBy { entry -> if (entry.key == DEFAULT_BANK_GROUP_NAME) "" else entry.key }

    fun startBank(bank: QuizBank?) {
        if (bank == null) return
        notice = null
        beginChapterPractice(
            context = context,
            bank = bank,
            onStarted = onStarted,
            onBlocked = { message -> notice = message }
        )
    }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ShirohaSpacing.Xl, vertical = ShirohaSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(ShirohaSpacing.Lg)
    ) {
        ShirohaHeader(
            kicker = "Chapters",
            title = "章节练习",
            subtitle = "点一行即设为当前并开练；分组默认收起。"
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ShirohaSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ActionPillButton(
                icon = Icons.Rounded.PlayArrow,
                text = "继续",
                primary = true,
                fillWidthContent = true,
                enabled = currentBank != null,
                modifier = Modifier.weight(1f).height(44.dp),
                onClick = { startBank(currentBank) }
            )
            ActionPillButton(
                icon = Icons.Rounded.ChevronLeft,
                text = "上一章",
                primary = false,
                fillWidthContent = true,
                enabled = previousBank != null,
                modifier = Modifier.weight(1f).height(44.dp),
                onClick = { startBank(previousBank) }
            )
            ActionPillButton(
                icon = Icons.Rounded.ChevronRight,
                text = "下一章",
                primary = false,
                fillWidthContent = true,
                enabled = nextBank != null,
                modifier = Modifier.weight(1f).height(44.dp),
                onClick = { startBank(nextBank) }
            )
        }

        GlassCard(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "当前题库：${currentBank?.name ?: "未选择"}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "练习范围：$scopeSummary · 共 ${allBanks.size} 个题库",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                notice = null
            },
            label = { Text("搜索章节") },
            singleLine = true,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = "搜索章节",
                    modifier = Modifier.size(20.dp)
                )
            },
            modifier = Modifier.fillMaxWidth()
        )

        notice?.let { message ->
            GlassCard(contentPadding = 14.dp) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        when {
            allBanks.isEmpty() -> {
                GlassCard {
                    Text(
                        text = "还没有任何题库。先在「导入」页导入题库，再回来按章节刷题。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            groupedBanks.isEmpty() -> {
                GlassCard {
                    Text(
                        text = "没有匹配「$normalizedQuery」的章节。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            else -> {
                groupedBanks.forEach { entry ->
                    val groupName = entry.key
                    val banksInGroup = entry.value
                    // 搜索时强制展开，否则命中的章节会被折叠藏起来。
                    val isExpanded = normalizedQuery.isNotBlank() || groupName in expandedGroups
                    val totalQuestions = banksInGroup.sumOf { it.questions.size }

                    GlassCard(contentPadding = 16.dp) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .shirohaNoRippleClickable(enabled = normalizedQuery.isBlank()) {
                                    expandedGroups = if (isExpanded) {
                                        expandedGroups - groupName
                                    } else {
                                        (expandedGroups + groupName).distinct()
                                    }
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isExpanded) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight,
                                contentDescription = if (isExpanded) "收起分组" else "展开分组",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(ShirohaSpacing.Sm))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = groupName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${banksInGroup.size} 个章节 · $totalQuestions 题",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (isExpanded) {
                            Spacer(Modifier.height(ShirohaSpacing.Sm))
                            banksInGroup.forEach { bank ->
                                ChapterRow(
                                    bank = bank,
                                    isCurrent = bank.id == currentBank?.id,
                                    onClick = { startBank(bank) }
                                )
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            ActionPillButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                text = "返回练习",
                primary = false,
                modifier = Modifier.height(44.dp),
                onClick = onBack
            )
        }
        Spacer(Modifier.height(ShirohaSpacing.Xl))
    }
}

/**
 * 一行 = 一个章节。只留名字 + 题数，整行点击即设为当前并直接开练。
 */
@Composable
private fun ChapterRow(
    bank: QuizBank,
    isCurrent: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shirohaNoRippleClickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = bank.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${bank.questions.size} 题",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 练习页顶部的「按章节刷题」入口卡。视觉沿用 GlassCard 通用卡片。
 */
@Composable
fun ChapterPracticeEntryCard(
    onClick: () -> Unit
) {
    GlassCard(
        modifier = Modifier.shirohaNoRippleClickable(onClick = onClick),
        contentPadding = 16.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.School,
                contentDescription = "按章节刷题",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(ShirohaSpacing.Md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "按章节刷题",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "一眼看全 ${QuizRepository.banks.size} 个题库，点一下就开练",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = "进入章节练习",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/**
 * 点一行 = 设为当前题库 + 立即用现有练习流程开局。
 * 不新增答题界面，全部复用 QuizRepository 的公开方法。
 */
private fun beginChapterPractice(
    context: Context,
    bank: QuizBank,
    onStarted: () -> Unit,
    onBlocked: (String) -> Unit
) {
    if (QuizRepository.banks.none { it.id == bank.id }) {
        onBlocked("「${bank.name}」暂时不可用，请返回题库管理刷新后再试。")
        return
    }
    // setActiveBank 同时写入当前题库与练习范围（BANK scope），一次到位。
    QuizRepository.setActiveBank(context, bank.id)
    val pool = QuizRepository.activePracticePoolQuestions()
    if (pool.isEmpty()) {
        onBlocked("「${bank.name}」里还没有题目。")
        return
    }
    val counts = QuizRepository.questionTypeCounts(pool)
    val types = QuizRepository.objectiveQuestionTypes()
        .filter { (counts[it] ?: 0) > 0 }
        .toSet()
        .ifEmpty { counts.filterValues { it > 0 }.keys.toSet() }
        .ifEmpty { QuizRepository.objectiveQuestionTypes() }
    val count = pool.count { it.type in types }
    if (count <= 0) {
        onBlocked("「${bank.name}」没有可练习的题型，请先在刷题偏好里检查题型设置。")
        return
    }
    val started = QuizRepository.startPracticeSession(
        questionCount = count,
        allowedTypes = types,
        sourceLabel = bank.name,
        randomize = true,
        practiceMode = QuizRepository.PRACTICE_MODE_INSTANT,
        batchGroupSize = count
    )
    if (started) {
        onStarted()
    } else {
        onBlocked("「${bank.name}」没能开始练习，请先在刷题偏好里检查题型设置。")
    }
}
