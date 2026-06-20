# Vendoring Plan — migrate all clean keiyoushi sources into the fork's EXT repo

**Decisions (user, 2026-05-29):** vendor **all 1,367 clean** sources (big fan-out); **remediate
then vendor** the 7 risk sources. User owns ongoing maintenance. Build on `KEIYOUSHI_AUDIT.md`.

**Source of truth:** read-only clone `C:\Users\nicol\Documents\Projects\keiyoushi-extensions-source`.
**Target:** `C:\Users\nicol\Documents\Projects\aniyomi-revived-extensions` (EXT, LIVE — local commits
only, no push without approval).

## Scale & the dependency graph (vendor in this order)
1,468 src modules · 67 lib-multisrc themes · 14 lib/ modules · 1 `core` utils package
(`keiyoushi.utils`, imported by **748** sources). Nothing compiles until its deps exist, so:

```
core (keiyoushi.utils)  ─┐
14 lib/ modules         ─┼─►  67 lib-multisrc themes  ─►  1,367 src sources
(enabler: HttpSource)   ─┘
```

## ✅ Enabler done (the key unlock)
`HttpSource.chapterPageParse` was `abstract` in this fork → would force a hand-edit on all ~1,400
sources. Changed to `open` default-throw in **both** `source-api/.../HttpSource.kt:287` (app) and
EXT `lib-stub/.../HttpSource.kt:60`. Backward-compatible (existing overrides still work), matches
upstream Mihon. *(App rebuild verifying — task bi1xurriy.)*

## Build-system bridge (the core engineering task)
keiyoushi uses convention plugins in `gradle/build-logic` (`kei.plugins.extension.legacy`,
`kei.plugins.multisrc`, `kei.plugins.library`, `kei.plugins.android.base`, `kei.plugins.spotless`)
+ its own version catalog. EXT uses a simple `apply from: "$rootDir/common.gradle"` convention
(per-source `ext { extName/extClass/extVersionCode/isNsfw }`; themes/libs as Gradle subprojects).
**Approach = rewrite-to-EXT-convention** (not port build-logic):
- **Sources**: generate a weebcentral-style `build.gradle` from each module's `ext { }` values;
  drop the `kei.plugins…` line; declare real deps (`compileOnly libs.jsoup`, theme/lib project deps).
- **Themes/libs**: mirror the existing EXT `lib-multisrc/madara` + `mangathemesia` subproject
  setup (already in EXT) for the other ~65 themes and the 14 libs + `core`.
- Central edits (NOT parallel-safe — done once by the orchestrator): `settings.gradle.kts`
  includes for every new `:core`, `:lib:*`, `:lib-multisrc:*`, `:src:*:*`; `gradle/libs.versions.toml`
  additions. Agents touch ONLY their own module dir to stay conflict-free.

## Waves (each gated on the previous compiling)
- **Wave 0 — foundation:** vendor `core` (keiyoushi.utils, 12 files) + 14 `lib/` modules, adapt to
  fork API (some helpers like `parseAs` already exist in `core:common` — reconcile/alias, don't
  duplicate). Verify each `:compileReleaseKotlin`.
- **Wave 1 — themes:** vendor all 67 `lib-multisrc` themes (EXT has madara+mangathemesia). Verify.
- **Wave 2 — sources:** fan-out vendor the 1,367 clean sources in batches (worktree isolation),
  each compiled. Skip the 7 risk + the `review` maintainer-host ones until Wave 3.
- **Wave 3 — remediate + special:** the 7 risk sources with fixes baked in; repoint the
  maintainer-host fetchers (zerobyw/jinmantiantang/wnacg/webcomics/webnovel, randomua) off
  keiyoushi/stevenyomi infra to your own host or bundled assets.
- **Wave 4 — publish:** sign + CI → `repo` branch; update `index.min.json` (now hundreds of entries).

## Remediation specs (Wave 3) — the 7 risk sources
| Source | Fix |
|---|---|
| readcomiconline | Remove the remote-config-JSON fetch + QuickJs eval of its `imageDecryptEval`; inline a pinned, audited decode (or drop the source). |
| mangafire / mangaforfreecom / megatokyo / noindexscan / spyfakku | Delete the trust-all `X509TrustManager` + `hostnameVerifier{true}`; use the default client (+ for spyfakku, drop the self-signed mirror + WebView `proceed()`-on-SSL-error). |
| zh/manwa | Remove the `fuwt.cc` third-party mirror-list fetch; pin baseUrl(s) or a user pref. |

## Proof-of-pipeline (gates the big fan-out)
Vendor ONE source end-to-end first (demonicscans=`src/en/mangademon`: only needs `tryParse` +
the now-solved chapterPageParse + a build.gradle) → `compileReleaseKotlin` green → that confirms the
convention bridge before scaling to 1,367.

## Guardrails
No push/publish/key-ops without explicit approval. Skip the 7 risk in Waves 0-2. Cite file:line.
Re-audit any module on future upstream pulls (the C-scanner, still TODO).
