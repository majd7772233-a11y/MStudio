#!/usr/bin/env python3
"""Fast, read-only Phase 10 checks for repository security invariants."""
from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ANDROID = "{http://schemas.android.com/apk/res/android}"
MANIFEST = ROOT / "core/app/src/main/AndroidManifest.xml"
WRAPPER = ROOT / "gradle/wrapper/gradle-wrapper.properties"
POLICY = ROOT / "core/app/src/main/java/dev/mutwakil/androidide/utils/DecompilerInputPolicy.kt"
PERMISSIONS = ROOT / "core/app/src/main/java/dev/mutwakil/androidide/fragments/onboarding/PermissionsFragment.kt"
APPLICATION = ROOT / "core/app/src/main/java/dev/mutwakil/androidide/app/IDEApplication.kt"
GENERAL_PREFERENCES = ROOT / "utilities/preferences/src/main/java/dev/mutwakil/androidide/preferences/internal/GeneralPreferences.kt"
ARABIC_STRINGS = ROOT / "core/resources/src/main/res/values-ar-rSA/strings.xml"


def require(condition: bool, message: str) -> None:
    if not condition:
        print(f"FAIL: {message}", file=sys.stderr)
        raise SystemExit(1)
    print(f"PASS: {message}")


def main() -> None:
    wrapper = WRAPPER.read_text(encoding="utf-8")
    require(
        any(line.strip().startswith("distributionUrl=https\\://services.gradle.org/distributions/")
            for line in wrapper.splitlines()),
        "Gradle wrapper downloads only from the official HTTPS distribution host",
    )
    require(
        "validateDistributionUrl=true" in wrapper.splitlines(),
        "Gradle wrapper distribution URL validation is enabled",
    )

    root = ET.parse(MANIFEST).getroot()
    app = root.find("application")
    require(app is not None, "Android manifest has an application element")
    require(app.get(ANDROID + "allowBackup") == "false", "application backup is disabled")

    exported = []
    component_tags = {"activity", "activity-alias", "receiver", "service", "provider"}
    for component in app:
        if component.tag not in component_tags:
            continue
        filters = component.findall("intent-filter")
        name = component.get(ANDROID + "name", "<unnamed>")
        if filters:
            require(
                component.get(ANDROID + "exported") in {"true", "false"},
                f"{name} declares android:exported explicitly because it has an intent filter",
            )
        if component.get(ANDROID + "exported") == "true":
            exported.append((component.tag, name, component.get(ANDROID + "permission")))

    receiver = next(
        (node for node in app.findall("receiver")
         if node.get(ANDROID + "name") == ".services.InstallationResultReceiver"),
        None,
    )
    require(receiver is not None, "installation callback receiver is registered")
    require(
        receiver.get(ANDROID + "exported") == "false" and not receiver.findall("intent-filter"),
        "installation callback receiver is private and relies on its explicit PendingIntent",
    )

    manifest_text = MANIFEST.read_text(encoding="utf-8")
    require("android:allowBackup=\"true\"" not in manifest_text,
            "manifest does not opt into app-data backup")
    require("android:usesCleartextTraffic=\"true\"" not in manifest_text,
            "manifest does not enable cleartext network traffic globally")

    policy = POLICY.read_text(encoding="utf-8")
    for invariant in (
        "MAX_INPUT_BYTES",
        "MAX_ARCHIVE_ENTRIES",
        "MAX_EXPANDED_ARCHIVE_BYTES",
        "MAX_OUTPUT_FILES",
        "MAX_OUTPUT_BYTES",
        "canIncludeArchiveEntry",
        "isWithinOutputLimits",
    ):
        require(invariant in policy, f"decompiler safety invariant exists: {invariant}")

    permission_source = PERMISSIONS.read_text(encoding="utf-8")
    require("context.packageName" in permission_source,
            "permission Settings intents target the installed applicationId")
    require("BuildInfo.PACKAGE_NAME" not in permission_source,
            "permission Settings intents do not use the inherited source namespace")
    require("ActivityNotFoundException" in permission_source
            and "ACTION_APPLICATION_DETAILS_SETTINGS" in permission_source,
            "permission Settings navigation has a safe fallback")

    application_source = APPLICATION.read_text(encoding="utf-8")
    general_preferences = GENERAL_PREFERENCES.read_text(encoding="utf-8")
    require('GeneralPreferences.selectedLocale = "ar-rSA"' in application_source,
            "Arabic is selected as the first-launch default locale")
    require("defaultLocaleInitialized" in application_source
            and "defaultLocaleInitialized" in general_preferences,
            "first-launch locale default does not overwrite later user choices")
    arabic_strings = ARABIC_STRINGS.read_text(encoding="utf-8")
    for string_name in (
        "permission_settings_unavailable",
        "permission_title_storage",
        "permission_desc_storage",
        "permission_title_install_packages",
        "permission_desc_install_packages",
        "onboarding_title_permissions",
        "onboarding_subtitle_permissions",
        "msg_grant_permissions",
    ):
        require(f'name="{string_name}"' in arabic_strings,
                f"Arabic translation exists for {string_name}")

    print("\nExported Android components requiring human review:")
    for kind, name, permission in exported:
        print(f"  {kind}: {name} (permission={permission or 'none'})")
    release_workflow = ROOT / ".github/workflows/phase12-release.yml"
    require(release_workflow.is_file(), "final signed Release workflow exists")
    release_text = release_workflow.read_text(encoding="utf-8")
    require("workflow_dispatch:" in release_text and "\n  push:" not in release_text,
            "signed Release build is manual-only")
    for secret in (
        "secrets.MY_KEYSTORE_JKS",
        "secrets.KEYSTORE_PASSWORD",
        "secrets.KEY_ALIAS",
        "secrets.KEY_PASSWORD",
    ):
        require(secret in release_text, f"Release workflow consumes configured secret: {secret}")
    for signing_env in (
        "IDE_SIGNING_ALIAS",
        "IDE_SIGNING_KEY_PASS",
        "IDE_SIGNING_STORE_PASS",
        "IDE_SIGNING_KEY_BIN",
    ):
        require(signing_env in release_text, f"Release signing environment is wired: {signing_env}")
    require("assembleRelease" in release_text, "Release workflow assembles the Release variant")
    require("verify --verbose --print-certs" in release_text and "APKSIGNER" in release_text,
            "Release workflow verifies generated APK signatures")
    require("SHA256SUMS.txt" in release_text, "Release workflow emits SHA-256 checksums")
    require("permissions:\n  contents: read" in release_text,
            "Release workflow has read-only repository permissions")
    require("gh release create" not in release_text and "contents: write" not in release_text,
            "Release workflow uploads artifacts only and does not publish a GitHub Release")

    print("\nStatic checks passed. This is not a substitute for merged-manifest, runtime, or device testing.")


if __name__ == "__main__":
    main()
