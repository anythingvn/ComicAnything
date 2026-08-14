package com.comicanything.reader

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.ActivityNotFoundException
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.comicanything.reader.data.model.ComicFormat
import com.comicanything.reader.ui.home.HomeScreen
import com.comicanything.reader.ui.home.rememberHomeScreenState
import com.comicanything.reader.ui.reader.EpubReaderScreen
import com.comicanything.reader.ui.reader.ReaderScreen
import com.comicanything.reader.ui.reader.ReaderViewModel
import com.comicanything.reader.ui.theme.ComicAnythingTheme
import com.comicanything.reader.util.StoragePermissions
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope

private const val DRIVE_READONLY_SCOPE = "https://www.googleapis.com/auth/drive.readonly"

class MainActivity : ComponentActivity() {

    private val viewModel: ReaderViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                // No longer passes a `driveAccessToken = { lastAccessToken }` closure over
                // this@MainActivity -- that kept a possibly-destroyed Activity instance reachable
                // from the retained ViewModel, and if the Activity WAS recreated, the closure kept
                // reading the OLD (dead) instance's field forever, going permanently stale. Fresh
                // tokens are now PUSHED into the ViewModel via updateDriveAccessToken() at each of
                // the three places below that obtain one.
                return ReaderViewModel(application) as T
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.setPermissionGranted(granted) }

    private var lastAccessToken: String? = null

    private val driveAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { activityResult ->
        try {
            val result = Identity.getAuthorizationClient(this)
                .getAuthorizationResultFromIntent(activityResult.data)
            lastAccessToken = result.accessToken
            viewModel.updateDriveAccessToken(result.accessToken)
            // accountEmail = null: see the comment in checkDriveAuthorizationSilently() above --
            // resolving the real email is an open question deferred to a future task.
            viewModel.onDriveAuthorized(accountEmail = null)
            Unit
        } catch (e: ApiException) {
            Log.w("MainActivity", "Drive authorization intent failed", e)
            viewModel.onDriveAuthorizationFailed()
        }
    }

    // Launches the OS's own "choose an account" dialog (not a Google Sign-In/Authorization API
    // screen -- a separate, stable Android system UI) so the user can pick which on-device
    // Google account to connect. See switchDriveAccount() for why this is needed at all: a plain
    // authorize() call reuses whatever account already has a valid grant with zero UI, so there
    // was previously no way to ever pick a different one.
    private val chooseAccountLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { activityResult ->
        val accountName = activityResult.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        if (accountName != null) {
            authorizeDriveAccount(Account(accountName, "com.google"))
        }
        // A null accountName means the user backed out of the chooser -- leave whatever
        // connection state already existed untouched rather than treating it as a failure.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Reading mode / color filter / auto-crop are simple global preferences that don't
        // change from outside the app (unlike Drive's connection state), so loading them once
        // here is enough -- no need to repeat this on every onResume().
        viewModel.loadReaderSettings()
        setContent {
            ComicAnythingTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val state by viewModel.uiState.collectAsState()
                    val homeScreenState = rememberHomeScreenState()

                    val activeComic = state.activeComic
                    val closeReader = {
                        viewModel.closeComic()
                        // libraryComics is a separately-scanned list (see
                        // LocalFileRepository.scanStorageDirectories vs listDirectory) -- opening
                        // a comic from Local Files' folder browser mutates a DIFFERENT ComicItem
                        // instance than the one sitting in libraryComics, so the just-persisted
                        // progress never becomes visible to the Continue Reading tab without an
                        // explicit refresh.
                        viewModel.refreshLibrary()
                    }
                    // Without this, the system back gesture/button has no Compose-level handler
                    // anywhere in the app (confirmed: no other BackHandler exists), so it falls
                    // through to the Activity's default behavior and exits the app entirely
                    // instead of closing the open comic first -- surprising and destructive
                    // (loses the "did you mean to leave" moment a reader app should have).
                    BackHandler(enabled = activeComic != null) { closeReader() }
                    when {
                        activeComic == null -> HomeScreen(
                            viewModel = viewModel,
                            homeScreenState = homeScreenState,
                            onOpenComic = { comic -> viewModel.openComic(comic) },
                            onRequestPermission = { requestStoragePermission() },
                            onConnectDrive = { connectDrive() },
                            onDisconnectDrive = { disconnectDrive() },
                            onSwitchDriveAccount = { switchDriveAccount() }
                        )
                        activeComic.format == ComicFormat.EPUB -> EpubReaderScreen(
                            comic = activeComic,
                            viewModel = viewModel,
                            onBack = closeReader
                        )
                        else -> ReaderScreen(
                            comic = activeComic,
                            viewModel = viewModel,
                            onBack = closeReader
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val granted = StoragePermissions.hasAccess(this)
        viewModel.setPermissionGranted(granted)
        if (granted) {
            viewModel.refreshLibrary()
        }
        viewModel.loadDriveConnectionState()
        checkDriveAuthorizationSilently()
    }

    private fun checkDriveAuthorizationSilently() {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_READONLY_SCOPE)))
            .build()
        Identity.getAuthorizationClient(this)
            .authorize(request)
            .addOnSuccessListener { result ->
                if (!result.hasResolution()) {
                    // Grant is still valid -- but this is a SILENT check that runs on every
                    // onResume(), not an explicit user action, so it must only CONFIRM an
                    // already-connected state, never ESTABLISH one from scratch. clearToken()
                    // (see disconnectDrive() below) only clears Play Services' local token
                    // cache; it does not revoke the grant server-side. So after an explicit
                    // disconnect, this authorize() call still succeeds with hasResolution() ==
                    // false on the very next resume -- if it called onDriveAuthorized() here
                    // (which unconditionally sets connected = true and persists it), it would
                    // silently undo the user's disconnect with zero interaction. Calling
                    // onDriveSilentCheckSucceeded() instead defers to the persisted hint: it
                    // only flips the UI to connected if the hint already says connected.
                    //
                    // Still remember the fresh token so disconnectDrive() (below) has something
                    // to clear -- this call, not connectDrive(), is what actually runs on every
                    // app resume, so it's the reliable place to keep lastAccessToken current.
                    lastAccessToken = result.accessToken
                    viewModel.updateDriveAccessToken(result.accessToken)
                    viewModel.onDriveSilentCheckSucceeded()
                } else {
                    // Grant needs interactive re-confirmation (revoked, expired scope, etc).
                    // Per the "never auto-pop consent UI" rule, this silent check does NOT
                    // launch the resolution intent -- it just downgrades the displayed state.
                    // The user can tap "Connect" again to go through the interactive flow.
                    viewModel.onDriveAuthorizationFailed()
                }
            }
            .addOnFailureListener {
                // No network, or no prior grant at all -- leave the optimistic hint as-is
                // rather than forcing a "disconnected" flash on every transient failure.
                // (Deliberately not calling onDriveAuthorizationFailed() here, unlike the
                // hasResolution()==true branch above, since that branch is a definitive
                // "needs re-confirmation" signal and this one is not.)
            }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = StoragePermissions.manageStorageSettingsIntent(this)
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                startActivity(StoragePermissions.manageStorageSettingsFallbackIntent())
            }
        } else {
            requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun connectDrive() {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_READONLY_SCOPE)))
            .build()
        Identity.getAuthorizationClient(this)
            .authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val pendingIntent = result.pendingIntent ?: run {
                        viewModel.onDriveAuthorizationFailed()
                        return@addOnSuccessListener
                    }
                    driveAuthLauncher.launch(
                        IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                    )
                } else {
                    lastAccessToken = result.accessToken
                    viewModel.updateDriveAccessToken(result.accessToken)
                    // accountEmail = null: see the comment in checkDriveAuthorizationSilently().
                    viewModel.onDriveAuthorized(accountEmail = null)
                }
            }
            .addOnFailureListener { e ->
                Log.w("MainActivity", "Drive authorization request failed", e)
                viewModel.onDriveAuthorizationFailed()
            }
    }

    // Shows the OS account chooser, then authorizes against whichever account the user picks --
    // this is the actual fix for "can't switch accounts": a plain authorize() (connectDrive()
    // above) silently reuses whatever account already has a valid grant with zero UI, since
    // clearToken() in disconnectDrive() only clears the LOCAL token cache, not the server-side
    // grant. Explicitly targeting the chosen account via AuthorizationRequest.setAccount()
    // bypasses that silent reuse: if the user picks a different account than the one currently
    // connected, Play Services has no cached grant for it and must show real interactive
    // consent, landing on the new account instead.
    private fun switchDriveAccount() {
        val intent = AccountManager.newChooseAccountIntent(
            null, null, arrayOf("com.google"), null, null, null, null
        )
        chooseAccountLauncher.launch(intent)
    }

    private fun authorizeDriveAccount(account: Account) {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_READONLY_SCOPE)))
            .setAccount(account)
            .build()
        Identity.getAuthorizationClient(this)
            .authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val pendingIntent = result.pendingIntent ?: run {
                        viewModel.onDriveAuthorizationFailed()
                        return@addOnSuccessListener
                    }
                    driveAuthLauncher.launch(
                        IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                    )
                } else {
                    lastAccessToken = result.accessToken
                    viewModel.updateDriveAccessToken(result.accessToken)
                    // Unlike connectDrive()'s accountEmail = null, we genuinely know which
                    // account this is here -- the user just picked it from the chooser.
                    viewModel.onDriveAuthorized(accountEmail = account.name)
                }
            }
            .addOnFailureListener { e ->
                Log.w("MainActivity", "Drive authorization request failed", e)
                viewModel.onDriveAuthorizationFailed()
            }
    }

    private fun disconnectDrive() {
        // AuthorizationClient.revokeAccess(RevokeAccessRequest) exists and would fully revoke
        // the grant on Google's side, but its builder requires .setAccount(account) (confirmed:
        // there is no access-token-based alternative on that request type) -- and this flow,
        // which only performs Authorization (not a separate Credential Manager identity
        // sign-in), has no confirmed way to obtain that Account object without adding a whole
        // extra sign-in step. That's a real scope question for a future task, not something to
        // guess at here.
        //
        // What IS confirmed and usable here is
        // AuthorizationClient.clearToken(ClearTokenRequest.builder().setToken(token).build()) --
        // it takes the access token string directly (which lastAccessToken holds), and clears
        // Play Services' local cache of that grant. This makes disconnectDrive() correctly
        // "forget" the connection for THIS app and prevents the silent onResume re-check from
        // finding a still-valid cached token and reconnecting the user against their wishes --
        // but it does not remove the app from the user's Google Account "Third-party apps with
        // access" list. Note this limitation in the Task 4 verification report; it's an
        // accurate description of what v1 does, not a bug to silently paper over.
        val token = lastAccessToken
        lastAccessToken = null
        viewModel.updateDriveAccessToken(null)
        if (token != null) {
            Identity.getAuthorizationClient(this)
                .clearToken(ClearTokenRequest.builder().setToken(token).build())
                .addOnFailureListener { e -> Log.w("MainActivity", "Drive clearToken failed", e) }
        }
        viewModel.disconnectDrive()
    }
}
