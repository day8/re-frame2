#!/usr/bin/env python3
"""Exercise skill installers against disposable sources and destinations only.

On POSIX this tests the shell installer; on Windows it tests both Git Bash and
Windows PowerShell, including actual junction replacement and target survival.
No invocation uses the default destination or the checkout's real skill source.
"""

import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parent.parent
WINDOWS = os.name == "nt"


def is_link(path):
    info = path.lstat()
    return stat.S_ISLNK(info.st_mode) or bool(
        getattr(info, "st_file_attributes", 0)
        & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0)
    )


def unlink(path):
    if WINDOWS and path.is_dir() and not path.is_symlink():
        os.rmdir(path)  # Nonrecursive: removes a junction, never its target.
    elif WINDOWS and not path.is_symlink():
        os.rmdir(path)  # A broken directory junction still has an entry.
    else:
        path.unlink()


def disarm_links(directory):
    for path in directory.iterdir():
        if is_link(path):
            unlink(path)
        elif path.is_dir():
            disarm_links(path)


class InstallerTests(unittest.TestCase):
    def setUp(self):
        scratch = ROOT / "ai"
        scratch.mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(
            prefix=f"skill-install-{ROOT.name}-", dir=scratch
        )
        self.base = Path(self.temp.name)
        # Quotes and spaces in BOTH source and destination are ordinary paths.
        self.repo = self.base / "author's repository"
        self.source = self.repo / "skills" / "demo"
        self.source.mkdir(parents=True)
        (self.source / "SKILL.md").write_text("current skill\n")
        (self.repo / "scripts").mkdir()
        for name in ("install-skills.sh", "install-skills.ps1"):
            shutil.copyfile(ROOT / "scripts" / name, self.repo / "scripts" / name)
        shell = shutil.which("sh")
        if not shell and WINDOWS:
            git = shutil.which("git")
            if git:
                candidate = Path(git).parent.parent / "bin" / "sh.exe"
                if candidate.is_file():
                    shell = str(candidate)
        self.assertIsNotNone(shell, "sh (Git Bash on Windows) is required")
        self.commands = [[shell, str(self.repo / "scripts/install-skills.sh")]]
        if WINDOWS:
            self.commands.append([
                "powershell.exe", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-File",
                str(self.repo / "scripts/install-skills.ps1"),
            ])

    def tearDown(self):
        # Never let recursive fixture cleanup encounter a live junction.
        disarm_links(self.base)
        self.temp.cleanup()

    def run_installer(self, command, target, *flags, expected=0):
        powershell = command[0] == "powershell.exe"
        args = ["-Target" if powershell else "--target", str(target)]
        args.extend(("-" + flag.title()) if powershell else "--" + flag
                    for flag in flags)
        result = subprocess.run(command + args, cwd=self.repo, text=True,
                                capture_output=True, timeout=60)
        self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
        return result

    def targets(self):
        for index, command in enumerate(self.commands):
            yield command, self.repo / f"user's skills {index}"

    def make_link(self, link, target):
        if WINDOWS:
            env = dict(os.environ, INSTALL_TEST_LINK=str(link),
                       INSTALL_TEST_TARGET=str(target))
            subprocess.run([
                "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "$ErrorActionPreference = 'Stop'; New-Item -ItemType Junction "
                "-Path $env:INSTALL_TEST_LINK -Target $env:INSTALL_TEST_TARGET "
                "| Out-Null",
            ], env=env, check=True, capture_output=True)
        else:
            link.symlink_to(target, target_is_directory=True)

    def assert_installed(self, target):
        link = target / "demo"
        self.assertTrue(is_link(link))
        self.assertEqual(link.resolve(), self.source.resolve())
        self.assertEqual((link / "SKILL.md").read_text(), "current skill\n")

    def test_install_check_and_repeat_preserve_link(self):
        for command, target in self.targets():
            with self.subTest(installer=command[0]):
                self.run_installer(command, target)
                self.assert_installed(target)
                before = (target / "demo").lstat().st_mtime_ns
                self.run_installer(command, target, "check")
                self.run_installer(command, target)
                self.assertEqual((target / "demo").lstat().st_mtime_ns, before)

    def test_check_does_not_create_destination(self):
        for command, target in self.targets():
            with self.subTest(installer=command[0]):
                self.run_installer(command, target, "check", expected=1)
                self.assertFalse(target.exists())

    def test_repoint_preserves_previous_source(self):
        for command, target in self.targets():
            with self.subTest(installer=command[0]):
                previous = self.base / f"previous-{target.name}"
                previous.mkdir()
                sentinel = previous / "keep.txt"
                sentinel.write_text("keep previous source")
                target.mkdir()
                self.make_link(target / "demo", previous)
                self.run_installer(command, target)
                self.assert_installed(target)
                self.assertEqual(sentinel.read_text(), "keep previous source")

    def test_repair_broken_link(self):
        for command, target in self.targets():
            with self.subTest(installer=command[0]):
                previous = self.base / f"gone-{target.name}"
                previous.mkdir()
                target.mkdir()
                self.make_link(target / "demo", previous)
                previous.rmdir()
                self.run_installer(command, target)
                self.assert_installed(target)

    def test_copies_and_files_require_force(self):
        for command, target in self.targets():
            for directory in (False, True):
                with self.subTest(installer=command[0], directory=directory):
                    destination = target / str(directory)
                    destination.mkdir(parents=True)
                    existing = destination / "demo"
                    if directory:
                        existing.mkdir()
                    sentinel = existing / "keep.txt" if directory else existing
                    sentinel.write_text("local edits")
                    self.run_installer(command, destination, expected=1)
                    self.assertEqual(sentinel.read_text(), "local edits")
                    self.run_installer(command, destination, "force")
                    self.assert_installed(destination)

    def test_relative_destination(self):
        for index, command in enumerate(self.commands):
            with self.subTest(installer=command[0]):
                relative = Path(f"relative skills {index}")
                self.run_installer(command, relative)
                self.assert_installed(self.repo / relative)
                self.run_installer(command, relative, "check")


if __name__ == "__main__":
    print(f"gate root: {ROOT}", flush=True)
    unittest.main(verbosity=2)
