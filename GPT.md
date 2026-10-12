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


## Execution log — 2026-10-12 (Phase 0.1 and 0.2)

- Rechecked latest branch dev and roadmap before acting. Initial HEAD was ccd8b4c9abbcebe8fe72700120af93053e6504da (edit Build.yml); its change removed global Maven credentials but left snapshot publishing ungated.
- Rechecked root Gradle settings/build files, wrapper, version catalog, daemon JVM properties, .gitmodules, both workflow files, app Gradle config, manifest and .nyx.yml. Confirmed wrapper Gradle 8.14.4, JDK 21 daemon, AGP 8.13.2, Kotlin 2.3.21, Tooling API 9.7.0, and the declared logback-android submodule. Remote inspection does not prove the submodule was initialized in a runner.
- Phase 0.2 fix committed as 63ae9aa0d9bac985b5efc6869069826b04af7a97: publish and publish_snapshots now require separate opt-in repository variables, strict AndroidIDE-Dev/AndroidIDE allowlisting, intended branches and successful prerequisite jobs. Maven Central credentials exist only in the specific publishing-step environments, not global workflow env. The MStudio fork cannot enter either publish job even if its variables are enabled.
- Roadmap execution record committed in 656a2d777d0e1f4f06edc296bd1303a41a5a6031; renamed MSTUDIO_ROADMAP to MSTUDIO_ROADMAP.md in commits 237ceb84319534ea618504c9223313765d8e78de and c1c4c640ba63830ac5cf35740ef4eacbc2f7bc0f. This fixes the mismatch with references in this file.
- Re-fetch the live workflow/roadmap and inspect the Actions run for parser/runtime feedback. No successful build, Maven publication or device test is claimed.
- Next roadmap task: Phase 0.3 signing audit (core/app/dev.keystore, core/app/build.gradle.kts, gradle.properties, .nyx.yml, and all signing consumers). Do not expose keystore bytes or secrets in notes.


### Source-state correction — submodule declaration mismatch (2026-10-12)

- `.gitmodules` declares `composite-builds/external/logback-android`, but GitHub's contents endpoint returns a normal directory listing for that path rather than a submodule entry. Treat this as a source-tree/declaration inconsistency to resolve in Phase 0.1/0.4. Do not assert that recursive submodule checkout has been proven. No Actions run was returned by the accessible workflow-run query after the guard commit, so CI parser/build validation remains unverified.


## 2026-10-12 engineering update — signing remediation and MStudio identity kickoff

Latest verified source HEAD before this batch was `def0aae3f60cc037c65e9ae760ad32f11ef099dc` on `dev`. Rechecked the roadmap and live source before editing.

Signing findings:
- `composite-builds/build-logic/common/.../config.kt` points to `signing/androidide.keystore`.
- `SigningConfigPlugin.kt` uses `IDE_SIGNING_ALIAS`, `IDE_SIGNING_STORE_PASS`, and `IDE_SIGNING_KEY_PASS`; it applies its common signing config only if the keystore and all three values exist.
- `SigningKeyUtils.kt` can load the key from `IDE_SIGNING_KEY_BIN` or download it using `IDE_SIGNING_URL` plus auth env/properties. Never log or display key bytes.
- Removed committed placeholder signing defaults and tracked keystores; added ignore rules. Git history remains intact, so the inherited key is treated as compromised and must not be reused. Release signing is not configured or validated yet; do not claim a release artifact is signed.
- Do not run Actions/build during these source phases. Phase 0.4 remains deferred until after deeper code work.

Identity changes:
- Launcher label and `BuildConfig.INTERNAL_NAME` now say `MStudio`; Gradle root project name is `MStudio`.
- Package/namespace/application ID remain unchanged at `dev.mutwakil.androidide`.
- Nyx release metadata now points at `majd7772233-a11y/MStudio` and uses MStudio asset names.
- Phase 1 is still in progress: inspect/update remaining About/help/onboarding strings, icon/splash visuals, README/user-visible references, and inherited attribution before calling the rebrand complete.
