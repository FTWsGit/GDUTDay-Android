package com.gdutday.feature.toolbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gdutday.core.model.GdutException
import com.gdutday.data.gdut.freeroom.FreeRoomBuilding
import com.gdutday.data.repository.AppContainer
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 空闲教室查询主界面。
 *
 * 结构：校区分 Tab → 楼按类型分组（教学/实验/学院/公共/其他）→ 点楼进教室时间轴。
 * 日期为查询参数，默认今天，可在顶栏修改。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun FreeRoomScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    var buildings by remember { mutableStateOf<List<FreeRoomBuilding>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var selectedBuilding by remember { mutableStateOf<FreeRoomBuilding?>(null) }

    LaunchedEffect(Unit) {
        try {
            buildings = container.freeRoomRepository.fetchBuildings()
        } catch (e: GdutException) {
            error = e.userMessage
        } catch (_: Exception) {
            error = ""
        }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.toolbox_free_room_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.toolbox_back))
                    }
                },
            )
        },
    ) { padding ->
        when {
            loading && buildings == null -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                Text(
                    text = error!!.ifEmpty { stringResource(R.string.toolbox_free_room_failed) },
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            buildings != null -> BuildingListView(
                buildings = buildings!!,
                modifier = Modifier.padding(padding),
                onBuildingClick = { selectedBuilding = it },
            )
        }
    }

    selectedBuilding?.let { building ->
        RoomTimelineScreen(
            container = container,
            building = building,
            onBack = { selectedBuilding = null },
        )
    }
}

/** 楼列表：校区 Tab + 类型分组。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BuildingListView(
    buildings: List<FreeRoomBuilding>,
    modifier: Modifier = Modifier,
    onBuildingClick: (FreeRoomBuilding) -> Unit,
) {
    val campuses = remember(buildings) {
        buildings.groupBy { it.campusName }.map { (name, list) ->
            CampusGroup(name = name, code = list.first().campusCode, buildings = list)
        }.sortedBy { it.name }
    }
    var selectedCampus by remember { mutableStateOf(0) }

    Column(modifier = modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = selectedCampus.coerceIn(0, campuses.lastIndex)) {
            campuses.forEachIndexed { i, campus ->
                Tab(
                    selected = selectedCampus == i,
                    onClick = { selectedCampus = i },
                    text = { Text(campus.name.removeSuffix("校区"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
        val campus = campuses.getOrNull(selectedCampus) ?: return
        LazyColumn(Modifier.fillMaxSize()) {
            FreeRoomLogic.groupBuildings(campus.buildings).forEach { (kind, list) ->
                item(key = "kind_${kind.name}") {
                    Text(
                        text = kind.displayName,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(list, key = { it.code }) { building ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clickable { onBuildingClick(building) },
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(building.name, style = MaterialTheme.typography.titleSmall)
                            }
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 校区分组（UI 内部用）。 */
private data class CampusGroup(
    val name: String,
    val code: String,
    val buildings: List<FreeRoomBuilding>,
)

// ============================================================================
// 教室时间轴（某栋楼某天的全部教室）
// ============================================================================

/** 教室时间轴页面状态。 */
private sealed interface TimelineState {
    data object Loading : TimelineState

    data class Loaded(
        val rooms: List<Pair<String, List<FreeRoomLogic.Slot>>>,
        val unassigned: List<com.gdutday.data.gdut.freeroom.RoomOccupancy>,
    ) : TimelineState

    data class Error(val message: String?) : TimelineState
}

/**
 * 某栋楼的教室时间轴：每间教室一行，12 节按时间顺序排开，
 * 每节标状态图标（空闲 / 上课 / 借用），点格看详情。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomTimelineScreen(
    container: AppContainer,
    building: FreeRoomBuilding,
    onBack: () -> Unit,
) {
    var state by remember { mutableStateOf<TimelineState>(TimelineState.Loading) }
    var date by remember { mutableStateOf(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)) }
    var detail by remember { mutableStateOf<FreeRoomLogic.Slot?>(null) }
    var detailRoom by remember { mutableStateOf("") }

    LaunchedEffect(date) {
        state = TimelineState.Loading
        try {
            val result = container.freeRoomRepository.fetchRoomUsage(building.code, date)
            state = TimelineState.Loaded(
                rooms = FreeRoomLogic.roomTimeline(result),
                unassigned = result.unassigned,
            )
        } catch (e: GdutException) {
            state = TimelineState.Error(e.userMessage)
        } catch (_: Exception) {
            state = TimelineState.Error(null)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(building.name, style = MaterialTheme.typography.titleMedium)
                        Text(date, style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.toolbox_back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                    }) {
                        Icon(Icons.Filled.Today, contentDescription = stringResource(R.string.toolbox_free_room_today))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // 图例
            LegendRow(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            when (val s = state) {
                is TimelineState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                is TimelineState.Error -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(
                        text = s.message ?: stringResource(R.string.toolbox_free_room_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                is TimelineState.Loaded -> LazyColumn(Modifier.fillMaxSize()) {
                    if (s.rooms.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.toolbox_free_room_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(24.dp),
                            )
                        }
                    }
                    item(key = "header") { PeriodHeaderRow() }
                    items(s.rooms, key = { it.first }) { (room, slots) ->
                        RoomTimelineRow(
                            room = room,
                            slots = slots,
                            onSlotClick = { slot ->
                                detail = slot
                                detailRoom = room
                            },
                        )
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    }
                    if (s.unassigned.isNotEmpty()) {
                        item(key = "unassigned") {
                            UnassignedBorrowCard(
                                rows = s.unassigned,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    detail?.let { slot ->
        SlotDetailSheet(
            room = detailRoom,
            slot = slot,
            onDismiss = { detail = null },
        )
    }
}

/** 图例：空闲 / 上课 / 借用。 */
@Composable
private fun LegendRow(modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LegendDot(FreeRoomLogic.SlotState.FREE, stringResource(R.string.toolbox_free_room_state_free))
        LegendDot(FreeRoomLogic.SlotState.CLASS, stringResource(R.string.toolbox_free_room_state_class))
        LegendDot(FreeRoomLogic.SlotState.BORROWED, stringResource(R.string.toolbox_free_room_state_borrowed))
    }
}

@Composable
private fun LegendDot(state: FreeRoomLogic.SlotState, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        SlotIcon(state, size = 10.dp)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 状态图标：空闲 = 空心圆，上课 = 实心圆，借用 = 菱形（不同形状便于色弱区分）。 */
@Composable
private fun SlotIcon(state: FreeRoomLogic.SlotState, size: androidx.compose.ui.unit.Dp) {
    val color = when (state) {
        FreeRoomLogic.SlotState.FREE -> MaterialTheme.colorScheme.primary
        FreeRoomLogic.SlotState.CLASS -> MaterialTheme.colorScheme.error
        FreeRoomLogic.SlotState.BORROWED -> MaterialTheme.colorScheme.tertiary
    }
    when (state) {
        FreeRoomLogic.SlotState.BORROWED ->
            Surface(
                color = color,
                shape = androidx.compose.ui.graphics.RectangleShape,
                modifier = Modifier
                    .size(size)
                    .graphicsLayer(rotationZ = 45f),
            ) {}
        else ->
            Icon(
                imageVector = Icons.Filled.Circle,
                contentDescription = null,
                tint = if (state == FreeRoomLogic.SlotState.FREE) color.copy(alpha = 0.25f) else color,
                modifier = Modifier.size(size),
            )
    }
}

/** 一间教室的时间轴行：教室名 + 固定 6 列大图标均分（Excel 式，一列一个时间段）。 */
@Composable
private fun RoomTimelineRow(
    room: String,
    slots: List<FreeRoomLogic.Slot>,
    onSlotClick: (FreeRoomLogic.Slot) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = room,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(0.30f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // 固定 6 列，每列等宽（weight(1f)），图标大、可点区域大
        Row(modifier = Modifier.weight(0.70f)) {
            slots.forEach { slot ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSlotClick(slot) }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    SlotIcon(slot.state, size = 22.dp)
                }
            }
        }
    }
}

/** 列头：与教室行的 6 列对齐的时间段标签。 */
@Composable
private fun PeriodHeaderRow(modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            text = stringResource(R.string.toolbox_free_room_column_room),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.30f),
        )
        Row(modifier = Modifier.weight(0.70f)) {
            FreeRoomLogic.Period.entries.forEach { period ->
                Text(
                    text = period.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 无教室归属的借用（学校接口不标注借用了哪间），单独一张卡说明。 */
@Composable
private fun UnassignedBorrowCard(
    rows: List<com.gdutday.data.gdut.freeroom.RoomOccupancy>,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SlotIcon(FreeRoomLogic.SlotState.BORROWED, size = 10.dp)
                Text(
                    text = stringResource(R.string.toolbox_free_room_unassigned_title),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text(
                text = stringResource(R.string.toolbox_free_room_unassigned_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            rows.sortedBy { it.sectionCode }.forEach { row ->
                Text(
                    text = "第${FreeRoomLogic.sectionRangeLabel(
                        FreeRoomLogic.sectionNumbers(row.sectionCode).firstOrNull() ?: 0,
                        FreeRoomLogic.sectionNumbers(row.sectionCode).lastOrNull() ?: 0,
                    )}节 · ${row.teacher}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** 节次详情：底部弹层，全部中文标签。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SlotDetailSheet(
    room: String,
    slot: FreeRoomLogic.Slot,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SlotIcon(slot.state, size = 14.dp)
                Text(
                    text = "$room · 第${slot.period.label}节",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            val occ = slot.occupancy
            if (occ == null) {
                DetailRow(stringResource(R.string.toolbox_free_room_detail_state), stringResource(R.string.toolbox_free_room_state_free))
                DetailRow(stringResource(R.string.toolbox_free_room_detail_sections), "${slot.period.startSection} 到 ${slot.period.endSection} 节")
                DetailRow(stringResource(R.string.toolbox_free_room_detail_note), stringResource(R.string.toolbox_free_room_detail_free_note))
            } else {
                DetailRow(stringResource(R.string.toolbox_free_room_detail_state), when (slot.state) {
                    FreeRoomLogic.SlotState.BORROWED -> stringResource(R.string.toolbox_free_room_state_borrowed)
                    else -> stringResource(R.string.toolbox_free_room_state_class)
                })
                if (occ.courseName.isNotEmpty()) {
                    DetailRow(stringResource(R.string.toolbox_free_room_detail_course), occ.courseName)
                }
                if (occ.teacher.isNotEmpty()) {
                    DetailRow(stringResource(R.string.toolbox_free_room_detail_teacher), occ.teacher)
                }
                if (occ.teachingClass.isNotEmpty()) {
                    DetailRow(stringResource(R.string.toolbox_free_room_detail_class), occ.teachingClass)
                }
                if (occ.teachingLink.isNotEmpty()) {
                    DetailRow(stringResource(R.string.toolbox_free_room_detail_link), occ.teachingLink)
                }
                val attendees = occ.attendees
                if (attendees != null) {
                    val cap = occ.capacity
                    DetailRow(
                        stringResource(R.string.toolbox_free_room_detail_people),
                        stringResource(R.string.toolbox_free_room_capacity, attendees, cap ?: attendees),
                    )
                }
                if (occ.week != null) {
                    DetailRow(stringResource(R.string.toolbox_free_room_detail_week), "第 ${occ.week} 周")
                    occ.dayOfWeek?.let {
                        DetailRow(stringResource(R.string.toolbox_free_room_detail_day), "星期$it")
                    }
                }
            }
        }
    }
}

/** 详情行：左标签右值。 */
@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}
