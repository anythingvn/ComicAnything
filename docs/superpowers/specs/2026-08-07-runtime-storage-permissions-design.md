# Runtime Storage Permissions & Local Files Wiring — Design

**Epic:** [PROJECT_TASKS.md](../../../PROJECT_TASKS.md) — Epic 1: Runtime Permissions & Local Storage Wiring

## Goal

Give the app real, permission-gated access to comic/ebook files on the
device, matching the original spec's promise of automatic scanning across
`/sdcard/Download/` and `/storage/emulated/0/` — and replace the silent
demo-data fallback with an honest UI that reflects actual permission and
scan state.

## Background

The manifest currently declares `READ_EXTERNAL_STORAGE` (maxSdkVersion 32),
`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, and `MANAGE_EXTERNAL_STORAGE`, but
`MainActivity` never requests any of them at runtime. `LocalFileRepository`
scans a single hardcoded path (`/storage/emulated/0/Download`) via
`java.io.File`, and silently falls back to two hardcoded demo `ComicItem`s
whenever the real scan is empty — which is indistinguishable from "no
permission" or "no files" in the UI today. The Local Files tab
(`HomeScreen.kt` `LocalFilesContent`) is entirely static placeholder text.

## Decisions

- **Access model: `MANAGE_EXTERNAL_STORAGE`** ("All files access"), not a
  Storage Access Framework folder picker. This matches the original spec's
  automatic whole-device scan and keeps `LocalFileRepository` on the
  existing `java.io.File` API rather than a `DocumentFile`/URI rewrite.
  Acceptable because this app is privately sideloaded, not distributed via
  Play Store (which restricts this permission for most app categories).
- **API-level branching:**
  - API 24–29 (pre-scoped-storage): request `READ_EXTERNAL_STORAGE` via the
    standard runtime permission dialog.
  - API 30+: `READ_EXTERNAL_STORAGE` cannot grant broad access under scoped
    storage. Request `MANAGE_EXTERNAL_STORAGE` by sending the user to
    Settings; there is no in-app grant dialog for this permission.
- **Drop `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO`** from the manifest. They
  only cover MediaStore-indexed images/videos, not PDF/CBZ/EPUB/MOBI, so
  they add nothing once `MANAGE_EXTERNAL_STORAGE` is in play — just two
  extra dangerous-permission prompts a user has to reason about for no
  benefit.
- **Remove the hardcoded demo-item fallback** in `LocalFileRepository`
  (currently returns "Batman: Year One" / "Solo Leveling Vol 1" whenever
  the real scan is empty). Once permission state is real and surfaced in
  the UI, silently substituting fake data for an empty or inaccessible
  directory is misleading — the UI should show an explicit "permission
  needed" or "no comics found" state instead.

## Components

### `util/StoragePermissions.kt` (new)

Pure, testable helper — no Activity dependency beyond `Context`:

```kotlin
object StoragePermissions {
    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

    fun manageStorageSettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )

    fun manageStorageSettingsFallbackIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
}
```

### `MainActivity.kt` (modify)

- Registers a `registerForActivityResult(ActivityResultContracts.RequestPermission())`
  launcher for the API 24–29 path, forwarding the result to
  `viewModel.setPermissionGranted(granted)`.
- `onResume()` re-checks `StoragePermissions.hasAccess(this)` and reports it
  to the ViewModel — this is how the API 30+ Settings-redirect flow is
  detected (there's no callback; the user backs out of Settings into
  `onResume`).
- Exposes `fun requestStoragePermission()`: on API 30+, starts
  `manageStorageSettingsIntent`, catching `ActivityNotFoundException` to
  fall back to `manageStorageSettingsFallbackIntent`; below API 30, calls
  `requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)`.
  Passed down through `HomeScreen` as a callback for the "Grant access"
  button.

### `ReaderViewModel.kt` (modify)

- `ReaderUiState` gains `hasStoragePermission: Boolean = false`.
- `fun setPermissionGranted(granted: Boolean)`: updates the flag; if it
  transitions from `false`/unknown to `true`, triggers
  `loadLocalLibrary()`. If it transitions to `false`, clears
  `libraryComics` so stale data doesn't linger after a revoke.
- `loadLocalLibrary()` is no longer called from `init {}` — it only runs
  once permission is confirmed.

### `data/repository/LocalFileRepository.kt` (modify)

- `scanStorageDirectories` walks `/storage/emulated/0/` recursively
  (depth-first, `File.walk()`, `maxDepth` bounded — e.g. 8 — to guard
  against pathological symlink structures), matching by extension exactly
  as today, deduplicated by absolute path.
- Remove the demo-item fallback block. Empty input directory ⇒ empty list.

### `ui/home/HomeScreen.kt` (modify)

- New shared composable `PermissionRequiredCard(onRequestPermission: () -> Unit)`:
  explains why storage access is needed, with a "Grant Access" button.
- `LibraryContent` and `LocalFilesContent` both show
  `PermissionRequiredCard` when `!state.hasStoragePermission`, instead of
  their current content.
- `LocalFilesContent` (currently static placeholder text) becomes a real
  grid bound to `state.libraryComics`, reusing the card composable already
  used in `LibraryContent`'s "My Bookshelf" grid. When permission is
  granted but the scan is empty, show "No comics found on this device."

## Testing

- Unit test `StoragePermissions.hasAccess` is not practical without
  Robolectric (Android framework calls) — out of scope here, covered by
  Epic 8's broader instrumented-test pass.
- Unit test `LocalFileRepository.scanStorageDirectories` against a real
  temp directory tree (JVM `java.io.File`, no Android dependency): nested
  subfolders, mixed extensions, dedup, empty-dir ⇒ empty-list (no demo
  fallback).
- Unit test `ReaderViewModel.setPermissionGranted` transitions: false→true
  triggers a load; true→false clears `libraryComics`.
- Manual check on the `comicanything_test` emulator already set up: grant
  All Files Access via Settings, drop a PDF/CBZ into `/Download`, confirm
  it appears in both Library and Local Files tabs; revoke access, confirm
  the permission card reappears.
