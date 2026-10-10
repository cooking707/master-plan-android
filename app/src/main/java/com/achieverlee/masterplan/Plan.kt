package com.achieverlee.masterplan

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

data class CalendarInfo(val id: Long, val name: String, val account: String, val color: Int)

/** 일정·할 일을 한 줄로 다루는 형태. 캘린더에서 온 할 일은 fromCalendar. */
data class PlanItem(
    val id: String,
    val isEvent: Boolean,
    val title: String,
    val start: LocalDateTime?,
    val end: LocalDateTime?,
    val allDay: Boolean,
    val hasTime: Boolean,
    val color: Int,
    val done: Boolean,
    val box: Int,
    val fromCalendar: Boolean = false,
    val eventId: Long? = null,
    val calendarId: Long? = null,
    val todoId: String? = null,
    val doneAt: Long? = null,
)

object K {
    val zone: ZoneId get() = ZoneId.systemDefault()
    private val ko = Locale.KOREAN
    fun time(t: LocalDateTime): String = t.format(DateTimeFormatter.ofPattern("a h:mm", ko))
    fun fmt(pattern: String, d: LocalDate): String = d.format(DateTimeFormatter.ofPattern(pattern, ko))
    fun key(d: LocalDate): String = d.format(DateTimeFormatter.BASIC_ISO_DATE)
    fun millis(t: LocalDateTime): Long = t.atZone(zone).toInstant().toEpochMilli()
    fun local(ms: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)
    /** 일요일 시작 한 주의 첫날. */
    fun startOfWeek(d: LocalDate): LocalDate = d.minusDays((d.dayOfWeek.value % 7).toLong())
    val weekdays = listOf("일", "월", "화", "수", "목", "금", "토")
}

object CalendarRepo {
    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun calendars(ctx: Context): List<CalendarInfo> {
        if (!hasPermission(ctx)) return emptyList()
        val out = mutableListOf<CalendarInfo>()
        val proj = arrayOf(
            CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.CALENDAR_COLOR,
        )
        ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj,
            "${CalendarContract.Calendars.VISIBLE} = 1", null, null)?.use { c ->
            while (c.moveToNext()) {
                out += CalendarInfo(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getInt(3))
            }
        }
        return out.sortedWith(compareBy({ it.account }, { it.name }))
    }

    /** [from, to) 사이의 일정(반복 일정은 회차별). 종일 일정은 UTC 날짜를 현지 날짜로 옮긴다. */
    fun events(ctx: Context, from: LocalDate, to: LocalDate, hidden: Set<Long>): List<PlanItem> {
        if (!hasPermission(ctx)) return emptyList()
        val zone = K.zone
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, from.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        ContentUris.appendId(b, to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        val proj = arrayOf(
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.DISPLAY_COLOR, CalendarContract.Instances.STATUS,
        )
        val out = mutableListOf<PlanItem>()
        ctx.contentResolver.query(b.build(), proj, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                val calId = c.getLong(5)
                if (calId in hidden) continue
                if (c.getInt(7) == CalendarContract.Instances.STATUS_CANCELED) continue
                val allDay = c.getInt(4) == 1
                val begin = c.getLong(2)
                val end = c.getLong(3)
                val s: LocalDateTime
                val e: LocalDateTime
                if (allDay) {
                    s = Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay()
                    e = Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay()
                } else {
                    s = K.local(begin)
                    e = K.local(end)
                }
                if (!e.isAfter(from.atStartOfDay()) && !s.isAfter(from.atStartOfDay())) continue
                if (!s.isBefore(to.atStartOfDay())) continue
                val eventId = c.getLong(0)
                out += PlanItem(
                    id = "e-$eventId-$begin", isEvent = true, title = c.getString(1) ?: "(제목 없음)",
                    start = s, end = e, allDay = allDay, hasTime = !allDay, color = c.getInt(6),
                    done = false, box = -1, eventId = eventId, calendarId = calId,
                )
            }
        }
        return out
    }
}

/** 렌더링·화면 한 번에 쓰는 데이터 묶음. */
class Snapshot(
    val now: LocalDateTime,
    val events: List<PlanItem>,
    val todos: List<PlanItem>,
    val calendarTasks: List<PlanItem>,
    val boxes: List<Box>,
) {
    val today: LocalDate get() = now.toLocalDate()
    private val allTodos get() = todos + calendarTasks

    fun occurs(item: PlanItem, day: LocalDate): Boolean {
        val ds = day.atStartOfDay()
        val de = day.plusDays(1).atStartOfDay()
        return if (item.isEvent) {
            val s = item.start ?: return false
            val e = item.end ?: return false
            if (item.allDay) s.isBefore(de) && e.isAfter(ds)
            else s.isBefore(de) && (e.isAfter(ds) || !s.isBefore(ds))
        } else {
            val d = item.start ?: (if (item.done) item.doneAt?.let { K.local(it) } else null) ?: return false
            !d.isBefore(ds) && d.isBefore(de)
        }
    }

    private fun sortKey(item: PlanItem, day: LocalDate): Double {
        val ds = day.atStartOfDay()
        if (item.isEvent && item.allDay) return -2.0
        if (!item.isEvent && item.done) return 200_000.0
        if (!item.isEvent && !item.hasTime) return -1.0
        val s = item.start ?: return 100_000.0
        return maxOf(0.0, ChronoUnit.SECONDS.between(ds, s).toDouble())
    }

    /** 그날의 일정·할 일. 캘린더에서 온 할 일은 원래 일정과 겹치므로 뺀다. */
    fun dayItems(day: LocalDate): List<PlanItem> =
        (events + todos).filter { occurs(it, day) }.sortedBy { sortKey(it, day) }

    val openTodos: List<PlanItem> get() = allTodos.filter { !it.done }

    fun boxItems(i: Int): List<PlanItem> = openTodos.filter { it.box == i }.sortedWith(byDue)

    fun isOverdue(item: PlanItem): Boolean {
        if (item.isEvent || item.done || item.fromCalendar) return false
        val d = item.start ?: return false
        return if (item.hasTime) d.isBefore(now) else d.toLocalDate().isBefore(today)
    }

    data class Row(val item: PlanItem, val label: String, val key: Double)

    /** 오늘 타임라인: 지남 → 종일 → 시간순. */
    fun todayRows(eventsOnly: Boolean = false): List<Row> {
        val ds = today.atStartOfDay()
        val out = mutableListOf<Row>()
        for (item in dayItems(today)) {
            if (eventsOnly && !item.isEvent) continue
            when {
                item.isEvent && item.allDay -> out += Row(item, "종일", -2.0)
                !item.isEvent && !item.hasTime -> out += Row(item, "종일", -1.0)
                item.start != null -> {
                    val shown = maxOf(item.start, ds)
                    out += Row(item, K.time(shown), ChronoUnit.SECONDS.between(ds, shown).toDouble())
                }
            }
        }
        if (!eventsOnly) {
            for (t in todos) if (isOverdue(t) && t.start?.toLocalDate() != today) out += Row(t, "지남", -3.0)
        }
        return out.sortedBy { it.key }
    }

    val undatedTodos: List<PlanItem> get() = todos.filter { !it.done && it.start == null }.sortedWith(byDue)

    /** 체크리스트: 오늘 마감(캘린더 할 일 포함) + 마감 없는 할 일. */
    val checklist: List<PlanItem>
        get() = openTodos.filter { it.start?.toLocalDate() == today }.sortedBy { sortKey(it, today) } + undatedTodos

    companion object {
        val byDue = Comparator<PlanItem> { a, b ->
            when {
                a.start != null && b.start != null -> a.start.compareTo(b.start)
                a.start != null -> -1
                b.start != null -> 1
                else -> 0
            }
        }

        /** 저장된 데이터와 캘린더로 스냅샷을 만든다. */
        fun build(ctx: Context, data: AppData = Store.load(ctx), now: LocalDateTime = LocalDateTime.now()): Snapshot {
            val today = now.toLocalDate()
            val from = K.startOfWeek(today).minusDays(7)
            val to = K.startOfWeek(today).plusDays(42)
            val events = CalendarRepo.events(ctx, from, to, data.hiddenCalendars)
            val calendars = CalendarRepo.calendars(ctx)
            // 칸 색: 연결한 캘린더가 있으면 그 색.
            val boxes = data.boxes.map { box ->
                val cal = box.calendarId?.let { id -> calendars.firstOrNull { it.id == id } }
                if (cal != null) box.copy(color = cal.color) else box
            }
            val todos = data.todos.map { t ->
                PlanItem(
                    id = "t-${t.id}", isEvent = false, title = t.title,
                    start = t.due?.let { K.local(it) }, end = null, allDay = false,
                    hasTime = t.due != null && t.hasTime, color = boxes.getOrNull(t.box)?.color ?: Defaults.boxColors[0],
                    done = t.completedAt != null, box = t.box, todoId = t.id, doneAt = t.completedAt,
                )
            }
            val tasks = calendarTasks(events, boxes, data, today)
            return Snapshot(now, events, todos, tasks, boxes)
        }

        fun taskKey(e: PlanItem, day: LocalDate) = "${e.eventId}|${e.start}|${K.key(day)}"

        /** 칸에 연결한 캘린더의 오늘 일정을 그 칸의 할 일로 만든다. */
        private fun calendarTasks(events: List<PlanItem>, boxes: List<Box>, data: AppData, today: LocalDate): List<PlanItem> {
            val ds = today.atStartOfDay()
            val de = today.plusDays(1).atStartOfDay()
            val out = mutableListOf<PlanItem>()
            for (e in events) {
                val boxIndex = boxes.indexOfFirst { it.calendarId != null && it.calendarId == e.calendarId }
                if (boxIndex < 0) continue
                val s = e.start ?: continue
                val end = e.end ?: continue
                if (!(s.isBefore(de) && (end.isAfter(ds) || !s.isBefore(ds)))) continue
                val key = taskKey(e, today)
                val doneAt = data.calendarDone[key]
                val timed = !e.allDay && !s.isBefore(ds)
                out += PlanItem(
                    id = "c-$key", isEvent = false, title = e.title,
                    start = if (timed) s else ds, end = null, allDay = false, hasTime = timed,
                    color = boxes[boxIndex].color, done = doneAt != null, box = boxIndex,
                    fromCalendar = true, eventId = e.eventId, calendarId = e.calendarId, doneAt = doneAt,
                )
            }
            return out
        }
    }
}

/** 할 일 칸 안의 마감 표기. 오늘이면 시각만, 다른 날이면 "10월 20일". */
fun PlanItem.dueLabel(today: LocalDate): String {
    val d = start ?: return ""
    if (d.toLocalDate() == today) return if (hasTime) K.time(d) else ""
    return K.fmt("M월 d일", d.toLocalDate())
}

/** 앱 목록의 마감 표기. */
fun PlanItem.appDueLabel(today: LocalDate): String? {
    val d = start ?: return null
    val day = d.toLocalDate()
    val prefix = when (day) {
        today -> "오늘"
        today.plusDays(1) -> "내일"
        else -> K.fmt("M월 d일", day)
    }
    return if (hasTime) "$prefix ${K.time(d)}" else prefix
}
