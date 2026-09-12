package com.comicanything.reader.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.comicanything.reader.data.model.ComicItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The real, production [DriveDownloadCoordinator]. Every enqueue/delete/clear call is sent as an
 * Intent that starts [DriveDownloadService] as a foreground service, so the actual download runs
 * on the SERVICE's own coroutine scope -- independent of whichever ViewModel/Activity created
 * this coordinator -- and can keep running, with a visible progress notification, after the app
 * is backgrounded or this process's Activity is destroyed.
 *
 * Separately binds to the service purely to mirror its live [DriveDownloadState] into [state]
 * while this coordinator is alive; that binding plays no part in keeping the service itself
 * running -- see [DriveDownloadService]'s doc comment.
 */
class ServiceBoundDriveDownloadCoordinator(private val context: Context) : DriveDownloadCoordinator {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(DriveDownloadState())
    override val state: StateFlow<DriveDownloadState> = _state.asStateFlow()

    @Volatile
    private var accessToken: String? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bound = true
            (binder as? DriveDownloadService.LocalBinder)?.let { localBinder ->
                scope.launch {
                    localBinder.coordinator.state.collect { _state.value = it }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
        }
    }

    init {
        context.bindService(Intent(context, DriveDownloadService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    private fun send(action: String, configure: Intent.() -> Unit = {}) {
        val intent = Intent(context, DriveDownloadService::class.java).setAction(action)
        intent.configure()
        ContextCompat.startForegroundService(context, intent)
    }

    override fun enqueueComic(comic: ComicItem) {
        send(DriveDownloadService.ACTION_ENQUEUE_COMIC) {
            putExtra(DriveDownloadService.EXTRA_COMIC, comic)
            putExtra(DriveDownloadService.EXTRA_TOKEN, accessToken)
        }
    }

    override fun deleteCache(comicId: String) {
        send(DriveDownloadService.ACTION_DELETE_CACHE) {
            putExtra(DriveDownloadService.EXTRA_COMIC_ID, comicId)
        }
    }

    override fun enqueueFolder(folderId: String) {
        send(DriveDownloadService.ACTION_ENQUEUE_FOLDER) {
            putExtra(DriveDownloadService.EXTRA_FOLDER_ID, folderId)
            putExtra(DriveDownloadService.EXTRA_TOKEN, accessToken)
        }
    }

    override fun clearCache() {
        send(DriveDownloadService.ACTION_CLEAR_CACHE)
    }

    override fun cancelAll() {
        send(DriveDownloadService.ACTION_CANCEL)
    }

    override fun updateAccessToken(token: String?) {
        accessToken = token
    }

    override fun close() {
        if (bound) {
            context.unbindService(connection)
            bound = false
        }
        scope.cancel()
    }
}
