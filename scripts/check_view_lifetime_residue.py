#!/usr/bin/env python3
"""View-lifetime residue ratchet — zero tolerance for the excised reservation term.

The re-frame2 excision epic (rf2-5e8zv) removed the view-lifetime reservation
term IN FULL: the view-lifetime binding is deleted, the observation surface
returns a HANDLE, and the Resources surface pins liveness with an OWNER. This
ratchet is the mechanism that keeps it gone — prose does not stop a
reintroduction. (This module deliberately never spells the retired token; it is
constructed at runtime from `_STEM` so the guard is not its own violation.)

It scans every git-tracked file's CONTENT and PATH for the retired vocabulary in
every lexical form: natural-language singular/plural/past/gerund, and
identifier / path forms (snake, camel, kebab). There is NO baseline, NO ceiling,
and NO allowlist for product files: the permitted count is exactly ZERO. Only
`.beads/**` is excluded — bead history is issue-tracker state, not product, and
records the excision it drove. Git history is outside the worktree scan.

The forbidden stem is CONSTRUCTED from fragments (see `_STEM`) so this guard's
own source never spells the retired word — the ratchet is therefore NOT its own
allowlisted violation, and it needs no self-exemption. The `--self-test` cases
likewise construct every example from `_STEM`.

Ordinary words that merely END in the stem — "release", "please", "displease"
and their inflections — are NOT flagged: the stem only counts when it is not the
tail of one of those. The survivors of the excision — "handle", "owner",
"route", "machine", "ssr", "app-event" — are different words and never match.

Exit code:
    0  clean (zero residue in content and paths)
    1  residue found (printed file:line by file:line)
    2  invocation / setup error
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path
from typing import Iterable

import re

# Construct the forbidden stem from fragments so this file never contains the
# literal retired word. A grep of this guard for the very pattern it enforces
# therefore finds nothing — the ratchet is not its own violation, and no path
# is ever exempted from the scan.
_STEM = "l" + "eas"

# A violation is the stem followed by a word tail (e / es / ed / ing),
# case-insensitively, that is NOT the tail of "re<stem>e" or "p<stem>e"
# (release / please / displease and their inflections). The exclusion is a
# fixed-width negative lookbehind on the two / one character(s) that precede the
# stem, so a genuine occurrence sharing a line with the survivor word "release"
# is still caught (the earlier line-level filters missed exactly that). No
# trailing word boundary, so identifier forms are caught too: snake
# `<stem>_id`, camel `<stem>Id` and `view<Stem>`, kebab `<stem>-manager`.
_PATTERN = re.compile(
    r"(?<!re)(?<!p)" + _STEM + r"(?:e|es|ed|ing)",
    re.IGNORECASE,
)

# The ONLY excluded prefix. Everything else tracked is product and held to zero.
_EXCLUDE_PREFIXES = (".beads/",)


def _tracked_files(repo_root: Path) -> list[str]:
    """Every git-tracked path, repo-root-relative (POSIX separators)."""
    result = subprocess.run(
        ["git", "ls-files", "-z"],
        cwd=repo_root,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise RuntimeError(
            f"git ls-files failed in {repo_root}: {result.stderr.strip()}"
        )
    return [entry for entry in result.stdout.split("\0") if entry]


def _excluded(rel: str) -> bool:
    return any(rel.startswith(prefix) for prefix in _EXCLUDE_PREFIXES)


def scan(repo_root: Path) -> list[tuple[str, int | None, str]]:
    """Return (path, line-number-or-None, text) for every residue match.

    A `None` line number marks a match in the PATH itself (a file whose name
    carries the retired vocabulary); an integer marks a content line.
    """
    hits: list[tuple[str, int | None, str]] = []
    for rel in _tracked_files(repo_root):
        if _excluded(rel):
            continue

        # Path form — a file named for the retired concept is residue too.
        if _PATTERN.search(rel):
            hits.append((rel, None, rel))

        path = repo_root / rel
        try:
            data = path.read_bytes()
        except OSError:
            # A tracked path can be absent mid-rename; the index still lists it.
            continue
        if b"\0" in data:
            # Binary heuristic — a decoded blob would false-match on noise.
            continue
        text = data.decode("utf-8", errors="ignore")
        for line_no, line in enumerate(text.splitlines(), start=1):
            if _PATTERN.search(line):
                hits.append((rel, line_no, line.strip()))
    return hits


def _report(hits: Iterable[tuple[str, int | None, str]]) -> None:
    stem_label = _STEM + "e"  # constructed, never a literal in this source
    sys.stderr.write(
        f"\n{stem_label}-residue ratchet: forbidden '{stem_label}' vocabulary "
        "found in the tracked tree (permitted count is ZERO):\n\n"
    )
    for rel, line_no, text in hits:
        where = f"{rel}:{line_no}" if line_no is not None else f"{rel} (path)"
        sys.stderr.write(f"  {where}\n      {text}\n")
    sys.stderr.write(
        "\nThe view-lifetime concept was excised in full (rf2-5e8zv): the "
        "observation surface returns a HANDLE and Resources pin liveness with "
        "an OWNER. Reword to the surviving vocabulary; do not reintroduce the "
        "retired term. Only .beads/** is exempt.\n"
    )


def run(repo_root: Path, *, verbose: bool) -> int:
    hits = scan(repo_root)
    if hits:
        _report(hits)
        return 1
    if verbose:
        stem_label = _STEM + "e"
        sys.stderr.write(
            f"{stem_label}-residue ratchet clean: zero '{stem_label}' residue in "
            "tracked content or paths.\n"
        )
    return 0


# ---------------------------------------------------------------------------
# Self-test — the matcher must DISCRIMINATE. Every example is built from _STEM,
# so this test file never spells the retired word either; the survivor words
# ("release", "please", …) may appear literally because the matcher is proven to
# skip them (that is exactly what the negative cases assert).
# ---------------------------------------------------------------------------

def _run_self_tests(*, verbose: bool) -> int:
    s = _STEM
    S = _STEM.capitalize()  # "Leas" — the camel-case head

    must_match = [
        ("prose singular", f"the view-lifetime {s}e was removed"),
        ("prose plural", f"two {s}es were held"),
        ("prose past", f"the node was {s}ed to the owner"),
        ("prose gerund", f"stop {s}ing the subscription"),
        ("snake identifier", f"(let [{s}e_id (acquire! t)] ...)"),
        ("camel identifier", f"const {s}eId = acquire(t);"),
        ("camel head word", f"class View{S}e {{}}"),
        ("kebab / filename", f"{s}e-manager.cljs"),
        ("hidden on a release line", f"release is a {S}E release, not GC"),
        ("upper-case", f"RETURNS A {s.upper()}E — THE OWNER TOKEN"),
    ]
    must_not_match = [
        ("release", "release the subscription"),
        ("released", "the owner is released by supersession"),
        ("releases", "route exit releases route owners"),
        ("releasing", "releasing the handle"),
        ("please", "please read the owner"),
        ("pleased", "the reviewer was pleased"),
        ("displease", "this would displease no one"),
        ("survivor handle", "returning a HANDLE, the owner token"),
        ("survivor owner", "an app-event owner pins liveness"),
        ("survivor machine", "the machine owner revives on restore"),
    ]

    failures = 0
    for label, text in must_match:
        if not _PATTERN.search(text):
            sys.stderr.write(f"self-test FAIL (must match): {label}: {text!r}\n")
            failures += 1
        elif verbose:
            sys.stderr.write(f"self-test PASS (match): {label}\n")
    for label, text in must_not_match:
        if _PATTERN.search(text):
            sys.stderr.write(
                f"self-test FAIL (must NOT match): {label}: {text!r}\n"
            )
            failures += 1
        elif verbose:
            sys.stderr.write(f"self-test PASS (skip): {label}\n")

    # Meta-test: the guard's OWN source must be clean, proving the constructed
    # token leaves no allowlisted live occurrence in this file.
    own = Path(__file__).read_text(encoding="utf-8", errors="ignore")
    own_hits = [
        (i, line)
        for i, line in enumerate(own.splitlines(), start=1)
        if _PATTERN.search(line)
    ]
    if own_hits:
        for i, line in own_hits:
            sys.stderr.write(
                f"self-test FAIL (guard self-match): {Path(__file__).name}:{i}: "
                f"{line.strip()!r}\n"
            )
        failures += len(own_hits)
    elif verbose:
        sys.stderr.write("self-test PASS (guard source carries no live occurrence)\n")

    if failures:
        sys.stderr.write(f"\n{failures} self-test failure(s).\n")
        return 1
    if verbose:
        sys.stderr.write(
            f"all {len(must_match) + len(must_not_match) + 1} self-tests passed.\n"
        )
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", default=None)
    parser.add_argument("--verbose", action="store_true")
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="Prove the matcher discriminates (prose / snake / camel / filename "
        "positives, release/please/survivor negatives) and exit.",
    )
    args = parser.parse_args(argv)

    if args.self_test:
        return _run_self_tests(verbose=args.verbose)

    if args.repo_root:
        repo_root = Path(args.repo_root).resolve()
    else:
        repo_root = Path(__file__).resolve().parent.parent

    if not (repo_root / ".git").exists():
        sys.stderr.write(
            f"error: {repo_root} is not a git repository root "
            "(no .git). Pass --repo-root explicitly.\n"
        )
        return 2

    return run(repo_root, verbose=args.verbose)


if __name__ == "__main__":
    raise SystemExit(main())
