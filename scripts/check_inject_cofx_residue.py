#!/usr/bin/env python3
"""EP-0017 §migration-residue — live `inject-cofx` / `:rf.world/inputs` doc gate.

EP-0017 (recordable coeffects, slice A) RETIRED two authoring surfaces with no
alias and no coexistence window (EP-0007 rule 2):

  * the `inject-cofx` / `inject-cofx*` coeffect-injection interceptors —
    coeffect delivery is now the `:rf.cofx/requires` registration declaration,
    suppliers are value-returning `reg-cofx`; calling `inject-cofx` is the hard
    error `:rf.error/inject-cofx-removed`; and
  * the `:rf.world/inputs` dispatch opt — renamed to the flat `:rf.cofx`
    recordable-coeffect map; a draft-only name, so supplying `:rf.world/inputs`
    earns no dedicated error id — it rides the generic
    `:rf.warning/unknown-dispatch-opt` surface with a did-you-mean naming
    `:rf.cofx`.

The runtime hard-errors on `inject-cofx` and warns on the retired
`:rf.world/inputs` opt (the generic `:rf.warning/unknown-dispatch-opt`
surface), and `cofx_cljs_test.cljc` pins both. But the
EP landing wave left STALE current-surface teaching examples behind — a
copy-pasteable `[(rf/inject-cofx :rf.server/request)]` interceptor entry in
spec/Pattern-SSR-Loaders.md / spec/Pattern-FormAction.md, prose in
docs/core/api/03-effects.md (rf2-d8mvke.3 finding 2). No residue test scanned the
human-facing surface for the retired API as *live recommended* code, so the
migration-breaking examples survived. This gate is that scan.

THE POLICY (docs/core/AUTHORING.md §Delta teaching)

    "Retired v1 surfaces (`inject-cofx`, `:rf.world/inputs`) appear *only* on
     that migration page, marked superseded — never in teaching prose
     elsewhere."

So a retired spelling is legitimate ONLY as a v1 "before" snippet on the
migration surface (the migration guide page + the migration skill that owns
the v1→v2 reshape) or in removed-context PROSE / comments that describe the
retirement. It is DRIFT anywhere it reads as live, recommended, copy-pasteable
re-frame2 API.

WHY THE SHAPE IS SCOPED TO FENCED CODE (the "where shapes allow" caveat)

`inject-cofx` and `:rf.world/inputs` are named CONSTANTLY in legitimate prose:
"`inject-cofx` is removed", "renamed from `:rf.world/inputs`", "v1's
`inject-cofx` interceptor". A bare keyword grep fires on 50+ correct
removed-context mentions. The load-bearing distinction is *position*:

  * Inside a fenced code block (```...```), the spelling reads as live
    copy-pasteable API — `[(rf/inject-cofx :x)]`, `{:rf.world/inputs {...}}`.
    THIS is the migration-breaking residue the gate fires on.
  * In prose (outside any code fence), the spelling is a NAME being discussed
    — "removed", "retired", "renamed". The gate never looks at prose, so a
    removed-context mention can never fire, with no allowlist needed.
  * In an INLINE `code span` (single backticks) the spelling is also a name
    being named, not a snippet to copy. Inline spans are part of prose and
    are not scanned.

A `;`-to-EOL Clojure comment INSIDE a code fence is masked (length-preserving)
the same way: a fenced block whose only mention is a `;; inject-cofx is
removed` comment is removed-context prose that happens to sit in a code block
(e.g. spec/Spec-Schemas.md's schema-comment, or the migration grep recipe).

THE NARROW ALLOWLIST (migration / removed-context surface only)

A v1 "before / after" example on the migration surface legitimately shows the
retired form inside a code fence — that is its job. The allowlist is exactly
the migration-owning files named by AUTHORING.md plus the EP docs that carry
the retirement rationale:

  * docs/core/25-from-re-frame-v1.md   — THE migration guide page
  * skills/re-frame-migration/**        — the v1→v2 migration skill (owns M-72)
  * docs/EP/** , spec/**/EP-*           — the EP docs (retirement rationale)

The allowlist is a FILE allowlist, not a pattern allowlist: it does not widen
what counts as residue, it only exempts the small set of files whose subject
IS the retirement. Every other doc — the whole teaching surface, the API
reference, the pattern docs, the non-migration skills — must be clean. A
future stale example anywhere outside the allowlist FAILS without needing the
allowlist touched.

SCAN SURFACE

`docs/core/`, `spec/`, and `skills/` markdown (`.md`). Generated
mirrors (`docs/spec/` — CI does `rm -rf docs/spec && cp -r spec docs/spec`)
and vendor/build dirs are excluded; the tracked `spec/` source is the
authoritative copy.

Exit code:
    0  no live retired spelling on the teaching surface
    1  at least one live retired spelling (results printed file:line)
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

# The human-facing + AI-facing doc surface EP-0017 finding 2 names: the API
# reference, the guide, the spec, and the skills docs.
DEFAULT_SCAN_DIRS = ("docs/core", "docs/api", "spec", "skills")

_DOC_SUFFIXES = (".md",)

# Directory names whose contents are never an authoritative teaching surface.
# `docs/spec/` is the generated CI mirror of `spec/` (rm -rf + cp -r), so it
# would double-count every spec hit; the tracked `spec/` tree is the source.
_EXCLUDE_DIR_NAMES = frozenset({
    "node_modules",
    "target",
    ".shadow-cljs",
    ".git",
    ".beads",
    ".cpcache",
    "site",   # mkdocs build output (gitignored)
})

# Generated mirror of spec/ that CI materialises under docs/. Excluded so a
# spec hit is reported once (against the tracked spec/ source), never twice.
_EXCLUDE_REL_PREFIXES = ("docs/spec/",)


# --------------------------------------------------------------------------
# Narrow allowlist — migration / removed-context surface (file-scoped)
# --------------------------------------------------------------------------
#
# These are the only files whose SUBJECT is the v1→v2 retirement, so a v1
# "before" snippet inside a code fence is correct there (per AUTHORING.md
# §Delta teaching). Matched as repo-relative path prefixes / globs with
# forward slashes (normalised before matching).
_ALLOWLIST_PREFIXES = (
    "docs/core/25-from-re-frame-v1.md",   # THE migration guide page
    "skills/re-frame-migration/",          # the v1→v2 migration skill (M-72)
    "docs/EP/",                            # EP docs (retirement rationale)
)

# EP docs may also live under spec/ as EP-*.md; allow those by basename glob.
_ALLOWLIST_BASENAME_RE = re.compile(r"(?:^|/)EP-\d+[^/]*\.md$")


def _is_allowlisted(rel_posix: str) -> bool:
    if any(rel_posix.startswith(p) for p in _ALLOWLIST_PREFIXES):
        return True
    if _ALLOWLIST_BASENAME_RE.search(rel_posix):
        return True
    return False


# --------------------------------------------------------------------------
# Retired-spelling patterns (matched only inside fenced code, see below)
# --------------------------------------------------------------------------
#
# `rf/inject-cofx` / `inject-cofx` / `inject-cofx*` — the retired interceptor.
# `\b` boundaries forbid matching a longer symbol; the optional namespace
# segment catches `rf/`, `re-frame.core/`, etc.; the optional trailing `*`
# catches the `inject-cofx*` fn form.
_INJECT_COFX_RE = re.compile(
    r"(?<![\w.+!*?<>=/-])(?:[\w.+!*?<>=/-]+/)?inject-cofx\*?(?![\w.+!*?<>=/-])"
)

# `:rf.world/inputs` — the retired dispatch-opt / envelope key. `(?![\w/-])`
# forbids `:rf.world/inputs-foo`; the leading negative-lookbehind keeps it from
# matching mid-keyword.
_WORLD_INPUTS_RE = re.compile(r":rf\.world/inputs(?![\w/-])")

_RESIDUE_PATTERNS = (
    ("inject-cofx", _INJECT_COFX_RE),
    (":rf.world/inputs", _WORLD_INPUTS_RE),
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
# We scan ONLY inside fenced code blocks (``` or ~~~), and within those, mask
# `;`-to-EOL Clojure line comments so a removed-context comment in a code block
# (e.g. a schema annotation, or the migration grep recipe) does not fire.
#
# Fence handling follows CommonMark closely enough for our docs: a fence opens
# on a line that begins (after optional indent) with >= 3 backticks or tildes,
# and closes on a later line with a matching fence of the same char and at
# least as many marks. The masking is length-preserving so 1-based line numbers
# and reported snippets line up with the raw source.

_FENCE_RE = re.compile(r"^(\s*)(`{3,}|~{3,})")
_LINE_COMMENT_RE = re.compile(r";.*$")


def _mask_clj_comment(line: str) -> str:
    """Blank a `;`-to-EOL Clojure line comment, length-preserving.

    Conservative: a `;` anywhere blanks to EOL. Inside a code fence a `;`
    almost always starts a comment in the Clojure-shaped snippets these docs
    carry; the rare in-string `;` would only matter if a retired spelling sat
    in a string literal after it, which does not occur in practice. The
    masking only ever REMOVES potential matches, so it cannot cause a false
    PASS for live code outside a comment.
    """
    m = _LINE_COMMENT_RE.search(line)
    if not m:
        return line
    start = m.start()
    return line[:start] + (" " * (len(line) - start))


def _code_fence_lines(text: str) -> list[tuple[int, str]]:
    """Return (1-based-line-no, masked-content) for lines INSIDE fenced code.

    Lines outside any fence (prose, inline code spans, headings) are omitted
    entirely — the gate never inspects prose. Comment lines inside a fence are
    yielded MASKED (blanked) so a removed-context comment cannot fire.
    """
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
            # Already in a fence: a same-char fence of >= the opening length
            # closes it. Otherwise it is content (a nested-looking fence).
            if char == fence_char and length >= fence_len:
                in_fence = False
                fence_char = ""
                fence_len = 0
                continue
        if in_fence:
            out.append((line_no, _mask_clj_comment(raw)))
    return out


# --------------------------------------------------------------------------
# File iteration
# --------------------------------------------------------------------------


def _iter_doc_files(scan_root: Path, repo_root: Path) -> Iterable[Path]:
    """Yield markdown files under scan_root, excluding generated/vendor dirs."""
    if scan_root.is_file():
        # Direct-file mode (used by --self-test fixtures pointing at one file).
        if scan_root.suffix in _DOC_SUFFIXES:
            yield scan_root
        return
    for path in sorted(scan_root.rglob("*")):
        if path.suffix not in _DOC_SUFFIXES:
            continue
        parts = set(path.relative_to(scan_root).parts)
        if parts & _EXCLUDE_DIR_NAMES:
            continue
        try:
            rel_posix = path.relative_to(repo_root).as_posix()
        except ValueError:
            rel_posix = path.as_posix()
        if any(rel_posix.startswith(pre) for pre in _EXCLUDE_REL_PREFIXES):
            continue
        yield path


# --------------------------------------------------------------------------
# Per-file scan
# --------------------------------------------------------------------------


def _scan_text(path: Path, text: str, rel_posix: str) -> list[Finding]:
    """Return live-residue findings in `text` (already file-attributed).

    Allowlisted files (the migration / EP surface) are skipped entirely — a v1
    "before" snippet is correct there. For every other file, scan only the
    masked fenced-code lines for a retired spelling.
    """
    if _is_allowlisted(rel_posix):
        return []
    findings: list[Finding] = []
    raw = text.splitlines()
    for line_no, masked in _code_fence_lines(text):
        for kind, pat in _RESIDUE_PATTERNS:
            if pat.search(masked):
                snippet = raw[line_no - 1].strip() if 0 <= line_no - 1 < len(raw) else ""
                findings.append(Finding(path, line_no, f"live-residue:{kind}", snippet))
    return findings


def scan(scan_root: Path, repo_root: Path | None = None) -> list[Finding]:
    """Scan doc markdown under scan_root for live retired spellings."""
    if repo_root is None:
        repo_root = scan_root if scan_root.is_dir() else scan_root.parent
    findings: list[Finding] = []
    for path in _iter_doc_files(scan_root, repo_root):
        try:
            rel_posix = path.relative_to(repo_root).as_posix()
        except ValueError:
            rel_posix = path.as_posix()
        text = path.read_text(encoding="utf-8", errors="replace")
        findings.extend(_scan_text(path, text, rel_posix))
    return findings


# --------------------------------------------------------------------------
# Reporting
# --------------------------------------------------------------------------

_FIX_HINTS = {
    "inject-cofx": (
        "`inject-cofx` / `inject-cofx*` were REMOVED in EP-0017 (no alias; "
        "hard error `:rf.error/inject-cofx-removed`). A handler declares the "
        "coeffects it consumes with the `:rf.cofx/requires` registration-"
        "metadata key, and suppliers are value-returning `reg-cofx` "
        "(`(fn [] v)` / `(fn [arg] v)`). Rewrite the interceptor entry "
        "`[(rf/inject-cofx :x)]` as `{:rf.cofx/requires [:x]}` on the event's "
        "registration map. See docs/core/07-effects-and-coeffects.md and "
        "spec/001-Registration.md §`inject-cofx` is removed. (A v1 \"before\" "
        "snippet is legitimate ONLY on the migration surface — the migration "
        "guide page / the re-frame-migration skill / the EP docs.)"
    ),
    ":rf.world/inputs": (
        "`:rf.world/inputs` was RENAMED to the flat `:rf.cofx` recordable-"
        "coeffect map in EP-0017 (no alias). It was a draft-only name, so "
        "supplying it earns no dedicated error id — it rides the generic "
        "`:rf.warning/unknown-dispatch-opt` surface with a did-you-mean "
        "naming `:rf.cofx`. Use `:rf.cofx` as the dispatch opt "
        "/ envelope field. See spec/002-Frames.md §The `:rf.cofx` envelope "
        "field and spec/Spec-Schemas.md §`:rf.cofx`."
    ),
}


def _report(findings: list[Finding], repo_root: Path) -> None:
    sys.stderr.write(
        f"\n{len(findings)} live retired-spelling residue hit(s) found on the "
        "teaching surface (EP-0017 §migration-residue):\n\n"
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
            "EP-0017 §migration-residue: fail on a live `inject-cofx` / "
            "`:rf.world/inputs` example on the teaching surface (fenced code "
            "outside the narrow migration allowlist)."
        ),
    )
    parser.add_argument(
        "--repo-root",
        default=None,
        help="Path to the repo root. Defaults to the script's grandparent.",
    )
    parser.add_argument(
        "--scan-dir",
        action="append",
        default=None,
        help=(
            "Directory (relative to repo-root) to scan. Repeatable. Defaults "
            f"to {list(DEFAULT_SCAN_DIRS)}."
        ),
    )
    parser.add_argument(
        "--verbose", "-v", action="store_true", help="Print progress to stderr."
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help=(
            "Run the bundled fixture-based self-tests in "
            "scripts/_test_fixtures/check_inject_cofx_residue/ and exit."
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

    scan_dirs = args.scan_dir or list(DEFAULT_SCAN_DIRS)
    all_findings: list[Finding] = []
    for d in scan_dirs:
        scan_root = repo_root / d
        if not scan_root.exists():
            sys.stderr.write(f"error: scan dir {scan_root} does not exist.\n")
            return 2
        if args.verbose:
            n = sum(1 for _ in _iter_doc_files(scan_root, repo_root))
            sys.stderr.write(f"scanning {n} markdown file(s) under {d}...\n")
        all_findings.extend(scan(scan_root, repo_root=repo_root))

    if all_findings:
        _report(all_findings, repo_root)
        return 1
    if args.verbose:
        sys.stderr.write("no live retired-spelling residue on the teaching surface.\n")
    return 0


# --------------------------------------------------------------------------
# Self-tests (fixture-driven) — prove the gate FIRES on each live shape
# and stays GREEN on every removed-context / migration counterpart.
# --------------------------------------------------------------------------

_SELF_TEST_FIXTURE_ROOT = (
    Path(__file__).resolve().parent / "_test_fixtures" / "check_inject_cofx_residue"
)


def _run_self_tests(verbose: bool = False) -> int:
    """Scan each fixture file and assert the expected finding count.

    Positive fixtures plant a LIVE retired spelling inside a code fence on a
    non-allowlisted-shaped page (expected >= 1). Negative fixtures exercise the
    counterparts that MUST stay green: removed-context prose, an inline code
    span, a masked `;` comment in a fence, and the rewritten `:rf.cofx/requires`
    teaching. Allowlist behaviour is covered by a dedicated case that scans a
    fixture as if it were the migration page.
    """
    cases: list[tuple[str, int]] = [
        # (fixture relative to fixture-root, expected finding count)
        # --- positives: a LIVE retired spelling in a code fence must FIRE ---
        # Replicates the rf2-d8mvke.3 stale Pattern-SSR / Pattern-FormAction
        # example: a copy-pasteable `[(rf/inject-cofx :rf.server/request)]`.
        ("positive/live_inject_cofx_interceptor.md",   1),
        ("positive/live_inject_cofx_star.md",          1),
        ("positive/live_world_inputs_dispatch.md",     1),
        # --- negatives: removed-context / rewritten forms must stay GREEN ---
        ("negative/removed_context_prose.md",          0),
        ("negative/inline_code_span_mention.md",       0),
        ("negative/masked_clj_comment_in_fence.md",    0),
        ("negative/rewritten_requires_teaching.md",    0),
        ("negative/world_inputs_prose_rename.md",      0),
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
        # Direct-file scan; the fixture's own posix path is non-allowlisted
        # (it does not start with a migration prefix), so positives fire.
        text = path.read_text(encoding="utf-8", errors="replace")
        got = len(_scan_text(path, text, rel_posix=fixture))
        if got == expected:
            if verbose:
                sys.stderr.write(f"self-test PASS: {fixture} (findings={got})\n")
        else:
            sys.stderr.write(
                f"self-test FAIL: {fixture} expected findings={expected}, "
                f"got {got}\n"
            )
            failures += 1

    # Dedicated allowlist case: the SAME live-residue fixture, scanned as if it
    # were the migration guide page, must NOT fire (the allowlist exempts it).
    allow_fixture = "positive/live_inject_cofx_interceptor.md"
    allow_path = _SELF_TEST_FIXTURE_ROOT / allow_fixture
    if allow_path.is_file():
        text = allow_path.read_text(encoding="utf-8", errors="replace")
        got = len(_scan_text(allow_path, text,
                             rel_posix="docs/core/25-from-re-frame-v1.md"))
        if got == 0:
            if verbose:
                sys.stderr.write(
                    "self-test PASS: allowlist exempts migration-page residue\n"
                )
        else:
            sys.stderr.write(
                "self-test FAIL: allowlist did NOT exempt migration-page "
                f"residue (got {got} findings)\n"
            )
            failures += 1
    else:
        sys.stderr.write(
            f"self-test FAIL: allowlist fixture {allow_fixture!r} missing\n"
        )
        failures += 1

    if failures:
        sys.stderr.write(f"\n{failures} self-test failure(s).\n")
        return 1
    if verbose:
        sys.stderr.write(f"all {len(cases) + 1} self-tests passed.\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
