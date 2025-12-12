package uk.bw86.nscgschedule.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.android.gms.wearable.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import uk.bw86.nscgschedule.data.models.ExamTimetable
import uk.bw86.nscgschedule.data.models.Timetable
import java.nio.charset.StandardCharsets

/**
 * Repository for managing timetable and exam data
 * Handles data persistence and communication with the phone app
 */
class DataRepository private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "DataRepository"
        private const val PREFS_NAME = "nscg_schedule_data"
        private const val KEY_TIMETABLE = "timetable"
        private const val KEY_EXAM_TIMETABLE = "exam_timetable"
        private const val KEY_TIMETABLE_UPDATED = "timetable_updated"
        private const val KEY_EXAM_UPDATED = "exam_updated"
        private const val KEY_LAST_TIMETABLE_SYNC = "last_timetable_sync"
        private const val KEY_LAST_EXAM_SYNC = "last_exam_sync"
        // Data Layer paths
        const val PATH_TIMETABLE = "/timetable"
        const val PATH_EXAM_TIMETABLE = "/exam_timetable"
        const val PATH_REQUEST_DATA = "/request_data"
        @Volatile
        private var instance: DataRepository? = null
        fun getInstance(context: Context): DataRepository {
            return instance ?: synchronized(this) {
                instance ?: DataRepository(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val dataClient: DataClient = Wearable.getDataClient(context)
    private val messageClient: MessageClient = Wearable.getMessageClient(context)
    private val nodeClient: NodeClient = Wearable.getNodeClient(context)
    
    private val _timetable = MutableStateFlow<Timetable?>(null)
    val timetable: StateFlow<Timetable?> = _timetable.asStateFlow()
    
    private val _examTimetable = MutableStateFlow<ExamTimetable?>(null)
    val examTimetable: StateFlow<ExamTimetable?> = _examTimetable.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _lastTimetableSync = MutableStateFlow<String?>(null)
    val lastTimetableSync: StateFlow<String?> = _lastTimetableSync.asStateFlow()
    private val _lastExamSync = MutableStateFlow<String?>(null)
    val lastExamSync: StateFlow<String?> = _lastExamSync.asStateFlow()
    
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.UNKNOWN)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()
    
    init {
        loadCachedData()
    }
    
    /**
     * Load cached data from SharedPreferences
     */
    private fun loadCachedData() {
        try {
            val timetableJson = prefs.getString(KEY_TIMETABLE, null)
            if (timetableJson != null) {
                _timetable.value = Timetable.fromJsonString(timetableJson)
            }
            val examJson = prefs.getString(KEY_EXAM_TIMETABLE, null)
            if (examJson != null) {
                _examTimetable.value = ExamTimetable.fromJsonString(examJson)
            }
            _lastTimetableSync.value = prefs.getString(KEY_LAST_TIMETABLE_SYNC, null)
            _lastExamSync.value = prefs.getString(KEY_LAST_EXAM_SYNC, null)
            Log.d(TAG, "Loaded cached data")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading cached data", e)
        }
    }
    
    /**
     * Save timetable data to cache
     */
    fun saveTimetable(timetable: Timetable, updated: String? = null) {
        try {
            prefs.edit()
                .putString(KEY_TIMETABLE, timetable.toJsonString())
                .putString(KEY_TIMETABLE_UPDATED, updated)
                .putString(KEY_LAST_TIMETABLE_SYNC, updated ?: java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .apply()
            _timetable.value = timetable
            Log.d(TAG, "Saved timetable with ${timetable.days.size} days")
            val timestamp = updated ?: java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            _lastTimetableSync.value = timestamp
        } catch (e: Exception) {
            Log.e(TAG, "Error saving timetable", e)
        }
    }
    
    /**
     * Save exam timetable data to cache
     */
    fun saveExamTimetable(examTimetable: ExamTimetable, updated: String? = null) {
        try {
            prefs.edit()
                .putString(KEY_EXAM_TIMETABLE, examTimetable.toJsonString())
                .putString(KEY_EXAM_UPDATED, updated)
                .putString(KEY_LAST_EXAM_SYNC, updated ?: java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .apply()
            _examTimetable.value = examTimetable
            Log.d(TAG, "Saved exam timetable with ${examTimetable.exams.size} exams")
            val timestamp = updated ?: java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            _lastExamSync.value = timestamp
        } catch (e: Exception) {
            Log.e(TAG, "Error saving exam timetable", e)
        }
    }
    
    /**
     * Update the last timetable sync time
     */
    fun updateLastTimetableSync(timestamp: String) {
        prefs.edit().putString(KEY_LAST_TIMETABLE_SYNC, timestamp).apply()
        _lastTimetableSync.value = timestamp
    }

    /**
     * Update the last exam sync time
     */
    fun updateLastExamSync(timestamp: String) {
        prefs.edit().putString(KEY_LAST_EXAM_SYNC, timestamp).apply()
        _lastExamSync.value = timestamp
    }
    
    /**
     * Check if phone is connected
     */
    suspend fun checkPhoneConnection(): Boolean {
        return try {
            val nodes = nodeClient.connectedNodes.await()
            val isConnected = nodes.isNotEmpty()
            _connectionStatus.value = if (isConnected) ConnectionStatus.CONNECTED else ConnectionStatus.DISCONNECTED
            Log.d(TAG, "Phone connection status: $isConnected, nodes: ${nodes.size}")
            isConnected
        } catch (e: Exception) {
            Log.e(TAG, "Error checking phone connection", e)
            _connectionStatus.value = ConnectionStatus.ERROR
            false
        }
    }
    
    /**
     * Request data from the phone app
     * Since we use application context, data will be automatically synced
     * This method just checks the connection and waits for context updates
     */
    suspend fun requestDataFromPhone() {
        _isLoading.value = true
        try {
            val isConnected = checkPhoneConnection()
            if (!isConnected) {
                Log.w(TAG, "No connected nodes found")
                _isLoading.value = false
                return
            }
            
            // Try to get the current application context
            try {
                val dataItems = dataClient.dataItems.await()
                
                Log.d(TAG, "Found ${dataItems.count} data items")
                
                dataItems.forEach { dataItem ->
                    processDataItem(dataItem)
                }
                
                dataItems.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching data items", e)
            }
            
            Log.d(TAG, "Data request completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting data from phone", e)
            _connectionStatus.value = ConnectionStatus.ERROR
        } finally {
            _isLoading.value = false
        }
    }
    
    /**
     * Process incoming message from the phone (not used anymore - using context instead)
     */
    fun processMessage(messageEvent: MessageEvent) {
        Log.d(TAG, "Processing message from path: ${messageEvent.path}")
        // Messages are no longer used, we use application context instead
    }
    
    /**
     * Process data item from the Data Layer API
     */
    fun processDataItem(dataItem: DataItem) {
        Log.d(TAG, "Processing data item from path: ${dataItem.uri.path}")
        
        try {
            val dataMapItem = DataMapItem.fromDataItem(dataItem)
            val dataMap = dataMapItem.dataMap
            
            // Check if we have JSON data
            if (dataMap.containsKey("json")) {
                val jsonString = dataMap.getString("json", "")
                if (jsonString.isNotEmpty()) {
                    try {
                        val json = JSONObject(jsonString)
                        
                        // Process timetable data
                        if (json.has("timetable")) {
                            val timetableJson = json.getJSONObject("timetable")
                            val updated = json.optString("timetableUpdated", "")
                            val timetable = Timetable.fromJson(timetableJson)
                            saveTimetable(timetable, updated)
                            Log.d(TAG, "Processed timetable with ${timetable.days.size} days")
                        }
                        
                        // Process exam timetable data
                        if (json.has("examTimetable")) {
                            val examJson = json.getJSONObject("examTimetable")
                            val updated = json.optString("examUpdated", "")
                            val examTimetable = ExamTimetable.fromJson(examJson)
                            saveExamTimetable(examTimetable, updated)
                            Log.d(TAG, "Processed exam timetable with ${examTimetable.exams.size} exams")
                        }
                        
                        Log.d(TAG, "Successfully processed data from JSON")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing JSON data", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing data item", e)
        }
    }
    
    /**
     * Convert DataMap to JSONObject recursively (deprecated - now using JSON strings)
     */
    private fun dataMapToJson(dataMap: DataMap): JSONObject {
        val json = JSONObject()

        for (key in dataMap.keySet()) {
            val value: Any? = when {
                dataMap.containsKey(key) && dataMap.getString(key) != null ->
                    dataMap.getString(key)

                dataMap.containsKey(key) && dataMap.getInt(key) != 0 ->
                    dataMap.getInt(key)

                dataMap.containsKey(key) && dataMap.getLong(key) != 0L ->
                    dataMap.getLong(key)

                dataMap.containsKey(key) && dataMap.getDouble(key) != 0.0 ->
                    dataMap.getDouble(key)

                dataMap.containsKey(key) && dataMap.getBoolean(key) != false ->
                    dataMap.getBoolean(key)

                dataMap.containsKey(key) && dataMap.getDataMap(key) != null ->
                    dataMap.getDataMap(key)

                dataMap.containsKey(key) && dataMap.getStringArrayList(key) != null ->
                    dataMap.getStringArrayList(key)

                dataMap.containsKey(key) && dataMap.getDataMapArrayList(key) != null ->
                    dataMap.getDataMapArrayList(key)

                else -> null
            }

            when (value) {
                is String -> json.put(key, value)
                is Int -> json.put(key, value)
                is Long -> json.put(key, value)
                is Double -> json.put(key, value)
                is Boolean -> json.put(key, value)
                is DataMap -> json.put(key, dataMapToJson(value))

                is ArrayList<*> -> {
                    val array = JSONArray()
                    for (item in value) {
                        when (item) {
                            is String -> array.put(item)
                            is Int -> array.put(item)
                            is Long -> array.put(item)
                            is Double -> array.put(item)
                            is Boolean -> array.put(item)
                            is DataMap -> array.put(dataMapToJson(item))
                        }
                    }
                    json.put(key, array)
                }
            }
        }

        return json
    }


    /**
     * Get the current timetable (cached)
     */
    fun getCurrentTimetable(): Timetable? = _timetable.value
    
    /**
     * Get the current exam timetable (cached)
     */
    fun getCurrentExamTimetable(): ExamTimetable? = _examTimetable.value
    
    /**
     * Clear all cached data
     */
    fun clearCache() {
        prefs.edit().clear().apply()
        _timetable.value = null
        _examTimetable.value = null
        _lastTimetableSync.value = null
        _lastExamSync.value = null
    }
}

enum class ConnectionStatus {
    UNKNOWN,
    CONNECTED,
    DISCONNECTED,
    ERROR
}
