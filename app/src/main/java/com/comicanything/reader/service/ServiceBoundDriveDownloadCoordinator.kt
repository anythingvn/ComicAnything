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

    // Set once bound, so refreshCachedStatus (a local disk check, not an actual download) can call
    // straight into the service's own coordinator instead of going through send()'s
    // startForegroundService -- every other action here is a real download/delete that belongs in
    // the foreground service, but startForegroundService unconditionally shows the "Downloading"
    // notification for a moment (onStartCommand calls it before looking at the action), which
    // would flash a spurious notification on every folder browse if reused for this.
    @Volatile
    private var boundCoordinator: DriveDownloadCoordinator? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bound = true
            (binder as? DriveDownloadService.LocalBinder)?.let { localBinder ->
                boundCoordinator = localBinder.coordinator
                scope.launch {
                    localBinder.coordinator.state.collect { _state.value = it }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            boundCoordinator = null
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

    override fun deleteCache(comic: ComicItem) {
        send(DriveDownloadService.ACTION_DELETE_CACHE) {
            putExtra(DriveDownloadService.EXTRA_COMIC, comic)
        }
    }

    override fun enqueueFolder(folderId: String, folderName: String) {
        send(DriveDownloadService.ACTION_ENQUEUE_FOLDER) {
            putExtra(DriveDownloadService.EXTRA_FOLDER_ID, folderId)
            putExtra(DriveDownloadService.EXTRA_FOLDER_NAME, folderName)
            putExtra(DriveDownloadService.EXTRA_TOKEN, accessToken)
        }
    }

    override fun refreshCachedStatus(comics: List<ComicItem>) {
        // Silently skipped if not bound yet (a brief window right after construction) -- the next
        // folder fetch calls this again, so a missed reconciliation here is never permanent.
        boundCoordinator?.refreshCachedStatus(comics)
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
