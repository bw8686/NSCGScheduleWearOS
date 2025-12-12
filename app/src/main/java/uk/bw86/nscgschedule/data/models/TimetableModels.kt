package uk.bw86.nscgschedule.data.models

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import android.util.Log
import java.util.Locale

/**
 * Represents a lesson in the timetable
 */
data class Lesson(
    val teachers: List<String>,
    val course: String,
    val group: String,
    val name: String,
    val startTime: String,
    val endTime: String,
    val room: String
) {
    companion object {
        fun fromJson(json: JSONObject): Lesson {
            val teachersArray = json.getJSONArray("teachers")
            val teachers = (0 until teachersArray.length()).map { teachersArray.getString(it) }
            
            return Lesson(
                teachers = teachers,
                course = json.optString("course", ""),
                group = json.optString("group", ""),
                name = json.optString("name", ""),
                startTime = json.optString("startTime", ""),
                endTime = json.optString("endTime", ""),
                room = json.optString("room", "")
            )
        }
    }
    
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("teachers", JSONArray(teachers))
            put("course", course)
            put("group", group)
            put("name", name)
            put("startTime", startTime)
            put("endTime", endTime)
            put("room", room)
        }
    }
    
    /**
     * Parse the start time string to LocalTime
     * Handles formats like "9:30AM", "10:45AM", "1:45PM"
     */
    fun getParsedStartTime(): LocalTime? {
        return parseTimeString(startTime)
    }
    
    /**
     * Parse the end time string to LocalTime
     * Handles formats like "9:30AM", "10:45AM", "1:45PM"
     */
    fun getParsedEndTime(): LocalTime? {
        return parseTimeString(endTime)
    }
    
    private fun parseTimeString(timeStr: String): LocalTime? {
        if (timeStr.isBlank()) return null

        var cleanTime = timeStr.trim()
        // Remove non-breaking spaces and normalize whitespace
        cleanTime = cleanTime.replace(Regex("\u00A0"), " ")
        cleanTime = cleanTime.replace(Regex("\\s+"), " ")

        val patterns = listOf(
            "h:mma",
            "h:mm a",
            "hh:mma",
            "hh:mm a",
            "H:mm",
            "HH:mm"
        )

        for (pattern in patterns) {
            try {
                val formatter = DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)
                val parsed = LocalTime.parse(cleanTime.uppercase(Locale.ENGLISH), formatter)
                Log.d("TimetableModels", "Parsed time '$timeStr' using pattern '$pattern' -> $parsed")
                return parsed
            } catch (e: DateTimeParseException) {
                // continue
            } catch (e: Exception) {
                Log.d("TimetableModels", "Unexpected error parsing time '$timeStr' with pattern '$pattern': ${e.message}")
            }
        }

        // Try with a space before AM/PM (e.g., "9:30AM" -> "9:30 AM")
        val withSpaceAmPm = cleanTime.replace(Regex("(?i)(am|pm)$"), " $1")
        if (withSpaceAmPm != cleanTime) {
            for (pattern in patterns) {
                try {
                    val formatter = DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)
                    val parsed = LocalTime.parse(withSpaceAmPm.uppercase(Locale.ENGLISH), formatter)
                    Log.d("TimetableModels", "Parsed time '$timeStr' using pattern '$pattern' after inserting space -> $parsed")
                    return parsed
                } catch (e: DateTimeParseException) {
                    // continue
                } catch (e: Exception) {
                    Log.d("TimetableModels", "Unexpected error parsing time '$timeStr' after inserting space with pattern '$pattern': ${e.message}")
                }
            }
        }

        Log.d("TimetableModels", "Couldn't parse time: raw='$timeStr' cleaned='$cleanTime'")
        return null
    }
}

/**
 * Represents a day's schedule with lessons
 */
data class DaySchedule(
    val day: String,
    val lessons: List<Lesson>
) {
    companion object {
        fun fromJson(json: JSONObject): DaySchedule {
            val lessonsArray = json.getJSONArray("lessons")
            val lessons = (0 until lessonsArray.length()).map { 
                Lesson.fromJson(lessonsArray.getJSONObject(it)) 
            }
            
            return DaySchedule(
                day = json.optString("day", ""),
                lessons = lessons
            )
        }
    }
    
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("day", day)
            put("lessons", JSONArray(lessons.map { it.toJson() }))
        }
    }
    
    /**
     * Extract the day name (e.g., "Monday" from "Monday 09/12/2025")
     */
    fun getDayName(): String {
        return day.split(" ").firstOrNull() ?: day
    }
}

/**
 * Represents the full timetable
 */
data class Timetable(
    val days: List<DaySchedule>
) {
    companion object {
        fun fromJson(json: JSONObject): Timetable {
            val daysArray = json.getJSONArray("days")
            val days = (0 until daysArray.length()).map { 
                DaySchedule.fromJson(daysArray.getJSONObject(it)) 
            }
            
            return Timetable(days = days)
        }
        
        fun fromJsonString(jsonString: String): Timetable? {
            return try {
                fromJson(JSONObject(jsonString))
            } catch (e: Exception) {
                null
            }
        }
        
        fun empty(): Timetable = Timetable(emptyList())
    }
    
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("days", JSONArray(days.map { it.toJson() }))
        }
    }
    
    fun toJsonString(): String = toJson().toString()
    
    /**
     * Get today's schedule
     */
    fun getTodaySchedule(): DaySchedule? {
        val today = LocalDate.now()
        val dayNames = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
        val todayDayName = dayNames[today.dayOfWeek.value - 1]
        
        return days.find { it.day.lowercase().contains(todayDayName) }
    }
    
    /**
     * Get the next upcoming lesson across all days
     */
    fun getNextLesson(): Pair<DaySchedule, Lesson>? {
        val now = LocalTime.now()
        val today = LocalDate.now()
        val dayNames = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
        val todayIndex = today.dayOfWeek.value - 1
        
        // First check today's remaining lessons
        val todaySchedule = days.find { it.day.lowercase().contains(dayNames[todayIndex]) }
        if (todaySchedule != null) {
            val nextLesson = todaySchedule.lessons.find { lesson ->
                lesson.getParsedStartTime()?.isAfter(now) == true
            }
            if (nextLesson != null) {
                return Pair(todaySchedule, nextLesson)
            }
        }
        
        // Then check upcoming days
        for (i in 1..6) {
            val checkIndex = (todayIndex + i) % 7
            val daySchedule = days.find { it.day.lowercase().contains(dayNames[checkIndex]) }
            if (daySchedule != null && daySchedule.lessons.isNotEmpty()) {
                return Pair(daySchedule, daySchedule.lessons.first())
            }
        }
        
        return null
    }
    
    /**
     * Get current lesson if one is in progress
     */
    fun getCurrentLesson(): Lesson? {
        val now = LocalTime.now()
        val todaySchedule = getTodaySchedule() ?: return null
        
        return todaySchedule.lessons.find { lesson ->
            val startTime = lesson.getParsedStartTime() ?: return@find false
            val endTime = lesson.getParsedEndTime() ?: return@find false
            // Consider a lesson current if it's in progress or starting within 5 minutes
            (now.isAfter(startTime) && now.isBefore(endTime)) ||
                    (now.isAfter(startTime.minusMinutes(5)) && now.isBefore(endTime))
        }
    }
}
