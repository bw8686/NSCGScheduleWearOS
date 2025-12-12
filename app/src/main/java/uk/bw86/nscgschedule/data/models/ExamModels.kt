package uk.bw86.nscgschedule.data.models

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Represents student information for exams
 */
data class StudentInfo(
    val refNo: String,
    val name: String,
    val dateOfBirth: String,
    val uln: String,
    val candidateNo: String
) {
    companion object {
        fun fromJson(json: JSONObject): StudentInfo {
            return StudentInfo(
                refNo = json.optString("refNo", ""),
                name = json.optString("name", ""),
                dateOfBirth = json.optString("dateOfBirth", ""),
                uln = json.optString("uln", ""),
                candidateNo = json.optString("candidateNo", "")
            )
        }
    }
    
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("refNo", refNo)
            put("name", name)
            put("dateOfBirth", dateOfBirth)
            put("uln", uln)
            put("candidateNo", candidateNo)
        }
    }
}

/**
 * Represents an exam entry
 */
data class Exam(
    val date: String,
    val boardCode: String,
    val paper: String,
    val startTime: String,
    val finishTime: String,
    val subjectDescription: String,
    val preRoom: String,
    val examRoom: String,
    val seatNumber: String,
    val additional: String
) {
    companion object {
        fun fromJson(json: JSONObject): Exam {
            return Exam(
                date = json.optString("date", ""),
                boardCode = json.optString("boardCode", ""),
                paper = json.optString("paper", ""),
                startTime = json.optString("startTime", ""),
                finishTime = json.optString("finishTime", ""),
                subjectDescription = json.optString("subjectDescription", ""),
                preRoom = json.optString("preRoom", ""),
                examRoom = json.optString("examRoom", ""),
                seatNumber = json.optString("seatNumber", ""),
                additional = json.optString("additional", "")
            )
        }
    }
    
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("date", date)
            put("boardCode", boardCode)
            put("paper", paper)
            put("startTime", startTime)
            put("finishTime", finishTime)
            put("subjectDescription", subjectDescription)
            put("preRoom", preRoom)
            put("examRoom", examRoom)
            put("seatNumber", seatNumber)
            put("additional", additional)
        }
    }
    
    /**
     * Parse the date string (format: "DD-MM-YYYY") to LocalDate
     */
    fun getParsedDate(): LocalDate? {
        if (date.isBlank()) return null
        val raw = date.trim()

        // Support common formats:
        // - DD-MM-YYYY (expected)
        // - DD/MM/YYYY
        // - YYYY-MM-DD
        val parts = raw.split('-', '/').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size != 3) {
            Log.d("ExamModels", "Couldn't parse exam date: raw='$raw'")
            return null
        }

        return try {
            val (a, b, c) = parts
            val parsed = if (a.length == 4) {
                // YYYY-MM-DD
                LocalDate.of(a.toInt(), b.toInt(), c.toInt())
            } else {
                // DD-MM-YYYY
                LocalDate.of(c.toInt(), b.toInt(), a.toInt())
            }
            Log.d("ExamModels", "Parsed exam date '$raw' -> $parsed")
            parsed
        } catch (e: Exception) {
            Log.d("ExamModels", "Couldn't parse exam date: raw='$raw' err='${e.message}'")
            null
        }
    }
    
    /**
     * Parse the start time string to LocalTime
     */
    fun getParsedStartTime(): LocalTime? {
        return parseTimeString(startTime, label = "start")
    }

    /**
     * Parse the finish time string to LocalTime
     */
    fun getParsedFinishTime(): LocalTime? {
        return parseTimeString(finishTime, label = "finish")
    }

    private fun parseTimeString(timeStr: String, label: String): LocalTime? {
        if (timeStr.isBlank()) return null

        var cleanTime = timeStr.trim()
        cleanTime = cleanTime.replace(Regex("\u00A0"), " ")
        cleanTime = cleanTime.replace(Regex("\\s+"), " ")

        val patterns = listOf(
            "H:mm",
            "HH:mm",
            "h:mma",
            "h:mm a",
            "hh:mma",
            "hh:mm a"
        )

        for (pattern in patterns) {
            try {
                val formatter = DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)
                val parsed = LocalTime.parse(cleanTime.uppercase(Locale.ENGLISH), formatter)
                Log.d("ExamModels", "Parsed exam $label time '$timeStr' using pattern '$pattern' -> $parsed")
                return parsed
            } catch (_: DateTimeParseException) {
                // continue
            } catch (e: Exception) {
                Log.d("ExamModels", "Unexpected error parsing exam $label time '$timeStr' with '$pattern': ${e.message}")
            }
        }

        val withSpaceAmPm = cleanTime.replace(Regex("(?i)(am|pm)$"), " $1")
        if (withSpaceAmPm != cleanTime) {
            for (pattern in patterns) {
                try {
                    val formatter = DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)
                    val parsed = LocalTime.parse(withSpaceAmPm.uppercase(Locale.ENGLISH), formatter)
                    Log.d("ExamModels", "Parsed exam $label time '$timeStr' using pattern '$pattern' after inserting space -> $parsed")
                    return parsed
                } catch (_: DateTimeParseException) {
                    // continue
                } catch (e: Exception) {
                    Log.d("ExamModels", "Unexpected error parsing exam $label time '$timeStr' after inserting space with '$pattern': ${e.message}")
                }
            }
        }

        Log.d("ExamModels", "Couldn't parse exam $label time: raw='$timeStr' cleaned='$cleanTime'")
        return null
    }
    
    /**
     * Get the full date and time of the exam start
     */
    fun getStartDateTime(): LocalDateTime? {
        val parsedDate = getParsedDate() ?: return null
        val parsedTime = getParsedStartTime() ?: return null
        return LocalDateTime.of(parsedDate, parsedTime)
    }
    
    /**
     * Check if this exam is upcoming (in the future)
     */
    fun isUpcoming(): Boolean {
        val examDateTime = getStartDateTime() ?: return false
        return examDateTime.isAfter(LocalDateTime.now())
    }
    
    /**
     * Get a short description for complications
     */
    fun getShortDescription(): String {
        return subjectDescription.split(" ").take(2).joinToString(" ")
    }
    
    /**
     * Get formatted date for display
     */
    fun getFormattedDate(): String {
        val parsedDate = getParsedDate() ?: return date
        return parsedDate.format(DateTimeFormatter.ofPattern("EEE d MMM"))
    }
}

/**
 * Represents the exam timetable
 */
data class ExamTimetable(
    val hasExams: Boolean,
    val studentInfo: StudentInfo?,
    val exams: List<Exam>,
    val warningMessage: String?
) {
    companion object {
        fun fromJson(json: JSONObject): ExamTimetable {
            val examsArray = json.optJSONArray("exams") ?: JSONArray()
            val exams = (0 until examsArray.length()).map { 
                Exam.fromJson(examsArray.getJSONObject(it)) 
            }
            
            val studentInfoJson = json.optJSONObject("studentInfo")
            val studentInfo = studentInfoJson?.let { StudentInfo.fromJson(it) }
            
            return ExamTimetable(
                hasExams = json.optBoolean("hasExams", false),
                studentInfo = studentInfo,
                exams = exams,
                warningMessage = json.optString("warningMessage", null)
            )
        }
        
        fun fromJsonString(jsonString: String): ExamTimetable? {
            return try {
                fromJson(JSONObject(jsonString))
            } catch (e: Exception) {
                null
            }
        }
        
        fun empty(): ExamTimetable = ExamTimetable(
            hasExams = false,
            studentInfo = null,
            exams = emptyList(),
            warningMessage = null
        )
    }
    
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("hasExams", hasExams)
            put("studentInfo", studentInfo?.toJson())
            put("exams", JSONArray(exams.map { it.toJson() }))
            put("warningMessage", warningMessage)
        }
    }
    
    fun toJsonString(): String = toJson().toString()
    
    /**
     * Get the next upcoming exam
     */
    fun getNextExam(): Exam? {
        return exams
            .filter { it.isUpcoming() }
            .minByOrNull { it.getStartDateTime() ?: LocalDateTime.MAX }
    }
    
    /**
     * Get all upcoming exams sorted by date
     */
    fun getUpcomingExams(): List<Exam> {
        return exams
            .filter { it.isUpcoming() }
            .sortedBy { it.getStartDateTime() ?: LocalDateTime.MAX }
    }
}
