# Handoff — Batch SR Queue + General Improvements

## Goal

- Allow batch super-resolution processing of downloaded manga chapters
- Persistent storage alongside downloads (not temp cache)
- Working reader cache hit for batch-processed results
- Stable model switching (no crashes)
- Proper queue management UI
- WebP compression throughout the pipeline

## Current State

### Repo
- Renamed to `mihon-super-resolution` (from `MangaSR-v2`)
- v2.0.0 release published at https://github.com/timyang2005/mihon-super-resolution/releases/tag/v2.0.0
- README rewritten in Mihon format

### What's Done (all pushed to `main`)

**Core batch SR (original PR #1 — `34aeab2` via `79db279`):**
- SRDiskCache, SRQueueStore, SRQueueProcessor, SuperResolutionSync
- MangaScreen sparkle icon, batch SR button, LaunchedEffect refresh
- MangaBottomActionMenu: batch SR button (grayed if undownloaded selected)
- Settings: queue management preference + i18n

**Crash fixes:**
- ArchiveInputStream buffer overflow (`clear()` → `position(0)`) → SIGSEGV in `BitmapFactory.decodeStream` during CBZ processing
- `SRQueueProcessor.runLoop()`: `removeFirst()` on empty queue after external cancel
- Model switching: `switchModel()` now suspend + `mutex.withLock` to prevent `release()` during native inference

**CBZ support (`34aeab2`):**
- `SRQueueProcessor.runLoop()`: uses `ArchiveReader` to read images from CBZ archives

**Persistent storage (`34a7bdb`):**
- SR cache moved from `context.cacheDir/sr_disk_cache` to `downloads/sr_cache`
- `SRCacheManager.diskCache` singleton (lazy) to avoid multi-evictScope race
- Batch results in `sr_cache/batch/{source}/{manga}/{chapterId}/` with `metadata.json`

**Queue UI (`35a7bdb` + `3d9f4d6` + fixes):**
- `SRQueueScreen`: full Voyager Screen (not AlertDialog)
- Both in-progress and completed sections: long-press multi-select with Checkbox
- Bottom bar: 取消选中 | 删除选中 | 反选 | 全选/全不选
- Clear All button shown only in non-selecting mode
- Progress bar with "X/Y pages" text (fixed `totalPages` overwrite bug)
- Auto-refresh on `completedCount` changes

**Image pipeline:**
- `ImageSaver.Image.Cover`: JPEG → WebP (`WEBP_LOSSY, 90`)
- `SRDiskCache`: all batch output uses WebP
- `ReaderViewModel.getSrBitmap()`: fallback chain `page.srBitmap` → `srManager.getCachedResult()` → `SRPreloadDispatcher.getSrBitmap()`

**Reader batch cache hit (`2035c03`):**
- `SRDiskCache.getBatchPageAnySource()`: scan `batch/` for matching chapterId
- `ReaderPageImageView`: check batch cache after memory and preload miss

**SR lifecycle:**
- `onCleared()`: cancel reader processing jobs (batch SR unaffected)
- `scheduleSrRefresh()`: skip if `!manager.isReady` (no timer when SR disabled)

### Key Commits

```
c636f0b docs: rewrite README for mihon-super-resolution
f01f595 fix: cancel reader SR processing on exit
2035c03 fix: reader batch cache hit for structured SR results
1eeff7e fix: queue removeFirst crash + progress bar + batch delete
2757e63 refactor: batch cache restructuring + code review fixes
3d9f4d6 fix: cancel mid-processing + WebP save + progress bar
34a7bdb refactor: SR queue dialog to full screen + unified cache
8f105f2 fix: ArchiveInputStream buffer overflow
79db279 Merge PR #1
34aeab2 fix: CBZ + persistent cache + save SR fallback (PR #1)
```

### Files Modified (this session)

| File | Change |
|------|--------|
| `core/archive/.../ArchiveInputStream.kt` | `clear()` → `position(0)` buffer overflow fix |
| `core/superresolution/.../SuperResolutionManager.kt` | `switchModel()` suspend + mutex protect; `cancelProcessingJobs()` public |
| `core/superresolution/.../SRDiskCache.kt` | Batch methods (`putBatchPage`, `getBatchPageAnySource`, etc.); `clear()` skips `batch/` |
| `core/superresolution/.../SRQueueStore.kt` | `SRQueueItem` added `sourceName` field |
| `core/superresolution/.../SRPreloadDispatcher.kt` | Constructor accepts `srCacheDir` param |
| `app/.../data/sr/SRQueueProcessor.kt` | CBZ ArchiveReader; cancel mid-processing; progress + sourceName |
| `app/.../data/sr/SRCacheManager.kt` | `diskCache` singleton (lazy) |
| `app/.../data/sr/SuperResolutionSync.kt` | Uses `SRCacheManager.diskCache` |
| `app/.../data/saver/ImageSaver.kt` | `Image.Cover` JPEG → WebP |
| `app/.../di/AppModule.kt` | `SRPreloadDispatcher` cache path |
| `app/.../more/settings/screen/SRQueueScreen.kt` | **New** — full queue management screen |
| `app/.../more/settings/screen/SettingsReaderScreen.kt` | Dialog → navigation to `SRQueueScreen` |
| `app/.../manga/MangaScreen.kt` | Use `getCompletedBatchChapters()`, `SRCacheManager.diskCache` |
| `app/.../manga/components/MangaChapterListItem.kt` | Sparkle icon 18→22dp |
| `app/.../ui/reader/ReaderViewModel.kt` | `getSrBitmap()` fallback; `onCleared()` cancels SR; `cancelProcessingJobs()` |
| `app/.../ui/reader/viewer/ReaderPageImageView.kt` | Batch cache hit; `scheduleSrRefresh()` ready check |
| `i18n/base/strings.xml` | `sr_queue_none`, `sr_queue_cancel_selected`, `sr_queue_selected_count`, `action_select_none` |
| `i18n/zh-rCN/strings.xml` | Same strings in Chinese |

### What We Tried That Failed

1. **`.gitignore` UTF-16 corruption** (from PR #1) — `edit` tool appended in UTF-16-LE. Fixed by PowerShell rewrite.
2. **`SuperResolutionSync()` direct instantiation** (from PR #1) — Instance mismatch. Fixed by `Injekt.get()`.
3. **`getDiskCache()` creating new instances each call** — Multiple `evictScope` competing. Fixed by `by lazy` singleton.
4. **Checkbox inside `combinedClickable`** — Double-toggle bug (onCheckedChange + combinedClickable both fire). Fixed by removing `combinedClickable` from Row in selecting mode, using Checkbox only.
5. **`updateProgress()` overwriting `totalPages`** — Stale local `item` variable. Fixed by `queue[idx].copy(processedPages = item.processedPages)`.
6. **Cancel mid-processing orphan cache** — Added `cancelledMidProcessing` flag, cleanup partial files.
7. **`queue.removeFirst()` crash** — External cancel removes item. Fixed by checking `queue.first().chapterId == item.chapterId`.
8. **Repo rename conflict** — Old `mihon-super-resolution` existed. Manually deleted then renamed.

## Next Steps

1. ~~**Push remaining uncommitted changes**~~ ✅ Done (commit `1247155`)
2. **CI build + install + test** on Xiaomi 14
3. **Test:** model switching should no longer crash
4. **Test:** SR indicator should be idle when SR disabled
5. **Consider** batch eviction limit (review noted no upper bound for `batch/` storage)
6. **Consider** restore "select all" button for completed section in non-selecting mode (reviewer's suggestion)

## New: SR Background Processing (commit `5f9f867`)

**Problem:** SR queue stops processing when screen turns off.

**Solution:** Implemented foreground service using WorkManager (same pattern as DownloadJob).

**Files Added/Modified:**
- `app/.../data/sr/SRJob.kt` — **New** — WorkManager + ForegroundInfo for background processing
- `app/.../data/sr/SRQueueProcessor.kt` — Added `isRunning` property, `start()` method, notification updates
- `app/.../data/sr/SuperResolutionSync.kt` — Register `SRQueueProcessor` with Injekt for SRJob access
- `app/.../data/notification/Notifications.kt` — Added SR notification channels (progress + complete)

**How it works:**
1. When queue has items, `SRJob.start(context)` is called
2. SRJob sets itself as foreground service (shows persistent notification)
3. SRJob calls `processor.start()` which launches the actual processing loop
4. Progress notifications show current manga/chapter being processed
5. Completion notification shown when queue is empty

**Testing needed:**
- Verify SR continues processing when screen turns off
- Verify notifications appear correctly
- Verify job stops when queue is empty

### Build Environment

- JDK 17 at `C:\Program Files\Zulu\zulu-17`
- Android SDK at `C:\Users\16pro\AppData\Local\Android\Sdk`
- NDK 27.0.12077973 + 29.0.14206865
- Wireless ADB: `192.168.31.136` (Xiaomi 14, HyperOS)
- Compile: `$env:JAVA_HOME = "C:\Program Files\Zulu\zulu-17"; .\gradlew.bat :app:compileDebugKotlin`
- Full build: `.\gradlew.bat assembleDebug` (requires ncnn libs in `core/superresolution/src/main/cpp/ncnn/`)
- Push with proxy: `$env:HTTPS_PROXY = "http://127.0.0.1:7897"; git push origin main`
- GitHub CLI with proxy: `$env:HTTPS_PROXY = "http://127.0.0.1:7897"; gh ...`
