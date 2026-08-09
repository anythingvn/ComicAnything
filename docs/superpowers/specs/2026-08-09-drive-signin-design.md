# Google Drive Sign-In & Token Persistence — Design

**Epic:** [PROJECT_TASKS.md](../../../PROJECT_TASKS.md) — Epic 4: Google Drive Integration Completion (sub-project 1 of 2)

## Goal

Let the user optionally connect their own Google account so the app can read their Drive files, without touching Drive file *browsing* yet (that's sub-project 2, built after this is verified working end-to-end). This replaces Epic 4's originally-scoped "API key" model — which only works for folders explicitly shared as "anyone with the link" — with real OAuth2 sign-in to the user's own private Drive, using Android's Credential Manager / Google Identity Services Authorization API.

This is explicitly an **optional, opt-in feature** — ComicAnything's core identity is local-first and private; Drive support is an extra a user can turn on, not something that runs by default or is required for the app's primary local-file reading experience.

## Scope

- **In scope:** a "Connect Google Drive" trigger on the Drive tab; the OAuth authorization flow requesting read-only Drive access; a signed-in/signed-out UI state (including "Connected as `[email]`"); a disconnect action that clears the locally-cached grant (see Components below for why this sub-project stops short of full server-side revocation); a lightweight local cache of "was connected, as this email" so the UI has something to show immediately (including offline) rather than blocking on a live network check every time; a written prerequisite doc (Google Cloud Console setup) for the project owner to complete on their own.
- **Out of scope (deferred to sub-project 2):** listing/browsing actual Drive files, downloading/streaming comic bytes, wiring a Drive comic into the reader, Drive-specific error states beyond the sign-in flow itself (file-list/download errors belong to sub-project 2).
- **Explicitly not building:** a backend server, refresh-token exchange, or "offline access" (`serverAuthCode`) — those exist to let a *server* keep working on the user's behalf after the user closes the client. This app has no server; the client re-requests a fresh access token from Google Play Services each time it needs one, which Play Services satisfies silently (no UI) as long as the prior grant is still valid.

## Prerequisite: Google Cloud Console setup (for the project owner, not implementation work)

Before sub-project 1 can be manually verified end-to-end on a real device, someone with access to a Google account needs to:

1. Create (or reuse) a project at [console.cloud.google.com](https://console.cloud.google.com).
2. Under **APIs & Services → Library**, enable the **Google Drive API**.
3. Under **APIs & Services → OAuth consent screen**: choose **External** user type (unless using a Google Workspace account with internal-only access), fill in the required app name/support email fields, and add the `https://www.googleapis.com/auth/drive.readonly` scope. While the app is unverified, add your own Google account under **Test users** — Google allows up to 100 test users on an unverified app in "Testing" publishing status, which is sufficient for personal/side-project use without going through full app verification.
4. Under **APIs & Services → Credentials → Create Credentials → OAuth client ID**, choose **Android** as the application type, and supply:
   - The app's package name (`com.comicanything.reader`)
   - The SHA-1 fingerprint of the signing key used for the build that will call this API (for a debug build, `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android` prints it)
5. No client *secret* is needed or generated for the Android client type — the client ID alone is sufficient; Android OAuth clients authenticate via the signing certificate, not a secret.

Per the fetched Android Identity documentation, the sample `AuthorizationRequest.builder().setRequestedScopes(scopes).build()` call for the non-offline-access path (this app's path) takes no explicit client-ID parameter — Android-type OAuth clients are matched by Play Services at runtime against the calling app's package name and signing certificate, not a string constant in code. So once the Cloud Console setup above is done, no further action or configuration value needs to flow from that setup into the codebase — implementation can proceed independent of it, and only needs a real device/emulator with the matching signing key for manual end-to-end verification. (If implementation discovers this app in fact needs a local config file or an explicit client-ID string — API surfaces do shift — treat that as a real finding to fix in this doc, not a silent workaround.)

## Components

### Dependency

```kotlin
implementation("com.google.android.gms:play-services-auth:21.6.0")
```
(Confirm this is still the current stable version at implementation time — Play Services artifacts revise fairly often.)

### Auth flow

Verified against Google's current Android Identity documentation (`developer.android.com/identity/authorization`) rather than assumed from memory, since this API surface has changed across library generations:

```kotlin
val requestedScopes = listOf(Scope("https://www.googleapis.com/auth/drive.readonly"))
val authorizationRequest = AuthorizationRequest.builder()
    .setRequestedScopes(requestedScopes)
    .build()

Identity.getAuthorizationClient(activity)
    .authorize(authorizationRequest)
    .addOnSuccessListener { result ->
        if (result.hasResolution()) {
            // Not yet granted (or grant needs re-confirmation) -- launch the
            // resolution intent to show Google's consent UI.
            val pendingIntent = result.pendingIntent!!
            launcher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
        } else {
            // Already granted -- accessToken is available immediately, no UI shown.
            onAuthorized(result.accessToken)
        }
    }
    .addOnFailureListener { e -> onAuthorizationFailed(e) }
```

The `https://www.googleapis.com/auth/drive.readonly` scope grants read access to all of the user's Drive files — the broader scope needed for sub-project 2's "list existing files" approach, as opposed to the narrower `.../auth/drive.file` scope, which only covers files the app itself created or that the user explicitly picked through a Google Picker UI (not used here, per the earlier design decision to build a simple in-app file list instead of the Picker). The scope is passed as a raw `Scope(...)` string rather than via the `com.google.api.services.drive.DriveScopes` constants class — that class lives in the separate, heavier `google-api-services-drive` Java client library, which isn't otherwise needed here and doesn't match this codebase's existing pattern of raw REST calls through `OkHttpClient` (see `GoogleDriveRepository.kt`, and sub-project 2's planned approach to actually listing/downloading files).

**No `.requestOfflineAccess(serverClientId)`** — that's the backend-refresh-token path this app doesn't need (see Scope above).

**Result handling**, matching this app's existing `MainActivity`-level `registerForActivityResult` pattern (already used for the storage-permission flow in `MainActivity.kt:26-28`):

```kotlin
private val driveAuthLauncher = registerForActivityResult(
    ActivityResultContracts.StartIntentSenderForResult()
) { activityResult ->
    try {
        val result = Identity.getAuthorizationClient(this)
            .getAuthorizationResultFromIntent(activityResult.data)
        viewModel.onDriveAuthorized(result.accessToken)
    } catch (e: ApiException) {
        viewModel.onDriveAuthorizationFailed(e)
    }
}
```

**Silent re-check on each app session** (e.g. when the Drive tab is opened, or on `onResume` alongside the existing storage-permission re-check in `MainActivity.kt:58-64`): call `authorize()` again. If the prior grant is still valid, Play Services returns a fresh access token with `hasResolution() == false` and no UI is shown — this is the mechanism that makes "staying signed in across app restarts" work, with no token persistence of our own required.

**Disconnect (corrected during plan-writing — see [the implementation plan](../plans/2026-08-09-drive-signin.md) for the full account):** `AuthorizationClient.revokeAccess(RevokeAccessRequest)` would fully revoke the grant server-side, but its builder requires `.setAccount(account)` with no access-token-based alternative, and this flow (Authorization only, no separate Credential Manager identity sign-in) has no confirmed way to obtain that `Account` object without adding a whole extra sign-in step — that's a real scope question for a future task, not something to guess at in this sub-project. What's used instead:
```kotlin
Identity.getAuthorizationClient(activity)
    .clearToken(ClearTokenRequest.builder().setToken(lastKnownAccessToken).build())
```
This takes the access token string directly (already held in memory from the last `authorize()` call) and clears Play Services' local cache of the grant — correctly making the app "forget" the connection and preventing the silent `onResume` re-check from finding a still-valid cached token and reconnecting the user against their wishes. It does **not** remove the app from the user's Google Account "Third-party apps with access" list — that's a known, documented limitation of this sub-project, not a bug to silently paper over. Full server-side revocation (resolving the `Account`-object question) is a reasonable candidate for a follow-up task if it matters enough to prioritize.

### Local "last known state" cache (not a token store)

A tiny addition to `ReaderUiState` plus one lightweight persisted field — this does **not** store the access token itself (short-lived, security-sensitive, and meant to be re-fetched, not cached by the app). It only stores what's needed to render the correct UI state instantly on launch, including offline, before a live silent `authorize()` check confirms or corrects it:

```kotlin
data class DriveConnectionHint(
    val isConnected: Boolean,
    val accountEmail: String?
)
```

Persisted via the same DataStore infrastructure Epic 3 introduced (`androidx.datastore:datastore-preferences`, already a dependency) — a new small file (`drive_connection` preferences name, distinct from Epic 3's `reading_progress` store) rather than overloading `ReadingProgressRepository`, since this data has nothing to do with reading progress and shouldn't share that repository's shape or tests.

Flow: on `onDriveAuthorized(accessToken)`, write `DriveConnectionHint(isConnected = true, accountEmail = email)` and on `onDisconnect()`, write `DriveConnectionHint(isConnected = false, accountEmail = null)`. On app launch, read the hint immediately for optimistic UI, then reconcile with a live silent `authorize()` call shortly after.

**Resolved during plan-writing (deferred, not guessed at):** exactly where the signed-in account's email address comes from wasn't confirmed by research for this doc — the fetched `AuthorizationResult` fields covered `accessToken`, `hasResolution()`, `pendingIntent`, and `serverAuthCode`, with no explicit account/email field. It may come from a separate Credential Manager sign-in step (basic identity, run once alongside or before the Drive authorization request), from decoding an ID token, or from a follow-up call to a Google userinfo/People API endpoint using the access token. Rather than guess, [the implementation plan](../plans/2026-08-09-drive-signin.md) has this sub-project always pass a `null` email and show a plain "Connected" state — `accountEmail` in `DriveConnectionHint` is nullable specifically for this fallback. Resolving the real email is left as a well-scoped candidate for a future task, not blocking this one.

### ViewModel / UI wiring

- `ReaderUiState` gains `isDriveConnected: Boolean` and `driveAccountEmail: String?`.
- `ReaderViewModel` gains `onDriveAuthorized(accessToken)`, `onDriveAuthorizationFailed(exception)`, `disconnectDrive()`, and a way to trigger the initial `authorize()` call — since `Identity.getAuthorizationClient(activity)` needs an `Activity`, the *trigger* (analogous to `onRequestPermission` in the existing `HomeScreen`/`MainActivity` wiring) lives as a callback passed down from `MainActivity`, while the *result handling and state updates* live in the ViewModel, matching the existing permission-flow split of responsibilities.
- `DriveContent` (`HomeScreen.kt:308-367`) gets a connected/disconnected branch: disconnected shows a "Connect Google Drive" button (replacing today's always-shown folder-URL text field, which belongs to sub-project 2's browsing UI, not this sub-project); connected shows "Connected as `[email]`" with a disconnect action. The existing folder-URL/search UI is out of scope for this sub-project and gets revisited when sub-project 2 wires up real browsing.

## Testing

Consistent with this project's "real objects, no mocking library" testing posture, and with how Epic 1 handled its own un-unit-testable Android permission flow:

- **Not realistically unit-testable:** the actual `Identity.getAuthorizationClient(...)` calls, the `Activity`-scoped launcher, and the real Google consent UI — these require a live Activity result and Google Play Services, same situation as the storage-permission flow. These are verified manually on a real device/emulator with Play Services (Task 6-equivalent for this sub-project), not covered by JVM unit tests.
- **Unit-testable with real objects:** the new small DataStore-backed hint repository (temp-file-backed `DataStore`, same pattern as `ReadingProgressRepositoryTest`) — round-trips `DriveConnectionHint` correctly, defaults to disconnected when nothing's been saved. `ReaderViewModel`'s state transitions (`onDriveAuthorized`/`onDriveAuthorizationFailed`/`disconnectDrive` correctly updating `isDriveConnected`/`driveAccountEmail`) are unit-testable directly, since they don't touch the Android Identity APIs themselves — the ViewModel methods take already-resolved results (an access token string, an exception, a plain disconnect call) as input, not the `Identity` client itself.
