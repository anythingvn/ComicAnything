package com.comicanything.reader.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.comicanything.reader.data.model.ComicItem
import com.comicanything.reader.data.model.ComicSource
import com.comicanything.reader.data.repository.DriveFileCache
import com.comicanything.reader.data.repository.GoogleDriveRepository
import com.comicanything.reader.data.repository.resolveComicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground service that actually runs Drive downloads. Everything it does is delegated to its
 * own [DefaultDriveDownloadCoordinator], built on [serviceScope] -- a scope that belongs to this
 * Service, not to any Activity/ViewModel, so a download keeps running (and this service keeps
 * showing a progress notification) even if the app is backgrounded or its hosting ViewModel is
 * destroyed. Started (via [ACTION_ENQUEUE_COMIC]/[ACTION_ENQUEUE_FOLDER]/etc. intents, sent by
 * [ServiceBoundDriveDownloadCoordinator]) rather than merely bound, so it survives independent of
 * any client unbinding; it stops itself once [DriveDownloadState] reports nothing in flight.
 */
class DriveDownloadService : Service() {

    inner class LocalBinder : Binder() {
        val coordinator: DriveDownloadCoordinator get() = this@DriveDownloadService.coordinator
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Default)

    @Volatile
    private var currentAccessToken: String? = null

    private val driveRepo = GoogleDriveRepository()

    private lateinit var coordinator: DefaultDriveDownloadCoordinator

    override fun onCreate() {
        super.onCreate()
        val cacheSupplier: () -> DriveFileCache = { DriveFileCache(File(applicationContext.filesDir, "drive_cache")) }
        coordinator = DefaultDriveDownloadCoordinator(
            scope = serviceScope,
            driveFileCache = cacheSupplier,
            driveAccessToken = { currentAccessToken },
            comicFileResolver = { comic, cache, token ->
                if (comic.source == ComicSource.LOCAL) File(comic.pathOrUrl)
                else resolveComicFile(comic, cache(), driveRepo::downloadFile, token)
            },
            fetchFolderContents = driveRepo::fetchFolderContents
        )
        createNotificationChannel()
        observeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must be called within moments of a startForegroundService()-launched start on Android 8+,
        // regardless of which action below actually runs -- observeState() below will immediately
        // demote/stop this service again if that action leaves nothing in flight.
        startForeground(NOTIFICATION_ID, buildNotification(coordinator.state.value))
        if (intent == null) return START_NOT_STICKY
        when (intent.action) {
            ACTION_ENQUEUE_COMIC -> {
                currentAccessToken = intent.getStringExtra(EXTRA_TOKEN)
                extractComic(intent)?.let { coordinator.enqueueComic(it) }
            }
            ACTION_ENQUEUE_FOLDER -> {
                currentAccessToken = intent.getStringExtra(EXTRA_TOKEN)
                intent.getStringExtra(EXTRA_FOLDER_ID)?.let { coordinator.enqueueFolder(it) }
            }
            ACTION_DELETE_CACHE -> intent.getStringExtra(EXTRA_COMIC_ID)?.let { coordinator.deleteCache(it) }
            ACTION_CLEAR_CACHE -> coordinator.clearCache()
            ACTION_CANCEL -> coordinator.cancelAll()
        }
        return START_NOT_STICKY
    }

    // Intent.getSerializableExtra(String, Class<T>) is only available from API 33 -- androidx.core's
    // IntentCompat has no equivalent for Serializable (only for Parcelable), so the two SDK levels
    // are handled directly here instead.
    @Suppress("DEPRECATION")
    private fun extractComic(intent: Intent): ComicItem? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(EXTRA_COMIC, ComicItem::class.java)
        } else {
            intent.getSerializableExtra(EXTRA_COMIC) as? ComicItem
        }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    private fun observeState() {
        serviceScope.launch {
            coordinator.state.collect { state ->
                val isActive = state.downloadingIds.isNotEmpty() || state.downloadingFolderIds.isNotEmpty()
                if (isActive) {
                    getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(state))
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun buildNotification(state: DriveDownloadState): Notification {
        val label = state.currentLabel?.let { " — $it" } ?: ""
        val text = if (state.batchTotal > 0) {
            "Downloading ${(state.batchCompleted + 1).coerceAtMost(state.batchTotal)} of ${state.batchTotal}$label"
        } else {
            "Downloading for offline reading$label"
        }
        val cancelIntent = Intent(this, DriveDownloadService::class.java).setAction(ACTION_CANCEL)
        val cancelPendingIntent = PendingIntent.getService(
            this, 0, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("ComicAnything")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(state.batchTotal, state.batchCompleted, state.batchTotal == 0)
            .addAction(0, "Cancel", cancelPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "drive_downloads"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_ENQUEUE_COMIC = "com.comicanything.reader.action.ENQUEUE_COMIC"
        const val ACTION_ENQUEUE_FOLDER = "com.comicanything.reader.action.ENQUEUE_FOLDER"
        const val ACTION_DELETE_CACHE = "com.comicanything.reader.action.DELETE_CACHE"
        const val ACTION_CLEAR_CACHE = "com.comicanything.reader.action.CLEAR_CACHE"
        const val ACTION_CANCEL = "com.comicanything.reader.action.CANCEL"

        const val EXTRA_COMIC = "comic"
        const val EXTRA_COMIC_ID = "comic_id"
        const val EXTRA_FOLDER_ID = "folder_id"
        const val EXTRA_TOKEN = "token"
    }
}
