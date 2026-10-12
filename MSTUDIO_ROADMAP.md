# MStudio — Comprehensive Source-Grounded Roadmap

> Planning grounded in inspected code, not a claim that items are already implemented.
>
> Initial source baseline: `e4dc15adf1c670edcfce12008e822c02b0d9cb64` on `dev`. Re-check live HEAD and actual files before every implementation task.

## Product definition and operating principles

MStudio aims to become a self-contained Android IDE for real Android development on a phone. MStudio must manage its own runtime, JDK, Android SDK tools, Gradle integration, project model, editor and previews. Beginners use a guided interface; terminal is optional for advanced users. Local build must remain available without GitHub login.

Preserve the actual Gradle Tooling API path in `tooling/api`, `tooling/impl`, `GradleBuildService` and `ToolingServerRunner`. NIDE is a selective feature source, not the build foundation. Never merge all of NIDE or substitute its custom APK builder/regex parser for Gradle.

Only mark an item complete when a commit and reproducible evidence exists: automated test, CI job/artifact or named device test. A class name, README checkbox or workflow definition is not proof.

## Source evidence register

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

## Phase 1 — lock baseline, disable publication, prove APK

### 1.1 Reconfirm source state
Fetch live `dev` HEAD and record SHA, date, subject, wrapper, version catalog and submodule state. Inspect complete current `settings.gradle.kts`, root `build.gradle.kts`, `gradle.properties`, wrapper properties, version catalog, daemon JVM config, `.gitmodules`, all workflow YAML, `core/app/build.gradle.kts` and main manifest. Check recursive submodule checkout; `.gitmodules` includes `composite-builds/external/logback-android`.

**Acceptance:** findings cite current SHA, not the old baseline.

### 1.2 Gate publishing before any routine CI
The inspected `build.yml` has `build_apk` and Tooling API tests, but also `publish` and `publish_snapshots`. The snapshot condition allows successful non-main/non-PR runs, potentially including a dev push. Review all publish/release job conditions, Nyx tasks and Maven credential references. Add a deliberate opt-in false by default for publishing, or separate normal build/test from release. Confirm default push/dispatch can build and upload artifacts only. Preserve upstream publishing capability behind intentional owner-controlled settings; don't delete it without documentation.

Markdown paths are ignored by main `build.yml`, so after the guard is committed deliberately dispatch or run the workflow safely. Never test by accidentally publishing to Maven Central.

**Acceptance:** default CI cannot release/publish, even if publishing credentials exist.

### 1.3 Audit signing material
Inspect tracked `core/app/dev.keystore`, `core/app/build.gradle.kts`, `gradle.properties`, `.nyx.yml` and all signing consumers. Determine what is dev-only. Debug builds must work without public release credentials. Release signing must use owner-controlled secrets, not values committed into source. Fail closed when deliberate release keys are missing; do not ship the inherited keystore as MStudio release identity.

**Acceptance:** safe debug path and a separate, deliberate release identity.

### 1.4 Baseline CI
After publish gating, run the existing primary workflow: recursive checkout, JDK 21, chmod gradlew, restore cache, build `core/app` debug and release, upload ARM64/ARMv7 artifacts and run Tooling API tests with `ANDROIDIDE_TEST=true` and `TEST_TOOLING_API_IMPL=true`. The separate tooling-api workflow only builds `tooling:impl:jar` on JDK 17; don't count it as full APK evidence.

Record run URL, job IDs, first causal failure, commit SHA, artifact names/ABI and submodule status. Fix earliest causal failure before unrelated updates.

**Acceptance:** exact branch build result understood; APK artifacts and Tooling API tests separately recorded.

### 1.5 Device smoke test
Download artifact for a named device ABI. Test installation, app startup, onboarding, permission-denial path, setup status, main screen, project create/open, editor and terminal entry, crash-free navigation. Record Android API, ABI, artifact SHA if available and logs. APK file existence is not install/run success.

**Gate:** broad visual redesign and NIDE merge remain gated until the safe CI and baseline artifact evaluation in this phase. Limited fork-identity fixes needed to avoid misleading users may proceed, but must not alter the build engine.

## Phase 2 — MStudio identity and user-facing rebrand

### 2.1 Visible identity
Audit launcher label, root project name, build constants, About screen, onboarding/help copy, README and user-facing strings. Use MStudio branding while preserving GPL and upstream attribution. Do not claim assets were replaced unless tracked icon/splash resources are found and changed.

### 2.2 About links and project destinations
Remove inherited AndroidIDE social/contact destinations from the MStudio About screen when they do not belong to this fork. Prefer verified MStudio repository destinations; do not invent an email, website, Telegram channel or contributor URL.

### 2.3 Package identity boundary
The current applicationId is `com.magd.mstudio`, while namespace and implementation packages intentionally remain `dev.mutwakil.androidide`. Keep this boundary until a separate planned migration. Review hard-coded authorities/actions/permissions, especially the DocumentsProvider authority `dev.mutwakil.androidide.documents`. Do not claim side-by-side installation compatibility until tested.

### 2.4 Compatibility and attribution
Keep upstream/GPL notices. Verify README and UI claims against evidence; do not advertise unsupported x86_64/NDK or device compatibility.

**Acceptance:** visible MStudio identity is coherent, About links point only to verified project destinations, and package/provider caveats remain explicit.

Build constants are in `composite-builds/build-logic/common/.../BuildConfig.kt`. Root name remains AndroidIDE and README/visual resources use inherited branding. The manifest includes hard-coded old namespace permission/action/provider values and an applicationId-based FileProvider.

First change visible name, icon, splash, about/help and user-facing references while leaving app ID/package namespace unchanged. Preserve upstream/GPL attribution. Search all resources/source/templates, not only BuildConfig.

If app ID is later changed intentionally, search every old namespace reference in source, manifests, providers, permissions, intent actions, tests and CI; test install/upgrade/data behavior because Android generally treats a changed application ID as a different app.

Decide minimum Android and ABI policy based on actual Android APIs, dependencies and embedded binaries. Current min SDK constant 26 means Android 8 floor; target SDK 28 and compile SDK 36 are different concerns. x86_64 is unverified because app upload/AAPT2 setup is commented. NDK is unverified because README says no official support despite a declared version.

**Acceptance:** consistent MStudio branding and verified compatibility claims.

## Phase 3 — self-contained runtime and setup repair

### 3.1 Lock down runtime paths
Read `core/common/.../Environment.java` and every caller. Source paths include app-private `usr`, `home`, `.androidide`, `.gradle`, `home/android-sdk`, managed Java under `usr/lib/jvm/java-21-openjdk`, tooling JAR under `home/.androidide/tooling-api`, AAPT2 under `home/.androidide/aapt2` and init script under `home/.androidide/init`. Projects currently use external `AndroidIDEProjects` through a storage helper.

Keep path definitions centralized. Before moving paths, search callers and define migration of existing projects/caches. Never delete project data during runtime cleanup.

### 3.2 Make ToolsManager failures visible
`ToolsManager.init` asynchronously checks ABI, loads JDK distributions and extracts plugin, AAPT2, tooling JAR, android.jar, color schemes, init script and Kotlin download script. An extraction error can be logged while callback still runs. Add per-component result/readiness and repair rather than treating callback completion as full success.

Verify downloaded source/version/hash, archive paths, executable permissions and successful invocation. Make setup idempotent. Test offline first run, interrupted download, corrupt archive/hash, disk full, missing JVM/AAPT2, app killed during setup, permission failure, upgrade and repeated taps.

### 3.3 JDK selection
`JdkUtils.kt` currently scans `$PREFIX/lib/jvm` and has a TODO about outside installations. Test managed runtime presence/version, corrupted install and execute permission. Distinguish MStudio source build JDK 21, Java source compatibility 17, managed runtime Java and each user's wrapper/AGP/JDK. Avoid changing all version numbers to fix one compatibility error.

### 3.4 Custom AAPT2
`SetupAapt2Task.kt` downloads custom AAPT2/checks SHA-256 for ARM64/ARMv7; x86_64 commented. `GradleBuildService` provides `android.aapt2FromMavenOverride`. For each supported ABI verify download, checksum, executable bit, binary version, resource compile/link and Gradle integration. Retain custom override until an alternative passes actual resource build.

### 3.5 First-run UX/storage
Reuse `OnboardingActivity` and onboarding fragments. Show ABI/storage/network checks, per-tool progress, setup readiness and a retryable error. Download tool packages as needed instead of bloating base APK with every SDK/JDK version. Preserve space for dependency caches and APK outputs; safely handle interrupted downloads. Terminal remains optional for advanced users.

**Acceptance:** clean/interrupted tool setup is recoverable entirely inside MStudio without separate Termux app.

## Phase 4 — actual Gradle build lifecycle

### 4.1 Trace and test call chain
Project selection → project sync/model → foreground `GradleBuildService` → internal Termux-derived environment → `ToolingServerRunner` process (managed Java + tooling JAR) → `ToolingApiLauncher` RPC → `tooling/impl` invokes Gradle Tooling API → results/events return → project indexing refresh → APK output/install.

Test each boundary. Process launched does not mean sync completed or APK built.

### 4.2 Wrapper detection/repair
Service checks `gradlew`, `gradle/wrapper/gradle-wrapper.jar` and `gradle-wrapper.properties` and can extract `gradle-wrapper.zip` from assets. Test each missing file, broken zip, read-only project, repeated repair, invalid wrapper and distribution download failure. Never overwrite a valid wrapper silently; explain proposed repair.

### 4.3 Arguments/flags
Service adds init script and AAPT2 override. Preferences include stacktrace/info/debug, scan, warning mode, cache and offline. Verify flags are passed once, output remains actionable and offline failures separate missing cached dependencies from compile issues. Build Scan can upload data and must require explicit user consent.

### 4.4 Lifecycle/cancel/output
Review `GradleBuildService`, `ToolingServerRunner`, `GradleServiceBinder`, `GradleBuildServiceConnnection` (source spelling) and callbacks. Test concurrent/double-tap requests, notifications, stderr/stdout drains, start failure/nonzero process, failure during sync/task, cancellation actually stopping work, Activity recreation, navigation, repeated build after cancel and correct status/artifact path. `dispatchBuildResult` appears to refresh indexing only; trace all callers before changing it.

### 4.5 Fixtures and tests
Inspect existing `testing/resources/sample-project`, `testing/resources/test-project` and Tooling API tests before adding fixtures. Cover Java/XML app, Maven dependency, Android/Java library dependency, Kotlin DSL, nested/multi-module, flavors/variants, missing SDK, resource/manifest failure, incompatible JDK/AGP, offline cached success and cancellation. Run tests, safe CI and device install/build.

**Acceptance:** actual Gradle produces a valid installable APK with understandable output, cancellation and retry.

## Phase 5 — import, modules, variants and dependencies

`ProjectValidation` currently expects an `app` child with Gradle script for common Android projects. Test roots, parent containers, application module not named app, root Java plugin, library-only projects, Kotlin DSL, nested/included/composite modules, hidden/incomplete folders, Unicode/spaces and SAF/document-provider access. Don't accept arbitrary folders; use safe selection and Gradle sync as authoritative.

`ProjectManagerImpl` maps workspace/module and sync/index state. `AndroidProjectModelBuilder` reads Android model/DSL/variants/dependencies but has a product-flavor TODO for classes JAR lookup. Add tests for module names, variant dimensions, test classpaths, dependencies, sync issues and switching projects. Never silently replace an explicit variant; disclose fallback if used.

Initial import/sync must not silently rewrite build files, move/delete data or expose credentials to Gradle scripts.

**Acceptance:** valid projects import safely, modules/tasks/variants are accurate and sync issues actionable.

## Phase 6 — editor, LSP and diagnostics

Audit `editor/api`, `editor/impl`, `editor/lexers`, `editor/treesitter`, core LSP APIs/models, `java/lsp`, `javac-services`, `lsp/kotlin`, `xml/lsp` and indexing before porting other editors. Test highlighting, Java completion, compile diagnostics, import assist, navigation, references, symbol search, formatting/code actions, XML completion, Kotlin features and recovery when classpath/sync fails.

Tie services to actual Gradle module, selected variant and dependency classpath. Re-index after sync/file create/rename/delete/build and clear stale diagnostics. Ensure indexing doesn't lock UI or compete unboundedly with Gradle. Classify Gradle sync, Java/Kotlin compile, XML/resource, network, JDK/SDK/AAPT2 and IDE-internal errors; include file/line when known and preserve original message when location is missing.

MStudio already builds google-java-format source. Trace current editor wiring before porting NIDE formatter. Port only for a demonstrated gap.

**Acceptance:** errors are understandable/navigable and editor stays responsive during builds.

## Phase 7 — XML designer/resources/previews

Relevant modules include `utilities/uidesigner`, `utilities/xml-inflater`, `xml/aaptcompiler`, `xml/dom`, `xml/utils`, `xml/lsp` and `subprojects/compose-preview`. README marks designer incomplete, so source presence is not acceptance.

Test layout open/parse errors, string/color/drawable/dimen/style resolution, XML completion, source edits refreshing preview, visual attribute edits writing valid XML without dropping unrelated attributes, drag/drop preserving IDs/constraints, includes/custom views/config-qualified resources and missing dependencies. Force preview error and ensure source isn't overwritten. Test Compose preview separately from XML/Views preview.

**Acceptance:** XML and visual edits safely round-trip; missing resources and preview failures are clear.

## Phase 8 — templates and beginner UX

Inspect `MainActivity`, template ViewModels/fragments, `utilities/templates-api`, `utilities/templates-impl`, `TemplateRecipeExecutor` and onboarding. For every advertised template: generate project, sync, build through actual local Tooling API, install and launch. Validate package/activity/namespace, manifest, resources, wrapper/settings, AGP and SDK agree. Test duplicate/invalid names, Unicode/spaces, low storage, cancellation, partial cleanup and reopening after restart.

Then redesign UI based on verified flows. Use string resources; preserve RTL, keyboard/insets and narrow/landscape. Terminal remains optional for normal build/create/install.

**Acceptance:** every advertised template yields runnable APK and failures offer recovery.

## Phase 9 — select NIDE ports

Use `NIDE_FEATURE_MATRIX.md`. Each candidate needs exact source, MStudio counterpart, reproducible gap, minimal port boundary, dependency/license audit, tests, CI/device evidence and commit. Candidates may include decompiler, independent Java/JAR workflow, formatter options if genuinely absent, samples and an autocomplete behavior. Do not port full NIDE builder, old dx/JDK1.7 compiler or FastGradleParser to the Gradle pipeline.

**Acceptance:** each port adds tested unique value and no competing Android build engine.

## Phase 10 — performance, security, compatibility

Profile managed JVM, Gradle daemons/workers, LSP/indexer/previews separately. Do not assume 5096 MB heap suits phones. Test cache size, low storage, corrupt cache, interrupted downloads, app update and active build; never clean active caches or projects.

Treat Gradle scripts as executable/untrusted. Verify downloaded binaries; review manifest permissions/exported services/providers; keep credentials out of logs; require consent for Build Scan/telemetry. Maintain a tested matrix of device/API/ABI/JDK/Wrapper/AGP/DSL/modules/dependencies/results. Verify NDK and x86_64 through real builds before advertising.

**Acceptance:** tested compatibility, recovery paths and measured resource use.

## Phase 11 — cloud build (deferred)

See `CLOUD_BUILD_PLAN.md`. Only begin after local MVP. Later flow: GitHub authorization, selected repository/branch, workflow diff/approval, dispatch, run/status/log and APK download. Use least privilege and verify API log semantics; never expose user token to build scripts. Cloud login must remain optional.

## Phase 12 — release

Build from known commit; verify APK checksum/signature/ABI; test clean install/upgrade; create/import/build Java/XML project; resolve Maven dependency; test resources, failure/cancel, tool repair, cached offline build and install. Verify signing ownership, publication opt-in, GPL/third-party notices and limitations. README claims must match tested behavior.

## Local MVP evidence checklist

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

### Phase 1.1 — source state reconfirmed

- Rechecked `dev` at starting HEAD `ccd8b4c9abbcebe8fe72700120af93053e6504da` (`edit Build.yml`). That commit removed workflow-wide Maven credentials but left snapshot publishing insufficiently gated.
- Rechecked root settings/build scripts, Gradle wrapper, version catalog, daemon JVM settings, `.gitmodules`, workflow YAML, app Gradle configuration, manifest, `.nyx.yml`, and project architecture/cloud-build notes.
- Confirmed wrapper Gradle 8.14.4, daemon JDK 21, AGP 8.13.2, Kotlin 2.3.21, Gradle Tooling API 9.7.0, and declared submodule `composite-builds/external/logback-android`. Remote source inspection confirms the declaration, not runner checkout status.
- Phase 1.1 source recheck is complete; this is not proof of a successful build or device test.

### Phase 1.2 — publishing guard committed

- Commit `63ae9aa0d9bac985b5efc6869069826b04af7a97` (`ci: guard Maven publishing behind upstream-only opt-in`) removes Maven Central credentials from workflow-wide `env` and scopes them to the publishing steps only.
- Release publishing requires `vars.MSTUDIO_ENABLE_UPSTREAM_RELEASE_PUBLISHING == 'true'`, repository `AndroidIDE-Dev/AndroidIDE`, `main`, a non-PR event, and successful APK + Tooling API jobs.
- Snapshot publishing requires `vars.MSTUDIO_ENABLE_UPSTREAM_SNAPSHOT_PUBLISHING == 'true'`, repository `AndroidIDE-Dev/AndroidIDE`, branch `dev` or `indexing`, a non-PR event, and a successful APK job. The MStudio fork is blocked by the repository allowlist.
- Re-fetched the committed workflow and verified both opt-in checks, both repository allowlists, and that Maven credentials are scoped to the two publishing steps. Check the resulting Actions run for parser/runtime feedback; no build, publication, or device test is claimed yet.
- Next: Phase 1.3 signing audit, then Phase 1.4 baseline CI.


### Source-state correction — submodule path

- Follow-up inspection of the GitHub contents API for `composite-builds/external/logback-android` returned an ordinary directory listing, not a Git submodule entry. The `.gitmodules` file declares this path as a submodule, so the committed tree and submodule declaration appear inconsistent. Resolve this explicitly before treating recursive checkout as proven; do not claim the submodule is initialized or assume its state.


## Execution log — 2026-10-12 (Phase 1.3 security audit + Phase 2 identity kickoff)

### Phase 1.3 — signing material audit/remediation

Inspected `core/app/build.gradle.kts`, root `gradle.properties`, `.nyx.yml`, `.gitignore`, `SigningConfigPlugin.kt`, `SigningKeyUtils.kt`, `config.kt`, the main manifest, and the tracked signing paths. The actual build-logic signing path is `signing/androidide.keystore`; `SigningConfigPlugin` only attaches its shared signing configuration when the key and all three alias/store/key credentials are available. The plugin attempts to obtain a missing key from environment/property-configured sources, and otherwise leaves the build without that common signing config.

Changes committed:
- Removed hardcoded `androidide` alias/password defaults from tracked `gradle.properties`.
- Added ignore rules for local signing material under `signing/` and `core/app/`.
- Removed tracked `signing/androidide.keystore`, the empty `signing/signing-key.jks` placeholder, and `core/app/dev.keystore`. No key bytes or secrets were displayed.
- Existing Git history is not rewritten. Treat the inherited keystore as public/compromised; do not reuse it as MStudio's release identity. A new release identity must be created privately and supplied through owner-controlled secrets or an untracked local Gradle properties file.
- Do not distribute a release APK until its signing identity is explicitly configured and verified. The build itself has intentionally not been run at this stage.

**Status:** source/config remediation committed; signing lifecycle still needs an explicit, testable release-task guard and a new private release identity before release distribution. This is not evidence of a successful build.

### Phase 2 — identity kickoff (not yet complete)

- Changed the app launcher label from `AndroidIDE-Dev` to `MStudio`.
- Changed `BuildConfig.INTERNAL_NAME` and Gradle `rootProject.name` to `MStudio`; Android namespace/application ID remain `dev.mutwakil.androidide` to avoid an unsafe package migration.
- Updated Nyx issue URLs, release asset names/descriptions and repository owner/name to this MStudio fork.
- Remaining before Phase 2 can be marked complete: audit and update About/help/onboarding copy, splash/icon assets and remaining user-visible inherited branding; inspect package-specific manifest permissions/actions/providers without renaming them; review release metadata and license/attribution consistency.

### Explicit sequencing decision

The user requested that Actions execution and actual builds happen only after substantial source-level work. Therefore Phase 1.4 (baseline Actions/build) is deferred until after the planned code review and implementation phases; Markdown-only commits are not considered build evidence.


### Phase 2 follow-up — README/source-facing identity

Updated `README.md` title and repository badges/issues/download destinations to MStudio, removed misleading instructions to download upstream AndroidIDE releases as MStudio releases, and labeled the upstream docs/community as inherited references. Preserved the original AndroidIDE GPL attribution and upstream acknowledgements. The launcher icon/splash and About screen destinations are intentionally not declared rebranded yet; their actual asset/configuration sources still need tracing before safe edits.


## Execution log — 2026-10-12 (Phase 2 application ID + Phase 3 runtime extraction)

### Phase 2 — application identity and signing workflow

- Set the Android **application ID** in `core/app/build.gradle.kts` to `com.magd.mstudio`.
- Intentionally left `namespace`, `BuildConfig.PACKAGE_NAME`, Java/Kotlin packages, and existing hard-coded AndroidIDE permission/action/provider identifiers unchanged, as requested. This is an application-ID-only change, not a package migration.
- Updated `.github/workflows/build.yml` to consume the existing owner-managed GitHub Secrets `MY_KEYSTORE_JKS`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`. These map to the legacy Gradle plugin environment names expected by the inherited signing plugin. `MY_KEYSTORE_JKS` must contain the Base64-encoded keystore bytes because `SigningKeyUtils` Base64-decodes `IDE_SIGNING_KEY_BIN`.
- Release signing secrets are scoped to the release build step instead of the workflow globally. A release preflight fails if any required secret is missing or the keystore secret is not valid Base64. Debug builds do not receive release credentials.
- Removed `dev` from automatic push-build branches so further source commits on `dev` do not launch the APK/test matrix before the user-authorized baseline phase. `workflow_dispatch` remains available; do not manually dispatch it yet.
- Remaining Phase 2: About/help/social destinations and actual icon/splash assets are not yet fully rebranded. Namespace/manifest authority migration remains deliberately out of scope.

### Phase 3 — runtime extraction safety (initial implementation)

- In `core/common/src/main/java/dev/mutwakil/androidide/managers/ToolsManager.java`, replaced delete-then-copy behavior for the embedded Gradle plugin JAR, Tooling API JAR, Kotlin artifact download script, and missing `android.jar` with a temporary-file extraction helper.
- The helper writes and syncs a temporary file, rejects empty output, then replaces the destination with an atomic move where supported (with a replace-existing fallback). A failed extraction now preserves the previously working destination instead of deleting it first.
- This is source-level implementation only. No build, tests, or Actions were run, by design. Later verification must exercise failed/interrupted copy, existing destination preservation, low-storage failure, and successful first-run extraction on a supported device.
- Further Phase 3 work remains: AAPT2 validation/repair, color-scheme directory replacement, explicit per-tool readiness, and actionable setup recovery.

### Sequencing guard

The user requested that build and Actions verification be deferred until substantial code work is complete. Do not manually start Actions or a build during the current source phases. Phase 1.4 remains deferred.


### Phase 3 follow-up — AAPT2 extraction

- Changed AAPT2 extraction to copy the packaged native binary to a temporary file and replace the destination only after a non-empty copy succeeds.
- Missing source binary, empty output, and inability to set executable permission now produce explicit setup failures rather than a silent log-only condition.
- Still unverified until later device/build phase; color-scheme directory replacement and repair of a pre-existing corrupt AAPT2 file remain follow-up work.


### Application-ID-only compatibility caveat

Changing `applicationId` does not change hard-coded manifest authorities. The documents provider still declares `dev.mutwakil.androidide.documents`; this may conflict with the upstream AndroidIDE package if both apps are installed. It was intentionally left untouched under the current application-ID-only scope. Before claiming side-by-side installation compatibility, revisit provider authorities and test installation on a clean device and with upstream AndroidIDE already installed.


## Roadmap numbering migration — 2026-10-12

- Replaced the old Phase 0 + Phase 1–11 scheme with a single sequential Phase 1–12 scheme. Previous Phase 0 is now Phase 1; previous Phase 1 is Phase 2; previous Phase 2 is Phase 3; subsequent phases each move up by one. Substeps are scoped under their parent phase (for example 3.1–3.5), never standalone fractional phases.
- Historical execution records were renumbered to the same scheme. Completion claims remain evidence-based; a source change alone does not complete an acceptance gate.
- Active implementation order: finish Phase 2 (identity/rebrand), then Phase 3 (runtime/setup repair). Phase 1's baseline CI and device smoke-test subtasks 1.4–1.5 are intentionally deferred until the source work reaches the verification gate; do not run builds or Actions early.


## Execution update — 2026-10-12 — Phases 2 and 3 source work

### Phase 2 — identity and user-facing rebrand (in progress)

Additional audit required before implementation: inspect BaseApplication hard-coded docs/contact/social URLs and every caller, shared resource strings such as msg_need_help, about_footer, and onboarding/help text. Replace inherited destinations only with verified MStudio repository/issues paths, or remove the action when no verified destination exists. Trace the actual launcher icon and splash resource source through asset/build plugins before touching visual assets.

- Updated `AboutActivity` to remove inherited AndroidIDE email and Telegram destinations. The website action now opens the verified MStudio repository, and the contribution action opens the repository's existing `CONTRIBUTING.md` path.
- Kept upstream/GPL source attribution intact. Did not invent MStudio email, website or social channels.
- The application ID remains `com.magd.mstudio`; namespace and implementation packages remain `dev.mutwakil.androidide`. The hard-coded DocumentsProvider authority remains a known risk until the separately planned authority/installation verification is authorized.
- Phase 2 is not complete: launcher icon/splash asset location has not yet been established, and onboarding/help/user-facing references still need a full source-grounded audit. Do not infer that an icon was changed from the manifest alone.

### Phase 3 — runtime and setup repair (in progress)

Before wiring readiness into onboarding, inspect the actual application startup call, setup-fragment lifecycle, UI-thread dispatch conventions and retry path. A result callback must be delivered safely to UI and must not imply that SDK/JDK setup commands in the separate terminal flow are complete merely because ToolsManager extraction completed. Review concurrent/repeated initialization and deterministic .tmp/.bak names for races.

- `ToolsManager` now exposes an immutable per-component `InitResult` through `initWithResult`, records successful components and failure messages, and always reports unsupported ABI instead of silently returning without a completion callback. It separately checks that JDK distributions were discovered and that managed `java -version` and AAPT2 `version` commands exit successfully within five seconds. The original Runnable overload remains for existing callers.
- Android API stub extraction now validates that `android.jar` is a readable non-empty JAR and atomically repairs missing/empty/invalid files while refusing to delete an unexpected directory.
- AAPT2 extraction runs the bundled binary's `version` command with a five-second timeout, replaces missing/empty/non-executable/broken destinations from the packaged native library, and fails setup clearly if the replacement is still unusable.
- Color-scheme updates now stage a complete directory, validate `scheme.prop`, swap only after successful extraction, and recover a prior backup after an interrupted swap. Version-read errors now schedule repair rather than incorrectly treating the installed scheme as valid.
- Remaining Phase 3 work: connect per-component readiness to a lifecycle-safe, user-visible runtime status/retry surface (the existing onboarding wizard handles separate SDK/JDK installation); make initialization single-flight and audit deterministic staging names for races; complete offline/interruption/low-storage/device tests. JDK and AAPT2 invocation checks are implemented in source but remain unverified on-device.

Commits for this batch:
- `878fe9176dafb4e130dc80f3a1ca6e55c1c78113` — About destinations use the MStudio repository.
- `aedc4306896161848518b56059bc4e59ff38443d` — verified contribution-guide link.
- `8e4d4c49e1b50f1149843743e5643c433887430a` and `a4297661e7c9667c6d3291d8f01605e5c01ecc68` — atomic color-scheme replacement and interrupted-swap recovery.
- `6f25e854829c29de71c06741341133adfc12268d` — per-component runtime readiness and Android JAR validation.

No build, test task, workflow dispatch or Actions run was launched. Phase 1 baseline CI and Phase 1 device smoke test remain deferred as requested.


### Phase 3 follow-up — managed Java validation

- Commit `4618270d1497c711d95dcf3f845ebe5478711af2` splits JDK distribution discovery from managed Java execution readiness. It reports an empty distribution list and validates the managed `java -version` process with a five-second timeout.
- This is still source-level validation only; actual runtime installation, version output and retry behavior must be verified on a supported device in the planned test phase.


## Roadmap consistency correction — 2026-10-12

- Corrected misleading section numbering: product definition, source register, evidence checklist and execution logs are document sections, not phases. Main phases remain Phase 1–12 with whole-number IDs and nested decimal substeps.
- Corrected the earlier blanket “former Phase 0 → Phase 1” statement; it was inaccurate because the original roadmap already used Phase 1–12 for its main work and the product-definition section was not a phase. Historical execution entries now preserve their actual context rather than imply a fabricated global renumbering.
- Kept the user's sequencing constraint explicit: no build, test task, workflow dispatch or Actions until the source-level Phase 2/3 work is substantially complete and the verification gate is deliberately reached.
- Expanded Phase 2 scope to audit BaseApplication URLs and shared help/About strings, and to trace the real icon/splash source before visual asset edits. Expanded Phase 3 scope to inspect startup callers, UI-thread/lifecycle handling, real retry behavior and initialization races before connecting readiness to UI.


### Phase 2 discovery — inherited destinations still reachable (2026-10-12)

- `BaseApplication.java` still exposes AndroidIDE Telegram group/channel, AndroidIDE docs, sponsor and email destinations; audit all callers and route user help to the verified MStudio repository/issues instead of inventing contact channels.
- Shared `core/resources/.../values/strings.xml` still contains AndroidIDE-Dev as the common app name, AndroidIDE in About footer/help copy, Telegram labels, and `msg_install_tools` links to docs.androidide.com. The app-specific app_name override is MStudio, but the common definition should be made consistent.
- `CONTRIBUTING.md` exists, so the About link is not a 404; however, its body still refers to AndroidIDE, upstream developer docs and upstream Telegram. It must be fork-adapted before it is advertised as MStudio's contribution guide.
- `OnboardingActivity` currently uses the inherited SDK/JDK terminal setup and only gates completion on installed JDK distributions and `Environment.ANDROID_HOME`; do not conflate that with `ToolsManager` component readiness. Icon/splash source remains untraced.


### Phase 2 discovery — generated build metadata still targets upstream (2026-10-12)

- `utilities/build-info/build.gradle.kts` generates runtime `BuildInfo.java` from `ProjectConfig.kt`. The live `ProjectConfig.kt` still hard-codes `REPO_OWNER = AndroidIDE-Dev`, `REPO_NAME = AndroidIDE`, and `PROJECT_SITE = https://m.androidide.com`. This means changelog links and any consumer of generated `BuildInfo.REPO_URL`/`PROJECT_SITE` can still send users to upstream despite the About screen's hard-coded MStudio URL.
- Phase 2 must update `ProjectConfig.kt` to the verified MStudio repository identity while preserving Maven group IDs and upstream license attribution. Inspect all uses of generated `BuildInfo` fields and distinguish MStudio repository destinations from any upstream attribution links.


### Phase 3 implementation plan — runtime readiness surface and single-flight setup (2026-10-12)

- `ToolsManager` should distinguish “not finished yet” from a completed unsupported-ABI or component failure result. Its initial placeholder result must never be shown as a real failure.
- Keep the existing SDK/JDK onboarding wizard intact. Add a lifecycle-safe observer for `ToolsManager` runtime readiness and show a retryable warning in the main IDE only after runtime initialization completes with failures. Retry must rerun runtime preparation; “Later” must leave the IDE usable without falsely marking tools ready.
- Serialize runtime initialization so a retry or repeated app entry cannot run two extraction jobs against the same deterministic temporary/backup paths. Publish results to UI observers on the main thread without changing the existing completion callback's thread contract for other callers.
- This is source implementation only until the planned verification phase; no build, test or Actions run is allowed during this work batch.


### Phase 2 discovery — actual launcher/splash resources located (2026-10-12)

- Correcting the earlier assumption: launcher resources are tracked under `core/resources/src/main/res`, not `core/app/src/main/res`. `mipmap-anydpi-v26/ic_launcher.xml` points to the inherited `ic_launcher_foreground` PNG; `drawable/ic_launcher_fg_vector.xml`, `ic_launcher_animated.xml`, `ic_launcher_main.xml`, and `values/styles.xml` also contain or reference the inherited AndroidIDE mark. The app manifest uses `@mipmap/ic_launcher`, and `AppTheme.Splash` uses `@drawable/ic_launcher_animated`.
- A source inspection of `core/resources/src/main/res/values/styles.xml` found the literal stray text `XZ` immediately after the closing `AppTheme.Splash` style tag (`</style>XZ`). This is invalid XML content and must be removed as part of the splash-resource repair. Do not run a build to test it yet; static source correction only.
- Phase 2 visual rebrand can now use a small vector MStudio monogram shared by adaptive launcher icon and splash, with dark background/contrasting strokes. Preserve GPL headers/attribution where present; do not delete upstream source attribution.


### Phase 2 discovery — translated resources also expose upstream identity (2026-10-12)

- A recursive source-tree query found 12 translated `values-*/strings.xml` files in addition to the default English resource. Several still override help text, About footer, unsupported-device text, first-build text, notification labels and SDK-install links with AndroidIDE branding or upstream Telegram/email/docs claims.
- Complete Phase 2 by aligning translated product names and links with MStudio. Preserve the localized wording where practical; update help copy and Telegram/email labels to GitHub Issues/repository wording, and replace upstream docs URLs with the verified MStudio README/issues. The contributor-credit string must continue to clearly credit upstream AndroidIDE rather than accidentally relabel upstream contributors as MStudio contributors.


### Phase 3 localization requirement — runtime recovery dialog (2026-10-12)

- The new runtime failure/retry dialog strings are currently defined in the default English resource only. Add matching translations to the maintained locale resource files (at minimum Arabic, plus all currently maintained locales) so runtime recovery does not unexpectedly switch localized users to English. Preserve RTL behavior and resource keys.


### Phase 2 final UI sweep — shared terminal errors and About icon (2026-10-12)

- The shared Termux-derived resource files `values/termux_app_strings.xml` and `values/termux_shared_strings.xml` still label runtime/setup errors as AndroidIDE; these are visible to users and must use MStudio wording while preserving upstream license headers and package/path identifiers.
- `core/app/src/main/res/layout/layout_about_header.xml` uses the old `@mipmap/ic_launcher_foreground` PNG, even though the launcher and onboarding greeting now use the MStudio vector. Change the About header to `@drawable/mstudio_launcher_foreground` so About does not show the old logo.
- The onboarding greeting references `@drawable/ic_launcher_fg_vector`, which has been updated to the MStudio mark. Preserve the package namespace/authorities; this sweep is branding only.


### Phase 2 destination-label consistency — 2026-10-12

- The About screen's `about_option_website` label still says “Website” while its verified destination is the MStudio GitHub repository (there is no separate MStudio website configured). Rename that label to “GitHub Repository” and translate it in maintained locales where the key exists, so the action does not imply an unpublished website.


### Phase 2 review follow-up — translated About footer consistency (2026-10-12)

- Latest locale sweep confirms the About link label is now translated as a GitHub repository in several locales, but at least Arabic (`values-ar-rSA/strings.xml`) labels the product as “Android” in `about_footer`, and Spanish (`values-es-rES/strings.xml`) still says “Android IDE”. Align these visible product-name strings to `MStudio` while preserving each locale’s existing `%1$s` / `%2$s` format arguments.
- Keep this a narrow branding correction; do not change package namespace, upstream license notices, or attribution. Re-fetch and verify every edited file after its commit. Continue Phase 2’s remaining generated `BuildInfo` repository metadata and complete source/locale audit before declaring the phase complete.


## Execution correction — Phase 2 metadata and Phase 3 readiness UI (2026-10-12)

- Rechecked the live source instead of relying on the earlier discovery note: `composite-builds/build-logic/common/src/main/java/dev/mutwakil/androidide/build/config/ProjectConfig.kt` already sets `REPO_OWNER = "majd7772233-a11y"`, `REPO_NAME = "MStudio"`, and `PROJECT_SITE` to the MStudio GitHub repository. The earlier statement that these constants still point to AndroidIDE is stale and is superseded by the live source. The generated BuildInfo pipeline reads these constants from `utilities/build-info/build.gradle.kts`; still inspect its consumers, but do not change already-correct values.
- The live `MainActivity.kt` already registers a `ToolsManager.InitResult` listener, defers results until RESUMED, displays a retryable setup dialog only for completed failures, retries through `initWithResult`, and removes the listener/dismisses the dialog in `onDestroy`. `ToolsManager` already serializes supported-ABI initialization and publishes per-component results. Therefore this integration is implemented in source, not yet proven on device; remaining Phase 3 work is a careful source audit of failure semantics, localized user-facing details, initialization races/cleanup classification, and later device validation.
- Localized About-footer correction committed: Arabic `values-ar-rSA/strings.xml` commit `7d0651d489b9750f825b5e977b8201276ae689f4`; Spanish `values-es-rES/strings.xml` commit `43799c4ca889988a2a53b7363aa7614c26665fe6`. Both preserve `%1$s` and `%2$s` while displaying MStudio as the product name.
- Planning/doc commits for this follow-up: roadmap `47495f5921ee9a34a356727c6019cb53b916bc41`; GPT memory `7f95899093c1b61fea37175029273cbeacc0bdc1`.
- No build, test task, workflow dispatch, or Actions run was launched.
