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
