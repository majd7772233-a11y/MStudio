# GPT.md — MStudio Project Memory

> Persistent engineering handoff for the assistant working on MStudio. This file is for continuity, not a substitute for reading live source.
>
> Before every substantive task: fetch latest HEAD of the active branch, read this file and MSTUDIO_ROADMAP.md, inspect relevant full files/callers/tests, and append findings to this file after meaningful work. Never delete historical notes; append corrections when evidence changes an earlier finding.

## 1. Product goal and repositories

MStudio aims to become a self-contained Android IDE for creating, editing, building, running and maintaining real Android apps on phones. A beginner should install MStudio, complete guided setup, create/open a project, edit Java/XML, sync Gradle, build, understand errors and install the APK without a separate Termux installation or typing terminal commands.

- **Active product:** https://github.com/majd7772233-a11y/MStudio — work on branch `dev` unless user chooses otherwise.
- **Foundation:** https://github.com/AndroidIDE-Dev/AndroidIDE — selected AndroidIDE fork.
- **Feature source:** https://github.com/majd7772233-a11y/MStudio-NIDE — fork of https://github.com/tranleduy2000/javaide.

**Decision:** AndroidIDE is the product foundation; NIDE is a selective feature source. Do not merge both repositories wholesale, and do not replace actual Gradle Tooling API with NIDE's custom builder/regex parser. GitHub Actions cloud build is optional later; local on-device build is the current priority. See CLOUD_BUILD_PLAN.md.

## 2. Initial source baseline — verify current HEAD on each task

Initial inspection observed:
- Main repo, branch `dev`, commit `e4dc15adf1c670edcfce12008e822c02b0d9cb64`, dated 2026-08-23, message “update README.md”.
- Initial tree SHA `78a916de8481687d79843cf5e0941cba82b62466`.
- NIDE master HEAD `fddee3ec2b0de9501b0d01bac4afeb86146d0c42`, dated 2018-06-15, message “Lib java decompiler”.
- Both repositories use GPL-3.0 at inspected baseline; check bundled tools/dependencies and notices before redistribution.
- No Actions runs or Releases were visible in MStudio fork during the initial check. This is a historical API observation, not proof code cannot build.
- Inherited primary workflow ignores Markdown-only changes, so docs commits may not trigger CI.

The documentation commit changes main HEAD. Never report the old source SHA as latest after that commit.

## 3. Source-derived configuration and risks

- `gradle/wrapper/gradle-wrapper.properties` uses Gradle 8.14.4 to build MStudio itself.
- `.github/workflows/build.yml` configures JDK 21, recursive submodule checkout, debug/release `core/app` builds, ARM64/ARMv7 artifact uploads and Tooling API tests. x86_64 upload sections are commented.
- `.github/workflows/tooling-api.yml` was manual-dispatch in inspected source and builds `tooling:impl:jar` using JDK 17; this is not proof the full APK builds.
- `gradle/gradle-daemon-jvm.properties` selects JDK 21 for root daemon.
- `gradle/libs.versions.toml` at inspection included AGP 8.13.2, AGP tooling 9.3.1, Gradle tooling 9.7.0, Kotlin 2.3.21 and editor 0.23.7. Re-read live catalog before changes.
- `BuildConfig.kt` under `composite-builds/build-logic/common` declares INTERNAL_NAME `AndroidIDE-Dev`, PACKAGE_NAME `dev.mutwakil.androidide`, compile SDK 36, min SDK 26, target SDK 28, Java source/target 17 and NDK 28.2.13676358.
- `settings.gradle.kts` still names root project AndroidIDE; README still uses AndroidIDE Dev identity.
- AndroidManifest has hard-coded old namespaced permissions/actions/provider authorities. Changing central package constant will not update all literals.
- `core/app/dev.keystore` is tracked in inspected source and `gradle.properties` contains IDE_SIGNING properties. Audit actual use before release; never ship inherited development signing identity as MStudio's public release key.
- Root `gradle.properties` requests a 5096 MB Gradle heap and a 4096 MB Kotlin daemon heap. These are not a proven on-device budget.

## 4. Local Gradle architecture — preserve and verify

### GradleBuildService
Source: `core/app/src/main/java/dev/mutwakil/androidide/services/builder/GradleBuildService.kt`.

This foreground Android service owns build state, notifications, wrapper checks/repair, progress/output/result callbacks and cancellation. It checks `gradlew`, wrapper JAR and `gradle-wrapper.properties` and can extract wrapper assets if missing. Build args add the IDE init script and `android.aapt2FromMavenOverride` pointing to custom AAPT2, since Maven's normal AAPT2 binary isn't guaranteed to run in Android. Optional arguments include stacktrace/info/debug, scan, warning mode, cache and offline.

`dispatchBuildResult(result,isSuccess)` currently requests indexing refresh but doesn't use result/success in the inspected body. Trace callers and all UI event paths before deciding whether this is a defect.

### Tooling process and RPC
`ToolingServerRunner.kt` executes `Environment.JAVA` with `Environment.TOOLING_API_JAR`, connects process input/output to the protocol and handles stderr separately. `tooling/api` includes messages/interfaces and `ToolingApiLauncher`; `tooling/impl` performs actual Gradle Tooling API operations and model building. Preserve this as the true Gradle path.

### Runtime and tool setup
`Environment.java` creates private paths under app files: `usr`, `home`, `.androidide`, `.gradle`, Android SDK, temp/bin/lib, tooling JAR and custom AAPT2. Runtime Java is under `usr/lib/jvm/java-21-openjdk`; SDK under `home/android-sdk`; tooling JAR under `home/.androidide/tooling-api`; AAPT2 under `home/.androidide/aapt2`; init script under `home/.androidide/init`. Projects currently use external `AndroidIDEProjects` through a storage helper.

`ToolsManager.java` loads JDK distributions and extracts the IDE Gradle plugin, AAPT2, tooling JAR, `android.jar`, editor schemes, init script and Kotlin-artifact script. An extraction error can be logged while the completion callback still runs. Validate readiness per tool instead of treating callback completion as overall success.

`JdkUtils.kt` scans `$PREFIX/lib/jvm` and has a TODO about broader discovery. Distinguish root build JDK 21, source Java compatibility 17, managed runtime Java, and each user's wrapper/AGP/JDK requirements.

`SetupAapt2Task.kt` downloads custom AAPT2 and verifies SHA-256. The inspected source listed ARM64/ARMv7 checksums; x86_64 was commented out. Keep custom override until an alternative passes real resource compile/link.

### Project models and UX
- `OnboardingActivity.kt`: first-run device/permission/tool wizard.
- `MainActivity.kt` and `RecentProjectsFragment.kt`: main and project-list/search/filter/sort flows.
- `ProjectValidation.kt`: normal project detection expects an `app` child with Groovy or Kotlin Gradle script, plus plugin-project exception. Test non-app module names, nested/multi-module layouts, composite builds and SAF.
- `subprojects/projects/.../ProjectManagerImpl.kt`: workspace/module mapping, sync and indexing.
- `core/projects/.../GradleProject.kt`: cached project metadata.
- `TemplateRecipeExecutor.kt`: template asset/file copy and generated source writing.
- `tooling/impl/.../AndroidProjectModelBuilder.kt`: Android models/DSL/variants/dependencies; `getClassesJar` has a product-flavor TODO.
- `ApkInstaller.kt` validates APK and uses PackageInstaller session/fallback; Android may require user confirmation.
- Modules include editor, Java/Kotlin/XML LSP, indexing, UI designer/inflater, Compose preview, Git and logging. Presence is not proof of feature completeness.

## 5. NIDE facts relevant to ports

NIDE baseline from 2018 uses Gradle 4.4, AGP 3.1.3, compile SDK 27 and Build Tools 27.0.3. Its Gradle templates explicitly warn NIDE itself does not support Gradle-based builds.

`AndroidAppBuilder.java` uses a fixed chain: clean, GenerateBuildConfig, ProcessAndroidResource, CompileJava, Dex, PackageApk and SignApk. `CompileAidlTask` and `MergeAssetTask` return true after TODOs; `MergeManifestTask` prints “Not impalement yet” and returns true; `SignApkTask.signRelease` is TODO. `FastGradleParser.java` lives under AOSP Gradle test sources and regex-parses only a few literals.

NIDE `CompileJavaTask` uses ECJ 4.6.1, Java 1.7 defaults and `-proc:none`. Old `dx` isn't an AGP substitute. NIDE `JavaFormatter` uses Google Java Format Google/AOSP style; MStudio already builds google-java-format source, so trace actual wiring before a port. Autocomplete should be a behavior/test reference, not a competing engine over MStudio LSP/indexing. Decompiler, standalone Java/JAR workflow and sample projects remain candidates.

## 6. Risk register

1. **CI publishing:** inspected build.yml permits successful non-main/non-PR snapshot publishing. Gate publishing/release jobs default-off before routine CI.
2. **Signing:** audit tracked dev.keystore, signing configuration, gradle.properties and .nyx.yml.
3. **Namespace:** old authorities/permissions mean application ID change requires full reference audit.
4. **ABI:** ARM64/ARMv7 emphasized; x86_64 unverified.
5. **Android version:** min SDK constant 26; lower API requires runtime/dependency tests.
6. **NDK discrepancy:** README says no official NDK support but BuildConfig declares a version; native build unverified.
7. **Memory:** inherited heaps are high for typical phones; profile device.
8. **Untrusted projects:** Gradle scripts can execute code. Avoid exposing unrelated tokens/secrets or silently uploading build scans.
9. **Build status:** initial source review did not demonstrate clean CI or device install.
10. **Licensing:** verify GPL-3.0 and bundled tools/dependencies.

## 7. Rules for future tasks

- Fetch live `dev` HEAD first and again before branch mutation; use expected SHA to avoid overwriting concurrent changes.
- Read relevant full files, callers and tests; distinguish facts from design decisions/unknown runtime behavior.
- Prefer GitHub Actions over a heavy local Android Studio build as the only verification path.
- User patch rules: new file = full contents; few-line edit = complete affected function/section and exact location; many edits in small file = whole file; large widespread change = complete repair functions and exact placements.
- Every actual fix/change gets an English commit message. Verify changed file and live commit afterwards. Never claim build/test/install success without evidence.
- Append notes to bottom of GPT.md after meaningful work; never erase prior notes.
- Local Gradle build first; cloud build later.

## 8. Initial investigation log — append-only

Before the initial documentation commit:
- Inspected main baseline `e4dc15adf1c670edcfce12008e822c02b0d9cb64` and NIDE baseline `fddee3ec2b0de9501b0d01bac4afeb86146d0c42`.
- Inspected wrapper/catalog/settings/CI/manifest/BuildConfig, Environment/ToolsManager/JdkUtils, AAPT2 setup, GradleBuildService/ToolingServerRunner/ToolingApiLauncher, project models/validation, onboarding/main/recent project screens, templates/installer and NIDE templates/builder/tasks/parser/compiler/formatter/cache/README.
- Source review only; no application code changed and successful device build/install was not proven.
- First code task recommended: secure CI publication/signing defaults, then baseline CI and APK smoke test before rebrand or feature port.


## Execution log — 2026-10-12 (Phase 1.1 and 0.2)

- Rechecked latest branch dev and roadmap before acting. Initial HEAD was ccd8b4c9abbcebe8fe72700120af93053e6504da (edit Build.yml); its change removed global Maven credentials but left snapshot publishing ungated.
- Rechecked root Gradle settings/build files, wrapper, version catalog, daemon JVM properties, .gitmodules, both workflow files, app Gradle config, manifest and .nyx.yml. Confirmed wrapper Gradle 8.14.4, JDK 21 daemon, AGP 8.13.2, Kotlin 2.3.21, Tooling API 9.7.0, and the declared logback-android submodule. Remote inspection does not prove the submodule was initialized in a runner.
- Phase 1.2 fix committed as 63ae9aa0d9bac985b5efc6869069826b04af7a97: publish and publish_snapshots now require separate opt-in repository variables, strict AndroidIDE-Dev/AndroidIDE allowlisting, intended branches and successful prerequisite jobs. Maven Central credentials exist only in the specific publishing-step environments, not global workflow env. The MStudio fork cannot enter either publish job even if its variables are enabled.
- Roadmap execution record committed in 656a2d777d0e1f4f06edc296bd1303a41a5a6031; renamed MSTUDIO_ROADMAP to MSTUDIO_ROADMAP.md in commits 237ceb84319534ea618504c9223313765d8e78de and c1c4c640ba63830ac5cf35740ef4eacbc2f7bc0f. This fixes the mismatch with references in this file.
- Re-fetch the live workflow/roadmap and inspect the Actions run for parser/runtime feedback. No successful build, Maven publication or device test is claimed.
- Next roadmap task: Phase 1.3 signing audit (core/app/dev.keystore, core/app/build.gradle.kts, gradle.properties, .nyx.yml, and all signing consumers). Do not expose keystore bytes or secrets in notes.


### Source-state correction — submodule declaration mismatch (2026-10-12)

- `.gitmodules` declares `composite-builds/external/logback-android`, but GitHub's contents endpoint returns a normal directory listing for that path rather than a submodule entry. Treat this as a source-tree/declaration inconsistency to resolve in Phase 1.1/0.4. Do not assert that recursive submodule checkout has been proven. No Actions run was returned by the accessible workflow-run query after the guard commit, so CI parser/build validation remains unverified.


## 2026-10-12 engineering update — signing remediation and MStudio identity kickoff

Latest verified source HEAD before this batch was `def0aae3f60cc037c65e9ae760ad32f11ef099dc` on `dev`. Rechecked the roadmap and live source before editing.

Signing findings:
- `composite-builds/build-logic/common/.../config.kt` points to `signing/androidide.keystore`.
- `SigningConfigPlugin.kt` uses `IDE_SIGNING_ALIAS`, `IDE_SIGNING_STORE_PASS`, and `IDE_SIGNING_KEY_PASS`; it applies its common signing config only if the keystore and all three values exist.
- `SigningKeyUtils.kt` can load the key from `IDE_SIGNING_KEY_BIN` or download it using `IDE_SIGNING_URL` plus auth env/properties. Never log or display key bytes.
- Removed committed placeholder signing defaults and tracked keystores; added ignore rules. Git history remains intact, so the inherited key is treated as compromised and must not be reused. Release signing is not configured or validated yet; do not claim a release artifact is signed.
- Do not run Actions/build during these source phases. Phase 1.4 remains deferred until after deeper code work.

Identity changes:
- Launcher label and `BuildConfig.INTERNAL_NAME` now say `MStudio`; Gradle root project name is `MStudio`.
- Package/namespace/application ID remain unchanged at `dev.mutwakil.androidide`.
- Nyx release metadata now points at `majd7772233-a11y/MStudio` and uses MStudio asset names.
- Phase 2 is still in progress: inspect/update remaining About/help/onboarding strings, icon/splash visuals, README/user-visible references, and inherited attribution before calling the rebrand complete.


Additional Phase 2 update:
- README heading, repo badges, issue links, Actions/artifact/release destinations and contact links now refer to MStudio. It explicitly warns that MStudio is work-in-progress and that inherited AndroidIDE documentation may not match.
- Original GPL attribution and upstream acknowledgements were preserved.
- The current app resources directory does not contain a tracked `mipmap/` or `drawable/` root; the manifest references `@mipmap/ic_launcher`, so icon/splash assets likely come from another generated/external resource path. Trace that path before replacing icons. About screen still uses inherited project metadata and must be audited before Phase 2 is complete.


## Latest execution update — 2026-10-12

- Current app ID is now `com.magd.mstudio` in `core/app/build.gradle.kts`. This is intentionally the only package identity change: keep namespace and implementation packages at `dev.mutwakil.androidide` for now.
- Signing workflow now uses existing GitHub Secrets `MY_KEYSTORE_JKS`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`, mapped to the inherited Gradle plugin's expected environment variable names. The keystore secret is expected to be Base64-encoded. Release builds fail early when a required secret is absent or the encoded keystore is invalid; debug build steps do not receive release signing secrets.
- Automatic push builds on `dev` are paused; explicit workflow dispatch is retained for the later baseline stage. Do not manually run build/Actions yet.
- Phase 3 source change: `ToolsManager` now extracts the Gradle plugin JAR, Tooling API JAR, Kotlin download script, and missing `android.jar` through a temporary file and replace-after-success move, preserving existing destination files when copying fails.
- No build/test/Actions verification has been performed. These changes are committed source work, not verified runtime behavior. Remaining: About/help and icon/splash audit, then AAPT2/color-scheme/readiness/recovery work before baseline verification.


### Phase 3 follow-up

- AAPT2 now uses temporary-file extraction and replace-after-success, validates that the destination is a non-empty file, and reports missing source or failed executable permissions as setup errors.
- Device/build verification remains intentionally deferred. Existing corrupt AAPT2 files and color-scheme directory updates still need dedicated repair handling.


**Important application-ID caveat:** The manifest's documents provider still uses the hard-coded authority `dev.mutwakil.androidide.documents`. Since the current scope changes only `applicationId`, this authority was deliberately not renamed; it may conflict with an installed upstream AndroidIDE. Do not claim side-by-side installation compatibility until the authority is reviewed and both install scenarios are tested.


## 2026-10-12 — roadmap numbering and Phase 2/3 source update

- The roadmap is now numbered sequentially from Phase 1 through Phase 12. The former Phase 0 is Phase 1; former Phase 1 is Phase 2; former Phase 2 is Phase 3; later phases move up by one. Substeps are nested under whole-number phases (for example 3.1–3.5). When continuing work, read the live roadmap first and execute the next two whole phases in order; if a requested change is not planned, add it to the roadmap before implementing it.
- Branch: `dev`. Most recent source changes in this batch:
  - `878fe9176dafb4e130dc80f3a1ca6e55c1c78113`: About screen no longer points to inherited AndroidIDE email/Telegram channels; website opens the MStudio repository.
  - `aedc4306896161848518b56059bc4e59ff38443d`: contribution action links to the existing MStudio repository CONTRIBUTING path.
  - `8e4d4c49e1b50f1149843743e5643c433887430a` and `a4297661e7c9667c6d3291d8f01605e5c01ecc68`: color schemes are staged and swapped after successful extraction; interrupted swaps restore the previous backup.
  - `6f25e854829c29de71c06741341133adfc12268d`: ToolsManager exposes immutable per-component readiness via `InitResult`/`initWithResult`, reports unsupported ABI as a failure, and validates/repairs an invalid Android API stub JAR.
- Runtime limitations still open: existing callers still use the legacy Runnable overload, so per-component status is not yet presented in onboarding/retry UI; AAPT2 now runs its `version` command with a five-second timeout and attempts replacement when unusable; this remains unverified on-device. JDK selection and actual device recovery cases remain unverified.
- Phase 2 remains in progress because launcher icon/splash resource origin and inherited onboarding/help strings need a full audit. The About links now use verified repository destinations. Preserve GPL/upstream attribution.
- Phase 3 remains in progress until readiness reaches user-facing setup/retry flows, AAPT2 execution validation is implemented, and setup recovery is tested. Source edits alone are not proof of runtime behavior.
- Current application ID: `com.magd.mstudio`. Keep `namespace` and implementation package at `dev.mutwakil.androidide` unless a separate planned migration authorizes more. DocumentsProvider authority `dev.mutwakil.androidide.documents` is still a side-by-side installation risk; do not claim compatibility without installation tests.
- Do not launch builds, tests, workflow dispatches or GitHub Actions during source phases. Phase 1 baseline CI and device smoke testing remain deferred until the planned verification point. Signing secrets remain `MY_KEYSTORE_JKS`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`; never print their values.

- Follow-up commit `1b83bc5c3f4f128512aa9cbeabc6b9f8503b1b1a` adds the AAPT2 invocation/version check with timeout and replacement behavior. Architecture documentation was synchronized in `e989b825b42febe3dc552e91e42bf477262ef9d4` and `e8ec0fd60274034db519fc071b1c9b371e23d9c1`; roadmap update `c5004b63ca01093a565491802f176e932ef0ca15` records this status. Do not mark Phase 3 complete until supported-device behavior and recovery are verified.

- Commit `4618270d1497c711d95dcf3f845ebe5478711af2` adds separate JDK-distribution discovery and managed `java -version` readiness checks with a five-second timeout. Roadmap was updated in `37de0af4f243daca6ad87dff2a8c441230d93bac`, and architecture map in `d2cb9a3f1d1fdf121e7a92273f02fd0e4824251b`. Still no build/device run; verify behavior later under the roadmap's explicit test gate.


## 2026-10-12 continuation checkpoint — roadmap correction before further code

- Corrected roadmap section labels in commit `173f104920432abdff10e5350d3eed06f3f15a13`. Product definition, source register, evidence checklist and execution records are sections, not implementation phases. Main phases remain Phase 1–12, with substeps nested under each phase. The earlier note claiming the former “Phase 0” globally became Phase 1 was inaccurate; it has been explicitly corrected rather than perpetuated.
- Active work remains Phase 2 (identity/user-facing rebrand) followed by Phase 3 (runtime/setup repair). Per the user's sequencing requirement, do not run builds, tests, Actions or workflow dispatch until source work reaches the planned verification gate. Phase 1 baseline CI/device checks remain deferred.
- Live source review found additional Phase 2 leaks: `core/common/.../BaseApplication.java` still contains AndroidIDE Telegram, docs, sponsor and email URLs/methods; `core/resources/.../values/strings.xml` still has AndroidIDE help/About copy (`msg_need_help`, `about_footer`, `about_subtitle`, and old Telegram labels). The app module overrides `app_name` to MStudio, but the common resource definition remains AndroidIDE-Dev and should be consistent too. Replace/remove only destinations with verified MStudio repository paths; do not invent a MStudio email, website or social account. Inspect callers before changing public methods.
- `AboutActivity.kt` uses the MStudio repository URL and a `CONTRIBUTING.md` URL, but the latter's existence should be verified before declaring it complete. The app manifest points to `@mipmap/ic_launcher`; no resource directory listing or asset-generation path has yet proven where the actual icon/splash originates. Trace `AndroidIDEAssetsPlugin` and generated assets before replacing visuals.
- Phase 3 source review: `BaseApplication.onCreate()` calls `ToolsManager.init(this, null)` only when not running on a JVM; `ToolsManager.initWithResult` currently reports asynchronously through `CompletableFuture` and callback execution occurs on the completing thread. Existing app startup ignores the readiness result, so a user-facing retry/status UI needs careful lifecycle/main-thread design. `core/app` applies a custom desugaring plugin, while min SDK is 26; the `java.util.function.Consumer` API is therefore not an immediate min-SDK concern, but actual build validation remains deferred.
- Runtime code risks to inspect before UI integration: repeated/concurrent init could collide on deterministic `.tmp`/`.bak` paths (especially color schemes); the 5-second process checks need device confirmation; managed Java discovery currently proves at least one distribution was found but does not prove it matches the managed Java executable. Do not label any of these runtime paths verified until tests/device evidence exist.
- Latest roadmap correction SHA: `173f104920432abdff10e5350d3eed06f3f15a13`. This checkpoint is source/document review only; no build, test or Actions run occurred.


### Phase 2 additional source evidence — 2026-10-12

- Roadmap scope was updated before implementing this finding in commit `5f23d1883414a1234c4898ffba689b3de123c44b`.
- `CONTRIBUTING.md` exists (so the About link itself is not a 404), but its contents are inherited AndroidIDE guidance, including upstream docs and Telegram group. Do not expose it as MStudio's contribution guide until adapted to this fork.
- `BaseApplication.java` still exposes upstream Telegram group/channel, `docs.androidide.com`, donation and `contact@androidide.com` routes through public methods/constants. Replace misleading user-facing routes with verified MStudio repository/issues or remove unsupported actions; don't invent a MStudio email, website, social account or donation destination. Keep generic Git commit author email preferences untouched.
- Shared strings still say AndroidIDE-Dev/AndroidIDE and direct users to upstream docs in SDK-install instructions, even though `core/app` overrides the launcher label to MStudio. Fix the shared strings and base app_name too so non-app consumers and About/help UI are consistent.
- `OnboardingActivity.checkToolsIsInstalled()` checks installed JDK distributions and `Environment.ANDROID_HOME`, while `BaseApplication.onCreate()` separately invokes `ToolsManager.init(this, null)`. These are distinct setup layers; don't use the runtime extraction result as a substitute for the existing SDK/JDK setup wizard.


### Phase 2 — generated repository metadata finding (2026-10-12)

- Roadmap was updated first in commit `9c6860e666d235d231a40dc9f6e6668faf6dd682`.
- `utilities/build-info/build.gradle.kts` generates `BuildInfo.java` using `composite-builds/build-logic/common/src/main/java/dev/mutwakil/androidide/build/config/ProjectConfig.kt`. That live config still says `REPO_OWNER = AndroidIDE-Dev`, `REPO_NAME = AndroidIDE`, and `PROJECT_SITE = https://m.androidide.com`. This can make generated `BuildInfo.REPO_URL` and `PROJECT_SITE` send users to upstream (including the changelog action in `IDEApplication`). It is a concrete Phase 2 bug and must be corrected at its source, while leaving Maven group IDs and GPL/upstream attribution unchanged.
- `GitHubContributors.kt` uses `GITHUB_API_REPO_URL` from a separate contributor service; audit its actual base URL before deciding whether About contributors should represent upstream or MStudio. Do not change contributor API URLs blindly without tracing the defining constant and current product intent.


### Phase 3 implementation plan — main-screen runtime retry (2026-10-12)

- Roadmap was amended first in commit `71167cad06099b011bec363c1ad6a557203430ec`.
- `ToolsManager` status is about embedded runtime extraction/validation (JDK distribution discovery, managed Java, AAPT2, tooling JAR, android.jar, schemes and scripts). `OnboardingActivity` separately checks the installed JDK distribution and `Environment.ANDROID_HOME` and can launch terminal setup. Keep these distinct; do not claim the existing wizard fully represents `ToolsManager` readiness.
- Planned UX: expose completed-vs-pending status, observe readiness with Activity lifecycle cleanup, show a retryable main-screen dialog only for completed failures, and let “Later” dismiss without pretending setup succeeded. Keep the existing `init(..., Runnable)` callback thread behavior stable.
- Prevent concurrent initialization/retries because extraction uses deterministic `.tmp`/`.bak` paths. Coalesce callbacks into one initialization and notify UI observers on the main thread. No build/test/Actions until the later verification gate.


### Phase 2 visual asset correction — 2026-10-12

- Roadmap updated first in commit `913844ebb956533e789f8c701f7478cce8b0ad2e`.
- Launcher/splash assets are tracked in `core/resources/src/main/res`, not the app module's `res` directory. `mipmap-anydpi-v26/ic_launcher.xml` uses the inherited PNG foreground; `drawable/ic_launcher_fg_vector.xml`, `drawable/ic_launcher_animated.xml`, `drawable/ic_launcher_main.xml` and `values/styles.xml` also contain/reference the inherited AndroidIDE logo. Manifest uses `@mipmap/ic_launcher`; `AppTheme.Splash` uses `@drawable/ic_launcher_animated`.
- `core/resources/src/main/res/values/styles.xml` has a literal `XZ` after the closing splash style tag. This is malformed resource XML; remove it as a source correction, but do not run builds until the planned verification gate.
- Visual implementation plan: create a small vector MStudio monogram suitable for the adaptive icon and splash, switch the adaptive icon and splash to that resource, and use a dark splash background with contrasting mark. Raster icon PNGs may remain as legacy fallback assets unless a safe binary-edit path is available; document that distinction rather than claiming every icon density was replaced.


### Phase 2 translated-resource audit — 2026-10-12

- Roadmap amended first in commit `c57f243a66526e02104ee7d68f7e75186b89c2a0`.
- A recursive Git tree query found 12 locale-specific `values-*/strings.xml` files. They override many of the English resources, and multiple locales still mention AndroidIDE, upstream Telegram/email support, and docs.androidide.com in user-facing help/setup strings. Editing only the default English resource would therefore leave false destinations visible to users in those locales.
- Update translated product labels and help text, replace upstream docs URLs with the MStudio README/issues, and change Telegram/email UI labels to GitHub issue/repository destinations. Preserve localized language where practical. Keep contributor credit explicit: MStudio is derived from AndroidIDE, and upstream contributors must not be mislabeled as MStudio contributors.


### Phase 3 localization acceptance — 2026-10-12

- Roadmap updated first in commit `2aae1d03141ddb4a803add1ea87a11d551f82886`.
- The new runtime recovery dialog uses four resource keys (`runtime_setup_failed_title`, `runtime_setup_failed_message`, `runtime_setup_retry`, `runtime_setup_later`) currently present in default English. Add translated values to the maintained locale string files, especially Arabic, so this new UI respects the app's existing localization/RTL support.


### Phase 2 final UI sweep — 2026-10-12

- Roadmap amended before this final sweep in commit `dcbe2beb017473f6764f78f1ce01d6ee0765a8f3`.
- `core/resources/src/main/res/values/termux_app_strings.xml` and `termux_shared_strings.xml` contain visible AndroidIDE error/setup copy. Update only the string values to MStudio; retain upstream license comments and all internal package/path strings such as `dev.mutwakil.androidide` and `.androidide`.
- `core/app/src/main/res/layout/layout_about_header.xml` directly referenced the inherited raster `@mipmap/ic_launcher_foreground`. Switch it to `@drawable/mstudio_launcher_foreground`; `fragment_onboarding_greeting.xml` already uses `@drawable/ic_launcher_fg_vector`, which was rebranded.
- `androidide_header.xml` and `androidide_header_compact.xml` are legacy wide vector resources; no use has yet been located in the inspected onboarding/About layouts. Do not rewrite/remove them without checking all references; the active manifest icon, onboarding greeting, About header and splash have known MStudio vector paths.


### Phase 2 About destination label — 2026-10-12

- Roadmap updated first in commit `0608fc5b8c04f5bef8d4fd99276c436724bff2a0`.
- `AboutActivity.kt` opens `PROJECT_URL` at the MStudio GitHub repository, but the resource key `about_option_website` still labels it “Website”. MStudio has no separate website configured in source; change the label to “GitHub Repository” and update translated values where present.


### Phase 2 follow-up — About footer localization audit (2026-10-12)

- Roadmap updated first in commit pending from this planning change. Live inspection found `core/resources/src/main/res/values-ar-rSA/strings.xml` uses `أندرويد` in `about_footer`, and `values-es-rES/strings.xml` uses `Android IDE`, even though the product identity is MStudio. Correct these visible strings to MStudio without changing the existing format placeholders.
- Phase 2 is still in progress: generated `BuildInfo` repository metadata and the broader inherited user-facing reference audit remain outstanding. No build/test/Actions until the explicit verification gate.


### Phase 2/3 live-source correction — 2026-10-12

- Roadmap correction added in commit `pending` (see latest `MSTUDIO_ROADMAP.md`).
- Current live `ProjectConfig.kt` is at `composite-builds/build-logic/common/src/main/java/dev/mutwakil/androidide/build/config/ProjectConfig.kt`; it already sets the MStudio owner, repository name, URL, and project site. This supersedes the older note saying generated build metadata still points upstream. Do not rewrite correct constants; trace BuildInfo consumers if that remains relevant.
- `MainActivity.kt` already observes runtime setup results with lifecycle-safe cleanup and a retry dialog. `ToolsManager` already returns per-component results and guards supported-ABI initialization against duplicate concurrent runs. These are source implementations, not device-tested behavior.
- Arabic and Spanish About-footer branding fixed in commits `7d0651d489b9750f825b5e977b8201276ae689f4` and `43799c4ca889988a2a53b7363aa7614c26665fe6`, retaining format placeholders.
- No builds/tests/Actions until the roadmap verification gate. Continue auditing remaining locales and Phase 3 semantics before marking either phase complete.


### Phase 2 locale audit — device warning and contribute copy (2026-10-12)

- Roadmap updated first in commit `9f1bb2617a24894350a80d829a7283c448336149`.
- Additional source audit found French unsupported-device copy still says AndroidIDE and Turkish contribution copy still says Android IDE. These are product-facing strings, unlike legitimate upstream attribution. Correct them to MStudio while preserving the upstream contributor credit and all internal package/path references.


### Phase 2 localized copy corrections — 2026-10-12

- Roadmap execution record updated first in commit `a647dcba38210e364b2bbb890025e7484f95c85d`.
- Arabic and Spanish About footers corrected to MStudio in commits `7d0651d489b9750f825b5e977b8201276ae689f4` and `43799c4ca889988a2a53b7363aa7614c26665fe6`.
- French unsupported-device message corrected in `a4525ca4c5ca216114e343bfe189f11fbfa9c81c`; Turkish contribution prompt corrected in `72e25fff369bf574ae877aa60e7a91a66a8d3174`.
- Kept AndroidIDE references where they are accurate upstream attribution and left internal package/path identifiers unchanged. Romanian and Bengali About footers already use MStudio.
- No build, test, workflow dispatch or Actions run. The Phase 2 sweep remains source-level and not exhaustive until the remaining full-source scan and verification gate.


### Phase 2 README audit — stale upstream destinations (2026-10-12)

- Roadmap updated first in commit `49e674e70d110e9049eebe8a3b9251f211882860`.
- The live README still links AndroidIDE docs and Telegram for MStudio support, uses the inherited F-Droid app ID `dev.mutwakil.androidide` despite the MStudio applicationId `com.magd.mstudio`, calls installation sources AndroidIDE, and points at the upstream AndroidIDE Crowdin project.
- Correct README destinations and product names without claiming any release artifact or F-Droid package is verified. Keep AndroidIDE clearly credited as upstream foundation; only link to upstream resources in that context. No builds/Actions before the verification gate.


### Phase 2 README correction and source-state reconciliation — 2026-10-12

- Roadmap updated first in commit `e6bd65fd5384d3b75d53fb14b500d8920d8c8208`.
- README commit `1aac325cef97d2ab0911f84660e246f947879606` removes misleading AndroidIDE docs/support, F-Droid package, upstream Crowdin and Telegram destinations. It explains that Actions artifacts are not verified releases and routes MStudio support to the MStudio issue tracker.
- README follow-up commits `b09dfc089daeff9fcbae70c5f2448c100b15e520` and `ded14a43e57c65631e79dfc4715b08229c6051e0` clean the limitations/acknowledgement section and fix intro markup.
- Current source confirms the root project name and generated repository metadata already target MStudio, CONTRIBUTING.md is fork-adapted, active launcher/splash resources use the MStudio vector, the old `XZ` is absent from styles.xml, and shared terminal error strings already say MStudio. Earlier roadmap discoveries that contradict this live state are historical, not current tasks.
- Preserve upstream AndroidIDE attribution and GPL notice. No build/test/Actions until the explicit verification gate.


### Phase 2 About icon consistency — 2026-10-12

- Roadmap updated first in commit `9c25cd3fb5dbda89e674485c335e91013218aa6b`.
- `AboutActivity.kt` labels the first action “GitHub Repository” and opens the MStudio repo, but still uses `R.drawable.ic_website`. The shared `R.drawable.ic_github` is confirmed to exist at `core/resources/src/main/res/drawable/ic_github.xml`; switch only that icon reference.


### Phase 2 repository action icon and README commits — 2026-10-12

- Roadmap updated first in commit `60eca546064e88b87bcb889ec6e8088a3390f123`.
- `AboutActivity.kt` now uses `R.drawable.ic_github` for the GitHub Repository destination; commit `98bb103c9b1e1f5b800e15b41253e0f0f1da871b`.
- README branding/destination updates: `1aac325cef97d2ab0911f84660e246f947879606`, `b09dfc089daeff9fcbae70c5f2448c100b15e520`, `ded14a43e57c65631e79dfc4715b08229c6051e0`.
- Locale corrections: Arabic `7d0651d489b9750f825b5e977b8201276ae689f4`; Spanish `43799c4ca889988a2a53b7363aa7614c26665fe6`; French `a4525ca4c5ca216114e343bfe189f11fbfa9c81c`; Turkish `72e25fff369bf574ae877aa60e7a91a66a8d3174`.
- No builds/tests/Actions. Phase 2 remains source-level until full audit and later verification.


### Roadmap stale-finding reconciliation — 2026-10-12

- Current roadmap wording now distinguishes historical findings from live state. Root name and generated repository metadata already target MStudio; active launcher/splash/About branding and README/support destinations have been corrected; the stray splash XML text is absent; and runtime retry UI is already wired into MainActivity.
- Remaining work is source audit and verification, not repeating completed fixes. Do not mark Phase 2 or Phase 3 fully complete until the remaining audit and later verification gate are satisfied.


### Phase 2 README icon mismatch — 2026-10-12

- Roadmap updated first in commit `fa4d45ebeae789c0275326524c1236e51a4f4b86`.
- Visual inspection of `images/icon.png` found the inherited AndroidIDE robot/braces logo. The actual launcher/splash use the MStudio M monogram. Add a repository-owned SVG that mirrors the active MStudio vector and point README at it; leave the old PNG untouched unless all its uses are audited.


### Phase 2 README artwork correction — 2026-10-12

- Roadmap updated first in commit `7ad2662913bb5e2842aaaa918c8bb39092c54f97`.
- Added `images/mstudio-icon.svg` matching the active MStudio monogram/brand colors (commit `d0ff881b1ff1bd76d6e8d5dc67542d62a56f4d1f`) and changed the README image to it (commit `7f0e379bb272506ef9729004b5c8d7518649b049`).
- The legacy `images/icon.png` remains untouched pending a full reference audit; only the README display is confirmed rebranded.


### Phase 2 README feature-claim qualification — 2026-10-12

- Roadmap updated first in commit `45d26700014658d7826c111d7b0d14650616ce7a`.
- README feature checkboxes describe source features, not proof every flow works on MStudio devices. Add an explicit qualifier directing readers to the roadmap for verified status, without removing the useful feature list.


### Phase 2 post-edit source checks — 2026-10-12

- Roadmap updated first in commit `4149ab3edb9fa7f7e4686c2b12bb1ac8ee9fc71c`.
- README caveat commit: `cfdbf2f24c04ff71ec73a22938fde49c5ac224fc`. The README now says feature checkboxes describe source features, not proof of runtime verification.
- Live refetch confirms outdated AndroidIDE docs/Telegram/Crowdin/F-Droid/package-ID/site destinations are gone, README uses `images/mstudio-icon.svg`, About uses the GitHub icon, and the four targeted locale fixes are present.
- These are source-content checks only, not XML/resource compilation, APK build, installation, or device validation.


### Phase 3 managed Java version check — 2026-10-12

- Roadmap updated first in commit `b9e161a71886f09015a3deb4be6f739c9bde2624`.
- `ToolsManager.isUsableJava()` currently checks that `java -version` exits zero and writes non-empty output, but does not parse the version. Strengthen it to parse the reported major version and require Java 17+; legacy `1.8.x` must parse as major 8 and fail. Preserve timeout/process cleanup. Do not run build/Actions yet.


### Phase 3 managed Java version check implemented — 2026-10-12

- Roadmap updated first in commit `b66fdfb8d82e8333e9f7bf2bc624cef89dd3a5f4`.
- Commit `48d6c4ea092d93953952a72081ddc3d7a9b75360` changes `isUsableJava()` to parse the version string and require major version >=17; legacy `1.8.x` is interpreted as 8 and rejected. The five-second timeout and cleanup remain.
- A simple pattern sanity check covered OpenJDK 21, Java 17, and Java 8 output strings. This does not prove Java compilation or actual managed-runtime behavior; no build/tests/Actions were run.


### Roadmap numbering history clarified — 2026-10-12

- The old migration paragraph that incorrectly described Phase 0→Phase 1 has been marked as superseded rather than left as an active instruction. The canonical phase structure remains main phases 1–12, with decimal substeps nested under whole-number phases.


### Phase 2 package identity disclosure — 2026-10-12

- Roadmap updated first in commit `96bf84407d2bea328d9d8825674c04698fd9004b`.
- Add an accurate README note that applicationId is `com.magd.mstudio`, implementation namespace remains `dev.mutwakil.androidide`, and the DocumentsProvider authority remains `dev.mutwakil.androidide.documents`. Side-by-side install with upstream AndroidIDE remains unverified and must not be promised.


### Phase 2 package identity caveat documented — 2026-10-12

- Roadmap updated first in commit `663930148f13b5eb5301c02ab0db040ca324fc4a`.
- README commit `611ac5872b8b873d1edfc54dc1535b768291efbc` records `com.magd.mstudio` applicationId, retained `dev.mutwakil.androidide` implementation namespace and `dev.mutwakil.androidide.documents` authority, without promising side-by-side installation. Device compatibility remains unverified.


### Phase 2 localized donation-label mismatch — 2026-10-12

- Roadmap updated first in commit `0e1004d340395877c1f3d4350ea88c828825eeea`.
- Default English `btn_donate` already reads “Project repository”, but 12 translated resources still say Donate/Support while the inherited destination now opens MStudio's repository. Localize “Project repository” in all maintained locales so users aren't misled into expecting a payment/donation flow.


### Phase 2 translated repository-action labels — 2026-10-12

- Roadmap updated first in commit `d1ef8fdd7e4427a1c008ff089fe596a6eab76471`.
- All 12 maintained locale resources now translate `btn_donate` as “Project repository” because the action opens the MStudio repository, not a donation/payment destination. Commit SHAs are recorded in `MSTUDIO_ROADMAP.md`.
- This avoids a false donation promise and keeps the action consistent with the English resource. No builds/tests/Actions were run.


### Phase 3 min-SDK compatibility follow-up — 2026-10-12

- Roadmap updated first in commit `e9b8f14c51680238ecdd42f80de83fd026f0df76`.
- The Java version parser introduced `Files.readString(Path, Charset)`, a Java 11 API that may not be available across min SDK 26 devices. Replace it with Java 8-compatible `FileReader`/reader logic before any build, preserving the parser and timeout.


### Phase 3 min-SDK reader fix — 2026-10-12

- Roadmap updated first in commit `54ce9c9a1d2ed7009d2286d2b2a0f6b20cc35afd`.
- Commit `1c05b4a6f8f30b4e1e4858a2cebbb8bc67f87e0e` replaces `Files.readString` with `FileReader` plus a character buffer, avoiding a Java 11 API assumption on min SDK 26. The Java 17+ version parsing and timeout remain intact.
- Re-fetched the source to confirm the change. No build/API lint/device verification was run.


### Current continuation point — 2026-10-12

- Post-edit fetch verified all 12 maintained locale values of `btn_donate` now mean “Project repository,” matching the actual MStudio GitHub destination. Roadmap continuation note is in commit `57080ddbb83ee4f87df555ab2474f5d1926a16fd`.
- Phase 2 source branding/documentation is substantially updated but not fully audited or device-verified. Phase 3 has per-component readiness and retry UI plus atomic repair and Java 17+ version parsing; remaining work is deeper setup/recovery source audit and later offline/interruption/low-storage/ABI/device validation.
- No build/test/Actions until the roadmap explicitly reaches the verification gate.

### Phase 3 AAPT2 validation hardening — 2026-10-12

- Roadmap-first follow-up was recorded in roadmap commit `493e49c4b462f81256fa9851d0b507148f8dd9f2`; execution details were appended in roadmap commit `e94d9ca15f39fbfba92607febb54fd9f280ac855`.
- `ToolsManager.isUsableAapt2()` no longer treats any zero-exit executable with arbitrary non-empty output as AAPT2. Commit `f47c90ba6b24aecdbc7ffd28a1dc618a1c3ad828` reads the `version` output and requires an AAPT/AAPT2 or “Android Asset Packaging Tool” marker. Timeout, forced process cleanup and temporary-output cleanup remain in place.
- Reviewed `writeInitScript()`: it writes packaged defaults to `init.gradle.bak` but only creates `init.gradle` when missing. Do not change this to overwrite existing scripts until the project has a safe versioning/migration contract; an existing script may contain user edits and the current backup is not a reliable prior-version marker.
- No build, test, workflow dispatch, Actions run or device validation was performed. This is source-level hardening only.

### Phase 2 contributor provenance labels — 2026-10-12

- Roadmap-first finding and scope were recorded in commit `e053c9e0c4c23ab9f0d0934bbcebf443c99099c3`; the completed execution record is in roadmap commit `16dfa85db08ec34c3f3332080e3f99a05b6fa631`.
- `ContributorsActivity` shows GitHub contributors from the MStudio repository, but Crowdin translators from the inherited `crowdin-contributors.json` that credits the upstream AndroidIDE project. Commit series changed both labels across English and all 12 maintained locales: GitHub contributors are explicitly MStudio contributors; Crowdin translators are explicitly from upstream/original AndroidIDE. This preserves credit and avoids claiming a separate MStudio Crowdin project.
- All 13 resource files were re-fetched and both labels confirmed after the edits. This is a content-level check only; XML/resource compilation and the online contributors screen remain unverified.
- Locale commits are recorded in `MSTUDIO_ROADMAP.md` under “Phase 2 contributor provenance labels”.
- No build, test, workflow dispatch, Actions run, or device validation was performed.

### Phase 2 About copy consistency — 2026-10-12

- Roadmap-first task was recorded in commit `cf42fae978e51d85f62a3c9d753b1f9e0e7628bd`; the execution log is in roadmap commit `1b49114021c48e15cf015fc06fca6fd9948a34ff`.
- Localized the About section header from “Socials” to “Project links” because its only current item is the MStudio repository. Reworded the contributors summary to reflect both MStudio GitHub contributors and upstream AndroidIDE translators.
- Updated English and all 12 maintained locale resources; exact commits are listed in `MSTUDIO_ROADMAP.md`. Re-fetched all 13 resource files and verified both strings. No resource build or device UI test was run.
- No build, tests, workflow dispatch, or Actions run was launched.

### Phase 2 core identity spot-check — 2026-10-12

- Roadmap spot-check was recorded in commit `8b88f390c78738ccc9e60a359caf92adb52bad8c`.
- Re-fetched current `BuildConfig.kt`, app manifest and default user-facing resources: app label and internal product name are MStudio; onboarding help links target MStudio's README/issues; About labels now distinguish MStudio repository/contributors from upstream AndroidIDE translation credit.
- Do not rename remaining “AndroidIDE” strings blindly: in inspected core files they are mainly GPL/copyright notices, namespace/provider identifiers, internal resource/style names, or explicit upstream attribution. This is a targeted spot-check, not a complete repository-wide audit or runtime proof. Phase 2 remains in progress.

### Phase 3 bundled JAR readiness checks — 2026-10-12

- Roadmap-first task was recorded in commit `6f8c802c8394854201a7f7e269c715afe354d77b`; execution record is in roadmap commit `0889cb5d44338a00d6891d3af098dcc6e602d7cb`.
- `ToolsManager` now runs the shared `JarFile` validation against the extracted Gradle plugin JAR and Tooling API JAR after atomic copy, and reports component-specific errors if either is malformed. Commit: `0e5c63db1aafc16cf5b51b229297a7730453de19`.
- The generic validator log no longer incorrectly says all invalid JARs are Android API stubs. The existing `android.jar` checks remain.
- Re-fetch/source inspection only; no build, test, JAR execution, Actions, or device validation.


## New-conversation handoff — 2026-10-12 19:16 +03:00

Read `MSTUDIO_ROADMAP.md` first; its latest handoff checkpoint was committed in roadmap commit `b4c9e48cfc84ae946e3c92be0ca49a1bd4eaab34`. Re-fetch both documents and live source from branch `dev` before doing further work.

### Exact latest source change
- Finding recorded roadmap-first in `6f8c802c8394854201a7f7e269c715afe354d77b`.
- `ToolsManager` bundled-JAR validation implementation: `0e5c63db1aafc16cf5b51b229297a7730453de19`.
- Execution note recorded in roadmap commit `0889cb5d44338a00d6891d3af098dcc6e602d7cb`.
- Extracted Gradle plugin JAR and Tooling API JAR now pass through the shared `JarFile` container validator after atomic copy. Component-specific errors are returned through `InitResult`; validator logging no longer incorrectly calls every malformed JAR an Android API stub.
- This is source-level inspection only. **No build, tests, JAR execution, workflow dispatch, GitHub Actions run, or device validation occurred.**

### Current state and unfinished work
- Canonical phase plan is Phase 1–12 with decimal substeps; the earlier Phase 0 renumbering statement was explicitly marked superseded because it was inaccurate. Do not repeat or reapply a global renumber.
- Phase 2 MStudio identity is substantially updated but still in progress. A complete source/string/link/resource sweep and later verification remain; preserve upstream AndroidIDE/GPL credit. `applicationId=com.magd.mstudio`, implementation namespace `dev.mutwakil.androidide`, and DocumentsProvider authority `dev.mutwakil.androidide.documents`; do not promise side-by-side install.
- Phase 3 runtime/setup is in progress. `ToolsManager.initWithResult` has per-component outcomes; retry/init is single-flight; observers use the main thread and Activity teardown removes its listener. Extraction safety covers atomic/staged writes, color-scheme backup recovery, JAR validation, AAPT2 version invocation/marker/timeout, and managed Java version parsing (Java 17+). The managed Java output reader avoids Java 11-only `Files.readString` for min SDK 26 compatibility.
- Existing `init.gradle` is intentionally not overwritten because it may contain user customizations; `init.gradle.bak` is regenerated from packaged defaults and is not a safe previous-version migration marker.
- None of the above has been proven by a successful Android build, lint, device execution, offline setup, interrupted setup, low-storage test, or ABI matrix.
- Phase 1 baseline build/device verification is also not done. Do not launch any build, tests, workflow dispatch or Actions until the roadmap explicitly reaches the verification gate.

### Next steps
1. Fetch live `dev` state, `MSTUDIO_ROADMAP.md`, `GPT.md`, and relevant source files again before edits.
2. Continue Phase 3 source audit: shared JAR validator behavior, all `ToolsManager` callers and result consumers, managed Java/AAPT2 failure recovery, and init-script consumers. Record each newly discovered task in roadmap first, then implement, then update both Markdown continuity files with exact commit IDs and evidence limits.
3. Continue Phase 2 repository-wide branding and user-facing string/link audit without blindly renaming package/authority identifiers or removing GPL/upstream attribution.
4. Keep cloud build deferred until local MVP is verified. Preserve real Gradle Tooling API as the build engine; NIDE is a selective feature source only.

### Non-negotiable constraints
- Do not record secret values. Signing variable names: `MY_KEYSTORE_JKS`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`; `MY_KEYSTORE_JKS` must be Base64-encoded JKS bytes for the inherited decoder. Cloudflare variable names: `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`, `MY_JWT_SECRET`.
- Never recommend `npx wrangler` for the user's Android/Termux architecture.
- User's roadmap-first rule: if a task is not represented in the roadmap, add and prioritize it there before implementing. Update `GPT.md` after meaningful changes. Do not claim verification without actual evidence.


## Execution update — Phase 3 project marker readiness correction (2026-10-12)

- Live `ToolsManager.writeNoMediaFile()` previously logged marker-creation failures and returned normally, causing `runComponent("projects-marker", ...)` to mark the component ready. It also accepted an existing non-file `.nomedia` path.
- Roadmap-first finding: commit `765be7e2e715c4c5f9f4885cfa821d2f9abf8f0d`. Source correction: commit `e9e97429dd559b6469e290658067f24497e95036`. Roadmap execution record: commit `159f2ee101d8bf2a5b6723534d2ba23423c71498`.
- The method now accepts an existing marker only if it is a regular file; unexpected paths are left untouched and reported as failure. Failed creation throws a diagnostic exception so component readiness is accurate.
- Source review only; no build, tests, Actions, or device validation was performed. Continue Phase 2 repository-wide identity/source-reference audit and Phase 3 review of remaining setup component contracts. Do not run verification until the roadmap gate explicitly permits it.


## Phase 2/3 source implementation checkpoint — 2026-10-12

- Latest source batch on `dev`: `5e75a5e527fc97b7ec261402af6233fca460a58b` hardens Gradle init-script writes; `12a654bf218c7c0a1260fb1bddd7e27dc29524a0` makes stale `ideenv` cleanup safe/optional; `20102a96c037639b1cc6e31966f613256b89882a` fixes localized setup guidance across all 12 maintained non-English resource files.
- `ToolsManager.writeInitScript()` writes missing scripts via synced sibling temporary files and replacement-after-success. Existing non-file/empty scripts fail visibly without being overwritten; valid user scripts remain intact. Refreshing the packaged-default `.bak` is optional. Stale cleanup now leaves unexpected non-file paths untouched and cannot make otherwise usable runtime components fail solely because optional cleanup did not finish.
- Phase 2 source audit: live `ProjectConfig.kt` and `settings.gradle.kts` point to MStudio; About/README/onboarding destinations point to the MStudio repository, contribution guide and Issues. All 13 `msg_install_tools` strings now have MStudio-specific wording and exactly one README link plus one GitHub Issues link; malformed/duplicate documentation anchors were removed. Several stale English translations in Arabic/German/Spanish/French/Romanian device/setup/contribution strings were localized. GPL notices and upstream AndroidIDE translator credit were preserved.
- `applicationId=com.magd.mstudio`; namespace/packages remain `dev.mutwakil.androidide`; DocumentsProvider authority remains `dev.mutwakil.androidide.documents`. Side-by-side install remains unverified.
- The active adaptive launcher is the MStudio vector (`mipmap-anydpi-v26/ic_launcher.xml` -> `mstudio_launcher_foreground` with MStudio brand background), and `MIN_SDK=26`. Inherited density fallback PNGs and Fastlane/store artwork were not changed; their update is now explicitly scheduled in Phase 12 before release, not misreported as complete.
- The roadmap records source-level Phase 2 acceptance and Phase 3 implementation, but the full runtime acceptance is **not verified**. No build, tests, workflow dispatch, Actions or device smoke test was run. The primary workflow is manually dispatched on this branch, and the available GitHub connection does not expose a workflow-dispatch write operation. Phase 1.4 baseline CI and Phase 1.5 device smoke test remain required before Phase 3 is fully verified or Phase 4 is unblocked.
- Roadmap checkpoint: `de5a276985ea59446dddfcb5ad7fd6d7c2346e05`. Do not claim runtime success based on source checks alone. Keep the real Gradle Tooling API path and do not start cloud-build/NIDE work.


### Raster asset scope correction — 2026-10-12

- Roadmap correction commit: `da15e502e79a81c25af415e8dbe76d46f4549ef4`. The active adaptive launcher uses MStudio vector resources and `MIN_SDK=26`; density PNGs are unsupported pre-26 fallbacks, while Fastlane/Play Store PNGs are release metadata. These files remain unchanged and are assigned to Phase 12 before publication. Do not describe all raster artwork as rebranded.
- Current verified roadmap revision is `da15e502e79a81c25af415e8dbe76d46f4549ef4`; source-level phase work is documented, but Phase 1.4 CI and Phase 1.5 device smoke test are still required for full runtime acceptance.


## Execution update — Tooling API process exit callback (2026-10-13)

- Live `dev` source review found `ToolingServerRunner` waited for the managed Java process and logged its exit code but did not invoke its existing `Observer.onServerExited(exitCode)` callback. `GradleBuildService` implements that callback to clear foreground-service state, so the lifecycle signal was disconnected.
- Roadmap-first finding recorded in commit `54dbf798ebb45cce1e88c7d81861d6a3d4c8f00f`. The source correction is commit `48b12cd2972e7c70ff6be0a8c7cef7065e192b59`: the process waiter now captures the `waitFor()` result and notifies the observer when an exit code is available. This is process-exit reporting only, not a build-success signal.
- Roadmap execution record: `d9efa0a0c95f9ceffc22414e5abee9cbe5e66a44`.
- Remaining lifecycle risks: startup failure before the waiter exists, cancellation actually terminating the child process, and concurrent build/service recreation behavior. Continue the Phase 4 source audit while preserving the actual Gradle Tooling API. Do not run builds, tests, Actions or device validation; the user will handle verification after the code batch.


## Phase 4 continuation — wrapper preflight hardening (2026-10-13)

- Roadmap-first finding recorded in `cc80e688359c63498b3c037345e8d9426b985157`. The live `GradleBuildService.isGradleWrapperAvailable` only used `exists()`, allowing empty files, directories, corrupt wrapper archives and missing distribution URLs to bypass repair.
- Source fix: `62b333c644694d033f750a70d0d41e753d9c7aee`. Preflight now checks regular/non-empty wrapper files, readable properties with a non-empty `distributionUrl`, and a wrapper JAR that opens as a non-empty ZIP archive. Roadmap execution record: `c21e44bd6790284650038403ac5087992cff1364`.
- This is source-level only. Validate later that the existing extraction path behaves safely for partial/read-only projects and preserves valid user wrapper files. Do not run builds/tests/Actions/device checks until the user-authorized verification batch.


## Phase 4 continuation — reset dead Tooling process state (2026-10-13)

- Roadmap-first follow-up finding: commit `467a3b4f9c5421094712f0a37e1978156ef9d5f1`. The first exit-callback fix revealed the runner's `isStarted` flag and PID stayed stale after the child JVM exited, while `GradleBuildService` retained its server proxy and started flag.
- `ToolingServerRunner` now resets `isStarted` and `pid` on process exit (commit `4ea24fb29278ec07bd53738e8436685a27ada168`); `GradleBuildService.onServerExited` clears the server proxy and started flag (commit `d5ece4b74fc0d66c03bbb0dd05cbf45ef87fef1d`). Roadmap execution record: `5649445631050df23474f474c691207ce5edc850`.
- Remaining risk: process exit may leave outstanding Tooling API RPC futures or a build marked in progress; verify restart, failure and cancellation flows later. No build/test/Actions/device run was performed.

## Phase 4 source implementation handoff — 2026-10-13

- Roadmap remains the single source of task order; see the latest Phase 4 implementation record in `MSTUDIO_ROADMAP.md`. Work stayed on `dev`; no PNG/raster assets were changed.
- `ToolingServerRunner.kt`: startup failures now clear state, destroy any process spawned before setup finished and call `Observer.onServerStartFailed`. When the process exits, the blocking RPC listener is run interruptibly and cancelled/joined so it cannot hold the runner open forever.
- `GradleBuildService.kt`: pending server RPC futures (metadata, initialize/sync, task execution, cancellation) are tracked and completed exceptionally when the child process exits or startup fails. The service clears server/build state and stops the stderr reader and foreground notification. Wrapper extraction now succeeds only if the strict post-repair preflight passes.
- Build Scan remains an explicit user-selected Gradle option; its preference summary now warns in English and Arabic that `--scan` may upload build data remotely and should be enabled only with explicit agreement.
- Latest implementation commits include `307c981`, `03156b0`, `9d5d65b`, `672cff7`, `b4e056b`, `1738863`, `b61ef8a`, and `2f227a5`; the roadmap implementation record is `31cb1bf`.
- No tests/builds/Actions/device verification were run, as instructed. Phase 4 source fixes are implemented, but runtime acceptance is not proven; keep all evidence checklist items unchecked until the user's later verification batch. Do not claim a verified APK build or installation.


## Mandatory execution style — user instruction, 2026-10-13

- Do not pad responses with philosophy, repeated status narration, or unnecessary explanations. When the user gives a clear actionable instruction, execute it immediately and directly; prioritize implementation over talking about implementation. Ask only when a genuinely blocking ambiguity prevents safe progress.
- Continue to respect the user's explicit constraint for the current MStudio batch: do not run tests, builds, GitHub Actions, or device checks until the user requests that verification batch. Do not claim runtime acceptance without evidence.

## Phase 4 wrapper-repair follow-up — 2026-10-13

- GradleBuildService.doInstallWrapper() now extracts the bundled archive into a staging directory and repairs only missing/invalid gradlew, gradle-wrapper.jar, and gradle-wrapper.properties. Existing valid components are preserved, including a valid project-specific distributionUrl; repaired files are checked before replacement, gradlew is marked executable, and strict wrapper preflight runs afterward. Staging is cleaned in finally.
- Commit: d1f7581017e2f3f4fc14a7384af5c907595fd4ca. This was source-only work; no tests/builds/Actions/device checks were run. Continue auditing Phase 4 source requirements and documentation, without misrepresenting the deferred runtime acceptance as verified.


## Phase 5 handoff — project structure validation (2026-10-13)

- Live branch `dev` was rechecked before the change. The previous source HEAD was `9c92ba516504d690cdaa6f93945d04e664c1f266`; source correction: `52a8c27f385d0a31048f02e6230abb7370f0837f`; roadmap record: `b6044db8a0ec8ea0ed683013532df50ada6f3fef`.
- Updated `core/app/src/main/java/dev/mutwakil/androidide/utils/ProjectValidation.kt` so import validation no longer hardcodes an `app/` module. It accepts a root Gradle build script, or a settings-based multi-module root with a descendant module build script within a bounded search depth; it rejects non-directory selections and build-script paths that are not regular files, and skips hidden/VCS/generated directories during descendant search. The special plugin-project case remains supported.
- This is only source-level structural validation. It does not prove Gradle sync, SAF access, nested/composite project modeling, variants, classpaths, or dependency resolution. Parent-container picker and model-builder work remain open under Phase 5.
- No tests, Gradle builds, Actions runs, or device checks were run. Keep all evidence-dependent checklist items unchecked and do not describe Phase 5 as complete.


## Mandatory execution scope when the user says “يلا المرحلة الخامسة” — 2026-10-13

- Interpret this exact request as authorization and instruction to execute **all of Phase 5**, including every subphase, task, subtask, improvement, bug fix, integration point, and documentation/roadmap update that belongs to Phase 5—not merely start it or complete one isolated fix.
- Work from the live `dev` branch and latest source commit; read this file and `MSTUDIO_ROADMAP.md`, inspect the actual source and existing work, avoid repeating completed changes, and keep the roadmap and this handoff synchronized as implementation progresses.
- Continue until all implementable Phase 5 work is completed and the code is ready to move directly to Phase 6. Do not mark runtime/build/device acceptance as passed without running it; current user constraint defers tests, builds, GitHub Actions, and device checks until explicitly requested. If verification is deferred, document that limitation precisely, but do not use it as an excuse to stop the remaining source implementation.
- Execute directly with concise communication; do not replace implementation with a plan, progress narration, or a partial phase. Ask only if a genuinely blocking ambiguity prevents safe implementation.


## Phase 5 full source implementation handoff — 2026-10-13

- Followed the user's explicit rule: “يلا المرحلة الخامسة” means complete the whole roadmap phase and all its related source tasks, not stop after a single fix. The instruction is also recorded in this file.
- Implemented recursive safe project/container discovery and expanded structural validation; added validation tests. Hardened SAF directory selection and canonical path checks.
- Fixed explicit Android variant handling (stale explicit variants now produce an actionable error instead of silently falling back), flavor-specific classes JAR lookup, duplicate IDEA module-name path collisions, dependency graph serialization for test artifacts, and preservation/display of Gradle sync issues.
- Fixed project-switch lifecycle so changing canonical project roots clears the previous workspace, variants, sync issues and indexer before initializing the next project. Editor path restoration uses the same manager API.
- Added tests for project structure, variants, classpath isolation, module-name ambiguity, dependency graph serialization, sync issue conversion and project-switch path normalization.
- Main implementation commits include `2ca624bc`, `2ce0a049`, `a2ec66a9`, `3889a0c5`, `f990dd36`, `611732a4`, `317684d6`, `6cba6176`, `3176e7e5`, `32b157c8`, `67943d72`, `5d7ca255`, `fc077795`, `84e96c64`, `e734e824`, `45ca3b99` and associated test commits. Consult the live branch history for full SHAs.
- **No tests, builds, Actions, or device checks were run** due to the user's explicit sequencing constraint. Phase 5 source implementation is ready for the next source phase, but its runtime acceptance remains pending.
- Known constraints to retain honestly: SAF selection currently supports the app's document provider and primary shared storage only; composite/included builds have not been independently verified as separate IDE modules. Do not claim acceptance or runtime success until the deferred verification batch is authorized and run.


### نص المستخدم الإلزامي بخصوص المرحلة الخامسة

عندما يقول المستخدم «يلا المرحلة الخامسة»، يجب تنفيذ المرحلة الخامسة من خريطة الطريق بشكل شامل وكامل، بجميع أجزائها الفرعية ووحداتها وتحسيناتها وإصلاحاتها وكل ما يتعلق بها، بحيث يكون المشروع مستعدًا للانتقال مباشرة إلى المرحلة التالية. ممنوع الاكتفاء بإصلاح جزئي أو بخطة أو بتحديث توثيق فقط. يظل منع تشغيل الاختبارات والبناء وGitHub Actions وفحوصات الجهاز قائمًا حتى يأذن المستخدم بها؛ لا تستخدم تأجيل التحقق ذريعة لترك أعمال المصدر القابلة للتنفيذ ناقصة، ولا تدّعِ نجاح تحقق لم يُنفّذ.


## Phase 5 closure and Phase 6 start — 2026-10-13

The user's latest instruction was to finish the two remaining Phase 5 gaps, then begin Phase 6. Latest source work is on branch dev; do not repeat the earlier statement that removable storage and composite builds are unsupported.

### Phase 5: removable storage
- BaseFragment resolves SAF external-storage document IDs through Android StorageManager. It accepts primary storage and a currently mounted removable volume whose UUID is reported by the system, maps removable volume roots to /storage/<UUID>, permits selecting a volume root, canonicalizes the selected directory, and rejects traversal/unmounted/unreadable paths.
- Own document-provider authority checks remain. Providers that do not map to a real filesystem path are still unsupported because Gradle needs filesystem-backed files.
- Commits: 0b78e430568c09925ed46236d3ceae70dbf9c46d and c829c37e0a0b494d9cbcd4d07f2450d64b183f91.

### Phase 5: composite/included builds
- RootModelBuilder fetches the Tooling API GradleBuild model, recursively visits included builds with canonical-root deduplication, requests IdeaProject for each included build, and appends its modules to the IDE model.
- Included module and task paths are namespaced by the included directory name; Java module dependencies and Android project dependency metadata are remapped. Root-build Android dependencies are mapped to included-build module paths when AGP supplies a matching build ID. Variant selections are filtered/remapped per included build to avoid collisions with root modules that have the same local path.
- Helpers/tests: CompositeBuildModelSupport.kt and CompositeBuildModelSupportTest.kt. Source commits include 66f67a43c6d6a1b0cfe5108b16d56888ec86447f, 35c10651af82f45790ee73ebdc47dfa22378ecc9, 8f10da15ecc777b139e970a7675f35fd9edcce10, 787420225c7d6dfeb0111899a523babdf5b0bc13, f7801575d9cd65d1168dd242cf4853c4968f4f8a, fd963f60ff86ae3352f24f0ff481d5ecf93823ed, 0dba230bb7f3a5e3bef35839100c70d2d138489f, f4708405d5b9b3a68d0c325238a2a65c4668e1d6. Test commits include f49f1b9b, e71c2637, ac4c417e.
- Known edge: public ToolingGradleBuild does not expose an explicit includeBuild { name = ... } override. The current namespace uses the included directory name (Gradle's default). Explicit custom included-build aliases still need a supported way to discover their task-selector name. Do not claim those aliases work.
- Phase 5 source work for the two requested gaps is implemented; runtime acceptance remains unverified. Do not run tests, builds, GitHub Actions or device checks until the user authorizes it.

### Phase 6 has started — editor/LSP and diagnostics
- JavaDiagnosticProvider previously paired per-file timestamps with one global cached DiagnosticResult, allowing a cache hit for file A to return diagnostics from the most recently analyzed file B. It now uses a concurrent per-file diagnostic cache, and clearing a document removes both its cached result and freshness timestamp.
- Workspace.findModuleForFile previously used string-prefix matching, which could assign files under sibling paths like /workspace/app-old to /workspace/app. It now uses normalized/canonical Path segment containment and picks the deepest matching module.
- JavaLanguageServer.analyzeSelected now analyzes the captured Path rather than re-reading mutable selectedFile inside the launched coroutine.
- Added PerFileDiagnosticCacheTest and WorkspacePathMatchingTest source tests.
- Source commits: f118137ea85246ec9e614d117cceec5a8ffd3741, 8f9f11239ac36978227c101cb80c69990130f12b, cde81243d63c0829dab8eb9ba530f7cf07090ed6, 968e766c027071e16ee35db8c2326ca0d7382a98, 20bcc3c27af992bcef6b7667d3bfb0201e1eab36. Test commits: d3a5e4862c19fa6dfa13905a4c750e5e93093502 and 915250a45b1e4d108d1e5475bb86a47ce46cb952.
- Phase 6 remains in progress. Continue source audit of classpath/sync recovery, stale diagnostics, Java/Kotlin/XML completion/navigation/references/formatting/code actions, and indexing responsiveness. Keep roadmap and GPT.md updated before/after meaningful batches. No executable verification has been performed.


### Phase 5 compatibility follow-up — 2026-10-13
- Composite-build discovery now wraps access to ToolingGradleBuild.includedBuilds because that model property was added after older Gradle versions. An older build without this optional API should continue as a regular project rather than failing sync.
- Source commit: e9a48859bdea06bcba990f0fd2bc6346b00b512c. Roadmap record: ae649a8fea8b05135a51101913cea07082ee4e3b.
- No executable verification was performed. Keep custom includeBuild name aliases as a known limitation until a supported way to retrieve their Gradle task-selector names is implemented.


### Phase 6 follow-up — invalidate diagnostics after workspace sync (2026-10-13)
- Added PerFileDiagnosticCache.clearAll() and JavaDiagnosticProvider.clearAllDiagnostics(). JavaLanguageServer.setupWithProject clears diagnostics computed against the previous workspace/classpath before it starts analyzing the new workspace.
- Added a source test that verifies clearing all cached diagnostic results.
- Source commits: a4968892db009ff0685b9ab1c1d8e66006372f43 and 68d22c9bc0dcb1b77bb0d8097e367fd3fc3f50fd; test: 23c2a3c788f4a6aa8e060b2204540367856e9a75. Roadmap record: a277ba6e82cfebc0108b3b046979dc092bcdab72.
- No tests/builds/Actions/device checks were run.



## Phase 6 source closure and Phase 7 kickoff — 2026-10-13

- Java diagnostics are explicitly invalidated on document edits so unsaved-buffer changes cannot reuse results merely because disk mtime is unchanged.
- Java completion cache resets on workspace/classpath replacement.
- JavaLanguageServer now cancels current diagnostic work and increments a workspace generation during setup; an asynchronous result from an older workspace is discarded before it reaches the editor.
- Source commits: `8fc635b04cf00ee989c3bed7eaa410c90d93ffba`, `4395a17eec50dd9475de304964a080339c2f619b`.
- Phase 6 cache/context fixes are source-implemented, but the entire acceptance matrix (Kotlin/XML, code actions, indexing responsiveness and runtime behavior) is not verified. Do not run tests/builds/Actions/device checks until the user authorizes them.

### Phase 7 started: XML designer input and preview safety
- `UIDesignerActivity` now handles absent or invalid/unreadable layout-file paths by returning `RESULT_CANCELED` through its existing error path rather than leaving a half-initialized activity or crashing.
- Existing inflation-error handling continues to refuse generated XML when the preview is invalid.
- Source commit: `f852a40bf4df457b82529e1dc6180fda22135385`.
- This begins Phase 7; it does not establish that XML designer round-tripping, resource resolution, drag/drop constraints, custom views/includes or Compose preview are complete. No executable verification was run.


## Phase 7 implementation handoff — 2026-10-13

Latest source work is on branch `dev`. Re-fetch live files and this note before any follow-up.

- Designer no longer treats source inflation as an edit: dirty state is marked by actual user attribute/hierarchy changes and M3 additions. Material views created while loading the XML are not persisted as user additions. Setting the same file path again preserves dirty/undo state through activity recreation; switching to a different path resets it.
- The back action returns original UTF-8 XML when the visual tree is untouched. `<include>` is represented by the existing `IncludeView` wrapper and serialized back as an include node. If a changed source contains `<merge>`, `<requestFocus>`, `<tag>`, `<fragment>`, or `<view class="...">`, save is blocked rather than returning a lossy generated tree.
- XML generation writes default namespace declarations as `xmlns` and rejects namespaced attributes with a URI but no usable prefix.
- Material component registration now uses a mutable registry rather than casting a `mapOf` to `MutableMap`.
- `ProjectResourceLocator` discovers drawable/mipmap resource directories across nested modules, source sets and qualifier folders, prioritizing matching night/orientation/density/API/screen-size qualifiers. ImageView and background preview use it; vector XML is rendered through `VectorDrawableRenderer` rather than a fake placeholder. Project `@color` values and aliases are resolved from matching `values*` XML before falling back to the IDE's resource table.
- Compose preview sets its initialization-ready flag after successful initial setup, guards against stale source compilation results, and can recreate preview resources after low-memory cleanup.
- Source tests were added for XML round-trip guards/default namespace serialization, qualified resource-directory discovery, configuration-specific color values and dirty-state lifecycle. **No tests/builds/Actions/device checks were run**; do not claim runtime success.
- Remaining Phase 7 functional gaps: safe refusal (rather than full editing) for `<merge>`, `<requestFocus>`, `<tag>`, `<fragment>`, and `<view class="...">`; selected flavor/source-set resource precedence; project `@dimen`/`@string`/`@style` value resolution; and broad missing-resource/resource-preview acceptance coverage. Continue Phase 7 only; Phase 8 must not start until the exact user authorization `يلا الان إبدأ بالمرحلة الثامنة`.


## Phase 7 source follow-up — 2026-10-13

Recent follow-up commits:
- `96607d39438a06257fc475baa5ad7a2aa20d1dec`: unsupported generic background XML no longer reports fake placeholder success.
- `d8f83996540d093995cdc10b1ac7a8d9d61370ac`: same behavior for ImageView generic XML drawables; unknown shapes no longer invent a default color.
- `0680600ae031f40555095c89b3f1f26064d23b74`, `94cd63903e7767ac149835321bbc8869448b2ed1`: project color/dimension aliases are resolved in shape previews and layout file context is threaded into the renderer.
- `09e40c450824421157807912aa0e7ad7d7d74a34`: custom vector path fill/stroke colors resolve from the project and unknown references fail instead of silently rendering black.
- `6b112903b6e2cfd2e3503b22f68fea33762d925f`, `e3a93bfe635be76de790342fd64905f53e0a324e`: resource resolver prefers settings root and owning module.
- `3b4a02e6eff38f483ad9b0e62a779e5539f3dec6`, `2bd32a851e16a25ae012e56cdf2db4d45b01c71c`, `6faddd97d3e9636bc14c21b4e9bb26441f79ec25`, `568d6e05530c39d08134f814318dc8da807c7f26`: module-local drawable/mipmap/vector preference.
- Tests added for settings-root/module resource precedence, value aliases/cycles, shape color resolution and no fake XML drawable placeholders.

No tests/builds/Actions/device checks were run. Do not claim runtime success.

Phase 7 is not to be described as fully accepted until the source-level remaining gaps are addressed: lossless editing of merge/requestFocus/tag/fragment/class-qualified view nodes (currently safely blocked when dirty), actual Gradle/AAPT variant/dependency resource merging and style/theme application, broad XML resource completion/missing-reference diagnostics, and Compose runtime acceptance. Phase 8 is strictly locked until the exact user authorization `يلا الان إبدأ بالمرحلة الثامنة`.


### Phase 7 latest source handoff — 2026-10-13

Resource APIs now accept a preferred module root, and preview callers derive it from the opened layout. Module-local drawable/mipmap/vector and values resources are preferred when names collide. Shape color/dimension references and custom vector fill/stroke colors use project-aware resolution. Unsupported generic selector/layer-list/ripple/animated XML no longer displays a fake placeholder as a successful preview. Tests for module precedence and failure behavior were added but not run. Latest source/test commits are recorded in MSTUDIO_ROADMAP.md. Phase 7 remains not runtime-accepted; full style/theme/AAPT merge and lossless editing of blocked XML constructs remain gaps. Phase 8 stays locked until the exact user authorization `يلا الان إبدأ بالمرحلة الثامنة`.


## Phase 7 latest source handoff — 2026-10-13

- Resource selection order is now owning Android module → opened layout's source set → configuration qualifier. This is applied to project drawable/mipmap/vector and values lookup. Common locale qualifiers and locale-list handling are present.
- Project text preview resolves simple `@string` references for text/hint/title/contentDescription; project dimension preview handles common text size, size, padding, margin and elevation attributes from `@dimen`; project style preview handles common style items plus parent inheritance. This is a preview-only subset, not AAPT/theme parity.
- XML LSP now reports malformed XML with a navigable range and clears the diagnostic when corrected. Comprehensive missing-resource reference diagnostics are still open.
- Source tests added for source-set precedence, project strings/dimensions/style inheritance, and XML syntax diagnostic lifecycle.
- No tests/builds/Actions/device checks were run. Do not claim runtime acceptance.
- Phase 7 still has explicit limitations: AAPT dependency/product-flavor/theme merging, full style/plural/styled-string support, missing-resource diagnostics across all types, and lossless editing of `<merge>`, `<requestFocus>`, `<tag>`, `<fragment>`, and class-qualified `<view>` nodes (currently refused on edited save). Complex drawable XML is refused rather than faked.
- Do not begin Phase 8 until the exact authorization `يلا الان إبدأ بالمرحلة الثامنة`.


## Phase 7 source closeout — 2026-10-13

Phase 7 source implementation is closed for the current safe-support boundary; this does **not** mean runtime acceptance has been proven. Read the final Phase 7 section in `MSTUDIO_ROADMAP.md` before further changes.

- XML resource-index cache invalidation now runs only for changed files under Android source-set `res` folders, not every XML editor keystroke.
- Missing-resource diagnostics now index `@+id/name` declarations from XML resource files and no longer blanket-ignore `@id/name` or custom `@attr/name` references. Unsaved local ID declarations and commented ID declarations are excluded from false-positive missing-ID warnings.
- Added tests for source-set resource cache invalidation, generated/non-resource path exclusion, and declared-ID indexing/comment handling.
- Resource preview remains module/source-set/qualifier-aware and resolves common project string/color/dimension/style values; vector rendering uses real vector/path data. Complex drawable XML and XML-only view constructs not faithfully represented by the visual model fail closed instead of being saved as lossy or fake previews.
- **Do not run tests, Gradle builds, GitHub Actions, or device checks yet.** The user requested source changes only and will verify later. Do not claim compile/test/runtime success.
- Runtime acceptance remains pending. Explicit limitations: complete Gradle/AAPT dependency/product-flavor resource merging and themes are not emulated; edited layouts containing `<merge>`, `<requestFocus>`, `<tag>`, `<fragment>`, or legacy `<view class="...">` are refused instead of losslessly edited; complex selector/layer-list/ripple/animated drawables and full plural/styled-string semantics remain unsupported.
- Keep existing UI language/style, strings, RTL/LTR and visual consistency intact. Do not introduce a second design language.
- **Phase 8 is locked** until the exact user instruction `يلا الان إبدأ بالمرحلة الثامنة`.
- Latest commits: `2fafe43e1dbe6f23755c5898f10ecd5f5dc25f01`, `8578c155139a19dc03b8fd624c0db8ec784eae54`, `b41850269cebbfe680635aa81bd58c68329dbcde`, `f59dfea1d241bde70b4ab9df06dcabfc320b4d68`, `2ea65c264a7c43a15a52b080ca0f6191a9e8020a`. Roadmap closeout commit: `b94c66b5b740dae6eea4f3c84456aa2b21a702e9`.


## Phase 8 execution log — 2026-10-13 (initial pass)

- User explicitly authorized starting Phase 8. Re-read this handoff and the live roadmap on `dev`; the latest commit before the phase work was `bfd60ae7995fb06ec90f4acc499ac2330371cb03` (template creation/reopen lifecycle fix). The phase acceptance criteria remain: advertised templates must produce runnable APKs through the actual local Tooling API, project configuration must agree, and failures must support recovery. No build, tests, Actions, or device verification are allowed under the current verification hold.
- Inspected the current `MainActivity.kt` and `MainViewModel.kt`, plus the latest template-creation lifecycle commits. Found a concrete transition bug in `MainViewModel.postTransition`: `LiveData.observe` immediately emits the current `true` value while a transition is running, but the observer removed itself and ran the pending action on that first emission. This could open a newly-created project before the screen transition ends, and could undermine the intended rotation-safe delivery.
- Fixed the observer to remain registered while the value is true and execute only after the transition callback publishes false. Commit: `a1e3aeeae6fc3f40fb8f844f94734ebc00cdb2b2` (`fix: wait for screen transition before opening created project`). The change is source-reviewed only; it has not been tested or run.
- Phase 8 remains in progress, not accepted. Continue inspecting template recipe generation, input validation, filesystem rollback/cleanup, generated Gradle/manifest/resource consistency, and onboarding recovery. Do not claim every template builds, syncs, installs, or launches until evidence exists. Keep the verification hold in force.


## Phase 8 execution update — 2026-10-13 (continued)

The later explicit user request to start Phase 8 supersedes the older Phase 8 lock statements above; those statements are retained as historical handoff notes, not active restrictions on starting the phase.

- Fixed template completion delivery in `TemplateDetailsFragment.kt`: successful recipe results are placed in the activity-scoped `MainViewModel.pendingCreatedProject`, and `MainActivity` remains the single owner of the transition and editor launch. This removes the fragment's competing direct-launch path and avoids resetting the screen transition twice. Commit: `1a4df200595b2a6da46ae2713195d638c04d62c2` and follow-up `42a251295421ff78f16499882bffb2459301c680`.
- Template failures are now routed through the activity-scoped pending-error state so the error is not tied to a fragment view that may be recreated during rotation. A result whose project directory is missing is rejected instead of being reported as a successful creation.
- A successful result is saved to recent projects before navigation, so a process interruption between generation and editor launch does not leave the generated project undiscoverable. Commit: `af90256815939b64567c420fcfbf6b04e7aebede`.
- These changes are source-reviewed only. The standing verification hold still prohibits running tests, Gradle builds, Actions, or device checks until separately authorized. Do not call Phase 8 complete: actual Tooling API generation/sync/build and install/launch checks for all eight advertised templates, configuration consistency, cancellation/low-storage/partial-cleanup acceptance, and responsive/RTL onboarding checks remain unverified.

- Follow-up hardening: `MainViewModel.saveProjectToRecents` is now suspending and awaits its database operation on `Dispatchers.IO`. Template completion launches that operation in the activity ViewModel scope and publishes `pendingCreatedProject` only after the save attempt finishes, so rotation does not cancel the persistence step. Commits: `149182d49a101b985bbb8f60ab4084e0faa7e121`, `38088121e97d718a8d036c9fe04f6a8fc02f5d90`.

- Final source review for this pass confirmed the template success path now awaits the recent-project DAO attempt before publishing the pending result. No runtime checks were executed in this pass; template APK generation, Tooling API sync/build, install/launch, and all edge-case acceptance still require the authorized verification environment.

## Verification authorization update — 2026-10-13

The user's latest instruction to finish Phase 8 fully supersedes the earlier source-only verification hold. Verification is now authorized as part of Phase 8 acceptance. No build, test, workflow run, generated-template Tooling API build, install, or launch result has yet been obtained in this session; the phase must remain unaccepted until those checks are actually completed and their results recorded.


## Phase 8 source closeout and Phase 9 start — 2026-10-13

- Re-read the live roadmap and inspected the eight registered project templates: No Activity, Empty Activity, Basic Activity, Navigation Drawer, Bottom Navigation, Tabbed Activity, No AndroidX Activity, and Compose Activity. The No Activity template is intentionally headless; it must not be falsely held to launcher-intent expectations.
- Merged generated-project contract assertions in commit `c256987eaa19ceed6ece474383d38ccbeec45d75` (PR #1). The existing template-generation test harness now checks root/app Gradle files, app namespace/applicationId/SDK declarations, wrapper distribution version, manifest package, and launcher intents where applicable. It already exercises Java/Kotlin and Groovy/Kotlin-DSL combinations for applicable templates.
- The PR did not produce a GitHub Actions run or check-run in the repository; no local checkout/build environment was available. The tests are authored, **not executed**. No template Tooling API sync/build, install/launch, cancellation, low-storage, or device layout test is claimed.
- Phase 8 source work is closed for this pass; runtime acceptance is explicitly deferred to the roadmap's final verification gate as permitted by the user's instruction to defer tests when the available environment cannot run them. Do not describe Phase 8 as runtime-verified or claim all generated APKs run.
- Phase 9 source audit started against the live `MStudio-NIDE` source. MStudio already uses Google Java Format with AOSP/Google styles through `JavaServerSettings` and `CodeFormatProvider`, so the NIDE formatter is not an approved duplicate port. NIDE's decompiler source headers identify Apache 2.0 while the inspected NIDE repository root license is GPL-3.0 and the `lib-decompiler` module listing contains no separate Apache license file; its old Java 7 configuration and broad Fernflower source tree also make it unsuitable for a blind wholesale port. Preserve this as an investigation/blocker until exact license provenance, a narrow API boundary, memory cost, and tests are resolved.
- Do not port NIDE's custom Android builder, legacy dx/JDK 1.7 compiler, or FastGradleParser into MStudio's Gradle Tooling API path. Continue Phase 9 from `NIDE_FEATURE_MATRIX.md`; update this file and the roadmap together after each meaningful implementation decision.
