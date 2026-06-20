# Aniyomi-Revived — Technical & Security Profile + Extension Takeover Plan

**Repository:** `aniyomi-revived` (a fork of `aniyomiorg/aniyomi`, itself a fork of Mihon ← Tachiyomi)
**Profiled:** 2026-05-29 · **Last upstream commit in tree:** 2025-11-05
**Method:** 6 parallel investigation workstreams (architecture, extensions, security/backdoor, maintenance, functionality, ecosystem), each citing `file:line`. Inferences are marked. Facts that shift over time (the extension ecosystem) are marked **[VOLATILE]**.

---

## Executive Summary

**What this is.** Aniyomi is a full-featured **Android** app (Kotlin 2.2.0, Gradle 8.13, Jetpack Compose) that reads manga/manhwa and plays anime. It ships **zero content** itself — all content comes from **third-party "extensions"**: standalone Android APKs that implement a Kotlin `source-api` and are loaded into the app at runtime. The app is a Tachiyomi-lineage codebase carrying a **dual anime + manga architecture** (two near-identical subsystems), an mpv-based video player (native libs), SQLDelight storage, and integrations with ~11 tracking/streaming services.

**Backdoor / malicious-code verdict (you specifically asked).** **Clean bill of health.** A dedicated hunt found **no backdoors, no hidden exfiltration, no obfuscated payloads, no secret-stealing CI, and no smuggled binaries.** Every hardcoded credential is a long-standing **public** OAuth client ID for a tracker; every hardcoded URL/IP maps to a legitimate service (trackers, DoH bootstrap IPs, GitHub, aniyomi.org). The in-app updater still points at the genuine `aniyomiorg/aniyomi` GitHub repo (a backdoored fork would repoint this). The fork is faithful to upstream Aniyomi to the depth audited. See **Workstream 3 · Part A**.

**Top 3 risks.**
1. **Extensions run in-process with full app permissions — no sandbox** (architectural, by design). A trusted-but-malicious extension can do anything the app can: read app data, use the shared cookie jar, run arbitrary native code. This is *the* security boundary you are inheriting responsibility for. (`AnimeExtensionLoader.kt:287,305`)
2. **The official extension repos are dead.** `aniyomiorg/aniyomi-extensions` was **archived (~Jul 2025) after DMCA takedowns** [VOLATILE]; Tachiyomi's manga repo was taken down years ago. There is **no default repo** baked into the app — content discovery depends entirely on community forks (keiyoushi for manga; yuzono/Secozzi/Kohi-den for anime). Owning the ecosystem means standing up and securing your own repo.
3. **Fragile supply chain & a pre-release HTTP stack.** OkHttp is pinned to **`5.0.0-alpha.14`** (an alpha) and five dependencies are pinned to **git commit hashes on JitPack** (unifile, injekt, image-decoder, subsampling-scale-image-view, flexible-adapter) — if those upstream repos vanish, builds break. The anime player depends on external native AARs (`aniyomi-mpv-lib`, `jmir1/ffmpeg-kit`) that are hard to rebuild.

**Overall health verdict: HEALTHY codebase, MODERATE-TO-HIGH maintenance burden.** Modern toolchain, clean architecture, production-grade CI — but ~24% of the code is anime/manga duplication (≈2× effort per feature), unit-test coverage is near-zero (~7 test files for ~1,391 `.kt` files), and the real ongoing work is the **extensions**, not the app. The app is maintainable solo at ~30–40 hrs/month; the extension ecosystem is the larger commitment.

---

## Workstream 1 — Architecture & Structure

**App type:** Android application (single APK, multi-arch). **Language:** Kotlin (some Kotlin Multiplatform modules). **Build:** Gradle 8.13 + AGP 8.9.0, version catalogs. **UI:** Jetpack Compose (Material3) + Voyager navigation. **DI:** Injekt (Mihon fork, manual DI — not Hilt). **DB:** SQLDelight 2.0.2.

### Module map (`settings.gradle.kts:48-60`)
| Module | Type | Purpose |
|---|---|---|
| `app` | Android app | Entry points, UI, extension managers, players/readers |
| `core:archive` | Lib | libarchive wrapper (archive extraction) |
| `core:common` | Lib | Networking (`NetworkHelper`), serialization, JS engine, file utils |
| `core-metadata` | Lib | Source metadata |
| `data` | Lib | SQLDelight DBs (manga + anime), repository impls |
| `domain` | Lib | Business logic / interactors, repository interfaces |
| `source-api` | KMP lib | **The extension contract** — `Source`/`AnimeSource` interfaces |
| `source-local` | KMP lib | Local-file sources (read from device) |
| `presentation-core` | Compose lib | Shared UI components |
| `presentation-widget` | Compose lib | Glance home-screen widgets |
| `i18n`, `i18n-aniyomi` | KMP lib | Moko-resources translations (dual) |
| `macrobenchmark` | Benchmark | Baseline profiles |
| `buildSrc` | Build logic | Gradle convention plugins |

**Build toolchain** (`buildSrc/.../AndroidConfig.kt`): `compileSdk 35`, `targetSdk 34`, `minSdk 26`, NDK 27.1.x, JVM target 17. Gradle wrapper 8.13 (`gradle/wrapper/gradle-wrapper.properties:3`), validated distribution URL. Version catalogs: `gradle/{libs,kotlinx,androidx,compose,aniyomi}.versions.toml`.

**Architecture pattern (inference, strongly evidenced):** Clean Architecture + MVVM. `app/presentation → domain (interactors) → data (repository impls) → source-api/source-local`. State via Coroutines `Flow`; screens via Voyager `ScreenModel`.

**Entry points.**
- `App.kt` (`app/.../App.kt:74-100`) — `Application` + lifecycle observer; initializes Injekt, Conscrypt (TLS 1.3 backport), Coil image loader, WebView multiprocess, migrations.
- `MainActivity.kt` — launcher activity (`singleTop`), Voyager navigator, handles `tachiyomi://`/`aniyomi://` **add-repo** deep links and `.tachibk` backup opens.
- Notable manifest components (`app/src/main/AndroidManifest.xml`): `ReaderActivity`, `PlayerActivity` (PiP), `WebViewActivity`, `Manga/AnimeExtensionInstallActivity`, `TrackLoginActivity`, plus extension-install services/receivers and a Shizuku provider.

**Notable permissions** (`AndroidManifest.xml:6-34`): `INTERNET`, `MANAGE_EXTERNAL_STORAGE`, `REQUEST_INSTALL_PACKAGES`, `REQUEST_DELETE_PACKAGES`, `UPDATE_PACKAGES_WITHOUT_USER_ACTION`, `QUERY_ALL_PACKAGES`, foreground-service perms. The install/query permissions exist to manage extension APKs (justified by design, but broad — see WS3). Full dependency inventory in the **Appendix**.

---

## Workstream 2 — Extensions Subsystem (priority)

Aniyomi runs **two parallel, near-identical extension subsystems**: manga (`eu.kanade.tachiyomi.extension.manga.*`, feature flag `tachiyomi.extension`) and anime (`...extension.anime.*`, flag `tachiyomi.animeextension`). They differ only in constants/types. Anime is cited as primary below; manga is identical in shape.

### What an extension is / how it's packaged
An extension is a normal Android **APK** that:
- Declares a `<uses-feature android:name="tachiyomi.animeextension">` (or `tachiyomi.extension`) — the marker that identifies it as an extension. (`AnimeExtensionLoader.kt:41`, `MangaExtensionLoader.kt:52`)
- Lists its source classes in `<application>` meta-data `tachiyomi.animeextension.class` (`;`-separated; leading `.` prefixed with package). (`AnimeExtensionLoader.kt:42,293-302`)
- Optionally sets `.factory`, `.nsfw`, `.hasReadme`, `.hasChangelog`; uses its app **label** as the display name (strips `"Aniyomi: "` prefix). (`:43-46,244`)
- Encodes the lib version in its `versionName`.

The classes implement the **`source-api`** contract: `AnimeSource` (`source-api/.../animesource/AnimeSource.kt:13`) → `AnimeCatalogueSource` (`.../AnimeCatalogueSource.kt:8`) → `AnimeHttpSource` (`.../online/AnimeHttpSource.kt:31`); plus `AnimeSourceFactory` (`.../AnimeSourceFactory.kt:6`) for multi-source APKs. Manga mirrors: `HttpSource.kt:29`, `ParsedHttpSource.kt:16`, `SourceFactory.kt:6`. Source `id = MD5("${name}/$lang/$versionId")` with the sign bit cleared (`AnimeHttpSource.kt:58,88`).

### Discovery → download → load → execute (traced end-to-end, anime)
1. **Add repo.** User pastes/deep-links a URL; `CreateAnimeExtensionRepo` enforces `^https://.*/index\.min\.json$` (`CreateAnimeExtensionRepo.kt:15`), then fetches `"$baseUrl/repo.json"` to record the repo's `signingKeyFingerprint` (`ExtensionRepoService.kt:25-28`, `ExtensionRepoDto.kt:7-26`).
2. **Discovery.** `AnimeExtensionApi.findExtensions()` GETs `"$baseUrl/index.min.json"` and parses a `List<AnimeExtensionJsonObject>`; filters by lib version (`AnimeExtensionApi.kt:42-67,116-137`). APK URL = `"$baseUrl/apk/<apk>"`, icon = `"$baseUrl/icon/<pkg>.png"` (`:133,139-141`).
3. **Download + install.** `AnimeExtensionInstaller` uses Android `DownloadManager` (`AnimeExtensionInstaller.kt:71-149`), then `installApk()` dispatches to LEGACY (`ACTION_INSTALL_PACKAGE` intent), PRIVATE (copy APK to `files/exts/<pkg>.ext`, no system install), or PackageInstaller/Shizuku via a foreground service (`:158-205`).
4. **Post-install.** `AnimeExtensionInstallReceiver` (`RECEIVER_NOT_EXPORTED`) fires on `PACKAGE_ADDED/REPLACED/REMOVED` → `AnimeExtensionLoader.loadExtensionFromPkgName` (`AnimeExtensionInstallReceiver.kt:50-103`).
5. **Load.** `loadExtension()` validates lib version (`:248-261`) → extracts SHA-256 signature(s) (`getSignatures`, `:398-412`) → **rejects unsigned** (`:263-266`) → **trust check** (`:267`) → NSFW gate (`:280-284`) → instantiates classes with **`ChildFirstPathClassLoader`** (`:287`) via **`Class.forName(name, false, cl).getDeclaredConstructor().newInstance()`** (`:305`), cast to `AnimeSource` or `AnimeSourceFactory.createSources()` (`:305-334`); `PathClassLoader` fallback on `LinkageError` (`:312-318`).
6. **Register + execute.** Loaded sources flow into `AndroidAnimeSourceManager` keyed by id (`AndroidAnimeSourceManager.kt:50-90`). A search calls `AnimeCatalogueSource.getSearchAnime(page,query,filters)` → `AnimeHttpSource` builds the request, runs it on the **shared** `network.client`, and `searchAnimeParse(response)` returns an `AnimesPage` (`AnimeHttpSource.kt:150-179`).

### Trust model (the crux)
- **Signature required:** unsigned APKs are rejected (`AnimeExtensionLoader.kt:263-266`).
- **Trusted if** any of the APK's signing-cert SHA-256 fingerprints matches a configured repo's `signingKeyFingerprint`, **OR** the exact tuple `"$pkg:$versionCode:$lastFingerprint"` is in the user's `trustedExtensions` preference (`TrustAnimeExtension.kt:14-18`). Untrusted → surfaced to UI as `Untrusted`, user can manually trust (`AnimeExtensionManager.kt:291-301`).
- **No hard-coded official key.** Trust derives entirely from the repos the user adds. This is **trust-on-first-use per repo**: trusting a repo = trusting any APK its key ever signs, at any version.
- Private-install updates require **signature continuity** (`containsAll`) and **block downgrades** (`AnimeExtensionLoader.kt:71-89`).
- **Version gating** is narrow and parsed naively from `versionName` via `substringBeforeLast('.').toDoubleOrNull()`: anime `LIB_VERSION_MIN=12 / MAX=16` (`AnimeExtensionLoader.kt:47-48,254-261`), manga `1.4 / 1.5` (`MangaExtensionLoader.kt:56-57`).

### Sandbox & dynamic code (security-critical)
- **No sandbox.** Extension classes run **in the Aniyomi process** with **all of Aniyomi's permissions and identity**, sharing the host's OkHttp client + cookie jar via Injekt (`AnimeHttpSource.kt:35,68`). The only "isolation" is class-loader namespacing (child-first), **not** a security boundary. *(Inference, strongly evidenced.)*
- **Dynamic loading inventory:** `ChildFirstPathClassLoader`/`PathClassLoader` + `Class.forName` in both loaders (`AnimeExtensionLoader.kt:287,305,312`; `MangaExtensionLoader.kt:297,315,322`). The dex executed is the **locally-installed APK only** — **no** `DexClassLoader`, **no** network class-loading, **no** eval. Other `Class.forName` uses (MIUI/device detection, widget lookup) are unrelated.

### Repository/source model
User-managed repos stored in DB (`ExtensionRepo(baseUrl,name,shortName,website,signingKeyFingerprint)`, `ExtensionRepo.kt:3-9`). **No default repo** (`SourcePreferences.kt:40-42` default `emptySet()`). Index format: `index.min.json` (array of `{name,pkg,apk,lang,code,version,nsfw,sources[]}`) + `apk/` + `icon/` + `repo.json` (meta with signing fingerprint). A legacy migration rewrote bare slugs to `raw.githubusercontent.com/$repo/repo` (`ExternalRepoMigration.kt:16`).

> **Duplication burden:** any extension-security change must be made in **both** loaders, **both** `TrustExtension`, **both** installers/receivers.

---

## Workstream 3 — Security Audit

### Part A — Backdoor / malicious-code hunt → **NO MALICIOUS CODE FOUND**

A skeptical, fork-tampering-focused audit. Result: clean. Specifics:

- **Hardcoded secrets = legitimate public OAuth client IDs only.** MyAnimeList (`MyAnimeListApi.kt:383`), AniList `"5338"` (`AnilistApi.kt:570`), Bangumi (`BangumiApi.kt:268`), Shikimori (`ShikimoriApi.kt:258`), Simkl (`SimklApi.kt:237`), Kitsu client-id+secret (`KitsuApi.kt:428-431`). The Kitsu secret is the long-standing **public** Tachiyomi/Mihon pair, not a leaked private key. **No** AWS keys, private keys, or bearer tokens. ACRA crash reporting is **commented out** (`app/build.gradle.kts:30-40`).
- **Network endpoints all accounted for.** Tracker APIs, the standard DoH provider list with well-known bootstrap IPs (`core/common/.../network/DohProviders.kt`: 1.1.1.1, 8.8.8.8, 9.9.9.9…), `aniyomi.org` (docs), `api.github.com` (updater + repos), `api.aniskip.com` (intro-skip), opt-in image proxies `wsrv.nl`/`api.resmush.it`. No anomalous phone-home host.
- **In-app updater NOT redirected.** `AppUpdateChecker.kt:43-59` targets official `aniyomiorg/aniyomi` (and `-preview`). A malicious fork would repoint this — it doesn't. *(Note for you: see WS5 — you'll likely want to repoint it to your own releases.)*
- **No obfuscation / no exec-of-payload.** The only `Runtime.exec` is `logcat *:E -d` for user bug reports (`CrashLogUtil.kt:35`, hardcoded, no injection). Reflection is device/MIUI detection (`DeviceUtil.kt:39,105`). `xor` appears only as bitwise flag math (`Pin.kt`). No base64/hex decode-then-run, no string-decrypt routine.
- **No telemetry/analytics.** No ACRA-active/Sentry/Firebase/Crashlytics. No SMS/contacts/location. Clipboard is write-only.
- **CI/build clean.** `.github/workflows/*` pin actions to SHAs and gate signing/secrets on `github.repository == 'aniyomiorg/aniyomi'` (forks can't read secrets); no `curl|bash`. `buildSrc`/`*.gradle.kts` use only standard repos (gradlePluginPortal, google, mavenCentral, jitpack). `git ls-files` shows **no** tracked `.so/.jar/.dex/.apk/.enc` binaries that could hide a payload.

> Caveat: this audit covers the **app** repo. It says nothing about the safety of any **extension** you install — those are separate code (see WS2/WS6). The clean bill is meaningful but bounded to what was reviewed.

### Part B — Standard security findings
- **In-process extensions, full perms, shared cookie jar** = the dominant real-world attack surface. (High, by design.)
- **Cleartext traffic globally permitted** + user-added CAs trusted (`network_security_config.xml`). Matches upstream (some sources are HTTP-only) but weakens transport security app-wide. (Medium)
- **`DataSaver.kt:166`** issues a cleartext `http://api.resmush.it/...` request (opt-in only). (Low — switch to HTTPS.)
- **WebView** has JS + DOM storage + third-party cookies enabled for the Cloudflare solver (`WebViewUtil.kt:80-92`). **Mitigated:** no `addJavascriptInterface` bridge anywhere (grep = 0), no file access. (Medium→Low)
- **QuickJS** (`JavaScriptEngine.kt`): `QuickJs.create()` with **no Java bindings** exposed, runs on IO dispatcher. Extensions can pass arbitrary JS but there's no bridge back to app internals. (Low)
- **TLS intact:** no custom `TrustManager`/`HostnameVerifier`/`SSLSocketFactory` overrides; OkHttp default validation (`NetworkHelper.kt`). (Good)
- **Zip-Slip resistant:** EPUB entries resolved via `canonicalPath` (`EpubReader.kt:120`). No `ObjectInputStream`/Java-serialization sinks; SQLDelight = parameterized queries (no raw SQL concatenation). (Good)
- **Exported components:** `add-repo` deep link only pre-fills a confirm screen, doesn't silently add/fetch (`MainActivity.kt:543-552`). (Low)

### Dependency CVE cross-check (web-verified + OSV.dev)
**Authoritative scan added 2026-05-29:** all **97 direct Maven coordinates** from the version catalogs were queried against the open **OSV.dev** database (script: `build/osv_catalog_scan.py`, runnable with stock Python — no account/telemetry). **Result: 0 known advisories.** This confirms the web-based findings below. (Covers *direct* declared deps; full *transitive* coverage needs a `gradle.lockfile` — see Appendix note.)

No **known CVEs** in the current pinned versions. OkHttp `5.0.0-alpha.14` has no CVE and the 5.x line *fixed* CVE-2021-0341 (a 3.x/4.x issue) — the concern is **alpha stability**, not a vuln. jsoup 1.19.1 (post-dates CVE-2021-37714 / CVE-2022-36033), Conscrypt 2.5.3, kotlinx-serialization 1.9.0, Coil 3.1.0, SQLDelight 2.0.2, QuickJS 0.9.2 — none with known advisories. Full table in **Appendix**.

---

## Workstream 4 — Maintenance Health

**Verdict: HEALTHY codebase / MODERATE-TO-HIGH burden for a solo maintainer.**

- **Activity:** last commit 2025-11-05; ~54 commits in the trailing 12 months, clustered around releases. Distributed contributor base (upstream arkon/jmir1/Secozzi plus ~10 recent contributors) + Weblate translations. Active, not high-velocity.
- **CI/CD:** two healthy workflows (`build_pull_request.yml`, `build_push.yml`) — format check → unit tests → multi-arch signed APKs + SHA-256 checksums; release steps gated to the upstream repo. Production-grade.
- **Toolchain:** Gradle 8.13 / AGP 8.9.0 / Kotlin 2.2.0 / Java 17 — current-ish as of early 2026.
- **Fragile deps:** OkHttp **`5.0.0-alpha.14`** (pre-release in production); **five git-hash-pinned JitPack deps** (unifile, injekt, image-decoder, subsampling-scale-image-view, flexible-adapter) with no version fallback; native AARs `aniyomi-mpv-lib 1.18.n` and `jmir1/ffmpeg-kit 1.18` are hard to rebuild if upstream disappears.
- **Tests:** ~7 unit-test files (1 in `app`, 6 in `domain`) for ~1,391 `.kt` files (~0.5%); no instrumentation/UI tests. Weak safety net → reliance on manual QA + user reports.
- **Tech debt:** ~50 TODO/FIXME markers (mostly refactor/polish, e.g. "move into domain model", "merge track sync"); only ~4 `@Deprecated`; RxJava 1.3.8 present but barely used (the `source-api` `fetch*` methods are the main RX surface, all deprecated in favor of suspend).
- **CHANGELOG:** Keep-a-Changelog format; ~v0.18.1.x line (late 2025); ~1 minor release per quarter.
- **Scale:** ~1,391 `.kt` files; **~328 files (~24%) are anime/manga duplication** — roughly 2× effort for shared features. Refactoring to generics is a large (200+ hr) project.

**Estimated burden:** ~20–40 hrs/month baseline (deps, translations, small fixes); 40–60 in feature months; spikes if a native/JitPack dep breaks. **The app is the smaller commitment — the extensions are the real ongoing work.**

---

## Workstream 5 — Functionality Map

**Features:** Library (categories, sort/filter, display modes — both manga & anime), Browse/Catalogue (sources, global search, source migration, extension-repo management), Manga **Reader** (multiple viewers, page cache), Anime **Player** (mpv-based, PiP, external-player, subtitle/audio tracks, intro-skip timestamps), **Downloads** (offline chapters/episodes), **Backup/Restore** (`.tachibk` = gzip'd protobuf), **Tracking** (11 services), **Library auto-update scheduling**, **Settings**, **History/Statistics**.

**Content-fetch data flow:** UI → domain interactor (`GetManga`/`GetAnime` + chapters/episodes) → data repository (SQLDelight) → on cache-miss, the **source extension** parses the site (jsoup/JSON) over the shared **OkHttp** stack (Cloudflare interceptor, cookie jar, DoH, UA spoofing) → `SManga`/`SAnime`, `SChapter`/`SEpisode`, `Page`/`Video` models → mapped to domain models → upserted to DB → covers cached (MD5 of URL). Reader/Player then fetch pages/videos lazily; progress writes back to DB and optionally syncs to trackers.

**Storage/caching:** SQLDelight DBs (manga + anime; ~26 migrations) — `mangas`, `chapters`, `categories`, `history`, `sources`, `extension_repos`, etc. (`data/src/main/sqldelight/`). Cover cache (external files, MD5 keys), chapter-page **DiskLruCache**, 5 MiB OkHttp network cache. Downloads laid out `{dir}/{source}/{title}/{chapter|episode}/`. **Backup includes library, history, prefs, AND extension-repo lists** (and references to extensions) — portable across forks.

### External service dependencies (takeover-risk table)
| Service | URL / endpoint | Embedded credential | Takeover risk |
|---|---|---|---|
| MyAnimeList | `api.myanimelist.net/v2` | public client id `686b980f…` | High (commercial, public key shared by many apps → rate-limit/revocation) |
| AniList | `graphql.anilist.co` | client `5338` | Medium-High |
| Kitsu | `kitsu.app/api/edge` | public id+secret | Medium-High |
| MangaUpdates | `api.mangaupdates.com` | user creds | Medium |
| Shikimori | `shikimori.one` | public client | Medium |
| Simkl | `api.simkl.com` | public client | Medium-High |
| Bangumi | `bangumi.tv` | public client | Medium-High |
| Jellyfin / Kavita / Komga / Suwayomi | user-hosted | local | Low (user-controlled) |
| **App updater** | `github.com/aniyomiorg/aniyomi` releases | — | **Medium — points at the ORIGINAL org, not you.** Repoint to your own releases when you fork-own. (`AppUpdateChecker.kt:43-59`) |
| Extension repos | user-added URLs | — | Low for app / **High strategically** — official repos are dead (WS6) |
| Cloudflare DoH (default) | `1.1.1.1/dns-query` | — | Medium (Google/others selectable) |
| mpv / ffmpeg | native AARs (JitPack) | — | Medium (open source; forkable) |
| aniyomi.org docs | website | — | Low (read-only links) |

**Native/runtime deps:** libmpv + ffmpeg (anime playback), system WebView (JS-heavy sources / Cloudflare / OAuth redirects).

---

## Workstream 6 — Extension Ecosystem Map & Takeover Plan

> **[VOLATILE / legally contested]** The extension world shifts monthly and is DMCA-affected. **GitHub facts below were live-verified via the GitHub REST API on 2026-05-29** (counts/dates rounded).

### What happened to the official repos
- **Manga (Tachiyomi):** original `tachiyomiorg/tachiyomi-extensions` taken down; Tachiyomi → **Mihon**; app ships no preloaded extensions.
- **Anime (Aniyomi):** `aniyomiorg/aniyomi-extensions` is **archived (read-only)**; its **last code push was 2024-08-19** (836★/284 forks). *(Corrects an earlier "~Jul 2025" estimate — actual last activity was Aug 2024.)* Anime extensions now target community forks / the **Anikku** successor.
- The app itself (`aniyomiorg/aniyomi`) is **not archived**, last push 2025-11-05 (7.3k★) — matches the commit in this tree.

### Active community forks (where the ecosystem lives now) — verified 2026-05-29
**Manga:** **`keiyoushi/extensions-source`** — **active leader**, pushed **2026-05-25**, 4.2k★ / 1.4k forks; mirrored to Codeberg. Also `yuzono/manga-repo` (auto-merges keiyoushi ~6h), `timschneeb/tachiyomi-extensions-archive` (historical reference).
**Anime:**
- **`yuzono/aniyomi-extensions`** (source) now returns **HTTP 451 "Unavailable For Legal Reasons"** — the DMCA pressure is real and current — **but its published artifacts repo `yuzono/anime-repo` was pushed 2026-05-29 (today)**, so the anime ecosystem is **alive** via the published branch. This is the most prominent anime source.
- **`Secozzi/aniyomi-extensions`** — active, pushed 2026-04-07 (87★).
- **`Kohi-den/extensions-source`** — **now archived** (last push 2026-05-12, 526★); was active until recently.
- Niche/regional: Claudemirovsky, hollow-fr. ("Kuukiyomi" as an anime extension repo remains **unconfirmed**.)

### Repo structure (two-branch model)
- **`main` (source):** `src/<lang>/<source>/`, `lib-multisrc/<theme>/` (shared base classes: Madara, ZoroTheme…), `lib/` (shared video extractors, AES/crypto, date utils), `.github/` CI.
- **`repo` (generated, what the app consumes):** `index.min.json` (+ `index.json`/`index.html`), `apk/`, `icon/`. CI builds APKs → runs Inspector.jar + aapt → `create-repo.py` assembles the index → commits to `repo`. App subscribes to `…/<repo>/repo/index.min.json`.

### Keiyoushi maintenance reality (verified 2026-05-29)
The user's read — "keiyoushi's are the best, but some are badly maintained" — is **accurate, with an important nuance**: the *repository* is one of the most actively maintained in the ecosystem (~4.2k★, ~9,640 closed vs ~609 open issues ≈ **16:1**, near-daily source-fix merges). The "badly maintained" feeling comes from **individual sources whose underlying sites are hostile**, not from neglect. The repo's own issue labels quantify why sources break [VOLATILE]:
- **`Domain changed` ≈ 2,400 issues** — by far #1. Aggregators hop domains to dodge DMCA. Trivial fix, but constant.
- **`Cloudflare protected` ≈ 219** — the *painful/unfixable* bucket (WAF challenges the WebView bypass often can't beat).
- Plus `Redesign` (markup churn) and `Source is down` (dead sites).

**Implication for you:** durability is an *architecture choice, not an effort level*. Prefer **API-backed first-party sources** (rarely break) and **`lib-multisrc` theme base classes** (one fix repairs many sites). Avoid domain-hoppers, Cloudflare-gated, and heavy-JS sources — they consume disproportionate time no matter who maintains them.

**Premise correction (affects the build-your-own guide):** keiyoushi's CONTRIBUTING now marks **`ParsedHttpSource` deprecated — use `HttpSource` instead.** New sources, and all JSON-API sources, should subclass `HttpSource` directly. (The skeleton below still shows `ParsedHttpSource` for the simple-HTML case; for anything new, prefer the `HttpSource` MangaDex skeleton in `EXTENSIONS_REPO_STARTER.md`.)

### Extension catalog (representative)
See the dedicated table below in **Deliverables**.

### Important version-gap note
Your local `source-api` already exposes **extensions-lib 16** (the new `seasonList`/`hosterList` video pipeline: `AnimeHttpSource.kt:330,367,404`), while published anime extensions largely target **≈ lib 14**. Plan for a porting/compat shim when adopting existing anime sources. [VOLATILE]

---

## Deliverable: Extension Catalog

| Name | Type | Source location | Last updated | Status | Complexity | Good template? |
|---|---|---|---|---|---|---|
| MangaDex | manga (multi-lang) | keiyoushi | active [VOLATILE] | Alive | Moderate | **Yes — best "API-over-scraping" reference** |
| ~~Comick~~ | manga | keiyoushi (removed) | site **dead 2026** | **Abandoned** | — | **No — site shut down after serial domain-hopping; do not rebuild** |
| Komga / Suwayomi (self-hosted) | manga | keiyoushi | active | Alive | Moderate | **Yes — zero external breakage risk** |
| Madara theme (100s of sites) | manga/manhwa | `lib-multisrc/madara` | active | Mixed | Trivial→Moderate | **Yes — best volume/effort lever** |
| Bato.to | manga | keiyoushi | active | Alive | Moderate | OK |
| WeebCentral | manga | keiyoushi | churning | Alive | Moderate→Complex | No (markup keeps breaking) |
| MangaFire | manga | keiyoushi | active | Alive | Complex | No (anti-bot, encoded images) |
| Jellyfin | anime (self-host) | yuzono | active | Alive | Moderate | **Yes — cleanest anime/JSON ref** |
| AllAnime | anime | yuzono / others | active | Alive | Moderate | Yes (JSON API + link decode) |
| HiAnime/AniWatch (Zoro) | anime | `lib-multisrc/zorotheme` | high churn | Alive | Complex | Advanced only (megacloud + AES) |
| AnimePahe | anime | yuzono | active | Alive | Complex | No (DDoS-Guard, kwik) |
| GogoAnime/AnimeKai | anime | yuzono | often broken | Alive | Complex | No (domain hops, encrypted embeds) |

## Deliverable: Prioritized Findings

| Severity | Area | Finding | File:Line | Recommendation |
|---|---|---|---|---|
| **High** | Extensions | Extensions run in-process, full app perms, shared cookie jar — no sandbox. A trusted-malicious extension = full compromise. | `AnimeExtensionLoader.kt:287,305`; `AnimeHttpSource.kt:35,68` | Own a curated, code-reviewed, single-key-signed repo; pin your repo's signing fingerprint; document the trust model to users. |
| **High** | Ecosystem | No default repo; official repos dead/DMCA'd. Content depends on community forks. | `SourcePreferences.kt:40-42`; WS6 | Stand up your own repo (keiyoushi/yuzono model); curate sources you can maintain. |
| **Medium** | Supply chain | OkHttp pinned to a pre-release alpha. | `gradle/libs.versions.toml:5` | Move to OkHttp 5.x GA when released; track it. |
| **Medium** | Supply chain | 5 deps pinned to JitPack git hashes (no version fallback). | `gradle/libs.versions.toml:33,42,50,51,59` | Mirror/fork these (unifile, injekt, image-decoder, ssiv, flexible-adapter) under your org. |
| **Medium** | Supply chain | Native AARs (mpv, ffmpeg) from external JitPack forks; hard to rebuild. | `gradle/aniyomi.versions.toml:2,5` | Mirror sources; verify upstream activity; document the rebuild procedure. |
| **Medium** | Trust | Repo trust = TOFU on a repo-declared signing key; no official key baked in. | `TrustAnimeExtension.kt:14-18`; `ExtensionRepoService.kt:25-28` | Acceptable, but make your key's fingerprint discoverable/pinned and never rotate silently. |
| **Medium** | Network | Cleartext traffic permitted app-wide + user CAs trusted. | `network_security_config.xml` | Scope cleartext per-domain where feasible; document why it's on. |
| **Medium** | Updater | App updater points at the original `aniyomiorg` org, not your fork. | `AppUpdateChecker.kt:43-59` | Repoint to your releases (or disable) so "revived" users get *your* updates. |
| **Low** | Network | Opt-in data-saver sends image URL over cleartext HTTP. | `DataSaver.kt:166` | Switch to `https://`. |
| **Low** | WebView | JS + DOM storage + 3rd-party cookies enabled (Cloudflare solver). | `WebViewUtil.kt:80-92` | Acceptable (no JS bridge, no file access); keep it that way. |
| **Low** | Maintainability | ~24% anime/manga code duplication; near-zero tests. | WS4 | Add unit tests around interactors/parsers first; consider gradual generics. |
| **Info** | Secrets | Public tracker OAuth IDs hardcoded (expected). | `KitsuApi.kt:428-431` et al. | None — but consider rotating to your own client IDs if usage scales. |

---

## Build-Your-Own-Extension Guide

### Minimal anatomy (what you MUST implement)
An extension is an APK with **(1) a feature flag**, **(2) class metadata**, and **(3) one `Source` class**.

**1. Build metadata** (per-source `build.gradle`):
```groovy
ext {
    extName = 'My Source'
    extClass = '.MySource'   // relative to package eu.kanade.tachiyomi.extension.<lang>.<sourcename>
    extVersionCode = 1       // bump every change; final version = <libVersion>.<extVersionCode>
    isNsfw = false
}
apply plugin: "<your-repo's extension gradle plugin>"
```
The Gradle plugin injects the `<uses-feature tachiyomi.extension>` flag, the `tachiyomi.extension.class` metadata, and the lib version. `AndroidManifest.xml` is **optional** — only needed if you want deep-link (`UrlActivity`) support.

> **Note (2026 best practice):** upstream now **deprecates `ParsedHttpSource`** in favor of `HttpSource`. The selector-based skeleton below is fine for learning and for simple static-HTML sites, but for anything new — and *always* for JSON APIs — subclass **`HttpSource`** directly (see the MangaDex skeleton in `EXTENSIONS_REPO_STARTER.md`).

**2. Manga source class** — extend `ParsedHttpSource` (selector-based) and implement:
`name`, `baseUrl`, `lang`, `supportsLatest`; `popularMangaRequest`/`popularMangaSelector`/`popularMangaFromElement`/`popularMangaNextPageSelector`; the `search*` and `latestUpdates*` equivalents; `mangaDetailsParse(Document)`; `chapterListSelector`/`chapterFromElement`; `pageListParse(Document)`; `imageUrlParse(Document)`. (Contract: `source-api/.../source/online/ParsedHttpSource.kt:16,40-54,161-200`; raw layer `HttpSource.kt:125-361`.)

Minimal skeleton:
```kotlin
package eu.kanade.tachiyomi.extension.en.mysource

class MySource : ParsedHttpSource() {
    override val name = "My Source"
    override val baseUrl = "https://example.com"
    override val lang = "en"
    override val supportsLatest = true

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/popular?page=$page", headers)
    override fun popularMangaSelector() = "div.manga-item"
    override fun popularMangaFromElement(e: Element) = SManga.create().apply {
        setUrlWithoutDomain(e.selectFirst("a")!!.attr("href"))   // survives domain changes
        title = e.selectFirst("a")!!.text()
        thumbnail_url = e.selectFirst("img")?.absUrl("src")
    }
    override fun popularMangaNextPageSelector() = "a.next"

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) =
        GET("$baseUrl/search?q=$query&page=$page", headers)
    override fun searchMangaSelector() = popularMangaSelector()
    override fun searchMangaFromElement(e: Element) = popularMangaFromElement(e)
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/latest?page=$page", headers)
    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun latestUpdatesFromElement(e: Element) = popularMangaFromElement(e)
    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    override fun mangaDetailsParse(d: Document) = SManga.create().apply {
        title = d.selectFirst("h1")!!.text()
        description = d.selectFirst("div.summary")?.text()
        thumbnail_url = d.selectFirst("img.cover")?.absUrl("src")
    }
    override fun chapterListSelector() = "li.chapter"
    override fun chapterFromElement(e: Element) = SChapter.create().apply {
        setUrlWithoutDomain(e.selectFirst("a")!!.attr("href"))
        name = e.selectFirst("a")!!.text()
    }
    override fun pageListParse(d: Document) =
        d.select("div.reader img").mapIndexed { i, img -> Page(i, imageUrl = img.absUrl("src")) }
    override fun imageUrlParse(d: Document) = throw UnsupportedOperationException()
}
```
**Anime** swaps `SManga→SAnime`, `SChapter→SEpisode`, pages→videos, and on extensions-lib 16 implements `hosterListParse`/`videoListParse(response, hoster)` (`AnimeHttpSource.kt:330-454`) instead of the legacy single `videoListParse(response)`.

### Step-by-step: rebuild one extension end-to-end (recommended first = **MangaDex**)
1. **Stand up your extensions repo** (two-branch model from WS6) with the Gradle convention plugin + CI from keiyoushi as a base.
2. **Pick MangaDex** (clean official JSON REST API → most resilient).
3. **Model the API:** create `@Serializable` DTOs for the MangaDex endpoints; extend `HttpSource` (not `ParsedHttpSource` — it's JSON, not HTML) and parse with `response.parseAs<T>()`.
4. **Implement** `popularMangaRequest/Parse`, `searchMangaRequest/Parse` (filters: language, tags, status), `mangaDetailsParse`, `chapterListParse` (handle scanlation groups + language), `pageListParse` (at-home server endpoint). Respect rate limits with a rate-limit interceptor.
5. **Use `setUrlWithoutDomain()`** and make `baseUrl` overridable so domain changes don't break libraries.
6. **Build + test** against your `source-api`; install the APK; trace discovery→search→read.
7. **Sign** with your CI key; let CI run Inspector.jar + `create-repo.py` to publish to your `repo` branch; pin the signing fingerprint in `repo.json`.

---

## Recommended Rebuild Roadmap

*Durability-first ordering (refined 2026-05-29 against keiyoushi's actual breakage data). Comick was dropped — its site shut down in 2026.*

1. **MangaDex (manga)** — highest value, first-party JSON API, rarely breaks. Your reference architecture (`HttpSource` + serialization DTOs). Maintenance: **very low**.
2. **Madara theme base + curated stable instances (manhwa)** — bring `lib-multisrc/madara` in once; each site becomes a ~30-line override with centralized selectors. Biggest volume-per-effort win; one base fix repairs many sites. Maintenance: **low (high leverage)**.
3. **MangaThemesia theme base (manga/manhwa)** — the second-largest site cluster; same leverage argument. Maintenance: **low (high leverage)**.
4. **Komga / Suwayomi / Jellyfin (self-hosted, manga + anime)** — user's own server APIs; **zero external breakage risk**; also the cleanest `HttpSource`/`AnimeHttpSource` references. Maintenance: **very low**.
5. **WeebCentral (manga)** — currently the most popular *reliable* EN standalone scraper, actively maintained; watch for redesigns. Maintenance: **medium**.
6. **AllAnime (anime)** — popular, API-based; add a link-decode helper to `lib/`. Maintenance: **medium**.
7. **Defer / exclude:** HiAnime/Zoro, AnimePahe, GogoAnime/AnimeKai (anime); MangaFire, the Manganato/Mangakakalot domain-hopper cluster, and any Cloudflare-WAF source (manga). High value but anti-bot + encrypted embeds + domain hops — these are the "badly maintained because the target is hostile" sources. Only attempt after shared `lib/` extractors exist, and budget continuous maintenance.

**Sequencing logic:** start with the first-party JSON API (1) and self-hosted servers (4) where breakage is near-zero; add theme base classes (2,3) for cheap, durable volume; take on the best standalone scraper (5) and an API-style anime source (6); quarantine hostile-target sources (7) behind shared infrastructure. **Prefer `HttpSource` over the deprecated `ParsedHttpSource` for everything new.** A full copy-pasteable starter (repo layout, Gradle plugin, `HttpSource` MangaDex skeleton, Madara theme, signing CI) is in `EXTENSIONS_REPO_STARTER.md`.

### Clean architecture for an ecosystem you own
- **Two-branch repo** (`main` source / `repo` generated artifacts), CI builds-from-source only (never accept prebuilt APKs).
- **Prefer JSON APIs over scraping**; for scrapers, **centralize CSS selectors** as named overridable vals (one edit fixes a markup change).
- **Theme abstraction** (`lib-multisrc`): fix once, fix all derived sources. **Shared `lib/` extractors** for video hosts + crypto.
- **Domain resilience:** always `setUrlWithoutDomain()`, make `baseUrl` a preference.
- **Security/trust you control:** one signing key in CI secrets (publish + pin the fingerprint); mandatory human review of every network call and any reflection/dynamic code; reject obfuscation or off-domain endpoints; least-privilege (use host `NetworkHelper`, no manifest unless a `UrlActivity` is truly needed); keep auditable `main` separate from published `repo` and tag releases.

---

## Appendix — Dependency Inventory & CVE Status

Versions from `gradle/{libs,kotlinx,androidx,compose,aniyomi}.versions.toml`. "Latest" and CVE status as web-verified early 2026; **none of the pinned versions carry a known CVE.** Re-verify before release.

| Dependency | Pinned version | Latest (≈2026) | Known CVEs | Notes |
|---|---|---|---|---|
| OkHttp (+logging/brotli/dnsoverhttps) | 5.0.0-**alpha.14** | 5.x GA | None for this ver | **Pre-release in prod** — track GA. 5.x line fixed CVE-2021-0341. |
| Okio | 3.10.2 | 3.x | None | |
| Conscrypt-android | 2.5.3 | 2.5.3 | None | TLS 1.3 backport |
| jsoup | 1.19.1 | 1.x | None | post-dates CVE-2021-37714 / CVE-2022-36033 |
| kotlinx-serialization (json/protobuf) | 1.9.0 | 1.9.x | None | |
| xmlutil | 0.90.3 | — | None | |
| quickjs-android (app.cash) | 0.9.2 | — | None | sandboxed, no JS↔native bridge |
| Coil | 3.1.0 | 3.x | None | |
| SQLDelight | 2.0.2 | 2.x | None | parameterized queries |
| kotlinx-coroutines | 1.10.1 (BOM) | 1.10.x | None | |
| Kotlin | 2.2.0 | 2.2.x | None | |
| AGP | 8.9.0 | 8.x | None | |
| Gradle | 8.13 | 8.x | None | validated distribution URL |
| Voyager | 1.0.1 | 1.x | None | |
| Compose BOM | 2025.03.00 | — | None | |
| Glance | 1.1.1 | — | None | |
| RxJava | 1.3.8 | 1.x (EOL) | None known | legacy; minimal use; deprecated in source-api |
| Injekt (mihonapp fork) | git `91edab2317` | — | n/a | **git-pinned (JitPack)** — mirror it |
| Unifile (tachiyomiorg) | git `e0def6b3dc` | — | n/a | **git-pinned** — mirror it |
| image-decoder (tachiyomiorg) | git `41c059e540` | — | n/a | **git-pinned** — mirror it |
| subsampling-scale-image-view | git `66e0db195d` | — | n/a | **git-pinned** — mirror it |
| FlexibleAdapter (arkon fork) | git `c8013533` | — | n/a | **git-pinned** — mirror it |
| aniyomi-mpv-lib | 1.18.n | — | n/a | native AAR (JitPack) — mirror/verify |
| ffmpeg-kit (jmir1 fork) | 1.18 | — | n/a | native AAR (JitPack) — mirror/verify |
| Moko Resources | 0.24.5 | — | None | |
| Shizuku API/Provider | 13.1.0 | — | None | elevated install path |
| LeakCanary | 2.14 | 2.x | None | debug only |

---

## FOSS Tooling Installed (2026-05-29)
All tools are free/open-source and current (winget pulls latest; "No upgrade available" at install):
- **GitHub CLI `gh` 2.93.0** (MIT) — for ecosystem verification and future repo/CI/signing work. **Not yet authenticated** — run `gh auth login` (interactive, one-time) to unlock private API calls and repo creation.
- **OSV-Scanner 2.3.8** (Apache-2.0, Google) — FOSS dependency scanner (the open alternative to Snyk). Native Gradle-catalog scanning isn't supported, so `build/osv_catalog_scan.py` bridges it via the OSV.dev API for direct deps.
- **Eclipse Temurin JDK 17.0.19** (GPLv2+CE) at `C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot` — required to build the app/extensions and to generate a `gradle.lockfile`.
- Already present: `git`, `python 3.12`, `winget`.

> **PATH note:** these were installed mid-session; **restart your terminal / Claude Code** so new shells see them automatically.
> **Full transitive CVE scan (optional, deferred):** requires the **Android SDK** (not installed — `compileSdk 35` + build-tools, license-gated) plus enabling Gradle dependency locking and running `./gradlew :app:dependencies --write-locks`, then `osv-scanner scan source --lockfile app/gradle.lockfile`. This downloads the full dependency graph; best run interactively. Happy to set up the Android SDK on request.

## Companion documents
- **`EXTENSIONS_REPO_STARTER.md`** — copy-pasteable starter for your own FOSS extensions repo: two-branch layout, Gradle convention plugin, a complete **`HttpSource`** MangaDex skeleton, a Madara `lib-multisrc` theme, a signing+publishing GitHub Actions CI, and the trust/fingerprint loop tied to this app's loaders (cited file:line).
- **`build/osv_catalog_scan.py`** — re-runnable FOSS dependency vuln check against OSV.dev.

## Open Questions / Uncertainties (stated, not guessed)
- GitHub activity/stars/archive status for the extension repos were **live-verified 2026-05-29** (see WS6). Counts drift over time.
- "Kuukiyomi" as an anime extension repo is **unconfirmed**.
- The extensions-lib **14 (published) vs 16 (your source-api)** gap needs hands-on porting validation.
- Whether `aniyomi-mpv-lib`/`jmir1/ffmpeg-kit` upstreams are still active was not live-verified — confirm before committing to anime playback maintenance.

---

# Appendices — Agent Backlog Execution (2026-05-29)

The following appendices were produced by executing the `tasks.md` backlog with concurrent agents
after installing the Android SDK (task P0). All builds ran on Gradle 8.13 / Temurin JDK 17 / SDK
platform-android-35 + build-tools 35.0.0 at `C:\Android\sdk`.

### Appendix — Transitive OSV Scan (2026-05-29)  *(task A3)*

**Method.** Enabled Gradle dependency locking via an out-of-tree init script
(`$TEMP/locking.init.gradle` = `allprojects { dependencyLocking { lockAllConfigurations() } }`;
no tracked build file modified). Resolved the full transitive graph with
`gradlew :app:dependencies :data:dependencies :domain:dependencies :source-api:dependencies --write-locks`
(Gradle 8.13, JDK 17) and scanned every generated `gradle.lockfile` with
osv-scanner v2.3.8 (`scan source -L <lockfile>`, data source deps.dev).

**Lockfile scope (all generated successfully).**
| Lockfile | Packages |
|---|---|
| `app/gradle.lockfile` | 487 |
| `data/gradle.lockfile` | 259 |
| `domain/gradle.lockfile` | 283 |
| `source-api/gradle.lockfile` | 233 |
| `buildSrc/gradle.lockfile` | 181 (build tooling; auto-locked) |

**Direct-dep baseline (for comparison).** `build/osv_catalog_scan.py` resolved 97 direct
catalog coordinates and queried OSV.dev: **0 advisories** — confirms the prior clean result.

**Transitive findings.** Deduplicated across all lockfiles: **31 unique advisories /
15 packages** (0 Critical, 12 High, 16 Moderate, 3 Low). Reachability analysis of the
lockfile configuration scopes shows the overwhelming majority are NOT shipped in the APK:

- **Shipped in APK (`releaseRuntimeClasspath`) — the only real exposure:**
  - `com.google.guava:guava@31.0.1-jre` — GHSA-7g45-4rm6-3mm3 / CVE-2023-2976 (Moderate),
    GHSA-5mg8-w23w-74h3 / CVE-2020-8908 (Low). Fixed in 32.0.0.
- **Test-tooling only (AGP Unified Test Platform `_internal-*` configs — never shipped):**
  netty 4.1.93.Final cluster (`netty-codec`, `-codec-http`, `-codec-http2`, `-common`,
  `-handler`, `-handler-proxy`) — 18 advisories incl. CVE-2025-55163 (HTTP/2 MadeYouReset,
  High), CVE-2025-24970 (High), CVE-2024-47535; plus `protobuf-java`/`protobuf-kotlin@3.24.4`
  GHSA-735f-pc8j-v9w8 / CVE-2024-7254 (High).
- **Gradle build classpath only (`buildSrc`, never shipped):**
  jdom2@2.0.6 (CVE-2021-33813), jose4j@0.9.5 (CVE-2024-29371),
  bcprov/bcpkix-jdk18on@1.79, commons-compress@1.21 (CVE-2024-25710, CVE-2024-26308),
  jgit@6.10.0 (CVE-2025-4949).

**Bottom line.** The direct-dep scan (0 advisories) understates transitive risk, but
configuration-scope analysis shows the only advisory-bearing package in the shipped APK is
**guava 31.0.1-jre (1 Moderate + 1 Low)**. All High-severity transitive advisories live in
test/build tooling and are not in the distributed binary. Recommended low-risk hardening: bump
guava to ≥32.0.0 (it arrives transitively; an explicit constraint or a newer pulling-dep would do it).

### Appendix — Build Verification (2026-05-29)  *(task A4)*

`:app:assembleDebug` → **BUILD SUCCESSFUL in 2m 16s** (warm cache), exit 0. The app has **no
product flavors** — only build types (`debug`/`release`/`preview`/`benchmark`,
`app/build.gradle.kts:45-86`), so the correct task is `assembleDebug` (not the
`assembleStandardDebug` the backlog guessed). ABI splits are enabled with a universal APK
(`app/build.gradle.kts:93-100`), producing five APKs under `app/build/outputs/apk/debug/`:

| APK | Size |
|---|---|
| `app-arm64-v8a-debug.apk` | 85.5 MB |
| `app-armeabi-v7a-debug.apk` | 79.4 MB |
| `app-x86-debug.apk` | 89.3 MB |
| `app-x86_64-debug.apk` | 93.9 MB |
| `app-universal-debug.apk` | 216.5 MB |

Native libs (mpv/ffmpeg/quickjs/etc.) packaged successfully → NDK present and functional. No
source/behavior changes were needed to build.

### Appendix — In-App Updater Re-point (task A1, pending GH-name confirmation)

The updater target (`AppUpdateChecker.kt:43-49`) was changed from `aniyomiorg/aniyomi[-preview]`
to a fork. **Release-asset naming dependency:** `ReleaseServiceImpl.kt:37-46` selects the download
asset by substring-matching the device ABI token (`-arm64-v8a`/`-armeabi-v7a`/`-x86_64`/`-x86`) in
the asset filename, falling back to the no-ABI-token (universal) asset. The fork's release workflow
must keep producing per-ABI + universal APKs with those tokens (the existing
`.github/workflows/build_push.yml` already does). **NOTE — unresolved owner name:** A1/A2/B4 used
`Blackyfi` (from the PROFILE gh-auth note); B1's mavenLocal publish derived `nicolasticot` (the
local git `user.name`). The repo owner must be confirmed before this is merged (see Open Questions).

### Appendix — Extensions Repo Security Re-Review (task D2)

Applied the EXT `CONTRIBUTING.md` checklist to every Group C source (MangaDex+filters, MangaThemesia
theme + KomikCast + ManhwaIndo, Komga, WeebCentral). **All sources PASS** all hard rules: every
network request resolves to a string literal or the user-overridable `baseUrl`/preference; no
reflection; no obfuscation/dynamic-code/`DexClassLoader`; no extra Android permissions/activities/
services; no out-of-API storage; Komga credentials are read only from prefs and sent only to the
user's own server via HTTP Basic. Three non-blocking notes:
- **F1 (Info):** MangaDex page-image host comes from the runtime `at-home/server` response
  (`MangaDex.kt`), controlled by the literal `api.mangadex.org` — intrinsic to MD@Home, used only
  as an image URL.
- **F2 (Info):** MangaThemesia reader image URLs come from the site's inline `ts_reader.run` payload
  — normal HTML-scraper behavior; request hosts are always literal site `baseUrl`s.
- **F3 (Low):** Komga attaches the Basic-auth header whenever a username is set, including to the
  inert `http://localhost/` no-config placeholder (unreachable in practice). Suggested fix: also
  gate the auth interceptor on `baseUrl.isNotEmpty()`.
