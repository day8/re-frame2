#!/usr/bin/env python3
"""Egress-walker residue guardrail — one `project-egress` door for tool code.

The normative rule this gate enforces is the rf2-kuky.9 ruling (option A),
executed across four stages by rf2-kuky.88 / .90 / .92 / .93: **`rf/project-egress`
is the ONLY projection door on the re-frame2 facade.** The leaf walker
`re-frame.elision/elide-wire-value` is a framework-internal mechanism BEHIND that
door — it has no `re-frame.core` re-export and no public-API manifest row
(rf2-kuky.90 removed both).

WHY A GATE AND NOT JUST A DELETION. The walker reads no `:rf.egress/profile`. A
tool that reaches it directly therefore has to hand-assemble the `:rf.size/*`
floor a named boundary already carries — and gets NO error if it assembles a
weaker one, because an all-false floor is a legitimate walker argument. That is
the fail-open shape stage 1 (rf2-kuky.88) migrated every tool off. Deleting the
facade export closes the `rf/` spelling; this gate closes the other three, which
still resolve because the walker stays public at its home namespace for the
framework's own emit-time chokepoints.

THE SURFACE — tool and preload SOURCE, nothing else.

    tools/*/src/**          the shipped tool source trees
    skills/*/preload/**     the skill preload runtimes that ride into a session

Tests, testbeds, conformance corpora and prose are NOT scanned, deliberately.
`tools/mcp-conformance`'s wire-vocab suite drives the framework-internal walker
on purpose (that IS its subject), several tool test files pin the retired
spelling as a NEGATIVE assertion (`:fixture/eval-form-must-not-contain
["re-frame.core/elide-wire-value"]`), and the spec/skills prose names the walker
as the mechanism it is. Scanning any of those would fire on correct content.

WHAT COUNTS AS RESIDUE — CALL POSITION, NOT THE NAME.

Naming the walker in a docstring or comment is fine and often right: four
`tools/*/src` files describe the framework's internal walk that way, spelled at
the home namespace. What this gate refuses is a CALL — an open paren immediately
followed by the symbol, optionally namespace-qualified and optionally behind a
`#` reader macro:

    (rf/elide-wire-value v opts)              <- retired facade spelling
    (re-frame.core/elide-wire-value v opts)   <- retired fully-qualified spelling
    (rf.elision/elide-wire-value v opts)      <- live, but not from tool source
    #(elide-wire-value % opts)                <- bare, after a :refer

STRING LITERALS ARE SCANNED ON PURPOSE. The pair-MCP servers ship walks as
RENDERED EVAL FORMS — source text assembled into a string and evaluated in the
inspected app. A residue call in that string is a real call at the far end, so
the gate makes no attempt to skip strings, and a `;` comment carrying a
copy-pasteable call is refused for the same reason: in a shipped tool source
tree it reads as the recommended shape.

A back-ticked mention inside a paren — ``(`re-frame.elision/elide-wire-value`)``
— never fires, because the character after the paren is a backtick, not the
symbol. That shape occurs live in `tools/xray/src` today.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

# tools/<tool>/src/**  and  skills/<skill>/preload/**
_SURFACE = re.compile(r"^(?:tools/[^/]+/src/|skills/[^/]+/preload/)")

# `(` (optionally behind `#`) then, with no intervening backtick or space, the
# optionally-qualified symbol. The trailing guard stops `elide-wire-value-ish`
# and `elide-wire-values` from matching.
_CALL = re.compile(
    r"#?\((?:[A-Za-z0-9_.*+!?<>=$%&|-]+/)?elide-wire-value(?![A-Za-z0-9_.*+!?<>=-])"
)

_SELF_TEST_FIXTURE_ROOT = REPO_ROOT / "scripts" / "_test_fixtures" / "check_egress_walker_residue"


def tracked_surface_files() -> list[Path]:
    """Every git-tracked file on the scanned surface."""
    out = subprocess.run(
        ["git", "ls-files", "--", "tools", "skills"],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=True,
    ).stdout
    return [REPO_ROOT / p for p in out.split("\n") if p and _SURFACE.match(p)]


def scan_file(path: Path) -> list[tuple[int, str]]:
    """Return (line-number, line) for every call-position walker reference."""
    try:
        text = path.read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError):
        return []
    if "elide-wire-value" not in text:
        return []
    findings = []
    for n, line in enumerate(text.replace("\r\n", "\n").split("\n"), start=1):
        if _CALL.search(line):
            findings.append((n, line.strip()))
    return findings


def run_scan(paths: list[Path], verbose: bool = False) -> int:
    findings = []
    for path in paths:
        for n, line in scan_file(path):
            findings.append((path, n, line))

    if verbose:
        sys.stderr.write(
            f"check_egress_walker_residue: scanned {len(paths)} file(s) on "
            f"tools/*/src + skills/*/preload\n"
        )

    if not findings:
        print(
            f"OK: no egress-walker residue on tool/preload source "
            f"({len(paths)} files scanned)."
        )
        return 0

    sys.stderr.write(
        "FAIL: tool/preload source calls the framework-internal egress walker "
        "directly.\n\n"
    )
    for path, n, line in findings:
        rel = path.relative_to(REPO_ROOT).as_posix()
        sys.stderr.write(f"  {rel}:{n}: {line}\n")
    sys.stderr.write(
        "\n`re-frame.elision/elide-wire-value` is a framework-internal mechanism, "
        "not a door (rf2-kuky.9 ruling A).\n"
        "Project through the one facade door with a NAMED boundary instead:\n\n"
        "    (rf/project-egress v {:rf.egress/profile :rf.egress/off-box-tool\n"
        "                          :frame frame-id})\n\n"
        "The profile resolves to the `:rf.size/*` floor the walker then applies, so\n"
        "the boundary is stated once and cannot be under-assembled by hand.\n"
        "Naming the walker in prose or a docstring is fine — only a CALL fires.\n"
    )
    return 1


def _run_self_tests(verbose: bool = False) -> int:
    """Prove the gate fires on each live shape and stays green on each sanctioned one."""
    cases = [
        ("residue_calls.cljs", 5),
        ("sanctioned_mentions.cljs", 0),
    ]
    failures = 0
    for fixture, expected in cases:
        path = _SELF_TEST_FIXTURE_ROOT / fixture
        if not path.exists():
            sys.stderr.write(f"self-test FAIL: fixture {fixture!r} missing at {path}\n")
            failures += 1
            continue
        got = len(scan_file(path))
        if got == expected:
            if verbose:
                sys.stderr.write(f"self-test PASS: {fixture} (findings={got})\n")
        else:
            sys.stderr.write(
                f"self-test FAIL: {fixture} expected findings={expected}, got {got}\n"
            )
            failures += 1

    if failures:
        sys.stderr.write(f"\n{failures} self-test failure(s).\n")
        return 1
    sys.stderr.write(f"all {len(cases)} self-tests passed.\n")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Refuse a direct call to the framework-internal egress walker "
            "from tool/preload source. rf/project-egress is the one door."
        )
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="Run the bundled fixture self-tests instead of scanning the repo.",
    )
    parser.add_argument("--verbose", action="store_true", help="Report progress on stderr.")
    parser.add_argument(
        "paths",
        nargs="*",
        help="Optional explicit files to scan (default: the whole tracked surface).",
    )
    args = parser.parse_args(argv)

    if args.self_test:
        return _run_self_tests(verbose=args.verbose)

    paths = [Path(p).resolve() for p in args.paths] if args.paths else tracked_surface_files()
    return run_scan(paths, verbose=args.verbose)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
