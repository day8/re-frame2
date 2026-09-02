#!/usr/bin/env python3
"""EDN well-formedness gate for the conformance corpus (rf2-x91a).

The invariant this gate enforces:

    Every file under `spec/conformance/fixtures/` is a well-formed EDN file
    holding EXACTLY ONE top-level form, with nothing after it.

The defect this prevents (rf2-5mr6): both fixture loaders read a fixture with
`clojure.edn/read-string` over the whole file. `read-string` returns the FIRST
form and IGNORES everything after it, silently. So a fixture whose expectation
block is closed one brace early still loads, still runs, and still reports as
PASSING — while every assertion that fell outside the block is discarded. That
is what `routing-not-found.edn` did: one extra `}` at line 46, two
`:trace-emissions` assertions never executed, green for however long it sat
there.

EVERY HALF OF THE CHECK IS LOAD-BEARING, and none alone is enough:

  * A "does it read without error" check catches NOTHING here — reading
    without error on a malformed file is precisely what `read-string` does.
  * A bracket-balance check alone misses trailing text that happens to
    balance: `{:a 1} {:b 2}` is perfectly balanced and still hides the
    second form from every loader.
  * A DEPTH-ONLY balance check — one counter for all three delimiter kinds —
    misses a mismatched pair, because the two errors cancel: `{:a [1 2)}`
    ends at depth 0, never goes negative, and holds one top-level "form",
    yet is not EDN at all. That was this gate's own false green (rf2-x91a).

So this scans for four things: each closer matching the delimiter kind it
closes, bracket depth that never goes negative, a final depth of zero, and
exactly one top-level form.

WHY A SCANNER AND NOT A PARSER. Python has no EDN reader in the stdlib, and
this gate must run in the fast-PR spine with no dependency to install. The
scan is deliberately lexical: it blanks `;` comments to end of line, treats a
double-quoted string as opaque (honouring backslash escapes), and treats a
backslash outside a string as introducing a one-character char literal (so
`\\(` and `\\"` are consumed, not counted). That is enough to count brackets
correctly, which is the whole job.

This is a corpus scanner, not a loader change, and it catches one thing a
loader change could not: a fixture whose capabilities are out of claim is
SKIPPED by the runner, so a defect inside it is invisible to any check that
runs at load time. Making the loaders themselves refuse trailing text remains
worthwhile and is tracked separately.

Exit code:
    0  no defects
    1  at least one defect
    2  invocation / setup error

Usage:
    python scripts/check_conformance_fixture_edn.py
    python scripts/check_conformance_fixture_edn.py --verbose
    python scripts/check_conformance_fixture_edn.py --ci          # terse; CI-shaped
    python scripts/check_conformance_fixture_edn.py --self-test   # built-in fixtures

rf2-x91a.
"""

from __future__ import annotations

import argparse
import sys
import tempfile
from pathlib import Path
from typing import Iterable

REPO_ROOT = Path(__file__).resolve().parent.parent
FIXTURES_ROOT = REPO_ROOT / "spec" / "conformance" / "fixtures"

# Force UTF-8 on output streams — the corpus carries → / em-dash etc. and the
# default Windows console codec (cp1252) would crash on them.
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8")  # type: ignore[union-attr]
    except (AttributeError, ValueError):  # pragma: no cover - non-reconfigurable stream
        pass

_OPEN = "([{"
_CLOSE = ")]}"
# The closer each opener requires. Aggregate depth cannot see this: `[1 2)`
# and `[1 2]` move the counter identically.
_CLOSER_FOR = {"(": ")", "[": "]", "{": "}"}
# EDN treats a comma as whitespace.
_WS = " \t\r\f\v,"


class Scan:
    """The lexical facts about one fixture's text."""

    __slots__ = (
        "final_depth",
        "min_depth",
        "first_negative_line",
        "top_level_forms",
        "second_form_line",
        "unterminated_string_line",
        "mismatch",
        "unclosed_opener",
    )

    def __init__(self) -> None:
        self.final_depth = 0
        self.min_depth = 0
        self.first_negative_line: int | None = None
        self.top_level_forms = 0
        self.second_form_line: int | None = None
        self.unterminated_string_line: int | None = None
        # (closer_found, closer_line, opener_char, opener_line) for the first
        # closer that does not match the delimiter it closes, else None.
        self.mismatch: tuple[str, int, str, int] | None = None
        # (opener_char, opener_line) for the outermost delimiter still open at
        # end of file, else None.
        self.unclosed_opener: tuple[str, int] | None = None

    @property
    def ok(self) -> bool:
        return (
            self.final_depth == 0
            and self.first_negative_line is None
            and self.top_level_forms == 1
            and self.unterminated_string_line is None
            and self.mismatch is None
        )

    def summary(self) -> str:
        return (
            f"final-depth={self.final_depth} "
            f"min-depth={self.min_depth} "
            f"top-level-forms={self.top_level_forms}"
        )


def scan_edn(text: str) -> Scan:
    """Lexically scan EDN text for bracket balance and top-level form count.

    Not a reader: it never builds a value, and it does not care whether the
    forms are semantically valid EDN. It answers exactly the two questions
    `clojure.edn/read-string` will not — is it balanced, and is there anything
    after the first form.

    Balance is tracked with an opener STACK, not an aggregate depth counter.
    A counter is blind to `{:a [1 2)}`: the `)` and the `}` each move it by
    one, so the two errors cancel and the file scans clean (rf2-x91a). The
    stack knows which delimiter each closer is closing, so it does not.
    """
    s = Scan()
    # Openers still awaiting a closer, innermost last: (char, line).
    stack: list[tuple[str, int]] = []
    # Kept alongside the stack so the reported depth stays a signed number:
    # the stack bottoms out at empty, but depth goes NEGATIVE on a closer
    # with nothing open, which is the rf2-5mr6 signature.
    depth = 0
    line = 1
    in_string = False
    string_start_line = 1
    # True while we are part-way through a run of non-whitespace at depth 0,
    # i.e. inside a top-level form we have already counted.
    in_run = False
    i = 0
    n = len(text)

    while i < n:
        ch = text[i]

        if ch == "\n":
            line += 1
            i += 1
            if not in_string:
                in_run = False
            continue

        if in_string:
            if ch == "\\":
                i += 2  # escaped char inside a string — consume both
                continue
            if ch == '"':
                in_string = False
            i += 1
            continue

        if ch in _WS:
            in_run = False
            i += 1
            continue

        if ch == ";":
            # Comment to end of line. Deliberately BEFORE the form-start
            # check: a header comment is not a top-level form.
            nl = text.find("\n", i)
            if nl == -1:
                break
            i = nl  # the newline branch bumps the line and clears the run
            continue

        # Any other non-whitespace character at depth 0 that is not already
        # part of a counted run begins a new top-level form.
        if depth == 0 and not in_run:
            s.top_level_forms += 1
            if s.top_level_forms == 2 and s.second_form_line is None:
                s.second_form_line = line
            in_run = True

        if ch == "\\":
            i += 2  # char literal: `\(`, `\"`, `\space` ... consume the pair
            continue

        if ch == '"':
            in_string = True
            string_start_line = line
            i += 1
            continue

        if ch in _OPEN:
            stack.append((ch, line))
            depth += 1
            i += 1
            continue

        if ch in _CLOSE:
            depth -= 1
            if depth < s.min_depth:
                s.min_depth = depth
            if depth < 0 and s.first_negative_line is None:
                s.first_negative_line = line
            if stack:
                opener_ch, opener_line = stack.pop()
                if _CLOSER_FOR[opener_ch] != ch and s.mismatch is None:
                    s.mismatch = (ch, line, opener_ch, opener_line)
            if depth <= 0:
                # The form closed: anything after this, even with no
                # whitespace between, is a NEW top-level form.
                in_run = False
            i += 1
            continue

        i += 1

    if in_string:
        s.unterminated_string_line = string_start_line
    if stack:
        s.unclosed_opener = stack[0]
    s.final_depth = depth
    return s


def _defect_reason(s: Scan) -> str | None:
    """The single most informative reason this scan is a defect, or None."""
    if s.unterminated_string_line is not None:
        return (
            f"unterminated string opened at line {s.unterminated_string_line} "
            f"({s.summary()})"
        )
    if s.mismatch is not None:
        found, found_line, opener_ch, opener_line = s.mismatch
        return (
            f"mismatched delimiters — the '{found}' at line {found_line} "
            f"closes the '{opener_ch}' opened at line {opener_line}, which "
            f"needs '{_CLOSER_FOR[opener_ch]}'. Aggregate depth cannot see "
            f"this: the two errors cancel ({s.summary()})"
        )
    if s.first_negative_line is not None:
        return (
            f"unbalanced brackets — a closing bracket at line "
            f"{s.first_negative_line} closes more than is open, so the form "
            f"ends early and every assertion after it is discarded by "
            f"read-string ({s.summary()})"
        )
    if s.final_depth != 0:
        where = ""
        if s.unclosed_opener is not None:
            where = (
                f", the outermost being the '{s.unclosed_opener[0]}' opened "
                f"at line {s.unclosed_opener[1]}"
            )
        return (
            f"unbalanced brackets — {s.final_depth} unclosed at end of file"
            f"{where} ({s.summary()})"
        )
    if s.top_level_forms == 0:
        return f"no top-level EDN form ({s.summary()})"
    if s.top_level_forms > 1:
        return (
            f"trailing text after the first form — a second top-level form "
            f"begins at line {s.second_form_line}, and read-string returns "
            f"only the FIRST, so everything after it is silently discarded "
            f"({s.summary()})"
        )
    return None


def _iter_fixtures(fixtures_root: Path) -> Iterable[Path]:
    if not fixtures_root.is_dir():
        return
    yield from sorted(fixtures_root.rglob("*.edn"))


def check(fixtures_root: Path, verbose: bool = False, ci: bool = False) -> tuple[int, int]:
    """Scan every fixture.  Return (n_scanned, n_defects)."""
    findings: list[tuple[Path, str]] = []
    n_scanned = 0

    for fixture in _iter_fixtures(fixtures_root):
        n_scanned += 1
        try:
            text = fixture.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError) as exc:
            findings.append((fixture, f"unreadable: {exc}"))
            continue
        reason = _defect_reason(scan_edn(text))
        if reason is not None:
            findings.append((fixture, reason))
        elif verbose:
            sys.stderr.write(f"ok: {fixture.name}\n")

    if findings:
        prefix = "::error:: " if ci else ""
        sys.stderr.write(f"\n{len(findings)} malformed conformance fixture(s):\n\n")
        for fixture, reason in findings:
            try:
                rel = fixture.relative_to(REPO_ROOT).as_posix()
            except ValueError:
                rel = fixture.as_posix()
            sys.stderr.write(f"  {prefix}{rel}: {reason}\n")
        sys.stderr.write(
            "\nFix: a conformance fixture must be ONE well-formed EDN form with "
            "nothing after it. Both loaders use clojure.edn/read-string, which "
            "returns the first form and ignores the rest — so a fixture in this "
            "state loads, runs and reports as PASSING while the assertions "
            "outside the first form are never executed. (rf2-x91a, rf2-5mr6)\n"
        )
    elif verbose:
        sys.stderr.write(
            f"all {n_scanned} conformance fixture(s) are one well-formed EDN form.\n"
        )

    return n_scanned, len(findings)


# --------------------------------------------------------------------------
# Self-tests — synthetic fixture dirs exercising both directions.
# --------------------------------------------------------------------------

# The real shape, reduced: a map whose value is a vector of assertion maps.
_GOOD = """;; a conformance fixture
{:kind :routing
 :given {:url "/garbage/path"}
 :trace-emissions
 [{:operation :rf.error/no-such-handler
   :tags {:url "/garbage/path" :kind :route}}
  {:operation :rf.event/run-start}]}
"""

# rf2-5mr6 exactly: one closing brace too many, so the top-level form ends
# early and the assertions after it fall outside it.
_EXTRA_BRACE = """;; a conformance fixture
{:kind :routing
 :given {:url "/garbage/path"}}

 :trace-emissions
 [{:operation :rf.error/no-such-handler}]}
"""

# Balanced, but two top-level forms: read-string returns only the first. A
# depth-only check passes this file.
_TRAILING_FORM = """{:kind :routing
 :given {:url "/garbage/path"}}
{:trace-emissions [{:operation :rf.event/run-start}]}
"""

# Brackets that appear only inside a string or as char literals must NOT be
# counted — otherwise the gate reds the corpus for no reason.
_TRICKY_BUT_VALID = """;; brackets in }} comments {{ are ignored
{:kind :routing
 :url "a string with }}} and {{{ and a \\" quote"
 :chars [\\{ \\} \\( \\)]
 :note "trailing ; is not a comment inside a string"}
"""

# The rf2-x91a false green, reduced to its smallest form. Aggregate depth
# ends at 0, never goes negative, and counts one top-level form — so a
# depth-only scanner calls this well-formed EDN. It is not EDN at all.
_MISMATCHED_PAIR = """{:a [1 2)}
"""

# The same defect at fixture scale: the assertion VECTOR is closed with a
# brace, and the outer map is then closed with the vector's bracket. Every
# aggregate number here is identical to the well-formed file's —
# final-depth=0, min-depth=0, top-level-forms=1 — which is exactly why this
# case, and none of the ones above it, discriminates the two scanners.
_MISMATCHED_IN_FIXTURE = """;; a conformance fixture
{:kind :routing
 :given {:url "/garbage/path"}
 :trace-emissions
 [{:operation :rf.error/no-such-handler}}]
"""

_UNCLOSED = """{:kind :routing
 :given {:url "/x"}
"""

_UNTERMINATED_STRING = """{:kind :routing
 :url "never closed}
"""

_ONLY_COMMENTS = """;; nothing but commentary
;; and more of it
"""


def _run_self_tests(verbose: bool = False) -> int:
    cases: list[tuple[str, str, int]] = [
        # (fixture name, text, expected defect count)
        ("ok_well_formed.edn", _GOOD, 0),
        ("ok_brackets_in_strings_and_chars.edn", _TRICKY_BUT_VALID, 0),
        ("bad_extra_closing_brace.edn", _EXTRA_BRACE, 1),
        ("bad_mismatched_pair.edn", _MISMATCHED_PAIR, 1),
        ("bad_mismatched_in_fixture.edn", _MISMATCHED_IN_FIXTURE, 1),
        ("bad_trailing_second_form.edn", _TRAILING_FORM, 1),
        ("bad_unclosed_form.edn", _UNCLOSED, 1),
        ("bad_unterminated_string.edn", _UNTERMINATED_STRING, 1),
        ("bad_no_form_at_all.edn", _ONLY_COMMENTS, 1),
    ]

    failures = 0
    for name, text, expected in cases:
        with tempfile.TemporaryDirectory() as td:
            root = Path(td) / "fixtures"
            root.mkdir()
            (root / name).write_text(text, encoding="utf-8")

            saved = sys.stderr
            sys.stderr = _DevNull()
            try:
                _, got = check(root, verbose=False, ci=False)
            finally:
                sys.stderr = saved

            if got == expected:
                if verbose:
                    reason = _defect_reason(scan_edn(text))
                    sys.stderr.write(
                        f"self-test PASS: {name} (defects={got})"
                        + (f" — {reason}\n" if reason else "\n")
                    )
            else:
                sys.stderr.write(
                    f"self-test FAIL: {name} expected {expected}, got {got}\n"
                )
                failures += 1

    # The gate must NAME the offending file: a defect count with no filename
    # is not actionable, and this is the half a count-only assertion misses.
    with tempfile.TemporaryDirectory() as td:
        root = Path(td) / "fixtures"
        root.mkdir()
        (root / "bad_named.edn").write_text(_EXTRA_BRACE, encoding="utf-8")
        buf = _Capture()
        saved = sys.stderr
        sys.stderr = buf
        try:
            check(root, verbose=False, ci=False)
        finally:
            sys.stderr = saved
        if "bad_named.edn" not in buf.text:
            sys.stderr.write(
                "self-test FAIL: names_the_file — the finding did not name "
                "bad_named.edn\n"
            )
            failures += 1
        elif verbose:
            sys.stderr.write("self-test PASS: names_the_file\n")

    # A defect COUNT of 1 on the mismatched cases is not enough: if they were
    # caught by the depth rule instead, they would not discriminate an
    # opener stack from the aggregate counter that shipped the false green.
    # Assert the reason, and assert the aggregate numbers are the clean
    # file's, so a regression to depth-only counting fails here.
    for name, text in (
        ("mismatch_pair", _MISMATCHED_PAIR),
        ("mismatch_in_fixture", _MISMATCHED_IN_FIXTURE),
    ):
        s = scan_edn(text)
        reason = _defect_reason(s)
        if (
            reason is None
            or not reason.startswith("mismatched delimiters")
            or s.summary() != "final-depth=0 min-depth=0 top-level-forms=1"
        ):
            sys.stderr.write(
                f"self-test FAIL: {name}_is_invisible_to_depth — expected a "
                f"'mismatched delimiters' defect over clean aggregate "
                f"numbers, got {reason!r} with {s.summary()}\n"
            )
            failures += 1
        elif verbose:
            sys.stderr.write(f"self-test PASS: {name}_is_invisible_to_depth\n")

    if failures:
        sys.stderr.write(f"\n{failures} self-test failure(s).\n")
        return 1
    if verbose:
        sys.stderr.write(f"all {len(cases) + 3} self-tests passed.\n")
    return 0


class _DevNull:
    def write(self, *_args, **_kwargs) -> int:  # noqa: D401
        return 0

    def flush(self) -> None:  # pragma: no cover
        return None


class _Capture:
    def __init__(self) -> None:
        self.text = ""

    def write(self, s: str) -> int:
        self.text += s
        return len(s)

    def flush(self) -> None:  # pragma: no cover
        return None


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Verify every conformance fixture is one well-formed EDN form with "
            "nothing after it (rf2-x91a)."
        ),
    )
    parser.add_argument(
        "--fixtures-root",
        default=None,
        help="Path to the fixtures root. Defaults to <repo>/spec/conformance/fixtures.",
    )
    parser.add_argument("--verbose", "-v", action="store_true")
    parser.add_argument(
        "--ci",
        action="store_true",
        help="CI mode: ::error:: prefixed findings, exit non-zero on any.",
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="Run the bundled synthetic-fixture self-tests and exit.",
    )
    args = parser.parse_args(argv)

    if args.self_test:
        return _run_self_tests(verbose=args.verbose)

    fixtures_root = (
        Path(args.fixtures_root).resolve() if args.fixtures_root else FIXTURES_ROOT
    )
    if not fixtures_root.is_dir():
        sys.stderr.write(f"error: {fixtures_root} is not a directory.\n")
        return 2

    n_scanned, n_defects = check(
        fixtures_root, verbose=args.verbose and not args.ci, ci=args.ci
    )
    if n_scanned == 0:
        sys.stderr.write(f"error: no .edn fixtures found under {fixtures_root}.\n")
        return 2
    return 0 if n_defects == 0 else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
