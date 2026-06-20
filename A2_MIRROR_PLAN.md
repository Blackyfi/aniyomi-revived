# A2 — Mirror Plan for Git-Pinned JitPack Dependencies

**Repo:** `aniyomi-revived` (APP repo)
**File under change (LATER — not now):** `gradle/libs.versions.toml`
**Target mirror org:** `Blackyfi`
**Status of this doc:** PLAN ONLY. No repos created, no build files modified.
**Verification date:** 2026-05-29 (via `gh api` against github.com)

---

## TL;DR

All 5 upstream repos still EXIST but are ALL **archived** (read-only, frozen). None of the
five pinned commits is "just a tag in disguise":

- 3 deps have **no tags at all** (injekt, image-decoder) or no *usable* tag (ssiv — its tags
  belong to the original davemorrissey lineage, 199 commits divergent from the tachiyomi fork code we use).
- unifile's pin is an *older* commit than its newest tag `1.0.0` (pin is 7 commits behind 1.0.0).
- flexible-adapter's pin is 9 commits *ahead* of the newest tag `5.1.0` (unreleased code).

**Therefore the recommended action for ALL five is FORK-AND-TAG under Blackyfi**, tagging the
exact pinned commit (so the build is byte-for-byte reproducible). Pinning a pre-existing
upstream tag would silently change the code for every dep except (arguably) none — there is no
dep where an existing upstream tag equals the pinned commit.

> JitPack coordinate mechanics matter here (see per-dep "forking risk"). Two deps have
> non-default coordinate shapes that must be preserved when forking, or the `module = ...`
> string in the catalog will no longer resolve.

---

## Plan Table

| dep | catalog key | upstream owner/repo | current pin (hash) | upstream still alive? | tags available? | proposed mirror coordinate + tag | exact catalog diff |
|-----|-------------|---------------------|--------------------|-----------------------|-----------------|----------------------------------|--------------------|
| unifile | `unifile` (`libs.versions.toml:33`) | `tachiyomiorg/UniFile` | `e0def6b3dc` | YES (archived; pushed 2024-05-05) | YES: `1.0.0`, `0.2.0`, `0.1.4`…`0.1.0`. **But pin `e0def6b3dc` is NOT any tag** — it is 7 commits *behind* `1.0.0` and 2 behind `main` HEAD. | `com.github.Blackyfi:UniFile:v1.0.1-aniyomi` (fork, then tag the **pinned commit** `e0def6b3dc`) | before: `unifile = "com.github.tachiyomiorg:unifile:e0def6b3dc"`<br>after: `unifile = "com.github.Blackyfi:UniFile:v1.0.1-aniyomi"` |
| injekt | `injekt` (`libs.versions.toml:42`) | `mihonapp/injekt` | `91edab2317` | YES (archived; pushed 2024-09-08) | NO tags. Pin `91edab2317` == current `main` HEAD. | `com.github.Blackyfi:injekt:v1.0.0-aniyomi` (fork, tag the pinned commit `91edab2317`) | before: `injekt = "com.github.mihonapp:injekt:91edab2317"`<br>after: `injekt = "com.github.Blackyfi:injekt:v1.0.0-aniyomi"` |
| subsampling-scale-image-view | `subsamplingscaleimageview` (`libs.versions.toml:50`) | `tachiyomiorg/subsampling-scale-image-view` | `66e0db195d` | YES (archived; pushed 2024-11-11) | YES: `v3.4.1`…`v2.2.1`. **But these tags are the davemorrissey upstream lineage — pin is 199 commits *behind* `v3.4.1` (divergent fork branch).** Pin `66e0db195d` == current `main` HEAD of the tachiyomi fork. | `com.github.Blackyfi:subsampling-scale-image-view:v1.0.0-aniyomi` (fork, tag pinned commit `66e0db195d`) — **see RISK: hardcoded groupId** | before: `subsamplingscaleimageview = "com.github.tachiyomiorg:subsampling-scale-image-view:66e0db195d"`<br>after: `subsamplingscaleimageview = "com.github.Blackyfi:subsampling-scale-image-view:v1.0.0-aniyomi"` |
| image-decoder | `image-decoder` (`libs.versions.toml:51`) | `tachiyomiorg/image-decoder` | `41c059e540` | YES (archived; pushed 2024-07-01) | NO tags. Pin `41c059e540` is 1 commit *behind* `main` HEAD (`f11fbd3a`). | `com.github.Blackyfi:image-decoder:v1.0.0-aniyomi` (fork, tag pinned commit `41c059e540`) | before: `image-decoder = "com.github.tachiyomiorg:image-decoder:41c059e540"`<br>after: `image-decoder = "com.github.Blackyfi:image-decoder:v1.0.0-aniyomi"` |
| flexible-adapter | `flexible-adapter-core` (`libs.versions.toml:59`) | `arkon/FlexibleAdapter` | `c8013533` | YES (archived; pushed 2021-10-18) | YES: `5.1.0`, `5.0.x`, `4.x`… **But pin `c8013533` is 9 commits *ahead* of `5.1.0` (unreleased fork code) and 1 behind `master` HEAD.** | `com.github.Blackyfi.FlexibleAdapter:flexible-adapter:v5.1.1-aniyomi` (fork, tag pinned commit `c8013533`) — **see RISK: multi-module + group shape** | before: `flexible-adapter-core = "com.github.arkon.FlexibleAdapter:flexible-adapter:c8013533"`<br>after: `flexible-adapter-core = "com.github.Blackyfi.FlexibleAdapter:flexible-adapter:v5.1.1-aniyomi"` |

> Tag names above (`v1.0.0-aniyomi`, etc.) are SUGGESTIONS — pick any tag string you like.
> The only hard requirement is: **the tag must point at the exact pinned commit listed**, so the
> mirrored build matches today's build bit-for-bit.

---

## Per-dep detail & forking risks

### 1. unifile — `libs.versions.toml:33`
- Coordinate shape: `com.github.<owner>:unifile` → group = `com.github.<owner>`, artifact = `unifile`.
  This is the **whole-repo** JitPack form (artifact name `unifile` is the lowercased repo name;
  the repo is actually `UniFile`). On fork to `Blackyfi/UniFile`, the group becomes
  `com.github.Blackyfi` and the artifact stays `unifile` (JitPack lowercases). Coordinate becomes
  `com.github.Blackyfi:UniFile:<tag>` — JitPack resolves `:UniFile` (repo) and emits artifact `unifile`.
- `library/build.gradle` has **no `publishing{}` / groupId override** → JitPack uses its own
  defaults, so the fork "just works" with no source edits. **LOW RISK.**
- NOTE: do NOT pin existing tag `1.0.0` — it is 7 commits ahead of what we ship today and would change behavior.

### 2. injekt — `libs.versions.toml:42`
- Coordinate shape: `com.github.<owner>:injekt`. Single-module, no tags. Pin == `main` HEAD.
- Fork to `Blackyfi/injekt`, tag `91edab2317`. **LOW RISK.**

### 3. subsampling-scale-image-view — `libs.versions.toml:50` — ⚠️ RISK
- Coordinate shape: `com.github.<owner>:subsampling-scale-image-view` (group = `com.github.<owner>`,
  artifact = `subsampling-scale-image-view`).
- **RISK — hardcoded groupId.** `library/build.gradle.kts` contains an explicit publishing block:
  ```
  publishing { ... groupId = "com.github.tachiyomiorg"; artifactId = "subsampling-scale-image-view" }
  ```
  and `jitpack.yml` runs `:library:publishToMavenLocal`. If you fork WITHOUT editing this, JitPack
  will publish under group `com.github.tachiyomiorg` even though the repo lives at `Blackyfi`, and
  the catalog coordinate `com.github.Blackyfi:...` will FAIL to resolve.
  **Required fix on the fork:** change `groupId = "com.github.tachiyomiorg"` →
  `groupId = "com.github.Blackyfi"` (artifactId stays `subsampling-scale-image-view`), then tag
  `66e0db195d`. This is the one dep where the fork needs a source edit before tagging.
- Do NOT use upstream `v3.4.1` etc. — those tags are 199 commits divergent (original davemorrissey
  lineage, not the tachiyomi fork code we depend on).

### 4. image-decoder — `libs.versions.toml:51`
- Coordinate shape: `com.github.<owner>:image-decoder`. No tags. Pin is 1 behind `main` HEAD.
- This is an Android lib with native (NDK) code; confirm JitPack can build the NDK module (it
  historically does for this repo since the live pin builds today). Fork `Blackyfi/image-decoder`,
  tag `41c059e540`. **LOW–MEDIUM RISK** (native build on JitPack — UNVERIFIED that Blackyfi fork
  builds identically, but upstream config is unchanged so expected OK).

### 5. flexible-adapter — `libs.versions.toml:59` — ⚠️ RISK
- Coordinate shape: `com.github.arkon.FlexibleAdapter:flexible-adapter` — this is the **multi-module**
  JitPack form: group = `com.github.<owner>.<repo>` = `com.github.arkon.FlexibleAdapter`, artifact =
  the submodule `flexible-adapter`. The repo has 5 modules (`flexible-adapter`, `-ui`, `-livedata`,
  `-databinding`, `-app`); we consume only the `flexible-adapter` submodule.
- **RISK — group shape must include repo name.** On fork the coordinate MUST become
  `com.github.Blackyfi.FlexibleAdapter:flexible-adapter:<tag>` (note the dot + `FlexibleAdapter`,
  not `com.github.Blackyfi:flexible-adapter`). Keep the fork repo name exactly `FlexibleAdapter`
  (case-sensitive) so the derived group matches.
- The module's `maven-publish.gradle` sets `publishedGroupId = "eu.davidea"`, but JitPack's
  multi-module resolver overrides the consumer-facing group with `com.github.<owner>.<repo>`, which
  is why the *current* catalog already uses `com.github.arkon.FlexibleAdapter` rather than
  `eu.davidea`. Forking should preserve this behavior with no source edit. **MEDIUM RISK** — verify
  the first JitPack build of the Blackyfi fork resolves `:flexible-adapter` before committing the catalog change.
- Do NOT pin tag `5.1.0` — pin `c8013533` is 9 commits ahead of it (arkon's unreleased fixes).

---

## Suggested execution order (later)
1. Fork the 3 low-risk repos first (unifile, injekt, image-decoder) and tag the exact pinned commits.
2. Fork ssiv, **edit `groupId` to `com.github.Blackyfi`**, then tag `66e0db195d`.
3. Fork FlexibleAdapter (keep repo name exactly `FlexibleAdapter`), tag `c8013533`.
4. Trigger a JitPack build for each (`https://jitpack.io/#Blackyfi/<repo>/<tag>`) and confirm green.
5. Apply the 5 catalog diffs above; run a full Gradle build to confirm resolution.

## Unverified items
- Whether JitPack will successfully build each Blackyfi fork (esp. image-decoder NDK and the
  FlexibleAdapter multi-module group derivation). Marked UNVERIFIED — must confirm via a JitPack
  build before swapping the catalog. All upstream build configs were read and are unchanged, so
  builds are *expected* to succeed.
