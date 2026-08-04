#!/usr/bin/env python3
"""Retired EP-0026 image-key guardrail — the SCOPED retirement residue gate.

EP-0026 (rf2-dlvmpc) RETIRES five EP-0023 image source keys and the three
image-capability surfaces with FAIL-LOUD rejection. The public `rf/image` value
now accepts exactly `:id`, `:select-ns`, and `:registrations`; composition
resolves by IMAGE ORDER and reports shadows via
`(:rf.gen/shadows (rf/frame-generation f))`; framework standards are protected;
image-declared host capabilities are removed end-to-end.

This gate keeps that retirement done. It fails if a RETIRED spelling reappears as
LIVE, copy-pasteable API on the teaching/reference/source surface — outside the
historical EP record and outside removed-context discussion.

THE EXACT RETIRED SET (the load-bearing scope)

The gate fires on these EXACT tokens and NOTHING else:

    :include-ns          (superseded by :select-ns :include)
    :exclude-ns          (superseded by :select-ns :exclude)
    :replace-standard    (standards are protected; no public opt-in)
    :rf.image/requires   (image-declared host capability — removed)
    :rf.gen/requires     (the generation's capability set — removed)
    make-frame :capabilities   (the frame-supplied capability map — removed; a
                                LITERAL `:capabilities` key inside a `make-frame`
                                call)
    :replace             (declared-winner map — superseded by image order); only
                         the IMAGE-KEY shape `:replace ` / `:replace }` /
                         `:replace\n` — NOT `str/replace`, `string/replace`,
                         `replace-first`, or `clojure.string/replace`.

CRITICAL — the gate NEVER matches the bare word `capability` / `capabilities`.
The `:rf.capability/*` host-service vocabulary, the conformance capability ids,
the tool capability flags, and runtime profiles are UNRELATED to the
image-capability surface EP-0026 removed, and MUST stay green. The only
`:capabilities` shape that fires is a literal key INSIDE a `(... make-frame ...)`
call (the retired image-selection key) — a bare `:capabilities` elsewhere never
fires.

WHY POSITION DISAMBIGUATES (mirrors the sibling composition-vocab gate)

These keyword tokens appear in legitimate PROSE constantly: "the `:replace` key
was retired", "`:include-ns` is gone (use `:select-ns`)". The retirement
diagnostic strings and migration prose name the keys on purpose. So:

  * Inside a fenced code block (```...```), a retired key reads as live
    copy-pasteable API. THIS is the drift the gate fires on.
  * In prose (outside any fence), or in an inline `code span`, the token is a
    NAME being discussed, never scanned.
  * A removed-context marker on the line or its immediate neighbours
    (`retired`, `removed`, `no longer`, `superseded`, `RETIRED`, `:rf.error/`,
    `fails loud`, `gone`, `deleted`, …) SUPPRESSES the hit — a fenced example
    that shows the retired key as the thing-being-rejected is removed-context.

For CLOJURE SOURCE (`.clj` / `.cljs` / `.cljc`), only `;`-comment lines are
prose; every other line is live code, so a retired key in real code FIRES (with
the same removed-context suppression for the line + neighbours, so the retired-
key DIAGNOSTIC map in re-frame.image and the negative tests stay green).

ALLOWLIST (historical surface ONLY)

`docs/EP/**` and `EP-*.md` under spec/ ARE the retirement record — worked
examples of the retired surface are their subject. Nothing else is exempt.

Exit code: 0 = clean, 1 = at least one live retired spelling, 2 = setup error.
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

# Doc/teaching markdown surfaces.
DEFAULT_DOC_DIRS = ("docs/core", "docs/api", "docs/EP", "spec", "skills")
# Per-tool spec trees are a teaching surface too (discovered at runtime).
_TOOL_SPEC_GLOB = "tools/*/spec"

# Clojure SOURCE surfaces — a retired key in real code is the worst drift.
DEFAULT_SOURCE_DIRS = ("implementation", "tools", "examples", "skills")

_DOC_SUFFIXES = (".md",)
_CLJ_SUFFIXES = (".clj", ".cljs", ".cljc", ".cljd", ".edn")

_EXCLUDE_DIR_NAMES = frozenset({
    "node_modules", "target", ".shadow-cljs", ".git", ".beads",
    ".cpcache", "site",
})
# Generated mirror of spec/ under docs/ (CI rm -rf + cp -r); scan the tracked
# source only.
_EXCLUDE_REL_PREFIXES = ("docs/spec/", "docs/cljs/")

# --------------------------------------------------------------------------
# Allowlist — historical record ONLY
# --------------------------------------------------------------------------

_ALLOWLIST_PREFIXES = ("docs/EP/",)
_ALLOWLIST_BASENAME_RE = re.compile(r"(?:^|/)EP-\d+[^/]*\.md$")
# This gate file itself names every retired token in its own docstring/patterns;
# its sibling fixtures are deliberate positives/negatives. Never scan them.
_ALLOWLIST_SELF_RE = re.compile(
    r"(?:^|/)(?:check_retired_image_keys\.py"
    r"|_test_fixtures/check_retired_image_keys/)"
)


def _is_allowlisted(rel_posix: str) -> bool:
    if any(rel_posix.startswith(p) for p in _ALLOWLIST_PREFIXES):
        return True
    if _ALLOWLIST_BASENAME_RE.search(rel_posix):
        return True
    if _ALLOWLIST_SELF_RE.search(rel_posix):
        return True
    return False


# --------------------------------------------------------------------------
# The retired-key patterns (the EXACT scoped set)
# --------------------------------------------------------------------------
#
# A Clojure symbol char class so a retired keyword does not match a longer one.
_KWEND = r"(?![\w.+!?<>=*/-])"

# The distinctive image-namespaced keyword tokens — fire UNCONDITIONALLY (the
# `:rf.image/` / `:rf.gen/` qualifier + the `-standard` suffix make these
# image-only spellings with no legitimate non-image reuse).
_IMAGE_ONLY_RETIRED = (
    (":replace-standard", re.compile(r":replace-standard" + _KWEND)),
    (":rf.image/requires", re.compile(r":rf\.image/requires" + _KWEND)),
    (":rf.gen/requires", re.compile(r":rf\.gen/requires" + _KWEND)),
)

# `:include-ns` / `:exclude-ns` are reused as a describe-image TOOL ARG (a
# boolean flag toggling the returned `:registrations` — a tool capability flag
# the bead leaves UNTOUCHED), so they fire ONLY in image/make-frame proximity,
# where they are unambiguously the retired image SELECTION key.
_CONTEXT_RETIRED = (
    (":include-ns", re.compile(r":include-ns" + _KWEND)),
    (":exclude-ns", re.compile(r":exclude-ns" + _KWEND)),
)

# `:replace` is the awkward one: `str/replace` / `string/replace` /
# `clojure.string/replace` / `replace-first` / `:replace-standard` all share the
# stem. Match the IMAGE-KEY shape only: a bare `:replace` keyword (NOT followed
# by `-standard` or another symbol char), i.e. the map-key spelling. The leading
# negative-lookbehind keeps it off `str/replace` (a `/`-qualified symbol, no
# leading `:`).
_REPLACE_RE = re.compile(r":replace(?!-)" + _KWEND)

# `make-frame :capabilities` — a LITERAL `:capabilities` key, but ONLY when a
# `make-frame` call is on the SAME line or an immediate neighbour (the retired
# image-selection key). A bare `:capabilities` elsewhere (an app's own config,
# the :rf.capability vocab, a tool flag) NEVER fires.
_CAPABILITIES_KEY_RE = re.compile(r":capabilities" + _KWEND)
_MAKE_FRAME_RE = re.compile(r"make-frame")
# `:replace` is a common keyword (a diff op, an FSM event, …), so it fires ONLY
# in IMAGE/MAKE-FRAME proximity — a co-occurring `rf/image` / `image/image` /
# `make-frame` token in the window. That keeps the retired image-key `:replace`
# in scope while leaving every unrelated `:replace` keyword alone.
_IMAGE_CTX_RE = re.compile(r"\b(?:rf/image|image/image|make-frame|:images)\b")
# A `make-frame` / image opts map can span several lines, so the anchoring token
# may sit a few lines from the key. The co-occurrence window (lines on each side).
_MAKE_FRAME_WINDOW = 4
# The gate's ADVERTISED contract — the exact token set the module docstring
# promises, written out once so the self-test can hold the implementation to it.
# `--self-test` asserts BOTH that the detectors emit exactly this set and that
# every member has its own positive fixture, so a token can never again lose its
# detector (or gain one) unnoticed. See `_run_self_tests` (rf2-e1xx0).
_ADVERTISED_KINDS = frozenset({
    ":include-ns", ":exclude-ns", ":replace-standard",
    ":rf.image/requires", ":rf.gen/requires",
    ":replace", "make-frame :capabilities",
})
# Removed-context suppression window (lines each side). A retirement-discussing
# `deftest` name / `testing` string / comment can sit a few lines above the
# retired-key data line (e.g. a `doseq` over retired specs), so the window is
# slightly wider than a bare neighbour pair.
_REMOVED_CTX_WINDOW = 3


# --------------------------------------------------------------------------
# Removed-context suppression
# --------------------------------------------------------------------------
#
# A line (or its immediate neighbourhood) that DISCUSSES the retirement is not
# live API. Mirrors the sibling composition-vocab gate's tolerance. The
# `:rf.error/` marker keeps the re-frame.image diagnostic map + negative tests
# green (they NAME the retired key as the thing being rejected).
_REMOVED_CONTEXT_RE = re.compile(
    r"\b(?:retired|RETIRED|removed?|removal|deleted?|delete|gone|absent|"
    r"eliminated?|dropped?|no\s+longer|there\s+is\s+no|is\s+no\b|are\s+no\b|"
    r"\bno\b|collapsed?|superseded?|supersedes|deprecated?|renamed|formerly|"
    r"legacy|fails?\s+loud|fail-loud|invalid|reject(?:s|ed)?|migrat|"
    r"EP-0026|rf2-dlvmpc)"
    r"|:rf\.error/",
    re.IGNORECASE,
)


class Finding(NamedTuple):
    path: Path
    line: int
    kind: str
    snippet: str


# Every `Finding.kind` is this prefix + one member of `_ADVERTISED_KINDS`.
_KIND_PREFIX = "live-retired:"


def _implemented_kinds() -> frozenset[str]:
    """The retired kinds the detectors can actually emit, derived from the
    patterns themselves — the self-test holds this against the advertised set."""
    kinds = {kind for kind, _ in _IMAGE_ONLY_RETIRED}
    kinds |= {kind for kind, _ in _CONTEXT_RETIRED}
    if _REPLACE_RE.search(":replace "):
        kinds.add(":replace")
    if _CAPABILITIES_KEY_RE.search(":capabilities "):
        kinds.add("make-frame :capabilities")
    return frozenset(kinds)


# --------------------------------------------------------------------------
# Fenced-code + comment masking (markdown), comment masking (Clojure)
# --------------------------------------------------------------------------

_FENCE_RE = re.compile(r"^(\s*)(`{3,}|~{3,})")
_LINE_COMMENT_RE = re.compile(r";.*$")
_INLINE_CODE_SPAN_RE = re.compile(r"(`+)(?:.+?)\1")
# A double-quoted Clojure string literal (no escape handling needed for the
# token search — masking only ever REMOVES potential matches). Masked so a
# retired token NAMED INSIDE A DOCSTRING (the normalized `:rf.image/include-ns`
# slot prose, the glob-grammar docstrings) is not a false live hit.
_CLJ_STRING_RE = re.compile(r'"[^"]*"')


def _mask_clj_comment(line: str) -> str:
    m = _LINE_COMMENT_RE.search(line)
    if not m:
        return line
    start = m.start()
    return line[:start] + (" " * (len(line) - start))


def _mask_clj_strings(line: str) -> str:
    """Blank double-quoted string literals, length-preserving, so a retired
    token named inside a docstring is not a live-code hit."""
    return _CLJ_STRING_RE.sub(lambda m: " " * len(m.group(0)), line)


def _code_fence_lines(text: str) -> list[tuple[int, str]]:
    """(1-based-line-no, masked-content) for lines INSIDE markdown fenced code.

    Lines outside any fence are omitted (prose is never code-scanned). A
    `;`-comment inside a fence is masked so a removed-context comment cannot be
    a false live hit.
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
                in_fence, fence_char, fence_len = True, char, length
                continue
            if char == fence_char and length >= fence_len:
                in_fence, fence_char, fence_len = False, "", 0
                continue
        if in_fence:
            out.append((line_no, _mask_clj_comment(raw)))
    return out


def _mask_multiline_clj_strings(text: str) -> str:
    """Blank every double-quoted Clojure string literal across the WHOLE file,
    length-preserving (newlines kept), so a retired token named inside a
    MULTI-LINE docstring is not a live-code hit. A `"` opens a string; the next
    unescaped `"` closes it. Escapes (`\\"`) are honoured so an embedded quote
    does not close early."""
    out: list[str] = []
    in_str = False
    escaped = False
    for ch in text:
        if in_str:
            if escaped:
                escaped = False
                out.append(" " if ch != "\n" else "\n")
            elif ch == "\\":
                escaped = True
                out.append(" ")
            elif ch == '"':
                in_str = False
                out.append(" ")
            else:
                out.append(" " if ch != "\n" else "\n")
        else:
            if ch == '"':
                in_str = True
                out.append(" ")
            else:
                out.append(ch)
    return "".join(out)


def _clj_code_lines(text: str) -> list[tuple[int, str]]:
    """(1-based-line-no, masked-content) for every Clojure SOURCE line.

    String literals (incl. multi-line docstrings) are masked first, then
    `;`-comments per line (a comment naming the retired key as removed prose is
    not live code). Everything else is live code.
    """
    masked_strings = _mask_multiline_clj_strings(text)
    return [(i, _mask_clj_comment(raw))
            for i, raw in enumerate(masked_strings.splitlines(), start=1)]


# --------------------------------------------------------------------------
# Per-line retired-key detection
# --------------------------------------------------------------------------


def _retired_key_hits(line: str, neighbour: str) -> list[str]:
    """Return the retired-key kinds present in `line` as LIVE API.

    `neighbour` is the masked line plus its immediate masked neighbours — used
    ONLY for the `make-frame :capabilities` co-occurrence (the make-frame call
    may sit one line above/below the `:capabilities` key). Removed-context
    SUPPRESSION is applied by the caller against the RAW context window.
    """
    hits: list[str] = []
    for kind, rx in _IMAGE_ONLY_RETIRED:
        if rx.search(line):
            hits.append(kind)
    in_image_ctx = bool(_IMAGE_CTX_RE.search(neighbour))
    # `:include-ns` / `:exclude-ns` fire ONLY in image/make-frame proximity (they
    # double as a describe-image tool ARG elsewhere).
    for kind, rx in _CONTEXT_RETIRED:
        if rx.search(line) and in_image_ctx:
            hits.append(kind)
    # `:replace` fires ONLY in image/make-frame proximity (it is otherwise a
    # common keyword — diff ops, FSM events, … — unrelated to the image key).
    if _REPLACE_RE.search(line) and in_image_ctx:
        hits.append(":replace")
    # make-frame :capabilities — the literal key co-occurring with a make-frame
    # call within the window (the retired image-selection key). Bare
    # :capabilities elsewhere never fires.
    if _CAPABILITIES_KEY_RE.search(line) and _MAKE_FRAME_RE.search(neighbour):
        hits.append("make-frame :capabilities")
    return hits


def _scan_lines(lines: list[tuple[int, str]], path: Path,
                raw: list[str]) -> list[Finding]:
    # Live-hit detection runs over the MASKED line (comments blanked so a
    # comment token is never a false live hit). Removed-context SUPPRESSION,
    # however, reads the RAW line + its raw neighbours, so a `;; … no longer
    # accepted` marker (or a marker on an adjacent prose/comment line) is still
    # honoured even though it was masked out of the live-hit text.
    masked_by_no = {ln: s for ln, s in lines}
    findings: list[Finding] = []
    for line_no, masked in lines:
        raw_context = " ".join(
            raw[n - 1] if 0 <= n - 1 < len(raw) else ""
            for n in range(line_no - _REMOVED_CTX_WINDOW, line_no + _REMOVED_CTX_WINDOW + 1)
        )
        # A `make-frame` opts map spans several lines, so the make-frame token
        # may sit a few lines above the `:capabilities` key. Use a small window.
        masked_neighbour = " ".join(
            masked_by_no.get(n, "")
            for n in range(line_no - _MAKE_FRAME_WINDOW, line_no + _MAKE_FRAME_WINDOW + 1)
        )
        # Re-detect on the masked line but suppress via the raw context window.
        hits = []
        if not _REMOVED_CONTEXT_RE.search(raw_context):
            hits = _retired_key_hits(masked, masked_neighbour)
        for kind in hits:
            snippet = raw[line_no - 1].strip() if 0 <= line_no - 1 < len(raw) else ""
            findings.append(Finding(path, line_no, _KIND_PREFIX + kind, snippet))
    return findings


def _scan_text(path: Path, text: str, rel_posix: str) -> list[Finding]:
    if _is_allowlisted(rel_posix):
        return []
    raw = text.splitlines()
    if path.suffix in _DOC_SUFFIXES:
        lines = _code_fence_lines(text)
    elif path.suffix in _CLJ_SUFFIXES:
        lines = _clj_code_lines(text)
    else:
        return []
    return _scan_lines(lines, path, raw)


# --------------------------------------------------------------------------
# File iteration
# --------------------------------------------------------------------------


def _tool_spec_scan_dirs(repo_root: Path) -> list[str]:
    if not (repo_root / "tools").is_dir():
        return []
    out: list[str] = []
    for spec_dir in sorted(repo_root.glob(_TOOL_SPEC_GLOB)):
        if spec_dir.is_dir():
            out.append(spec_dir.relative_to(repo_root).as_posix())
    return out


def _iter_files(scan_root: Path, repo_root: Path,
                suffixes: tuple[str, ...]) -> Iterable[Path]:
    if scan_root.is_file():
        if scan_root.suffix in suffixes:
            yield scan_root
        return
    for path in sorted(scan_root.rglob("*")):
        if path.suffix not in suffixes:
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


def scan(repo_root: Path,
         doc_dirs: tuple[str, ...] = DEFAULT_DOC_DIRS,
         source_dirs: tuple[str, ...] = DEFAULT_SOURCE_DIRS,
         add_tool_specs: bool = True) -> list[Finding]:
    findings: list[Finding] = []
    seen: set[Path] = set()
    doc_scan = list(doc_dirs) + (_tool_spec_scan_dirs(repo_root) if add_tool_specs else [])
    for d in doc_scan:
        root = repo_root / d
        if not root.exists():
            continue
        for path in _iter_files(root, repo_root, _DOC_SUFFIXES):
            if path in seen:
                continue
            seen.add(path)
            rel = _rel(path, repo_root)
            findings.extend(_scan_text(path, _read(path), rel))
    for d in source_dirs:
        root = repo_root / d
        if not root.exists():
            continue
        for path in _iter_files(root, repo_root, _CLJ_SUFFIXES):
            if path in seen:
                continue
            seen.add(path)
            rel = _rel(path, repo_root)
            findings.extend(_scan_text(path, _read(path), rel))
    return findings


def _rel(path: Path, repo_root: Path) -> str:
    try:
        return path.relative_to(repo_root).as_posix()
    except ValueError:
        return path.as_posix()


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


# --------------------------------------------------------------------------
# Reporting
# --------------------------------------------------------------------------

_FIX_HINT = (
    "EP-0026 (rf2-dlvmpc) RETIRED these image keys/surfaces — they fail loud. "
    "Migrate the example/code:\n"
    "  :include-ns / :exclude-ns -> :select-ns {:include [globs] :exclude [globs]}\n"
    "  :replace                  -> compose a LATER image (image order wins); "
    "read (:rf.gen/shadows (rf/frame-generation f)) to assert on what it "
    "shadowed\n"
    "  :replace-standard         -> framework standards are protected; an app "
    "image cannot shadow one\n"
    "  :rf.image/requires / make-frame :capabilities / :rf.gen/requires -> "
    "image-declared host capabilities are removed end-to-end; model a host "
    "dependency through ordinary registration selection / frame config / adapter "
    "setup\n"
    "The ONLY surface that may carry the retired spellings is the historical "
    "docs/EP/** record (allowlisted). If a line DISCUSSES the retirement, add a "
    "removed-context marker (retired / removed / no longer / superseded / "
    ":rf.error/ …) and the gate treats it as prose. NOTE: this gate NEVER matches "
    "the bare word `capability` — the :rf.capability/* host-service vocabulary, "
    "conformance capability ids, and tool capability flags are untouched."
)


def _report(findings: list[Finding], repo_root: Path) -> None:
    sys.stderr.write(
        f"\n{len(findings)} live RETIRED EP-0026 image-key spelling(s) on the "
        "teaching/source surface:\n\n"
    )
    for f in findings:
        try:
            rel = f.path.relative_to(repo_root)
        except ValueError:
            rel = f.path
        sys.stderr.write(f"  {f.kind}: {rel}:{f.line}\n      {f.snippet}\n")
    sys.stderr.write("\nFix:\n  " + _FIX_HINT + "\n")


# --------------------------------------------------------------------------
# Self-tests
# --------------------------------------------------------------------------

_SELF_TEST_FIXTURE_ROOT = (
    Path(__file__).resolve().parent / "_test_fixtures" / "check_retired_image_keys"
)

# The EXACT kind set every POSITIVE fixture must yield — ONE retired token per
# fixture, declared here.
#
# WHY EXACT, AND WHY ONE TOKEN EACH (rf2-e1xx0). This assertion used to be "at
# least one finding" over multi-token fixtures, and that is fail-open: a fixture
# carrying `:replace` AND `make-frame :capabilities` still reports a finding when
# either detector dies, because its sibling covers for it. Measured on the old
# fixtures, killing a single detector left --self-test GREEN for SIX of the seven
# tokens — every one except `:include-ns`, which happened to own a fixture alone.
# One token per fixture plus an exact-set assertion is what makes a dead detector
# red BY NAME, which is the whole job of a guard's self-test.
_POSITIVE_EXPECTATIONS: dict[str, frozenset[str]] = {
    "positive/live_include_ns_fenced.md":
        frozenset({":include-ns"}),
    "positive/live_exclude_ns_fenced.md":
        frozenset({":exclude-ns"}),
    "positive/live_replace_standard_fenced.md":
        frozenset({":replace-standard"}),
    "positive/live_replace_key_fenced.md":
        frozenset({":replace"}),
    "positive/live_make_frame_capabilities_fenced.md":
        frozenset({"make-frame :capabilities"}),
    "positive/live_image_requires_source.cljc":
        frozenset({":rf.image/requires"}),
    "positive/live_gen_requires_source.cljc":
        frozenset({":rf.gen/requires"}),
}


def _run_self_tests(verbose: bool = False) -> int:
    """Scan each fixture and assert the EXACT set of retired kinds it yields.

    Positive fixtures plant exactly ONE live retired token (in a code fence or in
    live Clojure source) and declare it in `_POSITIVE_EXPECTATIONS`; negative
    fixtures plant the same tokens under removed-context, in prose, in an inline
    span, in a `str/replace` call, outside image proximity, or as the
    `:rf.capability` vocab — all GREEN.

    Three assertions, and each closes a different way for the gate to go quietly
    toothless:

      1. Per fixture, the kind set must match EXACTLY — a dead detector reds by
         name instead of being masked by a sibling finding in the same file.
      2. The detectors must implement EXACTLY the advertised token set, so the
         module docstring's promise is executable rather than aspirational.
      3. Every advertised token must OWN a positive fixture, so a token added
         without one cannot pass unexercised.
    """
    root = _SELF_TEST_FIXTURE_ROOT
    if not root.is_dir():
        sys.stderr.write(f"error: fixture root {root} missing.\n")
        return 2
    failures = 0
    checked = 0
    covered: set[str] = set()
    for path in sorted(root.rglob("*")):
        if path.suffix not in (_DOC_SUFFIXES + _CLJ_SUFFIXES):
            continue
        rel = path.relative_to(root).as_posix()
        # Fixtures are scanned with the SELF-allowlist bypassed (they live under
        # _test_fixtures, normally allowlisted) — pass the bare basename as the
        # rel so the allowlist's self-rule does not exempt them.
        findings = _scan_text(path, _read(path), path.name)
        actual = frozenset(f.kind.removeprefix(_KIND_PREFIX) for f in findings)
        checked += 1
        if rel.startswith("positive/"):
            expected = _POSITIVE_EXPECTATIONS.get(rel)
            if expected is None:
                failures += 1
                sys.stderr.write(
                    f"  SELF-TEST FAIL: {rel} is a positive fixture with no entry "
                    "in _POSITIVE_EXPECTATIONS — declare the EXACT kind(s) it must "
                    "yield (one retired token per fixture).\n"
                )
                continue
            covered |= expected
        elif rel.startswith("negative/"):
            expected = frozenset()
        else:
            failures += 1
            sys.stderr.write(
                f"  SELF-TEST FAIL: {rel} sits in neither positive/ nor negative/ "
                "— a fixture whose expectation cannot be read is not a test.\n"
            )
            continue
        if actual != expected:
            failures += 1
            sys.stderr.write(f"  SELF-TEST FAIL: {rel}\n")
            missing = sorted(expected - actual)
            extra = sorted(actual - expected)
            if missing:
                sys.stderr.write(
                    "      DETECTOR DEAD — this fixture plants "
                    f"{', '.join(missing)} and the gate did not see it\n"
                )
            if extra:
                sys.stderr.write(
                    f"      UNEXPECTED kind(s): {', '.join(extra)}\n"
                )
            for f in findings:
                sys.stderr.write(f"      {f.kind}: line {f.line}: {f.snippet}\n")
        elif verbose:
            sys.stderr.write(
                f"  ok: {rel} ({', '.join(sorted(actual)) or 'no findings'})\n"
            )

    implemented = _implemented_kinds()
    if implemented != _ADVERTISED_KINDS:
        failures += 1
        sys.stderr.write(
            "  SELF-TEST FAIL: the detectors no longer implement the advertised "
            "retired set.\n"
            f"      absent from the detectors: {sorted(_ADVERTISED_KINDS - implemented)}\n"
            f"      not advertised:            {sorted(implemented - _ADVERTISED_KINDS)}\n"
        )
    uncovered = sorted(_ADVERTISED_KINDS - covered)
    if uncovered:
        failures += 1
        sys.stderr.write(
            "  SELF-TEST FAIL: advertised retired token(s) with no positive "
            f"fixture of their own: {', '.join(uncovered)}\n"
            "      Add one fixture per token and declare it in "
            "_POSITIVE_EXPECTATIONS.\n"
        )

    if failures:
        sys.stderr.write(
            f"\n{failures} self-test failure(s) across {checked} fixture(s).\n"
        )
        return 1
    if verbose:
        sys.stderr.write(
            f"\nall {checked} self-test fixture(s) passed; all "
            f"{len(_ADVERTISED_KINDS)} advertised retired token(s) covered.\n"
        )
    return 0


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "EP-0026 retired-image-key gate: fail on a live retired image "
            "key/surface (:include-ns/:exclude-ns/:replace/:replace-standard/"
            ":rf.image/requires/:rf.gen/requires/make-frame :capabilities) on the "
            "teaching or source surface, scoped to the EXACT tokens (never the "
            "bare word 'capability')."
        ),
    )
    parser.add_argument("--repo-root", default=None)
    parser.add_argument("--verbose", "-v", action="store_true")
    parser.add_argument("--self-test", action="store_true")
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
        sys.stderr.write("scanning doc + Clojure source surfaces for retired "
                         "EP-0026 image keys...\n")
    findings = scan(repo_root)
    if findings:
        _report(findings, repo_root)
        return 1
    if args.verbose:
        sys.stderr.write("no live retired EP-0026 image keys on the surface.\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
