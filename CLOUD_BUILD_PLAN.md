# MStudio Cloud Build — Deferred Feature Design

> **Status: DEFERRED.** Local on-device Gradle build is the current priority. This file records the optional future capability only; it does not implement GitHub login, workflow generation or orchestration.

## 1. Goal

A user may optionally authorize GitHub, select a repository/branch, let MStudio inspect the project's build files, preview a generated/updated GitHub Actions workflow, approve the changes, dispatch it, view run/job/step state and available logs, and download an APK artifact from that exact run. Users who never connect GitHub must still be able to build locally. Cloud is never an automatic upload fallback.

Show repo/branch, proposed workflow diff, commit destination, permissions, task/variant, artifact paths/retention, expected Actions usage and privacy implications before approval.

## 2. Preconditions and references

Do not begin until local MVP in MSTUDIO_ROADMAP.md passes. Project/module/variant detection must be reliable enough to choose workflow template without guessing.

At implementation time, re-check current official GitHub docs for auth, permission requirements, workflow-file writes, dispatch, run/job/step status, logs/artifacts, rate limits and token lifecycle. Start with:
- Fine-grained permission reference: https://docs.github.com/en/rest/authentication/permissions-required-for-fine-grained-personal-access-tokens
- Contents API: https://docs.github.com/en/rest/repos/contents#create-or-update-file-contents
- Workflow dispatch: https://docs.github.com/en/rest/actions/workflows#create-a-workflow-dispatch-event
- Artifacts API: https://docs.github.com/en/rest/actions/artifacts

These are starting points, not proof that current permission/endpoint details have already been verified.

## 3. Proposed flow

1. User opens optional Cloud Build.
2. App explains that GitHub-hosted infrastructure executes project build logic and may retain logs/artifacts under GitHub's policies.
3. User authorizes through a supported public-client flow. No confidential OAuth client secret in APK.
4. User chooses exact repo and branch.
5. MStudio reads metadata/tree and analyzes wrapper/settings/build scripts/modules/AGP/JDK/DSL/variants.
6. App reports supported/unsupported/uncertain rather than guessing.
7. It selects a reviewed workflow template and previews YAML changes, commands, task/variant, JDK, permissions, artifact paths and target branch.
8. User approves. Prefer separate branch or explicit destination; never overwrite existing workflow/default branch silently.
9. App writes the workflow through correct API route and verifies the commit/path.
10. User taps Build; app dispatches exact workflow/ref and records run ID.
11. App refreshes run/job/step status, offers cancellation where supported and explains failures.
12. At completion, list artifacts from exact run, download selected APK with progress/integrity checks where feasible and offer normal Android install.
13. Allow disconnect/revocation and optional cleanup of generated branch/workflow.

## 4. Least-privilege authorization

“Read/write permissions” does not mean request every GitHub permission. Choose minimum permissions for actual API operations and repository scope where supported. Fine-grained tokens and GitHub Apps differ; verify current rules.

Potential categories to verify:
- Metadata read for repository/branch discovery.
- Contents read for project inspection.
- Contents write for file/branch/commit operations when used.
- Workflows write for workflow-file creation/update where required.
- Actions read for run/job/step/artifact/log metadata.
- Actions write for dispatch and later cancellation/rerun only if offered.

Do not request Administration, Secrets, pull-request write or organization-wide access without a specific required endpoint. Never embed private/client secret in APK. Securely store needed credentials, handle expiry/revocation and clear access on disconnect. Never put personal token in workflow YAML, Gradle files, commits, logs, URLs or artifacts.

## 5. Workflow architecture

Keep reviewed workflow templates in version control, not YAML concatenated ad hoc in UI classes. Separate services/models for GitHub auth/client, repo inspection, compatibility report, template selection, YAML validation/diff, commit writer, dispatcher/run watcher and artifact downloader.

Workflow should choose JDK/Android toolchain explicitly, use project wrapper, run chosen task/variant, cache responsibly, upload predictably named APK artifacts with documented retention and set least workflow-job token permissions. Pin external Actions to trusted immutable revisions where appropriate. Never pass personal token or unrelated release secrets to Gradle scripts.

If a workflow already exists, inspect and offer reuse. Preview and obtain approval before any .github/workflows change. Avoid build loops from workflow commits retriggering indefinitely.

## 6. Status/logs: reflect actual API

Don't promise raw live terminal streaming until verified. Initial implementation can poll workflow runs/jobs/steps and show queue/running/completed/conclusion, timings, current step when provided, failures and artifact readiness. Log endpoints may provide downloadable archives instead of continuous stdout; verify behavior. If continuous streaming is unavailable, refresh status often and retrieve available log snapshots instead of inventing an API or scraping GitHub website UI.

Handle network drops, queue delays, rate limits, token expiry/revocation, cancellation, unavailable/expired artifacts and interrupted downloads with retry/backoff and clear messages.

## 7. Security and cost

- Treat Gradle scripts as arbitrary executable code on the hosted runner.
- Never pass personal GitHub token or unrelated secrets to build job.
- Set minimum workflow-job token permissions.
- Show repo/workflow diff before commit; preserve existing files.
- Review/pin actions, set timeout/cancel, prevent workflow loops.
- Explain hosted execution and current Actions/retention limits as applicable.
- Cloud remains opt-in and local build works disconnected.
- Never log credentials or upload code/logs without explicit user-facing behavior.

## 8. Deferred acceptance checklist

- [ ] Local build works with cloud disconnected.
- [ ] Supported public-client authorization, no APK-embedded client secret.
- [ ] User chooses exact repo/branch and previews permissions/file diff.
- [ ] Permissions are minimum for implemented endpoints.
- [ ] Project analyzer reports compatibility without guessing.
- [ ] Workflow validates and existing files aren't overwritten silently.
- [ ] Dispatch targets exact workflow/ref and stores run ID.
- [ ] Run/job/step status, failure and cancellation handled.
- [ ] UI accurately reflects log/streaming API capability.
- [ ] Artifact tied to exact run and download has progress/retry.
- [ ] Auth expiry, rate limits and artifact expiry are recoverable.
- [ ] No token/unrelated secrets exposed to build tasks.
- [ ] Disconnect/revocation works and local IDE continues.
- [ ] Public/private repository flows and APK installation have test evidence.

**Cloud build must not delay local Gradle execution, tool setup, project import or local MVP.**
