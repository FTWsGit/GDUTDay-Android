package com.gdutday.feature.toolbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gdutday.core.model.GdutException
import com.gdutday.data.gdut.freeroom.FreeRoomBuilding
import com.gdutday.data.gdut.freeroom.RoomUsageResult
import com.gdutday.data.repository.AppContainer
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 空闲教室查询对话框。
 *
 * 流程：选楼 → 看该楼当天的占用行（按教室聚合，节次连续段展示）。
 * "空闲教室 = 全部教室 − 占用行"，但接口只给占用行，所以展示占用情况。
 *
 * 会话打通（jwcwx SSO）在 Repository 内部完成，这里只处理三类结果：
 * Loading / 成功数据 / 失败文案（区分会话过期）。
 */
@Composable
internal fun FreeRoomDialog(
    container: AppContainer,
    onDismiss: () -> Unit,
) {
    var buildings by remember { mutableStateOf<List<FreeRoomBuilding>?>(null) }
    var buildingsError by remember { mutableStateOf<String?>(null) }
    var selectedBuilding by remember { mutableStateOf<FreeRoomBuilding?>(null) }
    var date by remember { mutableStateOf(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)) }
    var usage by remember { mutableStateOf<RoomUsageResult?>(null) }
    var usageError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    // 打开时拉教学楼列表
    LaunchedEffect(Unit) {
        try {
            buildings = container.freeRoomRepository.fetchBuildings()
        } catch (e: GdutException) {
            buildingsError = e.userMessage
        } catch (_: Exception) {
            buildingsError = ""
        }
    }

    // 选楼后查询
    LaunchedEffect(selectedBuilding, date) {
        val building = selectedBuilding ?: return@LaunchedEffect
        usage = null
        usageError = null
        loading = true
        try {
            usage = container.freeRoomRepository.fetchRoomUsage(building.code, date)
        } catch (e: GdutException.SessionExpired) {
            usageError = ""
        } catch (e: GdutException) {
            usageError = e.userMessage
        } catch (_: Exception) {
            usageError = ""
        }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.toolbox_close)) }
        },
        title = { Text(stringResource(R.string.toolbox_free_room_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BuildingPicker(
                    buildings = buildings,
                    error = buildingsError,
                    selected = selectedBuilding,
                    onSelect = { selectedBuilding = it },
                )

                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("日期 (yyyy-MM-dd)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                when {
                    loading -> Box(Modifier.fillMaxWidth().padding(16.dp)) {
                        CircularProgressIndicator(Modifier.padding(8.dp))
                    }
                    usageError != null -> Text(
                        text = usageError!!.ifEmpty { stringResource(R.string.toolbox_free_room_session_expired) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    selectedBuilding != null && usage != null -> UsageList(usage!!)
                    else -> Text(
                        text = stringResource(R.string.toolbox_free_room_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BuildingPicker(
    buildings: List<FreeRoomBuilding>?,
    error: String?,
    selected: FreeRoomBuilding?,
    onSelect: (FreeRoomBuilding) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded && buildings != null,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selected?.name ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.toolbox_free_room_pick_building)) },
            placeholder = {
                Text(
                    text = when {
                        error != null -> error.ifEmpty { stringResource(R.string.toolbox_free_room_not_logged_in) }
                        buildings == null -> "加载中…"
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        buildings?.let { list ->
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 320.dp),
            ) {
                // 按校区分组展示
                list.groupBy { it.campusName }.forEach { (campus, items) ->
                    DropdownMenuItem(
                        text = { Text(campus, style = MaterialTheme.typography.labelSmall) },
                        onClick = {},
                        enabled = false,
                    )
                    items.forEach { building ->
                        DropdownMenuItem(
                            text = { Text(building.name) },
                            onClick = {
                                onSelect(building)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageList(result: RoomUsageResult) {
    if (result.byRoom.isEmpty()) {
        Text(
            text = stringResource(R.string.toolbox_free_room_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
        for ((room, rows) in result.byRoom) {
            item(key = room) {
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(room, style = MaterialTheme.typography.titleSmall)
                    rows.sortedBy { it.sectionCode }.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "${sectionsLabel(row.sectionCode)} $row.courseName${row.teacher.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            row.attendees?.let { attendees ->
                                Text(
                                    text = stringResource(
                                        R.string.toolbox_free_room_capacity,
                                        attendees,
                                        row.capacity ?: attendees,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

/** `"0607"` → `"6-7节"`；`"101112"` → `"10-12节"`。解析失败原样返回。 */
private fun sectionsLabel(code: String): String {
    if (code.length % 2 != 0) return "${code}节"
    val sections = code.chunked(2).mapNotNull { it.toIntOrNull() }
    if (sections.isEmpty()) return "${code}节"
    // 按连续段合并：{6,7} → "6-7节"；{10,11,12} → "10-12节"
    val segments = mutableListOf<Pair<Int, Int>>()
    var start = sections.first()
    var prev = start
    for (s in sections.drop(1)) {
        if (s == prev + 1) {
            prev = s
        } else {
            segments += start to prev
            start = s
            prev = s
        }
    }
    segments += start to prev
    return segments.joinToString("、") { (a, b) ->
        if (a == b) "${a}节" else "${a}-${b}节"
    }
}
