#!/usr/bin/env python3
"""Egress-walker residue guardrail — one `project-egress` door for tool code.

The normative rule this gate enforces is the rf2-kuky.9 ruling (option A),
executed across four stages by rf2-kuky.88 / .90 / .92 / .93: **`rf/project-egress`
is the ONLY projection door on the re-frame2 facade.** The leaf walker
`re-frame.elision/elide-wire-value` is a framework-internal mechanism BEHIND that
door — it has no `re-frame.core` re-export and no public-API manifest row
(rf2-kuky.90 removed both).

WHY A GATE AND NOT JUST A DELETION. The walker reads no `:rf.egress/profile`. A
tool that reaches it directly therefore has to hand-assemble the `:rf.egress/*`
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
the home namespace. What this gate refuses is a CALL — an open paren, then
READER WHITESPACE ONLY, then the symbol, optionally namespace-qualified and
optionally behind a `#` reader macro:

    (rf/elide-wire-value v opts)              <- retired facade spelling
    (re-frame.core/elide-wire-value v opts)   <- retired fully-qualified spelling
    (rf.elision/elide-wire-value v opts)      <- live, but not from tool source
    #(elide-wire-value % opts)                <- bare, after a :refer

READER WHITESPACE MEANS WHAT THE READER MEANS BY IT — spaces, tabs, commas,
NEWLINES, and `;` line comments, in any mix. All of these read as the same call
form, so all of them are residue:

    ( rf.elision/elide-wire-value v opts)     <- a space
    (                                         <- a newline
      rf.elision/elide-wire-value v opts)
    ( ;; project the slot                     <- a comment, then a newline
      rf.elision/elide-wire-value v opts)

The earlier revision of this gate required the symbol IMMEDIATELY after the
paren and searched one line at a time, so all three of those passed while the
tight spelling was refused (rf2-kuky.90, merged-PR audit #9491). A gate whose
own fixtures all use one spelling cannot see that it only checks one spelling;
that is why every variant above now has a fixture of its own.

STRING LITERALS ARE SCANNED ON PURPOSE. The pair-MCP servers ship walks as
RENDERED EVAL FORMS — source text assembled into a string and evaluated in the
inspected app. A residue call in that string is a real call at the far end, so
the gate makes no attempt to skip strings, and a `;` comment carrying a
copy-pasteable call is refused for the same reason: in a shipped tool source
tree it reads as the recommended shape.

WHAT STILL DOES NOT FIRE, AND WHY THE WIDENING ABOVE KEEPS IT THAT WAY. A
back-ticked mention inside a paren — ``(`re-frame.elision/elide-wire-value`)``,
a shape that occurs live in `tools/xray/src` today — is prose, not a call. A
backtick is NOT reader whitespace, so it breaks the call head wherever it sits:
tight against the paren, or behind a space or a newline. Likewise a mention
inside a skipped `;` comment is consumed WITH that comment — the comment is
skipped as one unit, up to and including its newline — so naming the walker in
a comment inside an open form does not read as calling it.

Neither this gate nor its fixtures are a Clojure reader, and completing them was
never a request for one (audit #9491's own words). Reader-equivalent formatting
of an ordinary call is the surface; deliberate obfuscation is not.
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

# Reader whitespace between the paren and the callee: spaces, tabs, newlines,
# commas, and whole `;` comments. A comment is one unit up to and including its
# newline, so anything NAMED inside it is consumed with it rather than read as
# the callee. A backtick is absent from this set on purpose — that absence is
# what keeps the sanctioned prose mentions passing (see the module docstring).
# The two branches start on disjoint characters, so the `*` cannot backtrack
# catastrophically.
_READER_SPACE = r"(?:[\s,]|;[^\n]*\n)*"

# `(` (optionally behind `#`) then reader whitespace, then the
# optionally-qualified symbol. The trailing guard stops `elide-wire-value-ish`
# and `elide-wire-values` from matching.
_CALL = re.compile(
    r"#?\("
    + _READER_SPACE
    + r"(?:[A-Za-z0-9_.*+!?<>=$%&|-]+/)?elide-wire-value(?![A-Za-z0-9_.*+!?<>=-])"
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
    """Return (line-number, diagnostic) for every call-position walker reference.

    Matched over the WHOLE text rather than line by line, because a call head is
    allowed to span lines. The reported line is always the one the `(` sits on,
    so the diagnostic still points at the form's start; where the head spans
    lines, the span is named and the head is shown collapsed onto one line.
    """
    try:
        text = path.read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError):
        return []
    if "elide-wire-value" not in text:
        return []
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    lines = text.split("\n")
    findings = []
    for match in _CALL.finditer(text):
        start_line = text.count("\n", 0, match.start()) + 1
        end_line = text.count("\n", 0, match.end()) + 1
        if start_line == end_line:
            findings.append((start_line, lines[start_line - 1].strip()))
        else:
            findings.append(
                (
                    start_line,
                    f"{lines[start_line - 1].strip()}"
                    f"   ...   {lines[end_line - 1].strip()}"
                    f"   [call head spans lines {start_line}-{end_line}]",
                )
            )
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
        "The profile resolves to the `:rf.egress/*` floor the walker then applies, so\n"
        "the boundary is stated once and cannot be under-assembled by hand.\n"
        "Naming the walker in prose or a docstring is fine -- only a CALL fires.\n"
    )
    return 1


def _run_self_tests(verbose: bool = False) -> int:
    """Prove the gate fires on each live shape and stays green on each sanctioned one.

    Each case pins the LINE NUMBERS, not just the count. A count alone cannot
    tell a pattern that found the right five things from one that found four
    plus a false positive — and this gate has already shipped once with its
    fixtures and its pattern sharing a blind spot (audit #9491), so the cheap
    extra discrimination is worth having.
    """
    cases = [
        # the tight-call control: callee hard against the paren
        ("residue_calls.cljs", (10, 14, 18, 22, 26)),
        # the same call in reader-equivalent formattings: space, newline,
        # comment+newline, `#(`+newline, rendered eval string+newline
        ("residue_calls_formatted.cljs", (18, 23, 28, 33, 41)),
        # prose that names the mechanism without calling it
        ("sanctioned_mentions.cljs", ()),
    ]
    failures = 0
    for fixture, expected in cases:
        path = _SELF_TEST_FIXTURE_ROOT / fixture
        if not path.exists():
            sys.stderr.write(f"self-test FAIL: fixture {fixture!r} missing at {path}\n")
            failures += 1
            continue
        got = tuple(n for n, _ in scan_file(path))
        if got == expected:
            if verbose:
                sys.stderr.write(
                    f"self-test PASS: {fixture} (findings={len(got)} at lines {list(got)})\n"
                )
        else:
            sys.stderr.write(
                f"self-test FAIL: {fixture} expected findings at lines {list(expected)}, "
                f"got {list(got)}\n"
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
