# Contributing to MStudio

MStudio is an in-progress Android IDE based on the open-source AndroidIDE project. Contributions are welcome, but please read the current project state before starting work.

## Before making changes

1. Read [GPT.md](./GPT.md) for the current engineering handoff and known limitations.
2. Read [MSTUDIO_ROADMAP.md](./MSTUDIO_ROADMAP.md) and keep work aligned with its current phase. If a useful task is missing, propose it in the roadmap before implementing it.
3. Check [existing issues](https://github.com/majd7772233-a11y/MStudio/issues) before opening a new one.
4. Preserve upstream notices, GPL-3.0 requirements, and attribution. Audit any new dependency or bundled tool for its license.

## Report bugs and suggest improvements

Use [GitHub Issues](https://github.com/majd7772233-a11y/MStudio/issues). Include the device model, Android version, app build/commit when known, steps to reproduce, expected behavior, actual behavior, and relevant logs. Remove passwords, access tokens, signing material, personal data, and other secrets before attaching logs.

## Code contributions

- Work on a focused branch and describe the user-visible problem the change solves.
- Inspect the latest source and relevant callers/tests before editing.
- Preserve the existing Gradle Tooling API build path; do not introduce a competing APK builder as a shortcut.
- Keep user-facing strings in Android resources and preserve RTL/localization behavior.
- Do not claim a fix is verified unless a reproducible test, CI result, or named-device result supports it.
- Never commit keystores, signing secrets, API tokens, private project data, or generated build artifacts.
- Follow the existing source formatting conventions: two-space indentation for Java/Kotlin/XML where used by the surrounding file, and match the formatting of the file being edited.

## Build and verification

MStudio is under active development, and parts of the inherited AndroidIDE documentation may not describe the fork accurately. Check the live build scripts and roadmap rather than assuming every feature or build variant works. When verification is appropriate, record the exact commit, command or workflow, result, and artifact/device details. Do not trigger release or publishing workflows without explicit authorization.

## Pull requests

Open a pull request against the branch specified by the current repository workflow (currently `dev`, unless the maintainers announce otherwise). Explain the change, the reason for it, any compatibility impact, and the checks actually performed. Small, reviewable changes are preferred.

## Upstream attribution

MStudio is derived from [AndroidIDE](https://github.com/AndroidIDE-Dev/AndroidIDE). Preserve the applicable upstream copyright notices and GPL-3.0 license terms. MStudio-specific bug reports and contributions should use this repository's issue tracker and contribution process.
