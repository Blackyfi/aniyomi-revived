# Keiyoushi Trust Plan — own, audit, sign, and lock down

**Owner:** Blackyfi (Nicolas). **Date:** 2026-05-29.
**Decision (locked):** *Deny-by-default trust.* The app loads **only** extensions signed by
**your** key (fingerprint pinned in your `repo.json`). Everything else is **blocked** — the
"trust this untrusted extension" user override is removed/disabled. You audit + sign + maintain
everything that ships. This is pillars **A+B+C+D** collapsed into one pipeline.

> Why this is coherent: extensions run **in-process, no sandbox, full app perms** (the only
> boundary is signature trust — see `PROFILE_REPORT.md` / `EXTENSIONS_REPO_STARTER.md`). If the
> only key the app trusts is yours, and you only sign audited source you rebuilt, then the trust
> problem reduces to "review the diffs you choose to pull."

Repos in play:
- **APP**: `C:\Users\nicol\Documents\Projects\aniyomi-revived` (the loader / trust boundary)
- **EXT**: `C:\Users\nicol\Documents\Projects\aniyomi-revived-extensions` (your signed repo — LIVE)
- **KEI** (reference clone, read-only): `C:\Users\nicol\Documents\Projects\keiyoushi-extensions-source`
  (`keiyoushi/extensions-source`, `main`, Apache-2.0, ~188 MB)

---

## Recon (ground truth, 2026-05-29)

| Metric | Count |
|---|---|
| Source modules `src/<lang>/<name>` | **1,468** |
| Multisrc themes `lib-multisrc/*` | **67** |
| Shared `lib/*` modules | **14** |
| Total `.kt` | 2,753 |

**Risk sweep (file hit counts across `src`+`lib`+`lib-multisrc`):**
`DexClassLoader`=0 · `Runtime.exec`/`ProcessBuilder`=0 · `System.loadLibrary`=0 ·
reflection (`Class.forName`/`invoke`)=**2** · `Base64.decode`=86.

**Notable `lib/` modules to scrutinize:** `cryptoaes` (AES), `synchrony` (JS deobfuscator),
`speedbinb`/`publus` (DRM-ish readers), `unpacker`/`lzstring`/`seedrandom` (packed-JS decoders),
`cookieinterceptor`/`textinterceptor`/`zipinterceptor`/`randomua` (network interceptors).

**Audit leverage:** the 67 themes + 14 libs are the bulk of the *code paths*; the 1,468 sources
are mostly thin theme-derived overrides. Audit themes+libs first → triage standalone sources.

---

## Pillar D — Lock the app trust boundary (KEYSTONE, APP repo)

Make trust = your fingerprint only; remove the untrusted escape hatch.

- **D1. Map the current trust path. ✅ DONE.** Decision point is `TrustMangaExtension.isTrusted`
  (`TrustMangaExtension.kt:14-18`):
  `trustedFingerprints.any { fingerprints.contains(it) } || key in preferences.trustedExtensions().get()`.
  First clause = repo-`repo.json`-fingerprint trust (KEEP). Second clause = per-extension manual
  override, written by `trust()` (`:20-27`), invoked from the UI "Trust" action. Loader returns
  `MangaLoadResult.Untrusted` at `MangaExtensionLoader.kt:277-288`; unsigned rejected `:273-276`;
  cert SHA-256 via `getSignatures()` `:408-422`. Anime mirrors: `TrustAnimeExtension.kt` +
  `AnimeExtensionLoader.kt`.
- **D2. Deny-by-default.** (a) Drop the `|| key in preferences.trustedExtensions().get()` clause in
  both `TrustMangaExtension.kt:17` and `TrustAnimeExtension.kt` → trusted **iff** signing-cert
  SHA-256 ∈ a configured repo's `signingKeyFingerprint`. (b) Remove the UI "Trust" action that
  calls `trust()` (in `MangaExtensionsScreenModel.kt` / `AnimeExtensionsScreenModel.kt` and the
  `Untrusted` extension UI) so no manual override can be added. (c) Optionally keep `trust()`/
  `revokeAll()` only for internal use or delete. Untrusted extensions still *display* as blocked,
  just with no "trust anyway" button.
- **D3. Optional install-time static gate.** Reject APKs whose manifest requests Android perms
  beyond the minimal set, or that declare unexpected components. (Stretch.)
- **Acceptance:** an APK signed by any non-Blackyfi key cannot be loaded by any UI path; existing
  Blackyfi-signed extensions still load as trusted. Local branch only — do NOT push without OK.

## Pillar C — Audit tooling (the gate, EXT repo `tools/`)

- **C1. Static audit scanner** (`tools/audit_source.py` or a small Kotlin/JVM tool): for a given
  source/theme/lib dir, flag (a) network calls whose host is NOT a source literal or `baseUrl`
  pref, (b) reflection, (c) dynamic dex / `loadLibrary`, (d) `Runtime.exec`, (e) decode-then-eval
  (base64/hex/xor feeding URL/JS/class load), (f) added Android permissions/components. Output a
  per-module verdict: `clean` / `review` / `risk` + evidence lines.
- **C2. Wire into CI** on EXT `main` as a required gate before signing.
- **Acceptance:** running C1 over the KEI clone reproduces the manual audit's findings (Pillar B).

## Pillar B — Vendor → audit → rebuild → sign → publish (EXT repo)

- **B1. Audit pass over KEI** — ✅ **DONE** (`KEIYOUSHI_AUDIT.md`). 13-agent fan-out over 67
  themes + 14 libs + hotspots: **0 risk verdicts**, all clean/review. 4 patch-on-vendor items
  (mangahub ipify IP-leak, mmlook TLS downgrade, randomua keiyoushi.github.io dep, cookieinterceptor
  loose host check). Standalone ~1,400 `src/` modules = follow-up triage (C-scanner).
- **B2. Selection.** Pick the first vendor batch: durable, clean-audit sources + the themes that
  back them. (Start with what your users actually want; durable-first per project memory.)
- **B3. Vendor.** Copy selected source into EXT (preserve Apache-2.0 NOTICE/attribution), adapt to
  this fork's API (`HttpSource.chapterPageParse` override gotcha, `:lib-stub`, lib coordinates).
- **B4. Rebuild + sign + publish** via existing EXT CI → `repo` branch. Never accept prebuilt APKs.
- **Acceptance:** vendored sources `assembleRelease` → signed with the Blackyfi key, load as
  trusted, render in-app.

## Pillar A — Your repo (destination, DONE/extend)

Already live (`Blackyfi/aniyomi-revived-extensions`, 6 sources). B-vendored sources land here.

---

## Phasing

1. **NOW (read-only, safe):** B1 audit fan-out over KEI → `KEIYOUSHI_AUDIT.md`. In parallel,
   D1 (map the trust path) — pure investigation.
2. **Next:** C1 scanner (codifies B1's checks) + D2 deny-by-default implementation (local branch).
3. **Then:** B2 selection → B3 vendor → B4 publish, gated by C2.
4. Ongoing maintenance (you): pull KEI diffs → C scanner → human-review flags → re-sign.

## Guardrails
- No pushing/publishing, no key ops, no making anything public without explicit approval.
- KEI clone is **read-only reference** — never build/sign from it directly; vendor into EXT first.
- Cite `file:line`; report acceptance results explicitly.
