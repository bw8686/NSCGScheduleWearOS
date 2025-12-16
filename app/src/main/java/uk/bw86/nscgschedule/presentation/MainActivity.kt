/* While this template provides a good starting point for using Wear Compose, you can always
 * take a look at https://github.com/android/wear-os-samples/tree/main/ComposeStarter to find the
 * most up to date changes to the libraries and their usages.
 */

package uk.bw86.nscgschedule.presentation

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
// snapshotFlow intentionally not used due to compatibility; use LaunchedEffect + delay instead
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.tooling.preview.devices.WearDevices
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import android.util.Log
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import uk.bw86.nscgschedule.data.ConnectionStatus
import uk.bw86.nscgschedule.data.DataRepository
import uk.bw86.nscgschedule.data.models.DaySchedule
import uk.bw86.nscgschedule.data.models.Exam
import uk.bw86.nscgschedule.data.models.ExamTimetable
import uk.bw86.nscgschedule.data.models.Lesson
import uk.bw86.nscgschedule.data.models.Timetable
import uk.bw86.nscgschedule.presentation.theme.NSCGScheduleTheme
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import uk.bw86.nscgschedule.schedule.ScheduleMerger

private const val EXTRA_OPEN_KIND = "open_kind"
private const val EXTRA_OPEN_START_MILLIS = "open_start_millis"
private const val EXTRA_OPEN_END_MILLIS = "open_end_millis"

data class OpenRequest(
    val kind: ScheduleMerger.Kind,
    val startMillis: Long,
    val endMillis: Long
)

// Reuse this constant instead of allocating repeatedly
private val DAY_NAMES = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

private fun parseOpenRequest(intent: Intent?): OpenRequest? {
    val kindRaw = intent?.getStringExtra(EXTRA_OPEN_KIND) ?: return null
    val startRaw = intent.getStringExtra(EXTRA_OPEN_START_MILLIS) ?: return null
    val endRaw = intent.getStringExtra(EXTRA_OPEN_END_MILLIS) ?: return null

    val kind = runCatching { ScheduleMerger.Kind.valueOf(kindRaw) }.getOrNull() ?: return null
    val startMillis = startRaw.toLongOrNull() ?: return null
    val endMillis = endRaw.toLongOrNull() ?: return null
    return OpenRequest(kind = kind, startMillis = startMillis, endMillis = endMillis)
}

class MainActivity : ComponentActivity() {
    private val openRequestFlow = MutableStateFlow<OpenRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        openRequestFlow.value = parseOpenRequest(intent)
        setContent {
                WearApp(openRequestFlow = openRequestFlow)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRequestFlow.value = parseOpenRequest(intent)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WearApp(openRequestFlow: MutableStateFlow<OpenRequest?>) {
    val context = LocalContext.current
    val repository = remember { DataRepository.getInstance(context) }
    val scope = rememberCoroutineScope()
    
    val timetable by repository.timetable.collectAsState()
    val examTimetable by repository.examTimetable.collectAsState()
    val lastTimetableSync by repository.lastTimetableSync.collectAsState()
    val lastExamSync by repository.lastExamSync.collectAsState()
    val isLoading by repository.isLoading.collectAsState()
    val connectionStatus by repository.connectionStatus.collectAsState()
    // HorizontalPager for swipe navigation: 0 = Timetable, 1 = Exams
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })

    val openRequest by openRequestFlow.collectAsState()

    var requestedLesson by remember { mutableStateOf<Lesson?>(null) }
    var requestedExam by remember { mutableStateOf<Exam?>(null) }
    
    // Check connection and request data on launch (non-blocking)
    LaunchedEffect(Unit) {
        // Launch in background to avoid blocking UI
        scope.launch {
            repository.checkPhoneConnection()
            // Only request if we have no cached data at all
            if (timetable == null || examTimetable == null) {
                repository.requestDataFromPhone()
            }
        }
    }

    // (debug logging removed to reduce overhead in hot paths)

    val zone = ZoneId.systemDefault()
    var activeExamEvent by remember { mutableStateOf<ScheduleMerger.Event?>(null) }

    // Keep schedule-derived state updated without doing heavy work in recomposition.
    // Also auto-opens the exams page when an exam is active or imminent.
    // NOTE: Removed timetable/examTimetable from dependencies to prevent continuous re-triggering
    LaunchedEffect(Unit) {
        while (true) {
            val nowMillis = System.currentTimeMillis()
            // Capture current state values to avoid race conditions
            val currentTimetable = timetable
            val currentExamTimetable = examTimetable
            val events = ScheduleMerger.buildEvents(
                timetable = currentTimetable,
                examTimetable = currentExamTimetable,
                nowMillis = nowMillis,
                zoneId = zone,
                horizonDays = 14
            )
            val pick = ScheduleMerger.pick(events, nowMillis)
            val current = pick.current
            val next = pick.next

            activeExamEvent = if (current?.kind == ScheduleMerger.Kind.EXAM && current.isActiveAt(nowMillis)) {
                current
            } else {
                null
            }

            val shouldOpenExams = when {
                current?.kind == ScheduleMerger.Kind.EXAM && current.isActiveAt(nowMillis) -> true
                next?.kind == ScheduleMerger.Kind.EXAM && (next.startMillis - nowMillis) <= java.time.Duration.ofHours(4).toMillis() -> true
                else -> false
            }

            if (shouldOpenExams && pagerState.currentPage != 1) {
                // Auto-open exams page when needed
                pagerState.scrollToPage(1)
            }

            // Next check time is aligned with schedule boundaries (start/end), plus the 4h-before-exam threshold.
            val candidateTimes = buildList<Long> {
                current?.endMillis?.let { add(it) }
                next?.startMillis?.let { add(it) }
                if (next?.kind == ScheduleMerger.Kind.EXAM) {
                    add(next.startMillis - java.time.Duration.ofHours(4).toMillis())
                }
            }.filter { it > nowMillis + 1_000 }

            val nextWakeAt = candidateTimes.minOrNull()
            val delayMillis = if (nextWakeAt != null) {
                (nextWakeAt - nowMillis).coerceIn(1_000L, 30 * 60_000L)
            } else {
                30 * 60_000L
            }

            delay(delayMillis)
        }
    }

    // Handle deep-link style opens from tile/complication.
    LaunchedEffect(openRequest) {
        val req = openRequest ?: return@LaunchedEffect
        val nowMillis = System.currentTimeMillis()
        // Capture current state to avoid triggering on every data update
        val currentTimetable = timetable
        val currentExamTimetable = examTimetable
        val events = ScheduleMerger.buildEvents(
            timetable = currentTimetable,
            examTimetable = currentExamTimetable,
            nowMillis = nowMillis,
            zoneId = zone,
            horizonDays = 14
        )

        // Match by exact times if possible, but tolerate small clock/encoding differences by checking overlap
        val match = events.firstOrNull { ev ->
            if (ev.kind != req.kind) return@firstOrNull false
            // Exact match
            if (ev.startMillis == req.startMillis && ev.endMillis == req.endMillis) return@firstOrNull true
            // Tolerant overlap: if requested start time falls within event interval +/- 60s
            val tol = 60_000L
            val reqStart = req.startMillis
            val evStart = ev.startMillis
            val evEnd = ev.endMillis
            return@firstOrNull reqStart in (evStart - tol)..(evEnd + tol)
        }

        when (req.kind) {
            ScheduleMerger.Kind.EXAM -> {
                if (pagerState.currentPage != 1) pagerState.scrollToPage(1)
                requestedExam = match?.exam
            }
            ScheduleMerger.Kind.LESSON -> {
                if (pagerState.currentPage != 0) pagerState.scrollToPage(0)
                requestedLesson = match?.lesson
            }
        }

        // Consume so we don't repeatedly re-open.
        openRequestFlow.value = null
    }

    NSCGScheduleTheme {
    ScreenScaffold(
        timeText = { TimeText() }
    ) {
        Box(
        ) {
            // Loading overlay: only show if we're loading AND we have no cached data to display
            AnimatedVisibility(
                visible = isLoading && timetable == null && examTimetable == null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                        CircularProgressIndicator()
                }
            }
            
            // Main content
            if (!isLoading) {
                if (timetable == null && examTimetable == null) {
                    NoDataScreen(
                        connectionStatus = connectionStatus,
                        lastSync = lastTimetableSync,
                        onRefresh = {
                            scope.launch {
                                repository.requestDataFromPhone()
                            }
                        }
                    )
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Horizontal pager for swipe between pages
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.weight(1f)
                        ) { page ->
                            when (page) {
                                0 -> androidx.compose.runtime.key(lastTimetableSync) {
                                    TimetablePage(
                                        timetable = timetable,
                                        lastTimetableSync = lastTimetableSync,
                                        activeExamEvent = activeExamEvent,
                                        requestedLesson = requestedLesson,
                                        onRequestedLessonConsumed = { requestedLesson = null },
                                        onRefresh = {
                                            scope.launch {
                                                repository.requestDataFromPhone()
                                            }
                                        }
                                    )
                                }
                                1 -> androidx.compose.runtime.key(lastExamSync) {
                                    ExamsPage(
                                        examTimetable = examTimetable,
                                        lastExamSync = lastExamSync,
                                        requestedExam = requestedExam,
                                        onRequestedExamConsumed = { requestedExam = null },
                                        onRefresh = {
                                            scope.launch {
                                                repository.requestDataFromPhone()
                                            }
                                        }
                                    )
                                }
                            }
                        }
                        
                        // Page indicator dots
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            repeat(2) { index ->
                                val isSelected = pagerState.currentPage == index
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 4.dp)
                                        .size(if (isSelected) 8.dp else 6.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
        }
}

@Composable
fun NoDataScreen(
    connectionStatus: ConnectionStatus,
    lastSync: String?,
    onRefresh: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "NSCG",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        
        Text(
            text = "Schedule",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Text(
            text = when (connectionStatus) {
                ConnectionStatus.CONNECTED -> "Connected to phone"
                ConnectionStatus.DISCONNECTED -> "Phone not connected"
                ConnectionStatus.ERROR -> "Connection error"
                ConnectionStatus.UNKNOWN -> "Checking..."
            },
            color = MaterialTheme.colorScheme.secondary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Button(
            onClick = onRefresh,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text("Sync",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
                )
        }
        if (!lastSync.isNullOrEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Last synced: $lastSync",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
fun TimetablePage(
    timetable: Timetable?,
    lastTimetableSync: String?,
    activeExamEvent: ScheduleMerger.Event?,
    requestedLesson: Lesson?,
    onRequestedLessonConsumed: () -> Unit,
    onRefresh: () -> Unit
) {
    val listState = rememberScalingLazyListState()
    val nowDate = java.time.LocalDate.now()
    // Minute tick to force recomposition so time-based logic updates promptly
    val minuteTick = remember { androidx.compose.runtime.mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            minuteTick.value = minuteTick.value + 1
        }
    }
    val todayIndex = nowDate.dayOfWeek.value - 1  // 0-6 for Mon-Sun
    
    var selectedLesson by remember { androidx.compose.runtime.mutableStateOf<Lesson?>(null) }

    LaunchedEffect(requestedLesson) {
        if (requestedLesson != null) {
            selectedLesson = requestedLesson
            onRequestedLessonConsumed()
        }
    }
    // Memoize today's section + lessons (depends only on timetable & date)
    val (todaySectionIndex, todayLessons) = remember(timetable, nowDate) {
        if (timetable == null) Pair(-1, emptyList<Lesson>())
        else {
            val todayName = DAY_NAMES[nowDate.dayOfWeek.value - 1]
            val idx = timetable.days.indexOfFirst { it.getDayName().contains(todayName, ignoreCase = true) }
            val lessons = timetable.days.getOrNull(idx)?.lessons ?: emptyList()
            Pair(idx, lessons)
        }
    }

    // Memoize current/next lesson indices — depend on current time and today's lessons
    val currentLessonIndex = remember(minuteTick.value, todayLessons) {
        val nowTime = java.time.LocalTime.now()
        todayLessons.indexOfFirst { lesson ->
            val start = lesson.getParsedStartTime()
            val end = lesson.getParsedEndTime()
            start != null && end != null && ((nowTime.isAfter(start) && nowTime.isBefore(end)) || (nowTime.isAfter(start.minusMinutes(1)) && nowTime.isBefore(end)))
        }
    }

    val nextLessonIndex = remember(minuteTick.value, todayLessons) {
        val nowTime = java.time.LocalTime.now()
        todayLessons.indexOfFirst { lesson ->
            val start = lesson.getParsedStartTime()
            start != null && start.isAfter(nowTime)
        }
    }

    // Auto-scroll to today's section when timetable loads
    LaunchedEffect(timetable) {
        if (timetable != null && timetable.days.isNotEmpty() && todaySectionIndex >= 0) {
            // Compute the absolute scroll index for this day's list
            var absoluteIndex = 1 // list contains header at position 0
            // Accumulate sizes for all days before 'todaySectionIndex'
            for (i in 0 until todaySectionIndex) {
                absoluteIndex += 1 // day header
                absoluteIndex += 1 // spacing item
                absoluteIndex += (timetable.days.getOrNull(i)?.lessons?.size ?: 0)
            }

            // Now we're at the beginning of today's section
            val dayHeaderIndex = absoluteIndex
            val spacerIndex = dayHeaderIndex + 1
            // The first lesson for today will be after the spacer
            val firstLessonIndex = spacerIndex + 1

            val scrollIndex = when {
                currentLessonIndex >= 0 -> firstLessonIndex + currentLessonIndex
                nextLessonIndex >= 0 -> firstLessonIndex + nextLessonIndex
                todayLessons.isNotEmpty() -> firstLessonIndex
                else -> dayHeaderIndex
            }

            // (reduced logging removed to improve performance)

            // Scroll to the correct lesson (centered if possible)
            val centerIndex = scrollIndex
            // Wait until the list has rendered and has enough items to scroll to before calling scroll
            while (listState.layoutInfo.totalItemsCount <= centerIndex) {
                delay(50)
            }
            listState.scrollToItem(centerIndex)
        }
    }
    
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "TIMETABLE",
                    color = MaterialTheme.colorScheme.secondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 24.dp)
                )
                if (!lastTimetableSync.isNullOrEmpty()) {
                    val parsedTime = try {
                        java.time.LocalDateTime.parse(lastTimetableSync)
                    } catch (e: Exception) {
                        null
                    }
                    val formatted = parsedTime?.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM HH:mm")) ?: lastTimetableSync
                    Text(
                        text = "Updated: $formatted",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
            }
        }
        
        if (timetable == null || timetable.days.isEmpty()) {
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "No lessons",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Sync with phone",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            // Show all days
            timetable.days.forEachIndexed { index, daySchedule ->
                val dayNames = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
                val dayName = daySchedule.getDayName()
                val isToday = dayNames.indexOfFirst { dayName.contains(it, ignoreCase = true) } == todayIndex
                
                item {
                    if (index > 0) Spacer(modifier = Modifier.height(8.dp)) // Reduced gap between days
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = dayName,
                            color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium
                        )
                        if (isToday) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "•",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(2.dp)) } // Reduced gap between lessons
                items(daySchedule.lessons) { lesson ->
                    LessonCard(
                        lesson = lesson,
                        showToday = isToday,
                        activeExamEvent = if (isToday) activeExamEvent else null,
                        onClick = { selectedLesson = lesson },
                        timeTick = minuteTick.value
                    )
                }
            }
        }
        
        // Refresh button
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onRefresh,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier.fillMaxWidth(0.8f)
            ) {
                Text("Refresh Timetable",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center)
            }
        }
        
        // Swipe hint
        item {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Swipe for lessons →",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                fontSize = 10.sp
            )
        }
    }
    
    // Show lesson details dialog
    selectedLesson?.let { lesson ->
        LessonDetailsDialog(
            lesson = lesson,
            onDismiss = { selectedLesson = null }
        )
    }
}

@Composable
fun ExamsPage(
    examTimetable: ExamTimetable?,
    lastExamSync: String?,
    requestedExam: Exam?,
    onRequestedExamConsumed: () -> Unit,
    onRefresh: () -> Unit
) {
    val listState = rememberScalingLazyListState()

    // Low-frequency tick so we can recompute relevant exams periodically without doing it every recomposition
    val minuteTick = remember { androidx.compose.runtime.mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            minuteTick.value = minuteTick.value + 1
        }
    }

    val relevantExams = remember(examTimetable, minuteTick.value) {
        val now = java.time.LocalDateTime.now()
        examTimetable?.exams
            ?.mapNotNull { ex ->
                val start = ex.getStartDateTime() ?: return@mapNotNull null
                val finishTime = ex.getParsedFinishTime() ?: return@mapNotNull null
                val end = java.time.LocalDateTime.of(start.toLocalDate(), finishTime)
                if (end.isAfter(now)) ex else null
            }
            ?.sortedBy { it.getStartDateTime() ?: java.time.LocalDateTime.MAX }
            ?: emptyList()
    }
    
    var selectedExam by remember { androidx.compose.runtime.mutableStateOf<Exam?>(null) }

    LaunchedEffect(requestedExam) {
        if (requestedExam != null) {
            selectedExam = requestedExam
            onRequestedExamConsumed()
        }
    }
    
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "EXAMS",
                    color = MaterialTheme.colorScheme.tertiary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 24.dp)
                )
                if (!lastExamSync.isNullOrEmpty()) {
                    val parsedTime = try {
                        java.time.LocalDateTime.parse(lastExamSync)
                    } catch (e: Exception) {
                        null
                    }
                    val formatted = parsedTime?.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM HH:mm")) ?: lastExamSync
                    Text(
                        text = "Updated: $formatted",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
            }
        }
        
        if (relevantExams.isEmpty()) {
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "No exams",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Nothing scheduled yet",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            item {
                Text(
                    text = "${relevantExams.size} Scheduled",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            
            item { Spacer(modifier = Modifier.height(8.dp)) }
            
            items(relevantExams.take(5)) { exam ->
                ExamCard(
                    exam = exam,
                    onClick = { selectedExam = exam }
                )
            }
        }
        
        // Refresh button
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onRefresh,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier.fillMaxWidth(0.8f)
            ) {
                Text("Refresh Exams",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }
        
        // Swipe hint
        item {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "← Swipe for exams",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                fontSize = 10.sp
            )
        }
    }
    
    // Show exam details dialog
    selectedExam?.let { exam ->
        ExamDetailsDialog(
            exam = exam,
            onDismiss = { selectedExam = null }
        )
    }
}

@Composable
fun LessonCard(
    lesson: Lesson,
    showToday: Boolean = false,
    activeExamEvent: ScheduleMerger.Event? = null,
    onClick: () -> Unit = {},
    timeTick: Int = 0
) {
    // `timeTick` is used to force recomposition each minute from the parent.
    // Read into a local val so the parameter is referenced and doesn't warn as unused.
    val _tickRef = timeTick
    val now = LocalTime.now()
    val startTime = lesson.getParsedStartTime()
    val endTime = lesson.getParsedEndTime()

    val isOverriddenByActiveExam = showToday &&
            activeExamEvent?.kind == ScheduleMerger.Kind.EXAM &&
            startTime != null && endTime != null &&
            run {
                val zone = ZoneId.systemDefault()
                val today = java.time.LocalDate.now()
                val lessonStartMillis = today.atTime(startTime).atZone(zone).toInstant().toEpochMilli()
                val lessonEndMillis = today.atTime(endTime).atZone(zone).toInstant().toEpochMilli()
                // overlap for [start,end)
                !(lessonEndMillis <= activeExamEvent.startMillis || lessonStartMillis >= activeExamEvent.endMillis) &&
                        activeExamEvent.isActiveAt(System.currentTimeMillis())
            }
    
    // Active if within lesson time or starting within 5 minutes (only check if today)
        val isActive = !isOverriddenByActiveExam && showToday && startTime != null && endTime != null &&
            now.isAfter(startTime.minusMinutes(1)) && now.isBefore(endTime)
    
    val isUpcoming = showToday && startTime != null && startTime.isAfter(now)
    
    val minutesUntil = if (isUpcoming && startTime != null) {
        ChronoUnit.MINUTES.between(now, startTime)
    } else null

    // Consider "imminent" lessons (starting within 30 minutes) as highlighted like active lessons
    val isImminent = isUpcoming && minutesUntil != null && minutesUntil < 30
    
    // Use Material ColorScheme
    val cardColor = when {
        isActive -> MaterialTheme.colorScheme.primary
        isUpcoming && minutesUntil != null && minutesUntil < 30 -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val labelColor = if (isActive || isImminent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.secondary
    val textColor = if (isActive || isImminent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val secondaryTextColor = if (isActive || isImminent) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.95f) else MaterialTheme.colorScheme.onSurfaceVariant
    
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = cardColor
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(6.dp)
        ) {
            // Lesson name - allow 2 lines
            Text(
                text = lesson.name,
                color = textColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 16.sp
            )
            
            Spacer(modifier = Modifier.height(3.dp))
            
            // Time and room row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${lesson.startTime} - ${lesson.endTime}",
                    color = secondaryTextColor,
                    fontSize = 10.sp
                )
                
                Text(
                    text = lesson.room,
                    color = labelColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            
            // Time indicator
            if (minutesUntil != null && minutesUntil < 60 && !isActive) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "in ${minutesUntil}m",
                    color = labelColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun ExamCard(exam: Exam, onClick: () -> Unit = {}) {
    // Check if exam is upcoming to highlight it
    val now = java.time.LocalDateTime.now()
    val examDateTime = exam.getStartDateTime()
    val finishTime = exam.getParsedFinishTime()
    val examEndDateTime = if (examDateTime != null && finishTime != null) {
        java.time.LocalDateTime.of(examDateTime.toLocalDate(), finishTime)
    } else null

    val isActive = examDateTime != null && examEndDateTime != null &&
            (now.isEqual(examDateTime) || now.isAfter(examDateTime)) && now.isBefore(examEndDateTime)

    val isUpcoming = examDateTime != null && examDateTime.isAfter(now)
    val hoursUntil = if (isUpcoming && examDateTime != null) {
        java.time.temporal.ChronoUnit.HOURS.between(now, examDateTime)
    } else null
    
    // Use Material ColorScheme - match lesson card style
    val cardColor = when {
        isActive -> MaterialTheme.colorScheme.primary
        isUpcoming && hoursUntil != null && hoursUntil < 24 -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val textColor = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val secondaryTextColor = if (isActive) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.95f) else MaterialTheme.colorScheme.onSurfaceVariant
    val labelColor = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.secondary
    
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = cardColor
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(6.dp)
        ) {
            // Subject name - allow 2 lines
            Text(
                text = exam.subjectDescription,
                color = textColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 16.sp
            )
            
            Spacer(modifier = Modifier.height(3.dp))
            
            // Time and room row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${exam.startTime} - ${exam.finishTime}",
                    color = secondaryTextColor,
                    fontSize = 10.sp
                )
                
                Text(
                    text = exam.examRoom,
                    color = labelColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun LessonDetailsDialog(lesson: Lesson, onDismiss: () -> Unit) {
    androidx.wear.compose.material3.AlertDialog(
        visible = true,
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = lesson.name,
                maxLines = 6
                // Removed maxLines and overflow to allow full wrapping
            )
        },
        content = {
            item {
                Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                    DetailRow(label = "Time", value = "${lesson.startTime} - ${lesson.endTime}")
                    DetailRow(label = "Room", value = lesson.room)
                    if (lesson.teachers.isNotEmpty()) {
                        DetailRow(label = "Teacher", value = lesson.teachers.joinToString(", "))
                    }
                    if (lesson.course.isNotEmpty()) {
                        DetailRow(label = "Course", value = lesson.course)
                    }
                    if (lesson.group.isNotEmpty()) {
                        DetailRow(label = "Group", value = lesson.group)
                    }
                }
            }
        }
    )
}

@Composable
fun ExamDetailsDialog(exam: Exam, onDismiss: () -> Unit) {
    androidx.wear.compose.material3.AlertDialog(
        visible = true,
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = exam.subjectDescription
                // Removed maxLines and overflow to allow full wrapping
            )
        },
        content = {
            item {
                Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                    DetailRow(label = "Date", value = exam.getFormattedDate())
                    DetailRow(label = "Time", value = "${exam.startTime} - ${exam.finishTime}")
                    DetailRow(label = "Pre Room", value = exam.preRoom)
                    DetailRow(label = "Exam Room", value = exam.examRoom)
                    DetailRow(label = "Seat", value = exam.seatNumber)
                    if (exam.paper.isNotEmpty()) {
                        DetailRow(label = "Paper", value = exam.paper)
                    }
                    if (exam.boardCode.isNotEmpty()) {
                        DetailRow(label = "Board", value = exam.boardCode)
                    }
                    if (exam.additional.isNotEmpty()) {
                        DetailRow(label = "Notes", value = exam.additional)
                    }
                }
            }
        }
    )
}

@Composable
fun DetailRow(label: String, value: String) {
    if (value.isNotEmpty()) {
        Column(modifier = Modifier.padding(vertical = 2.dp)) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.secondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = value,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 11.sp
            )
        }
    }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun DefaultPreview() {
    WearApp(openRequestFlow = MutableStateFlow(null))
}