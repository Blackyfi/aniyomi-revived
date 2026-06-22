# Aniyomi-Revived — Performance, Optimization & Resource-Efficiency Audit

**Date:** 2026-06-21 · **Scope:** runtime performance, memory/resource efficiency, build & app-size optimization, startup & background work.
**Method:** module-graph + dependency + existing-doc inventory, then a 7-dimension multi-agent fan-out (Compose, coroutines, data, image/media, network, memory, build) with **every** candidate adversarially verified against the cited source before inclusion. **24 candidates → 22 confirmed, 2 refuted.** Severities below are the **post-verification adjusted** ratings (several were lowered after reading the code — the verifier notes say why).

> Severity is keyed to **runtime / user-visible** impact. Confidence = "is it real *and* on a hot/realistic path." Every finding cites a file:line range that was actually read.

## What this builds on (and does not repeat)

- `PROFILE_REPORT.md` / `AUDIT_REPORT.md` / `KEIYOUSHI_AUDIT.md` cover **security, logic, code-quality, ecosystem** — they contain **no runtime-perf content**. This audit is net-new and complementary.
- The two perf-adjacent items in `AUDIT_REPORT.md` were checked against `main`:
  - **#22 `setMangaReadingMode` `runBlocking`-on-UI — already fixed in `main`** (`ReaderViewModel.kt:680` now uses `viewModelScope.launchIO`). **Excluded.**
  - **#26 `TachiyomiImageDecoder.displayProfile` `@Volatile`** — still un-annotated (`TachiyomiImageDecoder.kt:89`); it's a JMM-visibility correctness nit, not a perf cost, so not re-raised here.
- Branch `fix/audit-findings` does not exist (local or remote); audited `main` as-is.
- **Structural multiplier:** ~24% of the code is anime/manga duplication. **15 of 22 findings have an identical sibling** in the other subsystem — each fix lands twice. Sibling paths are listed per finding.
- **No Critical or High findings.** This is a healthy codebase; the wins are an accumulation of **Med/Low** items, several of which are 1-line fixes on the hottest screens. **There are zero confirmed main-thread *hot-loop* stalls** — the main-thread costs that exist are on cold/occasional paths (player open, external-player return, install/upgrade).

---

## Executive summary — top 5

| # | Finding | Impact | Fix size |
|---|---------|--------|----------|
| **F1** | Library grids/lists render `items()` **without a stable `key=`** (all 6 variants) | Reorder/filter/sort on the app's hottest screen discards slot state → every shifted item recomposes & re-resolves its cover, loses move animation | ~1 line × 6 |
| **F14** | Global-search screen models **leak a 5-thread `ExecutorService`** per instance (no shutdown) | 5 non-daemon threads (~0.5–1 MB stack each) leaked on every global/source/migrate search open; accumulates across navigation | ~3 lines × 2 |
| **F7** | `mangas_categories` junction table has **no index on `manga_id`** | Unindexed table participates in `libraryView` join + `getCategoriesByMangaId`, which re-emit on every chapter-read | ~1 line + migration × 2 |
| **F10** | Player does **synchronous SAF/disk file I/O on the main thread** in `onCreate` | Delays time-to-first-frame on every player launch; ANR risk when mpv user files live on SAF | ~30 lines |
| **F4** | `runBlocking { initAnime() }` on the **main thread** in the external-player result callback | Two DB queries (incl. full episode list) park the UI thread during activity-resume | ~10 lines |

**Theme:** the highest-value, lowest-risk work is a cluster of **quick wins on the library screen + a few leak/I/O fixes** (F1, F14, F7, F16, F6, F12, F11). They're 1–5 lines each, high-confidence, and most must also be applied to the anime sibling.

---

## Findings table

| ID | Area | File:lines | Sev | Conf | Effort | Sibling? |
|----|------|-----------|-----|------|--------|----------|
| F1 | Compose | `MangaLibraryComfortableGrid.kt:38-41` (+5) | Med | High | ~1 ln ×6 | ✅ anime ×3 |
| F2 | Compose | `DateText.kt:31-79` | Low | Med | ~4 ln | ✅ shared helper |
| F3 | Compose | `MangaLibraryComfortableGrid.kt:44` (+5) | Low | Med | ~5 ln | ✅ anime ×3 |
| F4 | Coroutines | `MainActivity.kt:323-338` | Med | High | ~10 ln | — (anime-only) |
| F5 | Coroutines | `ManhwaLibraryScreenModel.kt:1-11` | Low | High | moderate | — (fork feature) |
| F6 | Coroutines | `MangaLibraryScreenModel.kt:106-134` | Low | Med | 1 ln | ✅ `AnimeLibraryScreenModel.kt:110` |
| F7 | Data | `mangas_categories.sq` | Med | High | ~1 ln + migration | ✅ `animes_categories.sq` |
| F8 | Data | `libraryView.sq:1-37` | Med | High | moderate | ✅ `animelibView.sq` |
| F9 | Data | `MangaLibraryScreenModel.kt:441-460` | Med | High | ~15 ln | ✅ anime |
| F10 | Image/media | `PlayerActivity.kt:230,412-503` | Med | High | ~30 ln | — (anime-only) |
| F11 | Image/media | `MangaCoverFetcher.kt:220-231` | Low | Med | ~10 ln | ✅ `AnimeImageFetcher.kt:221-232` |
| F12 | Image/media | `PlayerControls.kt:380-396` | Low | Med | 1 ln | — (anime-only) |
| F13 | Network | `OkHttpExtensions.kt:102-118` | Low | Med | ~10 ln | shared core |
| F14 | Network | `MangaSearchScreenModel.kt:46` | Med | High | ~3 ln | ✅ `AnimeSearchScreenModel.kt:46` |
| F15 | Memory | `PlayerActivity.kt:288-311,786-821` | Med | Med | ~5 ln | — (anime-only) |
| F16 | Memory | `MangaExtensionManager.kt:63,105-108,323-324` | Low | High | 1 ln | ✅ `AnimeExtensionManager.kt:66` |
| F17 | Memory | `InMemoryLogcatBuffer.kt:16-54` | Low | Med | ~2 ln | shared core |
| F18 | Build | `app/build.gradle.kts:5-11` | Low | High | moderate | — |
| F19 | Build | `gradle.properties:1` | Low | Med | 1 ln + refs | — |
| F20 | Build | `gradle.properties:7-10` | Low | Med | few ln | — |
| F21 | Startup | `App.kt:108` | Low | Med | ~10 ln | — (shared App) |
| F22 | Startup | `MainActivity.kt:159` | Low | Med | moderate | — (shared) |

---

## Detailed findings

### F1 — Library grids/lists render `items()` without a stable `key=` — **Med / High**
**Files:** `app/src/main/java/eu/kanade/presentation/library/manga/MangaLibraryComfortableGrid.kt:38-41`; siblings `MangaLibraryCompactGrid.kt:39`, `MangaLibraryList.kt:49`, `AnimeLibraryComfortableGrid.kt:38`, `AnimeLibraryCompactGrid.kt:39`, `AnimeLibraryList.kt:49`.

```kotlin
items(
    items = items,
    contentType = { "manga_library_comfortable_grid_item" },
) { libraryItem ->
```
**Problem.** All six home-library variants pass `contentType` but no `key`. Lazy item identity falls back to **list index**.
**Why it costs.** When the library `StateFlow` re-emits a re-sorted/filtered list, Compose matches items positionally: every item below an insertion/removal/reorder point receives new data at its slot, loses slot-table state, recomposes, and rebuilds its `MangaCover` model (Coil re-resolves it — usually a memory-cache hit, so the dominant cost is recomposition + lost item-placement animation, not network). Real triggers: **in-library search filtering** (typing churns indices), **sort changes**, **add/remove from library**. This is the home screen. The same codebase *does* key the chapter list (`MangaScreen.kt:807`) and sources list (`MangaSourcesScreen.kt:68`), so this is an inconsistency, not a platform limit.
**Fix.** Add `key = { it.libraryManga.id }` (anime: `it.libraryAnime.id`) to each `items(...)`.
**Verify.** Layout Inspector recomposition counts while toggling a sort/filter on a large category — only newly-visible items should recompose; covers should not flicker on sort change.

### F2 — `relativeDateText`/`relativeDateTimeText` recompute date conversion + resource lookups every recomposition — **Low / Med**
**File:** `app/src/main/java/eu/kanade/presentation/components/DateText.kt:31-79` (helper used by chapter/episode/Updates/History rows; `toRelativeString` in `DateExtensions.kt:75-131`).
```kotlin
return relativeDateTimeText(
    localDateTime = LocalDateTime.ofInstant(
        Instant.ofEpochMilli(dateEpochMillis), ZoneId.systemDefault(),
    ).takeIf { dateEpochMillis > 0L },
)
```
**Problem.** The composables `remember` the prefs but not the *work*: `Instant.ofEpochMilli` + `LocalDateTime.ofInstant` + `toRelativeString` (which calls `LocalDateTime.now()`, 2–3 `ChronoUnit.between`, and `pluralStringResource`/`stringResource`) run on every recomposition though the date input is unchanged.
**Why it costs.** Verifier tempered the original "many times/sec per row" claim: during a download only the **single** downloading row's `ChapterList.Item` is `.copy()`d (`MangaScreenModel.kt:542-554`), so only that row recomposes. The broader trigger is **selection toggles**, which re-emit all chapters and recompose every visible row, re-running the chain per row. Ops are individually sub-ms → real but minor.
**Fix.** `val text = remember(dateEpochMillis, relativeTime, dateFormat) { localDateTime?.toRelativeString(...) ?: ... }`.
**Verify.** Allocation tracking on the chapter list during selection — `LocalDateTime`/`Instant` allocs for unchanged rows should drop to ~0.

### F3 — Grid item resolves `isSelected` via O(n) `fastAny` scan over a `List<LibraryManga>` — **Low / Med**
**File:** `MangaLibraryComfortableGrid.kt:44` (+5 siblings as F1). Call path `MangaLibraryPager.kt:32-119`.
```kotlin
isSelected = selection.fastAny { it.id == libraryItem.libraryManga.id },
```
**Problem.** `selection` is declared `List<LibraryManga>` (Compose treats it **unstable**, even though the model backs it with a `PersistentList`). Every visible item reads it, so one selection toggle recomposes all visible items, each scanning the whole selection.
**Why it costs.** Cost per toggle is O(visibleItems × selectionSize). Verifier capped at Low: the lazy grid only composes the visible window (~20–50), `fastAny` short-circuits to O(1) when selection is empty (normal scroll), and the dominant cost is the redundant recomposition, not the scan itself.
**Fix.** Hoist `val selectedIds = remember(selection) { selection.mapTo(HashSet(selection.size)) { it.id } }` in `MangaLibraryContent`/`AnimeLibraryContent`, pass an `ImmutableSet`, and use `selectedIds.contains(id)` (O(1)).
**Verify.** Frame time of a select-all on a 200+ item category, before/after.

### F4 — `runBlocking { initAnime() }` on the main thread in the external-player result callback — **Med / High**
**File:** `app/src/main/java/eu/kanade/tachiyomi/ui/main/MainActivity.kt:323-338` (`initAnime` → `ExternalIntents.kt:105-114`).
```kotlin
if (animeId != null && episodeId != null) {
    runBlocking { ExternalIntents.externalIntents.initAnime(animeId, episodeId) }
}
```
**Problem.** `registerForActivityResult` callbacks dispatch on the main thread; `runBlocking` parks it while `initAnime` runs `getAnime.await()` + `getEpisodesByAnimeId.await()` (full episode list) on the activity-resume transition.
**Why it costs.** Guaranteed main-thread DB access on resume = a frozen frame, ANR risk under DB contention. Verifier note: the episode query is single-anime/`animeId`-indexed and episode counts are typically tens–hundreds (not thousands), so the stall is usually modest — and it only fires on external-player return (opt-in feature). The `runBlocking` is currently *structurally required* because the non-suspend `onActivityResult` reads the fields `initAnime` populates.
**Fix.** Wrap the `RESULT_OK` branch in `lifecycleScope.launchIO { initAnime(...); onActivityResult(...) }` so both run off-main in one coroutine; no synchronous result is needed.
**Verify.** Perfetto main-thread slice on external-player return — the `runBlocking` slice should disappear.

### F5 — Manhwa tab spawns a second full library pipeline duplicating all manga library work — **Low / High**
**File:** `app/src/main/java/eu/kanade/tachiyomi/ui/library/manga/ManhwaLibraryScreenModel.kt:1-11` (extends `MangaLibraryScreenModel`; both tabs permanent in `NavStyle.kt:41-52`).
**Problem.** `ManhwaLibraryScreenModel : MangaLibraryScreenModel(libraryType = MANHWA)` exists only to get a distinct Voyager instance. Both tabs are permanent bottom-nav entries, so once visited both stay alive and each runs the entire library pipeline in `init{}`. The `MangaType.MANHWA` filter is applied **last** in `applyFilters`, so the Manhwa instance processes the *whole* library before discarding non-manhwa entries.
**Why it costs.** Every library/track/`downloadCache` change drives **two** full SQLDelight subscriptions + two O(N) map+sort passes + two retained library maps. Verifier corrected two over-claims: `getDownloadCount` is an **in-memory** HashMap lookup (not disk I/O), and the whole pipeline is on `launchIO` (no UI jank). Net: doubled background CPU + memory scaling with library size — resource waste, not user-visible.
**Fix.** Hoist the expensive map/download-count/sort into one shared upstream flow (shared interactor, or one model exposing both partitions); have each tab apply only the cheap `MangaType` partition filter on the already-computed result. (Note: the current duplication exists because this Voyager version's `rememberScreenModel` lacks a `tag` param.)
**Verify.** Counter in `getLibraryFlow`'s combine block: with both tabs visited, a `mark-read` currently fires it twice; once after the fix.

### F6 — Library `searchQuery` flow lacks `distinctUntilChanged`, re-running filter+sort on every state change — **Low / Med**
**File:** `MangaLibraryScreenModel.kt:106-134` (sibling `AnimeLibraryScreenModel.kt:109-110`). `distinctUntilChanged` is already imported (`:40`).
```kotlin
combine(
    state.map { it.searchQuery }.debounce(SEARCH_DEBOUNCE_MILLIS),
    getLibraryFlow(), ...
) { searchQuery, library, tracks, trackingFilter, _ -> library.applyFilters(...).applySort(...) }
```
**Problem.** `Flow.map` doesn't dedupe; the `state` `StateFlow` emits on every distinct `State` (selection toggle, dialog open/close, …), each re-emitting the unchanged `searchQuery`. After the debounce, the combine re-runs `applyFilters().applySort()` over the whole library, and `MutableStateFlow` equality then discards the identical result.
**Why it costs.** Wasted IO-thread CPU/battery: a `getLibraryItemPreferencesFlow().first()` re-collect (~12 prefs), a `fastFilter`, and a full O(n log n) sort per spurious pass. Verifier corrected the original: the `downloaded` filter short-circuits unless enabled, so it's *not* a per-item `getDownloadCount`; and `collectLatest` + debounce mitigate. Off-main, so no jank → genuinely Low.
**Fix.** Add `.distinctUntilChanged()` after `state.map { it.searchQuery }` (mirror in anime).
**Verify.** Log entry into the transform; in selection mode on a large library it currently re-runs ~250 ms after each toggle, and stops after the fix.

### F7 — `mangas_categories` junction table has no index on `manga_id` — **Med / High**
**File:** `data/src/main/sqldelight/data/mangas_categories.sq` (sibling `data/src/main/sqldelightanime/dataanime/animes_categories.sq`).
```sql
CREATE TABLE mangas_categories(
    _id INTEGER NOT NULL PRIMARY KEY,
    manga_id INTEGER NOT NULL,
    category_id INTEGER NOT NULL,
    FOREIGN KEY(category_id) REFERENCES categories (_id) ON DELETE CASCADE,
    FOREIGN KEY(manga_id) REFERENCES mangas (_id) ON DELETE CASCADE
);  -- no CREATE INDEX
```
**Problem.** Both FKs are declared but no index exists. SQLite does not auto-index FK child columns, so lookups by `manga_id` have no index to use. The project indexes other FK columns (`chapters(manga_id)`, `excluded_scanlators`), so this is a real inconsistency.
**Why it costs.** `libraryView.sq:31-32` does `LEFT JOIN mangas_categories MC ON MC.manga_id = M._id`, and `categories.sq:47-69` (`getCategoriesByMangaId`) filters `WHERE MC.manga_id = :id`. `getLibraryMangaAsFlow` (`MangaRepositoryImpl.kt:60-61`) re-emits on any referenced-table change, so a `mark-read` re-runs the join. Verifier corrected the "quadratic" framing → SQLite builds a transient automatic index for the unindexed join, and this small table is dwarfed by the (already-indexed) chapter aggregate; the index helps but isn't the library's main bottleneck.
**Fix.** Add `CREATE INDEX mangas_categories_manga_id_index ON mangas_categories(manga_id);` (+ `category_id`, + the anime equivalents) via new `.sqm` migrations.
**Verify.** `EXPLAIN QUERY PLAN SELECT * FROM libraryView;` — `SCAN mangas_categories` → `SEARCH … USING INDEX`.

### F8 — `libraryView` recomputes a full-table chapter aggregate on every chapter/history mutation — **Med / High**
**File:** `data/src/main/sqldelight/view/libraryView.sq:1-37` (sibling `animelibView.sq` is heavier — composes 3 aggregate sub-views).
```sql
FROM chapters
    LEFT JOIN excluded_scanlators ...
    LEFT JOIN history ON chapters._id = history.chapter_id
    WHERE excluded_scanlators.scanlator IS NULL
    GROUP BY chapters.manga_id
```
**Problem.** The library list is backed by `subscribeToList(library())`. The view's correlated subquery aggregates count/sum/max across **all chapters of all favorites** + joins history. SQLDelight's `asFlow` re-runs the **entire** query whenever any referenced table (`chapters`, `history`, `mangas`, `excluded_scanlators`, `mangas_categories`) changes.
**Why it costs.** Marking one chapter read writes `chapters` + `history` rows → invalidates the query → full O(total chapters in library) GROUP BY re-executes and the whole result re-maps to `LibraryManga` (`MangaRepositoryImpl.kt:60-62`), and the library model stays subscribed behind the reader in the Voyager back stack. Off-main (SQLDelight IO dispatcher), so background CPU/IO churn that scales poorly with library size — no jank, hence Med.
**Fix.** Shortest path: the F7 index removes the unindexed join from the recompute. Longer term: `conflate`/debounce library emissions, or denormalize per-manga aggregate columns (`read`/`total`/`latestUpload`) maintained by triggers so the library query is a plain `SELECT`.
**Verify.** SQLDelight query-time logging while marking chapters read on a large seeded library; confirm each read triggers a full-aggregate re-run, measure reduction after debounce/denormalize.

### F9 — N+1 category queries computing common/mix categories for a selection — **Med / High**
**File:** `MangaLibraryScreenModel.kt:441-460` (sibling anime). `getCategoriesByMangaId` → `categories.sq:47-57`.
```kotlin
return mangas
    .map { getCategories.await(it.id).toSet() }
    .reduce { s1, s2 -> s1.intersect(s2) }
```
**Problem.** `getCommonCategories` and `getMixCategories` each issue one `getCategories.await(id)` per selected manga — a separate DB round-trip running `getCategoriesByMangaId`, which (per F7) scans the unindexed junction.
**Why it costs.** Triggered on multi-select → open Set-categories dialog. Verifier found it's effectively **2×S** queries (`openChangeCategoryDialog` calls both functions, each re-fetching the same per-manga sets), off-main so it's latency-before-dialog, not jank. Negligible for small selections; a select-all over a large library yields hundreds–thousands of sequential full-scan queries before the dialog appears.
**Fix.** Single batched query `WHERE manga_id IN :ids` returning `(manga_id, category_id)` pairs, grouped in memory; dedupe the double fetch (compute common+mix in one pass). Combined with F7 this collapses S scans into one indexed range scan.
**Verify.** Select N entries, open the dialog, count DB queries via SQLDelight logging — should drop from ~2N to 1.

### F10 — Player does synchronous SAF/disk file I/O on the main thread in `onCreate` — **Med / High**
**File:** `app/src/main/java/eu/kanade/tachiyomi/ui/player/PlayerActivity.kt:230` (call) and `412-503` (`setupPlayerMPV`/`copyUserFiles`/`copyAssets`). `copyFontsDirectory` (`505-521`) already shows the correct IO offload.
```kotlin
override fun onCreate(...) { ...; setupPlayerMPV() }   // main thread
// inside: writes mpv.conf/input.conf, deletes+recreates scripts/scriptopts/shaders,
// re-reads+writes aniyomi.lua, and (if mpvUserFiles) SAF listFiles()/copyTo on main
```
**Problem.** `setupPlayerMPV()` runs on the UI thread during Activity creation. It writes conf files, deletes & recreates dirs every launch, re-reads/writes the `aniyomi.lua` bridge asset, and — when `mpvUserFiles` is enabled — does `listFiles()` + `openInputStream().copyTo(openOutputStream())` over **content URIs** on main. Only `copyFontsDirectory` is offloaded.
**Why it costs.** Blocking disk/SAF I/O delays time-to-first-frame on **every** player launch; SAF copy over content URIs is the genuine ANR-risk path. Verifier caps at Med: `copyAssets` has a size-skip so `subfont.ttf`/`cacert.pem` aren't recopied after first launch, and `player.initialize()` depends on the config dir being populated, so naive full offloading needs sequencing — but the SAF user-file copies and dir churn can clearly move off-main.
**Fix.** Wrap the pre-`initialize` file work in `withContext(Dispatchers.IO)` (mirroring `copyFontsDirectory`) and await before MPV options that depend on it; skip re-copying `aniyomi.lua`/re-deleting dirs when unchanged; at minimum gate the SAF copy loops behind a background coroutine.
**Verify.** Perfetto main thread during player open — no `openInputStream`/`FileChannel` frames on UI; measure time-to-first-frame with custom mpv user files on SAF.

### F11 — Library cover write buffers the entire image into memory via `peekBody(Long.MAX_VALUE)` — **Low / Med**
**File:** `app/src/main/java/eu/kanade/tachiyomi/data/coil/MangaCoverFetcher.kt:220-231` (sibling `AnimeImageFetcher.kt:221-232`).
```kotlin
response.peekBody(Long.MAX_VALUE).source().use { input ->
    writeSourceToCoverCache(input, cacheFile)
}
```
**Problem.** `peekBody(Long.MAX_VALUE)` copies the full response body into an in-memory okio `Buffer` before writing it to the cover-cache file — and the peek is pointless here (when the cover-cache file path is taken, `httpLoader()` at `:139-142` returns `fileLoader` and never reuses the body; line 261 already streams correctly via `response.body.source()`).
**Why it costs.** `App.kt:219` sets `fetcherCoroutineContext = Dispatchers.IO.limitedParallelism(8)`, so during a full library cover refresh up to 8 full **encoded** images buffer simultaneously. Verifier caps at Low: these are encoded bytes (tens–hundreds KB), not decoded bitmaps, so 8 concurrent peeks ≈ a few MB transient heap, only on the first network fetch per cover (cached thereafter) — minor GC pressure, not jank.
**Fix.** Stream `response.body.source()` straight to the cache file (drop `peekBody`), as `writeToDiskCache` already does.
**Verify.** Memory Profiler allocation tracking during a cover refresh — peak transient `Buffer`/`byte[]` should drop.

### F12 — Seekbar re-allocates the chapter segment list every second during playback — **Low / Med**
**File:** `app/src/main/java/eu/kanade/tachiyomi/ui/player/controls/PlayerControls.kt:380-396` (same pattern at `:584`).
```kotlin
SeekbarWithTimers(
    position = position, ...
    chapters = chapters.map { it.toSegment() }.toImmutableList(),
)
```
**Problem.** The lambda reads `position` (updated ~1×/sec by the mpv `time-pos` observer → `PlayerViewModel.kt:240-241`), so the whole scope recomposes each tick and re-runs `chapters.map { it.toSegment() }.toImmutableList()`, allocating a fresh list independent of whether `chapters` changed.
**Why it costs.** Per-tick allocation churn while the controls are visible. Verifier corrected two over-claims: kotlinx immutable lists are Compose-stable with content equality so the child **still skips** (only the alloc is wasted), and `AnimatedVisibility` content is only composed while controls are shown (auto-hide), so it's bursty, and `chapters` is typically small/empty.
**Fix.** `val segments = remember(chapters) { chapters.map { it.toSegment() }.toImmutableList() }`; pass `segments`.
**Verify.** Allocation tracking on `SeekbarWithTimers` during playback — per-second list alloc disappears.

### F13 — Full stack-trace capture on every HTTP request (`await`/`awaitSuccess`) — **Low / Med**
**File:** `core/common/src/main/java/eu/kanade/tachiyomi/network/OkHttpExtensions.kt:102-118`.
```kotlin
suspend fun Call.awaitSuccess(): Response {
    val callStack = Exception().stackTrace.run { copyOfRange(1, size) }
    val response = await(callStack)
```
**Problem.** Every suspending OkHttp call eagerly allocates an `Exception` and materializes a full `StackTraceElement[]` (ART decodes the native backtrace, resolving class/method/file/line per frame) **before enqueue, on both paths** — yet the array is only consumed on the rare `IOException`/`HttpException` re-stamp.
**Why it costs.** Real wasted allocation/CPU per successful request. Verifier downgraded **High→Low**: it runs on background threads (Coil IO dispatcher / `launchIO`), so no UI jank; each capture precedes a network round-trip tens–hundreds of ms long (the stack work is <1% of it); and the "library-grid fling" claim is wrong (covers are cache-served — the fetcher only runs on a cache miss). It's deliberate upstream behavior to keep async `IOException`s debuggable.
**Fix.** Defer the capture into the failure branch only (or gate behind a verbose-logging pref). Small payoff; lowest priority.
**Verify.** Microbench `awaitSuccess` in a tight loop vs localhost; compare `StackTraceElement[]` allocs.

### F14 — Global-search screen models leak a 5-thread `ExecutorService` per instance — **Med / High**
**File:** `app/src/main/java/eu/kanade/tachiyomi/ui/browse/manga/source/globalsearch/MangaSearchScreenModel.kt:46` (sibling `…/anime/…/AnimeSearchScreenModel.kt:46`; subclasses `Global*`/`Migrate*` inherit it).
```kotlin
private val coroutineDispatcher = Executors.newFixedThreadPool(5).asCoroutineDispatcher()
```
**Problem.** Each model creates a private fixed pool of 5 non-daemon threads to bound per-source search concurrency, but there is **no `onDispose`/`shutdown`/`close`**. `newFixedThreadPool` keeps its 5 core threads alive forever (`keepAlive=0`, `allowCoreThreadTimeOut=false`); `asCoroutineDispatcher` registers no cleanup; Voyager cancels the scope but doesn't close a custom dispatcher.
**Why it costs.** Each global/source/migrate search open leaks 5 non-daemon threads (~0.5–1 MB stack each), and the pool's strong refs keep them un-GC'able. Accumulates across navigation — a power user who searches repeatedly grows the thread count linearly. Per-navigation cadence (not per-frame), so lower-end Med.
**Fix.** `private val coroutineDispatcher = Dispatchers.IO.limitedParallelism(5)` — same max-5 concurrency, no owned threads to leak. (If keeping the pool, override `onDispose { coroutineDispatcher.close() }` in the abstract base + anime sibling.)
**Verify.** Open/close global search ~20×, thread dump (`dumpsys`/Profiler) — `pool-N-thread-*` count should stop growing.

### F15 — PiP `BroadcastReceiver` not unregistered in `onDestroy` — **Med / Med**
**File:** `app/src/main/java/eu/kanade/tachiyomi/ui/player/PlayerActivity.kt:288-311` (`onDestroy`), `786-821` (receiver; registered `:814/816`, unregistered only at `:790`).
```kotlin
override fun onDestroy() {
    ...
    if (noisyReceiver.initialized) { unregisterReceiver(noisyReceiver); ... }
    // pipReceiver is never unregistered here
    MPVLib.removeObserver(playerObserver)
```
**Problem.** `pipReceiver` (anonymous, captures the `PlayerActivity` + `viewModel`) is unregistered **only** inside `onPictureInPictureModeChanged(false)`. If the user dismisses the floating PiP window directly (finishes the Activity) without that callback firing, the receiver stays registered at destroy time.
**Why it costs.** Android's `LoadedApk` retains registered receivers until explicit unregister, so the destroyed Activity object graph (incl. `viewModel`) leaks until process death ("Activity has leaked IntentReceiver"), one per occurrence. Verifier caps at Med/Med: `player.destroy()` still frees the heavy **native** mpv handles, so only the Java graph leaks; and whether `onPictureInPictureModeChanged(false)` fires on direct dismissal is version/path-dependent.
**Fix.** In `onDestroy`: `pipReceiver?.let { runCatching { unregisterReceiver(it) } }; pipReceiver = null` — mirror the `noisyReceiver` guard.
**Verify.** Re-enable LeakCanary / StrictMode, enter PiP, swipe-close the PiP window — no leaked-receiver warning; `PlayerActivity` is GC'd.

### F16 — Extension-icon `Drawable` cache (`iconMap`) never evicts uninstalled extensions — **Low / High**
**File:** `app/src/main/java/eu/kanade/tachiyomi/extension/manga/MangaExtensionManager.kt:63,105-108,323-324` (sibling `AnimeExtensionManager.kt:66,108,~329`). Process-lifetime singleton (`AppModule.kt:198`).
```kotlin
private val iconMap = mutableMapOf<String, Drawable>()
...
return iconMap[pkgName] ?: iconMap.getOrPut(pkgName) { /* loadIcon(...) */ }
...
private fun unregisterExtension(pkgName: String) {
    installedExtensionsMapFlow.value -= pkgName  // iconMap[pkgName] not removed
}
```
**Problem.** `iconMap` memoizes launcher-icon `BitmapDrawable`s keyed by package; `unregisterExtension` removes from the flow maps but never from `iconMap`, so a removed extension's icon is retained for the process lifetime.
**Why it costs.** Modest, bounded leak: entries are added only when an icon is loaded and orphaned only on uninstall-after-load within a session; retained objects are launcher-sized bitmaps (tens of KB each). Not a hot path.
**Fix.** Add `iconMap -= pkgName` in `unregisterExtension` (both managers); optionally cap map size.
**Verify.** Install several extensions, open Sources, uninstall, GC, heap dump — no `BitmapDrawable` keyed to removed packages.

### F17 — Always-on in-memory log ring buffer retains up to ~20 MB regardless of logging preference — **Low / Med**
**File:** `core/common/src/main/java/tachiyomi/core/common/util/system/InMemoryLogcatBuffer.kt:16-54` (installed unconditionally at `App.kt:165-172`).
```kotlin
private const val MAX_ENTRIES = 2000
private const val MAX_MESSAGE_LENGTH = 10_000
override fun isLoggable(priority: LogPriority): Boolean = true   // captures ALL priorities
```
**Problem.** A process-lifetime singleton captures **every** log call (`isLoggable` always true) into a 2000-entry `ArrayDeque` of up to 10 KB strings — even when verbose logging is off (only the *adb* logger is gated). Worst case ~20 MB resident until `clear()`.
**Why it costs.** Always-resident allocation added by this fork that competes with Coil's bitmap cache on low-RAM devices. Verifier caps at Low: the 20 MB ceiling (2000 × 10 KB) is essentially never reached — real log sites emit short strings, so realistic steady-state is sub-1 MB to a few MB; it's correctly **bounded** (not a runaway leak), and powering an in-app Debug-Logs screen without adb is a legitimate design choice.
**Fix.** Lower `MAX_ENTRIES`/`MAX_MESSAGE_LENGTH`, or keep only WARN+ERROR always-on and gate full capture behind the verbose-logging flag.
**Verify.** Heap dump under sustained logging — retained size of `InMemoryLogcatBuffer.entries` within budget on a 2 GB device.

### F18 — Baseline profile is hand-maintained and never auto-regenerated; plugin not applied, no startup profile — **Low / High**
**File:** `app/build.gradle.kts:5-11` (no `androidx.baselineprofile`); `macrobenchmark/build.gradle.kts` applies only `mihon.benchmark` (→ `com.android.test`); `BaselineProfileGenerator.kt` exists but is not build-wired.
**Problem.** `app/src/main/baseline-prof.txt` (37,789 lines, 3.9 MB) **is** installed/consumed (`profileinstaller` dep, `minSdk 26`), but the `androidx.baselineprofile` plugin is applied nowhere and the generator isn't hooked into the build — the profile is only updatable by manually copying upstream output. Content check confirms drift: it references only upstream `Landroidx/…`/`eu/kanade` classes, **0 `manhwa` hits**, and there's no `startup-prof.txt`.
**Why it costs.** Verifier lowered Med→Low: a large profile **is** present and already covers the dominant androidx/Compose cold-start + scroll hot paths (the biggest AOT win is captured). The real gap is (1) no auto-regen → silent drift, and (2) **zero AOT coverage of fork-added screens** (Manhwa library tab, anime flows) that users hit on launch → incremental, screen-specific cold-start/jank cost + a maintenance hazard.
**Fix.** Apply `androidx.baselineprofile` to `:app` and `:macrobenchmark`, add the `baselineProfile { }` consumer dependency from `:app` on `:macrobenchmark`, wire `:app:generateBaselineProfile` into CI/release, and add an `@StartupProfile` to the generator.
**Verify.** Macrobenchmark `StartupTimingBenchmark` (`CompilationMode.Partial` with the generated profile vs `None`) measuring `timeToInitialDisplay` on a release build, before/after.

### F19 — `android.nonTransitiveRClass=false` bloats R classes and slows incremental builds — **Low / Med**
**File:** `gradle.properties:1`.
**Problem.** Non-transitive R is explicitly disabled, so each module's R class re-declares all resource fields of every dependency.
**Why it costs.** **Build-time only**, no runtime/user-visible cost. Verifier corrected the over-claim: only 3 modules actually have `res/` (app, presentation-core, presentation-widget); the other ~10 carry no resources, so there's little transitive R to inflate, and R8 strips unused fields. Real but minor and at the edge of scope.
**Fix.** Set `android.nonTransitiveRClass=true` and run AGP's *Migrate to non-transitive R classes* (it must rewrite ~367 `R.*` references — moderate, not 1 line).
**Verify.** Compare incremental build wall time + generated `R$*.class` count; confirm no unresolved `R` refs.

### F20 — Configuration cache not enabled; `configureondemand=true` (deprecated / CC-incompatible) — **Low / Med**
**File:** `gradle.properties:7-10`.
```properties
org.gradle.caching=true
org.gradle.configureondemand=true
org.gradle.parallel=true
```
**Problem.** Caching/parallel are on but `org.gradle.configuration-cache` is absent, and `configureondemand=true` is unsupported by AGP (can yield incorrect task graphs) and redundant with CC.
**Why it costs.** Build-time only. Every invocation re-runs configuration, including the `getCommitCount()`/`getGitSha()`/`getBuildTime()` git subprocesses (`Commands.kt:37-45`, via the CC-friendly `providers.exec`). Verifier: enabling CC is **not** a 1-line change (it surfaces incompatibilities across a large Mihon-fork graph) — split the work.
**Fix.** Now (trivial/safe): drop `org.gradle.configureondemand`. Later (validated effort): add `org.gradle.configuration-cache=true` starting with `…problems=warn`, fixing incompatibilities incrementally.
**Verify.** No-op build twice — second run reports "Reusing configuration cache"; `--profile` shows reduced configuration time.

### F21 — Notification channels created synchronously on the main thread in `App.onCreate` every cold start — **Low / Med**
**File:** `app/src/main/java/eu/kanade/tachiyomi/App.kt:108` → `Notifications.createChannels` (`Notifications.kt:102-177`).
**Problem.** `setupNotificationChannels()` runs inline on the main thread before first frame on every process start, though channel creation is idempotent.
**Why it costs.** On the critical cold-start path. Verifier corrected the magnitude: it's **11 channels + 4 groups** (not "~16"), and API 26+ batches them into a single binder call per list (`createNotificationChannel*sCompat`), so it's ~11 binder transactions (the deprecated `deleteNotificationChannel` calls), not dozens; moko string lookups are in-memory. Realistic cost is low tens of ms, idempotent after first install/upgrade → Low.
**Fix.** Move onto a background dispatcher (`ProcessLifecycleOwner` scope `launchIO`), or gate behind a version/pref flag so it isn't redone every launch.
**Verify.** Perfetto `App.onCreate` — main-thread time in `createChannels` before/after; confirm channels still present.

### F22 — `Migrator.awaitAndRelease()` blocks the main thread with `runBlocking` during install/upgrade launches — **Low / Med**
**File:** `app/src/main/java/eu/kanade/tachiyomi/ui/main/MainActivity.kt:159` → `Migrator.kt:38-40` (`runBlocking { await().also { release() } }`).
**Problem.** `MainActivity.onCreate` blocks the UI thread inside `runBlocking` until the migration chain (started on `Migrator.scope` = `Dispatchers.IO` in `App.kt:178-191`) completes.
**Why it costs.** Verifier: on normal launches `old>=new` → `NoopMigrationStrategy` with an already-complete `CompletableDeferred`, so `runBlocking` returns **instantly** (zero cost on the common path). Real cost only on fresh install / upgrade, and those `isAlways` migrations are lightweight idempotent DB upserts (tens of ms), gated by the splash. It's the stock upstream pattern, not a fork regression → Low, not a hot path.
**Fix.** Make migration completion `suspend` (await in a `LaunchedEffect`, keep the existing splash "ready" flag) instead of `runBlocking`.
**Verify.** Bump `versionCode` on a populated DB, cold-start with Perfetto — no main-thread block at `awaitAndRelease`.

---

## Quick wins (low-effort, high-confidence, safe to land first)

Ordered by impact-per-line. **Bold = also apply to the anime sibling.**

1. **F1 — add `key = { it.libraryManga.id }`** to all 6 library `items()` calls. *~1 line ×6.* Hottest screen, clearest win. **(anime ×3)**
2. **F14 — `Dispatchers.IO.limitedParallelism(5)`** instead of the owned thread pool in both search models. *~1 line ×2.* Removes a real thread leak. **(anime)**
3. **F16 — `iconMap -= pkgName`** in `unregisterExtension`, both managers. *~1 line ×2.* **(anime)**
4. **F6 — `.distinctUntilChanged()`** after `state.map { it.searchQuery }`. *1 line ×2 (already imported).* **(anime)**
5. **F7 — `CREATE INDEX … ON mangas_categories(manga_id)`** (+ `category_id`, + anime) via `.sqm`. *~1 line + migration ×2.* **(anime)**
6. **F12 — `remember(chapters) { … toImmutableList() }`** in `PlayerControls`. *1 line.*
7. **F11 — drop `peekBody`, stream `response.body.source()`** in both fetchers. *~1 line ×2.* **(anime)**
8. **F2 — wrap the date conversion in `remember(dateEpochMillis, …)`** in `DateText`. *~4 lines.*
9. **F15 — unregister `pipReceiver` in `onDestroy`.** *~3 lines.*
10. **F20 (partial) — delete `org.gradle.configureondemand=true`.** *1 line, build-only.*

These ten are independent, individually testable, and most are mechanical mirrors of patterns the codebase already uses correctly elsewhere.

## Measurement plan

Static findings are hypotheses until measured. Instrument before/after:

- **Compose recomposition (F1, F2, F3, F12).** Layout Inspector recomposition counts, or `Modifier.Companion` recomposition highlighting / `Recomposer` counts in a debug build. Scenarios: sort/filter toggle on a 500-item library (F1), select-all on a 200-item category (F3), selection toggle on a long chapter list (F2), controls-visible playback (F12).
- **Main-thread stalls (F4, F10, F22, F21).** Perfetto/systrace `App.onCreate` and Activity `onCreate` slices; add custom `Trace.beginSection` around `setupPlayerMPV`, `setupNotificationChannels`, `awaitAndRelease`, and the external-player result branch. Watch for `BinderProxy`, `openInputStream`, `FileChannel` frames on the main thread. Pair with **StrictMode** `detectDiskReads()/detectDiskWrites()` + `penaltyLog` to catch main-thread I/O regressions automatically.
- **DB query cost (F7, F8, F9).** Seed a DB (≈2k favorites, several categories each, large chapter counts). `EXPLAIN QUERY PLAN` for `libraryView` and `getCategoriesByMangaId` (F7). Enable SQLDelight query logging and count queries when opening the category dialog (F9) and time library-flow emission latency while marking a chapter read (F8).
- **Allocation / memory (F11, F13, F16, F17).** Android Studio Memory Profiler allocation tracking during: a full library cover refresh (F11 `Buffer`/`byte[]`; F13 `StackTraceElement[]`); install→browse→uninstall cycles + heap dump (F16); sustained logging + heap dump for `InMemoryLogcatBuffer` retained size (F17).
- **Thread / receiver leaks (F14, F15).** Re-enable LeakCanary (currently disabled — see Refuted R2) for the duration; thread dump after repeated search opens (F14); heap dump after swipe-closing a PiP window (F15).
- **Cold start & baseline profile (F18).** Macrobenchmark `StartupTimingBenchmark` with `CompilationMode.Partial(baselineProfile)` vs `None`, `timeToInitialDisplay` + `timeToFullDisplay`, on a release build — first wire the generator (F18) so the comparison is meaningful.
- **Build (F19, F20).** `./gradlew --profile` clean + incremental, twice, comparing configuration-phase time and (F19) generated `R$*.class` count.

**Suggested standing harness:** the `macrobenchmark` module already exists — add a `FrameTimingBenchmark` scrolling the library grid and a `StartupTimingBenchmark`, run in CI on the `benchmark` build type (`app/build.gradle.kts:74-85`, already `isProfileable`), to catch regressions on F1/F18 going forward.

## Refuted (verified NOT actionable)

- **R1 — `genre` `List<String>` decoded per library row "though the grid never shows it"** (`MangaMapper.kt:63-131`). The split *does* happen per row, but the premise is wrong: `genre` is consumed by the library **tag/search filter** (`MangaLibraryItem.kt:36`), so it's functional, not dead weight; and the single `ArrayList` is negligible against the 26-column object graph already allocated per row. *(Cited DatabaseAdapter path in the original was wrong — actual is `data/src/main/java/tachiyomi/data/DatabaseAdapter.kt:14-25`.)*
- **R2 — "LeakCanary disabled removes the leak-detection safety net"** (`app/build.gradle.kts:296-299`). Factually true (`leakcanary-android` commented out, only `leakcanary-plumber` kept) but **out of scope**: it's a `debugImplementation` dep with **zero** effect on the shipping APK's runtime/size/resources. It's a dev-tooling observation, not a runtime defect. *(Recommendation stands: re-enable it temporarily to measure F14/F15/F16/F25-style leaks.)*

## Explicitly out of scope / needs runtime profiling

- **Whether F1's reorder churn actually drops frames** vs. is absorbed by Compose's lazy reuse — needs the recomposition trace above; the *code defect* is certain, the *frame cost* is the hypothesis.
- **F8 denormalization** (aggregate columns + triggers) is a correctness-sensitive schema change; only pursue if the index (F7) + `conflate` don't bring library-flow latency into budget on a large seeded DB.
- **mpv buffering / native resource tuning** beyond F10/F15 (cache size, demuxer thresholds, decoder selection) requires on-device playback profiling across networks/codecs — not statically assessable.
- **OkHttp connection-pool / dispatcher `maxRequests` tuning** for library-update fan-out: no misconfiguration found statically, but optimal concurrency vs. source rate-limits needs a real library-update trace.
- **Coil memory-cache sizing** vs. device RAM and the F17 log buffer's competition for heap — needs on-device memory-pressure measurement on a 2 GB device.
- **Transitive build-time gains** from F19/F20 — magnitude is build-machine-dependent; measure with `--profile` and a build scan before committing to the migrations.
