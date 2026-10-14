# Phase 10 — Verification Runbook

This runbook is separate from implementation status. **Defining a workflow is not evidence that a build, test, scan, or device check passed.** Historical manual-only wording below describes the initial preparation state; see the dated CI updates at the end for the current results.

## 1. Automated verification

On the dev branch, open Actions and run **Phase 10 — verification gate** (.github/workflows/phase10-verification.yml).

Review each job separately:

1. **Static safety and manifest checks**
   - Confirm wrapper URL validation, backup/network invariants, explicit exported declarations and decompiler safety bounds pass.
   - Review the printed exported-component inventory manually; the script is not a security approval.
2. **JVM unit tests and Tooling API suite**
   - Confirm :core:app:testDebugUnitTest and :tooling:impl:build both finish successfully.
   - Download phase10-test-reports and inspect failures even if the job fails.
3. **Debug APK**
   - Confirm :core:app:assembleDebug succeeds.
   - Download phase10-debug-apks; compare the APK list and SHA-256 checksums with the run's commit SHA.
   - This workflow deliberately does not use release signing secrets and does not publish anything.

Then run **Phase 10 — CodeQL security analysis** (.github/workflows/phase10-codeql.yml). Review and triage every finding; do not treat a successful upload as proof that all findings are harmless.

## 2. Device acceptance matrix

Record the exact device model, Android API, ABI, app version, commit SHA and result for every row. Test at least one supported ARM64 device and one ARMv7 device if both ABIs remain advertised.

| Area | Required checks | Evidence to retain |
|---|---|---|
| Install/upgrade | Clean install, upgrade over previous MStudio build, launch, onboarding and permissions granted/denied | Result, logcat, APK SHA-256 |
| Install callback security | Install a generated APK via PackageInstaller; verify the private InstallationResultReceiver still receives the explicit PendingIntent result | Install success/failure result and relevant logs |
| Local build | Create/open a sample Android project, sync, build debug APK, cancel a build and retry | Gradle output, APK path and checksum |
| Runtime recovery | Interrupt setup/download, simulate missing or invalid tool where practical, low-storage path, retry without losing projects | Setup status and logs |
| Decompiler normal input | .class, .jar, .zip, nested packages, source preview and SAF export | Expected source output |
| Decompiler rejection | .apk, .dex, unsupported extension, malformed archive, negative/unknown or oversized declared archive entries | Clear rejection and no crash |
| Decompiler resource bounds | Input over 128 MiB, over 5,000 archive entries, archive expansion over 256 MiB, output over 10,000 files / 256 MiB, timeout | Process termination, storage recovery, no stale partial result |
| Performance | Peak memory, CPU use, run duration, output-monitor overhead, remaining free storage during a representative archive | Measurements, not estimates |
| Regression | RTL/LTR, narrow display, back navigation, repeated runs and activity recreation | Screen recording or logs for failures |

## 3. Compatibility and release gate

- Review the manifest's **merged** form, not only core/app/src/main/AndroidManifest.xml.
- Confirm exported components are intentional: crash handler, launcher, DocumentsProvider and permission-protected log service.
- Verify JDK 21 source-build compatibility, managed runtime availability, actual Gradle wrapper/AGP combinations used in test projects, Kotlin/Groovy DSL, module layouts and dependency modes.
- Do not claim x86_64 or NDK compatibility unless a real build and device/emulator test succeeds.
- Do not change the 5096 MiB Gradle heap / 4096 MiB Kotlin daemon heap merely to make numbers look suitable. Measure the source-build environment separately from the IDE's managed on-device Gradle process.
- Preserve run URLs, job IDs, artifact names/checksums, first causal failure and device evidence in the roadmap before changing any acceptance status.

## Current status

Current state before the Phase 12 source fix: run 38576235891 failed because CloudBuildActivity.kt did not compile; static safety checks passed, but tests and debug APK assembly were blocked. CodeQL run 38576235807 also failed at the same compilation error. Phase 9 runtime acceptance, Phase 10 acceptance, and Phase 11 cloud-build runtime acceptance remain **pending**. See the latest dated section below.


## Failure analysis and corrective changes (2026-10-14)

The latest completed verification/CodeQL attempts failed during Kotlin compilation, before unit tests could run. The first causal failures were:

1. `:tooling:impl:compileKotlin` — `RootModelBuilder.kt` constructed generated protobuf `GradleModels.SyncIssue` using Kotlin named constructor arguments. Generated protobuf messages are Java classes and must be created through `newBuilder()`; the conversion now uses the generated builder setters and `build()`.
2. `:utilities:templates-api:compileDebugKotlin` — `projectNameParameter()` referenced `PROJECT_NAME` without importing `ParameterConstraint.PROJECT_NAME`; the import is now present.
3. `:xml:lsp:compileDebugKotlin` — two references used `module.delegate.projectDir`, but the delegate is a generated protobuf model with no `projectDir` property. Both now use the cached `module.projectDir`.

The debug APK job and CodeQL analysis were blocked by these same compile failures; no APK/checksum artifact was produced and the test job did not reach test execution. The Python static safety/manifest job passed. Corrective commits are on `dev`; rerun the full verification and CodeQL workflows against the final corrected head before treating the failures as resolved. These source changes alone are not evidence of a passing build or tests.


## Latest CI failure and correction (2026-10-14)

The most recent verification run reached Kotlin test compilation but failed in `FernflowerIntegrationTest.kt`: direct imports of `javax.tools.ToolProvider` and calls through `JavaCompiler` are not visible to the Android plugin's test compile classpath. The integration test now resolves `javax.tools.ToolProvider` reflectively and invokes the host JDK compiler without compile-time references to the `java.compiler` API. The debug APK build and static safety/manifest job passed on the same commit; the unit-test job did not reach test execution.

The paired CodeQL run built the debug app and Tooling API successfully, but its analysis job ended after the runner received a shutdown signal during result upload. This is recorded as an interrupted scan, not a security finding or a successful scan. Both workflows now avoid canceling an in-progress run when another commit is pushed. Fresh verification and CodeQL runs on the corrective commit must be reviewed before acceptance can be marked complete.


## Follow-up: reflective compiler access (2026-10-14)

The first reflective fix removed the Kotlin compile-time errors, and the test suite then ran 42 tests, but the Fernflower integration test failed with `IllegalAccessException`: invoking the method through the JDK's concrete compiler implementation class is blocked by module access. The invocation now resolves `run(InputStream, OutputStream, OutputStream, String[])` through the public `javax.tools.JavaCompiler` interface instead. The static checks and debug APK build passed in that run; the test failure is not yet considered fixed until the next CI run passes.


## Phase 12 pre-build audit — 2026-10-14

Latest inspected head before the corrective commit: `23a0175c250a64e3d7472071bf5eb56bb9ba85ca`.

- Verification run: https://github.com/majd7772233-a11y/MStudio/actions/runs/38576235891
- CodeQL run: https://github.com/majd7772233-a11y/MStudio/actions/runs/38576235807
- Root cause in both runs: `CloudBuildActivity.kt` used the nonexistent Kotlin synthetic property `textIsSelectable` (three compile errors) and declared a button property named `approveWorkflow`, colliding with the method of the same name and making the button reference ambiguous.
- Corrected the TextView property to `isTextSelectable` and renamed the field to `approveWorkflowButton`. A fresh run must confirm this correction; source edits alone do not count as passing tests.
- Added the manual-only Phase 12 Release workflow and static invariants for its four signing secrets, read-only permissions, signature verification, and SHA-256 output. Release signing itself has not yet run.


### Phase 12 static checker follow-up — 2026-10-14

Verification run `38582488392` had its static job fail because the new check looked for literal `apksigner verify`, but the workflow invokes `"$APKSIGNER" verify`. The checker was corrected to check the variable and the actual verification arguments in commit `53e7781dd9f5300873296e4d8d6cd37ef9573849`. Fresh run `38582699324` is queued; the earlier static-job failure is not an APK signature result.
