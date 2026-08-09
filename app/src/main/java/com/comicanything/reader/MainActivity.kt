package com.comicanything.reader

import android.Manifest
import android.content.ActivityNotFoundException
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.comicanything.reader.ui.home.HomeScreen
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

    private val viewModel: ReaderViewModel by viewModels()

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
            // accountEmail = null: see the comment in checkDriveAuthorizationSilently() above --
            // resolving the real email is an open question deferred to a future task.
            viewModel.onDriveAuthorized(accountEmail = null)
            Unit
        } catch (e: ApiException) {
            Log.w("MainActivity", "Drive authorization intent failed", e)
            viewModel.onDriveAuthorizationFailed()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ComicAnythingTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val state by viewModel.uiState.collectAsState()

                    if (state.activeComic != null) {
                        ReaderScreen(
                            comic = state.activeComic!!,
                            viewModel = viewModel,
                            onBack = { viewModel.closeComic() }
                        )
                    } else {
                        HomeScreen(
                            viewModel = viewModel,
                            onOpenComic = { comic -> viewModel.openComic(comic) },
                            onRequestPermission = { requestStoragePermission() },
                            onConnectDrive = { connectDrive() },
                            onDisconnectDrive = { disconnectDrive() }
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
                    // Grant is still valid -- reconcile the optimistic hint with the live check,
                    // and remember the fresh token so disconnectDrive() (below) has something
                    // to clear -- this call, not connectDrive(), is what actually runs on every
                    // app resume, so it's the reliable place to keep lastAccessToken current.
                    //
                    // Passing null for the email: per the design spec, resolving the signed-in
                    // account's actual email address wasn't confirmed against current docs
                    // (AuthorizationResult didn't expose one in the fetched reference) and is an
                    // open question for a future task, not this one. DriveConnectionHint's
                    // accountEmail is nullable specifically so the UI degrades to a plain
                    // "Connected" state without a name -- this is that fallback in use, not a
                    // bug. Do not "fix" this by reading back whatever driveAccountEmail already
                    // happens to hold; that would just be echoing stale/absent state, not
                    // resolving a real one.
                    lastAccessToken = result.accessToken
                    viewModel.onDriveAuthorized(accountEmail = null)
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
                    val pendingIntent = result.pendingIntent!!
                    driveAuthLauncher.launch(
                        IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                    )
                } else {
                    lastAccessToken = result.accessToken
                    // accountEmail = null: see the comment in checkDriveAuthorizationSilently().
                    viewModel.onDriveAuthorized(accountEmail = null)
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
        if (token != null) {
            Identity.getAuthorizationClient(this)
                .clearToken(ClearTokenRequest.builder().setToken(token).build())
                .addOnSuccessListener { lastAccessToken = null }
                .addOnFailureListener { e -> Log.w("MainActivity", "Drive clearToken failed", e) }
        }
        viewModel.disconnectDrive()
    }
}
