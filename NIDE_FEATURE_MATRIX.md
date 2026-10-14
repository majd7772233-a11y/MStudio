# MStudio-NIDE — Feature Mining Matrix

> NIDE is a source for candidate features, not MStudio's main build engine.
>
> Reference: https://github.com/majd7772233-a11y/MStudio-NIDE
>
> Inspected baseline: fddee3ec2b0de9501b0d01bac4afeb86146d0c42 from 2018-06-15. Re-check live source before each port.

## 1. Rules for candidate features

A candidate requires a reproducible user-visible gap. Inspect exact NIDE source, callers, dependencies, tests, licensing/notices and bundled binaries; locate an equivalent MStudio function; define the smallest integration boundary; add tests; run CI/device validation; record commit and limitations. A class or README checkbox isn't proof of a finished feature.

Statuses must be explicit: Candidate, Investigating, Approved, Ported, Verified or Rejected. Don't merge NIDE wholesale.

## 2. NIDE version facts

The inspected 2018 NIDE uses Gradle Wrapper 4.4, AGP 3.1.3, compile SDK 27 and Build Tools 27.0.3. Main app config had application ID com.duy.compiler.javanide, min SDK 14, target SDK27, version 1.4.5/code45. Legacy compiler depends on bundled AOSP components, old bouncycastle/dx/jdk-1_7, ECJ4.6.1, KXML, old Guava/Commons libraries and Android support libraries. These are not MStudio version recommendations.

## 3. Candidate inventory

| Candidate | NIDE source | Compare against MStudio | Current disposition |
|---|---|---|---|
| Java formatter styles | app/src/main/java/com/duy/ide/javaide/editor/format/JavaFormatter.java and lib-google-java-format | MStudio already builds google-java-format under composite-builds/build-deps/google-java-format; trace toolbar/settings/wiring/tests. | Investigate only if gap proven. |
| Java autocomplete | app/src/main/java/com/duy/ide/javaide/editor/autocomplete/JavaAutoCompleteProvider.java and adjacent internal/parser packages | MStudio has Java LSP, javac services, editor/indexing and symbol index. | Behavior/test reference; don't create a competing engine without proof. |
| Type/member resolution | NIDE JavaParser, JavaClassManager, TypeResolver, CompiledClassLoader and JavaDexClassLoader under autocomplete/parser | MStudio uses synced project classpaths and LSP/indexing. | Don't port wholesale; turn useful edge cases into tests. |
| Formatter tests | app/src/test/java/com/duy/ide/javaide/editor/format/FormatFactoryTest.java and google-java-format tests | Audit existing MStudio formatter tests first. | Possible behavior test cases after license review. |
| Standalone Java/JAR workflow | lib-android-compiler project/JavaProject.java and JavaProjectManager.java | Existing Java module support doesn't prove standalone console/JAR UX exists. | Possible separate feature; define runtime/library semantics first. |
| ECJ compilation | lib-android-compiler builder/task/java/CompileJavaTask.java | Android app build belongs to Gradle/AGP. | Never place in main Android build; consider separate Java mode only. |
| Legacy dx | lib-android-compiler builder/task/java/DexTask.java and root dx | Gradle/AGP handles standard Android DEX. | Do not port to main Gradle build. |
| Custom Android builder | lib-android-compiler builder/AndroidAppBuilder.java | MStudio has real Gradle Tooling API. | Reject as replacement; only reuse unrelated tested helper/UX. |
| AAR extraction | lib-android-compiler library/LibraryCache.java | Gradle resolves dependency graph/metadata. | Not a dependency resolver; consider only isolated inspection if needed. |
| Decompiler | lib-decompiler | Full equivalent workflow not confirmed in MStudio source audit. | Good research candidate; inspect API/formats/dependencies/license/memory. |
| Logcat | androidlogcat | MStudio has logging modules and README describes live app logs. | Compare existing behavior and extend it if possible. |
| Educational samples | app/src/main/assets/sample | Compare with MStudio templates/tests. | Potential low-risk source after attribution/license/package cleanup and build test. |
| Migration helpers | JavaProjectManager.java and AndroidProjectManager.java | MStudio has Gradle-aware project sync/import. | Reference only if a specific gap is proven; beware destructive moves. |
| Fast Gradle parser | aosp/gradle/src/test/java/com/duy/android/compiler/builder/parser/FastGradleParser.java | Gradle Tooling API supplies real project semantics. | Reject as source of truth; regex-based and under test source. |

## 4. Why NIDE build code is not a Gradle core

### Templates are not executed Gradle builds

lib-android-compiler/src/main/assets/templates/app/build_gradle.template and build.gradle.root explicitly warn that NIDE itself does not support Gradle-based builds and editing these templates does not affect its builder.

FastGradleParser.java lives under aosp/gradle/src/test and regex-parses only literals such as compileSdkVersion, buildToolsVersion, applicationId, minSdkVersion, targetSdkVersion, versionCode and versionName. It cannot evaluate arbitrary Groovy/Kotlin DSL, plugins, variants or dependency graphs.

### APK building uses a fixed custom task chain

AndroidAppBuilder.java runs a fixed sequence: CleanTask, GenerateBuildConfigTask, ProcessAndroidResourceTask, CompileJavaTask, DexTask, PackageApkTask and SignApkTask. It doesn't execute the project's Gradle wrapper or Tooling API.

### Incomplete source tasks

- CompileAidlTask.doFullTaskAction returns true after TODO.
- MergeAssetTask.doFullTaskAction returns true after TODO.
- MergeManifestTask.doFullTaskAction prints “Not impalement yet” and returns true.
- SignApkTask.signRelease is TODO.
- CompileJavaTask uses ECJ 4.6.1, Java 1.7 defaults and -proc:none.
- LibraryCache manually unpacks AAR and doesn't replace Gradle dependency resolution/metadata.

These are reasons not to use NIDE as a modern Android build engine.

## 5. Append-only port record template

### Feature — status: Candidate / Investigating / Approved / Ported / Verified / Rejected

- User problem and acceptance condition:
- Exact NIDE source:
- Existing MStudio counterpart:
- Reproduction showing gap:
- Decision/reason:
- Minimal target module/boundary:
- Dependency/license review:
- Tests, CI and device results:
- Commit SHA:
- Known limits/follow-up:

Only mark Verified after the feature runs on a supported MStudio build with evidence.


## Phase 9 live audit update — 2026-10-13

| Candidate | Live MStudio counterpart / gap | Decision | Evidence and next gate |
|---|---|---|---|
| Java formatter styles | `java/lsp/.../JavaServerSettings.java` already exposes AOSP and Google styles; `CodeFormatProvider.java` invokes Google Java Format. | **Rejected as a duplicate port for now.** | Re-open only if a concrete formatter behavior unsupported by the current implementation is reproduced and can be tested. |
| Java autocomplete/type resolution | Java LSP, javac services, project classpath and indexing already exist. | **Reference-only.** | Use NIDE's `JavaAutoCompleteProvider`, `TypeResolver` and related code only to derive specific regression tests; do not add a parallel engine without a demonstrated gap. |
| Fernflower decompiler | No decompiler implementation was found in the live MStudio source-tree filename audit; this is a potentially unique Java/JAR inspection feature. | **Investigating — blocked before import.** | NIDE source headers identify Fernflower files as Apache 2.0, but the inspected NIDE repository root license is GPL-3.0 and the `lib-decompiler` module has no separate Apache license file in its directory listing. Its Gradle module targets Java 7 and has a broad legacy source tree. Recover and verify exact license provenance, dependencies, notices, API boundary and memory behavior before any port. |
| NIDE Android builder, dx and FastGradleParser | MStudio's authoritative build path is Gradle/AGP through the Tooling API. | **Rejected for the core build path.** | Never replace Tooling API/AGP semantics with the old custom compiler or regex parser. |

No Phase 9 code has been imported. This audit is not a completed Phase 9; an approved candidate must have a reproducible gap, exact source, minimal boundary, dependency/license audit, regression tests and runtime evidence before being marked Ported or Verified.


## Phase 9 implementation update — 2026-10-13

| Candidate | Final disposition for this phase | Implementation/evidence |
|---|---|---|
| Fernflower / Java decompiler | **Ported at source level using maintained Vineflower fork.** | Added an offline Java class/JAR/ZIP decompiler entry to MStudio's main screen, source preview dialog, SAF source export, bounded input copy (128 MiB), private output directory, and explicit APK/DEX rejection. Dependency is centralized in the version catalog as org.vineflower:vineflower:1.12.0. See THIRD_PARTY_NOTICES.md. |
| Decompiler tests | **Authored; not executed.** | Added policy tests for accepted/rejected extensions and input-size bounds, plus a JVM integration test that compiles a tiny class and expects decompiled Java output. These are source tests only until the final verification phase. |
| Java formatter styles | **Rejected as duplicate.** | Existing Google Java Format integration already supports AOSP and Google style settings. |
| Java autocomplete/type resolution | **Reference-only.** | Existing Java LSP, javac services, classpath and indexing remain authoritative. No parallel completion engine was added. |
| Legacy Android builder, dx, FastGradleParser, manual AAR resolver | **Rejected for core build.** | Gradle/AGP Tooling API remains the single Android build path. |
| Logcat | **No new port.** | Existing logging and app-log surfaces exist; no unique reproducible gap was established in this pass. |
| Educational sample corpus | **Deferred, not silently marked complete.** | A safe curated sample set requires inventorying existing templates and sample coverage; no unreviewed bulk copy was added. |
| Standalone Java/JAR execution | **Deferred.** | Needs a separately specified runtime/process model and sandboxing; it is not folded into the Android Gradle build path. |
| Migration helpers | **Reference-only.** | Existing project import/sync remains authoritative; no destructive NIDE file-moving code was imported. |

**Phase 9 source boundary:** unique decompiler source integration is implemented, but runtime/build/device acceptance remains pending the final verification stage. Vineflower is a maintained Fernflower fork (Apache 2.0); MStudio does not copy NIDE's older module wholesale. The current UI handles JVM class/JAR/ZIP only, not APK/DEX. Do not mark runtime verified until the integration test, APK build, and device smoke test run successfully.


## Phase 9 implementation update — 2026-10-13

| Candidate | Final disposition for this phase | Implementation/evidence |
|---|---|---|
| Fernflower / Java decompiler | **Ported at source level using maintained Vineflower fork.** | Added offline Java class/JAR/ZIP decompiler entry, source preview, SAF export, bounded input copy (128 MiB), private output directory, and explicit APK/DEX rejection. Dependency is centralized as org.vineflower:vineflower:1.12.0. See THIRD_PARTY_NOTICES.md. |
| Decompiler tests | **Authored; not executed.** | Added policy tests for extension/size validation and a JVM integration test compiling a class and checking decompiled output. |
| Java formatter styles | **Rejected as duplicate.** | Existing Google Java Format supports AOSP and Google styles. |
| Java autocomplete/type resolution | **Reference-only.** | Existing Java LSP, javac services, classpath and indexing remain authoritative. |
| Legacy Android builder, dx, FastGradleParser, manual AAR resolver | **Rejected for core build.** | Gradle/AGP Tooling API remains the sole Android build path. |
| Logcat | **No new port.** | Existing logging surfaces exist; no unique gap was established. |
| Educational samples | **Deferred.** | Curate only after inventorying existing template/sample coverage. |
| Standalone Java/JAR execution | **Deferred.** | Requires separate runtime/process and sandbox scope; not folded into Android builds. |
| Migration helpers | **Reference-only.** | No destructive NIDE project-moving code imported. |

**Phase 9 source boundary:** source implementation is complete for the selected port and candidate disposition audit; runtime/build/device acceptance remains pending final verification. Vineflower is a maintained Fernflower fork (Apache 2.0); NIDE's older module was not copied wholesale. Current UI supports JVM class/JAR/ZIP only, not APK/DEX.


### Phase 9 implementation detail correction

Vineflower is not executed inside Android's ART process. The app build stages the single non-transitive Vineflower JAR into generated APK assets; MStudio copies it into its private runtime directory and invokes it using the already-managed Java executable. The child JVM is capped at 384 MiB heap and two active processors, has a three-minute timeout, drains process output, and is forcibly terminated on interruption/timeout. Inputs are capped at 128 MiB; JAR/ZIP archives are preflight-limited to 5,000 entries and 256 MiB declared expanded size. These are source safeguards, not a substitute for device profiling.


## Phase 10 integration note — 2026-10-13

The Phase 9 decompiler boundary was hardened while preparing Phase 10 verification: archive preflight rejects unknown/negative and overflow-sized entry lengths; generated output is monitored with a 10,000-file / 256 MiB cap; policy tests cover hostile archive sizes and output ceilings. The PackageInstaller callback receiver is now private and receives the result through an explicit PendingIntent. These changes are **source-level only** until the manual verification workflow and device tests run.

Prepared, not dispatched: `.github/workflows/phase10-verification.yml` (static checks, JVM tests, Tooling API tests/build, debug APK and checksums) and `.github/workflows/phase10-codeql.yml` (Java/Kotlin CodeQL). No test, build, scan, APK install or device validation has been run. Do not mark Phase 9 runtime acceptance or Phase 10 acceptance as verified based on workflow definitions alone.


Phase 10 runbook: see PHASE10_VERIFICATION.md for manual workflow order, device matrix, evidence requirements, and the current unverified status.


## CI failure investigation — 2026-10-14

Reviewed GitHub Actions runs on `dev` at `a394dce49dad2fd90c2df4c43f87d444692be661`:
- Verification gate run: https://github.com/majd7772233-a11y/MStudio/actions/runs/38542138006
- CodeQL run: https://github.com/majd7772233-a11y/MStudio/actions/runs/38542775833

The logs showed four primary source/build blockers and one independent build-environment collision:
1. `RootModelBuilder.kt` imported `dev.mutwakil.androidide.project.SyncIssue` as a top-level type, but protobuf generates it as `GradleModels.SyncIssue`.
2. `ToolsManager.java` declared a local variable named `java`, shadowing the package prefix in `java.util.regex.Pattern`; Javac therefore resolved `util` as a missing field on `File`.
3. Turkish `msg_install_tools` contained unescaped apostrophes; AAPT2 failed while flattening the resource.
4. The app asset-copy task expected `tooling-api-all.jar` even when tooling compilation had failed. The tooling `copyJar` task also lacked an explicit dependency on `shadowJar`, making the output ordering fragile.
5. AGP debug-keystore creation failed with `NoSuchFieldError: BCObjectIdentifiers.xmss_SHAKE128_512ph`, consistent with mismatched Bouncy Castle classes visible to the build. The Nyx settings classpath now excludes its transitive Bouncy Castle group to prevent it shadowing AGP's matching signing dependencies.

Fixes committed on `dev`:
- `759e632`: use the generated nested protobuf SyncIssue type.
- `3849b16`: use an imported `Pattern` instead of a package reference shadowed by the `java` variable.
- `82f0470`: escape apostrophes in Turkish setup text.
- `9d34d0e`: make `copyJar` depend explicitly on `shadowJar`.
- `a2b6ed2`: isolate Nyx's transitive Bouncy Castle dependencies from AGP.
- `bdfc189` and `2907ef5`: trigger Phase 10 verification and CodeQL on relevant `dev` source pushes as well as manual dispatch.

The initial CodeQL failure was a build/configuration failure, not a completed clean security analysis. The failed test run produced no test reports because compilation stopped before tests could execute. The fixes are not considered verified until the new Actions runs finish and their logs/artifacts are reviewed.


## Phase 10 CI failure follow-up (2026-10-14)

Three Kotlin compile blockers from GitHub Actions were fixed on `dev`: protobuf SyncIssue construction now uses the generated Java builder in `RootModelBuilder.kt`; the missing `PROJECT_NAME` enum import was added in template parameters; and both invalid `module.delegate.projectDir` references now use `module.projectDir`. Static safety/manifest checks passed, but unit tests did not run and APK/CodeQL jobs were blocked by compilation. Acceptance remains pending until fresh verification and CodeQL runs pass on the final corrected commit.
