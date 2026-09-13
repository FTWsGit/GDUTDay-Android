package com.gdutday.feature.schedule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gdutday.core.common.CourseBlock
import com.gdutday.core.ui.toComposeColor

/**
 * 同一时间格内全部冲突课程的列表。
 *
 * 从堆叠色块的 `+N` 角标进入：用户感知不到被盖住的课时，这是唯一的完整视图。
 * 点条目走普通的课程详情（[CourseBlockItem] 同一份点击语义）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConflictCourseListSheet(
    blocks: List<CourseBlock>,
    onDismiss: () -> Unit,
    onPick: (CourseBlock) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text(
                text = stringResource(R.string.schedule_conflict_title, blocks.size),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(blocks) { block ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = block.color.toComposeColor().copy(alpha = 0.24f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onDismiss()
                                onPick(block)
                            },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(12.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = block.course.name,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = listOf(block.course.classroom, block.course.teacher, block.sectionLabel)
                                        .filter { it.isNotBlank() }
                                        .joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = "${block.startClock}-${block.endClock}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.schedule_cancel)) }
            }
        }
    }
}
