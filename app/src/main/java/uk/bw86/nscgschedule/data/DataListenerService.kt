package uk.bw86.nscgschedule.data

import android.util.Log
import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import uk.bw86.nscgschedule.complication.MainComplicationService
import uk.bw86.nscgschedule.tile.MainTileService

/**
 * Service that listens for data changes from the phone app
 * This runs in the background and updates local data storage
 */
class DataListenerService : WearableListenerService() {
    
    companion object {
        private const val TAG = "DataListenerService"
    }
    
    private val repository: DataRepository by lazy { 
        DataRepository.getInstance(applicationContext) 
    }
    
    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)
        Log.d(TAG, "Message received (ignored): ${messageEvent.path}")
        // Messages are not processed anymore due to serialization issues
        // We use application context instead
    }
    
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        super.onDataChanged(dataEvents)
        Log.d(TAG, "Data changed: ${dataEvents.count} events")
        
        dataEvents.forEach { event ->
            if (event.type == com.google.android.gms.wearable.DataEvent.TYPE_CHANGED) {
                Log.d(TAG, "Processing data item: ${event.dataItem.uri}")
                repository.processDataItem(event.dataItem)
            }
        }
        
        // Request tile and complication updates
        requestTileUpdate()
        requestComplicationUpdate()
    }
    
    /**
     * Request an update for the tile
     */
    private fun requestTileUpdate() {
        try {
            TileService.getUpdater(applicationContext)
                .requestUpdate(MainTileService::class.java)
            Log.d(TAG, "Tile update requested")
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting tile update", e)
        }
    }
    
    /**
     * Request an update for complications
     */
    private fun requestComplicationUpdate() {
        try {
            MainComplicationService.requestUpdate(applicationContext)
            Log.d(TAG, "Complication update requested")
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting complication update", e)
        }
    }
}
