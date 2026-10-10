package com.achieverlee.masterplan

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** 할 일 칸 하나. 캘린더를 연결하면 그 캘린더의 오늘 일정이 이 칸의 할 일로 들어온다. */
@Serializable
data class Box(
    val name: String,
    val color: Int,
    /** 연결한 캘린더(CalendarContract.Calendars._ID). 없으면 직접 넣은 할 일만. */
    val calendarId: Long? = null,
)

@Serializable
data class Todo(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val box: Int,
    /** 마감(epoch ms). 시각이 없으면 그날 0시. */
    val due: Long? = null,
    val hasTime: Boolean = false,
    val note: String = "",
    val completedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class Completion(val title: String, val box: Int, val at: Long)

enum class Template(val label: String, val subtitle: String) {
    CeoPick("대표님 선호", "올해 진행률(365개 점)과 네 칸"),
    Matrix("매트릭스", "이번 주와 네 칸"),
    Calendar("캘린더", "올해 진행과 한 달 달력"),
    Timeline("오늘 타임라인", "이번 주와 오늘 일정·할 일"),
    TodayYear("오늘 + 연간 진행", "365개 점, 이번 주, 오늘 타임라인"),
    Checklist("체크리스트", "이번 주와 오늘 할 일 목록"),
}

enum class Background(val label: String, val top: Int, val bottom: Int) {
    Navy("남색", 0xFF204C84.toInt(), 0xFF0D1733.toInt()),
    Black("검정", 0xFF1C1C1E.toInt(), 0xFF000000.toInt()),
    Purple("보라", 0xFF4B2E83.toInt(), 0xFF140C2B.toInt()),
    Forest("숲", 0xFF1F5C4A.toInt(), 0xFF07201A.toInt()),
    Rose("장미", 0xFF8A3B5C.toInt(), 0xFF26101C.toInt()),
    Slate("회색", 0xFF5A6270.toInt(), 0xFF1E2228.toInt()),
}

@Serializable
data class AppData(
    val boxes: List<Box> = Defaults.boxes,
    val todos: List<Todo> = emptyList(),
    val completions: List<Completion> = emptyList(),
    /** 캘린더에서 온 할 일의 완료 표시: "일정ID|시작|yyyyMMdd" → 완료 시각. 캘린더 일정은 건드리지 않는다. */
    val calendarDone: Map<String, Long> = emptyMap(),
    val template: Template = Template.CeoPick,
    val background: Background = Background.Navy,
    val autoUpdate: Boolean = true,
    /** 카드가 시작하는 높이(화면 위에서 %). 시계 크기·위치에 맞춰 사용자가 고른다. */
    val topPercent: Int = 27,
    /** 숨긴 캘린더(달력·타임라인에서 뺀다). */
    val hiddenCalendars: Set<Long> = emptySet(),
    val lastAppliedAt: Long? = null,
    val lastAppliedSignature: String? = null,
    val lastError: String? = null,
    val screenW: Int = 0,
    val screenH: Int = 0,
)

object Defaults {
    val boxColors = listOf(0xFFFF6B5E.toInt(), 0xFF4A9BF5.toInt(), 0xFFF5C33B.toInt(), 0xFF9A9AA0.toInt())
    val boxes = listOf(
        Box("중요&긴급", boxColors[0]),
        Box("중요", boxColors[1]),
        Box("긴급", boxColors[2]),
        Box("언젠가", boxColors[3]),
    )
}

/** 앱·백그라운드 작업이 같이 쓰는 저장소(앱 내부 파일 하나). */
object Store {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    @Volatile private var cache: AppData? = null

    private fun file(ctx: Context) = File(ctx.filesDir, "data.json")

    fun load(ctx: Context): AppData = synchronized(lock) {
        cache ?: run {
            val f = file(ctx)
            val d = if (f.exists()) runCatching { json.decodeFromString<AppData>(f.readText()) }.getOrNull() ?: AppData() else AppData()
            cache = d
            d
        }
    }

    fun update(ctx: Context, change: (AppData) -> AppData): AppData = synchronized(lock) {
        val d = change(load(ctx))
        val f = file(ctx)
        val tmp = File(f.parentFile, "data.json.tmp")
        tmp.writeText(json.encodeToString(d))
        tmp.renameTo(f)
        cache = d
        d
    }
}
