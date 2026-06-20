# Keiyoushi Audit Report (B1)

**Date:** 2026-05-29. **Source:** `keiyoushi/extensions-source` @ shallow clone (read-only).
**Method:** 13-agent fan-out (`keiyoushi-audit` workflow), per-module read + security checklist.
**Scope:** all **67** `lib-multisrc` themes + **14** shared `lib/` modules + reflection &
interceptor hotspots. (The 1,468 `src/<lang>/<name>` sources are mostly thin theme overrides;
themes+libs are the shared code paths. Standalone-source triage = follow-up.)

## Headline

**0 `risk` verdicts.** Every audited module is `clean` or `review`. No dynamic class loading,
no `Runtime.exec`, no native libs, no decode-then-execute, no reflection on remote/decoded class
names. The only reflection (`zipinterceptor`) targets the host app's *own* `ImageDecoder` for
version compat. Crypto/deobfuscation is everywhere but **always content-decode** (site-obfuscated
page/image lists → JSON/URLs), never fed to eval or class loading.

> This validates the deny-by-default model: the keiyoushi corpus is auditable and, at the theme/lib
> layer, clean. The work is *vetting + signing*, not *rewriting*.

## Items that warrant a patch BEFORE you sign/vendor

These are not malicious, but they conflict with a "trustworthy fork" posture and are cheap to fix:

| # | Module | Issue | Evidence | Fix on vendor |
|---|--------|-------|----------|---------------|
| 1 | **mangahub** (theme) | Fetches the device's **public IP from a third-party host** (`api.ipify.org`) and sends it to the source as a `browserID` tracking param. Only non-source-host egress found. | `MangaHub.kt:423-424,426` | Drop the ipify call; send a constant/empty `browserID`. |
| 2 | **mmlook** (theme) | **Disables TLS hostname verification** (`hostnameVerifier { _,_ -> true }`) on its client and **downgrades https→http** for display URLs. MITM-weakening. | `MMLook.kt:34,37` | Remove the hostname-verifier override; keep https. (Or skip this source.) |
| 3 | **randomua** (lib) | Phones **`keiyoushi.github.io`** (hardcoded, non-source) to fetch a UA list. No data sent, but your fork shouldn't depend on keiyoushi infra. | `randomua/Helper.kt:45,73` | Repoint to your own host or bundle the UA list as an asset. |
| 4 | **cookieinterceptor** (lib) | Host gate uses `endsWith(domain)` → `evil-domain.com` matches `domain.com`. Static values limit impact, but it's a loose check. | `CookieInterceptor.kt:23` | Tighten to exact-host or `"." + domain` suffix match. |

## `review` items (benign, understood — no action required, listed for the record)

**Content decryption / deobfuscation (decode → JSON/image-URL only, never executed):**
gmanga (AES-CBC), madara (CryptoAES chapter-protector), mangotheme (AES-CBC), initmanga
(AES-256-CBC + PBKDF2), manga18 / scanreader / fmreader (base64 image URLs), mmlook (base64+XOR),
cryptoaes (CryptoJS-compat lib), publus (bespoke RC4/XOR), speedbinb (scramble tables),
clipstudioreader (XOR), lzstring/unpacker/seedrandom (pure codecs), **synchrony** (runs bundled
trusted JS in **sandboxed QuickJS** — no network/DOM/file bindings).

**Image descrambling (pixel reorder of already-fetched images):** comiciviewer, gigaviewer,
speedbinb, publus, clipstudioreader, zipinterceptor (+ dataimage / textinterceptor render locally,
no egress).

**Self-auth (credentials POSTed only to the source's own host, token host-guarded):** greenshit,
heancms, hentaihand, mangotheme, libgroup (reads its own `localStorage['auth']` via WebView).

**Source-controlled CDN host (host from the source's own API/page, used for images only):**
keyoapp, sinmh, mangabox, madtheme, galleryadults, speedbinb, clipstudioreader, kemono.

**Reflection (host app's own class, version-compat shim):** zipinterceptor.

## Clean (no flags)
bakkin, colorlibanime, comicaso, eromuse, ezmanhwa, fansubscat, foolslide, fuzzydoodle, gattsu,
goda, gravureblogger, grouple, guya, hotcomics, iken, kemono, lectormoe, liliana, mangacatalog,
mangadventure, mangareader, mangataro, mangathemesia, mangawork, mangaworld, manhwaz, masonry,
mccms, mmrcms, monochrome, moonlighttl, multichan, natsuid, oceanwp, paprika, peachscan,
pizzareader, scanr, senkuro, spicytheme, stalkercms, uzaymanga, vercomics, wpcomics, yuyu,
zeistmanga, zmanga · libs: i18n, lzstring, seedrandom, textinterceptor, unpacker.

## Standalone-source triage (B-triage, all 1,468 `src/` modules)

20-agent grep-first sweep. **1,367 clean · 94 review · 7 risk.**

### 🔴 RISK — do NOT vendor without remediation (or skip)
| Source | Issue |
|---|---|
| **en/readcomiconline** | **Remote JS fetch-then-eval**: pulls a config JSON from `raw.githubusercontent.com` whose fields contain JavaScript, then runs it in-process via QuickJs. Logic can change *independent of the signed APK* — the one true code-injection vector found. |
| **all/mangafire** | Trust-all `X509TrustManager` + `hostnameVerifier{true}` → TLS/MITM protection disabled on the shared client. |
| **en/mangaforfreecom** | Same trust-all TLS bypass (`getUnsafeOkHttpClient`). |
| **en/megatokyo** | Same trust-all TLS bypass (`ignoreAllSSLErrors`). |
| **pt/noindexscan** | Same trust-all TLS bypass. |
| **en/spyfakku** | Disables TLS globally + WebView `proceed()` on every SSL error; one mirror is a self-signed `.airdns.org`. |
| **zh/manwa** | Domain-update interceptor fetches a **hardcoded third-party host** (`fuwt.cc`), base64-decodes a mirror list, and uses it as the app's base hosts → remote host-pivot controlled by a non-source party. |

### 🟡 `review` patterns (benign, but note for vendoring)
- **Content decryption** (AES/XOR/RC4/ChaCha of page/image data against the source's *own* host): the large majority — comico, qtoon, mangago, mangasin, the `vi/*` AES cluster, sixmh, izneo, mangaup, etc. All decode → image/JSON, never eval/classload.
- **Image descramble interceptors** (pixel reorder): the `ja/*` cluster (comicfuz, cycomi, magazinepocket…), coronaex, kmanga, azuki, etc.
- **Self-auth** (creds/tokens only to the source's own host): asurascans, comikey, bookwalker, kagane, softkomik, mangano, ono, nicovideoseiga…
- **DRM** (Widevine/CloudFront/Web Crypto against own host): kagane, bookwalker, jnovel, unext.
- **WebView for Cloudflare/Turnstile/Sucuri solving** (own host only): infinityscans, komiktap, mangasusu, yurigarden, comix, allanime.
- **Maintainer-host fetches** (⚠ your fork should repoint these off keiyoushi/stevenyomi infra): UA list → webcomics; metadata → webnovel; **domain-update lists from `stevenyomi.github.io`** → zerobyw, jinmantiantang, wnacg.

### Your 5 priority sources — all clear to vendor
| Source | Verdict | Backing | Patch-on-vendor |
|---|---|---|---|
| mangadex | clean | standalone (+i18n) | add `chapterPageParse` override (1×, base class) |
| **thunderscans** | clean | mangathemesia (you have it) | `chapterPageParse` override on base |
| **webtoons** | clean | standalone (+cookie/text interceptor libs) | `chapterPageParse` override; vendor 2 lib interceptors |
| **demonicscans** (`mangademon`) | clean | standalone | `chapterPageParse` override; vendor 1-fn `tryParse` |
| **asurascans** | review (benign self-auth + image descramble, own host only) | standalone | `chapterPageParse` override |

Common adaptation for all: swap keiyoushi's `kei.plugins.extension.legacy` gradle plugin for this
fork's convention, point at the in-repo `:lib-stub`/source-api coordinate, and provide the
`keiyoushi.utils` helpers (vendor once, shared).

## Caveats / follow-ups
- **Standalone sources not yet audited** (the ~1,400 non-theme `src/` modules). Most are thin
  theme overrides, but any with their own network/crypto logic need individual review before
  signing — that's the C-scanner's job (codify these checks) + a second triage fan-out.
- Audit reflects the cloned commit (2026-05-25 push). Re-run on every upstream pull you adopt.
