package com.achieverlee.masterplan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 잠금화면 한 장을 Canvas로 그린다. 기준 폭 402(아이폰판과 같은 비율)로 설계하고 화면 폭에 맞춰 늘린다.
 * 위쪽은 시계 자리, 아래쪽은 잠금화면 바로 가기 자리로 비워 둔다.
 */
object WallpaperRenderer {
    private const val BASE_W = 402f
    private const val GAP = 10f
    private const val CARD = 0xE6111419.toInt()
    private const val TEXT = 0xFFFFFFFF.toInt()
    private const val SECONDARY = 0xFF8E8E96.toInt()
    private const val FAINT = 0xFF3A3B42.toInt()

    enum class Kind { YearDots, YearBar, Week, Matrix, Month, Timeline, Checklist }

    data class Block(val kind: Kind, var rows: Int = 0, var lanes: Int = 2)

    fun blocks(t: Template): List<Block> = when (t) {
        Template.CeoPick -> listOf(Block(Kind.YearDots), Block(Kind.Matrix, rows = 3))
        Template.Matrix -> listOf(Block(Kind.Week, lanes = 2), Block(Kind.Matrix, rows = 3))
        Template.Calendar -> listOf(Block(Kind.YearBar), Block(Kind.Month, lanes = 2))
        Template.Timeline -> listOf(Block(Kind.Week, lanes = 2), Block(Kind.Timeline, rows = 15))
        Template.TodayYear -> listOf(Block(Kind.YearDots), Block(Kind.Week, lanes = 2), Block(Kind.Timeline, rows = 7))
        Template.Checklist -> listOf(Block(Kind.Week, lanes = 2), Block(Kind.Checklist, rows = 11))
    }

    /** 화면 위에서 카드가 시작하는 비율. 렌더 호출마다 설정값을 넣는다. */
    var topFraction = 0.27f
    fun topFraction() = topFraction
    fun bottomFraction() = 0.11f

    /** 넘치면 목록 줄 수, 매트릭스 줄 수, 달력 줄 수 순서로 줄인다. */
    private fun fit(blocks: List<Block>, snap: Snapshot, availPt: Float): List<Block> {
        fun used() = blocks.sumOf { height(it, snap).toDouble() }.toFloat() + GAP * (blocks.size - 1)
        for (b in blocks.filter { it.kind == Kind.Timeline || it.kind == Kind.Checklist }) {
            while (b.rows > 3 && used() > availPt) b.rows--
        }
        for (b in blocks.filter { it.kind == Kind.Matrix }) {
            while (b.rows > 1 && used() > availPt) b.rows--
        }
        for (b in blocks.filter { it.kind == Kind.Month || it.kind == Kind.Week }) {
            while (b.lanes > 1 && used() > availPt) b.lanes--
        }
        return blocks
    }

    fun usage(t: Template, snap: Snapshot, w: Int, h: Int): Float {
        val s = w / BASE_W
        val avail = h / s * (1 - topFraction() - bottomFraction())
        val bs = fit(blocks(t), snap, avail)
        return (bs.sumOf { height(it, snap).toDouble() }.toFloat() + GAP * (bs.size - 1)) / avail
    }

    fun render(t: Template, bg: Background, snap: Snapshot, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = w / BASE_W
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), bg.top, bg.bottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)

        val availPt = h / s * (1 - topFraction() - bottomFraction())
        val bs = fit(blocks(t), snap, availPt)
        c.save()
        c.scale(s, s)
        val d = Draw(c)
        var y = h / s * topFraction()
        for (b in bs) {
            val bh = height(b, snap)
            d.block(b, snap, 16f, y, BASE_W - 32f, bh)
            y += bh + GAP
        }
        c.restore()
        return bmp
    }

    // MARK: 높이(기준 단위)

    private fun weekLanesUsed(snap: Snapshot, lanes: Int): Int {
        val start = K.startOfWeek(snap.today)
        val most = (0..6).maxOf { snap.dayItems(start.plusDays(it.toLong())).count { i -> i.isEvent || !i.done } }
        return min(lanes, max(1, most))
    }

    private fun monthWeeks(snap: Snapshot): Int {
        val first = snap.today.withDayOfMonth(1)
        val start = K.startOfWeek(first)
        val last = first.plusMonths(1).minusDays(1)
        return (ChronoUnit.DAYS.between(start, last) / 7 + 1).toInt()
    }

    fun height(b: Block, snap: Snapshot): Float = when (b.kind) {
        Kind.YearDots -> 14f + 22f + 10f + 10 * 9.3f + 10f + 14f + 14f
        Kind.YearBar -> 14f + 22f + 12f + 8f + 10f + 14f + 14f
        Kind.Week -> 12f + 12f + 5f + 24f + 6f + weekLanesUsed(snap, b.lanes) * 15f + 12f + 8f
        Kind.Matrix -> 2 * matrixCardH(b.rows) + GAP
        Kind.Month -> 14f + 22f + 18f + monthWeeks(snap) * (24f + b.lanes * 14f) + 10f
        Kind.Timeline -> {
            val (top, bottom, hidden) = timelineSplit(snap, b.rows)
            14f + 24f + top.size * 23f + (if (bottom.isNotEmpty()) 10f + bottom.size * 23f else 0f) +
                (if (hidden > 0) 20f else 0f) + 10f + (if (top.isEmpty() && bottom.isEmpty()) 24f else 0f)
        }
        Kind.Checklist -> {
            val list = snap.checklist
            val shown = min(list.size, b.rows)
            14f + 20f + 16f + 8f + shown * 23f + (if (list.size > shown) 20f else 0f) + 10f + (if (list.isEmpty()) 24f else 0f)
        }
    }

    private fun matrixCardH(rows: Int) = 12f + 24f + 8f + rows * 21f + 18f + 8f

    private fun timelineSplit(snap: Snapshot, maxRows: Int): Triple<List<Snapshot.Row>, List<PlanItem>, Int> {
        val top = snap.todayRows()
        val bottom = snap.undatedTodos
        val topShown = top.take(maxRows)
        val bottomShown = bottom.take(max(0, maxRows - topShown.size))
        return Triple(topShown, bottomShown, top.size + bottom.size - topShown.size - bottomShown.size)
    }

    // MARK: 그리기

    private class Draw(val c: Canvas) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        fun tp(size: Float, color: Int, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
        }

        /** baseline이 아니라 글줄 가운데 y에 맞춰 쓴다. */
        fun text(s: String, x: Float, cy: Float, p: TextPaint, maxW: Float = 9999f, align: Paint.Align = Paint.Align.LEFT) {
            val str = TextUtils.ellipsize(s, p, maxW, TextUtils.TruncateAt.END).toString()
            p.textAlign = align
            val fm = p.fontMetrics
            c.drawText(str, x, cy - (fm.ascent + fm.descent) / 2, p)
        }

        fun width(s: String, p: TextPaint) = p.measureText(s)

        fun card(x: Float, y: Float, w: Float, h: Float, r: Float = 22f) {
            fill.color = CARD
            c.drawRoundRect(RectF(x, y, x + w, y + h), r, r, fill)
        }

        fun block(b: Block, snap: Snapshot, x: Float, y: Float, w: Float, h: Float) {
            when (b.kind) {
                Kind.YearDots -> yearDots(snap, x, y, w, h)
                Kind.YearBar -> yearBar(snap, x, y, w, h)
                Kind.Week -> week(snap, x, y, w, h, b.lanes)
                Kind.Matrix -> matrix(snap, x, y, w, b.rows)
                Kind.Month -> month(snap, x, y, w, h, b.lanes)
                Kind.Timeline -> timeline(snap, x, y, w, h, b.rows)
                Kind.Checklist -> checklist(snap, x, y, w, h, b.rows)
            }
        }

        private fun yearInfo(today: LocalDate): Triple<Int, Int, Int> {
            val total = today.lengthOfYear()
            val passed = today.dayOfYear
            val pct = (passed * 100f / total).toInt()
            return Triple(total, passed, pct)
        }

        private fun yearHeader(snap: Snapshot, x: Float, y: Float, w: Float, pct: Int) {
            text("${snap.today.year}", x + 14f, y + 14f + 11f, tp(17f, TEXT, true))
            text("$pct%", x + w - 14f, y + 14f + 11f, tp(17f, TEXT, true), align = Paint.Align.RIGHT)
        }

        private fun yearFooter(x: Float, w: Float, fy: Float, pct: Int, left: Int) {
            text("올해의 ${pct}%가 지났어요", x + 14f, fy, tp(11f, SECONDARY))
            text("${left}일 남음", x + w - 14f, fy, tp(11f, SECONDARY), align = Paint.Align.RIGHT)
        }

        fun yearDots(snap: Snapshot, x: Float, y: Float, w: Float, h: Float) {
            card(x, y, w, h)
            val (total, passed, pct) = yearInfo(snap.today)
            yearHeader(snap, x, y, w, pct)
            val cols = 37
            val gap = 2.4f
            val innerW = w - 28f
            val dot = (innerW - (cols - 1) * gap) / cols
            val gy = y + 14f + 22f + 10f
            for (i in 0 until total) {
                val r = i / cols
                val col = i % cols
                fill.color = if (i < passed) TEXT else FAINT
                val cx = x + 14f + col * (dot + gap) + dot / 2
                val cy = gy + r * (dot + gap) + dot / 2
                c.drawCircle(cx, cy, dot / 2, fill)
            }
            val rows = ceil(total / cols.toFloat()).toInt()
            yearFooter(x, w, gy + rows * (dot + gap) + 12f, pct, total - passed)
        }

        fun yearBar(snap: Snapshot, x: Float, y: Float, w: Float, h: Float) {
            card(x, y, w, h)
            val (total, passed, pct) = yearInfo(snap.today)
            yearHeader(snap, x, y, w, pct)
            val by = y + 14f + 22f + 12f
            fill.color = FAINT
            c.drawRoundRect(RectF(x + 14f, by, x + w - 14f, by + 8f), 4f, 4f, fill)
            fill.color = TEXT
            c.drawRoundRect(RectF(x + 14f, by, x + 14f + (w - 28f) * passed / total, by + 8f), 4f, 4f, fill)
            yearFooter(x, w, by + 8f + 17f, pct, total - passed)
        }

        private fun chip(item: PlanItem, x: Float, y: Float, w: Float, today: LocalDate) {
            val color = item.color
            fill.color = (color and 0x00FFFFFF) or 0x55000000
            c.drawRoundRect(RectF(x, y, x + w, y + 13f), 3f, 3f, fill)
            fill.color = color
            c.drawRoundRect(RectF(x, y, x + 2.5f, y + 13f), 1.2f, 1.2f, fill)
            val label = if (!item.isEvent) "○ ${item.title}" else item.title
            text(label, x + 5f, y + 6.5f, tp(8.5f, TEXT), maxW = w - 7f)
        }

        fun week(snap: Snapshot, x: Float, y: Float, w: Float, h: Float, lanes: Int) {
            card(x, y, w, h)
            val start = K.startOfWeek(snap.today)
            val colW = (w - 16f) / 7
            for (i in 0..6) {
                val day = start.plusDays(i.toLong())
                val cx = x + 8f + colW * i + colW / 2
                text(K.weekdays[i], cx, y + 12f + 6f, tp(9.5f, SECONDARY), align = Paint.Align.CENTER)
                val dy = y + 12f + 12f + 5f + 12f
                if (day == snap.today) {
                    fill.color = TEXT
                    c.drawCircle(cx, dy, 12f, fill)
                    text("${day.dayOfMonth}", cx, dy, tp(14.5f, 0xFF111111.toInt(), true), align = Paint.Align.CENTER)
                } else {
                    text("${day.dayOfMonth}", cx, dy, tp(14.5f, TEXT, true), align = Paint.Align.CENTER)
                }
                val items = snap.dayItems(day).filter { it.isEvent || !it.done }
                var cy = y + 12f + 12f + 5f + 24f + 6f
                for (it in items.take(lanes)) {
                    chip(it, x + 8f + colW * i + 1.5f, cy, colW - 3f, snap.today)
                    cy += 15f
                }
                if (items.size > lanes) {
                    text("+${items.size - lanes}", cx, cy + 5f, tp(8f, SECONDARY), align = Paint.Align.CENTER)
                }
            }
        }

        fun matrix(snap: Snapshot, x: Float, y: Float, w: Float, rows: Int) {
            val cw = (w - GAP) / 2
            val ch = matrixCardH(rows)
            for (i in 0..3) {
                val cx = x + (i % 2) * (cw + GAP)
                val cy = y + (i / 2) * (ch + GAP)
                card(cx, cy, cw, ch)
                val box = snap.boxes.getOrNull(i) ?: continue
                val items = snap.boxItems(i)
                val hy = cy + 12f + 12f
                fill.color = box.color
                c.drawCircle(cx + 14f + 4f, hy, 4f, fill)
                text(box.name, cx + 14f + 13f, hy, tp(13.5f, box.color, true), maxW = cw - 14f - 13f - 34f)
                fill.color = box.color
                c.drawCircle(cx + cw - 14f - 10f, hy, 10f, fill)
                text("${items.size}", cx + cw - 14f - 10f, hy, tp(11f, TEXT, true), align = Paint.Align.CENTER)
                var ry = cy + 12f + 24f + 8f + 10.5f
                for (it in items.take(rows)) {
                    stroke.color = box.color
                    stroke.strokeWidth = 1.5f
                    c.drawCircle(cx + 14f + 5.5f, ry, 5.5f, stroke)
                    val due = it.dueLabel(snap.today)
                    val dueP = tp(10.5f, box.color, true)
                    val dueW = if (due.isEmpty()) 0f else width(due, dueP) + 6f
                    text(it.title, cx + 14f + 17f, ry, tp(12.5f, TEXT), maxW = cw - 14f - 17f - 12f - dueW)
                    if (due.isNotEmpty()) text(due, cx + cw - 12f, ry, dueP, align = Paint.Align.RIGHT)
                    ry += 21f
                }
                if (items.size > rows) text("+${items.size - rows}개 더", cx + 14f, ry - 2f, tp(10.5f, SECONDARY))
            }
        }

        fun month(snap: Snapshot, x: Float, y: Float, w: Float, h: Float, lanes: Int) {
            card(x, y, w, h)
            val first = snap.today.withDayOfMonth(1)
            text(K.fmt("M월", first), x + 14f, y + 14f + 11f, tp(16f, TEXT, true))
            val colW = (w - 16f) / 7
            for (i in 0..6) {
                text(K.weekdays[i], x + 8f + colW * i + colW / 2, y + 14f + 22f + 8f, tp(9.5f, SECONDARY), align = Paint.Align.CENTER)
            }
            val start = K.startOfWeek(first)
            val weeks = run {
                val last = first.plusMonths(1).minusDays(1)
                (ChronoUnit.DAYS.between(start, last) / 7 + 1).toInt()
            }
            val rowH = 24f + lanes * 14f
            for (wk in 0 until weeks) for (i in 0..6) {
                val day = start.plusDays((wk * 7 + i).toLong())
                val cx = x + 8f + colW * i + colW / 2
                val top = y + 14f + 22f + 18f + wk * rowH
                val inMonth = day.month == first.month
                val numColor = if (inMonth) TEXT else SECONDARY
                if (day == snap.today) {
                    fill.color = TEXT
                    c.drawCircle(cx, top + 10f, 10f, fill)
                    text("${day.dayOfMonth}", cx, top + 10f, tp(12f, 0xFF111111.toInt(), true), align = Paint.Align.CENTER)
                } else {
                    text("${day.dayOfMonth}", cx, top + 10f, tp(12f, numColor, true), align = Paint.Align.CENTER)
                }
                if (!inMonth) continue
                val items = snap.dayItems(day).filter { it.isEvent || !it.done }
                var cy = top + 22f
                for (it in items.take(lanes)) {
                    chip(it, x + 8f + colW * i + 1.5f, cy, colW - 3f, snap.today)
                    cy += 14f
                }
                if (items.size > lanes) {
                    fill.color = SECONDARY
                    c.drawCircle(cx + colW / 2 - 5f, top + 4f, 2f, fill)
                }
            }
        }

        private fun marker(item: PlanItem, mx: Float, cy: Float) {
            if (item.isEvent) {
                fill.color = item.color
                c.drawRoundRect(RectF(mx - 1.5f, cy - 8f, mx + 1.5f, cy + 8f), 1.5f, 1.5f, fill)
            } else if (item.done) {
                fill.color = item.color
                c.drawCircle(mx, cy, 6.5f, fill)
            } else {
                stroke.color = item.color
                stroke.strokeWidth = 1.6f
                c.drawCircle(mx, cy, 6f, stroke)
            }
        }

        private fun header(title: String, badge: String, x: Float, y: Float, w: Float) {
            text(title, x + 14f, y + 14f + 11f, tp(14f, TEXT, true))
            val bp = tp(11.5f, 0xFF111111.toInt(), true)
            val bw = width(badge, bp) + 16f
            fill.color = TEXT
            c.drawRoundRect(RectF(x + w - 14f - bw, y + 14f + 1f, x + w - 14f, y + 14f + 21f), 10f, 10f, fill)
            text(badge, x + w - 14f - bw / 2, y + 14f + 11f, bp, align = Paint.Align.CENTER)
        }

        fun timeline(snap: Snapshot, x: Float, y: Float, w: Float, h: Float, rows: Int) {
            card(x, y, w, h)
            val (top, bottom, hidden) = timelineSplit(snap, rows)
            header(K.fmt("M월 d일 EEEE", snap.today), "${snap.todayRows().size + snap.undatedTodos.size}", x, y, w)
            var ry = y + 14f + 24f + 11.5f
            val labelW = 52f
            if (top.isEmpty() && bottom.isEmpty()) {
                text("오늘 일정과 할 일이 없어요", x + 14f, ry + 4f, tp(12.5f, SECONDARY))
            }
            for (row in top) {
                text(row.label, x + 14f + labelW, ry, tp(9.5f, SECONDARY, true), align = Paint.Align.RIGHT)
                marker(row.item, x + 14f + labelW + 12f, ry)
                val right = rightLabel(row.item)
                val rp = tp(9.5f, row.item.color, true)
                val rw = if (right == null) 0f else width(right, rp) + 6f
                text(row.item.title, x + 14f + labelW + 24f, ry, tp(13f, if (row.item.done) SECONDARY else TEXT),
                    maxW = w - 14f - labelW - 24f - 14f - rw)
                if (right != null) text(right, x + w - 14f, ry, rp, align = Paint.Align.RIGHT)
                ry += 23f
            }
            if (bottom.isNotEmpty()) {
                fill.color = 0x26FFFFFF
                c.drawRect(x + 14f, ry - 8f, x + w - 14f, ry - 7.5f, fill)
                ry += 10f
                for (it in bottom) {
                    marker(it, x + 14f + labelW + 12f, ry)
                    text(it.title, x + 14f + labelW + 24f, ry, tp(13f, TEXT), maxW = w - 14f - labelW - 24f - 14f)
                    ry += 23f
                }
            }
            if (hidden > 0) text("+${hidden}개 더", x + 14f + labelW + 24f, ry - 2f, tp(10.5f, SECONDARY))
        }

        private fun rightLabel(item: PlanItem): String? {
            if (!item.isEvent) return null
            val s = item.start ?: return null
            val e = item.end ?: return null
            if (item.allDay) {
                val days = ChronoUnit.DAYS.between(s.toLocalDate(), e.toLocalDate())
                return if (days <= 1) "하루 종일" else "${s.monthValue}/${s.dayOfMonth} → ${e.minusDays(1).monthValue}/${e.minusDays(1).dayOfMonth}"
            }
            return "${K.time(s)}–${K.time(e)}"
        }

        fun checklist(snap: Snapshot, x: Float, y: Float, w: Float, h: Float, rows: Int) {
            card(x, y, w, h)
            val list = snap.checklist
            header("할 일", "${list.size}개 남음", x, y, w)
            text(K.fmt("M월 d일 EEEE", snap.today), x + 14f, y + 14f + 20f + 9f, tp(10.5f, SECONDARY))
            var ry = y + 14f + 20f + 16f + 8f + 11.5f
            if (list.isEmpty()) text("오늘 할 일이 없어요", x + 14f, ry + 4f, tp(12.5f, SECONDARY))
            for (it in list.take(rows)) {
                stroke.color = it.color
                stroke.strokeWidth = 1.6f
                c.drawRoundRect(RectF(x + 14f, ry - 6.5f, x + 27f, ry + 6.5f), 3f, 3f, stroke)
                val due = it.dueLabel(snap.today)
                val dp = tp(10.5f, it.color, true)
                val dw = if (due.isEmpty()) 0f else width(due, dp) + 6f
                text(it.title, x + 36f, ry, tp(13f, TEXT), maxW = w - 36f - 14f - dw)
                if (due.isNotEmpty()) text(due, x + w - 14f, ry, dp, align = Paint.Align.RIGHT)
                ry += 23f
            }
            if (list.size > rows) text("+${list.size - rows}개 더", x + 14f, ry - 2f, tp(10.5f, SECONDARY))
        }
    }
}
