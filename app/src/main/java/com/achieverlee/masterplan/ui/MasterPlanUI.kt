package com.achieverlee.masterplan.ui

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.CalendarContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.achieverlee.masterplan.AppModel
import com.achieverlee.masterplan.PlanItem
import com.achieverlee.masterplan.Snapshot
import com.achieverlee.masterplan.appDueLabel

object UI {
    val bg = Color(0xFFF2F2F7)
    val card = Color.White
    val ink = Color(0xFF1C1C1E)
    val secondary = Color(0xFF8E8E93)
    val field = Color(0xFFE9E9EE)
    val red = Color(0xFFFF6B5E)
    val green = Color(0xFF34C759)
    val orange = Color(0xFFFF9F0A)
}

fun Int.c() = Color(this)

enum class Tab(val label: String) { Todo("할 일"), Calendar("캘린더"), Lock("잠금화면"), Settings("설정") }

@Composable
fun MasterPlanUI(model: AppModel) {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.Todo) }
    var editing by remember { mutableStateOf<EditTarget?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.onPermissionResult() }

    LaunchedEffect(Unit) {
        if (!model.hasPermission) launcher.launch(AppModel.permission)
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = UI.ink, background = UI.bg, surface = UI.card)) {
        Scaffold(
            containerColor = UI.bg,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                NavigationBar(containerColor = Color.White, modifier = Modifier.navigationBarsPadding().height(72.dp)) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t, onClick = { tab = t },
                            icon = {
                                Icon(when (t) {
                                    Tab.Todo -> Icons.Filled.GridView
                                    Tab.Calendar -> Icons.Filled.CalendarMonth
                                    Tab.Lock -> Icons.Filled.Lock
                                    Tab.Settings -> Icons.Filled.Settings
                                }, null)
                            },
                            label = { Text(t.label, fontSize = 12.sp) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = UI.field, selectedIconColor = UI.ink),
                        )
                    }
                }
            },
            floatingActionButton = {
                if (tab == Tab.Todo || tab == Tab.Calendar) {
                    FloatingActionButton(onClick = { editing = EditTarget(null, 0) }, containerColor = UI.ink, contentColor = Color.White,
                        shape = CircleShape) { Icon(Icons.Filled.Add, "할 일 추가") }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize().statusBarsPadding()) {
                when (tab) {
                    Tab.Todo -> TodoScreen(model, onEdit = { editing = it }, onOpenSettings = { tab = Tab.Settings },
                        onRequestPermission = { launcher.launch(AppModel.permission) }, onOpenEvent = { openEvent(ctx, it) })
                    Tab.Calendar -> CalendarScreen(model, onEdit = { editing = it }, onOpenEvent = { openEvent(ctx, it) },
                        onRequestPermission = { launcher.launch(AppModel.permission) })
                    Tab.Lock -> LockScreenTab(model)
                    Tab.Settings -> SettingsScreen(model, onRequestPermission = { launcher.launch(AppModel.permission) },
                        onBattery = { requestBattery(ctx) })
                }
            }
        }
        editing?.let { target ->
            TodoEditor(model, target, onDismiss = { editing = null })
        }
    }
}

/** 편집할 할 일(id가 없으면 새로 만들기, box는 기본 칸). */
data class EditTarget(val todoId: String?, val box: Int)

fun openEvent(ctx: android.content.Context, item: PlanItem) {
    val id = item.eventId ?: return
    val i = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id))
    runCatching { ctx.startActivity(i) }
}

fun requestBattery(ctx: android.content.Context) {
    val pm = ctx.getSystemService(PowerManager::class.java)
    val i = if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    else Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
    runCatching { ctx.startActivity(i) }
}

// MARK: 공통 조각

@Composable
fun Card(modifier: Modifier = Modifier, color: Color = UI.card, radius: Int = 26, padding: Int = 16, content: @Composable () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(radius.dp)).background(color).padding(padding.dp)) { content() }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(text, color = if (selected) Color.White else UI.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(CircleShape).background(if (selected) UI.ink else UI.card).clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp))
}

@Composable
fun PermissionCard(onRequest: () -> Unit) {
    Card(color = UI.red.copy(alpha = 0.10f), modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("캘린더에 접근할 수 없어요", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = UI.ink)
            Text("일정이 앱과 잠금화면에 나오지 않아요. 캘린더 권한을 허용해 주세요.", fontSize = 13.sp, color = UI.secondary)
            Text("권한 허용", color = Color.White, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp).clip(CircleShape).background(UI.ink).clickable { onRequest() }
                    .padding(horizontal = 18.dp, vertical = 10.dp))
        }
    }
}

@Composable
fun TodoRow(item: PlanItem, snap: Snapshot, showBox: Boolean, boxName: String, onToggle: () -> Unit, onTap: () -> Unit) {
    val color = item.color.c()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(color.copy(alpha = 0.11f))
            .clickable { onTap() }.padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (item.done) Icons.Filled.CheckCircle else Icons.Outlined.Circle, "완료", tint = color,
            modifier = Modifier.size(26.dp).clip(CircleShape).clickable { onToggle() })
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, fontSize = 15.sp, color = if (item.done) UI.secondary else UI.ink, maxLines = 2,
                overflow = TextOverflow.Ellipsis, textDecoration = if (item.done) TextDecoration.LineThrough else null)
            val due = item.appDueLabel(snap.today)
            if (showBox || due != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (item.fromCalendar) Icon(Icons.Outlined.Event, null, tint = color, modifier = Modifier.size(12.dp))
                    val parts = listOfNotNull(if (showBox) boxName else null, due)
                    Text(parts.joinToString(" · "), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        color = if (snap.isOverdue(item)) UI.red else color)
                }
            }
        }
    }
}

@Composable
fun ScreenTitle(title: String, subtitle: String? = null, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = UI.ink)
            if (subtitle != null) Text(subtitle, fontSize = 17.sp, color = UI.secondary)
        }
        trailing()
    }
}

// MARK: 할 일 탭

@Composable
fun TodoScreen(model: AppModel, onEdit: (EditTarget) -> Unit, onOpenSettings: () -> Unit,
               onRequestPermission: () -> Unit, onOpenEvent: (PlanItem) -> Unit) {
    val snap = model.snap
    var grid by rememberSaveable { mutableStateOf(true) }
    var showDone by rememberSaveable { mutableStateOf(false) }
    val tap: (PlanItem) -> Unit = { if (it.fromCalendar) onOpenEvent(it) else onEdit(EditTarget(it.todoId, it.box)) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScreenTitle("안녕하세요!", "오늘 뭐 할까요?")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.weight(1f))
            Row(Modifier.clip(CircleShape).background(if (showDone) UI.ink else UI.card).clickable { showDone = !showDone }
                .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (showDone) Icons.Filled.Close else Icons.Filled.CheckCircle, null,
                    tint = if (showDone) Color.White else UI.green, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("완료", color = if (showDone) Color.White else UI.ink, fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.clip(CircleShape).background(UI.card).padding(4.dp)) {
                listOf(true to Icons.Filled.GridView, false to Icons.AutoMirrored.Filled.List).forEach { (g, icon) ->
                    Box(Modifier.clip(CircleShape).background(if (grid == g) UI.ink else Color.Transparent).clickable { grid = g }
                        .padding(horizontal = 14.dp, vertical = 7.dp)) {
                        Icon(icon, null, tint = if (grid == g) Color.White else UI.secondary, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        if (!model.hasPermission) PermissionCard(onRequestPermission)
        else if (model.data.boxes.none { it.calendarId != null } && model.calendars.isNotEmpty()) {
            Card(color = UI.orange.copy(alpha = 0.10f), modifier = Modifier.fillMaxWidth().clickable { onOpenSettings() }) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("칸에 캘린더를 연결해 보세요", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = UI.ink)
                    Text("설정에서 칸마다 캘린더를 고르면, 그 캘린더의 오늘 일정이 할 일로 들어오고 잠금화면에도 나와요. 눌러서 설정으로 가기",
                        fontSize = 13.sp, color = UI.secondary)
                }
            }
        }

        if (showDone) {
            val done = (snap.todos + snap.calendarTasks).filter { it.done }.sortedByDescending { it.doneAt ?: 0L }
            if (done.isEmpty()) Card(Modifier.fillMaxWidth()) { Text("아직 완료한 일이 없어요", color = UI.secondary) }
            else Card(Modifier.fillMaxWidth(), padding = 10) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    done.forEach { TodoRow(it, snap, true, snap.boxes.getOrNull(it.box)?.name ?: "", { model.toggle(it) }, { tap(it) }) }
                    Text("완료한 할 일 지우기", color = UI.red, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(6.dp).clickable { model.clearCompleted() })
                }
            }
        } else if (grid) {
            for (row in 0..1) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (col in 0..1) {
                        val i = row * 2 + col
                        BoxPanel(model, i, Modifier.weight(1f), onAdd = { onEdit(EditTarget(null, i)) }, onTap = tap)
                    }
                }
            }
        } else {
            GroupedList(model, tap)
        }
        Spacer(Modifier.height(90.dp))
    }
}

@Composable
fun BoxPanel(model: AppModel, i: Int, modifier: Modifier, onAdd: () -> Unit, onTap: (PlanItem) -> Unit) {
    val snap = model.snap
    val box = snap.boxes[i]
    val items = snap.boxItems(i)
    Card(modifier.heightIn(min = 280.dp), radius = 28, padding = 11) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(box.color.c()))
                Spacer(Modifier.width(7.dp))
                Text(box.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = UI.ink, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("${items.size}", fontSize = 14.sp, color = UI.secondary)
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(28.dp).clip(CircleShape).background(UI.bg).clickable { onAdd() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Add, "추가", tint = UI.secondary, modifier = Modifier.size(16.dp))
                }
            }
            items.forEach { TodoRow(it, snap, false, box.name, { model.toggle(it) }, { onTap(it) }) }
        }
    }
}

@Composable
fun GroupedList(model: AppModel, onTap: (PlanItem) -> Unit) {
    val snap = model.snap
    val open = snap.openTodos.sortedWith(Snapshot.byDue)
    val today = snap.today
    val groups = listOf(
        Triple("기한 지남", UI.red, open.filter { snap.isOverdue(it) }),
        Triple("오늘", UI.ink, open.filter { !snap.isOverdue(it) && it.start?.toLocalDate() == today }),
        Triple("내일", UI.ink, open.filter { it.start?.toLocalDate() == today.plusDays(1) }),
        Triple("이번 주", UI.ink, open.filter { s -> s.start?.toLocalDate()?.let { it >= today.plusDays(2) && it < today.plusDays(7) } == true }),
        Triple("나중에", UI.ink, open.filter { s -> s.start?.toLocalDate()?.let { it >= today.plusDays(7) } == true }),
        Triple("날짜 없음", UI.secondary, open.filter { it.start == null }),
    )
    if (open.isEmpty()) Card(Modifier.fillMaxWidth()) { Text("할 일이 없어요. +를 눌러 추가하세요", color = UI.secondary) }
    groups.filter { it.third.isNotEmpty() }.forEach { (title, color, items) ->
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$title  ${items.size}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = color, modifier = Modifier.padding(start = 4.dp))
            Card(Modifier.fillMaxWidth(), padding = 10) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items.forEach { TodoRow(it, snap, true, snap.boxes.getOrNull(it.box)?.name ?: "", { model.toggle(it) }, { onTap(it) }) }
                }
            }
        }
    }
}

@Composable
fun StarLabel(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Star, null, tint = UI.orange, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, fontWeight = FontWeight.SemiBold, color = UI.ink)
    }
}
