# MStudio — Source Architecture and Execution Map

> Baseline inspected: e4dc15adf1c670edcfce12008e822c02b0d9cb64. Refresh live source before relying on this map after refactors.

## 1. Module map

| Path/module | Role from source |
|---|---|
| core/app | Android app, onboarding, main/project/editor screens, Gradle foreground service, diagnostics and APK installer. Key files: activities/MainActivity.kt, activities/OnboardingActivity.kt, services/builder/GradleBuildService.kt, services/builder/ToolingServerRunner.kt, utils/ApkInstaller.kt. |
| core/common | Shared runtime path/environment and tool setup: utils/Environment.java, managers/ToolsManager.java. |
| termux/application, termux/emulator, termux/shared, termux/view | Termux-derived terminal/session source embedded in MStudio. Core IDE use must not require separate Termux APK. |
| tooling/api | RPC interfaces/messages, project/build metadata, tasks/results/progress; ToolingApiLauncher.kt. |
| tooling/impl | Actual Gradle Tooling API operations, sync and project model builders including AndroidProjectModelBuilder.kt. |
| tooling/events and tooling/model | Events/progress and model contracts crossing process boundary. |
| core/projects | Cached project objects including GradleProject. |
| subprojects/projects | Workspace/module mapping, project manager, sync and indexing integration. |
| subprojects/project-models and project-serialization | Project model extension/caching/serialization. |
| editor/api, editor/impl, editor/lexers, editor/treesitter | Editor contracts, implementation, highlighting/lexical structures. |
| core/lsp-api, core/lsp-models, java/lsp, java/javac-services, lsp/kotlin, xml/lsp | Java/Kotlin/XML language services and diagnostics. |
| core/indexing-api, core/indexing-core, lsp/indexing, lsp/jvm-symbol-index, lsp/jvm-symbol-models | Indexing and JVM symbol model. |
| xml/aaptcompiler, xml/dom, xml/utils, utilities/xml-inflater, utilities/uidesigner | XML/resource handling, inflater and visual designer; completeness requires tests. |
| subprojects/compose-preview | Compose preview, distinct from XML layout preview. |
| subprojects/git-core plus app Git UI | Git integration. |
| utilities/templates-api, utilities/templates-impl | Project templates; TemplateRecipeExecutor copies assets/files and writes generated source. |
| logging/logger, logging/logsender, logging/idestats | Logging, log integration and statistics/log-sending; review privacy/defaults. |
| composite-builds/build-logic | Root convention plugins, constants and tasks such as BuildConfig and SetupAapt2Task. |
| composite-builds/build-deps and build-deps-common | Source-built/shared dependencies. |
| testing | Automated tests and Gradle project fixtures. |

The live settings.gradle.kts is authoritative for modules included in the build.

## 2. Actual on-device Gradle build chain

1. OnboardingActivity decides whether tool setup is ready.
2. ToolsManager initializes managed Java/runtime and extracts tooling assets.
3. MainActivity/project screens open or create a project.
4. Project manager/workspace requests Gradle sync and model data.
5. GradleBuildService runs as an Android foreground service and owns build state/notifications/events.
6. Service creates an internal Termux-derived environment and starts ToolingServerRunner.
7. ToolingServerRunner runs Environment.JAVA with Environment.TOOLING_API_JAR and connects process stdin/stdout to RPC; stderr is handled separately.
8. ToolingApiLauncher configures the JSON-RPC protocol exposed through tooling/api.
9. tooling/impl invokes real Gradle Tooling API and generates sync/project/task models.
10. GradleBuildService includes private init script and android.aapt2FromMavenOverride for device-compatible AAPT2.
11. Progress/output/result callbacks return; project indexing refreshes after build.
12. ApkInstaller validates the output and invokes PackageInstaller session or fallback. Android may require user confirmation.

Source links:
- https://github.com/majd7772233-a11y/MStudio/blob/dev/core/app/src/main/java/dev/mutwakil/androidide/services/builder/GradleBuildService.kt
- https://github.com/majd7772233-a11y/MStudio/blob/dev/core/app/src/main/java/dev/mutwakil/androidide/services/builder/ToolingServerRunner.kt
- https://github.com/majd7772233-a11y/MStudio/blob/dev/tooling/api/src/main/java/dev/mutwakil/androidide/tooling/api/util/ToolingApiLauncher.kt
- https://github.com/majd7772233-a11y/MStudio/blob/dev/tooling/impl/src/main/java/dev/mutwakil/androidide/tooling/impl/sync/AndroidProjectModelBuilder.kt
- https://github.com/majd7772233-a11y/MStudio/blob/dev/core/common/src/main/java/dev/mutwakil/androidide/utils/Environment.java
- https://github.com/majd7772233-a11y/MStudio/blob/dev/core/common/src/main/java/dev/mutwakil/androidide/managers/ToolsManager.java

## 3. Runtime path contract

| Item | Current path strategy | Considerations |
|---|---|---|
| Runtime root | context.getFilesDir() | App-private. |
| Termux-like prefix | files/usr | bin/lib/temp and managed runtime binaries. |
| Home | files/home | HOME and user-level data. |
| IDE state | files/home/.androidide | Tooling JAR, AAPT2, init scripts and support files. |
| Gradle home | files/home/.gradle | Large cache; don't clean during active build. |
| Android SDK | files/home/android-sdk | Must match ANDROID_HOME/ANDROID_SDK_ROOT. |
| Java | files/usr/lib/jvm/java-21-openjdk/bin/java | Validate version, ABI and executable bit. |
| Tooling JAR | files/home/.androidide/tooling-api/tooling-api-all.jar | Must match app protocol/API. |
| AAPT2 | files/home/.androidide/aapt2 | Passed through AGP override. |
| Init script | files/home/.androidide/init/init.gradle | Added to Gradle arguments. |
| Project storage | external AndroidIDEProjects via storage helper | Test scoped storage, permissions and migration. |

Keep path derivation centralized. Search consumers before moving paths and test migrations; do not delete project data during runtime cleanup.

## 4. Distinguish build configuration layers

- MStudio source build: root Wrapper 8.14.4, CI JDK21, source Java compatibility17.
- Gradle tooling dependencies: catalog entries AGP tooling9.3.1 and Gradle tooling9.7.0 at initial inspection.
- Runtime Java: managed Java21 under embedded Termux-style prefix.
- User project Wrapper: project-local Gradle distribution/version.
- User project's AGP/JDK: project plugin setup and compatibility.
- Android SDK/AAPT2: managed SDK and customized AAPT2 suitable to device ABI.

Do not resolve a mismatch by changing all layers at once.

## 5. Initialization/AAPT2 details

ToolsManager.init asynchronously checks ABI, loads JDK distributions and extracts AndroidIDE Gradle plugin, AAPT2, tooling JAR, android.jar, color schemes, init script and Kotlin-artifact download script. Errors are logged; UI must validate each tool instead of treating callback completion as all-success.

SetupAapt2Task downloads and verifies SHA-256 for ARM64/ARMv7 in inspected source; x86_64 is commented. Maven AAPT2 isn't assumed runnable inside Android; the service passes custom override. Require an actual resource compile/link test for every supported ABI.

## 6. Current unknowns and risk areas

- Clean CI build and actual device install were not proven by initial source review.
- ProjectValidation assumes app module for common projects; nonstandard modules need tests.
- AndroidProjectModelBuilder has product-flavor classes JAR TODO.
- GradleBuildService result dispatch must be traced; body appears to trigger indexing refresh only.
- x86_64 and NDK support are unverified.
- UI designer and Compose preview need end-to-end tests.
- Root heap values are high for lower-memory phones.
- build.yml publishing needs default-off gating before routine CI.
- Manifest contains hard-coded namespace permissions/authorities; package rename requires full reference audit.
- README feature boxes and module presence are not proof of function.

Use MSTUDIO_ROADMAP.md for acceptance gates and GPT.md for append-only engineering findings.
