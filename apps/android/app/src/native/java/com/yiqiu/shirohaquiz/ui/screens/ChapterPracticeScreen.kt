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
import com.yiqiu.shirohaquiz.ui.util.bookSections
import com.yiqiu.shirohaquiz.ui.util.chapterDisplayOrder
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yiqiu.shirohaquiz.state.DEFAULT_BANK_GROUP_NAME
import com.yiqiu.shirohaquiz.state.QuizBank
import com.yiqiu.shirohaquiz.state.QuizRepository
import com.yiqiu.shirohaquiz.ui.components.ActionPillButton
import com.yiqiu.shirohaquiz.ui.components.GlassCard
import com.yiqiu.shirohaquiz.ui.components.ShirohaBookSectionHeader
import com.yiqiu.shirohaquiz.ui.components.ShirohaHeader
import com.yiqiu.shirohaquiz.ui.components.shirohaNoRippleClickable
import com.yiqiu.shirohaquiz.ui.theme.ShirohaSpacing

/**
 * 独立的「章节练习」页：按分组列出全部题库（章节），点一行即选中该章，不直接开练。
 * 顶部操作栏：继续 / 上一章 / 下一章 / 开始练习。
 * 分组默认全部收起；顶部操作栏提供 继续 / 上一章 / 下一章 / 开始练习。
 */
@Composable
fun ChapterPracticeScreen(
    onBack: () -> Unit,
    onStarted: () -> Unit
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    // 只记录"已展开"的分组/书目，默认空 = 全部收起。
    var expandedGroups by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var expandedBooks by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var notice by remember { mutableStateOf<String?>(null) }

    val allBanks = QuizRepository.banks
    val scopeSummary = QuizRepository.currentPracticeScopeSummary()

    // 搜索与分组（只做一次，避免重复遍历）
    val normalizedQuery = query.trim()
    val filteredBanks = remember(normalizedQuery) {
        allBanks.filter { bank ->
            normalizedQuery.isBlank() ||
                bank.name.contains(normalizedQuery, ignoreCase = true) ||
                bank.groupName.contains(normalizedQuery, ignoreCase = true)
        }
    }
    val groupedBanks = remember(normalizedQuery) {
        filteredBanks
            .groupBy { it.groupName.ifBlank { DEFAULT_BANK_GROUP_NAME } }
            .entries
            .sortedBy { entry -> if (entry.key == DEFAULT_BANK_GROUP_NAME) "" else entry.key }
    }

    // 「上一章 / 下一章」：在当前题库所属分组内按名称排序（章节名带 01/02 前缀，天然是教材顺序）。
    val currentGroupName = QuizRepository.activeBank()?.groupName?.ifBlank { DEFAULT_BANK_GROUP_NAME }
    val siblings = if (currentGroupName == null) {
        emptyList()
    } else {
        QuizRepository.banks
            .filter { it.groupName.ifBlank { DEFAULT_BANK_GROUP_NAME } == currentGroupName }
            .sortedWith(chapterDisplayOrder)
    }
    val currentIndex = siblings.indexOfFirst { it.id == QuizRepository.activeBank()?.id }
    val previousBank = if (currentIndex > 0) siblings[currentIndex - 1] else null
    val nextBank = if (currentIndex >= 0 && currentIndex < siblings.size - 1) siblings[currentIndex + 1] else null

    // 当前选中的章（用于高亮与导航），初始为当前题库，上一章/下一章只改它，不直接开练
    var selectedBank by remember { mutableStateOf(QuizRepository.activeBank()) }

    fun navigateToBank(bank: QuizBank?) {
        bank?.let { selectedBank = it }
    }

    fun startBank(bank: QuizBank?) {
        if (bank == null) return
        notice = null
        beginChapterPractice(
            context = LocalContext.current,
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
            subtitle = "点章节选中，上一章/下一章切换，点开始练习开练。"
        )

        // 顶部操作栏：继续当前 / 上一章 / 下一章 / 开始练习
        val activeSiblings = if (currentGroupName == null) {
            emptyList()
        } else {
            QuizRepository.banks
                .filter { it.groupName.ifBlank { DEFAULT_BANK_GROUP_NAME } == currentGroupName }
                .sortedWith(chapterDisplayOrder)
        }
        val activeIndex = activeSiblings.indexOfFirst { it.id == QuizRepository.activeBank()?.id }
        val previousActive = if (activeIndex > 0) activeSiblings[activeIndex - 1] else null
        val nextActive = if (activeIndex >= 0 && activeIndex < activeSiblings.size - 1) activeSiblings[activeIndex + 1] else null

        // 选中的章在同组内的前后（用于上一章/下一章导航）
        val selectedGroupName = selectedBank?.groupName?.ifBlank { DEFAULT_BANK_GROUP_NAME }
        val selectedSiblings = if (selectedGroupName == null) {
            emptyList()
        } else {
            QuizRepository.banks
                .filter { it.groupName.ifBlank { DEFAULT_BANK_GROUP_NAME } == selectedGroupName }
                .sortedWith(chapterDisplayOrder)
        }
        val selectedIndex = selectedSiblings.indexOfFirst { it.id == selectedBank?.id }
        val previousSelected = if (selectedIndex > 0) selectedSiblings[selectedIndex - 1] else null
        val nextSelected = if (selectedIndex >= 0 && selectedIndex < selectedSiblings.size - 1) selectedSiblings[selectedIndex + 1] else null

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ShirohaSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 继续当前正在练的章
            ActionPillButton(
                icon = Icons.Rounded.PlayArrow,
                text = "继续",
                primary = true,
                fillWidthContent = true,
                enabled = QuizRepository.activeBank() != null,
                modifier = Modifier.weight(1f).height(44.dp),
                onClick = { startBank(QuizRepository.activeBank()) }
            )
            // 选中的章的上一章
            ActionPillButton(
                icon = Icons.Rounded.ChevronLeft,
                text = "上一章",
                primary = false,
                fillWidthContent = true,
                enabled = previousSelected != null,
                modifier = Modifier.weight(1f).height(44.dp),
                onClick = { navigateToBank(previousSelected) }
            )
            // 选中的章的下一章
            ActionPillButton(
                icon = Icons.Rounded.ChevronRight,
                text = "下一章",
                primary = false,
                fillWidthContent = true,
                enabled = nextSelected != null,
                modifier = Modifier.weight(1f).height(44.dp),
                onClick = { navigateToBank(nextSelected) }
            )
            // 开始练习：对当前选中的章开练
            ActionPillButton(
                icon = Icons.Rounded.PlayArrow,
                text = "开始练习",
                primary = true,
                fillWidthContent = true,
                enabled = selectedBank != null,
                modifier = Modifier.weight(1f).height(44.dp),
                onClick = { startBank(selectedBank) }
            )
        }

        GlassCard(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val displayName = selectedBank?.name ?: QuizRepository.activeBank()?.name ?: "未选择"
                Text(
                    text = "当前选中：$displayName",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "练习范围：$scopeSummary · 共 ${QuizRepository.banks.size} 个题库",
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
            QuizRepository.banks.isEmpty() -> {
                GlassCard {
                    Text(
                        text = "还没有任何题库。先在「导入」页导入题库，再回来按章节刷题。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            QuizRepository.banks.filter { it.name.contains(query.trim(), ignoreCase = true) || it.groupName.contains(query.trim(), ignoreCase = true) }.isEmpty() -> {
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
                    val banksInGroup = entry.value.sortedWith(chapterDisplayOrder)
                    val isExpanded = query.trim().isNotBlank() || groupName in expandedGroups
                    val totalQuestions = banksInGroup.sumOf { QuizRepository.bankQuestionCount(it.id) }

                    GlassCard(contentPadding = 16.dp) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .shirohaNoRippleClickable(enabled = query.trim().isBlank()) {
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
                            bookSections(banksInGroup).forEach { section ->
                                val bookKey = "$groupName|${section.bookName}"
                                val bookExpanded = query.trim().isNotBlank() || bookKey in expandedBooks
                                if (section.bookName.isNotBlank()) {
                                    ShirohaBookSectionHeader(
                                        bookName = section.bookName,
                                        bankCount = section.banks.size,
                                        collapsed = !bookExpanded,
                                        onToggle = {
                                            expandedBooks = if (bookKey in expandedBooks) {
                                                expandedBooks - bookKey
                                            } else {
                                                (expandedBooks + bookKey).distinct()
                                            }
                                        }
                                    )
                                }
                                if (section.bookName.isBlank() || bookExpanded) {
                                    section.banks.forEach { bank ->
                                        ChapterRow(
                                            bank = bank,
                                            isCurrent = bank.id == selectedBank?.id,
                                            isActive = bank.id == QuizRepository.activeBank()?.id,
                                            onClick = { navigateToBank(bank) }
                                        )
                                    }
                                }
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
 * 一行 = 一个章节。显示名字 + 题数，当前选中高亮，正在练的章有标记。
 * 点击 = 选中该章，不直接开练。
 */
@Composable
private fun ChapterRow(
    bank: QuizBank,
    isCurrent: Boolean,
    isActive: Boolean,
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
            Row(horizontalArrangement = Arrangement.spacedBy(ShirohaSpacing.Xs)) {
                Text(
                    text = "${QuizRepository.bankQuestionCount(bank.id)} 题",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isActive) {
                    Text(
                        text = "练习中",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
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
                    text = "一眼看全 ${QuizRepository.banks.size} 个题库，点一下选中章节",
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
 * 点一行 = 选中该章，不直接开练。开始练习由顶部操作栏的按钮触发。
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
    // 这一章上次没做完：直接接着上次的题和题号，别重新洗牌把做过的题冲掉。
    if (QuizRepository.hasSavedPracticeSession() &&
        QuizRepository.resumeSavedPracticeSession()
    ) {
        onStarted()
        return
    }
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
    val count = pool.size
    if (count <= 0) {
        onBlocked("「${bank.name}」没有可练习的题型，请先在刷题偏好里检查题型设置。")
        return
    }
    val started = QuizRepository.startPracticeSession(
        questionCount = pool.size,
        allowedTypes = QuizRepository.objectiveQuestionTypes(),
        sourceLabel = bank.name,
        randomize = true,
        practiceMode = QuizRepository.PRACTICE_MODE_INSTANT,
        batchGroupSize = pool.size
    )
    if (started) {
        onStarted()
    } else {
        onBlocked("「${bank.name}」没能开始练习，请先在刷题偏好里检查题型设置。")
    }
}
