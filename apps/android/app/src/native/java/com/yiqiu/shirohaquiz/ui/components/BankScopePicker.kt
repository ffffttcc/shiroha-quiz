package com.yiqiu.shirohaquiz.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yiqiu.shirohaquiz.state.DEFAULT_BANK_GROUP_NAME
import com.yiqiu.shirohaquiz.state.QuizBank
import com.yiqiu.shirohaquiz.ui.theme.ShirohaColors
import com.yiqiu.shirohaquiz.ui.theme.ShirohaRadius
import com.yiqiu.shirohaquiz.ui.util.bookSections

/**
 * 「分组」这一级的折叠头：[ShirohaBookSectionHeader] 的上一级。
 *
 * 错题 / 收藏 / 记录 / 搜索四个范围选择弹窗共用，和列表页保持一致：
 * **默认收起**，点一下展开该分组下的书目与题库。
 */
@Composable
fun ShirohaGroupSectionHeader(
    groupName: String,
    bankCount: Int,
    collapsed: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 8.dp, bottom = 2.dp)
            .shirohaNoRippleClickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (collapsed) Icons.Rounded.ChevronRight else Icons.Rounded.ExpandMore,
            contentDescription = if (collapsed) "展开分组" else "收起分组",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = "$groupName · $bankCount 个题库",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = ShirohaColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 范围选择弹窗里的一行可选项（「全部题库」「当前题库」以及单个题库都走这一个）。
 * 已选行高亮 + 右侧对勾，与原各页面私有实现视觉一致。
 */
@Composable
fun ShirohaScopeOptionRow(
    title: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shirohaNoRippleClickable(onClick = onClick),
        shape = RoundedCornerShape(ShirohaRadius.Md),
        color = if (selected) ShirohaColors.BrandPrimarySoft else Color.Transparent,
        border = BorderStroke(
            1.dp,
            if (selected) ShirohaColors.LineSelected else ShirohaColors.LineSoft
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (desc.isNotBlank()) {
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Done,
                    contentDescription = "已选择",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * 「分组 → 书目 → 题库」三级折叠列表的通用骨架，不含行样式。
 *
 * 行内容由调用方通过 [row] 给出：范围选择弹窗传 [ShirohaScopeOptionRow]（单选），
 * 「我的」页导出选库传带复选框的行（多选）。折叠逻辑与排序只在这里实现一次。
 */
@Composable
fun ColumnScope.ShirohaGroupedBankList(
    banks: List<QuizBank>,
    row: @Composable (QuizBank) -> Unit
) {
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }
    val expandedBooks = remember { mutableStateMapOf<String, Boolean>() }

    val groupedBanks = banks
        .groupBy { it.groupName.ifBlank { DEFAULT_BANK_GROUP_NAME } }
        .entries
        .sortedBy { entry -> if (entry.key == DEFAULT_BANK_GROUP_NAME) "" else entry.key }

    groupedBanks.forEach groupLoop@ { entry ->
        val groupName = entry.key
        val banksInGroup = entry.value
        val groupExpanded = expandedGroups[groupName] == true

        ShirohaGroupSectionHeader(
            groupName = groupName,
            bankCount = banksInGroup.size,
            collapsed = !groupExpanded,
            onToggle = { expandedGroups[groupName] = !groupExpanded }
        )

        if (!groupExpanded) return@groupLoop

        bookSections(banksInGroup).forEach bookLoop@ { section ->
            val bookKey = "$groupName|${section.bookName}"
            if (section.bookName.isNotBlank()) {
                val bookExpanded = expandedBooks[bookKey] == true
                ShirohaBookSectionHeader(
                    bookName = section.bookName,
                    bankCount = section.banks.size,
                    collapsed = !bookExpanded,
                    onToggle = { expandedBooks[bookKey] = !bookExpanded }
                )
                if (!bookExpanded) return@bookLoop
            }
            section.banks.forEach { bank -> row(bank) }
        }
    }
}

/**
 * 「分组 → 书目 → 题库」三级范围选择列表，错题 / 收藏 / 记录 / 搜索四个弹窗共用。
 *
 * 声明为 [ColumnScope] 扩展：直接成为父 Column 的子项，沿用父级的间距，不额外包一层布局。
 * 合并分组与书目的展开状态都保存在本组件内，**默认为空 = 全部收起**；
 * 弹窗关闭时本组件离开组合，状态随之丢弃，重开自动回到收起态。
 *
 * 排序口径与题库管理页一致：[bookSections] + 默认分组排最前。
 *
 * @param bankScopeKey 由题库算出「选中键」，各页面键前缀不同（错题/收藏/记录/搜索各有前缀）。
 * @param bankDesc 题库行的副标题（题数、错题数等），各页面格式不同，由调用方给出。
 */
@Composable
fun ColumnScope.ShirohaBankScopeItems(
    banks: List<QuizBank>,
    selectedScopeKey: String,
    bankScopeKey: (QuizBank) -> String,
    bankDesc: (QuizBank) -> String,
    onSelect: (String) -> Unit
) {
    ShirohaGroupedBankList(banks = banks) { bank ->
        val key = bankScopeKey(bank)
        ShirohaScopeOptionRow(
            title = bank.name,
            desc = bankDesc(bank),
            selected = selectedScopeKey == key,
            onClick = { onSelect(key) }
        )
    }
}
