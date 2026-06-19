#!/usr/bin/env python3
"""EP-0023 §retired-composition-vocabulary — public-surface vocabulary guardrail.

EP-0023 (image-loaded frames) established `image -> frame -> event stream` as
THE public composition model and RETIRED the older EP-0013 app/realm/module
construction-and-install vocabulary at the public app surface (no alias). The
`pl97nd` epic scrubbed the retired vocabulary repo-wide (children .2/.3/.4 are
merged: the facade exports, the spec/API rows, and the doc/skill teaching
surface are all clean). This gate is the guardrail that keeps the scrub done:
it fails if a retired composition spelling reappears as LIVE, copy-pasteable
public API on the teaching / reference surface.

THE POLICY (EP-0023 §Naming, docs/EP/EP-0023-image-loaded-frames.md:201)

    "Avoid using `app`, `application`, `realm`, or `module` as new conceptual
     nouns in this model."

and the supersession table (EP-0023:1670) maps the retired construction +
install + inspection family — `rf/install!`, `rf/reinstall!`, `rf/realm`,
`rf/dispose-realm!`, `rf/installed-app`, `rf/realm-ids`, `rf/frame-realm`,
`rf/app-registrations`, `rf/app-owns`, `rf/app-requires`, `rf/module`,
`rf/app` — onto the public `rf/image` + `rf/make-frame` + `rf/reload-images!`
+ `rf/destroy-frame!` model.

So a retired composition spelling is legitimate ONLY where the SUBJECT is the
retirement itself — the EP design/supersession docs, the migration skill, the
port-builder implementor skill, the tooling skills (Pair / Xray) that read the
retained-internal substrate, and the spec meta-docs that map the removal. It is
DRIFT anywhere it reads as live, recommended, copy-pasteable re-frame2 API on
the public teaching surface.

WHY THE SHAPE IS SCOPED TO FENCED CODE (the "where shapes allow" caveat)

The words `app`, `application`, `module`, and `realm` are named CONSTANTLY in
legitimate prose: "the application", "app-db" (a sanctioned existing term per
EP-0023:204), "the module manifest", "discussing the realm container". A bare
word grep fires on hundreds of correct sites. The retired SYMBOLS are the
load-bearing signal, and *position* disambiguates:

  * Inside a fenced code block (```...```), a retired symbol reads as live
    copy-pasteable API — `(rf/install! realm app)`, `(rf/app-owns app [:cart])`,
    `(rf/realm ...)`. THIS is the drift the gate fires on.
  * In prose (outside any code fence), the symbol is a NAME being discussed —
    "`rf/install!` was retired", "the old `rf/realm` surface". The gate never
    looks at prose, so a removed-context mention can never fire.
  * In an INLINE `code span` (single backticks) the symbol is also a name being
    named, not a snippet to copy. Inline spans are part of prose and not scanned.

A `;`-to-EOL Clojure comment INSIDE a code fence is masked (length-preserving)
the same way: a fenced block whose only mention is a `;; rf/install! is retired`
comment is removed-context prose that happens to sit in a code block.

THE NARROW ALLOWLIST (historical / internal / migration surface only)

A retired symbol inside a code fence is correct on the surfaces whose SUBJECT
IS the retired vocabulary — the EP design/supersession docs carry worked
examples of the old construction, the migration skill shows the v1->v2 reshape,
the implementor skill specifies the retained-internal substrate for port
builders, the tooling skills read the internal substrate, and the spec
meta-docs map the removal. The allowlist is a FILE allowlist, not a pattern
allowlist: it does not widen what counts as the retired vocabulary, it only
exempts the small set of files whose subject IS the retirement. Every other
doc — the whole teaching surface, the public API reference, the pattern docs,
the authoring skill's teaching prose — must be clean. A future stale example
anywhere outside the allowlist FAILS without the allowlist being touched.

Each allowlist entry is self-documenting (see `_ALLOWLIST` below): it carries a
one-line note tying the exemption to the inventory finding
(ai/findings/API-review/codex/retired-app-composition-vocabulary.md) or the
EP/migration policy.

NOTE — the broader facade-hygiene gate (rf2-gqa7yv,
implementation/scripts/api-manifest/src/re_frame/api_manifest/facade_hygiene_check.clj)
is the MANIFEST-DATA counterpart of this DOC-SURFACE gate, and the two are
COMPLEMENTARY rather than one subsuming the other. This grep keeps the public
TEACHING surface clean — a retired spelling reappearing as live, copy-pasteable
API inside markdown fenced code. The manifest-hygiene gate keeps the
FACADE-EXPORT set clean — it joins the same EP-0013 -> EP-0023 dispositions
(re-frame.migration/migration-map) against spec/api-manifest.edn and fails if a
superseded export stays `:facade? true` instead of being demoted to its home
namespace or removed. Different surfaces (teaching prose vs. the manifest),
both driven from the same retirement; neither duplicates the other. Keep this
guard deliberately small and modular; do not grow it into the manifest gate.

SCAN SURFACE

`docs/api`, `docs/guide`, `spec`, and `skills` markdown (`.md`). Generated
mirrors (`docs/spec/` — CI does `rm -rf docs/spec && cp -r spec docs/spec`)
and vendor/build dirs are excluded; the tracked `spec/` source is the
authoritative copy. `docs/EP` rides under `docs/` but is allowlisted as a whole
(the EP docs ARE the retirement record).

Exit code:
    0  no live retired composition vocabulary on the teaching surface
    1  at least one live retired symbol (results printed file:line)
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

# The human-facing + AI-facing doc surface EP-0023 governs: the API reference,
# the guide, the spec, and the skills docs. (docs/EP rides under docs/ via the
# `docs/api`/`docs/guide`-sibling tree, but every EP doc is allowlisted below.)
DEFAULT_SCAN_DIRS = ("docs/api", "docs/guide", "docs/EP", "spec", "skills")

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
# Narrow allowlist — historical / internal / migration surface (file-scoped)
# --------------------------------------------------------------------------
#
# These are the only surfaces whose SUBJECT is the EP-0013->EP-0023 retirement,
# so a worked example of the retired construction inside a code fence is correct
# there. Each entry is (repo-relative path prefix, self-documenting note).
# Matched as forward-slash path prefixes (normalised before matching). KEEP THIS
# MINIMAL — every entry must name why the surface legitimately carries the
# retired vocabulary, tied to the inventory or an EP/migration policy.
_ALLOWLIST: tuple[tuple[str, str], ...] = (
    # The EP design + supersession docs ARE the retirement record. EP-0013 is
    # the design doc that introduced the app/realm/module construction; EP-0023
    # supersedes it and carries the mapping table (EP-0023:1670). Worked
    # examples of the old surface inside code fences are the docs' subject.
    ("docs/EP/",
     "EP design/supersession docs — the retirement record; worked examples of "
     "the retired surface are the docs' subject (EP-0013 design, EP-0023 §1670 "
     "supersession map)."),
    # EP-* docs may also live under spec/ (handled by the basename glob below);
    # the spec meta-docs that MAP the removal carry the retired symbols as the
    # thing-being-removed. Inventory §Implementation lists these as the scrub's
    # spec surface; they discuss the retired terms historically.
    ("spec/Ownership.md",
     "spec meta-doc — maps every contract surface to its owner; references the "
     "retained-internal realm substrate historically (inventory §Observed use 6)."),
    ("spec/Runtime-Subsystems.md",
     "spec meta-doc — grades the retained-internal substrate (realm/dispose) "
     "that EP-0023 keeps off the facade but alive for SSR routing (inventory "
     "§Proposed cleanup: 'realm machinery stays as internal substrate')."),
    ("spec/Conventions.md",
     "spec meta-doc — the normative naming/vocabulary catalogue; discusses the "
     "retired composition nouns to record that they are retired."),
    # The migration skill teaches the v1->v2 reshape: it MUST show the retired
    # surface as the 'before' so authors recognise what to migrate AWAY from.
    ("skills/re-frame-migration/",
     "v1->v2 migration skill — shows the retired surface as the 'before' shape "
     "to migrate away from (EP-0007 §Delta teaching analogue)."),
    # The port-builder implementor skill specifies the retained-INTERNAL
    # substrate (realm/install/dispose) that a port must implement off-facade.
    # Inventory §Proposed cleanup keeps the realm machinery as internal substrate.
    ("skills/re-frame2-implementor/",
     "port-builder skill — specifies the retained-internal substrate (realm/"
     "install/dispose) a port implements off-facade (inventory §Proposed cleanup)."),
    # The Pair + Xray tooling skills READ the internal substrate (via the
    # internal `re-frame.realm/` + `re-frame.frame/` namespaces, NOT the `rf/`
    # facade) to drive discovery + the legacy module/realm inspection lens.
    # Inventory §Observed use 6 names these as tooling migration targets, not
    # facade vocabulary.
    ("skills/re-frame2-pair/",
     "Pair tooling skill — reads the internal realm/frame substrate via "
     "re-frame.realm/ + re-frame.frame/ (NOT rf/ facade) for live discovery "
     "(inventory §Observed use 6)."),
    ("skills/re-frame2-xray/",
     "Xray tooling skill — reads the internal substrate for the legacy "
     "module/realm inspection lens (inventory §Observed use 6, a tooling "
     "migration target)."),
    # The shared tool-pair-surfaces contract documents the realm pair as the
    # internal multi-container addressing mechanism used by the tooling skills.
    ("skills/shared/tool-pair-surfaces.md",
     "shared tool contract — documents the internal realm-pair addressing the "
     "tooling skills consume (labelled internal, not facade)."),
)

# EP docs also appear under spec/ as `EP-*.md` (e.g. spec/EP-0007.md); allow
# those by basename glob in addition to the docs/EP/ prefix above.
_ALLOWLIST_BASENAME_RE = re.compile(r"(?:^|/)EP-\d+[^/]*\.md$")


def _is_allowlisted(rel_posix: str) -> bool:
    if any(rel_posix.startswith(p) for p, _ in _ALLOWLIST):
        return True
    if _ALLOWLIST_BASENAME_RE.search(rel_posix):
        return True
    return False


# --------------------------------------------------------------------------
# Retired-symbol patterns (matched only inside fenced code, see below)
# --------------------------------------------------------------------------
#
# Each pattern targets a RETIRED composition SYMBOL — the load-bearing signal,
# not a bare English word. Two families:
#
#   (a) The strong, unambiguous construction/install/inspection symbols. These
#       dash-cased identifiers are not English words and not used by any
#       sanctioned surface, so a bare-symbol match (optionally namespace-
#       qualified) is safe. The negative-lookarounds keep `install!` from
#       matching a longer symbol and forbid `realm-ids-foo` etc.
#
#   (b) The conceptual-noun symbols `app` / `module` / `realm` ONLY in the
#       dangerous NAMESPACED-CALL shape `(rf/app ...)`, `(rf/module ...)`,
#       `(rf/realm ...)`. A bare `realm`/`module`/`app` word is far too noisy
#       (app-db, the application, the module manifest) — EP-0023:204 explicitly
#       sanctions `app-db` and ordinary English `application` — so we scope to
#       the `rf/`-prefixed facade-call form, which is exactly the retired API
#       reintroduction the inventory targets.
#
# A symbol char class for Clojure identifiers (dash-cased + the usual punct).
_SYM = r"\w.+!?<>=-"

# (a) Strong unambiguous retired symbols. The drift shape is the BARE or
#     `rf/`-FACADE-qualified spelling — the public reintroduction the inventory
#     targets. A symbol qualified by ANY OTHER namespace names a different
#     symbol that merely shares the bare name (the internal `re-frame.realm/`
#     substrate read; an app's own `counter/install!` setup hook) and is NOT
#     drift — see `_strong_hits` for the qualifier filter. The trailing
#     negative-lookahead forbids matching a longer symbol (so `realm-ids` does
#     not match `realm-ids-extra`); `install!` / `reinstall!` / `dispose-realm!`
#     end in `!` which already bounds them.
_STRONG_SYMBOLS = (
    "reinstall!",          # listed before `install!` so the alternation
    "install!",            #   prefers the longer match
    "dispose-realm!",
    "realm-ids",
    "installed-app",
    "app-registrations",
    "app-owns",
    "app-requires",
    "frame-realm",
)

# Build one alternation. The optional namespace qualifier is CAPTURED so the
# matcher (`_strong_hits` below) can keep ONLY the facade forms (bare or `rf/`)
# and reject any other qualifier (`re-frame.realm/realm-ids` internal substrate,
# `counter/install!` app hook). The leading negative-lookbehind keeps it from
# matching mid-identifier; the trailing `(?![\w.+!?<>=-])` keeps `realm-ids`
# from matching `realm-idsx` and `app-owns` from `app-ownsx`. `install!` won't
# match inside `reinstall!`/`installed-app` because the lookbehind forbids a
# preceding identifier char.
_STRONG_ALT = "|".join(re.escape(s) for s in _STRONG_SYMBOLS)
_STRONG_RE = re.compile(
    rf"(?<![{_SYM}])(?P<ns>[{_SYM}]+/)?(?:{_STRONG_ALT})(?![{_SYM}])"
)

# The retired SYMBOLS are `re-frame.core` (`rf/`) FACADE exports. The drift is
# therefore exactly the `rf/`-qualified or BARE-unqualified spelling. Any OTHER
# namespace qualifier names a DIFFERENT symbol that merely shares the bare name:
#
#   * `re-frame.realm/realm-ids` — the RETAINED internal substrate read
#     (inventory §Proposed cleanup: "the realm machinery stays as internal
#     substrate"), legitimately used by tooling. (`re-frame.frame/frame-realm`
#     and the per-frame realm-membership view were removed entirely under
#     rf2-70owfr — the afdlyr collapse leaves a single default realm; the bare /
#     `rf/` facade spelling stays a guarded drift symbol regardless.)
#   * `counter/install!`, `my.app/install!` — an APP's own setup hook (a normal
#     name for an example app's registration entry point; see
#     spec/008-Testing.md §Pattern 5 `with-app-fixture {:install counter/install!}`).
#     `install!` is not a reserved word; an app may name its own fn that.
#
# So a strong-symbol match is drift ONLY when its captured `ns` qualifier is
# empty (bare) or exactly `rf/` (the facade). A bare `install!` is still caught
# because the dangerous reintroduction `[(rf/install! ...)]` is the common shape
# AND a bare `(install! ...)` on the public teaching surface reads as the
# retired verb; an app's setup hook is always namespace-qualified (it lives in
# the app's ns, called as `counter/install!`), so the bare form is unambiguous
# drift on the teaching surface.
_FACADE_NS_QUALIFIERS = frozenset({"", "rf/"})


def _strong_hits(line: str) -> bool:
    """True iff `line` carries a retired strong-symbol drift (bare or `rf/`).

    A match qualified by any namespace OTHER than `rf/` (e.g. the internal
    `re-frame.realm/`, or an app's own `counter/`) names a different symbol
    that merely shares the bare name, and is NOT drift.
    """
    for m in _STRONG_RE.finditer(line):
        ns = m.group("ns") or ""
        if ns in _FACADE_NS_QUALIFIERS:
            return True
    return False

# (b) Retired conceptual-noun facade CALLS: `(rf/app ...)`, `(rf/module ...)`,
#     `(rf/realm ...)`. Scoped to the open-paren + `rf/`-qualified call form so
#     `app-db`, "the application", the `:rf.realm/...` keyword, and
#     `re-frame.realm/` internal namespace reads never match. The trailing
#     `(?![\w.+!?<>=-])` forbids `rf/app-db`, `rf/realm-thing`, etc. — those
#     are different symbols (and `rf/app-db` is not even a real export). We
#     deliberately require the `(` so a bare `rf/realm` mentioned as a value
#     does not fire; the retired API reintroduction is always a call.
_RETIRED_FACADE_CALL_RE = re.compile(
    r"\(\s*rf/(?:app|module|realm)(?![\w.+!?<>=-])"
)

# Each family is a (kind, predicate) pair where the predicate maps a single
# (masked) line to True iff it carries that family's drift. The strong family
# uses `_strong_hits` (capture + internal-namespace filter); the facade-noun
# family is a plain regex search.
_RETIRED_PATTERNS = (
    ("retired-construction-symbol", _strong_hits),
    ("retired-facade-noun-call", lambda line: bool(_RETIRED_FACADE_CALL_RE.search(line))),
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
# does not fire. Fence handling follows CommonMark closely enough for our docs:
# a fence opens on a line beginning (after optional indent) with >= 3 backticks
# or tildes, and closes on a later line with a matching fence of the same char
# and at least as many marks. Masking is length-preserving so 1-based line
# numbers and reported snippets line up with the raw source.
#
# (Shape copied from scripts/check_inject_cofx_residue.py — the sibling EP-0017
# residue gate — so the two doc-surface vocabulary gates stay consistent.)

_FENCE_RE = re.compile(r"^(\s*)(`{3,}|~{3,})")
_LINE_COMMENT_RE = re.compile(r";.*$")


def _mask_clj_comment(line: str) -> str:
    """Blank a `;`-to-EOL Clojure line comment, length-preserving.

    Conservative: a `;` anywhere blanks to EOL. Inside a code fence a `;`
    almost always starts a comment in the Clojure-shaped snippets these docs
    carry. The masking only ever REMOVES potential matches, so it cannot cause
    a false PASS for live code outside a comment.
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
    """Return live retired-vocabulary findings in `text` (already attributed).

    Allowlisted files (the historical / internal / migration surface) are
    skipped entirely — a worked example of the retired surface is correct
    there. For every other file, scan only the masked fenced-code lines for a
    retired symbol.
    """
    if _is_allowlisted(rel_posix):
        return []
    findings: list[Finding] = []
    raw = text.splitlines()
    for line_no, masked in _code_fence_lines(text):
        for kind, predicate in _RETIRED_PATTERNS:
            if predicate(masked):
                snippet = raw[line_no - 1].strip() if 0 <= line_no - 1 < len(raw) else ""
                findings.append(Finding(path, line_no, f"live-retired:{kind}", snippet))
    return findings


def scan(scan_root: Path, repo_root: Path | None = None) -> list[Finding]:
    """Scan doc markdown under scan_root for live retired composition vocab."""
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
    "retired-construction-symbol": (
        "The EP-0013 construction/install/inspection symbols (`install!`, "
        "`reinstall!`, `dispose-realm!`, `realm-ids`, `installed-app`, "
        "`app-registrations`, `app-owns`, `app-requires`, `frame-realm`) were "
        "RETIRED from the public facade by EP-0023 (image-loaded frames). The "
        "public composition model is `rf/image` -> `rf/make-frame` / "
        "`rf/reg-frame` -> `rf/reload-images!` / `rf/destroy-frame!`. Rewrite "
        "the example onto that model. (The realm machinery survives as an "
        "INTERNAL substrate behind `re-frame.realm/` / `re-frame.frame/` for "
        "tooling + SSR routing — that is allowlisted on the Pair/Xray/"
        "implementor skills, not the public teaching surface.)"
    ),
    "retired-facade-noun-call": (
        "`(rf/app ...)`, `(rf/module ...)`, and `(rf/realm ...)` are the "
        "retired EP-0013 facade constructors. EP-0023 §Naming "
        "(docs/EP/EP-0023-image-loaded-frames.md:201) retires `app` / `realm` "
        "/ `module` as composition nouns in favour of `image` -> `frame` -> "
        "`event stream`. Build composition from `rf/image` + `rf/make-frame` "
        "instead. (`app-db` is a sanctioned EXISTING term — EP-0023:204 — and "
        "ordinary English `application` is fine; only the `rf/`-facade noun "
        "CALL is the drift.)"
    ),
}


def _report(findings: list[Finding], repo_root: Path) -> None:
    sys.stderr.write(
        f"\n{len(findings)} live retired composition-vocabulary hit(s) found on "
        "the teaching surface (EP-0023 §retired-composition-vocabulary):\n\n"
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
    sys.stderr.write(
        "\nIf the surface legitimately discusses the retired vocabulary "
        "historically (an EP doc, the migration skill, a tooling/implementor "
        "skill that reads the internal substrate, or a spec meta-doc), add a "
        "self-documenting entry to `_ALLOWLIST` in "
        "scripts/check_retired_composition_vocab.py tying it to the inventory "
        "(ai/findings/API-review/codex/retired-app-composition-vocabulary.md).\n"
    )


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "EP-0023 §retired-composition-vocabulary: fail on a live retired "
            "app/realm/module composition symbol on the public teaching "
            "surface (fenced code outside the narrow historical allowlist)."
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
            "scripts/_test_fixtures/check_retired_composition_vocab/ and exit."
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
        sys.stderr.write(
            "no live retired composition vocabulary on the teaching surface.\n"
        )
    return 0


# --------------------------------------------------------------------------
# Self-tests (fixture-driven) — prove the gate FIRES on each live shape
# and stays GREEN on every removed-context / sanctioned counterpart.
# --------------------------------------------------------------------------

_SELF_TEST_FIXTURE_ROOT = (
    Path(__file__).resolve().parent
    / "_test_fixtures"
    / "check_retired_composition_vocab"
)


def _run_self_tests(verbose: bool = False) -> int:
    """Scan each fixture file and assert the expected finding count.

    Positive fixtures plant a LIVE retired symbol inside a code fence on a
    non-allowlisted-shaped page (expected >= 1). Negative fixtures exercise the
    counterparts that MUST stay green: removed-context prose, an inline code
    span, a masked `;` comment in a fence, the sanctioned `app-db` term, the
    rewritten image/frame teaching, and the internal `re-frame.realm/` /
    `re-frame.frame/` namespace reads. A dedicated allowlist case scans a
    positive fixture AS IF it were an EP doc and asserts it does NOT fire.
    """
    cases: list[tuple[str, int]] = [
        # (fixture relative to fixture-root, expected finding count)
        # --- positives: a LIVE retired symbol in a code fence must FIRE ---
        ("positive/live_install_realm_app.md",         1),
        ("positive/live_reinstall.md",                 1),
        ("positive/live_app_owns_inspector.md",        1),
        ("positive/live_rf_realm_call.md",             1),
        ("positive/live_rf_module_call.md",            1),
        ("positive/live_installed_app_read.md",        1),
        # --- negatives: removed-context / sanctioned forms must stay GREEN ---
        ("negative/removed_context_prose.md",          0),
        ("negative/inline_code_span_mention.md",       0),
        ("negative/masked_clj_comment_in_fence.md",    0),
        ("negative/app_db_sanctioned.md",              0),
        ("negative/rewritten_image_frame_teaching.md", 0),
        # Non-facade namespace-qualified symbols: the internal substrate read
        # (`re-frame.realm/realm-ids`, `re-frame.realm/installed-app`) AND an
        # app's own `counter/install!` setup hook — all share a bare name with
        # a retired facade symbol but are different symbols. Must stay GREEN.
        ("negative/internal_substrate_ns_reads.md",    0),
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
        # (it does not start with an allowlist prefix), so positives fire.
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
    # were an EP doc, must NOT fire (the docs/EP/ allowlist exempts it).
    allow_fixture = "positive/live_install_realm_app.md"
    allow_path = _SELF_TEST_FIXTURE_ROOT / allow_fixture
    if allow_path.is_file():
        text = allow_path.read_text(encoding="utf-8", errors="replace")
        got = len(_scan_text(
            allow_path, text,
            rel_posix="docs/EP/EP-0013-app-values-and-runtime-realms.md",
        ))
        if got == 0:
            if verbose:
                sys.stderr.write(
                    "self-test PASS: allowlist exempts EP-doc retired example\n"
                )
        else:
            sys.stderr.write(
                "self-test FAIL: allowlist did NOT exempt EP-doc retired "
                f"example (got {got} findings)\n"
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
