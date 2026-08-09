# EPUB Reading Support — Design

**Epic:** [PROJECT_TASKS.md](../../../PROJECT_TASKS.md) — Epic 5: EPUB & MOBI Support (sub-project 1 of 2)

## Goal

Make `.epub` files, which the library already scans and tags but which currently just show "This format isn't supported yet" when opened, actually readable: extract the EPUB, render its content as continuously-scrollable, styled text via a WebView, and persist reading progress as a percentage (page numbers don't meaningfully apply to reflowable text).

## Scope

- **In scope:** extracting and parsing a local `.epub` file (container.xml → OPF manifest/spine), rendering the full book as one continuously-scrollable WebView, scroll-position-based progress tracking and persistence/resume, reusing the existing color-filter selector as a real CSS theme (background/text color) for the text, favorite/bookmark toggle (unchanged mechanism).
- **Explicitly out of scope for this sub-project:** MOBI (a separate, later brainstorm per the session's scope-split decision — likely just a scope decision, not full implementation). DRM-protected EPUBs (no support planned at all; a DRM'd file will fail extraction/parsing like any other malformed file and fall back to the existing "couldn't open" error card). A table-of-contents/chapter-jump UI (v1 is front-to-back continuous scroll only — a real nice-to-have, explicitly deferred rather than built now). Horizontal page-turn pagination (decided against in favor of continuous scroll, see below). Font-size/typeface user controls (not requested; the EPUB's own CSS controls typography for v1).
- **Explicitly not touched:** CBR support (Epic 6, unrelated format). The existing paged-image `ReaderScreen`/`SinglePageReader`/`DualPageSpreadReader`/`WebtoonReader` and their `ReadingMode`/`autoCropMargins` concepts — none of that applies to reflowable text, and this sub-project doesn't modify any of those files' internals.

## Components

### EPUB extraction & parsing

An EPUB is a zip file containing `META-INF/container.xml` (points at the actual content manifest, the "OPF" file), the OPF file itself (XML: a `<manifest>` of every resource with an id/href/media-type, and a `<spine>` — the ordered list of manifest items that make up the reading order), and the XHTML/CSS/image content those reference.

On `openComic` for an EPUB comic:
1. Extract the whole zip to `context.cacheDir/epub_temp/<comic.id>/`, deleting any prior extraction for that id first (EPUBs are small; a full re-extract on every open is simpler and more robust than trying to detect staleness, and avoids ever serving content from a half-written previous extraction).
2. Parse `META-INF/container.xml` to find the OPF file's path within the archive.
3. Parse the OPF file to get the manifest (id → href, resolved relative to the OPF's own directory) and the spine (ordered list of manifest item ids = reading order).

XML parsing must use `javax.xml.parsers.DocumentBuilderFactory`/`org.w3c.dom` (the standard JDK DOM parser), not any `android.util.Xml`-based API — the JDK DOM classes are real, unstubbed classes available in both the Android runtime and this project's plain-JVM unit tests (no Robolectric configured, confirmed by the cover-thumbnails sub-project's investigation), the same reasoning that already led this project to prefer `java.util.zip.ZipFile` over anything Android-specific for CBZ parsing. This is what makes the parsing logic itself real-object unit-testable, unlike the WebView rendering it feeds into.

### Combined document + rendering

Rather than one WebView per chapter (WebView instances are expensive to create and notoriously janky to recycle inside a lazy/scrolling list), all spine chapters are combined into a single HTML document, and the whole book is rendered in **one** WebView that the user scrolls through continuously:

- For each spine item, read its extracted XHTML file, pull out its `<head>`'s stylesheet `<link>`/`<style>` tags and its `<body>` inner content.
- Write one combined file, `<extractedDir>/__combined.xhtml`: a single `<head>` containing the union of every chapter's stylesheet references (plus one empty `<style id="reader-theme">` tag, see below) and a single `<body>` containing each chapter's body content concatenated in spine order, each wrapped in a `<div id="epub-chapter-N">` marker (not used for navigation in v1, but a cheap structural hook to keep for a future TOC feature rather than losing chapter boundaries entirely).
- The WebView loads this combined file through `androidx.webkit.WebViewAssetLoader` configured with an `InternalStoragePathHandler` pointing at the extraction directory, serving it over the virtual `https://appassets.androidplatform.net/...` origin — this is what lets each chapter's relative image/CSS `href`s resolve correctly, and avoids the CORS/security restrictions modern WebView applies to raw `file://` URLs.

### `EpubReaderScreen`

A new composable, separate from the existing `ReaderScreen` — not a new `ReadingMode` branch inside it, since reflowable scrolling text is a fundamentally different rendering paradigm from the paged-image modes `ReaderScreen` already dispatches between. It embeds a `WebView` via Compose's `AndroidView`, configured with the asset loader above, and:

- Shows a loading spinner while extraction/parsing is in progress, the existing generic error card (reusing `pageLoadError`, same as the image-format error path) if extraction/parsing fails, or the WebView once ready — mirroring `SinglePageReader`'s existing three-state pattern (loading / error / content) rather than inventing a new one.
- Has its own top bar (title, back button, favorite toggle, settings icon) and quick-settings bottom sheet, visually matching `ReaderScreen`'s existing chrome, but the settings sheet only offers the color-filter selector — no reading-direction-mode or auto-crop-margins controls, since neither applies here. This is a deliberate small duplication of `ReaderScreen`'s top-bar/sheet code rather than generalizing `ReaderScreen` itself to be format-aware, which would be a much larger, riskier refactor for what this sub-project needs.
- On scroll, debounces (matching the existing 1.5s debounce pattern already used for page-progress persistence) computing `scrollY / (scrollHeight - viewportHeight)` as the progress percentage and persisting it.
- On open, once the WebView has finished its initial load, if the comic has a nonzero persisted `progressPercentage`, scrolls to that fraction of the page automatically (resume).

### Color filter theming

For EPUB text, `ColorFilterMode` is a real, straightforward CSS background/text-color swap — a much better fit than for image pages (where these modes remain an unimplemented, deferred Epic 2 item). Mapping (background / text), matching `ReaderScreen`'s existing `bgColor` choices where a direct equivalent exists:
- `ORIGINAL`: white / black
- `SEPIA`: `#FBF0D9` / dark brown-black
- `NIGHT`: `#1E1E1E` / light gray
- `AMOLED_BLACK`: black / white
- `HIGH_CONTRAST`: black / white (same as AMOLED_BLACK for v1 — no additional font-weight/size treatment, avoiding scope creep beyond what's requested)

The combined HTML's `<head>` includes an empty `<style id="reader-theme">` tag from the start; `EpubReaderScreen` sets/replaces its content via `WebView.evaluateJavascript(...)` whenever `state.filterMode` changes, rather than reloading the page — reloading would lose scroll position, which a same-document JS-only style swap avoids entirely.

### `ReaderViewModel` changes

- `openComic(comic)` branches at the very top on `comic.format == ComicFormat.EPUB`: the existing `createPageSource`/`PageBitmapCache` path is completely bypassed for EPUB (that path is for paged-image formats only). A new `openEpubComic(comic)` path does the extraction (off `ioDispatcher`, matching the existing `withContext(ioDispatcher) { createPageSource(comic) }` pattern) and populates a new `ReaderUiState` field — the WebView-loadable URL for the combined document — instead of `currentPageBitmap`/`pageCache`. Extraction/parsing failure sets `pageLoadError`, reusing the existing field and its established "show a generic error card" meaning rather than adding a parallel error mechanism.
- A new method (analogous to `setCurrentPageIndicator`, but percentage-only — EPUB has no `currentPage`/`totalPages` concept) updates `comic.progressPercentage`/`lastReadTimestamp` and debounce-persists via the same `schedulePersist` mechanism already used for page progress. `comic.currentPage`/`totalPages` are deliberately left untouched (whatever default they already hold) for EPUB comics — the persisted `ReadingProgress` record's `currentPage`/`totalPages` fields go unused for EPUB, which is fine since the UI always branches on `comic.format == EPUB` to display `progressPercentage` directly rather than reconstructing a percentage from page numbers. This avoids a `ReadingProgress`/DataStore schema change.
- `closeComic()`/teardown deletes the just-closed EPUB's extracted temp directory (if the closed comic was an EPUB), keeping at most one book's temp files on disk at a time rather than accumulating across sessions — the same spirit as `pageCache?.close()` releasing PDF/CBZ resources on close, just for on-disk extraction instead of open file handles.

### `MainActivity` dispatch

The existing `if (state.activeComic != null) { ReaderScreen(...) } else { HomeScreen(...) }` becomes a three-way dispatch: `HomeScreen` when there's no active comic, `EpubReaderScreen` when the active comic's format is `EPUB`, `ReaderScreen` (unchanged) otherwise.

### New dependency

`androidx.webkit` (for `WebViewAssetLoader`, expected to use its `InternalStoragePathHandler` for serving the extracted directory) — the exact current version to pin, `minSdk = 24` compatibility, and the exact class/constructor API surface all need live verification during plan-writing before any code is written against them, not assumed from general knowledge. This project already hit a case (Epic 4's Google Identity Services `Scope` class) where a plausible-sounding package path for an Android library turned out wrong when actually checked against the real AAR/docs — the same discipline applies here.

## Testing

- **Container/OPF XML parsing**: real-object unit tests, no mocks — construct small real EPUB-shaped zip files (via `TemporaryFolder`, the same pattern `CbzPageSourceTest`/`ThumbnailDecoderTest` already use) with a synthetic `container.xml` and OPF, and assert the parsed manifest/spine order. This is fully testable on plain JVM per the `DocumentBuilderFactory` choice above.
- **Combined-document generation**: also real-object/pure-string-logic testable — given parsed spine content, assert the combined HTML contains the right chapters in the right order with the right anchor markers.
- **WebView rendering, scroll-based progress, JS theme injection, resume-scroll**: not unit-testable in this project's setup (real `WebView` requires a real Android runtime) — covered by manual on-device verification in the implementation plan's final task, the same way Coil/Compose UI wiring and on-device page-rendering work were verified elsewhere in this project.
