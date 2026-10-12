# MStudio — Comprehensive Source-Grounded Roadmap

> Planning grounded in inspected code, not a claim that items are already implemented.
>
> Initial source baseline: `e4dc15adf1c670edcfce12008e822c02b0d9cb64` on `dev`. Re-check live HEAD and actual files before every implementation task.

## 0. Product definition and operating principles

MStudio aims to become a self-contained Android IDE for real Android development on a phone. MStudio must manage its own runtime, JDK, Android SDK tools, Gradle integration, project model, editor and previews. Beginners use a guided interface; terminal is optional for advanced users. Local build must remain available without GitHub login.

Preserve the actual Gradle Tooling API path in `tooling/api`, `tooling/impl`, `GradleBuildService` and `ToolingServerRunner`. NIDE is a selective feature source, not the build foundation. Never merge all of NIDE or substitute its custom APK builder/regex parser for Gradle.

Only mark an item complete when a commit and reproducible evidence exists: automated test, CI job/artifact or named device test. A class name, README checkbox or workflow definition is not proof.

## 1. Source evidence register

| Source | Fact observed at baseline | Roadmap consequence |
|---|---|---|
| `gradle/wrapper/gradle-wrapper.properties` | Gradle 8.14.4 for root build | Prove inherited build before changing it. |
| `.github/workflows/build.yml` | JDK 21, recursive checkout, debug/release `core/app` builds, ARM64/ARMv7 artifacts, Tooling API test and publish jobs | Gate publication before testing fork. |
| `.github/workflows/tooling-api.yml` | Manual Tooling API JAR build using JDK 17 | Separate evidence from full APK build. |
| `gradle/libs.versions.toml` | AGP 8.13.2, AGP tooling 9.3.1, Gradle tooling 9.7.0, Kotlin 2.3.21, editor 0.23.7 at inspection | Read current catalog before updates. |
| `BuildConfig.kt` | Package `dev.mutwakil.androidide`, compile SDK 36, min SDK 26, target SDK 28, Java 17, declared NDK version | Keep identity/API/Java decisions separate. |
| `Environment.java` | Private runtime directories for JVM/SDK/Gradle home/tooling JAR/AAPT2 | Test setup and repair, preserve projects. |
| `ToolsManager.java` | Extracts IDE plugin, AAPT2, tooling runtime, android.jar, schemes and scripts | Validate each extraction, not callback only. |
| `GradleBuildService.kt` | Actual Tooling API service, wrapper repair, AAPT2 override, progress/output/cancel | Preserve and test full lifecycle. |
| `ToolingServerRunner.kt` | Starts managed Java process with tooling JAR and protocol streams | Test startup, stderr, exit and cancellation. |
| `AndroidProjectModelBuilder.kt` | Android models/DSL/variants/dependencies; flavor-related TODO in classes JAR lookup | Add targeted variant tests. |
| `ProjectValidation.kt` | Common detection looks for an `app` directory with Gradle script | Test non-app module names/multi-module layouts. |
| `OnboardingActivity.kt` | Existing first-run permission/device/tool setup | Improve actual path; don't bypass. |
| `ApkInstaller.kt` | PackageInstaller session and intent fallback | APK success must include install/run evidence. |
| `SetupAapt2Task.kt` | Custom ARM64/ARMv7 AAPT2 hashes; x86_64 commented | Test resource build on each supported ABI. |
| NIDE Gradle templates | Explicitly say NIDE itself does not support Gradle-based builds | NIDE is not a replacement build engine. |

## 2. Phase 0 — lock baseline, disable publication, prove APK

### 0.1 Reconfirm source state
Fetch live `dev` HEAD and record SHA, date, subject, wrapper, version catalog and submodule state. Inspect complete current `settings.gradle.kts`, root `build.gradle.kts`, `gradle.properties`, wrapper properties, version catalog, daemon JVM config, `.gitmodules`, all workflow YAML, `core/app/build.gradle.kts` and main manifest. Check recursive submodule checkout; `.gitmodules` includes `composite-builds/external/logback-android`.

**Acceptance:** findings cite current SHA, not the old baseline.

### 0.2 Gate publishing before any routine CI
The inspected `build.yml` has `build_apk` and Tooling API tests, but also `publish` and `publish_snapshots`. The snapshot condition allows successful non-main/non-PR runs, potentially including a dev push. Review all publish/release job conditions, Nyx tasks and Maven credential references. Add a deliberate opt-in false by default for publishing, or separate normal build/test from release. Confirm default push/dispatch can build and upload artifacts only. Preserve upstream publishing capability behind intentional owner-controlled settings; don't delete it without documentation.

Markdown paths are ignored by main `build.yml`, so after the guard is committed deliberately dispatch or run the workflow safely. Never test by accidentally publishing to Maven Central.

**Acceptance:** default CI cannot release/publish, even if publishing credentials exist.

### 0.3 Audit signing material
Inspect tracked `core/app/dev.keystore`, `core/app/build.gradle.kts`, `gradle.properties`, `.nyx.yml` and all signing consumers. Determine what is dev-only. Debug builds must work without public release credentials. Release signing must use owner-controlled secrets, not values committed into source. Fail closed when deliberate release keys are missing; do not ship the inherited keystore as MStudio release identity.

**Acceptance:** safe debug path and a separate, deliberate release identity.

### 0.4 Baseline CI
After publish gating, run the existing primary workflow: recursive checkout, JDK 21, chmod gradlew, restore cache, build `core/app` debug and release, upload ARM64/ARMv7 artifacts and run Tooling API tests with `ANDROIDIDE_TEST=true` and `TEST_TOOLING_API_IMPL=true`. The separate tooling-api workflow only builds `tooling:impl:jar` on JDK 17; don't count it as full APK evidence.

Record run URL, job IDs, first causal failure, commit SHA, artifact names/ABI and submodule status. Fix earliest causal failure before unrelated updates.

**Acceptance:** exact branch build result understood; APK artifacts and Tooling API tests separately recorded.

### 0.5 Device smoke test
Download artifact for a named device ABI. Test installation, app startup, onboarding, permission-denial path, setup status, main screen, project create/open, editor and terminal entry, crash-free navigation. Record Android API, ABI, artifact SHA if available and logs. APK file existence is not install/run success.

**Gate:** no major rebrand, NIDE merge or UI rewrite before safe CI and baseline artifact evaluation.

## 3. Phase 1 — rebrand without breaking package identity

Build constants are in `composite-builds/build-logic/common/.../BuildConfig.kt`. Root name remains AndroidIDE and README/visual resources use inherited branding. The manifest includes hard-coded old namespace permission/action/provider values and an applicationId-based FileProvider.

First change visible name, icon, splash, about/help and user-facing references while leaving app ID/package namespace unchanged. Preserve upstream/GPL attribution. Search all resources/source/templates, not only BuildConfig.

If app ID is later changed intentionally, search every old namespace reference in source, manifests, providers, permissions, intent actions, tests and CI; test install/upgrade/data behavior because Android generally treats a changed application ID as a different app.

Decide minimum Android and ABI policy based on actual Android APIs, dependencies and embedded binaries. Current min SDK constant 26 means Android 8 floor; target SDK 28 and compile SDK 36 are different concerns. x86_64 is unverified because app upload/AAPT2 setup is commented. NDK is unverified because README says no official support despite a declared version.

**Acceptance:** consistent MStudio branding and verified compatibility claims.

## 4. Phase 2 — self-contained runtime and setup repair

### 2.1 Lock down runtime paths
Read `core/common/.../Environment.java` and every caller. Source paths include app-private `usr`, `home`, `.androidide`, `.gradle`, `home/android-sdk`, managed Java under `usr/lib/jvm/java-21-openjdk`, tooling JAR under `home/.androidide/tooling-api`, AAPT2 under `home/.androidide/aapt2` and init script under `home/.androidide/init`. Projects currently use external `AndroidIDEProjects` through a storage helper.

Keep path definitions centralized. Before moving paths, search callers and define migration of existing projects/caches. Never delete project data during runtime cleanup.

### 2.2 Make ToolsManager failures visible
`ToolsManager.init` asynchronously checks ABI, loads JDK distributions and extracts plugin, AAPT2, tooling JAR, android.jar, color schemes, init script and Kotlin download script. An extraction error can be logged while callback still runs. Add per-component result/readiness and repair rather than treating callback completion as full success.

Verify downloaded source/version/hash, archive paths, executable permissions and successful invocation. Make setup idempotent. Test offline first run, interrupted download, corrupt archive/hash, disk full, missing JVM/AAPT2, app killed during setup, permission failure, upgrade and repeated taps.

### 2.3 JDK selection
`JdkUtils.kt` currently scans `$PREFIX/lib/jvm` and has a TODO about outside installations. Test managed runtime presence/version, corrupted install and execute permission. Distinguish MStudio source build JDK 21, Java source compatibility 17, managed runtime Java and each user's wrapper/AGP/JDK. Avoid changing all version numbers to fix one compatibility error.

### 2.4 Custom AAPT2
`SetupAapt2Task.kt` downloads custom AAPT2/checks SHA-256 for ARM64/ARMv7; x86_64 commented. `GradleBuildService` provides `android.aapt2FromMavenOverride`. For each supported ABI verify download, checksum, executable bit, binary version, resource compile/link and Gradle integration. Retain custom override until an alternative passes actual resource build.

### 2.5 First-run UX/storage
Reuse `OnboardingActivity` and onboarding fragments. Show ABI/storage/network checks, per-tool progress, setup readiness and a retryable error. Download tool packages as needed instead of bloating base APK with every SDK/JDK version. Preserve space for dependency caches and APK outputs; safely handle interrupted downloads. Terminal remains optional for advanced users.

**Acceptance:** clean/interrupted tool setup is recoverable entirely inside MStudio without separate Termux app.

## 5. Phase 3 — actual Gradle build lifecycle

### 3.1 Trace and test call chain
Project selection → project sync/model → foreground `GradleBuildService` → internal Termux-derived environment → `ToolingServerRunner` process (managed Java + tooling JAR) → `ToolingApiLauncher` RPC → `tooling/impl` invokes Gradle Tooling API → results/events return → project indexing refresh → APK output/install.

Test each boundary. Process launched does not mean sync completed or APK built.

### 3.2 Wrapper detection/repair
Service checks `gradlew`, `gradle/wrapper/gradle-wrapper.jar` and `gradle-wrapper.properties` and can extract `gradle-wrapper.zip` from assets. Test each missing file, broken zip, read-only project, repeated repair, invalid wrapper and distribution download failure. Never overwrite a valid wrapper silently; explain proposed repair.

### 3.3 Arguments/flags
Service adds init script and AAPT2 override. Preferences include stacktrace/info/debug, scan, warning mode, cache and offline. Verify flags are passed once, output remains actionable and offline failures separate missing cached dependencies from compile issues. Build Scan can upload data and must require explicit user consent.

### 3.4 Lifecycle/cancel/output
Review `GradleBuildService`, `ToolingServerRunner`, `GradleServiceBinder`, `GradleBuildServiceConnnection` (source spelling) and callbacks. Test concurrent/double-tap requests, notifications, stderr/stdout drains, start failure/nonzero process, failure during sync/task, cancellation actually stopping work, Activity recreation, navigation, repeated build after cancel and correct status/artifact path. `dispatchBuildResult` appears to refresh indexing only; trace all callers before changing it.

### 3.5 Fixtures and tests
Inspect existing `testing/resources/sample-project`, `testing/resources/test-project` and Tooling API tests before adding fixtures. Cover Java/XML app, Maven dependency, Android/Java library dependency, Kotlin DSL, nested/multi-module, flavors/variants, missing SDK, resource/manifest failure, incompatible JDK/AGP, offline cached success and cancellation. Run tests, safe CI and device install/build.

**Acceptance:** actual Gradle produces a valid installable APK with understandable output, cancellation and retry.

## 6. Phase 4 — import, modules, variants and dependencies

`ProjectValidation` currently expects an `app` child with Gradle script for common Android projects. Test roots, parent containers, application module not named app, root Java plugin, library-only projects, Kotlin DSL, nested/included/composite modules, hidden/incomplete folders, Unicode/spaces and SAF/document-provider access. Don't accept arbitrary folders; use safe selection and Gradle sync as authoritative.

`ProjectManagerImpl` maps workspace/module and sync/index state. `AndroidProjectModelBuilder` reads Android model/DSL/variants/dependencies but has a product-flavor TODO for classes JAR lookup. Add tests for module names, variant dimensions, test classpaths, dependencies, sync issues and switching projects. Never silently replace an explicit variant; disclose fallback if used.

Initial import/sync must not silently rewrite build files, move/delete data or expose credentials to Gradle scripts.

**Acceptance:** valid projects import safely, modules/tasks/variants are accurate and sync issues actionable.

## 7. Phase 5 — editor, LSP and diagnostics

Audit `editor/api`, `editor/impl`, `editor/lexers`, `editor/treesitter`, core LSP APIs/models, `java/lsp`, `javac-services`, `lsp/kotlin`, `xml/lsp` and indexing before porting other editors. Test highlighting, Java completion, compile diagnostics, import assist, navigation, references, symbol search, formatting/code actions, XML completion, Kotlin features and recovery when classpath/sync fails.

Tie services to actual Gradle module, selected variant and dependency classpath. Re-index after sync/file create/rename/delete/build and clear stale diagnostics. Ensure indexing doesn't lock UI or compete unboundedly with Gradle. Classify Gradle sync, Java/Kotlin compile, XML/resource, network, JDK/SDK/AAPT2 and IDE-internal errors; include file/line when known and preserve original message when location is missing.

MStudio already builds google-java-format source. Trace current editor wiring before porting NIDE formatter. Port only for a demonstrated gap.

**Acceptance:** errors are understandable/navigable and editor stays responsive during builds.

## 8. Phase 6 — XML designer/resources/previews

Relevant modules include `utilities/uidesigner`, `utilities/xml-inflater`, `xml/aaptcompiler`, `xml/dom`, `xml/utils`, `xml/lsp` and `subprojects/compose-preview`. README marks designer incomplete, so source presence is not acceptance.

Test layout open/parse errors, string/color/drawable/dimen/style resolution, XML completion, source edits refreshing preview, visual attribute edits writing valid XML without dropping unrelated attributes, drag/drop preserving IDs/constraints, includes/custom views/config-qualified resources and missing dependencies. Force preview error and ensure source isn't overwritten. Test Compose preview separately from XML/Views preview.

**Acceptance:** XML and visual edits safely round-trip; missing resources and preview failures are clear.

## 9. Phase 7 — templates and beginner UX

Inspect `MainActivity`, template ViewModels/fragments, `utilities/templates-api`, `utilities/templates-impl`, `TemplateRecipeExecutor` and onboarding. For every advertised template: generate project, sync, build through actual local Tooling API, install and launch. Validate package/activity/namespace, manifest, resources, wrapper/settings, AGP and SDK agree. Test duplicate/invalid names, Unicode/spaces, low storage, cancellation, partial cleanup and reopening after restart.

Then redesign UI based on verified flows. Use string resources; preserve RTL, keyboard/insets and narrow/landscape. Terminal remains optional for normal build/create/install.

**Acceptance:** every advertised template yields runnable APK and failures offer recovery.

## 10. Phase 8 — select NIDE ports

Use `NIDE_FEATURE_MATRIX.md`. Each candidate needs exact source, MStudio counterpart, reproducible gap, minimal port boundary, dependency/license audit, tests, CI/device evidence and commit. Candidates may include decompiler, independent Java/JAR workflow, formatter options if genuinely absent, samples and an autocomplete behavior. Do not port full NIDE builder, old dx/JDK1.7 compiler or FastGradleParser to the Gradle pipeline.

**Acceptance:** each port adds tested unique value and no competing Android build engine.

## 11. Phase 9 — performance, security, compatibility

Profile managed JVM, Gradle daemons/workers, LSP/indexer/previews separately. Do not assume 5096 MB heap suits phones. Test cache size, low storage, corrupt cache, interrupted downloads, app update and active build; never clean active caches or projects.

Treat Gradle scripts as executable/untrusted. Verify downloaded binaries; review manifest permissions/exported services/providers; keep credentials out of logs; require consent for Build Scan/telemetry. Maintain a tested matrix of device/API/ABI/JDK/Wrapper/AGP/DSL/modules/dependencies/results. Verify NDK and x86_64 through real builds before advertising.

**Acceptance:** tested compatibility, recovery paths and measured resource use.

## 12. Phase 10 — cloud build (deferred)

See `CLOUD_BUILD_PLAN.md`. Only begin after local MVP. Later flow: GitHub authorization, selected repository/branch, workflow diff/approval, dispatch, run/status/log and APK download. Use least privilege and verify API log semantics; never expose user token to build scripts. Cloud login must remain optional.

## 13. Phase 11 — release

Build from known commit; verify APK checksum/signature/ABI; test clean install/upgrade; create/import/build Java/XML project; resolve Maven dependency; test resources, failure/cancel, tool repair, cached offline build and install. Verify signing ownership, publication opt-in, GPL/third-party notices and limitations. README claims must match tested behavior.

## 14. Local MVP evidence checklist

- [ ] Fresh install sets up tools without separate Termux app.
- [ ] Create Java/XML project and import supported Gradle project.
- [ ] Sync model works and external Maven dependency resolves.
- [ ] Actual Tooling API runs assembleDebug and emits APK.
- [ ] Custom AAPT2 resource compile/link succeeds on named supported device/ABI.
- [ ] APK installs/launches on named device.
- [ ] Failures are actionable and cancellation doesn't leave process stuck.
- [ ] Missing/corrupt runtime tool can be repaired.
- [ ] CI cannot publish by default; release signing is deliberate.
- [ ] Supported versions and limitations are evidence-based.
- [ ] Local build works without GitHub login and can reuse dependencies offline.

Keep all boxes unchecked until evidence is recorded.


## Execution record — 2026-10-12

### Phase 0.1 — source state reconfirmed

- Rechecked `dev` at starting HEAD `ccd8b4c9abbcebe8fe72700120af93053e6504da` (`edit Build.yml`). That commit removed workflow-wide Maven credentials but left snapshot publishing insufficiently gated.
- Rechecked root settings/build scripts, Gradle wrapper, version catalog, daemon JVM settings, `.gitmodules`, workflow YAML, app Gradle configuration, manifest, `.nyx.yml`, and project architecture/cloud-build notes.
- Confirmed wrapper Gradle 8.14.4, daemon JDK 21, AGP 8.13.2, Kotlin 2.3.21, Gradle Tooling API 9.7.0, and declared submodule `composite-builds/external/logback-android`. Remote source inspection confirms the declaration, not runner checkout status.
- Phase 0.1 source recheck is complete; this is not proof of a successful build or device test.

### Phase 0.2 — publishing guard committed

- Commit `63ae9aa0d9bac985b5efc6869069826b04af7a97` (`ci: guard Maven publishing behind upstream-only opt-in`) removes Maven Central credentials from workflow-wide `env` and scopes them to the publishing steps only.
- Release publishing requires `vars.MSTUDIO_ENABLE_UPSTREAM_RELEASE_PUBLISHING == 'true'`, repository `AndroidIDE-Dev/AndroidIDE`, `main`, a non-PR event, and successful APK + Tooling API jobs.
- Snapshot publishing requires `vars.MSTUDIO_ENABLE_UPSTREAM_SNAPSHOT_PUBLISHING == 'true'`, repository `AndroidIDE-Dev/AndroidIDE`, branch `dev` or `indexing`, a non-PR event, and a successful APK job. The MStudio fork is blocked by the repository allowlist.
- Re-fetched the committed workflow and verified both opt-in checks, both repository allowlists, and that Maven credentials are scoped to the two publishing steps. Check the resulting Actions run for parser/runtime feedback; no build, publication, or device test is claimed yet.
- Next: Phase 0.3 signing audit, then Phase 0.4 baseline CI.


### Source-state correction — submodule path

- Follow-up inspection of the GitHub contents API for `composite-builds/external/logback-android` returned an ordinary directory listing, not a Git submodule entry. The `.gitmodules` file declares this path as a submodule, so the committed tree and submodule declaration appear inconsistent. Resolve this explicitly before treating recursive checkout as proven; do not claim the submodule is initialized or assume its state.
