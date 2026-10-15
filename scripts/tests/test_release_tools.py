import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import release_tools as release


def dex_fixture(descriptor, defined=True):
    encoded = descriptor.encode()
    data = bytearray(152)
    data[:8] = b"dex\n035\0"
    struct.pack_into("<II", data, 56, 1, 112)
    struct.pack_into("<II", data, 64, 1, 116)
    struct.pack_into("<II", data, 96, int(defined), 120)
    struct.pack_into("<I", data, 112, 152)
    return bytes(data) + bytes([len(encoded)]) + encoded + b"\0"


class ReleaseToolsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def properties(self, name="0.2.2", code="4"):
        (self.root / "gradle.properties").write_text(f"appVersionName={name}\nappVersionCode={code}\n")
        return release.read_version(self.root)

    def test_version_and_xposed_tag(self):
        self.assertEqual(self.properties()["xposed_tag"], "4-0.2.2")
        self.assertEqual(self.properties("0.3.0-beta.1", "5")["source_tag"], "v0.3.0-beta.1")

    def test_rejects_invalid_versions_and_codes(self):
        for name in ["../0.2.2", "0.2.2;echo unsafe", "0.2.2 unsafe", "v0.2.2", "01.2.2"]:
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.properties(name)
        for code in ["0", "-1", "2147483647", "4x"]:
            with self.subTest(code=code), self.assertRaises(ValueError):
                self.properties(code=code)

    def test_exact_changelog_section(self):
        text = "## [0.2.20]\nwrong\n## [0.2.2] - 2026-10-16\nright\n## [0.2.1]\nold\n"
        self.assertEqual(release.extract_notes(text, "0.2.2"), "right\n")
        for invalid in ["## [0.2.20]\nwrong", "## [0.2.2]\n\n## [0.2.1]\nold",
                        "## [0.2.2]\nfirst\n## [0.2.2]\nsecond"]:
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                release.extract_notes(invalid, "0.2.2")

    def test_metadata_allowlist_and_source_links(self):
        for name in ["README.md", "README.en.md"]:
            (self.root / name).write_text("[guide](docs/release.md) [web](https://example.com) [anchor](#use)\n")
        (self.root / "local.properties").write_text("not-for-publication")
        output = self.root / "metadata"
        commit = "a" * 40
        release.write_xposed_metadata(self.root, output, "owner/project", commit)
        self.assertEqual({p.name for p in output.iterdir()}, {"README.md", "README.en.md", "SCOPE", "SOURCE_URL", "SUMMARY"})
        self.assertEqual((output / "SCOPE").read_text(), '["com.autonavi.minimap"]\n')
        text = (output / "README.md").read_text()
        self.assertIn(f"https://github.com/owner/project/blob/{commit}/docs/release.md", text)
        self.assertIn("[web](https://example.com)", text)
        self.assertIn("[anchor](#use)", text)

    def archive(self, *, extra_abi=False, defined=True, scope=release.SCOPE, entry=release.ENTRY):
        apk = self.root / "fixture.apk"
        with zipfile.ZipFile(apk, "w") as z:
            z.writestr("lib/arm64-v8a/libdexkit.so", b"fixture")
            if extra_abi:
                z.writestr("lib/x86_64/libdexkit.so", b"wrong ABI")
            z.writestr("META-INF/xposed/java_init.list", entry)
            z.writestr("META-INF/xposed/scope.list", scope)
            z.writestr("META-INF/xposed/module.prop", "minApiVersion=102\ntargetApiVersion=102\nstaticScope=true\n")
            z.writestr("classes.dex", dex_fixture("L" + release.ENTRY.replace(".", "/") + ";", defined))
        return apk

    def test_archive_accepts_retained_entry_and_arm64_only(self):
        release.verify_archive(self.archive())

    def test_archive_rejects_wrong_abi_scope_or_removed_entry(self):
        for args in [{"extra_abi": True}, {"defined": False}, {"scope": "android"}, {"entry": "renamed.Entry"}]:
            with self.subTest(args=args), self.assertRaises(ValueError):
                release.verify_archive(self.archive(**args))

    def test_badging_requires_correct_version_package_and_debug_flag(self):
        version = self.properties()
        good = "package: name='org.hdhmc.amaproutedefault' versionCode='4' versionName='0.2.2'\n"
        release.parse_badging(good, version, "release")
        release.parse_badging(good + "application-debuggable\n", version, "debug")
        for text, variant in [(good, "debug"), (good + "application-debuggable", "release"),
                              (good.replace("'4'", "'5'"), "release"),
                              (good.replace("org.hdhmc.amaproutedefault", "wrong.package"), "release")]:
            with self.subTest(text=text, variant=variant), self.assertRaises(ValueError):
                release.parse_badging(text, version, variant)

    def test_certificate_pin_rejects_wrong_signer(self):
        version = self.properties()
        apk = self.archive()
        badging = "package: name='org.hdhmc.amaproutedefault' versionCode='4' versionName='0.2.2'"
        results = [subprocess.CompletedProcess([], 0, stdout=badging),
                   subprocess.CompletedProcess([], 0, stdout="Signer #1 certificate SHA-256 digest: " + "a" * 64)]
        with patch.object(release, "sdk_tool", return_value="fixture"), \
             patch.object(release.subprocess, "run", side_effect=results), \
             patch.dict(os.environ, {"RELEASE_CERT_SHA256": "b" * 64}), self.assertRaises(ValueError):
            release.verify_apk(apk, "release", version)

    def test_old_and_new_certificate_formats(self):
        digest = "a" * 64
        for signer in ["Signer #1", "Signer (minSdkVersion=28, maxSdkVersion=2147483647)"]:
            output = f"{signer} certificate SHA-256 digest: {digest}\n"
            output += "Source Stamp Signer certificate SHA-256 digest: " + "b" * 64 + "\n"
            output += f"{signer} public key SHA-256 digest: " + "c" * 64 + "\n"
            with self.subTest(signer=signer):
                self.assertEqual(release.parse_certificate(output), digest)
                self.assertEqual(release.parse_certificate(output + output), digest)

    def test_rejects_missing_or_ambiguous_signing_certificates(self):
        for output in ["no certificate", "Signer #1 certificate SHA-256 digest: 1234",
                       "Signer #1 certificate SHA-256 digest: " + "a" * 64 + "\n"
                       "Signer #2 certificate SHA-256 digest: " + "b" * 64]:
            with self.subTest(output=output), self.assertRaises(ValueError):
                release.parse_certificate(output)

    def test_uses_installed_ci_build_tools_instead_of_newest_preinstalled(self):
        for version in ["36.0.0", "38.0.0"]:
            directory = self.root / "build-tools" / version
            directory.mkdir(parents=True)
            (directory / "apksigner").write_text("fixture")
        with patch.dict(os.environ, {"ANDROID_HOME": str(self.root), "ANDROID_BUILD_TOOLS_VERSION": "36.0.0"}):
            self.assertEqual(release.sdk_tool("apksigner"), str(self.root / "build-tools/36.0.0/apksigner"))


class XposedSyncTest(unittest.TestCase):
    def test_empty_repo_idempotence_and_preserving_unrelated_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bare = root / "remote.git"
            metadata = root / "metadata"
            metadata.mkdir()
            names = ("README.md", "README.en.md", "SCOPE", "SOURCE_URL", "SUMMARY")
            for name in names:
                (metadata / name).write_text("fixture metadata\n")
            binary = root / "bin"
            binary.mkdir()
            gh = binary / "gh"
            gh.write_text('#!/usr/bin/env bash\nset -eu\nif [[ "$1" == api ]]; then echo true; fi\n')
            gh.chmod(0o755)
            config = root / "gitconfig"
            env = dict(os.environ, GH_TOKEN="test-fixture", XPOSED_REPOSITORY="fixture/module",
                       METADATA_DIR=str(metadata), GITHUB_OUTPUT=str(root / "output"),
                       GIT_CONFIG_GLOBAL=str(config), GIT_CONFIG_NOSYSTEM="1",
                       PATH=str(binary) + os.pathsep + os.environ["PATH"])

            def git(*args):
                return subprocess.check_output(["git", *args], env=env, stderr=subprocess.STDOUT, text=True).strip()

            git("init", "--bare", "-b", "main", str(bare))
            git("config", "--file", str(config), f"url.{bare.as_uri()}.insteadOf", "https://github.com/fixture/module.git")
            script = Path(__file__).resolve().parents[1] / "sync_xposed.sh"

            def sync():
                subprocess.run(["bash", str(script)], env=env, check=True, capture_output=True, text=True)
                return git("--git-dir", str(bare), "rev-parse", "main")

            first = sync()
            self.assertEqual(first, sync())
            self.assertEqual(set(git("--git-dir", str(bare), "ls-tree", "--name-only", "main").splitlines()), set(names))
            clone = root / "clone"
            git("clone", str(bare), str(clone))
            (clone / "unrelated.txt").write_text("preserve me")
            git("-C", str(clone), "add", "unrelated.txt")
            git("-C", str(clone), "-c", "user.name=Fixture", "-c", "user.email=fixture@example.com", "commit", "-m", "fixture")
            git("-C", str(clone), "push", "origin", "main")
            (metadata / "README.md").write_text("updated metadata\n")
            self.assertNotEqual(first, sync())
            self.assertEqual(git("--git-dir", str(bare), "show", "main:unrelated.txt"), "preserve me")
            self.assertEqual(git("--git-dir", str(bare), "show", "main:README.md"), "updated metadata")


if __name__ == "__main__":
    unittest.main()
