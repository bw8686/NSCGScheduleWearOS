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
            val exam = Exam(
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

            // Prime parsed caches so parsing happens once at model creation
            exam.getParsedDate()
            exam.getParsedStartTime()
            exam.getParsedFinishTime()
            // Also prime computed epoch values
            exam.getStartDateTime()
            exam.getStartMillisZone()
            exam.getEndMillisZone()

            return exam
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
        return parsedDateCache
    }
    
    /**
     * Parse the start time string to LocalTime
     */
    fun getParsedStartTime(): LocalTime? {
        return parsedStartTimeCache
    }

    /**
     * Parse the finish time string to LocalTime
     */
    fun getParsedFinishTime(): LocalTime? {
        return parsedFinishTimeCache
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
                return parsed
            } catch (_: DateTimeParseException) {
                // continue
            } catch (e: Exception) {
                // ignore
            }
        }

        val withSpaceAmPm = cleanTime.replace(Regex("(?i)(am|pm)$"), " $1")
        if (withSpaceAmPm != cleanTime) {
            for (pattern in patterns) {
                try {
                    val formatter = DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)
                    val parsed = LocalTime.parse(withSpaceAmPm.uppercase(Locale.ENGLISH), formatter)
                    return parsed
                } catch (_: DateTimeParseException) {
                    // continue
                } catch (e: Exception) {
                    // ignore
                }
            }
        }

        return null
    }

    // Cached parsed values to avoid repeated parsing during UI recomposition
    private val parsedDateCache: LocalDate? by lazy {
        if (date.isBlank()) return@lazy null
        val raw = date.trim()
        val parts = raw.split('-', '/').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size != 3) return@lazy null

        try {
            val (a, b, c) = parts
            if (a.length == 4) {
                // YYYY-MM-DD
                LocalDate.of(a.toInt(), b.toInt(), c.toInt())
            } else {
                // DD-MM-YYYY
                LocalDate.of(c.toInt(), b.toInt(), a.toInt())
            }
        } catch (e: Exception) {
            null
        }
    }

    private val parsedStartTimeCache: LocalTime? by lazy {
        parseTimeString(startTime, label = "start")
    }

    private val parsedFinishTimeCache: LocalTime? by lazy {
        parseTimeString(finishTime, label = "finish")
    }
    
    /**
     * Get the full date and time of the exam start
     */
    fun getStartDateTime(): LocalDateTime? {
        val pd = getParsedDate() ?: return null
        val pt = getParsedStartTime() ?: return null
        return LocalDateTime.of(pd, pt)
    }

    // Cached epoch millis for the exam start/end to avoid recomputing during UI renders
    private val startEpochCache: Long? by lazy {
        val dt = getStartDateTime() ?: return@lazy null
        dt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private val endEpochCache: Long? by lazy {
        val pd = getParsedDate() ?: return@lazy null
        val ft = getParsedFinishTime() ?: return@lazy null
        LocalDateTime.of(pd, ft).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    fun getStartMillisZone(zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long? {
        // If caller uses system default zone, we can return cached value
        return if (zone == java.time.ZoneId.systemDefault()) startEpochCache else getStartDateTime()?.atZone(zone)?.toInstant()?.toEpochMilli()
    }

    fun getEndMillisZone(zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long? {
        return if (zone == java.time.ZoneId.systemDefault()) endEpochCache else getParsedDate()?.let { d -> getParsedFinishTime()?.let { ft -> LocalDateTime.of(d, ft).atZone(zone).toInstant().toEpochMilli() } }
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
