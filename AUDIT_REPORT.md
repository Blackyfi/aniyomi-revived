# Aniyomi-revived — Security & Code Audit Report

> Generated 2026-05-29 via a fan-out multi-agent audit (10 specialized finders across
> security / logic / errors / code-quality / warnings, each finding adversarially
> verified against the source before inclusion). 37 candidates → **29 confirmed**, 8 refuted.

## Executive Summary

Severities below are the **adjusted** (post-verification) ratings, which in several cases
are lower than the original finder claims because the issues are inherited upstream
behavior, require user interaction, or have limited practical blast radius.

| Severity | Count |
|----------|-------|
| High     | 3     |
| Medium   | 4     |
| Low      | 22    |
| **Total**| **29**|

### Top things to fix first

1. **Season-to-parent corruption on restore** (High, `AnimeRestorer.kt`) — the fork's headline season-grouping feature breaks on any cross-device backup restore because seasons are re-written with stale backup-time `parentId`s. Core data path, silent corruption.
2. **Episode flag bit-mask remap with no DB migration** (High, `Anime.kt`) — a routine app update silently changes sort order *and* hides episodes for existing library entries, because persisted `episode_flags` bits were re-assigned without migrating the column.
3. **Anime extension installer intent redirection** (Medium, `PackageInstallerInstallerAnime.kt`) — the anime installer's status receiver is `RECEIVER_EXPORTED` and forwards an unsanitized nested intent, letting any installed app drive Aniyomi into launching an attacker-chosen Activity from its own context. The manga sibling was already hardened; the anime variant diverged.
4. **OAuth flows omit `state`/CSRF and the callback validates only the host** (Medium, `TrackLoginActivity.kt`) — the exported deep-link callback trusts any `code`/`token` delivered to it, enabling tracker-account login-CSRF.
5. **Path traversal / arbitrary file write on backup restore** (Medium, `ExtensionsRestorer.kt`) — unsanitized `pkgName` used as a filename allows writing attacker bytes outside `cacheDir` under the app UID when a crafted backup is imported.

Network/TLS surface note: the TLS validation chain itself is intact (no custom
`X509TrustManager`/`HostnameVerifier`/`SSLSocketFactory` overrides exist in app source).
The network weaknesses found (cleartext allowed, user-CA trust, Safe Browsing disabled)
are all intentional, inherited trade-offs rated low.

## Remediation status — branch `fix/audit-findings`

Compiles clean; formatted with `spotlessApply`; verified with `:app:assembleDebug`.

**Fixed (19):**
- #4 MyAnimeList PKCE upgraded from `plain` to `S256` (`code_challenge_method=S256`, SHA-256 challenge; raw verifier still sent at the token endpoint).
- #9 Backup decompression capped (`BackupDecoder` aborts past 200 MB) to stop gzip decompression bombs.
- #10 User-added CA trust restricted to debug builds (release `network_security_config` trusts system CAs only; debug source set keeps user CAs for proxying).
- #1 Anime installer hardened to mirror the manga sibling (`RECEIVER_NOT_EXPORTED` + `IntentSanitizer`).
- #2 OAuth `state`/CSRF added to all 5 trackers (`TrackerOAuthState` + `state` in every `authUrl()`, validated on callback).
- #3 Backup `pkgName` path-traversal guarded (package-name regex + canonical-path containment check).
- #12 Season-restore parent linkage fixed (seasons restored once, with the correct new `parentId`; old-backup `null==null` season match also guarded).
- #13 Episode-flags remap migration added (`136.sqm`) — **see caveat below**.
- #14 Anime source-mapping copy-paste fixed (`animeSourceMapping`).
- #15 Dead `StateFlow == List` guard fixed (`queueState.value`).
- #16 Stub-source collector now writes back into `stubSourcesMap` (anime + manga).
- #17 AniList interceptor NPE (dead null-check) fixed.
- #18 `DataSaver.getUrl` falls back to original URL instead of returning a bogus body.
- #19 `AniChartApi.toUnixTimestamp` no longer crashes the screen-model coroutine on a bad date.
- #20 `AniSkipApi.getMalIdFromAL` closes the response and reads the body inside the try.
- #21 Kitsu `refreshToken` `!!` replaced with a thrown `IOException`.
- #22 `setMangaReadingMode` moved off the UI thread (`viewModelScope.launchIO`).
- #23 `PlayerActivity` noisy receiver registered with an explicit non-exported flag.
- #24/#26/#27/#28 lint/quality cleanups (null-safe system service, `@Volatile`, exhaustive `when`, `VERSION_CODES.R`).

**#13 caveat (user-accepted risk):** the seasons feature already shipped in the released
lineage, so some users already hold `episode_flags` in the *new* bit layout. Old- and
new-layout values overlap and are indistinguishable, so `136.sqm` may re-map rows that were
already migrated. This is an accepted one-time trade-off, documented in the migration file.

**Deliberately not changed (with rationale):**
- **#5 AniList implicit flow** — AniList's authorization-code grant requires a `client_secret`; embedding one in an open-source public client is itself an anti-pattern, so the implicit flow is the correct choice for this app. The CSRF half is already mitigated by the new `state` (#2); the token-in-fragment is inherent to the flow.
- **#6 Extension-restore signature pre-validation** — the OS package installer already shows the APK's real parsed identity/permissions, extension restore is off by default, and the path-traversal write is now fixed (#3). Residual is a social-engineering UX gap; pre-validation couples to the extension loader and was left out to keep scope contained.
- **#7 WebView cross-origin headers** — the default header set is User-Agent only (no secret), and the `shouldInterceptRequest` leak was refuted; scoping headers risks breaking the Cloudflare-bypass/source flows for no real security gain.
- **#8 Plaintext token storage** — `EncryptedSharedPreferences` (androidx.security-crypto) is deprecated by Google, the change would touch the shared `PreferenceStore` and require migrating existing tokens (risk of breaking every tracker login), and the data is already app-sandbox isolated. Disproportionate for a low finding.
- **#11 WebView Safe Browsing disabled** — intentional privacy trade-off (Safe Browsing reports URL hashes to Google), consistent with the app's other privacy choices.
- **#25 Static dismiss callback** — not a demonstrable bug in the single-Activity Voyager model; left to avoid risking dialog-dismiss behavior for a cosmetic smell.

---

## Security

### 1. Anime extension installer is exported and forwards an unsanitized nested intent (intent redirection) — Medium
**File:** `app/src/main/java/eu/kanade/tachiyomi/extension/anime/installer/PackageInstallerInstallerAnime.kt:75-114`

The status receiver is registered with `ContextCompat.RECEIVER_EXPORTED` for a fixed action string, and the PendingIntent uses `FLAG_MUTABLE`. On `STATUS_PENDING_USER_ACTION`, `onReceive` reads the nested intent and launches it with no validation:
```kotlin
ContextCompat.registerReceiver(service, packageActionReceiver, IntentFilter(INSTALL_ACTION), ContextCompat.RECEIVER_EXPORTED)
...
val userAction = intent.getParcelableExtraCompat<Intent>(Intent.EXTRA_INTENT)
userAction.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
service.startActivity(userAction)
```
**Impact:** While an anime-extension install session is active, any app can broadcast `INSTALL_ACTION` with a crafted `EXTRA_INTENT` to make Aniyomi launch an arbitrary Activity from its own context (confused-deputy), and can spoof install status (`InstallStep` desync). The PendingIntent already targets `setPackage(service.packageName)`, so export is unnecessary. The manga sibling (`PackageInstallerInstallerManga.kt:29-42,127`) is already hardened with `RECEIVER_NOT_EXPORTED` + `IntentSanitizer`; the anime variant diverged.
**Fix:** Register with `RECEIVER_NOT_EXPORTED`, run `EXTRA_INTENT` through `IntentSanitizer`, and use `FLAG_IMMUTABLE` — mirroring the manga installer.

*(Merges two separately-reported findings against this file — exported intent-redirection and exported+mutable status-spoofing — same root cause/location.)*

### 2. OAuth flows omit the `state`/CSRF parameter; callback validates only the host — Medium
**File:** `app/src/main/java/eu/kanade/tachiyomi/ui/setting/track/TrackLoginActivity.kt:9-83`

Every tracker OAuth URL (AniList, MAL, Bangumi, Shikimori, Simkl) is built with no `state` parameter, and `handleResult()` dispatches solely on `data.host` then trusts the incoming `code`/`token`. The activity is `android:exported="true"` (`AndroidManifest.xml:233`) and registers the `aniyomi` scheme.
**Impact:** Any installed app or web page can fire `aniyomi://myanimelist-auth?code=ATTACKER_CODE`; the app exchanges it and binds the victim's app instance to the attacker's tracker account (login-CSRF). Requires user interaction, hence Medium.
**Fix:** Generate a `SecureRandom` `state` per request (as `PkceUtil` already does), append `&state=` to every `authUrl()`, persist transiently, reject any callback whose returned `state` doesn't match before calling `login()`.

### 3. Path traversal / arbitrary file write via unsanitized backup `pkgName` — Medium
**File:** `app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/restorers/ExtensionsRestorer.kt:14-31`

`pkgName` is deserialized verbatim from the imported backup and used directly as a filename:
```kotlin
val file = File(context.cacheDir, "${'$'}{it.pkgName}.apk")
file.writeBytes(it.apk)
```
The only guard checks `pkgName` against installed packages; a traversal value (`../../files/payload`) never matches, so the write proceeds.
**Impact:** A crafted backup writes attacker-controlled bytes outside `cacheDir` under the app's UID. Requires importing a crafted backup *and* enabling "extensions" restore (defaults to `false`), hence Medium.
**Fix:** Validate `pkgName` against a strict package-name regex (or `DiskUtil.buildValidFilename`), and verify `file.canonicalPath` starts with `context.cacheDir.canonicalPath + File.separator` before writing.

### 4. MyAnimeList PKCE uses the `plain` method — Low
**File:** `app/src/main/java/eu/kanade/tachiyomi/data/track/myanimelist/MyAnimeListApi.kt:392-428`

`authUrl()` sends the raw verifier as `code_challenge` and never sets `code_challenge_method`, defaulting to PKCE `plain`. With `plain`, the value in the redirect *is* the secret used at the token endpoint, so an app intercepting the custom-scheme redirect can complete the exchange. `codeVerifier` is also a shared mutable static.
**Fix:** Send `code_challenge = Base64URL(SHA-256(verifier))` with `code_challenge_method=S256`; keep the verifier per-attempt.

### 5. AniList uses the deprecated OAuth implicit flow (`response_type=token`) — Low
**File:** `app/src/main/java/eu/kanade/tachiyomi/data/track/anilist/AnilistApi.kt:584-587`

Bearer token arrives in the redirect fragment, regex-parsed in `TrackLoginActivity.handleAnilist()`; `createOAuth` fabricates a 1-year expiry with no refresh handling and no `state`.
**Fix:** Migrate to authorization-code + PKCE (S256) and validate `state`.

### 6. ExtensionsRestorer installs backup-supplied APK bytes with no extension/signature validation — Low
**File:** `app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/restorers/ExtensionsRestorer.kt:14-31`

Raw APK bytes from the backup are written and handed to an `ACTION_VIEW` install intent with no `isPackageAnExtension`/signature check. Low because the OS installer always prompts with the APK's real parsed identity, and an installed APK isn't loaded as an extension unless it passes the loader's signature checks. Residual = hardening/UX gap.
**Fix:** Pre-validate with `getPackageArchiveInfo` + `isPackageAnExtension`, confirm the real `packageName`, verify the signature against a trusted fingerprint before prompting.

### 7. WebView re-applies source headers to cross-origin navigations (Referer scoping gap) — Low
**File:** `app/src/main/java/eu/kanade/presentation/webview/WebViewScreenContent.kt:107-126`

`shouldOverrideUrlLoading` unconditionally re-applies the source's header map to every navigation target with no host check. *Note:* the originally-claimed subresource/secret leak was refuted — realistic leak is Referer/User-Agent following cross-origin.
**Fix:** Scope sensitive headers to requests whose host matches the source's base host; strip cross-origin.

### 8. OAuth access/refresh tokens persisted in plaintext SharedPreferences — Low
**File:** `app/src/main/java/eu/kanade/domain/track/service/TrackPreferences.kt:19-35`

`trackToken`/`trackPassword` are plain string prefs (no encryption). Readable with root/ADB/forensic access; sandbox-isolated otherwise, matches upstream.
**Fix:** Use `EncryptedSharedPreferences` / Jetpack Security with a Keystore-backed key.

### 9. Unbounded decompression of gzipped backup (decompression bomb) — Low
**File:** `app/src/main/java/eu/kanade/tachiyomi/data/backup/BackupDecoder.kt:33-39`

Entire decompressed gzip stream read into memory with no cap → OOM on a crafted `.tachibk`. User-initiated, availability-only.
**Fix:** Impose a decompressed-size cap, or decode the protobuf in a streaming manner.

### 10. Global cleartext HTTP permitted and user CAs trusted — Low
**File:** `app/src/main/res/xml/network_security_config.xml:4-16`

`cleartextTrafficPermitted="true"` + `<certificates src="user">`. Intentional (HTTP-only legacy sources, debug proxies); TLS chain otherwise intact.
**Fix:** Limit user-CA trust to debug builds via a debug/release config split.

### 11. WebView Safe Browsing disabled globally — Low
**File:** `app/src/main/AndroidManifest.xml:296-298`

`EnableSafeBrowsing=false` while loading arbitrary source sites. Intentional privacy trade-off.
**Fix:** Re-enable or document/scope the override.

---

## Logic

### 12. Season-to-parent relationship corrupted on restore — High
**File:** `app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/restorers/AnimeRestorer.kt:57-104`

Each season is both a child inside its parent's `backupSeasons` loop *and* a standalone top-level entry. The parent pass correctly re-points seasons (`copy(parentId = restoredAnime.id)`), but the season's own top-level pass overwrites it with the stale backup-time value (`anime.parentId`). `sortByNew` does not guarantee parents process after seasons.
**Impact:** On cross-device/fresh restores, auto-increment ids restart so the stale `parentId` matches no real `_id` → seasons orphaned, breaking the fork's headline season-grouping feature.
**Fix:** Build a `backupId → newId` map after inserting all top-level anime, then a second pass to set `parent_id`. Restore each season's details exactly once.

### 13. Episode flag bit-mask remap with no DB migration — High
**File:** `domain/src/main/java/tachiyomi/domain/entries/anime/model/Anime.kt:232-248`

The seasons/fillermark feature shifted persisted `episode_flags` sort bits without migrating the column. Old `EPISODE_SORTING_NUMBER=0x100` etc. became `0x200/0x400/0x600` (mask `0x600`), and `0x80/0x100` were reassigned to fillermark filters (mask `0x180`). No migration rewrites `episode_flags`.
**Impact:** On upgrade every existing entry is silently reinterpreted — e.g. old `NUMBER` (0x100) reverts sorting to SOURCE *and* turns on "hide filler", unexpectedly hiding episodes. Library-wide, no user action.
**Fix:** Add a `.sqm` migration relocating old sorting bits into the new `0x600` field and clearing the newly-claimed `0x80/0x100` bits for pre-existing rows (or move the new flags to unused higher bits).

### 14. Anime source mapping overwritten by manga mapping (copy-paste bug) — Low
**File:** `app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/BackupRestorer.kt:85-88`

Both lines assign to `mangaSourceMapping`; `animeSourceMapping` stays `emptyMap()`. Anime restore error logs fall back to numeric source IDs. Log-only; restore correctness unaffected.
**Fix:** Assign the first line to `animeSourceMapping`.

### 15. `updateQueue` guard compares a `StateFlow` to a `List` (dead code) — Low
**File:** `app/src/main/java/eu/kanade/tachiyomi/data/download/anime/AnimeDownloader.kt:851`

`if (queueState == downloads) return` — `StateFlow == List` is always false, so the early-return never triggers. Harmless (callers pass freshly-rebuilt lists).
**Fix:** `queueState.value == downloads`, or delete the line.

### 16. `subscribeAll*` collector computes a map and discards it — Low
**File:** `app/src/main/java/eu/kanade/tachiyomi/source/anime/AndroidAnimeSourceManager.kt:77-85` (and `AndroidMangaSourceManager.kt:74-82`)

`mutableMap` is built then never written back to `stubSourcesMap`. Narrowed by `getOrStub()` lazily loading DB stubs on access; symptom is `getStubSources()` missing stubs of fully-uninstalled sources at cold start.
**Fix:** `stubSourcesMap.putAll(...)` inside the collector, or remove the loop.

---

## Errors

### 17. Dead null check after `oauth!!` dereference → NPE in AniList interceptor — Medium
**File:** `app/src/main/java/eu/kanade/tachiyomi/data/track/anilist/AnilistInterceptor.kt:29-41`

```kotlin
if (oauth == null) { oauth = anilist.loadOAuth() }  // can return null
if (oauth!!.isExpired()) { ... }                    // NPE here
if (oauth == null) { throw IOException(...) }        // dead code
```
When the password pref is set but the OAuth blob is missing/corrupt, the interceptor throws an unclean NPE on the OkHttp thread instead of a descriptive `IOException`.
**Fix:** `val currAuth = oauth ?: throw IOException("No authentication token")`, then use `currAuth` (mirror `KitsuInterceptor`).

### 18. Silent garbage image URL on parse failure in `DataSaver.getUrl` — Low
**File:** `app/src/main/java/aniyomi/util/DataSaver.kt:164-168`

`.substringAfter("\"dest\":\"")` returns the *entire* body when the marker is absent (resmush.it error/HTML), which is then returned as the image URL. *Note:* the claimed connection leak was refuted (`.string()` closes the body).
**Fix:** Use the `substringAfter(marker, "")` overload, detect empty, fall back to original URL; check `resp.isSuccessful`.

### 19. Uncaught `DateTimeParseException` can crash a screen-model coroutine in AniChartApi — Low
**File:** `app/src/main/java/eu/kanade/tachiyomi/util/AniChartApi.kt:161` (callers `AnimeScreenModel.kt:279,1503`)

`toUnixTimestamp` calls `OffsetDateTime.parse(...)`; the try/catch wraps only `.execute()`. A malformed/format-changed Simkl payload propagates into `screenModelScope.launch{}` with no handler → crash. Latent. (Claimed connection leaks refuted.)
**Fix:** Wrap `toUnixTimestamp` in a try returning `0L`.

### 20. Response leaked and body read outside try in `AniSkipApi.getMalIdFromAL` — Low
**File:** `app/src/main/java/eu/kanade/tachiyomi/ui/player/utils/AniSkipApi.kt:52-65`

Catch guards only `.execute()`; `.body.string()` runs outside it and the response is never closed. An IO error mid-read throws uncaught and leaks a connection; caller uses `runBlocking` with no catch. Narrow window.
**Fix:** Move `.body.string()` inside the try, or use `.execute().use { ... }`.

### 21. Non-null assertion on nullable `refreshToken` in Kitsu interceptor — Low
**File:** `app/src/main/java/eu/kanade/tachiyomi/data/track/kitsu/KitsuInterceptor.kt:25`

`val refreshToken = currAuth.refreshToken!!` — `refreshToken: String?`. A persisted OAuth with null `refreshToken` throws NPE on the network thread even when no refresh is needed (unwrap precedes `isExpired()`).
**Fix:** `?: throw IOException("Missing Kitsu refresh token")`, ideally inside the refresh branch.

---

## Code Quality

### 22. `setMangaReadingMode` blocks the UI thread with `runBlocking(Dispatchers.IO)` — Medium
**File:** `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt:681-703`

A non-suspend Compose callback (`ReaderActivity.kt:358`) wraps DB read+write + channel send in `runBlocking(Dispatchers.IO)`, which blocks the *calling* (UI) thread — `Dispatchers.IO` only routes the body. The sibling `setMangaOrientationType` (line 720) does identical work via `viewModelScope.launchIO`.
**Impact:** UI freeze on each reading-mode change; ANR risk on slow storage.
**Fix:** Replace with `viewModelScope.launchIO { ... }`.

---

## Warnings

### 23. `registerReceiver` without exported flag (targetSdk 34) — Low
**File:** `app/src/main/java/eu/kanade/tachiyomi/ui/player/PlayerActivity.kt:971`

`registerReceiver(noisyReceiver, filter)` triggers `UnspecifiedRegisterReceiverFlag`; inconsistent with the PIP receiver (812–816). `ACTION_AUDIO_BECOMING_NOISY` is protected so it won't crash.
**Fix:** `ContextCompat.registerReceiver(this, noisyReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)`.

### 24. Nullable system service dereferenced in `isTvBox()` — Low
**File:** `app/src/main/java/eu/kanade/tachiyomi/util/system/TvUtils.kt:15-16`

`getSystemService(UiModeManager::class.java).getCurrentModeType()` — platform-nullability smell; inconsistent with null-safe `getSystemService<T>()` elsewhere.
**Fix:** `getSystemService<UiModeManager>()?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION`.

### 25. Mutable static callback `onDismissDialog` reassigned from multiple sites — Low
**File:** `app/src/main/java/eu/kanade/presentation/entries/anime/EpisodeOptionsDialogScreen.kt:134`

`companion object { var onDismissDialog: () -> Unit = {} }` written from `AnimeScreen.kt:378` and `AnimeUpdatesTab.kt:105`; never reset on dispose, retaining a lambda over a ScreenModel statically. Smell, not a demonstrable leak in the single-Activity Voyager model.
**Fix:** Route dismiss through the Screen/ScreenModel or navigator result; at minimum reset on dispose.

### 26. Mutable static `displayProfile` ByteArray in `TachiyomiImageDecoder` — Low
**File:** `app/src/main/java/eu/kanade/tachiyomi/data/coil/TachiyomiImageDecoder.kt:88`

Written on main thread (`ReaderActivity.kt:907`), read on Coil's decode thread (line 24) with no memory barrier — JMM visibility gap. Worst case is a transient wrong color profile.
**Fix:** Mark `@Volatile`, or pass per-request via Coil `Options.parameters`.

### 27. Non-exhaustive `when` over sealed `PlayerUpdates` relies on `else -> {}` — Low
**File:** `app/src/main/java/eu/kanade/tachiyomi/ui/player/controls/PlayerControls.kt:301-311`

`else -> {}` swallows `None`/`DoubleSpeed` and any future subtype. Current behavior correct (guarded above); maintainability nit.
**Fix:** Enumerate no-op cases and drop `else`.

### 28. Magic number `30` instead of `Build.VERSION_CODES.R` — Low
**File:** `app/src/main/java/eu/kanade/tachiyomi/util/system/TvUtils.kt:30`

`if (Build.VERSION.SDK_INT < 30)` — cosmetic; inconsistent with `Build.VERSION_CODES.*` elsewhere.
**Fix:** Replace `30` with `Build.VERSION_CODES.R`.

---

## Notes for the maintainer

- **Verifier downgrades:** several finder-claimed severities were lowered after reading the code — the WebView header leak (Medium → Low; subresource/secret claim was wrong), both `ExtensionsRestorer` install-validation framings (the OS installer always shows the real APK identity), and three "connection leak" claims that don't hold because OkHttp's `ResponseBody.string()` closes the body. The substantive bugs in those findings (path traversal, silent garbage URL, uncaught date parse) are real and retained.
- **Upstream parity:** many low findings are inherited from Mihon/Tachiyomi rather than fork-introduced. The clearest fork-introduced regressions are the two High data-corruption bugs (season restore, episode-flag remap) and the anime-installer divergence from its already-hardened manga sibling — prioritize those.
- **Quick consistency wins:** #1, #23 (mirror existing hardened siblings), #15, #14 (one-line fixes), #24/#28 (lint cleanups) are low-risk and fast.
