# CBR (RAR) Comic Support — Design

**Epic:** [PROJECT_TASKS.md](../../../PROJECT_TASKS.md) — Epic 6: CBR (RAR) Support

## Goal

Make local `.cbr` files actually openable. `ComicFormat.CBR` already exists in the enum and is fully wired through the rest of the app — local file scanning tags `.cbr` files correctly, the library search/format-filter chips and grid/list toggle already treat CBR as a real option, and cover-thumbnail decoding already has a defined (fallback-icon) behavior for it — but `createPageSource` has no `CBR` branch, so tapping a CBR comic always shows the generic "This format isn't supported yet" error card. This epic closes that one gap.

## Scope

- **In scope:** extracting a local `.cbr` (RAR archive) file's image pages and rendering them through the exact same paged-image `ReaderScreen`/`PageBitmapCache`/reading-mode pipeline PDF and CBZ already use — no new reader screen, no new `ReaderViewModel` state, no new UI. A new `CbrPageSource` implementing the existing `ComicPageSource` interface is the only new production surface.
- **Explicitly out of scope:** cover-thumbnail decoding for CBR (`ThumbnailDecoder.kt`'s `decodeThumbnail` currently returns `null`/fallback-icon for every format except `PDF`/`CBZ` — extending it to CBR is a reasonable, cheap follow-up once this epic's core `CbrPageSource` exists, since it could reuse the same one-time full-extraction the reader itself does, but it's not required to make CBR *readable*, which is this epic's actual goal). Noted as a plan-writing-time decision, not pre-decided here — see Components below.
- **Explicitly out of scope:** RAR5-specific edge cases beyond what `junrar` itself supports out of the box, password-protected/encrypted RAR archives (no decryption UI exists anywhere in this app), and multi-volume/split RAR archives (`.r00`, `.r01`, ... — a single-file `.cbr` is the only supported shape, matching how CBZ only supports a single-file `.zip`).

## Components

### Library choice: `junrar`

`com.github.junrar:junrar` (latest `8.1.0` as of this writing — exact version to re-confirm at plan-writing time, since dependency landscapes move; this epic already learned from Epic 5 that even a library described as "pure Java" can carry a version-specific transitive Kotlin dependency worth checking against this project's Kotlin Gradle Plugin version, currently `1.9.22`). Actively maintained, published on Maven Central, pure Java with no native/JNI component (unlike `libmobi`, the reason Epic 5 formally descoped MOBI). License is the "UnRar License," which restricts using the code to build a *competing RAR-compatible archiver* — not a restriction on using it to read/extract files inside an application, which is exactly this app's use case.

Verified real API surface (not guessed): `com.github.junrar.Archive` implements `Closeable`, has a constructor taking a `java.io.File` directly, and exposes `nextFileHeader(): FileHeader?` / `extractFile(header: FileHeader, out: OutputStream)`. `FileHeader` has `getFileNameString(): String` (file-separator-normalized, not the Windows-backslash form) and `isDirectory(): Boolean`.

### Extraction strategy: full upfront extraction, not per-page streaming

Unlike `CbzPageSource` (which opens a `ZipFile` once and calls `zipFile.getInputStream(entry)` freshly for each page — genuinely random-access, since ZIP's central directory makes every entry independently seekable), RAR's compression can be "solid" — later files' compressed data can depend on earlier files having already been decompressed, so there's no guarantee `junrar` can cheaply jump straight to page 40 without having internally processed pages 1-39 first. Rather than depend on real-world CBR files happening not to use solid compression, `CbrPageSource` extracts the entire archive to a temp directory once (mirroring the exact pattern already built, reviewed, and hardened for Epic 5's `EpubExtractor`), then serves pages by reading the already-extracted files directly off disk — genuinely random-access after that one-time cost, with no dependency on RAR's internal compression scheme.

This means opening a CBR comic pays an extraction-time cost proportional to the whole archive's size up front, rather than CBZ's "show page 1 almost instantly regardless of total archive size." For a typical comic archive (tens of images, tens of MB), this is expected to still be fast — and it's the same UX shape (a brief loading spinner before the first page appears) the app already has for PDF/CBZ's own page-1 decode and for EPUB's extraction step, not a new pattern users haven't already seen.

**Security — apply the Epic 5 lesson from the start, don't wait for a reviewer to find it:** Epic 5's real Zip Slip vulnerability (a malicious zip entry's name containing `../` segments escaping the intended extraction directory) applies identically here — a RAR archive is just as capable of carrying a `FileHeader` whose `getFileNameString()` resolves outside the extraction directory. The same canonical-path validation guard `EpubExtractor.kt` already uses (resolve the target file's canonical path, verify it's a genuine descendant of the extraction directory's canonical path, skip any entry that fails this check rather than aborting the whole extraction) must be built into `CbrPageSource`'s extraction from the first version, not added in a later fix round.

### `CbrPageSource`

New file `app/src/main/java/com/comicanything/reader/data/pagesource/CbrPageSource.kt`, implementing the existing `ComicPageSource` interface (`val pageCount: Int`, `suspend fun getPage(page: Int): Bitmap`, `fun close()`), following the same shape as `CbzPageSource`:

- Constructor takes a `File` (the `.cbr` file) and does the extraction synchronously (this is consistent with `PdfPageSource`'s existing constructor, which also does blocking I/O directly — both are always invoked from inside `withContext(ioDispatcher) { createPageSource(comic) }` in `ReaderViewModel.openComic`, already on a background dispatcher, so a longer-than-CBZ extraction here doesn't block the UI thread; it just means the existing loading-spinner state shows a bit longer before the first page renders — the same tradeoff EPUB already accepted for its own extraction step).
- Extracts every non-directory entry to a per-comic temp directory (mirroring `EpubExtractor`'s `cacheDir/epub_temp/<comicId>/` pattern, e.g. `cacheDir/cbr_temp/<comicId>/`), applying the canonical-path Zip-Slip guard described above.
- Lists the extracted files, filters to image extensions, and sorts them in natural numeric order — reusing the *same* `naturalOrderComparator()` already public in `CbzPageSource.kt` (it operates on filename `String`s, format-agnostic), rather than reusing `listImagePagesSorted` itself (which is `ZipEntry`-typed and doesn't fit a post-extraction, plain-`File`-based listing). A small CBR-specific equivalent of the image-extension/junk-file filtering (`__MACOSX/`, dotfiles, the same `jpg/jpeg/png/webp/gif` whitelist) operates on the extracted `File` list instead.
- `getPage(page)` decodes the corresponding extracted file via `BitmapFactory` with the same bounds-then-sampled-decode two-pass approach `CbzPageSource.getPage` already uses (decode bounds first to compute `inSampleSize`, then decode at the reduced size) — full reuse of that established decode-quality/memory-tradeoff pattern, just reading from a plain `File` instead of a `ZipEntry`'s `InputStream`.
- `close()` deletes the extraction temp directory. Because `CbrPageSource` flows through the *existing* `PageBitmapCache`/`ReaderViewModel.pageCache` machinery exactly like `PdfPageSource`/`CbzPageSource` already do, this cleanup is picked up for free by every cleanup path already hardened in this codebase this session — `flushAndTeardown()`, `capturePreviousComicResources()`/`closePreviousComicResources()`'s cross-format-safe teardown, the generation guard on superseded opens — with **zero `ReaderViewModel` changes required**. This is a meaningfully simpler integration than Epic 5's EPUB support needed, because CBR fits entirely inside the pipeline that already exists rather than needing a parallel path.

### `createPageSource` change

One new branch in the existing `when (comic.format)` dispatch in `ComicPageSource.kt`:
```kotlin
ComicFormat.CBR -> CbrPageSource(File(comic.pathOrUrl))
```
No other change to that function.

### New dependency

`com.github.junrar:junrar` — exact version and any transitive-dependency check (per the Kotlin-metadata lesson from Epic 5) to be confirmed at plan-writing time via live research, not assumed from this design conversation.

## Testing

- **`CbrPageSource`'s extraction logic** (Zip-Slip guard, image-file filtering, natural-sort ordering): real-object unit-testable using `TemporaryFolder` and a genuinely-constructed small `.cbr` fixture — this requires building a real RAR archive as a test fixture, which is a real constraint `junrar` itself can't help with (it's an *extraction* library, not a *creation* one). The implementation plan needs to work out a concrete way to produce a test `.cbr` file (e.g. a small binary fixture checked into the test resources, or shelling out to a RAR-creation tool if one is reliably available in this environment) — this is a genuine open question to resolve during plan-writing, not glossed over here.
- **Actual RAR decompression / `BitmapFactory` decode**: not unit-testable in this project's setup for the same reason CBZ/PDF/EPUB's real decode paths aren't (real Android `BitmapFactory`, no Robolectric configured) — covered by manual on-device verification, the same as every other format epic this session.
- Per this epic's own lesson-learned entry from Epic 5 (`[[epub_final_review_and_ondevice_lessons]]`): the manual verification step should include at least one CBR file produced by a mainstream real-world tool (e.g. WinRAR, 7-Zip, or a comic-scanning tool that outputs CBR), not only a synthetic fixture built for this epic — Epic 5's Critical bug slipped past every layer of testing specifically because every fixture, hand-built and script-built alike, shared the same unrealistic assumption.
