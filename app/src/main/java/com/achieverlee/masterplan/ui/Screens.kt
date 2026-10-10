package com.achieverlee.masterplan.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.achieverlee.masterplan.AppModel
import com.achieverlee.masterplan.Background
import com.achieverlee.masterplan.Box as PlanBox
import com.achieverlee.masterplan.Defaults
import com.achieverlee.masterplan.K
import com.achieverlee.masterplan.PlanItem
import com.achieverlee.masterplan.Template
import com.achieverlee.masterplan.Todo
import com.achieverlee.masterplan.Updater
import com.achieverlee.masterplan.WallpaperRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime

// MARK: 캘린더 탭

@Composable
fun CalendarScreen(model: AppModel, onEdit: (EditTarget) -> Unit, onOpenEvent: (PlanItem) -> Unit, onRequestPermission: () -> Unit) {
    val snap = model.snap
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenTitle("캘린더", K.fmt("yyyy년 M월", snap.today))
        if (!model.hasPermission) PermissionCard(onRequestPermission)
        for (i in 0 until 21) {
            val day = snap.today.plusDays(i.toLong())
            val items = snap.dayItems(day)
            if (items.isEmpty() && i > 0) continue
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    (if (i == 0) "오늘 · " else if (i == 1) "내일 · " else "") + K.fmt("M월 d일 EEEE", day),
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (i == 0) UI.red else UI.ink,
                    modifier = Modifier.padding(start = 4.dp),
                )
                Card(Modifier.fillMaxWidth(), padding = 12) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (items.isEmpty()) Text("일정이 없어요", color = UI.secondary, fontSize = 14.sp)
                        items.forEach { item -> AgendaRow(item, day) { if (item.isEvent) onOpenEvent(item) else onEdit(EditTarget(item.todoId, item.box)) } }
                    }
                }
            }
        }
        Spacer(Modifier.height(90.dp))
    }
}

@Composable
fun AgendaRow(item: PlanItem, day: LocalDate, onTap: () -> Unit) {
    val color = item.color.c()
    val label = when {
        item.isEvent && item.allDay -> "종일"
        item.start == null -> ""
        !item.hasTime -> "당일"
        item.start.toLocalDate() != day -> "계속"
        else -> K.time(item.start)
    }
    Row(Modifier.fillMaxWidth().clickable { onTap() }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = UI.secondary, modifier = Modifier.width(62.dp))
        if (item.isEvent) Box(Modifier.width(4.dp).height(22.dp).clip(RoundedCornerShape(2.dp)).background(color))
        else Box(Modifier.size(14.dp).clip(CircleShape).border(1.6.dp, color, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(item.title, fontSize = 15.sp, color = if (item.done) UI.secondary else UI.ink, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (item.isEvent && !item.allDay && item.end != null && item.start != null) {
            Text(K.time(item.end), fontSize = 12.sp, color = color)
        }
    }
}

// MARK: 잠금화면 탭

private fun preview(model: AppModel, t: Template, width: Int): Bitmap {
    val (sw, sh) = Updater.screenSize(model.ctx)
    val h = (width.toFloat() * sh / sw).toInt()
    WallpaperRenderer.topFraction = model.data.topPercent / 100f
    val bmp = WallpaperRenderer.render(t, model.data.background, model.snap, width, h)
    // 미리보기에만 시계를 얹는다(실제 잠금화면 시계는 휴대폰이 그린다).
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textAlign = Paint.Align.CENTER }
    p.textSize = width * 0.04f
    c.drawText(K.fmt("M월 d일 EEEE", model.snap.today), width / 2f, h * 0.12f, p)
    p.textSize = width * 0.15f
    p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    c.drawText(K.time(model.snap.now).replace("오전 ", "").replace("오후 ", ""), width / 2f, h * 0.21f, p)
    return bmp
}

@Composable
fun LockScreenTab(model: AppModel) {
    var current by remember { mutableStateOf<Bitmap?>(null) }
    var gallery by remember { mutableStateOf<Map<Template, Bitmap>>(emptyMap()) }
    var message by remember { mutableStateOf<String?>(null) }
    val data = model.data

    LaunchedEffect(model.version) {
        withContext(Dispatchers.Default) {
            current = preview(model, data.template, 540)
            gallery = Template.entries.associateWith { preview(model, it, 300) }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScreenTitle("잠금화면")
        Card(Modifier.fillMaxWidth(), radius = 30, padding = 14) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.width(130.dp).clip(RoundedCornerShape(18.dp))) {
                    current?.let { Image(it.asImageBitmap(), "현재 잠금화면", modifier = Modifier.fillMaxWidth()) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("사용 중", fontSize = 13.sp, color = UI.secondary)
                    Text(data.template.label, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = UI.ink)
                    val last = data.lastAppliedAt?.let { val t = K.local(it); "${K.fmt("M월 d일", t.toLocalDate())} ${K.time(t)}" }
                    Text(if (last != null) "마지막 적용 $last" else "아직 적용하지 않았어요", fontSize = 13.sp, color = UI.secondary)
                    data.lastError?.let { Text("오류: $it", fontSize = 12.sp, color = UI.red) }
                    Text("지금 잠금화면에 적용", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                        modifier = Modifier.clip(CircleShape).background(UI.ink).clickable { message = model.applyNow() }
                            .padding(horizontal = 16.dp, vertical = 11.dp))
                    message?.let { Text(it, fontSize = 13.sp, color = if (it.startsWith("실패")) UI.red else UI.green) }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("자동으로 바꾸기", fontWeight = FontWeight.SemiBold, color = UI.ink)
                    Text("30분마다, 자정, 캘린더가 바뀔 때, 앱을 나갈 때 다시 그려요", fontSize = 13.sp, color = UI.secondary)
                }
                Switch(checked = data.autoUpdate, onCheckedChange = { model.setAutoUpdate(it) })
            }
        }

        Card(Modifier.fillMaxWidth()) {
            var v by remember(data.topPercent) { mutableStateOf(data.topPercent.toFloat()) }
            Column {
                Text("카드 시작 높이  ${v.toInt()}%", fontWeight = FontWeight.SemiBold, color = UI.ink)
                Text("잠금화면 시계와 겹치면 늘리고, 위가 비어 보이면 줄이세요", fontSize = 13.sp, color = UI.secondary)
                Slider(value = v, onValueChange = { v = it }, valueRange = 15f..50f, steps = 34,
                    onValueChangeFinished = { model.setTopPercent(v.toInt()) })
            }
        }

        Text("배경", fontWeight = FontWeight.SemiBold, color = UI.ink, modifier = Modifier.padding(start = 4.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Background.entries.forEach { b ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { model.setBackground(b) }) {
                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp))
                        .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(b.top.c(), b.bottom.c())))
                        .border(if (b == data.background) BorderStroke(3.dp, UI.ink) else BorderStroke(0.dp, Color.Transparent), RoundedCornerShape(14.dp)))
                    Text(b.label, fontSize = 12.sp, color = UI.ink)
                }
            }
        }

        Text("템플릿", fontWeight = FontWeight.SemiBold, color = UI.ink, modifier = Modifier.padding(start = 4.dp))
        Template.entries.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { t ->
                    Column(Modifier.weight(1f).clickable { model.setTemplate(t) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.fillMaxWidth().aspectRatio(0.46f).clip(RoundedCornerShape(22.dp))
                            .border(if (t == data.template) BorderStroke(3.dp, UI.ink) else BorderStroke(0.dp, Color.Transparent), RoundedCornerShape(22.dp))) {
                            gallery[t]?.let { Image(it.asImageBitmap(), t.label, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (t == Template.CeoPick) {
                                Icon(Icons.Filled.Star, null, tint = UI.orange, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(t.label, fontWeight = FontWeight.SemiBold, color = UI.ink, fontSize = 15.sp)
                            if (t == data.template) {
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Filled.Check, "사용 중", tint = UI.green, modifier = Modifier.size(16.dp))
                            }
                        }
                        Text(t.subtitle, fontSize = 12.sp, color = UI.secondary)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

// MARK: 설정 탭

@Composable
fun SettingsScreen(model: AppModel, onRequestPermission: () -> Unit, onBattery: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenTitle("설정")
        if (!model.hasPermission) PermissionCard(onRequestPermission)

        Text("할 일 네 칸", fontWeight = FontWeight.SemiBold, color = UI.ink, modifier = Modifier.padding(start = 4.dp))
        Text("칸마다 캘린더를 연결하면 그 캘린더의 오늘 일정이 그 칸의 할 일로 들어와요. 칸 이름과 색은 캘린더를 따라가요. 체크는 이 앱 안에서만 남고 캘린더 일정은 바뀌지 않아요.",
            fontSize = 13.sp, color = UI.secondary, modifier = Modifier.padding(horizontal = 4.dp))
        model.data.boxes.forEachIndexed { i, box -> BoxSettings(model, i, box) }

        Text("자동 갱신이 멈출 때", fontWeight = FontWeight.SemiBold, color = UI.ink, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("갤럭시는 배터리 절약 때문에 백그라운드 작업을 멈출 수 있어요. 아래 버튼에서 '제한 없음(최적화 안 함)'으로 바꿔 주세요.",
                    fontSize = 13.sp, color = UI.secondary)
                Text("배터리 최적화 해제", color = Color.White, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(CircleShape).background(UI.ink).clickable { onBattery() }.padding(horizontal = 16.dp, vertical = 10.dp))
                Text("잠금화면이 안 바뀌면: 설정 > 잠금화면 > 배경화면 서비스(다이내믹 잠금화면)를 '사용 안 함'으로 두세요. 마스터 플랜의 잠금화면 탭에서 '지금 잠금화면에 적용'을 누르면 바로 다시 그려요.",
                    fontSize = 13.sp, color = UI.secondary)
            }
        }
        Text("마스터 플랜 · 안드로이드판", fontSize = 12.sp, color = UI.secondary, modifier = Modifier.padding(start = 4.dp))
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
fun BoxSettings(model: AppModel, i: Int, box: PlanBox) {
    var name by remember(box.name) { mutableStateOf(box.name) }
    var menu by remember { mutableStateOf(false) }
    val cal = model.calendars.firstOrNull { it.id == box.calendarId }
    val shownColor = (cal?.color ?: box.color).c()
    Card(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(shownColor))
                Spacer(Modifier.width(8.dp))
                Text("${i + 1}번 칸", fontSize = 13.sp, color = UI.secondary)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("칸 이름") }, modifier = Modifier.weight(1f))
                if (name.trim() != box.name && name.isNotBlank()) {
                    TextButton(onClick = { model.setBox(i, box.copy(name = name.trim())) }) { Text("저장") }
                }
            }
            Box {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(UI.field).clickable { menu = true }
                    .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("연결한 캘린더", fontSize = 14.sp, color = UI.secondary, modifier = Modifier.weight(1f))
                    Text(cal?.name ?: "연결 안 함", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = UI.ink)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("연결 안 함") }, onClick = {
                        menu = false
                        model.setBox(i, box.copy(calendarId = null, color = Defaults.boxColors[i]))
                    })
                    model.calendars.forEach { c ->
                        DropdownMenuItem(
                            leadingIcon = { Box(Modifier.size(12.dp).clip(CircleShape).background(c.color.c())) },
                            text = {
                                Column {
                                    Text(c.name)
                                    Text(c.account, fontSize = 11.sp, color = UI.secondary)
                                }
                            },
                            onClick = {
                                menu = false
                                model.setBox(i, box.copy(calendarId = c.id, name = c.name, color = c.color))
                            },
                        )
                    }
                }
            }
            if (cal == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val palette = Defaults.boxColors + listOf(0xFF34C759.toInt(), 0xFFAF52DE.toInt(), 0xFFFF9F0A.toInt())
                    palette.forEach { col ->
                        Box(Modifier.size(28.dp).clip(CircleShape).background(col.c())
                            .border(if (col == box.color) 3.dp else 0.dp, UI.ink, CircleShape)
                            .clickable { model.setBox(i, box.copy(color = col)) })
                    }
                }
            }
        }
    }
}

// MARK: 할 일 편집

@Composable
fun TodoEditor(model: AppModel, target: EditTarget, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val old: Todo? = model.todo(target.todoId)
    var title by remember { mutableStateOf(old?.title ?: "") }
    var box by remember { mutableStateOf(old?.box ?: target.box) }
    var day by remember { mutableStateOf(old?.due?.let { K.local(it).toLocalDate() }) }
    var time by remember { mutableStateOf(old?.let { if (it.hasTime && it.due != null) K.local(it.due).toLocalTime() else null }) }
    val today = LocalDate.now()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = UI.card,
        title = { Text(if (old == null) "새 할 일" else "할 일 편집", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("무엇을 할까요?") }, modifier = Modifier.fillMaxWidth())
                Text("칸", fontSize = 13.sp, color = UI.secondary)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    model.snap.boxes.forEachIndexed { i, b ->
                        Row(Modifier.clip(CircleShape).background(if (box == i) b.color.c() else UI.field).clickable { box = i }
                            .padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(b.name, fontSize = 13.sp, color = if (box == i) Color.White else UI.ink, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Text("마감", fontSize = 13.sp, color = UI.secondary)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("없음", day == null) { day = null; time = null }
                    Pill("오늘", day == today) { day = today }
                    Pill("내일", day == today.plusDays(1)) { day = today.plusDays(1) }
                    val other = day != null && day != today && day != today.plusDays(1)
                    Pill(if (other) K.fmt("M월 d일", day!!) else "날짜 선택", other) {
                        val d = day ?: today
                        DatePickerDialog(ctx, { _, y, m, dd -> day = LocalDate.of(y, m + 1, dd) }, d.year, d.monthValue - 1, d.dayOfMonth).show()
                    }
                }
                if (day != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("시간 없음", time == null) { time = null }
                        Pill(time?.let { K.time(LocalDate.now().atTime(it)) } ?: "시간 선택", time != null) {
                            val t = time ?: LocalTime.of(9, 0)
                            TimePickerDialog(ctx, { _, h, m -> time = LocalTime.of(h, m) }, t.hour, t.minute, false).show()
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = {
                val due = model.dueMillis(day, time)
                val t = (old ?: Todo(title = title.trim(), box = box)).copy(title = title.trim(), box = box, due = due, hasTime = time != null)
                model.saveTodo(t)
                onDismiss()
            }) { Text("저장", fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            Row {
                if (old != null) TextButton(onClick = { model.deleteTodo(old.id); onDismiss() }) { Text("삭제", color = UI.red) }
                TextButton(onClick = onDismiss) { Text("취소") }
            }
        },
    )
}
