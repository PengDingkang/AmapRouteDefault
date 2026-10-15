#!/usr/bin/env python3
"""Local/CI release checks. Uses only the Python standard library and Android SDK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "org.hdhmc.amaproutedefault"
ENTRY = PACKAGE + ".RouteModule"
SCOPE = "com.autonavi.minimap"
VERSION_PATTERN = r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?"


def read_version(root=ROOT):
    values = {}
    for line in (root / "gradle.properties").read_text().splitlines():
        if not line.strip() or line.lstrip().startswith(("#", "!")) or "=" not in line:
            continue
        key, value = (part.strip() for part in line.split("=", 1))
        if key in values:
            raise ValueError(f"Duplicate Gradle property: {key}")
        values[key] = value
    name = values.get("appVersionName", "")
    code = values.get("appVersionCode", "")
    if not re.fullmatch(VERSION_PATTERN, name):
        raise ValueError("appVersionName must be a numeric x.y.z version with an optional prerelease suffix")
    if not re.fullmatch(r"[1-9][0-9]*", code) or int(code) > 2_100_000_000:
        raise ValueError("appVersionCode must be a positive Android version code")
    return {"version_name": name, "version_code": code,
            "source_tag": f"v{name}", "xposed_tag": f"{code}-{name}"}


def extract_notes(text, version):
    lines = text.splitlines()
    heading = re.compile(r"^## \[" + re.escape(version) + r"\](?:\s.*)?$")
    starts = [i for i, line in enumerate(lines) if heading.fullmatch(line)]
    if len(starts) != 1:
        raise ValueError(f"Expected one changelog section for {version}, found {len(starts)}")
    start = starts[0] + 1
    end = next((i for i in range(start, len(lines)) if lines[i].startswith("## [")), len(lines))
    notes = "\n".join(lines[start:end]).strip()
    if not notes:
        raise ValueError(f"Empty changelog section for {version}")
    return notes + "\n"


def rewrite_links(text, repository, commit):
    def replace(match):
        label, link = match.groups()
        if link.startswith(("#", "/")) or re.match(r"[A-Za-z][A-Za-z0-9+.-]*:", link):
            return match.group(0)
        return f"[{label}](https://github.com/{repository}/blob/{commit}/{link.removeprefix('./')})"
    return re.sub(r"\[([^\]]+)\]\(([^\s)]+)\)", replace, text)


def write_xposed_metadata(root, output, repository, commit):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Expected an owner/repository slug")
    if not re.fullmatch(r"[0-9a-f]{40}|[0-9a-f]{64}", commit):
        raise ValueError("Expected a full source commit hash")
    output.mkdir(parents=True, exist_ok=True)
    for name in ("README.md", "README.en.md"):
        (output / name).write_text(rewrite_links((root / name).read_text(), repository, commit))
    (output / "SCOPE").write_text(json.dumps([SCOPE]) + "\n")
    (output / "SOURCE_URL").write_text(f"https://github.com/{repository}\n")
    (output / "SUMMARY").write_text("设置高德地图默认出行方式，支持公共交通、步行、骑行和驾车。\n")


def dex_defines_class(data, descriptor):
    if not data.startswith(b"dex\n") or len(data) < 112:
        return False
    strings_count, strings_offset = struct.unpack_from("<II", data, 56)
    types_count, types_offset = struct.unpack_from("<II", data, 64)
    classes_count, classes_offset = struct.unpack_from("<II", data, 96)
    for i in range(classes_count):
        type_index = struct.unpack_from("<I", data, classes_offset + 32 * i)[0]
        if type_index >= types_count:
            raise ValueError("Invalid DEX class index")
        string_index = struct.unpack_from("<I", data, types_offset + 4 * type_index)[0]
        if string_index >= strings_count:
            raise ValueError("Invalid DEX string index")
        offset = struct.unpack_from("<I", data, strings_offset + 4 * string_index)[0]
        # Skip the ULEB128 UTF-16 length; descriptors here contain only ASCII.
        for _ in range(5):
            value = data[offset]
            offset += 1
            if value < 128:
                break
        else:
            raise ValueError("Invalid DEX string length")
        end = data.index(b"\0", offset)
        if data[offset:end] == descriptor.encode():
            return True
    return False


def verify_archive(apk):
    with zipfile.ZipFile(apk) as archive:
        natives = {name for name in archive.namelist() if name.startswith("lib/") and name.endswith(".so")}
        if natives != {"lib/arm64-v8a/libdexkit.so"}:
            raise ValueError(f"Unexpected native libraries: {sorted(natives)}")
        for name, expected in {"java_init.list": ENTRY, "scope.list": SCOPE}.items():
            actual = archive.read("META-INF/xposed/" + name).decode().strip()
            if actual != expected:
                raise ValueError(f"Unexpected Xposed {name}: {actual}")
        properties = dict(line.split("=", 1) for line in archive.read("META-INF/xposed/module.prop").decode().splitlines()
                          if line.strip() and not line.startswith("#"))
        if properties.get("minApiVersion") != "102" or properties.get("targetApiVersion") != "102":
            raise ValueError("Unexpected Xposed API version")
        if properties.get("staticScope") != "true":
            raise ValueError("Expected static Xposed scope")
        descriptor = "L" + ENTRY.replace(".", "/") + ";"
        dex_files = [name for name in archive.namelist() if re.fullmatch(r"classes(?:[0-9]+)?\.dex", name)]
        if not any(dex_defines_class(archive.read(name), descriptor) for name in dex_files):
            raise ValueError("Xposed entry class was removed or renamed by R8")


def sdk_tool(name):
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise ValueError("ANDROID_HOME must identify the Android SDK")
    version = os.environ.get("ANDROID_BUILD_TOOLS_VERSION", "36.0.0")
    tool = Path(sdk) / "build-tools" / version / name
    if not tool.is_file():
        raise ValueError(f"Android SDK tool missing: {name}")
    return str(tool)


def parse_certificate(text):
    # New apksigner versions identify v3 signers by SDK range instead of "#1".
    # Source Stamp Signer and public-key digests are not APK signing certificates.
    certificates = {value.lower() for value in re.findall(
        r"^Signer[^\r\n]* certificate SHA-256 digest: ([0-9a-fA-F]{64})[ \t\r]*$", text, re.M)}
    if len(certificates) != 1:
        raise ValueError(f"Expected one unique APK signing certificate, found {len(certificates)}")
    return certificates.pop()


def parse_badging(text, version, variant):
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", text, re.M)
    expected = (PACKAGE, version["version_code"], version["version_name"])
    if not package or package.groups() != expected:
        raise ValueError("APK package/version does not match gradle.properties")
    if ("application-debuggable" in text) != (variant == "debug"):
        raise ValueError(f"Unexpected debuggable flag for {variant}")


def verify_apk(apk, variant, version):
    verify_archive(apk)
    result = subprocess.run([sdk_tool("aapt"), "dump", "badging", str(apk)], check=True, capture_output=True, text=True)
    parse_badging(result.stdout, version, variant)
    if variant != "unsigned":
        result = subprocess.run([sdk_tool("apksigner"), "verify", "--print-certs", str(apk)],
                                check=True, capture_output=True, text=True)
        certificate = parse_certificate(result.stdout)
        expected = os.environ.get("RELEASE_CERT_SHA256", "").replace(":", "").strip().lower()
        if variant == "release" and expected and certificate != expected:
            raise ValueError("Release signing certificate does not match RELEASE_CERT_SHA256")
        print("Signing certificate SHA-256:", certificate)
    print(f"Verified {variant} APK: {apk} ({apk.stat().st_size} bytes)")
    print("APK SHA-256:", hashlib.sha256(apk.read_bytes()).hexdigest())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    version_parser = commands.add_parser("version")
    version_parser.add_argument("--expected")
    version_parser.add_argument("--github-output", type=Path)
    notes_parser = commands.add_parser("notes")
    notes_parser.add_argument("--output", type=Path, required=True)
    metadata_parser = commands.add_parser("xposed-metadata")
    metadata_parser.add_argument("--repository", required=True)
    metadata_parser.add_argument("--commit", required=True)
    metadata_parser.add_argument("--output", type=Path, required=True)
    apk_parser = commands.add_parser("verify-apk")
    apk_parser.add_argument("apk", type=Path)
    apk_parser.add_argument("--variant", choices=("debug", "release", "unsigned"), required=True)
    args = parser.parse_args()
    try:
        version = read_version()
        if args.command == "version":
            if args.expected is not None and args.expected != version["version_name"]:
                raise ValueError("Requested release version does not match appVersionName")
            if args.github_output:
                with args.github_output.open("a") as output:
                    for key, value in version.items():
                        output.write(f"{key}={value}\n")
            print(json.dumps(version))
        elif args.command == "notes":
            args.output.write_text(extract_notes((ROOT / "CHANGELOG.md").read_text(), version["version_name"]))
        elif args.command == "xposed-metadata":
            write_xposed_metadata(ROOT, args.output, args.repository, args.commit)
        else:
            verify_apk(args.apk, args.variant, version)
    except (ValueError, OSError, KeyError, IndexError, struct.error, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Release check failed: {error}\n")


if __name__ == "__main__":
    main()
