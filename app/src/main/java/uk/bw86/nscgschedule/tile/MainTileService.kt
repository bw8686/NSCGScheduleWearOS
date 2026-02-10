package uk.bw86.nscgschedule.tile


import android.content.Context

import android.util.Log

import androidx.wear.protolayout.ActionBuilders

import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders

import androidx.wear.protolayout.DimensionBuilders.dp

import androidx.wear.protolayout.DimensionBuilders.expand

import androidx.wear.protolayout.DimensionBuilders.wrap

import androidx.wear.protolayout.LayoutElementBuilders

import androidx.wear.protolayout.LayoutElementBuilders.Box

import androidx.wear.protolayout.LayoutElementBuilders.Column

import androidx.wear.protolayout.LayoutElementBuilders.Row

import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.TEXT_ALIGN_CENTER

import androidx.wear.protolayout.ModifiersBuilders

import androidx.wear.protolayout.ResourceBuilders

import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.layouts.PrimaryLayout

import androidx.wear.protolayout.material3.ButtonColors
import androidx.wear.protolayout.material3.CardDefaults

import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.card

import androidx.wear.protolayout.material3.materialScope
import androidx.wear.protolayout.material3.primaryLayout

import androidx.wear.protolayout.material3.text

import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.modifiers.LayoutModifier

import androidx.wear.protolayout.types.LayoutColor

import androidx.wear.protolayout.types.layoutString

import androidx.wear.tiles.RequestBuilders

import androidx.wear.tiles.TileBuilders

import com.google.android.horologist.annotations.ExperimentalHorologistApi

import com.google.android.horologist.tiles.SuspendingTileService

import uk.bw86.nscgschedule.data.DataRepository

import uk.bw86.nscgschedule.data.models.DaySchedule

import uk.bw86.nscgschedule.data.models.Lesson

import uk.bw86.nscgschedule.data.models.Exam

import uk.bw86.nscgschedule.presentation.MainActivity

import uk.bw86.nscgschedule.schedule.ScheduleMerger

import java.time.LocalDate

import java.time.LocalTime

import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

import java.time.temporal.ChronoUnit


private const val RESOURCES_VERSION = "1"

// Minimum width (dp) required to show the title. On smaller screens hide title for a cleaner layout.
private const val TITLE_MIN_SCREEN_WIDTH_DP = 180

private fun isLargeScreen(
    deviceParameters: androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
): Boolean {
    // Mirror the simple behavior of the example's isLargeScreen helper:
    // treat screens >= threshold as large.
    return deviceParameters.screenWidthDp >= TITLE_MIN_SCREEN_WIDTH_DP
}


// Sealed class to represent both lessons and exams in a unified way
private sealed class ScheduleItem {
    abstract val startTime: LocalTime?
    abstract val endTime: LocalTime?
    abstract val kind: ScheduleMerger.Kind
    abstract val openStartMillis: Long?
    abstract val openEndMillis: Long?
    
    data class LessonItem(
        val lesson: Lesson,
        override val openStartMillis: Long? = null,
        override val openEndMillis: Long? = null
    ) : ScheduleItem() {
        override val kind: ScheduleMerger.Kind = ScheduleMerger.Kind.LESSON
        override val startTime: LocalTime? = lesson.getParsedStartTime()
        override val endTime: LocalTime? = lesson.getParsedEndTime()
    }
    
    data class ExamItem(
        val exam: Exam,
        override val openStartMillis: Long? = null,
        override val openEndMillis: Long? = null
    ) : ScheduleItem() {
        override val kind: ScheduleMerger.Kind = ScheduleMerger.Kind.EXAM
        override val startTime: LocalTime? = exam.getParsedStartTime()
        override val endTime: LocalTime? = try {
            LocalTime.parse(exam.finishTime, DateTimeFormatter.ofPattern("HH:mm"))
        } catch (e: Exception) {
            null
        }
    }
}


// M3 Expressive Colors

private const val COLOR_PRIMARY = 0xFFFF5722.toInt()

private const val COLOR_ON_PRIMARY = 0xFFFFFFFF.toInt()

private const val COLOR_SURFACE_CONTAINER = 0xFF2B2930.toInt()

private const val COLOR_ON_SURFACE = 0xFFE6E1E5.toInt()

private const val COLOR_SECONDARY = 0xFFFF6E40.toInt()

private const val COLOR_SECONDARY_DIM = 0xFFFF3D00.toInt()


@OptIn(ExperimentalHorologistApi::class)


class MainTileService : SuspendingTileService() {


    private val repository: DataRepository by lazy {

        DataRepository.getInstance(applicationContext)

    }


    override suspend fun resourcesRequest(

        requestParams: RequestBuilders.ResourcesRequest

    ) = ResourceBuilders.Resources.Builder()

        .setVersion(RESOURCES_VERSION)

        .build()


    override suspend fun tileRequest(

        requestParams: RequestBuilders.TileRequest

    ) = tile(requestParams, this, repository)

}


private fun tile(
    requestParams: RequestBuilders.TileRequest,
    context: Context,
    repository: DataRepository
): TileBuilders.Tile {
    val timetable = repository.getCurrentTimetable()
    val examTimetable = repository.getCurrentExamTimetable()
    val today = LocalDate.now()
    val zone = java.time.ZoneId.systemDefault()
    val nowMillis = java.time.Instant.now().toEpochMilli()

    val hasTimetableData = timetable != null && timetable.days.isNotEmpty()
    val hasExamData = examTimetable != null && examTimetable.exams.isNotEmpty()

    val timelineBuilder = TimelineBuilders.Timeline.Builder()
    if (!hasTimetableData && !hasExamData) {
        // Fallback: no data
        timelineBuilder.addTimelineEntry(
            TimelineBuilders.TimelineEntry.Builder()
                .setLayout(LayoutElementBuilders.Layout.Builder().setRoot(noDataMaterialLayout(requestParams, context)).build())
                .build()
        )
    } else {
        val dayStartMillis = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEndMillis = today.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli() // cover today + tomorrow
        // A strict end boundary for "today" only (used for checking "no lessons today")
        val todayOnlyEndMillis = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val events = ScheduleMerger.buildEvents(
            timetable = timetable,
            examTimetable = examTimetable,
            nowMillis = nowMillis,
            zoneId = zone,
            horizonDays = 14
        )

        // Debug: log event list and day boundaries to diagnose "No lessons today!" always showing
        try {
            Log.d(TAG, "Events total=${events.size}")
            val dayStartReadable = java.time.Instant.ofEpochMilli(dayStartMillis).atZone(zone)
            val todayOnlyEndReadable = java.time.Instant.ofEpochMilli(todayOnlyEndMillis).atZone(zone)
            Log.d(TAG, "Day bounds: start=$dayStartReadable endExclusive=$todayOnlyEndReadable now=${java.time.Instant.ofEpochMilli(nowMillis).atZone(zone)}")
            events.sortedBy { it.startMillis }.forEachIndexed { idx, ev ->
                val start = java.time.Instant.ofEpochMilli(ev.startMillis).atZone(zone)
                val end = java.time.Instant.ofEpochMilli(ev.endMillis).atZone(zone)
                Log.d(TAG, "Event[$idx]: kind=${ev.kind} start=$start end=$end lesson=${ev.lesson?.name ?: "-"} exam=${ev.exam?.subjectDescription ?: "-"}")
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to log events: ${e.message}")
        }

        // Build explicit timeline entries per-event so we have deterministic
        // coverage at both the start and end of each lesson/exam. For each
        // event add an entry valid for [startMillis, endMillis) and, if there
        // is a gap to the next event, an entry covering that gap to display
        // a "done/next" state until the next event.
        if (events.isEmpty()) {
            timelineBuilder.addTimelineEntry(
                TimelineBuilders.TimelineEntry.Builder()
                    .setLayout(LayoutElementBuilders.Layout.Builder().setRoot(noDataMaterialLayout(requestParams, context)).build())
                    .build()
            )
        } else {
            fun toScheduleItem(ev: ScheduleMerger.Event): ScheduleItem {
                return when (ev.kind) {
                    ScheduleMerger.Kind.EXAM -> ScheduleItem.ExamItem(
                        exam = ev.exam!!,
                        openStartMillis = ev.startMillis,
                        openEndMillis = ev.endMillis
                    )
                    ScheduleMerger.Kind.LESSON -> ScheduleItem.LessonItem(
                        lesson = ev.lesson!!,
                        openStartMillis = ev.startMillis,
                        openEndMillis = ev.endMillis
                    )
                }
            }

            val sorted = events.sortedBy { it.startMillis }

            // If the first event is in the future, add a pre-gap entry that
            // covers 'now' up until that first event start. This ensures the
            // Tile has at least one timeline entry valid for the current time
            // (avoids the system warning about "Out of valid timelines").
            val firstEv = sorted.first()
            if (firstEv.startMillis > nowMillis) {
                val todayDate = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()

                // If the first event is later TODAY, show it (and the following
                // event if present) instead of a placeholder message.
                val firstEvDate = java.time.Instant.ofEpochMilli(firstEv.startMillis).atZone(zone).toLocalDate()
                if (firstEvDate == todayDate) {
                    val secondEv = sorted.getOrNull(1)
                    val preGapRoot = scheduleMaterialLayout(
                        context = context,
                        deviceParameters = requestParams.deviceConfiguration!!,
                        currentItem = toScheduleItem(firstEv),
                        nextItem = secondEv?.let { toScheduleItem(it) },
                        isCurrentActive = false
                    )

                    val preGapLayout = LayoutElementBuilders.Layout.Builder().setRoot(preGapRoot).build()
                    val preGapEntry = TimelineBuilders.TimelineEntry.Builder().setLayout(preGapLayout)
                    preGapEntry.setValidity(
                        TimelineBuilders.TimeInterval.Builder()
                            .setStartMillis(nowMillis)
                            .setEndMillis(firstEv.startMillis)
                            .build()
                    )
                    timelineBuilder.addTimelineEntry(preGapEntry.build())
                } else {
                    val lessonsToday = events.filter { it.kind == ScheduleMerger.Kind.LESSON && it.lesson != null }
                        .filter { java.time.Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate() == todayDate }

                    // Debug: list which events were considered "today"
                    try {
                        Log.d(TAG, "lessonsToday filtered count=${lessonsToday.size}")
                        lessonsToday.forEachIndexed { idx, ev ->
                            val startLocal = java.time.Instant.ofEpochMilli(ev.startMillis).atZone(zone).toLocalDate()
                            val startZ = java.time.Instant.ofEpochMilli(ev.startMillis).atZone(zone)
                            Log.d(TAG, "lessonsToday[$idx]: kind=${ev.kind} startLocal=$startLocal startZ=$startZ startMillis=${ev.startMillis} name=${ev.lesson?.name ?: "-"}")
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Failed to log lessonsToday: ${e.message}")
                    }

                    Log.d(TAG, "lessonsToday count=${lessonsToday.size} (pre-gap) todayStart=$dayStartMillis todayEnd=$todayOnlyEndMillis now=$nowMillis nextEvStart=${firstEv.startMillis}")

                    val scheduledTodayInfo = countScheduledLessonsAndMaxEndMillis(timetable, todayDate, zone)
                    Log.d(TAG, "scheduledToday count=${scheduledTodayInfo.first} maxEnd=${scheduledTodayInfo.second}")

                    val message = when {
                        scheduledTodayInfo.first == 0 -> "No lessons today!"
                        scheduledTodayInfo.second != null && nowMillis > scheduledTodayInfo.second!! -> "Done for today!"
                        else -> "You're done for now"
                    }

                    val currentPlaceholder = ScheduleItem.LessonItem(
                        Lesson(
                            name = message,
                            startTime = "",
                            endTime = "",
                            room = "",
                            teachers = emptyList(),
                            course = "",
                            group = ""
                        ),
                        openStartMillis = null,
                        openEndMillis = null
                    )

                    // When there are no lessons today we still want to show the next
                    // lesson (on a future day). Show the next card and label it with
                    // the day of the next event.
                    val showNextCard = true
                    val nextDay = java.time.Instant.ofEpochMilli(firstEv.startMillis)
                        .atZone(zone)
                        .dayOfWeek
                        .getDisplayName(TextStyle.FULL, Locale.getDefault())
                    val nextLabelText = if (firstEv.kind == ScheduleMerger.Kind.LESSON) "Next lesson: $nextDay" else "Next: $nextDay"

                    val preGapRoot = scheduleMaterialLayout(
                        context = context,
                        deviceParameters = requestParams.deviceConfiguration!!,
                        currentItem = currentPlaceholder,
                        nextItem = if (showNextCard) toScheduleItem(firstEv) else null,
                        isCurrentActive = false,
                        nextLabel = if (showNextCard) nextLabelText else null
                    )

                    val preGapLayout = LayoutElementBuilders.Layout.Builder().setRoot(preGapRoot).build()
                    val preGapEntry = TimelineBuilders.TimelineEntry.Builder().setLayout(preGapLayout)
                    preGapEntry.setValidity(
                        TimelineBuilders.TimeInterval.Builder()
                            .setStartMillis(nowMillis)
                            .setEndMillis(firstEv.startMillis)
                            .build()
                    )
                    timelineBuilder.addTimelineEntry(preGapEntry.build())
                }
            }

            for (i in sorted.indices) {
                val ev = sorted[i]
                val nextEv = sorted.getOrNull(i + 1)

                // Entry for  the event duration
                val eventRoot = scheduleMaterialLayout(
                    context = context,
                    deviceParameters = requestParams.deviceConfiguration!!,
                    currentItem = toScheduleItem(ev),
                    nextItem = nextEv?.let { toScheduleItem(it) },
                    isCurrentActive = nowMillis in ev.startMillis until ev.endMillis
                )

                val eventLayout = LayoutElementBuilders.Layout.Builder().setRoot(eventRoot).build()
                val eventEntry = TimelineBuilders.TimelineEntry.Builder().setLayout(eventLayout)
                eventEntry.setValidity(
                    TimelineBuilders.TimeInterval.Builder()
                        .setStartMillis(ev.startMillis)
                        .setEndMillis(ev.endMillis)
                        .build()
                )
                timelineBuilder.addTimelineEntry(eventEntry.build())

                // Entry for gap after event until next start (or day end)
                val gapStart = ev.endMillis
                val gapEnd = nextEv?.startMillis ?: dayEndMillis
                if (gapStart < gapEnd) {
                    val gapRoot = if (nextEv != null) {
                            // If the next event is later the SAME DAY, show the upcoming
                            // lesson(s) instead of a placeholder message during the gap.
                            val nextEvDate = java.time.Instant.ofEpochMilli(nextEv.startMillis).atZone(zone).toLocalDate()
                            val todayDate = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()

                            if (nextEvDate == todayDate) {
                                // Show the next event as the current card, and the following
                                // event (if any) as the next card.
                                val followingEv = sorted.getOrNull(i + 2)
                                scheduleMaterialLayout(
                                    context = context,
                                    deviceParameters = requestParams.deviceConfiguration!!,
                                    currentItem = toScheduleItem(nextEv),
                                    nextItem = followingEv?.let { toScheduleItem(it) },
                                    isCurrentActive = false
                                )
                            } else {
                                // Build a dynamic placeholder message depending on lessons for today
                                val lessonsToday = events.filter { it.kind == ScheduleMerger.Kind.LESSON && it.lesson != null }
                                    .filter { java.time.Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate() == todayDate }

                                // Debug: list which events were considered "today" for this gap
                                try {
                                    Log.d(TAG, "lessonsToday filtered count=${lessonsToday.size} (gap)")
                                    lessonsToday.forEachIndexed { idx, ev ->
                                        val startLocal = java.time.Instant.ofEpochMilli(ev.startMillis).atZone(zone).toLocalDate()
                                        val startZ = java.time.Instant.ofEpochMilli(ev.startMillis).atZone(zone)
                                        Log.d(TAG, "lessonsToday(gap)[$idx]: kind=${ev.kind} startLocal=$startLocal startZ=$startZ startMillis=${ev.startMillis} name=${ev.lesson?.name ?: "-"}")
                                    }
                                } catch (e: Exception) {
                                    Log.d(TAG, "Failed to log lessonsToday (gap): ${e.message}")
                                }

                                Log.d(TAG, "lessonsToday count=${lessonsToday.size} todayStart=$dayStartMillis todayEnd=$todayOnlyEndMillis now=$nowMillis nextEvStart=${nextEv.startMillis}")

                                val scheduledTodayInfo = countScheduledLessonsAndMaxEndMillis(timetable, todayDate, zone)
                                Log.d(TAG, "scheduledToday count=${scheduledTodayInfo.first} maxEnd=${scheduledTodayInfo.second} (gap)")

                                val message = when {
                                    scheduledTodayInfo.first == 0 -> "No lessons today!"
                                    scheduledTodayInfo.second != null && nowMillis > scheduledTodayInfo.second!! -> "Done for today!"
                                    else -> "You're done for now"
                                }

                                val currentPlaceholder = ScheduleItem.LessonItem(
                                    Lesson(
                                        name = message,
                                        startTime = "",
                                        endTime = "",
                                        room = "",
                                        teachers = emptyList(),
                                        course = "",
                                        group = ""
                                    ),
                                    openStartMillis = null,
                                    openEndMillis = null
                                )

                                // Show the next day's event even when there are no lessons
                                // today, and label it with the weekday name.
                                val showNextCard = true
                                val nextDay = java.time.Instant.ofEpochMilli(nextEv.startMillis)
                                    .atZone(zone)
                                    .dayOfWeek
                                    .getDisplayName(TextStyle.FULL, Locale.getDefault())
                                val nextLabelText = if (nextEv.kind == ScheduleMerger.Kind.LESSON) "Next lesson: $nextDay" else "Next: $nextDay"

                                scheduleMaterialLayout(
                                    context = context,
                                    deviceParameters = requestParams.deviceConfiguration!!,
                                    currentItem = currentPlaceholder,
                                    nextItem = if (showNextCard) toScheduleItem(nextEv) else null,
                                    isCurrentActive = false,
                                    nextLabel = if (showNextCard) nextLabelText else null
                                )
                            }
                        } else {
                            noDataMaterialLayout(requestParams, context)
                        }

                    val gapLayout = LayoutElementBuilders.Layout.Builder().setRoot(gapRoot).build()
                    val gapEntry = TimelineBuilders.TimelineEntry.Builder().setLayout(gapLayout)
                    gapEntry.setValidity(
                        TimelineBuilders.TimeInterval.Builder()
                            .setStartMillis(gapStart)
                            .setEndMillis(gapEnd)
                            .build()
                    )
                    timelineBuilder.addTimelineEntry(gapEntry.build())
                }
            }
        }
    }

    return TileBuilders.Tile.Builder()
        .setResourcesVersion(RESOURCES_VERSION)
        .setTileTimeline(timelineBuilder.build())
        .setFreshnessIntervalMillis(60_000) // 1 minute
        .build()
}

private fun scheduleMaterialLayout(
    context: Context,
    deviceParameters: androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters,
    currentItem: ScheduleItem,
    nextItem: ScheduleItem?,
    isCurrentActive: Boolean,
    nextLabel: String? = null
): LayoutElementBuilders.LayoutElement = materialScope(context, deviceParameters) {
    // Instead of a single card, show a column of cards for all valid schedule items for this timeline entry
    // (for timeline, this will be just the current/next item, but this structure allows for easy expansion)
    primaryLayout(
        // Use a fixed title "Lessons". Hide title on small screens.
        titleSlot = if (isLargeScreen(deviceParameters)) {
            { text("Lessons".layoutString) }
        } else null,
        mainSlot = {
            Column.Builder()
                .setWidth(expand())
                .setHeight(wrap())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .apply {
                    // Detect placeholder currentItem (blank times & room)
                    val isPlaceholder = when (currentItem) {
                        is ScheduleItem.LessonItem ->
                            currentItem.lesson.startTime.isBlank() &&
                                    currentItem.lesson.endTime.isBlank() &&
                                    currentItem.lesson.room.isBlank()
                        else -> false
                    }

                    if (isPlaceholder && nextItem != null && nextLabel != null) {
                        // Render a single card that contains the placeholder message
                        // and the small "Next lesson: <Day>" label together.
                        addContent(
                            buildPlaceholderCardWithLabel(
                                placeholder = currentItem as ScheduleItem.LessonItem,
                                label = nextLabel,
                                scope = this@materialScope,
                                context = context,
                                isLarge = isLargeScreen(deviceParameters),
                                onClick = createOpenAppClickable(context)
                            )
                        )
                    } else {
                        addContent(
                            buildScheduleItemCard(currentItem, isActive = isCurrentActive, scope = this@materialScope, context = context, isLarge = isLargeScreen(deviceParameters))
                        )
                    }
                }
                .addContent(Spacer.Builder().setHeight(dp(4f)).build())
                // Always attempt to show a second card. If nextItem is null, show a 'You're done' placeholder.
                    .apply {
                    if (nextItem != null) {
                        // If we already embedded the label in the placeholder card,
                        // don't add the label here again. Otherwise show it above
                        // the next card.
                        val currentIsPlaceholder = when (currentItem) {
                            is ScheduleItem.LessonItem ->
                                currentItem.lesson.startTime.isBlank() &&
                                        currentItem.lesson.endTime.isBlank() &&
                                        currentItem.lesson.room.isBlank()
                            else -> false
                        }

                        if (!(currentIsPlaceholder && nextLabel != null)) {
                            if (nextLabel != null) {
                                addContent(
                                    text(
                                        text = nextLabel.layoutString,
                                        typography = Typography.LABEL_SMALL
                                    )
                                )
                            }
                        }

                        addContent(
                            buildScheduleItemCard(nextItem, isActive = false, scope = this@materialScope, context = context, isLarge = isLargeScreen(deviceParameters))
                        )
                    } else {
                        // If the currentItem is already a placeholder (created with
                        // empty start/end times and room), don't render an extra
                        // fallback 'You're done for today' card — that would show
                        // two messages. Only render the fallback when the
                        // currentItem is a real schedule item.
                        val isPlaceholder = when (currentItem) {
                            is ScheduleItem.LessonItem ->
                                currentItem.lesson.startTime.isBlank() &&
                                        currentItem.lesson.endTime.isBlank() &&
                                        currentItem.lesson.room.isBlank()
                            else -> false
                        }

                        if (!isPlaceholder) {
                            addContent(
                                buildLessonCard(
                                    Lesson(
                                        name = "You're done for today!",
                                        startTime = "",
                                        endTime = "",
                                        room = "",
                                        teachers = emptyList(),
                                        course = "",
                                        group = ""
                                    ),
                                    isActive = false,
                                    scope = this@materialScope,
                                    context = context,
                                    isLarge = isLargeScreen(deviceParameters),
                                    onClick = createOpenAppClickable(context)
                                )
                            )
                        }
                    }
                }
                .build()
        },
        bottomSlot = {
            textEdgeButton(
                onClick = createOpenAppClickable(context),
                labelContent = { text("More".layoutString) }
            )
        }
    )
}
private fun noDataMaterialLayout(
    requestParams: RequestBuilders.TileRequest,
    context: Context
): LayoutElementBuilders.LayoutElement = materialScope(context, requestParams.deviceConfiguration!!) {
    primaryLayout(
        // Fixed title "Lessons"; hide on small screens
        titleSlot = if (isLargeScreen(requestParams.deviceConfiguration!!)) {
            { text("Lessons".layoutString) }
        } else null,
        mainSlot = {
            Column.Builder()
                .setWidth(expand())
                .setHeight(wrap())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(
                    text(
                        text = "No data".layoutString,
                        typography = Typography.TITLE_LARGE
                    )
                )
                .addContent(Spacer.Builder().setHeight(dp(4f)).build())
                .addContent(
                    text(
                        text = "Open app to sync".layoutString,
                        typography = Typography.BODY_MEDIUM
                    )
                )
                .build()
        },
        bottomSlot = {
            textEdgeButton(
                onClick = createOpenAppClickable(context),
                labelContent = { text("Sync".layoutString) }
            )
        }
    )
}

// Overload that accepts an isLarge flag so callers that know device size can render responsive cards
private fun buildScheduleItemCard(
    item: ScheduleItem,
    isActive: Boolean,
    scope: androidx.wear.protolayout.material3.MaterialScope,
    context: Context,
    isLarge: Boolean
): LayoutElementBuilders.LayoutElement {
    val onClick = if (item.openStartMillis != null && item.openEndMillis != null) {
        createOpenScheduleItemClickable(
            context = context,
            kind = item.kind,
            startMillis = item.openStartMillis!!,
            endMillis = item.openEndMillis!!
        )
    } else {
        createOpenAppClickable(context)
    }
    return when (item) {
        is ScheduleItem.LessonItem -> buildLessonCard(item.lesson, isActive, scope, context, isLarge, onClick)
        is ScheduleItem.ExamItem -> buildExamCard(item.exam, isActive, scope, context, isLarge, onClick)
    }
}

// Responsive exam card overload
private fun buildExamCard(
    exam: Exam,
    isActive: Boolean,
    scope: androidx.wear.protolayout.material3.MaterialScope,
    context: Context,
    isLarge: Boolean,
    onClick: ModifiersBuilders.Clickable
): LayoutElementBuilders.LayoutElement = scope.run {

    val colors = this.colorScheme

    val timeText = "${exam.startTime} - ${exam.finishTime}"
    val roomText = formatRoomWithPreroom(exam.examRoom, exam.preRoom)

    val subjectTypography = if (isLarge) Typography.BODY_MEDIUM else Typography.BODY_SMALL

    scope.card(
        onClick = onClick,
        width = DimensionBuilders.expand(),
        height = DimensionBuilders.wrap(),
        content = {
            Column.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.wrap())
                // Centered EXAM header
                .addContent(
                    text(
                        text = "EXAM".layoutString,
                        typography = Typography.LABEL_SMALL,
                        color = colors.primary,
                        alignment = TEXT_ALIGN_CENTER
                    )
                )
                .addContent(Spacer.Builder().setHeight(dp(2f)).build())
                // Centered subject
                .addContent(
                    text(
                        text = exam.subjectDescription.layoutString,
                        typography = subjectTypography,
                        alignment = TEXT_ALIGN_CENTER,
                        maxLines = 1
                    )
                )
                .addContent(Spacer.Builder().setHeight(dp(4f)).build())
                // Centered time
                .addContent(
                    text(
                        text = timeText.layoutString,
                        typography = Typography.LABEL_SMALL,
                        alignment = TEXT_ALIGN_CENTER
                    )
                )
                .addContent(Spacer.Builder().setHeight(dp(2f)).build())
                // Centered room with preroom arrow format
                .addContent(
                    text(
                        text = roomText.layoutString,
                        typography = Typography.LABEL_SMALL,
                        alignment = TEXT_ALIGN_CENTER,
                        maxLines = 2
                    )
                )
                .build()
        }
    )
}

// Responsive lesson card overload
private fun buildLessonCard(
    lesson: Lesson,
    isActive: Boolean,
    scope: androidx.wear.protolayout.material3.MaterialScope,
    context: Context,
    isLarge: Boolean,
    onClick: ModifiersBuilders.Clickable
): LayoutElementBuilders.LayoutElement = scope.run {

    // Get system Material You colors
    val colors = this.colorScheme

    val timeText = "${lesson.startTime} - ${lesson.endTime}"

    val titleTypography = if (isLarge) Typography.BODY_MEDIUM else Typography.BODY_SMALL

    scope.card(
        onClick = onClick,
        width = DimensionBuilders.expand(),
        height = DimensionBuilders.wrap(),
        content = {
            Column.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.wrap())
                // Centered lesson name
                .addContent(
                    text(
                        text = lesson.name.layoutString,
                        typography = titleTypography,
                        alignment = TEXT_ALIGN_CENTER,
                        maxLines = 1
                    )
                )
                .addContent(Spacer.Builder().setHeight(dp(4f)).build())
                // Centered time and room (only shown when lesson times are present)
                .apply {
                    if (lesson.startTime.isNotBlank() || lesson.endTime.isNotBlank() || lesson.room.isNotBlank()) {
                        addContent(
                            Row.Builder()
                                .setWidth(DimensionBuilders.expand())
                                .addContent(
                                    text(
                                        text = timeText.layoutString,
                                        typography = Typography.LABEL_SMALL,
                                        alignment = TEXT_ALIGN_CENTER
                                    )
                                )
                                .addContent(Spacer.Builder().setWidth(dp(8f)).build())
                                .addContent(
                                    text(
                                        text = lesson.room.layoutString,
                                        typography = Typography.LABEL_SMALL,
                                        alignment = TEXT_ALIGN_CENTER
                                    )
                                )
                                .build()
                        )
                    }
                }
                .build()
        }
    )
}


private const val TAG = "MainTileService"


private fun logd(msg: String) {

    Log.d(TAG, msg)

}

/**
 * Validates if preroom should be shown (not blank and less than 6 words)
 */
private fun isValidPreroom(preroom: String?): Boolean {
    if (preroom.isNullOrBlank()) return false
    val wordCount = preroom.trim().split(Regex("\\s+")).size
    return wordCount < 6
}

/**
 * Extracts short room code (first token before space)
 */
private fun extractRoomCode(room: String): String {
    if (room.isBlank()) return ""
    return room.trim().split(Regex("\\s+")).firstOrNull() ?: ""
}

/**
 * Formats room display with arrow notation if preroom is valid
 * Returns "Pre: A101 → IC203" if preroom valid, otherwise just the room
 */
private fun formatRoomWithPreroom(room: String, preroom: String?): String {
    return if (isValidPreroom(preroom)) {
        val preCode = extractRoomCode(preroom!!)
        val mainCode = extractRoomCode(room)
        "Pre: $preCode → $mainCode"
    } else {
        room
    }
}


private fun getTimeUntilText(item: ScheduleItem): String {

    val startTime = item.startTime ?: return "Next"

    val now = LocalTime.now()

    val minutes = ChronoUnit.MINUTES.between(now, startTime)


    return when {

        minutes < 1 -> "Starting"

        minutes < 60 -> "in ${minutes}m"

        else -> {

            val hours = minutes / 60

            "in ${hours}h"

        }

    }

}


private fun mergeSchedule(

    lessons: List<Lesson>,

    exams: List<Exam>,

    now: LocalTime

): List<ScheduleItem> {

    val items = mutableListOf<ScheduleItem>()

    

    // Add all exams as ExamItems

    exams.forEach { exam ->

        items.add(ScheduleItem.ExamItem(exam))

    }

    

    // Add lessons, but check for overlaps with exams

    lessons.forEach { lesson ->

        val lessonStart = lesson.getParsedStartTime()

        val lessonEnd = lesson.getParsedEndTime()

        

        if (lessonStart != null && lessonEnd != null) {

            // Check if this lesson overlaps with any exam

            val overlapsWithExam = exams.any { exam ->

                val examStart = exam.getParsedStartTime()

                val examEnd = try {

                    LocalTime.parse(exam.finishTime, DateTimeFormatter.ofPattern("HH:mm"))

                } catch (e: Exception) {

                    null

                }

                

                if (examStart == null || examEnd == null) return@any false

                

                // Check if times overlap

                !(lessonEnd.isBefore(examStart) || lessonStart.isAfter(examEnd))

            }

            

            if (overlapsWithExam) {

                // Add the lesson but it will be sorted after the exam due to our sorting logic

                items.add(ScheduleItem.LessonItem(lesson))

            } else {

                // No overlap, add normally

                items.add(ScheduleItem.LessonItem(lesson))

            }

        } else {

            // Add lessons without valid times

            items.add(ScheduleItem.LessonItem(lesson))

        }

    }

    

    // Sort by start time, with exams taking priority when times are equal

    return items.sortedWith(compareBy<ScheduleItem> { it.startTime ?: LocalTime.MAX }

        .thenBy { item ->

            // When times are equal, exams come first (return 0 for exam, 1 for lesson)

            when (item) {

                is ScheduleItem.ExamItem -> 0

                is ScheduleItem.LessonItem -> 1

            }

        })

}


// Count scheduled lessons on a given LocalDate (based on `timetable` contents).
// Returns Pair(count, maxEndMillisOrNull).
private fun countScheduledLessonsAndMaxEndMillis(
    timetable: uk.bw86.nscgschedule.data.models.Timetable?,
    date: java.time.LocalDate,
    zone: java.time.ZoneId
): Pair<Int, Long?> {
    if (timetable == null) return Pair(0, null)

    var count = 0
    var maxEnd: Long? = null

    val patterns = listOf("d/M/uuuu", "dd/MM/uuuu", "d/MM/uuuu", "dd/M/uuuu")

    timetable.days.forEach { daySchedule ->
        val raw = daySchedule.day.trim()
        if (raw.isBlank()) return@forEach

        val parts = raw.split(' ').filter { it.isNotBlank() }
        val datePart = parts.lastOrNull() ?: return@forEach

        var parsedDate: java.time.LocalDate? = null
        for (pattern in patterns) {
            try {
                val formatter = java.time.format.DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)
                parsedDate = java.time.LocalDate.parse(datePart, formatter)
                break
            } catch (_: Exception) {
            }
        }

        val matches = if (parsedDate != null) {
            parsedDate == date
        } else {
            // If the day string doesn't include an explicit date, match by weekday name
            val rawLower = raw.lowercase(Locale.ENGLISH)
            val dowNameFull = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase(Locale.ENGLISH)
            val dowNameShort = date.dayOfWeek.name.lowercase(Locale.ENGLISH)
            rawLower.contains(dowNameFull) || rawLower.contains(dowNameShort)
        }

        if (!matches) return@forEach

        // Count lessons and compute max end millis
        daySchedule.lessons.forEach { lesson ->
            val s = try { lesson.getParsedStartTime() } catch (_: Exception) { null }
            val e = try { lesson.getParsedEndTime() } catch (_: Exception) { null }
            if (s == null || e == null) return@forEach
            if (!e.isAfter(s)) return@forEach

            val endMillis = java.time.LocalDateTime.of(date, e).atZone(zone).toInstant().toEpochMilli()
            count += 1
            if (maxEnd == null) {
                maxEnd = endMillis
            } else {
                if (endMillis > maxEnd!!) {
                    maxEnd = endMillis
                }
            }
        }
    }

    return Pair(count, maxEnd)
}


private fun createOpenAppClickable(context: Context): ModifiersBuilders.Clickable {

    return ModifiersBuilders.Clickable.Builder()

        .setId("open_app")

        .setOnClick(

            ActionBuilders.LaunchAction.Builder()

                .setAndroidActivity(

                    ActionBuilders.AndroidActivity.Builder()

                        .setPackageName(context.packageName)

                        .setClassName(MainActivity::class.java.name)

                        .build()

                )

                .build()

        )

        .build()

}

private const val EXTRA_OPEN_KIND = "open_kind"
private const val EXTRA_OPEN_START_MILLIS = "open_start_millis"
private const val EXTRA_OPEN_END_MILLIS = "open_end_millis"

private fun createOpenScheduleItemClickable(
    context: Context,
    kind: ScheduleMerger.Kind,
    startMillis: Long,
    endMillis: Long
): ModifiersBuilders.Clickable {
    return ModifiersBuilders.Clickable.Builder()
        .setId("open_item_${kind.name}_$startMillis")
        .setOnClick(
            ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(
                    ActionBuilders.AndroidActivity.Builder()
                        .setPackageName(context.packageName)
                        .setClassName(MainActivity::class.java.name)
                        .addKeyToExtraMapping(
                            EXTRA_OPEN_KIND,
                            ActionBuilders.AndroidStringExtra.Builder().setValue(kind.name).build()
                        )
                        .addKeyToExtraMapping(
                            EXTRA_OPEN_START_MILLIS,
                            ActionBuilders.AndroidStringExtra.Builder().setValue(startMillis.toString()).build()
                        )
                        .addKeyToExtraMapping(
                            EXTRA_OPEN_END_MILLIS,
                            ActionBuilders.AndroidStringExtra.Builder().setValue(endMillis.toString()).build()
                        )
                        .build()
                )
                .build()
        )
        .build()
}


private fun buildContentLayout(

    context: Context,

    currentItem: ScheduleItem?,

    upcomingItems: List<ScheduleItem>,

    allItems: List<ScheduleItem>,

    scope: androidx.wear.protolayout.material3.MaterialScope,

    maxItems: Int = 2

): LayoutElementBuilders.LayoutElement = scope.run {

    val columnBuilder = Column.Builder()

        .setWidth(expand())

        .setHeight(wrap())

        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)

    

    // LayoutColor for 'text'

    val secondaryDimLc = LayoutColor(COLOR_SECONDARY_DIM)


    when {

        currentItem != null -> {

            // Show current item

            columnBuilder.addContent(

                buildScheduleItemCard(currentItem, isActive = true, scope, context)

            )

            // Show next upcoming item(s) if available and allowed

            if (upcomingItems.isNotEmpty() && maxItems > 1) {

                columnBuilder.addContent(Spacer.Builder().setHeight(dp(4f)).build())

                columnBuilder.addContent(

                    buildScheduleItemCard(upcomingItems.first(), isActive = false, scope, context)

                )

            }

        }

        upcomingItems.isNotEmpty() -> {

            // Show up to maxItems upcoming items

            upcomingItems.take(maxItems).forEachIndexed { idx, item ->

                if (idx > 0) columnBuilder.addContent(Spacer.Builder().setHeight(dp(4f)).build())

                columnBuilder.addContent(

                    buildScheduleItemCard(item, isActive = false, scope, context)

                )

            }

        }

        allItems.isNotEmpty() -> {

            // All items are in the past

            columnBuilder.addContent(

                buildLessonCard(

                    Lesson(

                        name = "You're done for today!",

                        startTime = "",

                        endTime = "",

                        room = "",

                        teachers = emptyList(),

                        course = "",

                        group = ""

                    ),

                    isActive = false,

                    scope,

                    context

                )

            )

        }

        else -> {

            // No schedule items at all

            columnBuilder.addContent(

                buildLessonCard(

                    Lesson(

                        name = "No events today",

                        startTime = "",

                        endTime = "",

                        room = "",

                        teachers = emptyList(),

                        course = "",

                        group = ""

                    ),

                    isActive = false,

                    scope,

                    context

                )

            )

            columnBuilder.addContent(Spacer.Builder().setHeight(dp(4f)).build())

            columnBuilder.addContent(

                text(

                    text = "Enjoy your free time!".layoutString,

                    typography = Typography.LABEL_SMALL,

                    color = secondaryDimLc

                )

            )

        }

    }


    columnBuilder.build()

}


private fun buildScheduleItemCard(

    item: ScheduleItem,

    isActive: Boolean,

    scope: androidx.wear.protolayout.material3.MaterialScope,

    context: Context

): LayoutElementBuilders.LayoutElement {

    // Delegate to responsive overload with a conservative default (not-large).
    return buildScheduleItemCard(item, isActive, scope, context, /*isLarge=*/false)

}


 private fun buildExamCard(
     exam: Exam,
     isActive: Boolean,
     scope: androidx.wear.protolayout.material3.MaterialScope,
     context: Context
 ): LayoutElementBuilders.LayoutElement = scope.run {
     // Delegate to responsive overload with default isLarge=false
     buildExamCard(exam, isActive, scope, context, /*isLarge=*/false, onClick = createOpenAppClickable(context))
 }


 private fun buildLessonCard(
     lesson: Lesson,
     isActive: Boolean,
     scope: androidx.wear.protolayout.material3.MaterialScope,
     context: Context
 ): LayoutElementBuilders.LayoutElement = scope.run {
     // Delegate to responsive overload with default isLarge=false
     buildLessonCard(lesson, isActive, scope, context, /*isLarge=*/false, onClick = createOpenAppClickable(context))
 }


// Build a placeholder card that includes a small label (e.g., "Next lesson: Wednesday")
// so the placeholder message and the next-lesson label appear together in one card.
private fun buildPlaceholderCardWithLabel(
    placeholder: ScheduleItem.LessonItem,
    label: String,
    scope: androidx.wear.protolayout.material3.MaterialScope,
    context: Context,
    isLarge: Boolean,
    onClick: ModifiersBuilders.Clickable
): LayoutElementBuilders.LayoutElement = scope.run {
    val colors = this.colorScheme

    scope.card(
        onClick = onClick,
        width = DimensionBuilders.expand(),
        height = DimensionBuilders.wrap(),
        content = {
            Column.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.wrap())
                // Placeholder title
                .addContent(
                    text(
                        text = placeholder.lesson.name.layoutString,
                        typography = if (isLarge) Typography.BODY_MEDIUM else Typography.BODY_SMALL,
                        alignment = TEXT_ALIGN_CENTER,
                        maxLines = 2
                    )
                )
                .addContent(Spacer.Builder().setHeight(dp(6f)).build())
                // Small label inside the same card
                .addContent(
                    text(
                        text = label.layoutString,
                        typography = Typography.LABEL_SMALL,
                        color = colors.secondary
                    )
                )
                .build()
        }
    )
}


 private fun noDataLayout(
     requestParams: RequestBuilders.TileRequest,
     context: Context
 ): LayoutElementBuilders.LayoutElement {
     return materialScope(context, requestParams.deviceConfiguration) {
         primaryLayout(
             // no titleSlot used here — optional
             titleSlot = {
                 text(
                     text = "NSCG Schedule".layoutString,
                     typography = Typography.LABEL_SMALL,
                     color = LayoutColor(COLOR_ON_SURFACE)
                 )
             },
             mainSlot = {
                 // main content: vertical layout with your messages
                 Column.Builder()
                     .setWidth(DimensionBuilders.expand())
                     .setHeight(DimensionBuilders.wrap())
                     .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                     .addContent(
                         text(
                             text = "No data".layoutString,
                             typography = Typography.TITLE_LARGE,
                             color = LayoutColor(COLOR_ON_SURFACE)
                         )
                     )
                     .addContent(Spacer.Builder().setHeight(dp(4f)).build())
                     .addContent(
                         text(
                             text = "Open app to sync".layoutString,
                             typography = Typography.BODY_MEDIUM,
                             color = LayoutColor(COLOR_SECONDARY)
                         )
                     )
                     .build()
             },
             bottomSlot = {
                 textEdgeButton(
                     onClick = createOpenAppClickable(context),
                     colors = ButtonColors(
                         containerColor = LayoutColor(COLOR_PRIMARY),
                         labelColor = LayoutColor(COLOR_ON_PRIMARY)
                     )
                 ) {
                     text("More".layoutString)
                 }
             },
         )
     }
 }