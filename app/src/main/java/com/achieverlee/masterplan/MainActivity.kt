package com.achieverlee.masterplan

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.achieverlee.masterplan.ui.MasterPlanUI
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {
    private lateinit var model: AppModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        rememberScreenSize()
        model = AppModel(applicationContext)
        setContent { MasterPlanUI(model) }
    }

    override fun onResume() {
        super.onResume()
        model.refresh()
        model.checkUpdate()
    }

    /** 앱을 나가면 잠금화면을 다시 그린다(아이폰판의 '앱 닫힘 자동화'에 해당). */
    override fun onStop() {
        super.onStop()
        Updater.requestNow(applicationContext)
    }

    /** 펼친 화면 크기를 저장한다. Z 플립 바깥 화면(정사각형에 가까움)에서 열렸을 때는 저장하지 않는다. */
    private fun rememberScreenSize() {
        val (w, h) = if (Build.VERSION.SDK_INT >= 30) {
            val b = windowManager.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
        val sw = min(w, h)
        val sh = max(w, h)
        if (sh.toFloat() / sw > 1.6f) Store.update(this) { it.copy(screenW = sw, screenH = sh) }
    }
}

/** 화면이 보는 상태. 바꿀 때마다 저장하고 잠금화면 갱신을 요청한다. */
class AppModel(val ctx: Context) {
    var data by mutableStateOf(Store.load(ctx))
        private set
    var snap by mutableStateOf(Snapshot.build(ctx, data))
        private set
    var calendars by mutableStateOf(CalendarRepo.calendars(ctx))
        private set
    /** 미리보기를 다시 그릴 때 쓰는 번호. */
    var version by mutableIntStateOf(0)
        private set
    var hasPermission by mutableStateOf(CalendarRepo.hasPermission(ctx))
        private set

    /** 릴리스에 올라온 더 새 버전 이름(없으면 null). */
    var newVersion by mutableStateOf<String?>(null)
        private set
    private val versionCode: Long = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }.getOrDefault(0L)
    val versionName: String = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" }.getOrDefault("")

    /** GitHub 최신 릴리스(태그 v1.0.N, N = versionCode)와 비교한다. 실패하면 조용히 넘어간다. */
    fun checkUpdate() {
        Thread {
            runCatching {
                val c = java.net.URL("https://api.github.com/repos/cooking707/master-plan-android/releases/latest").openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                val body = c.inputStream.bufferedReader().readText()
                val tag = Regex("\"tag_name\"\\s*:\\s*\"v([0-9.]+)\"").find(body)?.groupValues?.get(1) ?: return@runCatching
                val n = tag.substringAfterLast('.').toLongOrNull() ?: return@runCatching
                newVersion = if (n > versionCode) tag else null
            }
        }.start()
    }

    fun setCalendarVisible(id: Long, visible: Boolean) = change { d ->
        d.copy(hiddenCalendars = if (visible) d.hiddenCalendars - id else d.hiddenCalendars + id)
    }

    fun refresh() {
        hasPermission = CalendarRepo.hasPermission(ctx)
        data = Store.load(ctx)
        calendars = CalendarRepo.calendars(ctx)
        snap = Snapshot.build(ctx, data)
        version++
    }

    fun onPermissionResult() {
        refresh()
        Updater.schedule(ctx)
        Updater.requestNow(ctx)
    }

    private fun change(f: (AppData) -> AppData) {
        Store.update(ctx, f)
        refresh()
        Updater.requestNow(ctx)
    }

    fun saveTodo(t: Todo) = change { d ->
        val list = d.todos.toMutableList()
        val i = list.indexOfFirst { it.id == t.id }
        if (i >= 0) list[i] = t else list += t
        d.copy(todos = list)
    }

    fun deleteTodo(id: String) = change { d -> d.copy(todos = d.todos.filter { it.id != id }) }

    fun toggle(item: PlanItem) = change { d ->
        val now = System.currentTimeMillis()
        if (item.fromCalendar) {
            val key = item.id.removePrefix("c-")
            val done = d.calendarDone.toMutableMap()
            val comps = d.completions.toMutableList()
            if (done.containsKey(key)) {
                done.remove(key)
                comps.removeAll { it.title == item.title && it.box == item.box && LocalDateTime.now().toLocalDate() == K.local(it.at).toLocalDate() }
            } else {
                done[key] = now
                comps += Completion(item.title, item.box, now)
            }
            // 60일 지난 표시는 지운다.
            val cutoff = K.key(LocalDate.now().minusDays(60))
            d.copy(calendarDone = done.filterKeys { it.substringAfterLast('|') >= cutoff }, completions = comps)
        } else {
            val todos = d.todos.map { t ->
                if (t.id != item.todoId) t
                else if (t.completedAt != null) t.copy(completedAt = null)
                else t.copy(completedAt = now)
            }
            val comps = d.completions.toMutableList()
            if (item.done) comps.removeAll { it.title == item.title && it.box == item.box }
            else comps += Completion(item.title, item.box, now)
            d.copy(todos = todos, completions = comps)
        }
    }

    fun clearCompleted() = change { d -> d.copy(todos = d.todos.filter { it.completedAt == null }) }

    fun setTemplate(t: Template) = change { it.copy(template = t) }
    fun setBackground(b: Background) = change { it.copy(background = b) }
    fun setAutoUpdate(on: Boolean) = change { it.copy(autoUpdate = on) }
    fun setTopPercent(v: Int) = change { it.copy(topPercent = v) }
    fun setBox(i: Int, box: Box) = change { d -> d.copy(boxes = d.boxes.toMutableList().also { it[i] = box }) }

    fun todo(id: String?): Todo? = data.todos.firstOrNull { it.id == id }

    fun applyNow(): String {
        val r = Updater.apply(ctx, force = true)
        refresh()
        return r
    }

    fun dueMillis(day: LocalDate?, time: LocalTime?): Long? =
        day?.let { K.millis(if (time != null) it.atTime(time) else it.atStartOfDay()) }

    companion object {
        val permission = Manifest.permission.READ_CALENDAR
        const val DOWNLOAD_URL = "https://github.com/cooking707/master-plan-android/releases/latest/download/master-plan.apk"
    }
}
