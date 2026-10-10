package com.achieverlee.masterplan

import android.app.Application
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

class MasterPlanApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Updater.schedule(this)
    }
}

/**
 * 잠금화면 갱신. 안드로이드는 앱이 잠금화면 배경을 직접 바꿀 수 있어서 단축어 같은 중간 단계가 없다.
 * 갱신 시점: 30분마다, 매일 0시 1분, 캘린더가 바뀔 때, 앱에서 무언가 바꿨을 때, 앱을 나갈 때.
 * 그린 내용이 지난번과 같으면 배경을 다시 쓰지 않는다(깜빡임·배터리 절약).
 */
object Updater {
    private const val PERIODIC = "periodic"
    private const val MIDNIGHT = "midnight"
    private const val CALENDAR = "calendar-change"
    private const val NOW = "now"

    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UpdateWorker>(30, TimeUnit.MINUTES).build())
        scheduleMidnight(ctx, ExistingWorkPolicy.KEEP)
        watchCalendar(ctx, ExistingWorkPolicy.KEEP)
    }

    /** 작업 안에서 다시 걸 때는 APPEND_OR_REPLACE(지금 도는 작업을 취소하지 않게), 앱 시작 때는 KEEP. */
    fun scheduleMidnight(ctx: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE) {
        val now = LocalDateTime.now()
        val next = now.toLocalDate().plusDays(1).atTime(0, 1)
        val delay = Duration.between(now, next).toMillis()
        WorkManager.getInstance(ctx).enqueueUniqueWork(MIDNIGHT, policy,
            OneTimeWorkRequestBuilder<UpdateWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .addTag(MIDNIGHT).build())
    }

    /** 캘린더 내용이 바뀌면 깨어나는 일회성 작업. 실행될 때마다 다시 건다. */
    fun watchCalendar(ctx: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE) {
        if (!CalendarRepo.hasPermission(ctx)) return
        val c = Constraints.Builder()
            .addContentUriTrigger(CalendarContract.Events.CONTENT_URI, true)
            .addContentUriTrigger(CalendarContract.Instances.CONTENT_URI, true)
            .setTriggerContentUpdateDelay(20, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(2, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(CALENDAR, policy,
            OneTimeWorkRequestBuilder<UpdateWorker>().setConstraints(c).addTag(CALENDAR).build())
    }

    fun requestNow(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<UpdateWorker>().addTag(NOW).build())
    }

    fun screenSize(ctx: Context): Pair<Int, Int> {
        val d = Store.load(ctx)
        if (d.screenW > 0 && d.screenH > 0) return d.screenW to d.screenH
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(m)
        return minOf(m.widthPixels, m.heightPixels) to maxOf(m.widthPixels, m.heightPixels)
    }

    fun signature(d: AppData, snap: Snapshot, w: Int, h: Int): String {
        val sb = StringBuilder()
        sb.append(d.template).append('|').append(d.topPercent).append('|').append(d.background).append('|').append(snap.today).append('|').append(w).append('x').append(h)
        snap.boxes.forEach { sb.append('|').append(it.name).append(it.color) }
        (snap.events + snap.todos + snap.calendarTasks).forEach {
            sb.append('|').append(it.id).append(it.title).append(it.done).append(it.start).append(it.end).append(it.color)
        }
        // 지남 표시는 시각에 따라 바뀐다.
        sb.append('|').append(snap.todos.count { snap.isOverdue(it) })
        return sb.toString().hashCode().toString()
    }

    /** 그려서 잠금화면에 적용한다. force면 내용이 같아도 다시 쓴다. 결과 메시지를 돌려준다. */
    fun apply(ctx: Context, force: Boolean = false): String {
        val d = Store.load(ctx)
        if (!d.autoUpdate && !force) return "자동 갱신 꺼짐"
        val (w, h) = screenSize(ctx)
        val snap = Snapshot.build(ctx, d)
        val sig = signature(d, snap, w, h)
        if (!force && sig == d.lastAppliedSignature) return "바뀐 내용 없음"
        return try {
            WallpaperRenderer.topFraction = d.topPercent / 100f
            val bmp = WallpaperRenderer.render(d.template, d.background, snap, w, h)
            WallpaperManager.getInstance(ctx).setBitmap(bmp, null, true, WallpaperManager.FLAG_LOCK)
            bmp.recycle()
            Store.update(ctx) { it.copy(lastAppliedAt = System.currentTimeMillis(), lastAppliedSignature = sig, lastError = null) }
            "적용함"
        } catch (e: Exception) {
            Store.update(ctx) { it.copy(lastError = "${e.javaClass.simpleName}: ${e.message}") }
            "실패: ${e.message}"
        }
    }
}

class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Updater.apply(applicationContext)
        if (tags.contains("midnight")) Updater.scheduleMidnight(applicationContext)
        if (tags.contains("calendar-change")) Updater.watchCalendar(applicationContext)
        return Result.success()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Updater.schedule(context)
        Updater.requestNow(context)
    }
}
