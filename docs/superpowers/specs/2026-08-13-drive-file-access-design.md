# Google Drive File Access & Browsing — Design

**Epic:** [PROJECT_TASKS.md](../../../PROJECT_TASKS.md) — Epic 4 sub-project 2: Google Drive Integration Completion

## Goal

Make Google Drive comics actually readable. Today, once a user connects their Drive account (Epic 4 sub-project 1 — real OAuth2 sign-in via Android's Identity Authorization API, already built), there is no way to browse their Drive or open anything from it: the only entry point is a text field where the user pastes one folder URL/ID, which is fetched with an old, unused `apiKey` parameter that's never actually supplied — so it silently falls through to a hardcoded public-PDF demo item every time, regardless of connection state. Even if that query worked, tapping any resulting item would fail immediately, because `createPageSource` unconditionally throws `UnsupportedFormatException` for any comic whose `source != ComicSource.LOCAL`, and `openEpubComic` constructs `File(comic.pathOrUrl)` directly, assuming a local path — neither has any concept of downloading a remote file first.

This closes both gaps end-to-end: real Drive folder browsing (navigate from "My Drive" down through subfolders, see files alongside folders, not just one pasted folder's flat file list) and real file access (download once to a persistent local cache using the real OAuth token, then open through the exact same decoders every local comic already uses).

## Scope

**In scope:**
- Replace the unused `apiKey` query-param auth in `GoogleDriveRepository` with the real OAuth Bearer token from sub-project 1's sign-in flow.
- Widen the Drive query to include subfolders and EPUB/CBR files (the current query predates both formats and excludes folders entirely).
- A real folder browser: start at Drive root once connected, navigate into subfolders and back out via a breadcrumb trail, with the existing paste-a-link field demoted to an optional "jump to folder" shortcut.
- A shared local-file resolution step used by both the PDF/CBZ/CBR path (`createPageSource`) and the EPUB path (`openEpubComic`): for a `LOCAL` comic, an instant passthrough (today's behavior, unchanged); for a `GOOGLE_DRIVE` comic, a cache-first lookup that downloads via the real Drive API only on a cache miss.
- A persistent, size-capped local cache for downloaded Drive files, so a comic opened once can be reopened later — including offline — without a network call or a live token.
- Distinct error/empty states: not-connected, folder empty, network failure (with retry), and a download failure when opening a specific comic — replacing today's single "No Drive folder linked yet" text and the silent demo-item fallback.

**Explicitly out of scope:**
- True byte-range/progressive streaming. Every download is a full fetch to the local cache before the comic opens, mirroring the "extract once, then reuse existing decoders" pattern this project already used for EPUB and CBR. A large file means a longer spinner before the first page shows, not a new decode pipeline.
- Cache invalidation when a Drive file is edited/replaced server-side (same file ID, new content). v1 treats a cache hit as authoritative — if the user needs the latest version, they'd need to clear the app's storage. A "force re-download" affordance is a reasonable, cheap follow-up but isn't required to make Drive comics readable.
- Real download-progress UI (percentage/bytes). v1 reuses the existing brief-spinner pattern already used for CBR/EPUB's extraction wait.
- Server-side grant revocation, resolving the signed-in account's real email — both already tracked as sub-project 1 follow-ups in `PROJECT_TASKS.md`, unaffected by this sub-project.
- Full end-to-end on-device verification is expected to hit the same blocker sub-project 1 already found: `Identity.getAuthorizationClient(...).authorize(...)` returns `ApiException: 8` on the `comicanything_test` emulator's outdated Play Services build. Code and unit tests can proceed regardless; genuine on-device confirmation of a real download/cache/offline-reopen cycle may need a newer emulator image or a physical device — this is a pre-existing environment limitation, not something this sub-project can fix.

## Components

### `GoogleDriveRepository` changes

- `fetchFolderContents(folderId: String, accessToken: String?): List<DriveEntry>` — replaces the `apiKey: String?` parameter with `accessToken: String?`, sent as an `Authorization: Bearer <token>` header instead of a `key=` query parameter. A `null`/blank token returns an empty list immediately (the caller is responsible for showing a "not connected" state — see Error Handling) rather than falling through to demo data; the hardcoded public-PDF fallback item is removed entirely.
- The query string widens from `mimeType = 'application/pdf' or mimeType = 'application/zip' or name contains '.pdf' or name contains '.cbz'` to also match `mimeType = 'application/vnd.google-apps.folder'` (subfolders) and `name contains '.epub' or name contains '.cbr'` (the two formats added since this query was written).
- New sealed result type:
  ```kotlin
  sealed interface DriveEntry {
      data class Folder(val id: String, val name: String) : DriveEntry
      data class ComicFile(val comic: ComicItem) : DriveEntry
  }
  ```
  `fetchFolderContents` returns entries sorted folders-first, each group alphabetical by name — a single ordered list is simplest for the UI to render as one list, and matches standard file-browser convention (folders before files).
- New `downloadFile(fileId: String, destination: File, accessToken: String)` (suspend): issues an authenticated `GET https://www.googleapis.com/drive/v3/files/{fileId}?alt=media` request (the Drive API v3 file-content endpoint — not the legacy `webContentLink` field, which isn't reliable for private files) and streams the response body to `destination`. Throws a new `DriveDownloadException(message: String, cause: Throwable? = null)` on any failure (HTTP error, network error, empty/unreadable body).

### `DriveFileCache` (new)

Manages a persistent cache directory under `Application.filesDir` (deliberately not `cacheDir` — Android can wipe `cacheDir` under storage pressure without warning, which would silently break the "read offline later" promise that's the whole point of this cache), keyed by `comic.id` (the Drive file ID, which `GoogleDriveRepository` already uses as `ComicItem.id` — globally unique per file).

- `fun cachedFile(comicId: String): File?` — returns the cached file if present, else `null`. A cache entry is only ever considered valid once fully written (see below), so a present result is always safe to open immediately with no further I/O.
- `suspend fun download(comicId: String, download: suspend (File) -> Unit): File` — downloads to a `<comicId>.part` file first via the passed `download` lambda (which `resolveComicFile` supplies as a call to `GoogleDriveRepository.downloadFile`), then renames it to the final `<comicId>` filename only on success. A failed or killed download never leaves a corrupt file that a later `cachedFile` lookup would mistake for a valid cache hit — the `.part` suffix is never checked by `cachedFile`.
- LRU-by-total-size eviction, capped at **750MB** by default (typical comic archives run 20–200MB; this allows a handful of cached comics without unbounded growth). On each successful download, if the cache directory's total size exceeds the cap, delete least-recently-*opened* files (tracked via `File.lastModified()`, touched on every `cachedFile` hit) until back under the cap. Runs synchronously after each download completes, not on a separate schedule.

### `resolveComicFile` (new, suspend)

```kotlin
suspend fun resolveComicFile(
    comic: ComicItem,
    driveCache: DriveFileCache,
    downloadDriveFile: suspend (fileId: String, destination: File, accessToken: String) -> Unit,
    accessToken: () -> String?
): File
```

- `comic.source == ComicSource.LOCAL`: returns `File(comic.pathOrUrl)` immediately — no I/O, byte-for-byte today's behavior.
- `comic.source == ComicSource.GOOGLE_DRIVE`: checks `driveCache.cachedFile(comic.id)` first; on a hit, returns it with no network call at all (this is what makes a previously-opened Drive comic reopen instantly and work offline). On a miss, calls `accessToken()` — if `null`, throws `DriveDownloadException("Not connected to Google Drive")` immediately, no network attempt — otherwise calls `driveCache.download(comic.id) { dest -> downloadDriveFile(comic.id, dest, token) }` and returns the result.
- `downloadDriveFile` takes a lambda rather than a concrete `GoogleDriveRepository`, deliberately mirroring `DriveFileCache.download`'s own shape and the existing `epubExtractor`/`thumbnailDecoder` injected-function-reference pattern on `ReaderViewModel` — the real default is `driveRepo::downloadFile`, and tests can substitute a fake without needing `GoogleDriveRepository` (a concrete class, not an interface) to be subclassed or mocked.

### `ReaderViewModel` / `ComicPageSource.kt` integration

- `createPageSource(comic: ComicItem, cbrCacheRoot: () -> File, file: File): ComicPageSource` — signature changes to take the already-resolved `File` as a parameter instead of deriving it from `comic.pathOrUrl` internally. The `if (comic.source != ComicSource.LOCAL) throw UnsupportedFormatException(...)` guard is removed entirely — resolution has already validated the file exists (of either source) before this function is ever called, so `createPageSource` goes back to being purely a format dispatcher, with no notion of source at all. Its `when (comic.format)` branches use `file` directly (`PdfPageSource(file)`, `CbzPageSource(file)`, `CbrPageSource(file, File(cbrCacheRoot(), comic.id))`) instead of reconstructing `File(comic.pathOrUrl)`.
- `openComic`: resolves via `resolveComicFile` first (inside the existing `withContext(ioDispatcher) { ... }` block, wrapped in the same try/catch that already handles `UnsupportedFormatException`/`CancellationException`/generic `Exception` — `DriveDownloadException` joins that same catch-and-surface-to-`pageLoadError` path with no new UI pattern), then passes the resolved file into `createPageSource`.
- `openEpubComic`: same shape — resolves first, then calls `epubExtractor(resolvedFile, extractionDir)` instead of `epubExtractor(File(comic.pathOrUrl), extractionDir)`.
- New constructor properties on `ReaderViewModel`, following the exact lazy-supplier / injected-function-reference pattern `epubCacheRoot`/`cbrCacheRoot`/`epubExtractor` already establish:
  - `driveFileCache: DriveFileCache = DriveFileCache(File(application.filesDir, "drive_cache"))`
  - `driveAccessToken: () -> String? = { null }` — deliberately defaults to `null` rather than reading anything from `Application`, since the real token is Activity-scoped (see below). Every existing PDF/CBZ/EPUB/CBR test that never opens a `GOOGLE_DRIVE` comic is unaffected by this default, exactly like `cbrCacheRoot`'s laziness protected non-CBR tests in Epic 6.
  - `resolveComicFile: suspend (ComicItem, DriveFileCache, () -> String?) -> File = ::resolveComicFile` (the top-level function, bound with `driveRepo::downloadFile` as its `downloadDriveFile` argument) — injected the same way `epubExtractor` is, so a test can substitute a fake resolver without needing a real network call or a real `GoogleDriveRepository` fake.
- `fetchDriveFolder(folderUrlOrId: String, apiKey: String? = null)` is replaced by two methods:
  - `navigateDriveFolder(folderId: String, name: String)` — calls `driveRepo.fetchFolderContents(folderId, driveAccessToken())`, updates `driveEntries` and pushes `(folderId, name)` onto the breadcrumb stack.
  - `navigateDriveUp(toIndex: Int)` — truncates the breadcrumb stack back to `toIndex` and re-fetches that level's contents.
  - Connecting (`onDriveAuthorized`) auto-calls `navigateDriveFolder("root", "My Drive")` — Drive API v3's literal folder ID `"root"` refers to "My Drive" with no special-casing needed.
  - The existing paste-a-link field still works: parsing it through the existing `extractFolderId` and calling `navigateDriveFolder(extractedId, name = extractedId)` (no extra API call to resolve the pasted folder's real display name — it shows as a raw ID/URL fragment in the breadcrumb, a reasonable v1 tradeoff since resolving it would need a second Drive API round-trip for a rarely-used shortcut path).

### `ReaderUiState` changes

- `driveComics: List<ComicItem>` is replaced by `driveEntries: List<DriveEntry>` (folders and files together, as returned by the repository).
- New `driveBreadcrumbs: List<DriveBreadcrumb>` where `data class DriveBreadcrumb(val folderId: String, val name: String)` — the last entry is always the current folder; tapping any earlier entry calls `navigateDriveUp(toIndex)`.
- New `driveError: String?` — distinct from `pageLoadError` (which is about *opening a specific comic*, not about *browsing folders*), so a folder-fetch failure and a comic-open failure never overwrite each other's message.
- `isLoadingDrive: Boolean` is reused unchanged for folder-navigation loading state.

### `MainActivity` — token handoff

The OAuth access token (`lastAccessToken`) is Activity-scoped — it comes from `Identity.getAuthorizationClient(this)`, refreshed on every `onResume()` — so it can't be a constructor-default lambda the way `cbrCacheRoot`/`epubCacheRoot` are (those only ever need `Application`, which any `AndroidViewModel` already has). `MainActivity` switches from plain `by viewModels()` to a small custom `ViewModelProvider.Factory` that constructs `ReaderViewModel` with `driveAccessToken = { lastAccessToken }` — a real constructor-injected lambda closing over the Activity's private field, keeping the same "inject a lazy supplier" shape used everywhere else in this codebase rather than introducing a new imperative `viewModel.updateToken(...)` setter with its own staleness questions.

### `DriveContent` UI changes

- A tappable breadcrumb row ("My Drive > Comics > Volume 1") replaces the static "Connected as X" row as the primary navigation surface; the connect/disconnect affordance moves alongside it (still visible, just no longer the row's only content).
- The paste-a-link field remains, visually demoted (e.g. a smaller field behind a "jump to folder" label) since breadcrumb navigation is now the primary path.
- The entry list renders `DriveEntry.Folder` rows with a folder icon + chevron (tap → `navigateDriveFolder`) and `DriveEntry.ComicFile` rows with the existing book icon (tap → `onOpenComic`, unchanged), in the order the repository already sorted them.
- Empty/error states: "Connect your Google Drive to browse it" (not connected), "This folder is empty" (connected, zero entries), "Couldn't load this folder — network error" with a retry action (connected, `driveError != null`) — replacing today's single static message.

## Error Handling

- **Folder navigation failure** (network error, malformed folder ID, Drive API error): caught in `navigateDriveFolder`/`navigateDriveUp`, sets `driveError` to a message distinguishing "network" from "folder not found/inaccessible" where the HTTP response tells us which; `driveEntries` is left as whatever it was (doesn't clear a previously-successful listing just because a retry failed).
- **Opening a Drive comic that fails to download**: `DriveDownloadException` is caught by `openComic`/`openEpubComic`'s existing generic-exception handling and surfaces through `pageLoadError`, identical in shape to how a corrupt local file already fails today — no new UI pattern, no crash.
- **Token absent** (never connected, or `lastAccessToken` is `null` because the silent re-check on `onResume()` hasn't run yet or failed): `resolveComicFile` fails fast with a clear "Not connected to Google Drive" message rather than attempting a request that will 401.
- **Partial/interrupted download** (app backgrounded mid-download, network drop): the `.part`-file-then-rename pattern in `DriveFileCache.download` guarantees a half-written file is never mistaken for a valid cache entry on a later open attempt — the next open simply re-downloads from scratch.

## Testing

- `GoogleDriveRepository`: the Bearer-auth header construction and the widened query string are pure request-building logic, directly unit-testable (matches how `extractFolderId` is already tested today). The actual network call isn't unit-tested, same limitation as every other repository in this project.
- `DriveFileCache`: fully real-object testable against a real temp directory — cache hit/miss, the `.part`-then-rename atomicity (simulate a failed `download` lambda and confirm no `.part` or final file is left behind), and LRU eviction at the 750MB cap (using small fake files sized to trigger eviction without needing genuinely large test data).
- `resolveComicFile`: the `LOCAL` passthrough and the `GOOGLE_DRIVE` cache-hit path are directly testable with real objects. The cache-miss/download path needs an injectable download function — mirroring the existing `epubExtractor`/`thumbnailDecoder` injected-lambda pattern already used in `ReaderViewModel`'s constructor for exactly this kind of untestable-in-plain-JVM real I/O.
- `ReaderViewModel`'s new `navigateDriveFolder`/`navigateDriveUp` breadcrumb push/pop logic is testable with a fake `driveRepo` returning canned `DriveEntry` lists, following this project's established no-mocking, real-fake-object convention.
- On-device verification is expected to be gated by sub-project 1's pre-existing `ApiException: 8` blocker on this emulator, as noted in Scope above — this sub-project's plan should still include an on-device verification task attempting the full flow, documenting whatever can and can't be confirmed on this specific emulator, consistent with how sub-project 1's own verification pass was documented as partially blocked rather than skipped.
