package uk.bw86.nscgschedule.schedule

import android.util.Log
import uk.bw86.nscgschedule.data.models.DaySchedule
import uk.bw86.nscgschedule.data.models.Exam
import uk.bw86.nscgschedule.data.models.ExamTimetable
import uk.bw86.nscgschedule.data.models.Lesson
import uk.bw86.nscgschedule.data.models.Timetable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Shared schedule merge logic for WearOS Tile + Complication.
 *
 * Rules:
 * - Lessons and exams are ordered by date+time.
 * - If intervals overlap, exams win.
 * - When an item ends, we show the next item (we do NOT keep showing the previous one during gaps).
 */
object ScheduleMerger {

    private const val TAG = "ScheduleMerger"

    enum class Kind { LESSON, EXAM }

    data class Event(
        val kind: Kind,
        val start: Instant,
        val end: Instant,
        val lesson: Lesson? = null,
        val exam: Exam? = null,
        val sourceLabel: String
    ) {
        val startMillis: Long get() = start.toEpochMilli()
        val endMillis: Long get() = end.toEpochMilli()
        val priority: Int get() = if (kind == Kind.EXAM) 2 else 1

        fun isActiveAt(epochMillis: Long): Boolean = startMillis <= epochMillis && epochMillis < endMillis

        override fun toString(): String {
            val what = when (kind) {
                Kind.LESSON -> "lesson='${lesson?.name}' room='${lesson?.room}'"
                Kind.EXAM -> "exam='${exam?.subjectDescription}' room='${exam?.examRoom}'"
            }
            return "Event(kind=$kind, start=$startMillis, end=$endMillis, $what, src='$sourceLabel')"
        }
    }

    data class Pick(val current: Event?, val next: Event?)

    data class DisplaySegment(
        val startMillis: Long,
        val endMillis: Long,
        val display: Event?,
        val next: Event?,
        val isDisplayActive: Boolean
    )

    /**
     * Build a unified list of events from timetable + exam timetable.
     *
     * Notes:
     * - Lessons get their date from `DaySchedule.day` if it includes one (e.g. "Monday 09/12/2025");
     *   otherwise we fall back to the next occurrence of the weekday.
     * - Exams include BOTH upcoming and currently-active entries (not just `getUpcomingExams()`).
     */
    fun buildEvents(
        timetable: Timetable?,
        examTimetable: ExamTimetable?,
        nowMillis: Long,
        zoneId: ZoneId,
        horizonDays: Long = 14
    ): List<Event> {
        val events = mutableListOf<Event>()

        val horizonEnd = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate().plusDays(horizonDays)

        // Lessons
        timetable?.days?.forEach { daySchedule ->
            val date = parseDayScheduleDate(daySchedule, zoneId) ?: inferDateFromDayName(daySchedule, nowMillis, zoneId)
            if (date == null) {
                Log.d(TAG, "Skipping day (no date): day='${daySchedule.day}'")
                return@forEach
            }
            if (date.isAfter(horizonEnd)) return@forEach

            daySchedule.lessons.forEach { lesson ->
                val s = lesson.getParsedStartTime()
                val e = lesson.getParsedEndTime()
                if (s == null || e == null) {
                    Log.d(TAG, "Lesson time parse failed: name='${lesson.name}' rawStart='${lesson.startTime}' rawEnd='${lesson.endTime}' day='${daySchedule.day}'")
                    return@forEach
                }
                if (!e.isAfter(s)) {
                    Log.d(TAG, "Lesson invalid interval (end<=start): name='${lesson.name}' start=$s end=$e day='$date'")
                    return@forEach
                }

                val start = LocalDateTime.of(date, s).atZone(zoneId).toInstant()
                val end = LocalDateTime.of(date, e).atZone(zoneId).toInstant()
                // Keep a little history so current lessons still resolve; otherwise only keep relevant future.
                if (end.toEpochMilli() < nowMillis - 6 * 60_000L) return@forEach

                events.add(
                    Event(
                        kind = Kind.LESSON,
                        start = start,
                        end = end,
                        lesson = lesson,
                        sourceLabel = "lesson:${daySchedule.day}"
                    )
                )
            }
        }

        // Exams
        examTimetable?.exams?.forEach { exam ->
            val d = exam.getParsedDate()
            val s = exam.getParsedStartTime()
            val e = exam.getParsedFinishTime()
            if (d == null || s == null || e == null) {
                Log.d(TAG, "Exam parse failed: subj='${exam.subjectDescription}' rawDate='${exam.date}' rawStart='${exam.startTime}' rawFinish='${exam.finishTime}'")
                return@forEach
            }
            if (d.isAfter(horizonEnd)) return@forEach
            if (!e.isAfter(s)) {
                Log.d(TAG, "Exam invalid interval (finish<=start): subj='${exam.subjectDescription}' start=$s finish=$e date='$d'")
                return@forEach
            }

            val start = LocalDateTime.of(d, s).atZone(zoneId).toInstant()
            val end = LocalDateTime.of(d, e).atZone(zoneId).toInstant()
            // Include active + near-future; skip old.
            if (end.toEpochMilli() < nowMillis - 6 * 60_000L) return@forEach

            events.add(
                Event(
                    kind = Kind.EXAM,
                    start = start,
                    end = end,
                    exam = exam,
                    sourceLabel = "exam:${exam.date}"
                )
            )
        }

        val sorted = sortEvents(events)
        Log.d(TAG, "Built events: count=${sorted.size}, nowMillis=$nowMillis")
        sorted.take(12).forEachIndexed { idx, ev ->
            Log.d(TAG, "Event[$idx] $ev")
        }

        return sorted
    }

    /** Returns (currentActive, nextAfterNowOrAfterCurrentEnd depending on context). */
    fun pick(events: List<Event>, nowMillis: Long): Pick {
        val current = pickCurrent(events, nowMillis)
        val next = if (current != null) {
            // "next" means the next item that starts at/after current ends.
            pickNextAfter(events, current.endMillis)
        } else {
            pickNextAfter(events, nowMillis)
        }
        Log.d(TAG, "Pick: now=$nowMillis current=${current?.kind} next=${next?.kind}")
        return Pick(current = current, next = next)
    }

    /**
     * Build time segments where the *displayed* item (current if active else next) is stable.
     * This makes tiles switch immediately at end-times (showing the next item during gaps).
     */
    fun buildDisplaySegments(
        events: List<Event>,
        windowStartMillis: Long,
        windowEndMillis: Long
    ): List<DisplaySegment> {
        if (windowEndMillis <= windowStartMillis) return emptyList()

        // Use a list so we do not deduplicate identical boundary points here.
        // Deduplication of time points previously removed equal timestamps
        // via `distinct()`; keep all insertions so downstream logic can
        // reason about identical boundaries if necessary.
        val bounds = mutableListOf<Long>()
        bounds.add(windowStartMillis)
        bounds.add(windowEndMillis)

        events.forEach { ev ->
            val s = ev.startMillis
            val e = ev.endMillis
            if (s in windowStartMillis..windowEndMillis) bounds.add(s)
            if (e in windowStartMillis..windowEndMillis) bounds.add(e)
        }

        // Preserve potential duplicate boundary entries; sort to get ordered points.
        val points = bounds.sorted()
        if (points.size < 2) return emptyList()

        val segments = mutableListOf<DisplaySegment>()

        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            if (a >= b) continue

            val current = pickCurrent(events, a)
            val display = current ?: pickNextAfter(events, a)
            val isDisplayActive = display?.isActiveAt(a) == true

            val next = if (display != null) {
                pickNextAfter(events, display.endMillis)
            } else {
                null
            }

            segments.add(
                DisplaySegment(
                    startMillis = a,
                    endMillis = b,
                    display = display,
                    next = next,
                    isDisplayActive = isDisplayActive
                )
            )
        }

        // Collapse adjacent segments with identical display+next+active flag.
        val collapsed = mutableListOf<DisplaySegment>()
        for (seg in segments) {
            val last = collapsed.lastOrNull()
            if (last != null &&
                last.display == seg.display &&
                last.next == seg.next &&
                last.isDisplayActive == seg.isDisplayActive &&
                last.endMillis == seg.startMillis
            ) {
                collapsed[collapsed.size - 1] = last.copy(endMillis = seg.endMillis)
            } else {
                collapsed.add(seg)
            }
        }

        Log.d(TAG, "DisplaySegments: count=${collapsed.size}, window=[$windowStartMillis,$windowEndMillis)")
        collapsed.take(12).forEachIndexed { idx, seg ->
            Log.d(TAG, "Seg[$idx] [${seg.startMillis},${seg.endMillis}) display=${seg.display?.kind} active=${seg.isDisplayActive} next=${seg.next?.kind}")
        }

        return collapsed
    }

    private fun sortEvents(events: List<Event>): List<Event> {
        return events.sortedWith(
            compareBy<Event> { it.startMillis }
                .thenByDescending { it.priority }
                .thenBy { it.endMillis }
        )
    }

    private fun pickCurrent(events: List<Event>, nowMillis: Long): Event? {
        // Choose the highest-priority active event; tie-breaker earliest start.
        val active = events.filter { it.isActiveAt(nowMillis) }
        if (active.isEmpty()) return null
        return active.sortedWith(
            compareByDescending<Event> { it.priority }
                .thenBy { it.startMillis }
                .thenBy { it.endMillis }
        ).firstOrNull()
    }

    private fun pickNextAfter(events: List<Event>, thresholdMillis: Long): Event? {
        // Next event that starts at/after the threshold.
        return events
            .filter { it.startMillis >= thresholdMillis }
            .sortedWith(
                compareBy<Event> { it.startMillis }
                    .thenByDescending { it.priority }
                    .thenBy { it.endMillis }
            )
            .firstOrNull()
    }

    private fun parseDayScheduleDate(daySchedule: DaySchedule, zoneId: ZoneId): LocalDate? {
        val raw = daySchedule.day.trim()
        if (raw.isBlank()) return null

        // Common format observed: "Monday 09/12/2025"
        // We'll attempt to parse the tail as a date.
        val parts = raw.split(' ').filter { it.isNotBlank() }
        val datePart = parts.lastOrNull() ?: return null

        val patterns = listOf("d/M/uuuu", "dd/MM/uuuu", "d/MM/uuuu", "dd/M/uuuu")
        for (pattern in patterns) {
            try {
                val formatter = DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)
                val parsed = LocalDate.parse(datePart, formatter)
                Log.d(TAG, "Parsed DaySchedule date: day='${daySchedule.day}' datePart='$datePart' pattern='$pattern' -> $parsed")
                return parsed
            } catch (_: DateTimeParseException) {
                // continue
            } catch (e: Exception) {
                Log.d(TAG, "Unexpected error parsing DaySchedule date: day='${daySchedule.day}' pattern='$pattern' err='${e.message}'")
            }
        }

        Log.d(TAG, "Couldn't parse DaySchedule date: day='${daySchedule.day}' datePart='$datePart' zone='$zoneId'")
        return null
    }

    private fun inferDateFromDayName(daySchedule: DaySchedule, nowMillis: Long, zoneId: ZoneId): LocalDate? {
        // Fallback: find next occurrence of the weekday mentioned in day string.
        val raw = daySchedule.day.lowercase(Locale.ENGLISH)
        val targetDow = when {
            raw.contains("monday") -> DayOfWeek.MONDAY
            raw.contains("tuesday") -> DayOfWeek.TUESDAY
            raw.contains("wednesday") -> DayOfWeek.WEDNESDAY
            raw.contains("thursday") -> DayOfWeek.THURSDAY
            raw.contains("friday") -> DayOfWeek.FRIDAY
            raw.contains("saturday") -> DayOfWeek.SATURDAY
            raw.contains("sunday") -> DayOfWeek.SUNDAY
            else -> null
        } ?: return null

        val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        var d = today
        for (i in 0..7) {
            if (d.dayOfWeek == targetDow) {
                Log.d(TAG, "Inferred DaySchedule date: day='${daySchedule.day}' -> $d")
                return d
            }
            d = d.plusDays(1)
        }

        return null
    }
}
