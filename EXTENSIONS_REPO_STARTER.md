# Build & Own a FOSS Aniyomi-Revived Extensions Repository — Starter Guide

This is a concrete, copy-pasteable starter for standing up your **own**, fully open-source
extensions repository for `aniyomi-revived`. Every contract citation points at the **local**
`source-api` and extension loaders in this tree (file:line), so the code below matches *this*
app, not upstream assumptions.

> **Why this matters (the trust boundary you inherit).** Extensions are Android APKs whose
> classes are loaded **into the Aniyomi process** via `ChildFirstPathClassLoader` +
> `Class.forName(...).getDeclaredConstructor().newInstance()`
> (`app/src/main/java/eu/kanade/tachiyomi/extension/manga/util/MangaExtensionLoader.kt:297,315`;
> anime: `.../anime/util/AnimeExtensionLoader.kt:287,305`). They run with **all of the app's
> permissions and identity** and share the host `OkHttpClient` + cookie jar via Injekt
> (`source-api/.../source/online/HttpSource.kt:34,67`). **There is no sandbox.** The only
> "isolation" is class-loader namespacing. Owning the repo means owning the review of every line
> that ships. Section 6 hardens what you control.

---

## How the app finds, trusts, and loads your repo (ground truth)

Before designing anything, here is exactly what the running app does, traced through this tree:

1. **Add repo.** The user pastes a URL that must match `^https://.*/index\.min\.json$`
   (`domain/.../extensionrepo/manga/interactor/CreateMangaExtensionRepo.kt:15`). The app strips
   `/index.min.json` to get a `baseUrl` (`:23`), then GETs **`$baseUrl/repo.json`** and parses
   it into the repo record (`domain/.../extensionrepo/service/ExtensionRepoService.kt:25-28`).
2. **`repo.json` shape** is fixed by the deserializer
   (`domain/.../extensionrepo/service/ExtensionRepoDto.kt:6-17`):
   ```json
   { "meta": { "name": "...", "shortName": "...", "website": "...", "signingKeyFingerprint": "..." } }
   ```
   The `signingKeyFingerprint` is stored as a trusted fingerprint for that repo.
3. **Discovery.** `MangaExtensionApi.getExtensions()` GETs **`$baseUrl/index.min.json`** and
   parses `List<ExtensionJsonObject>`
   (`app/src/main/java/eu/kanade/tachiyomi/extension/manga/api/MangaExtensionApi.kt:55-61`).
   APK URL = `"$repoUrl/apk/<apk>"` (`:136`); icon URL = `"$repoUrl/icon/<pkg>.png"` (`:129`).
4. **`index.min.json` shape** is fixed by the deserializer (`MangaExtensionApi.kt:144-162`):
   array of objects `{ name, pkg, apk, lang, code, version, nsfw, sources[] }`, where each
   `sources[]` is `{ id, lang, name, baseUrl }`.
5. **Lib-version gate.** The app keeps only entries whose lib version (derived from
   `version.substringBeforeLast('.').toDouble()`, `MangaExtensionApi.kt:140`) is within
   `[LIB_VERSION_MIN, LIB_VERSION_MAX]` (`:116`).
6. **Trust check at load.** When the APK is installed and loaded, the app extracts SHA-256
   signing-cert fingerprints (`MangaExtensionLoader.kt:408-422`), **rejects unsigned APKs**
   (`:273-276`), and considers it trusted iff one of its fingerprints matches a configured repo's
   `signingKeyFingerprint`, **or** the tuple `"$pkg:$versionCode:$lastFingerprint"` is in the
   user's `trustedExtensions` preference
   (`app/src/main/java/eu/kanade/domain/extension/manga/interactor/TrustMangaExtension.kt:14-18`).
   **So: the fingerprint in your `repo.json` MUST be the SHA-256 of the certificate you sign APKs
   with.** One key, pinned once, signs everything.

**Loader manifest facts you must satisfy** (manga; anime is identical with the `anime` infix):

| Concern | Manga value | Anime value | Source |
|---|---|---|---|
| Feature flag (`<uses-feature>`) | `tachiyomi.extension` | `tachiyomi.animeextension` | `MangaExtensionLoader.kt:52` / `AnimeExtensionLoader.kt:41` |
| Source class metadata key | `tachiyomi.extension.class` | `tachiyomi.animeextension.class` | `:53` / `:42` |
| Factory metadata key | `tachiyomi.extension.factory` | `tachiyomi.animeextension.factory` | `:54` / `:43` |
| NSFW metadata key | `tachiyomi.extension.nsfw` | `tachiyomi.animeextension.nsfw` | `:55` / `:44` |
| `LIB_VERSION_MIN`..`MAX` | **1.4 .. 1.5** | **12 .. 16** | `:56-57` / `:47-48` |
| App label prefix stripped | `"Tachiyomi: "` | `"Aniyomi: "` | `:252` / `:244` |
| `versionName` format | `<libVersion>.<extVersionCode>` (lib = `substringBeforeLast('.')`) | same | `:264` / `:254` |
| Class metadata format | `;`-separated; leading `.` is prefixed with package | same | `:303-312` / `:293-302` |

The class metadata string is split on `;`; each entry is instantiated and must be **either** a
`MangaSource`/`AnimeSource` **or** a `SourceFactory`/`AnimeSourceFactory` whose `createSources()`
returns a list (`MangaExtensionLoader.kt:313-319`; `AnimeExtensionLoader.kt:303-309`).

> **Lib-version takeaway.** A manga extension must declare `versionName` `1.4.x` or `1.5.x`.
> An anime extension must declare `12.x` … `16.x`. Get this wrong and the loader silently rejects
> the extension (`MangaExtensionLoader.kt:265-271`).

---

## 1. Repository layout (two-branch model)

Keep **auditable source** and **published binaries** strictly separate. Source lives on `main`;
the app only ever consumes the generated artifacts on the `repo` branch.

```
your-extensions/                      # branch: main  (human-reviewed source ONLY)
├── .github/
│   └── workflows/
│       └── build_push.yml            # CI: build → inspect → sign → generate index → publish
├── build.gradle.kts                  # root build (plugins, repos)
├── settings.gradle.kts              # includes lib-multisrc/* and src/*/* modules
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml            # version catalog (pin source-api, okhttp, serialization…)
│   └── wrapper/…                     # checked-in Gradle wrapper (validated distribution URL)
├── buildSrc/                         # the convention plugin that injects the manifest + flags
│   ├── build.gradle.kts
│   └── src/main/kotlin/
│       └── eu.kanade.tachiyomi.ext.gradle.kts   # "ext" convention plugin (Section 2)
├── lib/                              # shared helpers (crypto, date utils, video extractors)
│   └── unpacker/…
├── lib-multisrc/                     # shared THEME base classes (Section 4)
│   └── madara/
│       ├── build.gradle.kts
│       └── src/main/kotlin/eu/kanade/tachiyomi/multisrc/madara/Madara.kt
├── src/                              # one Gradle module per source
│   ├── all/
│   │   └── mangadex/                 # ← worked example, Section 3
│   │       ├── build.gradle          # declares extName/extClass/extVersionCode/isNsfw
│   │       ├── res/
│   │       │   ├── mipmap-hdpi/ic_launcher.png   # becomes the icon in icon/<pkg>.png
│   │       │   └── … (other densities)
│   │       └── src/eu/kanade/tachiyomi/extension/all/mangadex/
│   │           ├── MangaDex.kt
│   │           ├── MangaDexFactory.kt
│   │           └── dto/MangaDexDto.kt
│   └── en/
│       └── somemadarasite/           # ~30-line theme override (Section 4)
│           ├── build.gradle
│           ├── res/…
│           └── src/…/SomeMadaraSite.kt
├── README.md
├── repo.json.template                # filled by CI with the real fingerprint (Section 5)
└── CONTRIBUTING.md                   # mandatory-review policy (Section 6)
```

```
your-extensions/                      # branch: repo  (GENERATED — what the app fetches)
├── index.min.json                    # array of {name,pkg,apk,lang,code,version,nsfw,sources[]}
├── index.json                        # pretty version (optional, for humans)
├── repo.json                         # {"meta":{name,shortName,website,signingKeyFingerprint}}
├── apk/
│   ├── tachiyomi-all.mangadex-v1.4.20.apk
│   └── …
└── icon/
    ├── eu.kanade.tachiyomi.extension.all.mangadex.png
    └── …
```

The app subscribes to `https://<host>/<...>/repo/index.min.json`. If you publish via GitHub
Pages or `raw.githubusercontent.com`, the user adds a URL like
`https://raw.githubusercontent.com/<you>/your-extensions/repo/index.min.json`.

> *Inference (CI convention, not enforced by the app):* the two-branch split, the `apk/`+`icon/`
> directory names, and `index.json`/`index.html` are conventions inherited from the keiyoushi /
> yuzono model. The app **only** requires `repo.json` (meta) and `index.min.json` at the repo
> base, plus `apk/<apk>` and `icon/<pkg>.png` resolving — all proven above by file:line.

---

## 2. The Gradle convention plugin approach

Each source's `build.gradle` declares four things and applies a shared convention plugin. The
plugin's job: inject the `<uses-feature>` flag, the `tachiyomi.extension.class` metadata, the
NSFW flag, and compose the `versionName` as `<libVersion>.<extVersionCode>` so it lands inside
the loader's allowed range.

### 2a. Per-source `build.gradle` (the only thing an extension author writes)

`src/all/mangadex/build.gradle`:

```groovy
ext {
    extName = 'MangaDex'
    extClass = '.MangaDexFactory'   // relative → resolves to <pkg>.MangaDexFactory
    extVersionCode = 20             // bump on EVERY change; final versionName = 1.4.20
    isNsfw = true                   // sets tachiyomi.extension.nsfw = 1
}

apply from: "$rootDir/common.gradle"
```

`extClass` maps directly to the `tachiyomi.extension.class` metadata. The loader prefixes a
leading `.` with the package (`MangaExtensionLoader.kt:307-311`), so `.MangaDexFactory` becomes
`eu.kanade.tachiyomi.extension.all.mangadex.MangaDexFactory`.

### 2b. Root build setup

`build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
        // Your fork of the source-api artifacts (publish source-api to a Maven you control,
        // or composite-build against this app tree). Avoid jitpack for your own libs.
    }
}
```

`gradle/libs.versions.toml` (the load-bearing pin is the **lib version**, which determines the
manifest `versionName` prefix and therefore loader acceptance):

```toml
[versions]
# Manga sources target source-api lib 1.5 (loader accepts 1.4..1.5; see MangaExtensionLoader.kt:56-57)
lib-manga = "1.5"
# Anime sources target source-api lib 16 (loader accepts 12..16; see AnimeExtensionLoader.kt:47-48)
lib-anime = "16"

kotlin   = "2.2.0"
serialization = "1.9.0"   # match the app's kotlinx-serialization
okhttp   = "5.0.0-alpha.14"  # match the app so DTO/parseAs semantics line up

[libraries]
source-api = { module = "your.org:source-api", version = "REPLACE" }   # publish from this tree
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }

[plugins]
android-application = { id = "com.android.application", version = "8.9.0" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

### 2c. The convention plugin / `common.gradle` (maps `ext { }` → manifest)

This is where `extVersionCode` + the lib version become a loader-valid `versionName`, and where
the feature flag and class metadata are injected. *(Structure modeled on the keiyoushi/yuzono
`common.gradle`; values are derived from this tree's loaders — Inference where noted.)*

```groovy
apply plugin: 'com.android.application'
apply plugin: 'kotlin-android'
apply plugin: 'kotlinx-serialization'

assert ext.has("extName")
assert ext.has("extClass")
assert ext.has("extVersionCode")

// LIB version comes from the catalog; manga = 1.5, anime would use 16.
// Loader builds libVersion = versionName.substringBeforeLast('.').toDouble()
// and requires LIB_VERSION_MIN <= libVersion <= LIB_VERSION_MAX.
def libVersion = "1.5"   // MangaExtensionLoader.kt:56-57 accepts 1.4..1.5

android {
    compileSdk 35
    namespace "eu.kanade.tachiyomi.extension"

    defaultConfig {
        minSdk 26
        targetSdk 34
        applicationId "eu.kanade.tachiyomi.extension.${project.parent.name}.${project.name}"
        versionCode ext.extVersionCode
        // FINAL versionName the loader parses: "<libVersion>.<extVersionCode>"  → e.g. 1.5.20
        versionName "${libVersion}.${ext.extVersionCode}"

        // Display name: loader strips the "Tachiyomi: " prefix (MangaExtensionLoader.kt:252)
        manifestPlaceholders = [
            appName : "Tachiyomi: ${ext.extName}",
            // tachiyomi.extension.class metadata (MangaExtensionLoader.kt:53,303)
            extClass: ext.extClass,
            nsfw    : ext.has("isNsfw") && ext.isNsfw ? 1 : 0,   // tachiyomi.extension.nsfw :55
        ]
    }

    signingConfigs {
        release {
            // Filled from CI secrets; the cert SHA-256 here MUST equal repo.json fingerprint.
            storeFile file(System.getenv("CI_KEYSTORE_PATH") ?: "ci.keystore")
            storePassword System.getenv("CI_KEYSTORE_PASSWORD")
            keyAlias System.getenv("CI_KEY_ALIAS")
            keyPassword System.getenv("CI_KEY_PASSWORD")
        }
    }
    buildTypes {
        release { signingConfig signingConfigs.release }
    }
}

dependencies {
    compileOnly libs.source.api          // provided by the host app at runtime
    compileOnly libs.kotlinx.serialization.json
}
```

The shared **`AndroidManifest.xml`** that the plugin uses (one file for all sources; placeholders
fill the per-source bits):

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <!-- The marker the loader filters on: MangaExtensionLoader.kt:52,398-400 -->
    <uses-feature android:name="tachiyomi.extension" android:required="false"/>

    <application android:label="${appName}">
        <!-- Source class list; ';'-separated; leading '.' prefixed with pkg (loader :303-312) -->
        <meta-data android:name="tachiyomi.extension.class"  android:value="${extClass}"/>
        <meta-data android:name="tachiyomi.extension.nsfw"   android:value="${nsfw}"/>
    </application>
</manifest>
```

> No `<activity>` is required. Only add one (a `UrlActivity` for `tachiyomi://`/`aniyomi://`
> deep-link handling) if you actually want URL-import support. Least privilege: don't.

---

## 3. A complete MangaDex-style extension skeleton (JSON API)

MangaDex exposes a clean JSON REST API, so we extend **`HttpSource`** directly (not
`ParsedHttpSource`, which is for HTML/jsoup) and parse responses with the host's
`Response.parseAs<T>()` extension. This is the most resilient pattern: no CSS selectors to break.

**Contract methods we must implement** (all `abstract` in
`source-api/.../source/online/HttpSource.kt`): `popularMangaRequest` (`:125`) /
`popularMangaParse` (`:132`); `searchMangaRequest` (`:169`) / `searchMangaParse` (`:176`);
`latestUpdatesRequest` (`:197`) / `latestUpdatesParse` (`:204`); `mangaDetailsParse` (`:242`);
`chapterListParse` (`:280`); `chapterPageParse` (`:287`); `pageListParse` (`:325`);
`imageUrlParse` (`:361`). Plus `name`/`lang`/`supportsLatest` from `CatalogueSource`
(`source-api/.../source/CatalogueSource.kt:13,18`) and `baseUrl` from `HttpSource` (`:39`).

`setUrlWithoutDomain(...)` is provided on `HttpSource` for both `SManga` and `SChapter`
(`HttpSource.kt:391-403`) — always use it so saved library entries survive a domain change.
The host JSON parser is `context(Json) inline fun <reified T> Response.parseAs(): T`
(`core/common/.../network/OkHttpExtensions.kt:135-138`); `GET`/`POST` come from
`core/common/.../network/Requests.kt:20,43`; rate limiting from
`core/common/.../network/interceptor/RateLimitInterceptor.kt:53`.

### `dto/MangaDexDto.kt` — serialization DTOs

```kotlin
package eu.kanade.tachiyomi.extension.all.mangadex.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MangaListDto(
    val data: List<MangaDataDto> = emptyList(),
    val limit: Int = 0,
    val offset: Int = 0,
    val total: Int = 0,
)

@Serializable
data class MangaDataDto(
    val id: String,
    val attributes: MangaAttributesDto,
    val relationships: List<RelationshipDto> = emptyList(),
)

@Serializable
data class MangaAttributesDto(
    val title: Map<String, String> = emptyMap(),
    val description: Map<String, String> = emptyMap(),
    val status: String? = null,
    val tags: List<TagDto> = emptyList(),
)

@Serializable
data class TagDto(val attributes: TagAttributesDto)

@Serializable
data class TagAttributesDto(val name: Map<String, String> = emptyMap())

@Serializable
data class RelationshipDto(
    val id: String,
    val type: String,
    val attributes: AuthorAttributesDto? = null,
)

@Serializable
data class AuthorAttributesDto(val name: String? = null, val fileName: String? = null)

@Serializable
data class ChapterListDto(
    val data: List<ChapterDataDto> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
)

@Serializable
data class ChapterDataDto(
    val id: String,
    val attributes: ChapterAttributesDto,
    val relationships: List<RelationshipDto> = emptyList(),
)

@Serializable
data class ChapterAttributesDto(
    val title: String? = null,
    val chapter: String? = null,
    val volume: String? = null,
    @SerialName("translatedLanguage") val translatedLanguage: String? = null,
    @SerialName("publishAt") val publishAt: String? = null,
)

// "at-home" page server response
@Serializable
data class AtHomeDto(val baseUrl: String, val chapter: AtHomeChapterDto)

@Serializable
data class AtHomeChapterDto(val hash: String, val data: List<String> = emptyList())
```

### `MangaDex.kt` — the source (extends `HttpSource`)

```kotlin
package eu.kanade.tachiyomi.extension.all.mangadex

import eu.kanade.tachiyomi.extension.all.mangadex.dto.AtHomeDto
import eu.kanade.tachiyomi.extension.all.mangadex.dto.ChapterListDto
import eu.kanade.tachiyomi.extension.all.mangadex.dto.MangaDataDto
import eu.kanade.tachiyomi.extension.all.mangadex.dto.MangaListDto
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.network.parseAs
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

open class MangaDex(override val lang: String, private val dexLang: String) : HttpSource() {

    override val name = "MangaDex"

    // baseUrl is the website; API host is separate. Both kept as vals so a domain hop is 1 edit.
    override val baseUrl = "https://mangadex.org"
    private val apiUrl = "https://api.mangadex.org"
    private val cdnUrl = "https://uploads.mangadex.org"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    // Respect MangaDex's published limit (5 req/s) via the host rate-limit interceptor.
    // RateLimitInterceptor.kt:53 → rateLimit(permits, period).
    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(permits = 5, period = kotlin.time.Duration.parse("1s"))
        .build()

    // ---- Popular ----------------------------------------------------------
    override fun popularMangaRequest(page: Int): Request {
        val url = "$apiUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("order[followedCount]", "desc")
            .addQueryParameter("limit", LIMIT.toString())
            .addQueryParameter("offset", offset(page))
            .addQueryParameter("includes[]", "cover_art")
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val result = with(json) { response.parseAs<MangaListDto>() }
        val mangas = result.data.map(::mangaFromDto)
        val hasNext = result.offset + result.limit < result.total
        return MangasPage(mangas, hasNext)
    }

    // ---- Latest -----------------------------------------------------------
    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("order[latestUploadedChapter]", "desc")
            .addQueryParameter("availableTranslatedLanguage[]", dexLang)
            .addQueryParameter("limit", LIMIT.toString())
            .addQueryParameter("offset", offset(page))
            .addQueryParameter("includes[]", "cover_art")
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ---- Search -----------------------------------------------------------
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("title", query)
            .addQueryParameter("limit", LIMIT.toString())
            .addQueryParameter("offset", offset(page))
            .addQueryParameter("includes[]", "cover_art")
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ---- Details ----------------------------------------------------------
    // mangaDetailsRequest is open on HttpSource (:233). Override to hit the API by UUID.
    override fun mangaDetailsRequest(manga: SManga): Request {
        val id = manga.url.substringAfterLast("/")
        val url = "$apiUrl/manga/$id".toHttpUrl().newBuilder()
            .addQueryParameter("includes[]", "cover_art")
            .addQueryParameter("includes[]", "author")
            .addQueryParameter("includes[]", "artist")
            .build()
        return GET(url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val data = with(json) { response.parseAs<MangaWrapperDto>() }.data
        return mangaFromDto(data)
    }

    // ---- Chapters ---------------------------------------------------------
    override fun chapterListRequest(manga: SManga): Request {
        val id = manga.url.substringAfterLast("/")
        val url = "$apiUrl/manga/$id/feed".toHttpUrl().newBuilder()
            .addQueryParameter("translatedLanguage[]", dexLang)
            .addQueryParameter("order[chapter]", "desc")
            .addQueryParameter("limit", "500")
            .addQueryParameter("includes[]", "scanlation_group")
            .build()
        return GET(url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val result = with(json) { response.parseAs<ChapterListDto>() }
        return result.data.map { dto ->
            SChapter.create().apply {
                // setUrlWithoutDomain provided by HttpSource (:391); survives domain changes.
                setUrlWithoutDomain("$baseUrl/chapter/${dto.id}")
                name = buildString {
                    dto.attributes.volume?.let { append("Vol. $it ") }
                    dto.attributes.chapter?.let { append("Ch. $it ") }
                    dto.attributes.title?.takeIf(String::isNotBlank)?.let { append("- $it") }
                }.trim().ifEmpty { "Oneshot" }
                chapter_number = dto.attributes.chapter?.toFloatOrNull() ?: -1f
                scanlator = dto.relationships
                    .firstOrNull { it.type == "scanlation_group" }?.attributes?.name
                date_upload = dto.attributes.publishAt?.let(::parseDate) ?: 0L
            }
        }
    }

    // chapterPageParse is abstract on HttpSource (:287) even for JSON sources; unused here.
    override fun chapterPageParse(response: Response): SChapter =
        throw UnsupportedOperationException()

    // ---- Pages ------------------------------------------------------------
    override fun pageListRequest(chapter: SChapter): Request {
        val id = chapter.url.substringAfterLast("/")
        return GET("$apiUrl/at-home/server/$id", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val atHome = with(json) { response.parseAs<AtHomeDto>() }
        return atHome.chapter.data.mapIndexed { i, file ->
            Page(i, imageUrl = "${atHome.baseUrl}/data/${atHome.chapter.hash}/$file")
        }
    }

    // imageUrlParse is abstract (:361); pages already carry absolute imageUrl, so it's never called.
    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException()

    // ---- Helpers ----------------------------------------------------------
    private fun mangaFromDto(data: MangaDataDto): SManga = SManga.create().apply {
        setUrlWithoutDomain("$baseUrl/manga/${data.id}")
        title = data.attributes.title.values.firstOrNull().orEmpty()
        description = data.attributes.description[dexLang]
            ?: data.attributes.description["en"]
        genre = data.attributes.tags
            .mapNotNull { it.attributes.name["en"] }
            .joinToString()
        author = data.relationships.firstOrNull { it.type == "author" }?.attributes?.name
        artist = data.relationships.firstOrNull { it.type == "artist" }?.attributes?.name
        status = when (data.attributes.status) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "cancelled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        val coverFile = data.relationships
            .firstOrNull { it.type == "cover_art" }?.attributes?.fileName
        thumbnail_url = coverFile?.let { "$cdnUrl/covers/${data.id}/$it.256.jpg" }
    }

    private fun offset(page: Int) = ((page - 1) * LIMIT).toString()

    private fun parseDate(date: String): Long =
        runCatching { dateFormat.parse(date)?.time }.getOrNull() ?: 0L

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'+00:00'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }

    companion object {
        private const val LIMIT = 20
    }
}
```

> One small DTO referenced above for the single-manga endpoint:
> ```kotlin
> @kotlinx.serialization.Serializable
> data class MangaWrapperDto(val data: MangaDataDto)
> ```

### `MangaDexFactory.kt` — multi-language via `SourceFactory`

The loader instantiates `extClass`; if it's a `SourceFactory`, it calls `createSources()`
(`MangaExtensionLoader.kt:317`; contract `source-api/.../source/SourceFactory.kt:6-11`). One APK,
many language variants, each a distinct source `id` (the id is derived from `name/lang/versionId`,
`HttpSource.kt:57,87-91`).

```kotlin
package eu.kanade.tachiyomi.extension.all.mangadex

import eu.kanade.tachiyomi.source.MangaSource
import eu.kanade.tachiyomi.source.SourceFactory

class MangaDexFactory : SourceFactory {
    override fun createSources(): List<MangaSource> = listOf(
        MangaDex("en", "en"),
        MangaDex("es", "es"),
        MangaDex("fr", "fr"),
        MangaDex("ja", "ja"),
        // … add languages here; each becomes its own selectable source.
    )
}
```

---

## 4. The `lib-multisrc` theme pattern

A *theme* is a shared base class for a CMS that powers dozens of sites (e.g. WordPress + the
"Madara" manga theme). The base class centralizes every CSS selector and request shape; a derived
site becomes ~30 lines: name, baseUrl, lang, and selector overrides only when that site deviates.
**A site-wide markup change is then a one-line fix in the base class** that fixes every derived
source at once.

### `lib-multisrc/madara/.../Madara.kt` — the base (extends `ParsedHttpSource`)

`ParsedHttpSource` (`source-api/.../source/online/ParsedHttpSource.kt:16`) already implements
`popularMangaParse`/`searchMangaParse`/`latestUpdatesParse` in terms of `*Selector()` +
`*FromElement()` (`:23-130`), and `chapterListParse`/`pageListParse` in terms of selectors
(`:153-184`). The theme just supplies the selectors — once — as overridable `open` members.

```kotlin
package eu.kanade.tachiyomi.multisrc.madara

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

abstract class Madara(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
) : ParsedHttpSource() {

    override val supportsLatest = true

    // ---- Centralized, overridable selectors (the whole point of the theme) ----
    protected open val mangaSubString = "manga"
    protected open fun popularMangaSelector() = "div.page-item-detail.manga"
    protected open val mangaUrlSelector = "div.post-title a"
    protected open val mangaThumbnailSelector = "img"
    protected open fun popularMangaNextPageSelector(): String? = "div.nav-previous, a.nextpostslink"
    protected open val chapterListSelectorValue = "li.wp-manga-chapter"
    protected open val pageListSelector = "div.page-break img, li.blocks-gallery-item img"

    // ---- Requests (overridable; most sites share these) ----
    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/$mangaSubString/page/$page/?m_orderby=views", headers)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/$mangaSubString/page/$page/?m_orderby=latest", headers)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/page/$page/?s=$query&post_type=wp-manga", headers)

    // ---- Element mappers (shared once) ----
    override fun popularMangaSelector() = popularMangaSelector()
    override fun searchMangaSelector() = popularMangaSelector()
    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()
    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    private fun mangaFromElement(element: Element) = SManga.create().apply {
        val link = element.selectFirst(mangaUrlSelector)!!
        setUrlWithoutDomain(link.attr("href"))     // HttpSource.kt:401 — domain-change safe
        title = link.text()
        thumbnail_url = element.selectFirst(mangaThumbnailSelector)
            ?.let { it.absUrl("data-src").ifEmpty { it.absUrl("src") } }
    }

    override fun popularMangaFromElement(element: Element) = mangaFromElement(element)
    override fun searchMangaFromElement(element: Element) = mangaFromElement(element)
    override fun latestUpdatesFromElement(element: Element) = mangaFromElement(element)

    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        title = document.selectFirst("div.post-title h1")!!.text()
        description = document.selectFirst("div.description-summary")?.text()
        genre = document.select("div.genres-content a").joinToString { it.text() }
        thumbnail_url = document.selectFirst("div.summary_image img")?.absUrl("src")
    }

    override fun chapterListSelector() = chapterListSelectorValue
    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        val link = element.selectFirst("a")!!
        setUrlWithoutDomain(link.attr("href"))
        name = link.text()
    }

    override fun pageListParse(document: Document): List<Page> =
        document.select(pageListSelector).mapIndexed { i, img ->
            Page(i, imageUrl = img.absUrl("data-src").ifEmpty { img.absUrl("src") })
        }

    override fun imageUrlParse(document: Document): String =
        throw UnsupportedOperationException()
}
```

### A derived site — `src/en/somemadarasite/.../SomeMadaraSite.kt` (~10 lines)

```kotlin
package eu.kanade.tachiyomi.extension.en.somemadarasite

import eu.kanade.tachiyomi.multisrc.madara.Madara

class SomeMadaraSite : Madara(
    name = "Some Madara Site",
    baseUrl = "https://somemadarasite.example",
    lang = "en",
) {
    // Only override when this site deviates from the theme defaults:
    override val mangaSubString = "series"
}
```

Its `build.gradle` just declares `extName/extClass/extVersionCode/isNsfw` (Section 2a) and depends
on the `lib-multisrc/madara` module. Spinning up 20 Madara sites = 20 tiny modules; if Madara
changes its chapter markup, you edit `chapterListSelectorValue` **once**.

> **Anime parallel.** The same pattern applies to anime via `ParsedAnimeHttpSource`
> (`source-api/.../animesource/online/ParsedAnimeHttpSource.kt`) and themes like ZoroTheme. On
> this tree's **lib 16** anime API, the video pipeline is `hosterList`/`videoList`
> (`source-api/.../animesource/online/AnimeHttpSource.kt:330+`), so anime themes centralize
> hoster/embed selectors instead of page selectors.

---

## 5. GitHub Actions CI (FOSS): build → inspect → sign → index → publish

This workflow builds every source module, runs an **Inspector** step to extract each APK's
`Source` classes (id/lang/name/baseUrl) for the `sources[]` array, signs with a key in CI
secrets, generates `index.min.json` + `repo.json` (with the matching `signingKeyFingerprint`), and
pushes the artifacts to the `repo` branch.

> *Inference:* the Inspector tool, `create-repo.py`, and `apkanalyzer`/`aapt` glue are CI
> conventions from the keiyoushi/yuzono model — the app does **not** mandate any particular
> generator. What the app *requires* is proven by file:line in the intro: `repo.json` meta shape
> (`ExtensionRepoDto.kt:6-17`), `index.min.json` array shape (`MangaExtensionApi.kt:144-162`),
> `apk/<apk>` (`:136`), `icon/<pkg>.png` (`:129`), and the fingerprint-trust rule
> (`TrustMangaExtension.kt:14-18`).

`.github/workflows/build_push.yml`:

```yaml
name: Build & publish extensions repo

on:
  push:
    branches: [main]

concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

permissions:
  contents: write   # least privilege: only what's needed to push the repo branch

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout source (main)
        uses: actions/checkout@<pinned-sha>   # pin actions to SHAs, not tags

      - name: Set up JDK 17
        uses: actions/setup-java@<pinned-sha>
        with:
          distribution: temurin
          java-version: 17

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@<pinned-sha>

      # ---- Reconstruct the signing keystore from a CI secret (base64) ----
      - name: Decode signing keystore
        env:
          KEYSTORE_B64: ${{ secrets.SIGNING_KEYSTORE_B64 }}
        run: echo "$KEYSTORE_B64" | base64 -d > "$RUNNER_TEMP/ci.keystore"

      # ---- Build & sign all source APKs (build-from-source ONLY) ----
      - name: Assemble release APKs
        env:
          CI_KEYSTORE_PATH: ${{ runner.temp }}/ci.keystore
          CI_KEYSTORE_PASSWORD: ${{ secrets.SIGNING_STORE_PASSWORD }}
          CI_KEY_ALIAS: ${{ secrets.SIGNING_KEY_ALIAS }}
          CI_KEY_PASSWORD: ${{ secrets.SIGNING_KEY_PASSWORD }}
        run: ./gradlew assembleRelease

      # ---- Inspector: load each APK, extract its Source classes' id/lang/name/baseUrl ----
      # Outputs a per-apk JSON consumed by create-repo.py to fill sources[].
      - name: Run Inspector
        run: |
          mkdir -p repo/apk repo/icon
          for apk in $(find . -path '*/build/outputs/apk/release/*-release.apk'); do
            cp "$apk" "repo/apk/$(basename "$apk")"
            java -jar tools/inspector.jar "$apk" "repo/apk/$(basename "$apk").json"
          done

      - name: Extract icons
        run: |
          # Pull each APK's launcher icon → repo/icon/<pkg>.png  (app expects icon/<pkg>.png)
          python tools/extract_icons.py repo/apk repo/icon

      # ---- Compute the signing-cert SHA-256 fingerprint (== repo.json value) ----
      - name: Compute signing fingerprint
        id: fp
        env:
          CI_KEYSTORE_PATH: ${{ runner.temp }}/ci.keystore
          CI_KEYSTORE_PASSWORD: ${{ secrets.SIGNING_STORE_PASSWORD }}
          CI_KEY_ALIAS: ${{ secrets.SIGNING_KEY_ALIAS }}
        run: |
          # SHA-256 of the signing cert, lowercase, no colons — matches Hash.sha256 of the
          # signing cert that the loader compares (MangaExtensionLoader.kt:408-422).
          FP=$(keytool -list -v -keystore "$CI_KEYSTORE_PATH" \
                 -alias "$CI_KEY_ALIAS" -storepass "$CI_KEYSTORE_PASSWORD" \
               | grep -A1 'SHA256:' | grep -oE '([0-9A-F]{2}:){31}[0-9A-F]{2}' \
               | tr -d ':' | tr 'A-F' 'a-f' | head -n1)
          echo "fingerprint=$FP" >> "$GITHUB_OUTPUT"

      # ---- Generate index.min.json (+ index.json) and repo.json ----
      - name: Generate index + repo metadata
        env:
          SIGNING_FINGERPRINT: ${{ steps.fp.outputs.fingerprint }}
          REPO_HOST: ${{ github.repository_owner }}.github.io   # or raw.githubusercontent.com
        run: |
          python tools/create_repo.py repo/apk repo/index.json repo/index.min.json
          # repo.json — shape fixed by ExtensionRepoDto.kt:6-17
          cat > repo/repo.json <<EOF
          {
            "meta": {
              "name": "Your Extensions",
              "shortName": "yourext",
              "website": "https://github.com/${{ github.repository }}",
              "signingKeyFingerprint": "${SIGNING_FINGERPRINT}"
            }
          }
          EOF

      # ---- Publish artifacts to the 'repo' branch (orphan, artifacts only) ----
      - name: Publish to repo branch
        uses: peaceiris/actions-gh-pages@<pinned-sha>
        with:
          github_token: ${{ secrets.GITHUB_TOKEN }}
          publish_dir: ./repo
          publish_branch: repo
          force_orphan: true
```

**How the app consumes it (full circle):**
- User adds `https://<host>/repo/index.min.json`; regex-validated, `/index.min.json` stripped to
  `baseUrl` (`CreateMangaExtensionRepo.kt:15,23`).
- App GETs `$baseUrl/repo.json`, records `signingKeyFingerprint` (`ExtensionRepoService.kt:25-28`).
- App GETs `$baseUrl/index.min.json`, filters by lib version, lists extensions
  (`MangaExtensionApi.kt:55,116`).
- On install, the loader rejects unsigned APKs (`MangaExtensionLoader.kt:273-276`) and marks the
  APK **trusted** iff its signing-cert SHA-256 equals your `signingKeyFingerprint`
  (`TrustMangaExtension.kt:15-17`). **This is why `steps.fp.outputs.fingerprint` and the value
  written into `repo.json` must be the SAME cert.**

**`index.min.json` contract** (each element — `MangaExtensionApi.kt:144-162`):

```json
[
  {
    "name": "Tachiyomi: MangaDex",
    "pkg": "eu.kanade.tachiyomi.extension.all.mangadex",
    "apk": "tachiyomi-all.mangadex-v1.5.20.apk",
    "lang": "all",
    "code": 20,
    "version": "1.5.20",
    "nsfw": 1,
    "sources": [
      { "id": 2499283573021220255, "lang": "en", "name": "MangaDex", "baseUrl": "https://mangadex.org" }
    ]
  }
]
```

(`version` must keep its `<lib>.<code>` shape: the app derives lib version via
`version.substringBeforeLast('.').toDouble()`, `MangaExtensionApi.kt:140`, and gates on
`LIB_VERSION_MIN..MAX`, `:116`.)

---

## 6. Security / trust hardening you control

The app's only enforced guarantees are: **unsigned APKs are rejected** and **a trusted APK is one
your repo key signed** (`MangaExtensionLoader.kt:273-276`; `TrustMangaExtension.kt:14-18`). There
is **no runtime sandbox** — a trusted extension can read app data, use the shared cookie jar, and
run arbitrary code in-process (`MangaExtensionLoader.kt:297,315`; `HttpSource.kt:34,67`). Trusting
your repo = trusting *every* APK your key *ever* signs. Therefore the human review *is* the
security model. Make these non-negotiable in `CONTRIBUTING.md`:

1. **Mandatory human review of every network call.** Every `GET`/`POST`/`client.newCall` must hit
   a domain that is a literal in the source (or the overridable `baseUrl`). Reject any request to
   a URL assembled from decoded/decrypted strings or fetched at runtime. Diff every PR's request
   set.
2. **Reject obfuscation outright.** No base64/hex/xor "decode-then-eval", no reflection
   (`Class.forName`, `Method.invoke`) inside an extension, no `DexClassLoader`/dynamic dex, no
   runtime code download. (The app audit found the *host* clean of these; keep your extensions
   clean too.) If you can't read what it does, it doesn't merge.
3. **One signing key, pinned via `repo.json` fingerprint, never rotated silently.** Keep the
   keystore only in CI secrets; publish the fingerprint in `repo.json` and your README so users
   can verify it. Because trust is TOFU-per-repo, a silent key rotation breaks every user's trust
   chain — treat rotation as a major, announced event.
4. **Build-from-source-only CI.** Never accept a prebuilt APK into `apk/`. The only path to the
   `repo` branch is `assembleRelease` over reviewed `main` source (Section 5). This makes the
   published binary a deterministic function of auditable source.
5. **Least privilege.** Extensions use the host `NetworkHelper` client (`HttpSource.kt:67`) — they
   should not request Android permissions, should ship **no** `<activity>`/services unless a
   `UrlActivity` is genuinely needed, and should never touch storage outside the source API. The
   convention plugin emits a minimal manifest (Section 2c): feature flag + class/nsfw metadata,
   nothing else.
6. **Pin your CI supply chain.** Pin every GitHub Action to a commit SHA (not a tag), gate
   secret-using steps so forks can't read them, and avoid `curl | bash`. (Mirrors the hardening
   already present in this app's own workflows.)
7. **Keep `main` (auditable) and `repo` (generated) strictly separate**, tag releases, and keep
   the per-PR request/permission diff in the review checklist.

> The blunt version for your README: *"Adding this repo means trusting our single signing key with
> full in-process access on your device. We mitigate that with mandatory human review of every
> network call, a zero-obfuscation policy, and build-from-source CI. Verify our key fingerprint:
> `<fingerprint>`."*

---

## 7. Resilience to markup / API change

Design every source so the *common* failures (a CSS tweak, a domain hop) are one-line fixes, and
the *rare* failures (incompatible URL scheme) are an explicit, migration-free version bump.

1. **Prefer JSON APIs over scraping.** Extend `HttpSource` + `parseAs<DTO>()` (Section 3) wherever
   the site has an API. JSON shapes change far less often than HTML, and `@Serializable` DTOs with
   defaults degrade gracefully when fields are added.
2. **Centralize CSS selectors** as named `open` vals in a base class (Section 4). One edit to
   `chapterListSelectorValue` fixes every derived site at once.
3. **Theme base classes (`lib-multisrc`)** for any CMS that powers many sites: fix once, fix all.
   Shared `lib/` for video extractors / crypto / date parsing so a host-format change is centralized.
4. **`baseUrl` overridable.** Always store URLs domain-relative via `setUrlWithoutDomain()`
   (`HttpSource.kt:391-403`) so a domain change doesn't orphan a user's library. For sites that
   hop domains often, expose `baseUrl` as a user-editable preference via `ConfigurableSource`
   (`source-api/.../source/ConfigurableSource.kt:9-20`) backed by `getSourcePreferences()` — the
   user (or you, via an update) repoints without losing saved entries.
5. **`versionId` bumps for incompatible changes.** When a site's URL scheme changes so old saved
   URLs can no longer resolve, increment `versionId` (`HttpSource.kt:45`). Because the source `id`
   is `MD5("${name}/$lang/$versionId")` (`:57,87-91`), this creates a *new* source id and the app
   treats it as a distinct source — clean break, no half-broken state. For everything else, just
   bump `extVersionCode` (Section 2a) so users get the fix as a normal update.
6. **DTO tolerance.** Give every `@Serializable` field a default and use nullable types for
   optional data (Section 3 DTOs) so a single missing/extra field in the API response doesn't
   throw and brick the whole source.

---

### Quick start checklist

- [ ] Create the repo with the `main` layout (Section 1); create an empty orphan `repo` branch.
- [ ] Generate a signing keystore; store it base64 in `secrets.SIGNING_KEYSTORE_B64` + passwords.
- [ ] Add the convention plugin / `common.gradle` (Section 2) — verify it emits `versionName`
      `1.5.x` (manga) or `16.x` (anime) so the loader accepts it.
- [ ] Port **MangaDex** first (Section 3) — your JSON-API reference architecture.
- [ ] Bring in **`lib-multisrc/madara`** + a few sites (Section 4) for cheap volume.
- [ ] Wire CI (Section 5); confirm `repo.json.signingKeyFingerprint == keytool SHA-256`.
- [ ] Add this repo's `…/repo/index.min.json` in the app; install MangaDex; verify it loads as
      **trusted** (not "Untrusted") — that proves the fingerprint chain end to end.
- [ ] Publish your key fingerprint + review policy in the README (Section 6).
