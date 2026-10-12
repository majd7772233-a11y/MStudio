<p align="center">
  <img src="./images/mstudio-icon.svg" alt="MStudio" width="80" height="80"/>
</p>

<h2 align="center"><b>MStudio</b></h2>
<p align="center">
  An IDE to develop real, Gradle-based Android applications on Android devices.
</p>

<p align="center">
<!-- Build and test -->
<img src="https://github.com/majd7772233-a11y/MStudio/actions/workflows/build.yml/badge.svg" alt="Build and test workflow">
<!-- CodeFactor -->
<img src="https://www.codefactor.io/repository/github/majd7772233-a11y/mstudio/badge/dev" alt="CodeFactor">
<!-- License -->
<img src="https://img.shields.io/badge/License-GPLv3-blue.svg" alt="License">

</p>

<p align="center">
  <a href="./MSTUDIO_ROADMAP.md">Project roadmap</a> &nbsp; &#8226; &nbsp;
  <a href="./CONTRIBUTING.md">Contributing guide</a>
</p>

<p align="center">
  <a href="https://github.com/majd7772233-a11y/MStudio/issues/new?labels=bug&title=%5BBug%5D%3A+">Report a bug</a> &nbsp; &#8226; &nbsp;
  <a href="https://github.com/majd7772233-a11y/MStudio/issues/new?labels=enhancement&title=%5BFeature%5D%3A+">Request a feature</a> &nbsp; &#8226; &nbsp;
  <a href="https://github.com/majd7772233-a11y/MStudio/issues">MStudio support and discussion via GitHub Issues</a>
</p>

## Features

This checklist describes features present in the source. It is not a claim that every feature has passed device/runtime verification; see the [roadmap](./MSTUDIO_ROADMAP.md) for current evidence and known limitations.

- [x] Gradle support.
- [x] `JDK 21` available for use.
- [x] Terminal with necessary packages.
- [x] Custom environment variables (for Build & Terminal).
- [x] SDK Manager (Available via terminal).
- [x] API information for classes and their members (since, removed, deprecated).
- [x] Log reader (shows your app's logs in real-time)
- [x] Language servers
    - [x] Java
    - [x] XML
    - [x] Kotlin
- [ ] UI Designer
    - [x] Layout inflater
    - [x] Resolve resource references
    - [x] Auto-complete resource values when user edits attributes using the attribute editor
    - [x] Drag & Drop
    - [x] Visual attribute editor
    - [x] Android Widgets
- [ ] String Translator
- [ ] Asset Studio (Drawable & Icon Maker)
- [x] Git
- [x] Jetpack Compose preview

## Installation

MStudio is under active development. **No APK should be treated as a verified production release until its signing identity, installation, startup, and device smoke tests have been recorded.**

- Check the [project roadmap](./MSTUDIO_ROADMAP.md) for current implementation and verification status.
- The [GitHub Actions page](https://github.com/majd7772233-a11y/MStudio/actions) shows automation runs; workflow artifacts are CI outputs, not a promise that an APK has passed installation or runtime testing.
- When a release is deliberately published, the repository's [Releases page](https://github.com/majd7772233-a11y/MStudio/releases) is the canonical destination. No F-Droid package or separate MStudio website is claimed here.

## Limitations

- Package identity: the installed application ID is `com.magd.mstudio`, while the implementation namespace remains `dev.mutwakil.androidide`. The DocumentsProvider authority still uses `dev.mutwakil.androidide.documents`; side-by-side installation with upstream AndroidIDE is not claimed or verified.

- MStudio's inherited Gradle integration requires Android Gradle Plugin v7.2.0 or newer according to the current source documentation; compatibility still needs verification across supported projects.
- The Android SDK Manager can be used from the included terminal, but tool availability depends on the Android-compatible packages available to the runtime.
- Official NDK support is not available because the required NDK tools have not been built for Android.

The app is still being developed actively. It's in beta stage and may not be stable. if you have any
issues using the app, please let us know.

## Contributing

See the [contributing guide](./CONTRIBUTING.md).

Translations currently live in the Android resources under [core/resources/src/main/res](./core/resources/src/main/res). MStudio does not claim a separate Crowdin translation project.

## Thanks to

- [Rosemoe](https://github.com/Rosemoe) for the
  awesome [CodeEditor](https://github.com/Rosemoe/sora-editor)
- [Termux](https://github.com/termux) for [Terminal Emulator](https://github.com/termux/termux-app)
- [Bogdan Melnychuk](https://github.com/bmelnychuk)
  for [AndroidTreeView](https://github.com/bmelnychuk/AndroidTreeView)
- [George Fraser](https://github.com/georgewfraser) for
  the [Java Language Server](https://github.com/georgewfraser/java-language-server)
- [Appdevforall](https://github.com/appdevforall) for the
  awesome [CodeOnTheGo](https://github.com/appdevforall/CodeOnTheGo)

Thanks to all developers and upstream contributors whose work makes MStudio possible.

## Contact Us

- [MStudio source repository](https://github.com/majd7772233-a11y/MStudio)
- [Upstream AndroidIDE project](https://github.com/AndroidIDE-Dev/AndroidIDE) — for upstream source history and attribution only; use MStudio's GitHub Issues for MStudio-specific support.

## License

MStudio is derived from the upstream AndroidIDE project and retains its GPL-3.0 licensing obligations and applicable source notices. The following inherited notice is preserved from AndroidIDE:

```
AndroidIDE is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

AndroidIDE is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
```

For MStudio-specific licensing questions, open an issue in the [MStudio repository](https://github.com/majd7772233-a11y/MStudio/issues).
