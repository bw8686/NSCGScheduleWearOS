package uk.bw86.nscgschedule.complication

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.util.Log
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeRange
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import uk.bw86.nscgschedule.data.DataRepository
import uk.bw86.nscgschedule.data.models.Lesson
import uk.bw86.nscgschedule.presentation.MainActivity
import uk.bw86.nscgschedule.schedule.ScheduleMerger
import java.time.Instant
import java.time.LocalTime

/**
 * Complication showing the next lesson or exam
 * Supports SHORT_TEXT and LONG_TEXT complication types
 */
class MainComplicationService : SuspendingComplicationDataSourceService() {
    
    companion object {
        private const val TAG = "MainComplicationService"
        
        /**
         * Request an update for all complications provided by this service
         */
        fun requestUpdate(context: Context) {
            try {
                val requester = ComplicationDataSourceUpdateRequester.create(
                    context,
                    ComponentName(context, MainComplicationService::class.java)
                )
                requester.requestUpdateAll()
                Log.d(TAG, "Complication update requested")
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting complication update", e)
            }
        }
    }
    
    private val repository: DataRepository by lazy {
        DataRepository.getInstance(applicationContext)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return when (type) {
            ComplicationType.SHORT_TEXT -> createShortTextData(
                text = "TG00",
                contentDescription = "Next: Minecraft at 9:00",
                validUntilMillis = null
            )
            ComplicationType.LONG_TEXT -> createLongTextData(
                title = "TG00",
                text = "9:00AM",
                contentDescription = "Next: Minecraft at 9:00 in Room TG00",
                validUntilMillis = null
            )
            else -> null
        }
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        Log.d(TAG, "Complication request received for type: ${request.complicationType}")

        val timetable = repository.getCurrentTimetable()
        val examTimetable = repository.getCurrentExamTimetable()
        val zone = java.time.ZoneId.systemDefault()
        val nowMillis = java.time.Instant.now().toEpochMilli()

        Log.d(TAG, "nowMillis=$nowMillis now=${java.time.LocalDateTime.now()} zone=$zone")

        val events = ScheduleMerger.buildEvents(
            timetable = timetable,
            examTimetable = examTimetable,
            nowMillis = nowMillis,
            zoneId = zone,
            horizonDays = 14
        )

        val pick = ScheduleMerger.pick(events, nowMillis)
        val display = pick.current ?: pick.next

        // Mark data as stale after the displayed item's end, so the system refreshes near boundaries.
        val validUntilMillis = display?.endMillis

        Log.d(
            TAG,
            "Complication pick: display=${display?.kind} active=${display?.isActiveAt(nowMillis) == true} next=${pick.next?.kind} events=${events.size}"
        )

        return when (display?.kind) {
            ScheduleMerger.Kind.EXAM -> createExamComplication(request.complicationType, display.exam!!, validUntilMillis)
            ScheduleMerger.Kind.LESSON -> createLessonComplication(request.complicationType, display.lesson!!, validUntilMillis)
            null -> createNoDataComplication(request.complicationType, validUntilMillis)
        }
    }

    private fun createLessonComplication(
        type: ComplicationType,
        lesson: Lesson,
        validUntilMillis: Long?
    ): ComplicationData? {
        // For SHORT_TEXT: Show full room and time on one line (no truncation)
        // For LONG_TEXT: Use title for full room, text for time (displays on 2 lines)
        return when (type) {
            ComplicationType.SHORT_TEXT -> {
                val text = "${lesson.room}"
                createShortTextData(
                    text = text,
                    contentDescription = "Next lesson: ${lesson.name} at ${lesson.startTime} in ${lesson.room}",
                    validUntilMillis = validUntilMillis
                )
            }
            ComplicationType.LONG_TEXT -> createLongTextData(
                title = lesson.room,
                text = lesson.startTime,
                contentDescription = "Next lesson: ${lesson.name} at ${lesson.startTime} in ${lesson.room}",
                validUntilMillis = validUntilMillis
            )
            else -> null
        }
    }
    
    private fun createExamComplication(
        type: ComplicationType,
        exam: uk.bw86.nscgschedule.data.models.Exam,
        validUntilMillis: Long?
    ): ComplicationData? {
        // For SHORT_TEXT: Show only room (truncated to 4 chars, like lessons)
        // For LONG_TEXT: Title is subject, text is time and room (like lessons)
        val roomShort = getShortRoom(exam.examRoom)
        return when (type) {
            ComplicationType.SHORT_TEXT -> {
                createShortTextData(
                    text = roomShort,
                    contentDescription = "Next exam: ${exam.subjectDescription} at ${exam.startTime} in ${exam.examRoom}",
                    validUntilMillis = validUntilMillis
                )
            }
            ComplicationType.LONG_TEXT -> createLongTextData(
                title = "EXAM - ${roomShort}",
                text = "${exam.startTime}",
                contentDescription = "Next exam: ${exam.subjectDescription} at ${exam.startTime} in ${exam.examRoom}",
                validUntilMillis = validUntilMillis
            )
            else -> null
        }
    }
    
    private fun createNoDataComplication(type: ComplicationType, validUntilMillis: Long?): ComplicationData? {
        return when (type) {
            ComplicationType.SHORT_TEXT -> createShortTextData(
                text = "NSCG",
                contentDescription = "NSCG Schedule - No upcoming events",
                validUntilMillis = validUntilMillis
            )
            ComplicationType.LONG_TEXT -> createLongTextData(
                title = "NSCG Schedule",
                text = "No upcoming events",
                contentDescription = "No upcoming lessons or exams",
                validUntilMillis = validUntilMillis
            )
            else -> null
        }
    }
    
    private fun createShortTextData(
        text: String,
        contentDescription: String,
        validUntilMillis: Long?
    ): ShortTextComplicationData {
        val tapIntent = Intent(applicationContext, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(text).build(),
            contentDescription = PlainComplicationText.Builder(contentDescription).build()
        )
            .setTapAction(pendingIntent)
            .apply {
                if (validUntilMillis != null && validUntilMillis > 0) {
                    setValidTimeRange(TimeRange.before(Instant.ofEpochMilli(validUntilMillis)))
                }
            }
            .build()
    }
    
    private fun createLongTextData(
        title: String,
        text: String,
        contentDescription: String,
        validUntilMillis: Long?
    ): LongTextComplicationData {
        val tapIntent = Intent(applicationContext, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return LongTextComplicationData.Builder(
            text = PlainComplicationText.Builder(text).build(),
            contentDescription = PlainComplicationText.Builder(contentDescription).build()
        )
            .setTitle(PlainComplicationText.Builder(title).build())
            .setTapAction(pendingIntent)
            .apply {
                if (validUntilMillis != null && validUntilMillis > 0) {
                    setValidTimeRange(TimeRange.before(Instant.ofEpochMilli(validUntilMillis)))
                }
            }
            .build()
    }
    
    private fun truncateText(text: String, maxLength: Int): String {
        return if (text.length > maxLength) {
            text.take(maxLength)
        } else {
            text
        }
    }

    private fun getShortRoom(room: String): String {
        if (room.isBlank()) return ""
        // Split by whitespace and take the first token as the room number.
        // Examples:
        // - "B123 1st Floor" -> "B123"
        // - "TG00" -> "TG00"
        val token = room.trim().split(Regex("\\s+")).firstOrNull() ?: return ""
        // Return only alphanumeric characters from the token to avoid punctuation
        val cleaned = token.filter { it.isLetterOrDigit() }
        return cleaned
    }
}