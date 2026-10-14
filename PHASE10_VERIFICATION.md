# Phase 10 — Verification Runbook

This runbook is separate from implementation status. **Defining a workflow is not evidence that a build, test, scan, or device check passed.** The workflows below are manual-only and have not been dispatched.

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

Source-level hardening and workflow preparation are committed. No tests, builds, Actions, CodeQL scans, APK installs, or device checks have been run. Phase 9 runtime acceptance and Phase 10 acceptance remain **pending**.


## Failure analysis and corrective changes (2026-10-14)

The latest completed verification/CodeQL attempts failed during Kotlin compilation, before unit tests could run. The first causal failures were:

1. `:tooling:impl:compileKotlin` — `RootModelBuilder.kt` constructed generated protobuf `GradleModels.SyncIssue` using Kotlin named constructor arguments. Generated protobuf messages are Java classes and must be created through `newBuilder()`; the conversion now uses the generated builder setters and `build()`.
2. `:utilities:templates-api:compileDebugKotlin` — `projectNameParameter()` referenced `PROJECT_NAME` without importing `ParameterConstraint.PROJECT_NAME`; the import is now present.
3. `:xml:lsp:compileDebugKotlin` — two references used `module.delegate.projectDir`, but the delegate is a generated protobuf model with no `projectDir` property. Both now use the cached `module.projectDir`.

The debug APK job and CodeQL analysis were blocked by these same compile failures; no APK/checksum artifact was produced and the test job did not reach test execution. The Python static safety/manifest job passed. Corrective commits are on `dev`; rerun the full verification and CodeQL workflows against the final corrected head before treating the failures as resolved. These source changes alone are not evidence of a passing build or tests.
