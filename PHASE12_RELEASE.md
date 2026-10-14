# Phase 12 — Final build and Release signing

## Purpose

Produce a reproducible, signed Release APK from the reviewed MStudio source. This workflow uploads build artifacts only; it does not publish a GitHub Release, publish Maven packages, or push code.

## Start the build

1. Open Actions and select **Phase 12 — signed Release build**.
2. Choose **Run workflow** on `dev` (or an explicitly reviewed `release/*` branch).
3. Wait for the `release-apk` job to finish.
4. Download `mstudio-signed-release-apks-<run number>`.
5. Verify the included `SHA256SUMS.txt` before installing the APK. The workflow runs `apksigner verify` on each generated Release APK before uploading it.

Workflow: `.github/workflows/phase12-release.yml`.

## Required GitHub Actions secrets

| Secret name | Required value |
| --- | --- |
| `MY_KEYSTORE_JKS` | Base64-encoded bytes of the owner-controlled JKS keystore. This matches the existing Gradle signing plugin's `IDE_SIGNING_KEY_BIN` contract. |
| `KEYSTORE_PASSWORD` | Keystore password. |
| `KEY_ALIAS` | Alias of the signing key inside the keystore. |
| `KEY_PASSWORD` | Password for that key alias. |

The workflow checks that all four secrets exist, decodes the keystore in a temporary runner location, and verifies the store password and alias with `keytool` before Gradle starts. The build maps the secrets to the existing names expected by the Gradle signing plugin:

- `IDE_SIGNING_KEY_BIN`
- `IDE_SIGNING_STORE_PASS`
- `IDE_SIGNING_ALIAS`
- `IDE_SIGNING_KEY_PASS`

No secret values are written to the repository or intentionally printed in logs. Do not replace the signing identity after distributing a public build unless you intentionally accept that Android will reject it as an in-place update.

## Safety boundaries

- The workflow is manual-only and accepts only `dev` or `release/*` refs.
- GitHub token permissions are read-only.
- The keystore is validated before build, then APK signatures are verified after build.
- Artifacts are retained for 30 days; download and archive the signed APK and checksum if you need longer retention.
- This workflow does not create a GitHub Release. Publishing is a separate explicit decision.
- A successful CI build is not device acceptance. Install and launch the artifact on supported ARM64 and ARMv7 devices if both ABIs are advertised.

## Acceptance checklist

- [ ] All four secrets are configured and `MY_KEYSTORE_JKS` is Base64-encoded.
- [ ] Verification gate and unit/Tooling API tests pass on the same reviewed commit.
- [ ] Phase 12 signing-key validation passes.
- [ ] Release APK assembly succeeds.
- [ ] `apksigner verify` succeeds for every APK.
- [ ] SHA-256 checksums are uploaded with the APKs.
- [ ] The signed APK installs and launches on the target device.
- [ ] Phase 9 decompiler, Phase 10 security/performance, and Phase 11 cloud-build runtime acceptance items are separately recorded; do not infer them from Release build success.


## Latest pre-release compile correction

The first compile fix used `isTextSelectable = true`, which CI rejected as a read-only property. The three sites now call `setTextIsSelectable(true)` explicitly in commit `408158241f19c40093a1ce8db4639d9a7a1a5fed`. The verification and CodeQL workflows have been queued on that commit. Do not dispatch the signed Release workflow until the verification gate passes; the Release workflow itself remains manual-only.
