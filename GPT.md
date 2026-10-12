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
