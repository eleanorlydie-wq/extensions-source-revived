# Optimization Report — extensions-source-revived

Branch: `optimization/full-pass` (forked off `main`). **Not merged.**
Repo: `eleanorlydie-wq/extensions-source-revived` — fork of `keiyoushi/extensions-source`.

---

## Phase 0 — Baseline (recorded 2026-06-21)

### Toolchain / versions (already current)
| Component | Version | Notes |
|---|---|---|
| Gradle wrapper | **9.5.1** | latest 9.x |
| Kotlin | **2.3.21** | current |
| Android Gradle Plugin | **9.2.1** | current |
| ktlint | **1.8.0** | current |
| spotless | **8.5.0** | current |
| JDK target | **11** (`kei.java`) | CI builds on JDK **17** (temurin) |
| coroutines | 1.10.2 | |
| kotlinx-serialization | 1.7.3 | **intentionally pinned** — 1.8+ `all-compatibility` mode breaks compile-only extension classes (see catalog comment / keiyoushi#12962) |
| okhttp | 5.3.2 | |
| jsoup | 1.22.1 | |

### Repo shape
- **1379** extensions under `src/<lang>/<name>` (22 language dirs).
- **15** `lib/` modules, **65** `lib-multisrc/` modules, plus `core`, `common`.
- Build logic: `gradle/build-logic` included build with 5 convention plugins
  (`PluginAndroidBase`, `PluginLibrary`, `PluginMultiSrc`, `PluginExtensionLegacy`, `PluginSpotless`).
- Two version catalogs: `libs` (`gradle/libs.versions.toml`) and `kei` (`gradle/kei.versions.toml`).

### Build performance settings (gradle.properties) — already optimal
`org.gradle.caching=true`, `org.gradle.configuration-cache=true`,
`org.gradle.configureondemand=true`, `org.gradle.parallel=true`, `org.gradle.jvmargs=-Xmx6144m`,
`kotlin.stdlib.default.dependency=false`. **Nothing to add here.**

### CI (.github/workflows) — already modernized
- `build_pull_request.yml`, `build_push.yml`: matrix chunking (`CI_CHUNK_SIZE=80`),
  `concurrency` + `cancel-in-progress`, path filters excluding `**.md`,
  **all actions SHA-pinned to latest** (checkout v6.0.3, setup-java v5.2.0,
  gradle/actions/setup-gradle v5.0.2, upload/download-artifact v7/v8),
  Gradle caching (`cache-read-only`), `nx-set-shas` for incremental push builds,
  `permissions: {}` least-privilege, `timeout-minutes` on every job.
- `zizmor.yml`: GitHub Actions security scanning on workflow changes.
- `lock.yml`, `issue_moderator.yml`, `codeberg_mirror.yml`: maintenance automation.

### Divergence from upstream
- `main` is **0 commits behind / 10 ahead** of `upstream/main`. All upstream
  modernizations are already merged. The 10 local commits are E-Hentai feature
  work + CI/lib repointing to the eleanor fork (JitPack lib, forked inspector).

### Build environment (this aarch64 box) — verification constraints
- JDK 17 at `~/.gradle/jdks/eclipse_adoptium-17-aarch64-linux.2` (pass `-Dorg.gradle.java.home`).
- **Extension APKs cannot be built locally**: SDK `aapt2` is an x86_64 binary; qemu loader
  missing. **Kotlin/JVM compile and Android-library (AAR) builds work** (verified
  `:lib:randomua:assembleRelease` ✓). Extension APK build/verification happens on x86_64 CI.
- Portable verification gates used in this pass:
  1. `spotlessCheck` (ktlint, JVM) — full-repo lint gate.
  2. Library/`lib-multisrc`/`core` module builds (AAR) — compile verification of shared code.
  3. Per-extension Kotlin compile where aapt2 is not required.

### Green-baseline status
- **Pre-existing FAILURE (not introduced by this pass):** `spotlessCheck` fails on
  `gradle/build-logic/src/main/kotlin/PluginAndroidBase.kt` — unused import
  `org.gradle.kotlin.dsl.dependencies` (line 13). Trivial lint-only fix; scheduled as
  first safe item.
- **Latent gap:** spotless XML target scans `**/*.xml` including untracked `build/`
  output dirs, so a local `spotlessCheck` after a build spuriously fails on generated
  AAPT `merger.xml`. CI is unaffected (clean checkout) but local dev is. Candidate fix:
  exclude `build/` from the spotless XML target.
- `:lib:randomua:assembleRelease` ✓ BUILD SUCCESSFUL.

### Honest assessment
This fork is already **highly modernized and in sync with upstream**. The usual
build/CI/Gradle low-hanging fruit is already harvested. The remaining genuine
optimization surface is: (a) the pre-existing lint failure + spotless config gap,
(b) shared-code dedup/algorithmic wins in `lib`/`lib-multisrc`/`core`/`common`
(high leverage — 1379 extensions depend on them, but **behavior-risk** since APKs
can't be locally verified), (c) safe lint tightening. Dependency freshness is limited
because we already track upstream.

---

## Phase 1 — Parallel analysis findings

Five read-only subagents (A Build/Gradle, B CI/CD, C Deps, D Shared code, E Lint).
Summary: the repo is genuinely well-built; findings are subtle. **One finding (CI F1)
is high-impact and evidence-confirmed.** Most others are Low/Med polish.

### A — Build & Gradle
- **A1** `lib-multisrc/kemono` & `natsuid` pin `okhttp-brotli:5.0.0-alpha.11` while catalog okhttp is 5.3.2 (`randomua` uses 5.3.2). Inconsistent. _Med×S. Note: upstream still uses the alpha → divergence._
- **A2** okhttp version inline in catalog (not a `[versions]` ref); brotli string hand-copied in 3 files. _Low×S._
- **A3** `TYPESAFE_PROJECT_ACCESSORS` preview enabled (settings.gradle.kts:29) but `projects.*` never used. _Low×S. Divergence from upstream._
- **A4** Stale comment in `kei.versions.toml:2` references nonexistent `gradle/build-config/...PluginKotlinMultiplatform.kt`. _Low×S._
- **A5** `PluginAndroidBase.kt:39` uses eager `tasks.getByName("preBuild")` (realized in ~1450 subprojects). Should be `tasks.named`. _Low×S, config-cache idiom._
- **A6** Add `org.gradle.configuration-cache.parallel=true` (root + build-logic gradle.properties) — real wall-time win at ~1450 modules; incubating but safe. _Med×S._
- **A8** Root `buildscript{}` classpath may be redundant — **unverifiable without a build; flagged, not touched.**
- Already optimal (no action): two-catalog split, eachDir settings scan (config-cached), per-module factoring, lazy task APIs elsewhere.

### B — CI/CD
- **B-F1 (HIGH, CONFIRMED)** `permissions: {}` at workflow level in `build_push.yml` denies `actions: read`, so `nrwl/nx-set-shas` 403s and falls back to the empty-tree SHA. **CI-log proof** (run 27914178842): `WARNING: Unable to find a successful workflow run`, `Using provided fallback SHA: 4b825dc…`, then **18/18 chunks built**. Net effect: **every push/dispatch rebuilds all 1379 extensions**; incremental build is fully defeated. Fix: grant `permissions: { contents: read, actions: read }` to the `prepare` job. _High×S._
- **B-F2 (HIGH)** Once F1 is fixed, manual `workflow_dispatch` ("rebuild all") becomes a no-op when `main` is unchanged. Must add a `full_rebuild` dispatch input that feeds the empty-tree SHA to the matrix script. **F1 and F2 must ship together.** _High×M._
- **B-F3** `CORE_FILES_REGEX` in `generate-build-matrices.py` treats all of `.github/scripts/**` as core → full rebuild on publish-only script edits (only `generate-build-matrices.py` actually affects what's built). Narrow it. _Med×S._
- **B-F4** `SIGNING_KEY` interpolated into a `run:` script (other secrets use `env:`). Move to `env:` + `printf …| base64 -d`. _Med×S._
- **B-F5** Publish job checks out `ref: main` (branch tip) not `github.sha` → can publish from a newer commit than the built APKs if a push races. Pin to `github.sha`. _Med×S._
- **B-F6** `Inspector.jar` pulled via `assets[0]` of latest release (order-dependent) + no integrity check. Select by asset name. _Med×S._
- **B-F7** `codeberg_mirror.yml` gated on `keiyoushi/extensions-source` → dead on this fork. Remove or repoint. _Low×S._
- **B-F9** `pip install protobuf` unpinned at publish time. Pin a version. _Low×S._
- **B-F10** issue_moderator messages still link `keiyoushi.github.io` docs (cosmetic). _Low._
- **B-F8** Verify chunk cache restores on chunks >1 (verify-only).
- Confirmed necessary (not redundant): all-artifact download for index, double checkout in publish.

### C — Dependencies
- Catalog is **identical to upstream version-for-version** (only intentional divergence = `tachiyomi-lib` fork coord). **No CVEs**, no unused catalog entries, no harmful duplicates. rxjava 1.3.8 / quickjs 0.9.2 / jspecify / serialization-1.7.3-pin all confirmed required.
- Safe minor bumps *available* but unadopted by upstream too: okhttp 5.3.2→5.4.0, spotless 8.5.0→8.7.0, coroutines 1.10.2→1.11.0. **Recommendation: do NOT bump — stay in sync with upstream; let upstream drive these.**
- No approval-gated bumps recommended. serialization stays at 1.7.3 by design.

### D — Shared code & algorithms (SAFE = byte-identical results)
High-leverage perf wins, all **behavior-preserving** (constant `Regex`/list literals hoisted out of per-item hot paths). Verifiable by lib-multisrc AAR compile on this box.
- **S1** `madara` (645 consumers) `parseChapterDate` recompiles 3 regexes + allocates ~5 `WordSet`s **per chapter**. Hoist to companion vals. _High×S._
- **S2** `madara` `containsIn` rebuilds a lowercased list each call (≤4×/manga). Precompute lowercased `Set`s. _Med×S._
- **S7** `mangathemesia` (306) `parseStatus` rebuilds 4 literal lists per manga. Hoist. _Med×S._
- **S3/S4** `wpcomics` `toDate` (8 lists + regex/chapter) & `hasValidAttr` (regex/image). Hoist. _Med×S._
- **S5** `grouple` recompiles `[\"\']+` regex inside page loop. Hoist. _Med×S._
- **S6** `madtheme` recompiles `/{2,}` regex per chapter. Hoist. _Med×S._
- **S9** `lib/cryptoaes` `Deobfuscator` compiles up to 2 regexes **per digit**. Hoist. _Med×S._
- **S10/S11** `mangabox` `normalizeSearchQuery` (10 regexes/search) & 2 constant desc regexes. Hoist. _Low×S._
- **S8** `mangathemesia` `parseUpdatedOnDate` allocates a `SimpleDateFormat` per call → hoist to a **private** val (keep unshared; see R2). _Low×S._
- **FLAGGED (behavior/thread risk — NOT auto-applied):** R1 shared mutable `SimpleDateFormat` in per-chapter `.map` across 8 bases (thread-safety); R3 unguarded `!!` on parsed content; R4 duplicated date-parse helpers (consolidation could shift word-matching). False positive cleared: MangaBox uses `Locale.ROOT`.

### E — Lint & style
- **E1** Add `targetExclude("**/build/**")` to spotless format blocks in `PluginSpotless.kt` — defensive against the local `build/`-XML flakiness I hit during baseline. _Low×S, behavior-safe (only narrows what's formatted)._
- **E2** `.editorconfig:21` `ktlint_standard_chain-method-continuation = disable` is an invalid value (siblings use `disabled`) → rule likely still enforced contrary to intent. Fix typo. _Med×S._
- **E3** `.git-blame-ignore-revs` lists upstream SHAs that are `bad object` in this fork → dead. Repoint to our formatting commit (`474f37095d`) or remove. _Low×S._
- **E4/E5** (discuss): `max_line_length=140` is build-overridden to unlimited (IDE-advisory only) — add a clarifying comment; optionally make `PluginSpotless` set editorconfig path explicitly.
- Confirmed good: only 1 ktlint suppression in the whole tree (legitimate), 0 `ktlint-disable`, configs internally consistent, no unsafe rules to enable.

---

## Phase 2 — Consolidated backlog (ordered Impact desc, Effort asc)

**Verification gates per batch:** `spotlessCheck` (full, JVM) after every batch; AAR
compile of each touched `lib`/`lib-multisrc`/`core` module for code batches. Extension
APKs verified on x86_64 CI (can't build locally — see baseline).

### Batch 0 — Baseline green (build-logic) — SAFE
- Remove unused import in `PluginAndroidBase.kt` (fixes pre-existing `spotlessCheck` failure). [commit: fix lint]
- A5: `tasks.getByName` → `tasks.named` (same file). [separate commit: build]

### Batch 1 — CI correctness & speed — SAFE, HIGH impact
- **F1 + F2 together**: grant `actions:read`+`contents:read` to prepare; add `full_rebuild` dispatch input. (Restores incremental builds; preserves manual full rebuild.)
- F3 narrow `CORE_FILES_REGEX`; F5 publish `ref: github.sha`; F4 `SIGNING_KEY` via env; F6 Inspector.jar by name; F9 pin protobuf; F7 remove dead codeberg mirror.

### Batch 2 — Gradle perf & hygiene — SAFE
- A6 `configuration-cache.parallel=true`; A4 fix stale comment; A2 centralize okhttp version. (A1/A3 = optional, see decision below.)

### Batch 3 — Lint config — SAFE (config-only, no code reformat)
- E1 spotless `targetExclude` build; E2 fix `disable`→`disabled`; E3 fix blame-ignore-revs; E4 clarifying comment.

### Batch 4 — Shared-code perf (regex/list hoisting) — SAFE, byte-identical
- S1, S2 (madara) → S7 (mangathemesia) → S3, S4 (wpcomics) → S5 (grouple), S6 (madtheme), S9 (cryptoaes), S10, S11 (mangabox), S8. Each verified by the module's AAR compile.

### FLAGGED — need explicit approval (NOT auto-applied)
- Dependency minor bumps (recommend SKIP, stay upstream-synced).
- D-R1 (SimpleDateFormat thread-safety), R3 (`!!` hardening), R4 (date-helper consolidation) — behavior/robustness risk.
- A8 (root buildscript classpath) — unverifiable locally.
- A1/A3 (okhttp-brotli realign, TYPESAFE removal) — diverge from upstream; judgment call.

---

## Phase 3 — Execution log

Decisions from user checkpoint: proceed with all safe batches 0–4; treat the fork
as **fully independent** of upstream (apply safe divergent items on their merits).
Pure dependency-minor bumps were skipped as churn with no measurable win.

### Batch 0 — Baseline green ✅
- `build: drop unused imports in PluginAndroidBase convention plugin` — removed 3
  unused imports (`CompileOptions`, `extensions.libs`, `dsl.dependencies`) that caused
  the pre-existing `spotlessCheck` failure. Verified `:build-logic:spotlessKotlinCheck` green.
- `build: use lazy tasks.named for preBuild dependency` (A5) — avoids eager realization
  in ~1450 subprojects. Verified `:lib:randomua:assembleRelease` green.

### Batch 1 — CI ✅
- `ci: restore incremental builds and add full-rebuild dispatch` (F1+F2) — the headline
  fix. Grants prepare job `contents:read`+`actions:read` (so nx-set-shas stops falling
  back to empty-tree and rebuilding all 1379 extensions every push) + adds a
  `full_rebuild` workflow_dispatch input. **Expected impact: pushes now rebuild only
  changed modules instead of all 18 chunks.** YAML validated.
- `ci: only treat the matrix script as build-affecting in core-files check` (F3).
- `ci: harden publish job (signing key, publish SHA, inspector, protobuf)` (F4+F5+F6+F9)
  — note: protobuf pinned `>=7.35,<8` after verifying `index_pb2.py` is generated for
  7.35.0 (a naive `<7` pin would have broken publish — caught during verification).
- `ci: select inspector jar by suffix in PR check too` (F6, PR workflow) — selects the
  versioned asset (`Tachiyomi.Extensions.Inspector-vX.jar`) by `.jar` suffix.
- `ci: remove dead Codeberg mirror workflow` (F7).
- Skipped: F10 (cosmetic doc links — keiyoushi troubleshooting docs are still canonical).

### Batch 2 — Gradle perf & hygiene ✅
- `build: enable parallel configuration cache` (A6) — root + build-logic. Verified
  builds + config-cache store/reuse.
- `build: fix stale reference comment in kei.versions.toml` (A4).
- `build: align okhttp-brotli to 5.3.2` (A1) — 6 modules off a 3-year-old alpha;
  compile-only, behavior-preserving; verified 5.3.2 resolves + multisrc modules compile.
- `build: drop unused TYPESAFE_PROJECT_ACCESSORS feature preview` (A3).
- Skipped: A2 catalog rewire (diverges from repo's string-literal brotli convention for
  marginal value); A8 root buildscript classpath (unverifiable locally — flagged).

### Batch 3 — Lint config (in progress; full spotlessCheck gate running)
- E1 `targetExclude("**/build/**")` on all 4 spotless format blocks — confirmed a REAL
  fix (extension `build/` output lives under `src/<lang>/<ext>/build/`, which the
  `src/**` globs matched — this is what broke the local baseline run).
- E2 `.editorconfig` `chain-method-continuation = disable` → `disabled` (invalid value → typo).
- E3 removed dead `.git-blame-ignore-revs` (only upstream SHAs, `bad object` here; our
  one "formatting" commit isn't clean enough to repoint to).

### Batch 4 — Shared-code perf ✅
SAFE regex/`WordSet`/`List` hoists (all immutable → thread-safe, byte-identical),
one atomic `perf(...)` commit per module. All 7 modules verified:
`compileReleaseKotlin` ✅ + `spotlessKotlinCheck` ✅.
- `perf(cryptoaes)` (S9), `perf(madtheme)` (S6), `perf(grouple)` (S5),
  `perf(wpcomics)` (S3/S4), `perf(mangabox)` (S10/S11), `perf(mangathemesia)` (S7),
  `perf(madara)` (S1).
- **S2 skipped** — madara `containsIn` uses `this.lowercase() in array.map{it.lowercase()}`,
  which is locale-dependent; rewriting to `equals(ignoreCase=true)` would change
  edge-case behavior (e.g. Turkish i/I). Not worth the risk for a tiny win.
- **S8 skipped** — hoisting mangathemesia's `SimpleDateFormat` would introduce shared
  mutable state (thread-safety risk, D's own R2 caveat) for a once-per-manga allocation.

---

## Phase 4 — Verification, summary & handoff

**Final gate: ✅ `./gradlew --no-build-cache --no-configuration-cache spotlessCheck` →
BUILD SUCCESSFUL.** Plus: all 7 touched shared-code modules `compileReleaseKotlin` ✅,
`:build-logic` compiles ✅, library AAR builds ✅. Extension **APKs** can't be built on
this aarch64 box (aapt2) — they build/verify on x86_64 CI at merge time.

### Baseline vs after
| Item | Before | After |
|---|---|---|
| Pre-existing `spotlessCheck` failure (unused imports) | RED | fixed ✅ |
| CI incremental builds | **broken** — every push rebuilt all 1379 exts (empty-tree fallback) | restored (prepare job gets `actions:read`) + manual `full_rebuild` input |
| CI publish-script edits | full rebuild of all exts | rebuild nothing (regex narrowed) |
| Publish hardening | secret in run-script; `ref: main` race; `assets[0]`; unpinned protobuf | env+printf; `ref: github.sha`; jar-by-suffix; `protobuf>=7.35,<8` |
| Dead workflow | `codeberg_mirror.yml` (never runs on fork) | removed |
| Gradle config cache | serial | `configuration-cache.parallel=true` |
| Eager task realization | `tasks.getByName("preBuild")` ×~1450 | lazy `tasks.named` |
| okhttp-brotli | 6 modules on 3-yr-old `5.0.0-alpha.11` | aligned to `5.3.2` (compile-only, behavior-preserving) |
| Dead/stale config | TYPESAFE preview, stale kei comment, bad blame-revs | removed/fixed |
| spotless local flakiness | `src/**` globs matched `build/` output | `targetExclude("**/build/**")` |
| Shared-code hot paths | regexes/lists rebuilt per chapter/search | hoisted to companion (7 high-traffic bases) |

### Commit summary
21 atomic commits on `optimization/full-pass`: 2 baseline-green (build-logic),
6 CI, 6 Gradle/build hygiene, 3 lint-config, 7 shared-code `perf`. No mass reformat;
formatting/config separated from logic.

### Dependency deltas
None bumped. Catalog already current. Safe minors available (okhttp 5.4.0, spotless
8.7.0, coroutines 1.11.0) were **skipped** as no-measurable-win churn. serialization
stays pinned at 1.7.3 by design.

### Shared-code algorithmic notes (allocation/CPU per call)
- madara `parseChapterDate`/`parseRelativeDate`: −4 regex compilations & −~12 `WordSet`
  allocations **per chapter** (×645 consumers).
- mangathemesia `parseStatus`: −4 list allocations per manga (×306).
- wpcomics `toDate`/`hasValidAttr`: −8 list allocs & −1 regex/chapter, −1 regex/image.
- mangabox `normalizeSearchQuery`: −10 regex compilations per search; +2 const desc regexes.
- grouple: −1 Pattern/chapter, −1 regex/page. madtheme: −1 regex/chapter.
- cryptoaes: −2 regex compilations **per decoded digit**.

### FLAGGED for your manual review (NOT applied)
- **Behavior-risk (D's R-list):** R1 shared mutable `SimpleDateFormat` in per-chapter
  `.map` across ~8 bases (thread-safety); R3 unguarded `!!` on parsed content; R4
  consolidating duplicated date-parse helpers into one shared `lib/` helper.
- **A8:** root `buildscript {}` classpath may be redundant — unverifiable locally.
- **Dep minors** (okhttp/spotless/coroutines) — available if you want them.
- **Pre-existing spotless/build-cache flakiness (NOT mine, NOT A6):** a full-tree
  `spotlessCheck` fails non-deterministically with `Could not read path
  build/spotless-{lints,clean}/spotless<Format>` on a *different, untouched* module each
  run (coffeemanga, heytoon, magusmanga, toomics…) — never a format violation. Root
  cause: spotless's per-format tasks get restored **FROM the Gradle build cache**
  without their `build/spotless-lints` side-files, so the lints aggregation can't read
  them. Proven independent of my work and of A6:
  - reproduces with parallel config cache **off** (`-D…configuration-cache.parallel=false`),
  - reproduces with `--no-parallel`,
  - **passes green** with `--no-build-cache --no-configuration-cache` (EXIT=0).
  **Reliable gate used to certify this pass: `./gradlew --no-build-cache
  --no-configuration-cache spotlessCheck` → BUILD SUCCESSFUL.** A6 kept (innocent).
  _Optional future fix: bump the spotless plugin (a known caching bug in this line)._

### ⚠️ Working-tree incident (FYI)
A concurrent session switched this shared working tree from `optimization/full-pass`
to `fix/coomer-image-fallback` mid-run (between Batch 3 and Batch 4), so my 7 `perf`
commits initially landed on `fix/coomer-image-fallback`; that session then committed
`7287802d75` (coomer thumbnail fallback) on top. Recovered non-destructively:
`optimization/full-pass` force-set to my tip (`5f55685571`, all 21 commits, verified
the coomer commit is NOT an ancestor); safety tag `_recovery_coomer_tip` created.
`fix/coomer-image-fallback` left untouched (it carries my 7 perf commits + their coomer
commit — harmless, same SHAs; you may want to rebase it off `main` later).

### Suggested PR description
> **Optimize build, CI, and shared-code hot paths (behavior-preserving)**
>
> Repo was already modern; this is targeted polish + one real CI fix.
> - **CI:** restore incremental builds (nx-set-shas was 403ing → full rebuild every
>   push) + manual `full_rebuild` dispatch; narrow core-files trigger; harden publish
>   (signing key, build-SHA pin, inspector-by-suffix, protobuf pin); drop dead mirror.
> - **Gradle:** parallel config cache; lazy `preBuild`; align okhttp-brotli to 5.3.2;
>   drop dead preview/comment.
> - **Lint:** exclude `build/` from spotless; fix invalid ktlint value; drop dead blame-revs.
> - **Perf (shared bases, byte-identical):** hoist constant regexes/word-lists out of
>   per-chapter/per-search hot paths in madara, mangathemesia, wpcomics, mangabox,
>   grouple, madtheme, cryptoaes.
>
> No extension behavior change. Dependency majors untouched. See OPTIMIZATION_REPORT.md.
