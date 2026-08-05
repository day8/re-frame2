#!/usr/bin/env python3
"""Failure-corpus co-edit invariant — retired failure-semantics advisory scan.

The framework's *failure corpus* — the errors concept page
(`docs/core/errors.md`) and the failure testbeds
(`testbeds/schema_violation`, `testbeds/drain_depth_trigger`, …) — is a
TEACHING corpus for the error catalogue in Spec 009 §Error event catalogue.
Spec 009 already binds the *catalogue rows* to the feature specs by a co-edit
invariant (009 §Error event catalogue: "every `:rf.<area>/<category>` event
MUST land as a row in this catalogue in the same PR as the owning Spec
change"). But nothing bound the corpus: when a category was retired or
re-semantics'd, the teaching examples could — and did — keep asserting the
RETIRED shape, so the repo's own failure teaching taught behaviour the runtime
no longer produces (rf2-2oqj59):

  * `testbeds/drain_depth_trigger` taught whole-drain ATOMIC ROLLBACK
    (`:depth-reached` reads back to 0). Retired by rf2-nj6p7 / rf2-u6jsj:
    the atomicity unit is the EVENT, not the drain — per-event durability,
    NO whole-drain rollback, `:rollback? false`, `:depth-reached` reads back
    to the ceiling. (Spec 002 §Drain versus event — the epoch unit.)

  * `testbeds/schema_violation` Button C taught `:where :cofx` schema-
    validation (skip-handler, queue-continues). Retired by rf2-nkf4l3: a
    recordable coeffect failing its `reg-cofx` `:schema` is the separate,
    HALTING `:rf.error/cofx-value-invalid` hard error — it THROWS and does
    NOT emit `:rf.error/schema-validation-failure` at all. There is no
    `:where :cofx` member of the schema-validation `:where` enum. (Spec 010
    §Validation order step 2.)

This gate is the mechanical half of widening that co-edit invariant to the
corpus: a grep-able advisory scan that fires when a KNOWN-RETIRED failure
spelling reappears as LIVE teaching in the corpus. It does not (and cannot)
prove every future retirement is followed through — that is the reviewer's
job under the co-edit invariant documented in errors.md §"This page is bound
to the catalogue". It DOES pin the retirements already made so they cannot
silently regress.

THE ONE MECHANICAL SPELLING: `:where :cofx`

Only one of the two retirements has a crisp, low-false-positive lintable
spelling: the retired `:where :cofx` schema-validation surface. A live
`:where :cofx` keyword-value pair in the corpus is drift — the surface does
not exist. (The drain-rollback retirement is prose-shaped — "atomic
rollback", "reads back to 0" — which cannot be grepped without firing on the
many correct removed-context mentions; that retirement is guarded by the
co-edit invariant + reviewer, not by this scan. Extend `_RESIDUE_PATTERNS`
below if a future retirement yields another crisp spelling.)

WHY POSITION MATTERS (the "where shapes allow" caveat)

`:where :cofx` is named CONSTANTLY in legitimate removed-context prose:
"`:where :cofx` was retired", "the old `:where :cofx` surface", ";; NOT a
:where :cofx trace". A bare grep fires on every one of those correct
mentions. The load-bearing distinction is *position*:

  * In a MARKDOWN fenced code block (```...```), or in LIVE testbed source
    (outside a `;` comment / string literal), the spelling reads as a live
    value the runtime would produce. THIS is the residue the gate fires on.
  * In markdown PROSE (outside any fence), in an INLINE `code span` (single
    backticks), in a `;`-to-EOL Clojure comment, or inside a "..." string
    literal, the spelling is a NAME being discussed as retired. The gate
    masks all of these, so a removed-context mention never fires.

SCAN SURFACE

The failure corpus:
  * docs/core/errors.md               — the errors concept page
  * testbeds/**/README.md                       — testbed teaching (markdown)
  * testbeds/**/*.cljs / *.cljc / *.clj         — testbed source

Markdown files are scanned only inside fenced code (with `;` comments masked);
source files are scanned with `;` comments AND "..." string literals masked.

Exit code:
    0  no live retired failure spelling in the corpus
    1  at least one live retired failure spelling (results printed file:line)
    2  invocation / setup error

Dependency-light — Python stdlib only.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from typing import Iterable, NamedTuple

# --------------------------------------------------------------------------
# Scan surface
# --------------------------------------------------------------------------

# The single errors concept page + the whole testbeds tree. errors.md is named
# explicitly (a single file); the testbeds dir is walked for READMEs + source.
_ERRORS_PAGE = "docs/core/errors.md"
_TESTBEDS_DIR = "testbeds"

_MD_SUFFIXES = (".md",)
_SRC_SUFFIXES = (".cljs", ".cljc", ".clj")

# Directory names that are never part of the teaching corpus.
_EXCLUDE_DIR_NAMES = frozenset({
    "node_modules",
    "target",
    ".shadow-cljs",
    ".git",
    ".beads",
    ".cpcache",
    "out",     # shadow-cljs testbed build output
    "site",    # mkdocs build output (gitignored)
})


# --------------------------------------------------------------------------
# Retired-spelling patterns
# --------------------------------------------------------------------------
#
# `:where :cofx` — the retired schema-validation `:where` surface (rf2-nkf4l3).
# Whitespace-tolerant between the two keywords; `\b`-style trailing guards
# forbid `:cofx/x` / `:cofxs`. The leading `:where` anchor is what scopes this
# to the schema-validation surface (a bare `:cofx` is a legitimate data key —
# e.g. the testbed's `{:cofx 0}` click-counter slot — so we require the
# `:where` head to avoid firing on it).
_WHERE_COFX_RE = re.compile(r":where\s+:cofx(?![\w/-])")

_RESIDUE_PATTERNS = (
    (":where :cofx", _WHERE_COFX_RE),
)


class Finding(NamedTuple):
    path: Path
    line: int
    kind: str
    snippet: str


# --------------------------------------------------------------------------
# Markdown fenced-code extraction + Clojure-comment masking
# --------------------------------------------------------------------------
#
# For markdown we scan ONLY inside fenced code blocks (``` or ~~~) and mask
# `;`-to-EOL Clojure comments within them, so a removed-context comment in a
# code block does not fire. Prose + inline code spans are never inspected.
# (Same shape as scripts/check_inject_cofx_residue.py.)

_FENCE_RE = re.compile(r"^(\s*)(`{3,}|~{3,})")
_LINE_COMMENT_RE = re.compile(r";.*$")


def _mask_clj_comment(line: str) -> str:
    """Blank a `;`-to-EOL Clojure line comment, length-preserving."""
    m = _LINE_COMMENT_RE.search(line)
    if not m:
        return line
    start = m.start()
    return line[:start] + (" " * (len(line) - start))


def _code_fence_lines(text: str) -> list[tuple[int, str]]:
    """Return (1-based-line-no, masked-content) for lines INSIDE fenced code."""
    out: list[tuple[int, str]] = []
    in_fence = False
    fence_char = ""
    fence_len = 0
    for line_no, raw in enumerate(text.splitlines(), start=1):
        m = _FENCE_RE.match(raw)
        if m:
            marks = m.group(2)
            char = marks[0]
            length = len(marks)
            if not in_fence:
                in_fence = True
                fence_char = char
                fence_len = length
                continue
            if char == fence_char and length >= fence_len:
                in_fence = False
                fence_char = ""
                fence_len = 0
                continue
        if in_fence:
            out.append((line_no, _mask_clj_comment(raw)))
    return out


# --------------------------------------------------------------------------
# Source-file masking: `;` comments AND "..." string literals
# --------------------------------------------------------------------------
#
# Testbed source names the retired spelling in docstrings + `;;` comments
# describing the retirement (the Button-C section comment). Those are
# documentation, not live code. We mask both, length-preserving, tracking
# multi-line strings. (Same shape as scripts/check_retired_spellings.py.)


def _mask_strings(line: str, in_string: bool) -> tuple[str, bool]:
    """Replace "..."-string-literal contents with spaces (length-preserving)."""
    out = []
    i = 0
    n = len(line)
    while i < n:
        c = line[i]
        if in_string:
            if c == "\\" and i + 1 < n:
                out.append("  ")
                i += 2
                continue
            if c == '"':
                in_string = False
                out.append('"')
                i += 1
                continue
            out.append(" ")
            i += 1
            continue
        if c == '"':
            in_string = True
            out.append('"')
            i += 1
            continue
        out.append(c)
        i += 1
    return "".join(out), in_string


def _masked_source_lines(text: str) -> list[str]:
    """Per-line source content with "..." strings + `;` comments blanked."""
    masked: list[str] = []
    in_string = False
    for raw in text.splitlines():
        line, in_string = _mask_strings(raw, in_string)
        line = _mask_clj_comment(line)
        masked.append(line)
    return masked


# --------------------------------------------------------------------------
# File iteration
# --------------------------------------------------------------------------


def _iter_corpus_files(repo_root: Path) -> Iterable[Path]:
    """Yield the corpus files: errors.md + every testbed README / source file."""
    errors_page = repo_root / _ERRORS_PAGE
    if errors_page.is_file():
        yield errors_page
    testbeds = repo_root / _TESTBEDS_DIR
    if testbeds.is_dir():
        for path in sorted(testbeds.rglob("*")):
            if path.suffix not in _MD_SUFFIXES + _SRC_SUFFIXES:
                continue
            parts = set(path.relative_to(testbeds).parts)
            if parts & _EXCLUDE_DIR_NAMES:
                continue
            yield path


# --------------------------------------------------------------------------
# Per-file scan
# --------------------------------------------------------------------------


def _scan_markdown(path: Path, text: str) -> list[Finding]:
    """Scan fenced code of a markdown file for a live retired spelling."""
    findings: list[Finding] = []
    raw = text.splitlines()
    for line_no, masked in _code_fence_lines(text):
        for kind, pat in _RESIDUE_PATTERNS:
            if pat.search(masked):
                snippet = raw[line_no - 1].strip() if 0 <= line_no - 1 < len(raw) else ""
                findings.append(Finding(path, line_no, f"live-residue:{kind}", snippet))
    return findings


def _scan_source(path: Path, text: str) -> list[Finding]:
    """Scan masked source (strings + `;` comments blanked) for a spelling."""
    findings: list[Finding] = []
    raw = text.splitlines()
    for line_no, masked in enumerate(_masked_source_lines(text), start=1):
        for kind, pat in _RESIDUE_PATTERNS:
            if pat.search(masked):
                snippet = raw[line_no - 1].strip() if 0 <= line_no - 1 < len(raw) else ""
                findings.append(Finding(path, line_no, f"live-residue:{kind}", snippet))
    return findings


def _scan_file(path: Path, text: str) -> list[Finding]:
    if path.suffix in _MD_SUFFIXES:
        return _scan_markdown(path, text)
    return _scan_source(path, text)


def scan(repo_root: Path) -> list[Finding]:
    """Scan the failure corpus for live retired failure spellings."""
    findings: list[Finding] = []
    for path in _iter_corpus_files(repo_root):
        text = path.read_text(encoding="utf-8", errors="replace")
        findings.extend(_scan_file(path, text))
    return findings


# --------------------------------------------------------------------------
# Reporting
# --------------------------------------------------------------------------

_FIX_HINTS = {
    ":where :cofx": (
        "`:where :cofx` was RETIRED in EP-0017 (rf2-nkf4l3). A recordable "
        "coeffect (`:rf.cofx/requires`) whose value fails its `reg-cofx` "
        "`:schema` is NOT a `:where :cofx` schema-validation trace — it is the "
        "separate, HALTING `:rf.error/cofx-value-invalid` hard error, which "
        "THROWS and does NOT emit `:rf.error/schema-validation-failure` at all "
        "(spec/010-Schemas.md §Validation order step 2). Rewrite the example "
        "to teach `:rf.error/cofx-value-invalid` (`:recovery :no-recovery`, "
        "throws, run halts). A removed-context mention of `:where :cofx` — in "
        "prose, an inline code span, a `;` comment, or a docstring — is fine; "
        "only a LIVE value in a fenced block / live testbed code fires."
    ),
}


def _report(findings: list[Finding], repo_root: Path) -> None:
    sys.stderr.write(
        f"\n{len(findings)} live retired-failure-spelling hit(s) found in the "
        "failure corpus (rf2-2oqj59 §co-edit invariant):\n\n"
    )
    for f in findings:
        try:
            rel = f.path.relative_to(repo_root)
        except ValueError:
            rel = f.path
        sys.stderr.write(f"  {f.kind}: {rel}:{f.line}\n      {f.snippet}\n")
    families = {k.split(":", 1)[1] for k in (f.kind for f in findings)}
    sys.stderr.write("\nFix:\n")
    for fam in sorted(families):
        sys.stderr.write(f"  * {_FIX_HINTS[fam]}\n")


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Failure-corpus co-edit invariant: fail on a known-retired failure "
            "spelling reappearing as LIVE teaching in the corpus (errors.md + "
            "testbeds)."
        ),
    )
    parser.add_argument(
        "--repo-root",
        default=None,
        help="Path to the repo root. Defaults to the script's grandparent.",
    )
    parser.add_argument(
        "--verbose", "-v", action="store_true", help="Print progress to stderr."
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help=(
            "Run the bundled fixture-based self-tests in "
            "scripts/_test_fixtures/check_failure_corpus_residue/ and exit."
        ),
    )
    args = parser.parse_args(argv)

    if args.self_test:
        return _run_self_tests(verbose=args.verbose)

    if args.repo_root:
        repo_root = Path(args.repo_root).resolve()
    else:
        repo_root = Path(__file__).resolve().parent.parent

    if not (repo_root / "mkdocs.yml").is_file():
        sys.stderr.write(
            f"error: {repo_root} does not look like the re-frame2 repo root "
            "(no mkdocs.yml). Pass --repo-root explicitly.\n"
        )
        return 2

    if args.verbose:
        n = sum(1 for _ in _iter_corpus_files(repo_root))
        sys.stderr.write(f"scanning {n} failure-corpus file(s)...\n")

    findings = scan(repo_root)
    if findings:
        _report(findings, repo_root)
        return 1
    if args.verbose:
        sys.stderr.write("no live retired failure spelling in the corpus.\n")
    return 0


# --------------------------------------------------------------------------
# Self-tests (fixture-driven) — prove the gate FIRES on each live shape
# and stays GREEN on every removed-context counterpart.
# --------------------------------------------------------------------------

_SELF_TEST_FIXTURE_ROOT = (
    Path(__file__).resolve().parent / "_test_fixtures" / "check_failure_corpus_residue"
)


def _run_self_tests(verbose: bool = False) -> int:
    """Scan each fixture file and assert the expected finding count.

    Positive fixtures plant a LIVE `:where :cofx` inside a markdown code fence
    or in live testbed source, and the assertion is EXACT — the count must be
    the one declared, not merely non-zero. (The docstring advertised `>= 1`
    long after the code stopped doing it; `>= 1` is the fail-open shape
    rf2-e1xx0 removed, and prose describing it is an invitation to put it
    back — rf2-57vnc.) Negative fixtures exercise the
    counterparts that MUST stay green: removed-context prose, an inline code
    span, a `;` comment in a fence, a source docstring / `;` comment mention,
    the bare `:cofx` data-key (no `:where` head), and the rewritten
    `:rf.error/cofx-value-invalid` teaching.
    """
    cases: list[tuple[str, int]] = [
        # (fixture relative to fixture-root, expected finding count)
        # --- positives: a LIVE :where :cofx must FIRE ---
        ("positive/live_where_cofx_fence.md",        1),
        ("positive/live_where_cofx_source.cljs",     1),
        # --- negatives: removed-context / rewritten forms must stay GREEN ---
        ("negative/removed_context_prose.md",        0),
        ("negative/inline_code_span_mention.md",     0),
        ("negative/masked_clj_comment_in_fence.md",  0),
        ("negative/source_comment_mention.cljs",     0),
        ("negative/source_docstring_mention.cljs",   0),
        ("negative/bare_cofx_datakey.cljs",          0),
        ("negative/rewritten_cofx_value_invalid.md", 0),
    ]

    failures = 0
    for fixture, expected in cases:
        path = _SELF_TEST_FIXTURE_ROOT / fixture
        if not path.is_file():
            sys.stderr.write(
                f"self-test FAIL: fixture {fixture!r} missing at {path}\n"
            )
            failures += 1
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        got = len(_scan_file(path, text))
        if got == expected:
            if verbose:
                sys.stderr.write(f"self-test PASS: {fixture} (findings={got})\n")
        else:
            sys.stderr.write(
                f"self-test FAIL: {fixture} expected findings={expected}, "
                f"got {got}\n"
            )
            failures += 1

    if failures:
        sys.stderr.write(f"\n{failures} self-test failure(s).\n")
        return 1
    if verbose:
        sys.stderr.write(f"all {len(cases)} self-tests passed.\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
