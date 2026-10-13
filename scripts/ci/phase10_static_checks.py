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

    print("\nExported Android components requiring human review:")
    for kind, name, permission in exported:
        print(f"  {kind}: {name} (permission={permission or 'none'})")
    print("\nStatic checks passed. This is not a substitute for merged-manifest, runtime, or device testing.")


if __name__ == "__main__":
    main()
