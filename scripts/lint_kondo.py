#!/usr/bin/env python3
"""Run CI's clj-kondo gate LOCALLY — same version, same paths, same flags.

WHY THIS EXISTS (rf2-x1mz).  A worker edited
`implementation/hicasso/src/re_frame/hicasso/impl/overlay.cljs`, ran the local
spine, got green, pushed, and CI's `clj-kondo` job went red.  No local gate in
this repo could have caught it, for TWO INDEPENDENT REASONS — either one
sufficient on its own:

  1. VERSION SKEW.  The only local lane that ran clj-kondo at all
     (`implementation/hicasso/scripts/check_lint_export.py`) resolved the
     binary off `PATH`, whatever version that happened to be.  Measured on the
     exact defect: clj-kondo 2025.10.23 reports `errors: 0, exit 0`; the pinned
     2026.04.15 reports `Expected: array, received: function` and exits 3.  And
     the pin is not merely absent from that machine — it is UNOBTAINABLE the
     usual way: the npm distribution of clj-kondo stops at 2025.10.23, so
     "install the pin" is not advice a developer can follow.  Hence the
     provisioning half below.

  2. PATH COVERAGE.  That lane's `--lint` targets are two fixture files and
     `hicasso/testbed`.  It never linted `hicasso/src/`, so the file was
     outside every local lane REGARDLESS of version — and even had it been
     inside, that gate reads only findings in the `re-frame.hicasso/*`
     namespace, so a built-in kondo ERROR like this one is invisible to it.

WHAT THE DEFECT COST, so the stakes are concrete rather than stylistic: two
`(aset f "displayName" "...")` calls stamping a React displayName onto a
FUNCTION with the ARRAY accessor.  With `:checked-arrays` off (the repo
default) `aset`'s 3-arity emits character-identical output to `unchecked-set`,
so the shipped consequence was none; with `:checked-arrays :error` it THROWS,
and because these are top-level `def` forms the throw lands at NAMESPACE
INITIALIZATION — turning on a standard hardening flag would take the whole
overlay module out at load time.

NOTHING ABOUT THE GATE IS RESTATED HERE.  The version pin, the flags and the
`--lint` target list are all READ from the `Run clj-kondo` step of
`.github/workflows/lint.yml`, through the same workflow parser
`check_fast_pr_gap.py` uses to audit that file.  A copy of the command in this
file would be a second description of the gate, and a second description drifts
— which is the shape of defect this script exists to close, not to repeat.  Add
a `--lint` root in the workflow and this runner lints it on the next run,
including in its decision about whether the lane is armed at all.

A SKEWED BINARY IS NOT A FALLBACK.  If the pin cannot be provisioned this
script exits 2 and says what went unchecked; it never runs a different version
and reports a verdict.  A green from the wrong version is precisely the false
assurance that produced rf2-x1mz, and the spine's caller turns exit 2 into a
LOUD SKIP rather than a pass.

    python scripts/lint_kondo.py                  provision + run the gate
    python scripts/lint_kondo.py --classify F...  is the lane armed by F...?
    python scripts/lint_kondo.py --print-binary   path to the pinned binary
    python scripts/lint_kondo.py --self-test      prove the gate's red

Stdlib only, like every other checker in this directory: CI jobs that run these
install no pip packages.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import platform
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_fast_pr_gap import (  # noqa: E402  (path shim must precede)
    _KONDO_PIN_RE,
    parse_workflow,
)

REPO_ROOT = Path(__file__).resolve().parent.parent
LINT_WORKFLOW = REPO_ROOT / ".github" / "workflows" / "lint.yml"
KONDO_JOB = "clj-kondo"
KONDO_STEP = "Run clj-kondo"

# Exit codes with meanings the caller acts on.  The gate's own verdict passes
# through untouched (clj-kondo exits 3 on an ERROR-level finding), so 2 is
# reserved for "the gate did not run" and can never collide with it.
EXIT_UNPROVISIONED = 2

RELEASES = "https://github.com/clj-kondo/clj-kondo/releases/download"

# The release archive for this host.  clj-kondo publishes one per platform and
# the names are not derivable from `sys.platform` alone, so the table is
# explicit — but it is a table of PLATFORMS, not of versions: a pin bump needs
# no edit here.  Linux takes the `-static-` build, which is what lint.yml
# installs and what needs no glibc of a particular vintage.
ARCHIVES = {
    ("Linux", "x86_64"):   "clj-kondo-{v}-linux-static-amd64.zip",
    ("Linux", "amd64"):    "clj-kondo-{v}-linux-static-amd64.zip",
    ("Linux", "aarch64"):  "clj-kondo-{v}-linux-aarch64.zip",
    ("Linux", "arm64"):    "clj-kondo-{v}-linux-aarch64.zip",
    ("Darwin", "x86_64"):  "clj-kondo-{v}-macos-amd64.zip",
    ("Darwin", "arm64"):   "clj-kondo-{v}-macos-aarch64.zip",
    ("Windows", "AMD64"):  "clj-kondo-{v}-windows-amd64.zip",
    ("Windows", "x86_64"): "clj-kondo-{v}-windows-amd64.zip",
}


# ---------------------------------------------------------------------------
# What CI runs, read from CI
# ---------------------------------------------------------------------------

class GateUnreadable(Exception):
    """lint.yml no longer describes the gate this runner reproduces."""


def gate(workflow: Path = LINT_WORKFLOW):
    """`(pin, argv)` for CI's clj-kondo gate, read from the workflow.

    `argv` is the step's own command with the leading `clj-kondo` word left in
    place for the caller to swap for a resolved binary — so the flags, their
    order and the `--lint` roots are CI's, not this file's.
    """
    jobs = parse_workflow(workflow.read_text(encoding="utf-8"), workflow.name)
    job = jobs.get(KONDO_JOB)
    if job is None:
        raise GateUnreadable(
            "%s has no `%s` job; this runner reproduces that job's gate and "
            "can no longer find it" % (workflow.name, KONDO_JOB))

    pin = None
    argv = None
    for step in job.steps:
        if step.run:
            m = _KONDO_PIN_RE.search(step.run)
            if m and pin is None:
                pin = m.group(1)
        if step.name == KONDO_STEP:
            argv = step.command.split()

    if pin is None:
        raise GateUnreadable(
            "no clj-kondo version pin in the `%s` job (looked for a release "
            "URL of the form `.../clj-kondo/releases/download/v<pin>/...`); "
            "running an unpinned binary proves nothing, which is rf2-x1mz"
            % KONDO_JOB)
    if not argv or argv[0] != "clj-kondo":
        raise GateUnreadable(
            "the `%s` step of the `%s` job is missing, or no longer begins "
            "with a bare `clj-kondo` invocation, so its command cannot be "
            "reproduced locally" % (KONDO_STEP, KONDO_JOB))
    return pin, argv


def lint_roots(argv) -> list[str]:
    """The `--lint` targets in a parsed gate command, in order."""
    return [argv[i + 1] for i, tok in enumerate(argv)
            if tok == "--lint" and i + 1 < len(argv)]


# ---------------------------------------------------------------------------
# Provisioning the pinned binary
# ---------------------------------------------------------------------------

def cache_dir(pin: str) -> Path:
    """Where the pinned binary is kept.

    OUTSIDE the repository, deliberately.  This project runs many linked
    worktrees at once; an in-tree cache would download 18 MB per worktree and
    would need a `.gitignore` entry to stay out of everyone's `git status`.
    One shared user-level cache, keyed by pin, costs one download per machine
    per bump and is invisible to git by construction.
    """
    override = os.environ.get("RF2_KONDO_CACHE")
    root = Path(override) if override else Path(
        os.environ.get("XDG_CACHE_HOME") or (Path.home() / ".cache"))
    return root / "re-frame2" / "clj-kondo" / pin


def binary_path(pin: str) -> Path:
    name = "clj-kondo.exe" if platform.system() == "Windows" else "clj-kondo"
    return cache_dir(pin) / name


def _fetch(url: str, dest: Path) -> None:
    with urllib.request.urlopen(url, timeout=120) as resp:  # noqa: S310
        dest.write_bytes(resp.read())


def provision(pin: str, quiet: bool = False) -> Path:
    """The pinned binary, downloading it once if this machine lacks it.

    The archive's `.sha256` sidecar is verified before anything is unpacked:
    the whole value of a pin is that the bytes are known, and an unverified
    download is a pin in name only.
    """
    exe = binary_path(pin)
    if exe.is_file():
        return exe

    key = (platform.system(), platform.machine())
    template = ARCHIVES.get(key)
    if template is None:
        raise RuntimeError(
            "no clj-kondo release archive is published for this platform "
            "(%s/%s); upstream builds %s"
            % (key[0], key[1], ", ".join(sorted({v.split("-", 2)[2]
                                                 for v in ARCHIVES.values()}))))
    archive = template.format(v=pin)
    url = "%s/v%s/%s" % (RELEASES, pin, archive)

    if not quiet:
        # ASCII only in anything PRINTED: Windows consoles default to cp1252
        # and a stray em-dash comes out as a replacement character, or raises.
        print("  fetching clj-kondo %s (%s) - once per machine per pin"
              % (pin, archive))
    with tempfile.TemporaryDirectory() as tmp:
        tmpd = Path(tmp)
        zip_path = tmpd / archive
        try:
            _fetch(url, zip_path)
            expected = urllib.request.urlopen(  # noqa: S310
                url + ".sha256", timeout=60).read().decode().split()[0].strip()
        except (urllib.error.URLError, OSError) as exc:
            raise RuntimeError("could not download %s: %s" % (url, exc))

        actual = hashlib.sha256(zip_path.read_bytes()).hexdigest()
        if actual != expected:
            raise RuntimeError(
                "%s failed its published sha256 (expected %s, got %s)"
                % (archive, expected, actual))

        with zipfile.ZipFile(zip_path) as zf:
            members = [n for n in zf.namelist()
                       if Path(n).name in ("clj-kondo", "clj-kondo.exe")]
            if len(members) != 1:
                raise RuntimeError(
                    "%s does not contain exactly one clj-kondo binary (found "
                    "%s)" % (archive, members or "none"))
            extracted = Path(zf.extract(members[0], tmpd))

        cache_dir(pin).mkdir(parents=True, exist_ok=True)
        # Move into place through a temp name in the SAME directory, so two
        # spines racing on one machine cannot observe a half-written binary.
        staged = exe.with_suffix(exe.suffix + ".%d.part" % os.getpid())
        shutil.move(str(extracted), str(staged))
        staged.chmod(0o755)
        os.replace(staged, exe)

    return exe


def resolved(pin: str, quiet: bool = False) -> Path:
    """The pinned binary, preferring one already on PATH at the right version.

    A CI runner has installed the pin to `/usr/local/bin` before this script
    runs, and a developer may have it too; downloading a second copy of a
    binary already present would be waste with no gain.  The version is
    CHECKED rather than assumed — that check is the whole bead.
    """
    found = (shutil.which("clj-kondo.cmd") or shutil.which("clj-kondo.bat")
             or shutil.which("clj-kondo"))
    if found:
        try:
            out = subprocess.run([found, "--version"], capture_output=True,
                                 text=True, timeout=60).stdout
        except (OSError, subprocess.SubprocessError):
            out = ""
        if pin in out:
            return Path(found)
    return provision(pin, quiet=quiet)


# ---------------------------------------------------------------------------
# Is the lane armed?
# ---------------------------------------------------------------------------

def arms(paths, roots) -> bool:
    """True when any of `paths` is inside something the gate reads.

    The `--lint` roots come from the workflow, so a new root arms the lane the
    moment it is added there.  Two roots are added on top, and both are read
    rather than linted: `.clj-kondo/` is the shared config the run loads, and
    the workflow itself carries the pin and the target list — an edit to either
    changes the verdict without touching a single `.cljs` file.
    """
    prefixes = [r.replace("\\", "/").rstrip("/") for r in roots]
    prefixes += [".clj-kondo", ".github/workflows/lint.yml"]
    for raw in paths:
        p = str(raw).replace("\\", "/")
        # `./x` and `x` are the same path; `lstrip` is the wrong tool for that
        # trim — it eats every leading `.` and `/`, which silently turned
        # `.clj-kondo/config.edn` into `clj-kondo/config.edn` and stopped the
        # shared config arming anything.
        while p.startswith("./"):
            p = p[2:]
        if any(p == pre or p.startswith(pre + "/") for pre in prefixes):
            return True
    return False


# ---------------------------------------------------------------------------
# The self-test — proving the red, not merely the green
# ---------------------------------------------------------------------------

_FIXTURE_JOB = """\
jobs:
  clj-kondo:
    name: clj-kondo (fail on errors, warnings informational)
    steps:
      - name: Install clj-kondo
        run: |
          curl -fsSL -o /tmp/clj-kondo.zip \\
            https://github.com/clj-kondo/clj-kondo/releases/download/v9999.01.02/clj-kondo-9999.01.02-linux-static-amd64.zip
      - name: Run clj-kondo
        run: |
          clj-kondo \\
            --parallel \\
            --fail-level error \\
            --lint implementation \\
            --lint tools/story
"""


def self_test() -> int:
    ok = True

    def check(label, cond, detail=""):
        nonlocal ok
        print("  %s %s%s" % ("ok  " if cond else "FAIL", label,
                             "" if cond else "  <- " + str(detail)))
        if not cond:
            ok = False

    # --- the reader, against a fixture whose answers are known --------------
    with tempfile.TemporaryDirectory() as tmp:
        fixture = Path(tmp) / "lint.yml"
        fixture.write_text(_FIXTURE_JOB, encoding="utf-8")
        pin, argv = gate(fixture)
        check("the pin is read off the installer step's release URL",
              pin == "9999.01.02", pin)
        check("the gate command is read from the step, flags and all",
              argv == ["clj-kondo", "--parallel", "--fail-level", "error",
                       "--lint", "implementation", "--lint", "tools/story"],
              argv)
        check("the --lint roots are derived from that command",
              lint_roots(argv) == ["implementation", "tools/story"],
              lint_roots(argv))

        # A workflow that stopped describing the gate must RAISE, not shrug: a
        # runner that silently lints nothing is the fail-open shape this whole
        # file exists to close.
        for label, text in (
            ("a workflow with no clj-kondo job", "jobs:\n  other:\n    steps:\n      - run: echo\n"),
            ("a job with no version pin",
             "jobs:\n  clj-kondo:\n    steps:\n      - name: Run clj-kondo\n        run: clj-kondo --lint implementation\n"),
            ("a job whose gate step is gone",
             _FIXTURE_JOB.replace("- name: Run clj-kondo", "- name: Run something else")),
        ):
            fixture.write_text(text, encoding="utf-8")
            try:
                gate(fixture)
                check(label + " raises", False, "it returned instead")
            except GateUnreadable:
                check(label + " raises", True)

    # --- the arming predicate ----------------------------------------------
    roots = ["implementation", "tools/story"]
    check("a source file under a --lint root arms the lane",
          arms(["implementation/hicasso/src/re_frame/hicasso/impl/overlay.cljs"], roots))
    check("the shared kondo config arms the lane",
          arms([".clj-kondo/config.edn"], roots))
    check("the workflow carrying the pin arms the lane",
          arms([".github/workflows/lint.yml"], roots))
    check("a doc-only change does not",
          not arms(["docs/design/hicasso/product/budgets.md", "README.md"], roots))
    check("a near-miss prefix does not arm the lane",
          not arms(["implementation-notes/x.cljs"], roots))

    # --- the real workflow still parses ------------------------------------
    try:
        pin, argv = gate()
        real_roots = lint_roots(argv)
        check("the repo's own lint.yml yields a pin", bool(pin), pin)
        check("...and at least three --lint roots",
              len(real_roots) >= 3, real_roots)
        check("...every one of which exists on disk",
              all((REPO_ROOT / r).exists() for r in real_roots),
              [r for r in real_roots if not (REPO_ROOT / r).exists()])
    except GateUnreadable as exc:
        check("the repo's own lint.yml describes the gate", False, exc)

    # --- the gate's RED, on a planted defect -------------------------------
    #
    # The claim under test is not "clj-kondo works" but "THIS runner, at THIS
    # pin, over the roots THIS workflow names, reports a defect in a file that
    # was outside every local lane before it existed". So the plant goes into
    # a real source tree under a real `--lint` root, and is restored from the
    # bytes read before it — never from a diff, which cannot tell an exact
    # restore from a patch that never applied.
    try:
        exe = resolved(pin)
    except RuntimeError as exc:
        print("  SKIP the planted-defect control - %s" % exc)
        print("       (the pin could not be provisioned; the RED half of this "
              "gate is therefore unproven on this host)")
        ok = False
        exe = None

    if exe is not None:
        victim = (REPO_ROOT / "implementation" / "hicasso" / "src" / "re_frame"
                  / "hicasso" / "impl" / "overlay.cljs")
        original = victim.read_bytes()
        before = hashlib.sha256(original).hexdigest()
        anchor = b'(unchecked-set "displayName" "hicasso/modal")'
        if original.count(anchor) != 1:
            check("the planted-defect control has its anchor", False,
                  "%d occurrences of %r in %s"
                  % (original.count(anchor), anchor.decode(), victim.name))
        else:
            planted = original.replace(
                anchor, b'(aset "displayName" "hicasso/modal")', 1)
            try:
                victim.write_bytes(planted)
                proc = subprocess.run(
                    [str(exe), "--fail-level", "error", "--lint", str(victim)],
                    cwd=REPO_ROOT, capture_output=True, text=True)
            finally:
                victim.write_bytes(original)
            after = hashlib.sha256(victim.read_bytes()).hexdigest()
            check("an aset-on-a-function is reported as an ERROR",
                  proc.returncode != 0 and "error" in proc.stdout,
                  "exit %d: %s" % (proc.returncode, proc.stdout.strip()))
            check("...naming the file it is in",
                  victim.name in proc.stdout, proc.stdout.strip())
            check("the plant was restored byte-for-byte",
                  after == before, "%s != %s" % (after, before))

    print("lint_kondo self-test: %s" % ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


# ---------------------------------------------------------------------------

def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--print-binary", action="store_true",
                        help="provision if needed, print the binary's path")
    parser.add_argument("--classify", nargs="*", metavar="FILE",
                        help="print kondo_surface=true|false for these paths")
    parser.add_argument("--self-test", action="store_true",
                        help="prove the gate's red/green classification")
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()

    try:
        pin, command = gate()
    except GateUnreadable as exc:
        print("FAIL: %s" % exc, file=sys.stderr)
        return 1

    if args.classify is not None:
        print("kondo_surface=%s"
              % ("true" if arms(args.classify, lint_roots(command)) else "false"))
        return 0

    try:
        exe = resolved(pin, quiet=args.print_binary)
    except RuntimeError as exc:
        print("clj-kondo %s is NOT AVAILABLE: %s" % (pin, exc), file=sys.stderr)
        print("A DIFFERENT VERSION IS NOT A FALLBACK (rf2-x1mz): 2025.10.23 "
              "reports 0 errors on a line 2026.04.15 fails, so a pass from one "
              "is not a pass from the other. Nothing was linted.",
              file=sys.stderr)
        return EXIT_UNPROVISIONED

    if args.print_binary:
        print(exe)
        return 0

    print("clj-kondo %s (%s)" % (pin, exe))
    print("  $ %s" % " ".join(command))
    return subprocess.run([str(exe)] + command[1:], cwd=REPO_ROOT).returncode


if __name__ == "__main__":
    sys.exit(main())
