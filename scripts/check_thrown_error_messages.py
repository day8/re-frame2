#!/usr/bin/env python3
"""rf2-vvixub thrown-error HUMAN-MESSAGE contract — corpus-driven CI gate.

Spec 009 §The thrown-error shape rules the framework thrown-error message
contract (rf2-vvixub):

  1. `(ex-message e)` is a human-actionable one-line sentence (the public
     concept + the expected fix + key context). It is NOT the bare
     stringified `:rf.error/<id>` discriminator keyword.
  2. `:rf.error/id` (ex-data) is the SOLE canonical machine discriminator;
     tools and tests branch on it, never on the message.
  3. The message is stable in MEANING, not bytes.
  4. The message carries a trailing `[:rf.error/<id>]` greppability token.

`re-frame.error/throw-error!` / `re-frame.error/thrown-ex-info` are the
canonical builders: they DERIVE the message from `:reason` + the token, so a
keyword-only message is impossible to emit. The per-feature artefacts route
their throws through them (directly, or via thin helpers like
`flows.registry/flow-error`, `routing.registry/route-error`).

WHY THIS GATE EXISTS (the rf2-6bb3pg finding)

The original conformance test
(`implementation/core/test/re_frame/thrown_error_message_conformance_cljs_test.cljc`)
is a CURATED allow-list: it `:require`s a handful of namespaces and exercises
those sites directly. A NEW (or never-converted) `(ex-info ":rf.error/…" …)`
site is invisible to it. rf2-6bb3pg found 50+ such sites still shipping the
bare keyword as `ex-message` — the exact regression rf2-vvixub abolished —
that CI could not see. This gate replaces the allow-list with a CORPUS SWEEP
that fails on ANY framework `(ex-info …)` whose MESSAGE position is a bare
`:rf.*` discriminator keyword, so the rollout cannot silently regress.

It mirrors the source-scanning drift guards already in this tree
(`check_retired_spellings.py` / `check_ambient_durable_reads.py` /
the EP-0011 reply-vocab + EP-0015 privacy-vocab projection guards): a
comment/string-masked scan over framework source, a `--self-test` fixture
mode that proves the gate FIRES on each bad shape and stays GREEN on the
conformant counterparts, and a fix hint.

THE FLAGGED SHAPE (the bare-keyword MESSAGE — `ex-info`'s FIRST argument)

The contract violation is entirely about the FIRST argument of `ex-info`
(the message). A conformant site passes a human sentence there — usually
`(human-message …)` / `(error/human-message …)`, or a builder
(`thrown-ex-info` / `throw-error!` / `flow-error` / `route-error` / …) that
derives it. A NON-conformant site passes a bare error keyword:

  (a) a `:rf.*` keyword STRING LITERAL:        (ex-info ":rf.error/foo" …)
  (b) `(str <literal :rf.* keyword>)`:         (ex-info (str :rf.error/foo) …)
  (c) `(str <error-keyword-named var>)`:        (ex-info (str error-kw) …)
                                                (ex-info (str error-id) …)
  (d) `(str (:rf.error/id <map>))`:             (ex-info (str (:rf.error/id payload)) …)

All four render `ex-message` as exactly the stringified discriminator
keyword — the OLD shape. Forms (c)/(d) are the per-surface helper clones
(`registration-error`, `validation-error`, `raise-removed-reg-event!`, the
frame.cljc payload throws) that centralised the regression rather than
fixing it; they still ship the bare keyword and are equally caught.

We detect on the `ex-info` FIRST ARGUMENT only, so a `:rf.error/foo` keyword
appearing as a `:reason` value, an ex-data slot, a trace-emit arg, or inside
a docstring/comment never fires — those are sanctioned.

THE WIDENED RULE (the builder-BYPASS — rf2-krrv87)

The four patterns above catch a message that is LITERALLY the discriminator
keyword. But a site can ALSO bypass the canonical builder while putting
*some* human-ish text in the message — yet still skip the `[:rf.<ns>/…]`
greppability token (rule 4) and the central `human-message` derivation. The
error model is uniform behind `error/throw-error!` / `thrown-ex-info` /
`ex-info-from-data`; a raw `(ex-info …)` whose ex-data carries `:rf.error/id`
is a hand-rolled error that did NOT route through the builder, and a future
one would re-introduce exactly the drift those builders abolish.

  (e) builder bypass:   (throw (ex-info (str "raw " x) {… :rf.error/id …}))
                        (throw (ex-info "plain prose"  {… :rf.error/id …}))

The widened rule (`builder-bypass-message`) fires on ANY raw `(ex-info <msg>
<data>)` where `<data>` carries `:rf.error/id` AND `<msg>` is NOT conformant.
A CONFORMANT message (does not fire) is one of:

  - a `(human-message …)` / `(<ns>/human-message …)` call (the central
    derivation the builders use); or
  - a message that ALREADY embeds the `[:rf.<ns>/…]` token — a contiguous
    literal `"… [:rf.<ns>/…]"` OR the token ASSEMBLED across a `(str …)`
    concatenation `(str "… [" :rf.<ns>/<id> "]")` — the SANCTIONED
    bundle-isolated reagent-slim shape (it cannot `:require re-frame.error`,
    so it hand-rolls the human sentence + token inline).

A DELIBERATE exemption — a message pinned to bare prose downstream (the
conformance-DSL `:throw` / `:count` ops re-emit it as `:exception-message`,
and the conformance corpus pins the exact text) — opts out with a
`rf2:builder-bypass-ok` marker comment on the `ex-info` line or in the small
comment block directly above it. A new ACCIDENTAL bypass has no marker and
fails. The four bare-keyword patterns are a strict subset of this rule (their
message is a bare keyword, which is neither a `human-message` call nor a
token-bearing literal); a site matching both is reported once under its more
specific bare-keyword kind.

SCAN SURFACE

Framework source under `implementation/` — `.clj` / `.cljc` / `.cljs`. The
default excludes `test/` trees (a test that asserts the old shape is gone, or
constructs a `(ex-info ":rf.error/…")` to exercise a predicate, legitimately
names it). `tools/` is bundle-isolated dev tooling and `examples/` is
consumer-shaped, so neither is framework source for this gate. The one
framework artefact that CANNOT `:require` `re-frame.error` (the
bundle-isolated `reagent-slim` adapter) hand-rolls a human sentence inline —
so it too must clear the gate, and it is under `implementation/`.

Exit code:
    0  no bare-keyword thrown-error message in framework source
    1  at least one (results printed file:line)
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

DEFAULT_SCAN_DIR = "implementation"

_SOURCE_SUFFIXES = (".clj", ".cljc", ".cljs")

_EXCLUDE_DIR_NAMES = frozenset({
    "node_modules",
    "target",
    "out",
    ".shadow-cljs",
    ".git",
    ".beads",
    ".cpcache",
})

# `test` / `tests` dirs are excluded by default (see module docstring): a test
# may legitimately construct a bare-keyword ex-info to exercise a predicate, or
# assert the old shape is gone. `--include-tests` lifts the exclusion (the
# self-test fixtures rely on this — their files live under fixture dirs).
_TEST_DIR_NAMES = frozenset({"test", "tests"})


# --------------------------------------------------------------------------
# The bare-keyword MESSAGE patterns (ex-info's FIRST argument)
# --------------------------------------------------------------------------
#
# Every pattern anchors on `(ex-info` + optional whitespace + the bad first
# argument. The trailing whitespace/`)` after the keyword token ensures we
# matched the WHOLE message argument, not a longer expression that merely
# starts with the keyword (e.g. `(ex-info (str :rf.error/x " context") …)` —
# a `str` that CONCATENATES the keyword with human text is NOT a bare-keyword
# message and is intentionally not flagged).
#
# The `:rf\.[a-z]` namespace match covers every reserved discriminator root
# (`:rf.error/…`, `:rf.ssr/…`, …) without firing on app keywords.

# (a) A bare `:rf.*` keyword STRING LITERAL as the message.
#     (ex-info ":rf.error/foo" …)   — the quotes are part of the match so a
#     `:rf.error/foo` used as a non-message arg never fires here.
_LITERAL_STRING_RE = re.compile(
    r"""\(\s*ex-info\s+"(:rf\.[a-z][\w.-]*/[\w.*+!?<>=-]+)"\s*"""
)

# (b) (str <literal :rf.* keyword>) as the message.
#     (ex-info (str :rf.error/foo) …)
_STR_LITERAL_KW_RE = re.compile(
    r"\(\s*ex-info\s+\(\s*str\s+(:rf\.[a-z][\w.-]*/[\w.*+!?<>=-]+)\s*\)"
)

# (c) (str <error-keyword-named var>) as the message. The var name is the
#     load-bearing signal it holds a discriminator keyword: the per-surface
#     helper clones bind the keyword as `error-kw` / `error-id` /
#     `error-keyword`. Scoped to those exact names so a `(str some-human-var)`
#     never fires.
_STR_ERR_VAR_RE = re.compile(
    r"\(\s*ex-info\s+\(\s*str\s+(error-kw|error-id|error-keyword|err-kw|err-id)\s*\)"
)

# (d) (str (:rf.error/id <map>)) as the message — pull the discriminator off a
#     payload map and stringify it. The frame.cljc payload throws use this.
_STR_ID_OF_MAP_RE = re.compile(
    r"\(\s*ex-info\s+\(\s*str\s+\(\s*:rf\.error/id\s+[\w.*+!?<>=/-]+\s*\)\s*\)"
)

_PATTERNS = (
    ("literal-keyword-string", _LITERAL_STRING_RE),
    ("str-literal-keyword", _STR_LITERAL_KW_RE),
    ("str-error-keyword-var", _STR_ERR_VAR_RE),
    ("str-id-of-payload", _STR_ID_OF_MAP_RE),
)


# --------------------------------------------------------------------------
# (e) The builder-BYPASS shape — a `(throw (ex-info <msg> {… :rf.error/id …}))`
#     whose MESSAGE bypasses the canonical builder (rf2-krrv87 gate widen).
# --------------------------------------------------------------------------
#
# The four bare-keyword patterns above catch a message that is LITERALLY the
# stringified discriminator. But a site can ALSO bypass the builder while
# putting *some* human-ish text in the message position — yet still skip the
# `[:rf.<ns>/<id>]` greppability token (rule 4) and the central
# `human-message` derivation. The canonical error model is uniform behind
# `error/throw-error!` / `error/thrown-ex-info` / `error/ex-info-from-data`;
# a raw `(ex-info …)` whose ex-data carries `:rf.error/id` is a hand-rolled
# error that did NOT route through the builder, and a future one would
# re-introduce exactly the drift those builders abolish.
#
# So: flag any raw `(ex-info <msg> <data>)` where
#   - the data argument carries `:rf.error/id` (it IS a framework error), AND
#   - the message is NOT conformant.
#
# A CONFORMANT message (does NOT fire) is one of:
#   - a `(human-message …)` / `(<ns>/human-message …)` call — the central
#     derivation (the builders use this under the hood; a few shared-payload
#     sites call it directly);
#   - a STRING LITERAL that already contains the bracketed `[:rf.<ns>/…]`
#     token — the SANCTIONED bundle-isolated reagent-slim shape, which cannot
#     `:require re-frame.error` and hand-rolls the human sentence + token
#     inline (module docstring §SCAN SURFACE).
#
# Everything else with `:rf.error/id` in ex-data is a builder bypass:
# a `(str …)` that is not the token form, a plain string literal WITHOUT the
# token, a bare var, a non-human-message fn call. The four bare-keyword
# patterns are a strict subset (their message IS a bare keyword, which is
# neither a human-message call nor a token-bearing literal) — the de-dup in
# `_scan_text` keeps a site reported once under its most specific kind.
#
# NOTE: the canonical builders (`thrown-ex-info` / `ex-info-from-data`) call
# `(ex-info (human-message …) …)` INTERNALLY in `re-frame.error`. Those are
# conformant by construction (the message IS a `human-message` call), so the
# rule does not fire on the builder definitions themselves.

# A `:rf.error/id` key appearing as a MAP KEY in the ex-data. (`:rf.error/id`
# is the canonical discriminator slot; its presence marks the form a framework
# error regardless of how the value is supplied.)
_HAS_ERROR_ID_RE = re.compile(r":rf\.error/id\b")

# A conformant message: a `(… human-message …)` call (any namespace alias) as
# the FIRST `ex-info` argument.
_HUMAN_MESSAGE_MSG_RE = re.compile(r"^\(\s*(?:[\w.*+!?<>=-]+/)?human-message\b")

# A conformant message: the message form ALREADY embeds the bracketed
# `[:rf.<ns>/…]` greppability token (rule 4) — whether as a pure string literal
# or inside a `(str … "[:rf.<ns>/…]")` concatenation. This is the SANCTIONED
# bundle-isolated reagent-slim shape: it cannot `:require re-frame.error`, so it
# hand-rolls the human sentence + the token inline. A message carrying the
# literal token IS conformant to rule 4, so it does not fire.
_INLINE_TOKEN_MSG_RE = re.compile(r"\[:rf\.[a-z][\w.-]*/[\w.*+!?<>=-]+\]")

# The same token ASSEMBLED across a `(str …)` concatenation — the open/close
# brackets are string-literal pieces and the keyword sits between them, e.g.
# `(str "… [" :rf.error/<id> "]")`. The runtime message is `[…:rf.<ns>/…]`,
# so it is equally conformant to rule 4; statically the bracket is not
# contiguous with the keyword, so this matches the assembled form: a `[` then
# (across quotes/whitespace) a `:rf.<ns>/…` keyword then a `]`.
_ASSEMBLED_TOKEN_MSG_RE = re.compile(
    r'\[["\s]*:rf\.[a-z][\w.-]*/[\w.*+!?<>=-]+["\s]*\]'
)


def _split_first_arg(body: str) -> tuple[str, str]:
    """Split a balanced `ex-info` BODY (text after `ex-info` and its
    whitespace, up to but excluding the closing paren) into its FIRST argument
    (the message form) and the remainder (the ex-data + any extra).

    Returns `(first_arg, rest)` with both stripped. Whitespace/quote/bracket
    aware so a `(str …)` / map / nested form is taken as one unit. A `;`
    comment cannot appear (the caller passes comment-masked text)."""
    body = body.lstrip()
    if not body:
        return "", ""
    i = 0
    n = len(body)
    depth = 0
    in_string = False
    # The first arg ends at the first top-level whitespace (depth 0, not in a
    # string) AFTER at least one token char — OR at the end of the body.
    started = False
    while i < n:
        c = body[i]
        if in_string:
            if c == "\\" and i + 1 < n:
                i += 2
                continue
            if c == '"':
                in_string = False
            i += 1
            continue
        if c == '"':
            in_string = True
            started = True
            i += 1
            continue
        if c in "([{":
            depth += 1
            started = True
            i += 1
            continue
        if c in ")]}":
            depth -= 1
            i += 1
            continue
        if c.isspace():
            if started and depth == 0:
                break
            i += 1
            continue
        started = True
        i += 1
    return body[:i].strip(), body[i:].strip()


def _extract_ex_info_form(text: str, open_paren_idx: int) -> str | None:
    """Given the index of the `(` that opens an `(ex-info …)` form, return the
    BODY text between `ex-info` and the matching close paren (exclusive of the
    outer parens), or None if the form is unterminated. String/comment aware
    (caller passes comment-masked text; strings are tracked so a `)` inside a
    reason sentence does not close the form early)."""
    n = len(text)
    i = open_paren_idx
    depth = 0
    in_string = False
    start_body = None
    while i < n:
        c = text[i]
        if in_string:
            if c == "\\" and i + 1 < n:
                i += 2
                continue
            if c == '"':
                in_string = False
            i += 1
            continue
        if c == '"':
            in_string = True
            i += 1
            continue
        if c == "(":
            depth += 1
            if depth == 1:
                # Skip past `(ex-info` to the body start.
                m = re.match(r"\(\s*ex-info\b", text[i:])
                if not m:
                    return None
                start_body = i + m.end()  # index right after `ex-info`
            i += 1
            continue
        if c == ")":
            depth -= 1
            if depth == 0:
                if start_body is None:
                    return None
                return text[start_body:i]
            i += 1
            continue
        i += 1
    return None


_EX_INFO_OPEN_RE = re.compile(r"\(\s*ex-info\b")

# The inline opt-out marker for a DELIBERATE, sanctioned builder-bypass — a
# message that intentionally stays bare human prose WITHOUT the `[:rf.<ns>/…]`
# token. A handful of sites need this: the conformance-DSL `:throw` / `:count`
# ops surface a FIXTURE-supplied / builtin message downstream as the
# sub-exception trace's `:exception-message`, and the conformance corpus PINS
# that exact text — so the message must stay the prose, not carry the token
# (the canonical `:rf.error/id` still rides on ex-data for branching). A site
# carrying this marker on the `ex-info` line (or the line just above) is
# exempt; a NEW accidental bypass has no marker and fails the gate. The reagent-
# slim adapter does NOT need the marker — it embeds the token inline, so it is
# conformant under the token rule, not an exemption.
_BYPASS_OK_MARKER = "rf2:builder-bypass-ok"

# How many lines ABOVE the `ex-info` line the marker comment may sit (a
# multi-line rationale block precedes some exemptions). Small + bounded so the
# marker stays tied to its `ex-info`, never a distant unrelated comment.
_MARKER_LOOKBACK = 4


def _scan_builder_bypass(path: Path, masked: str, raw_lines: list[str]) -> list["Finding"]:
    """Find raw `(ex-info <msg> {… :rf.error/id …})` whose message bypasses the
    canonical builder (no `[:rf.<ns>/…]` token, not a `human-message` call).

    A DELIBERATE exemption opts out with a `rf2:builder-bypass-ok` comment on
    the `ex-info` line or the line directly above it."""
    findings: list[Finding] = []
    for m in _EX_INFO_OPEN_RE.finditer(masked):
        body = _extract_ex_info_form(masked, m.start())
        if body is None:
            continue
        first_arg, rest = _split_first_arg(body)
        # Only framework errors: the ex-data must carry the :rf.error/id slot.
        if not _HAS_ERROR_ID_RE.search(rest):
            continue
        # Conformant messages do not fire: a central `human-message` derivation,
        # or a message already embedding the `[:rf.<ns>/…]` greppability token
        # (the reagent-slim inline shape).
        if _HUMAN_MESSAGE_MSG_RE.match(first_arg):
            continue
        if _INLINE_TOKEN_MSG_RE.search(first_arg):
            continue
        if _ASSEMBLED_TOKEN_MSG_RE.search(first_arg):
            continue
        line_no = _line_of_offset(masked, m.start())
        # A deliberate, documented exemption opts out via the marker comment on
        # the `ex-info` line or within the small comment block directly above it
        # (we scan the RAW lines — the marker lives in a `;`-comment, which the
        # masked text blanks). The window covers a multi-line rationale comment.
        lo = max(0, line_no - 1 - _MARKER_LOOKBACK)
        marker_window = raw_lines[lo:line_no]  # the ex-info line + lookback above
        if any(_BYPASS_OK_MARKER in ln for ln in marker_window):
            continue
        snippet = raw_lines[line_no - 1].strip() if 0 <= line_no - 1 < len(raw_lines) else ""
        findings.append(Finding(path, line_no, "builder-bypass-message", snippet))
    return findings


class Finding(NamedTuple):
    path: Path
    line: int
    kind: str
    snippet: str


# --------------------------------------------------------------------------
# Clojure-comment masking
# --------------------------------------------------------------------------
#
# `(ex-info (str error-kw) …)` and `(ex-info ":rf.error/…")` appear in PROSE:
# the `re-frame.error` rationale comment literally quotes the retired
# `(ex-info (str error-kw) …)` shape, and docstrings reference
# `:rf.error/…` ex-infos. Those are documentation, not reintroduced code.
#
# We mask `;`-to-EOL comments (length-preserving). We DO NOT mask "..." string
# literals — pattern (a) MUST see the `":rf.error/…"` literal message string,
# which is itself a string literal. A `:rf.error/…` mention inside an unrelated
# docstring cannot match any pattern (none of the patterns match a `:rf.*`
# keyword that is not the immediate `ex-info` first argument), so leaving
# string contents visible is safe — and (a) requires it. Multi-line docstrings
# that happen to contain the literal text `(ex-info ":rf.error/…"` would be a
# false positive, but that exact byte sequence does not occur in any docstring
# (a docstring describing the OLD shape spells it `(ex-info (str error-kw) …)`,
# which is a `;;` comment in re-frame.error and is masked).

_LINE_COMMENT_RE = re.compile(r";.*$")


def _mask_comments(text: str) -> list[str]:
    """Return per-line content with `;`-to-EOL comments blanked.

    Length-preserving so 1-based line numbers and reported snippets line up
    with the source. A `;` inside a "..." string literal is NOT a comment, so
    we track string state to avoid blanking a `;` that lives in a string (rare,
    but e.g. a reason sentence with a semicolon). Backslash-escaped quotes do
    not toggle string state.
    """
    masked: list[str] = []
    in_string = False
    for raw in text.splitlines():
        out = []
        i = 0
        n = len(raw)
        while i < n:
            c = raw[i]
            if in_string:
                if c == "\\" and i + 1 < n:
                    out.append(raw[i:i + 2])
                    i += 2
                    continue
                if c == '"':
                    in_string = False
                out.append(c)
                i += 1
                continue
            if c == '"':
                in_string = True
                out.append(c)
                i += 1
                continue
            if c == ";":
                # Comment to EOL — blank length-preserving and stop the line.
                out.append(" " * (n - i))
                i = n
                continue
            out.append(c)
            i += 1
        masked.append("".join(out))
    return masked


# --------------------------------------------------------------------------
# File iteration
# --------------------------------------------------------------------------


def _iter_source_files(scan_root: Path, include_tests: bool) -> Iterable[Path]:
    """Yield framework source files under scan_root."""
    if scan_root.is_file():
        if scan_root.suffix in _SOURCE_SUFFIXES:
            yield scan_root
        return
    for path in sorted(scan_root.rglob("*")):
        if path.suffix not in _SOURCE_SUFFIXES:
            continue
        parts = set(path.relative_to(scan_root).parts)
        if parts & _EXCLUDE_DIR_NAMES:
            continue
        if not include_tests and (parts & _TEST_DIR_NAMES):
            continue
        yield path


# --------------------------------------------------------------------------
# Per-file scan
# --------------------------------------------------------------------------
#
# The four patterns can span lines (a builder call wrapped across newlines), so
# we run them over the WHOLE masked text and map each match back to its 1-based
# line number. `(ex-info\n  ":rf.error/…"` is a real shape (most converted /
# unconverted sites put the message on the line after `(ex-info`), so a
# line-local scan would miss them.


def _line_of_offset(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def _scan_text(path: Path, text: str) -> list[Finding]:
    masked = "\n".join(_mask_comments(text))
    raw_lines = text.splitlines()

    def raw_snippet(line_no: int) -> str:
        return raw_lines[line_no - 1].strip() if 0 <= line_no - 1 < len(raw_lines) else ""

    findings: list[Finding] = []
    for kind, pat in _PATTERNS:
        for m in pat.finditer(masked):
            line_no = _line_of_offset(masked, m.start())
            findings.append(Finding(path, line_no, kind, raw_snippet(line_no)))
    # The widened builder-BYPASS rule (rf2-krrv87): a raw `(ex-info …)` whose
    # ex-data carries `:rf.error/id` but whose message skips the builder/token.
    bypass = _scan_builder_bypass(path, masked, raw_lines)
    # A bare-keyword site (one of the four strict patterns) is ALSO a builder
    # bypass; report it once under the more specific bare-keyword kind. So drop
    # a bypass finding when the SAME line already has a more specific finding.
    bare_lines = {(f.path, f.line) for f in findings}
    findings.extend(f for f in bypass if (f.path, f.line) not in bare_lines)
    # De-dup (a single site cannot match two patterns, but guard anyway).
    seen = set()
    unique: list[Finding] = []
    for f in findings:
        key = (f.path, f.line, f.kind)
        if key not in seen:
            seen.add(key)
            unique.append(f)
    return sorted(unique, key=lambda f: (str(f.path), f.line))


def scan(scan_root: Path, include_tests: bool = False) -> list[Finding]:
    findings: list[Finding] = []
    for path in _iter_source_files(scan_root, include_tests):
        text = path.read_text(encoding="utf-8", errors="replace")
        findings.extend(_scan_text(path, text))
    return findings


# --------------------------------------------------------------------------
# Reporting
# --------------------------------------------------------------------------

_FIX_HINT = (
    "These sites bypass the canonical thrown-error builder (Spec 009 §The "
    "thrown-error shape) — either a BARE `:rf.error/…` keyword message (the OLD "
    "shape rf2-vvixub abolished) or a `builder-bypass-message`: a raw "
    "`(ex-info …)` whose ex-data carries `:rf.error/id` but whose message skips "
    "the builder + the `[:rf.<ns>/<id>]` token (rf2-krrv87). Route each through "
    "the canonical builder so the message LEADS with a human sentence and "
    "TRAILS with the `[:rf.<ns>/<id>]` token:\n"
    "  * an artefact that can `:require re-frame.error`:\n"
    "      (error/throw-error! :rf.error/<id> 'rf/<where> \"<human sentence>\" {:extra {...}})\n"
    "    or build-without-throwing via `error/thrown-ex-info`; a shared-payload\n"
    "    throw-and-emit site uses `error/ex-info-from-data`. A per-surface\n"
    "    helper (flow-error / route-error / registration-error / …) should\n"
    "    DELEGATE its message to `error/thrown-ex-info` rather than\n"
    "    `(ex-info (str error-id) …)`.\n"
    "  * the bundle-isolated reagent-slim adapter (cannot :require\n"
    "    re-frame.error): hand-roll the same shape inline —\n"
    "      (ex-info (str \"<human sentence> [\" :rf.error/<id> \"]\") {:rf.error/id … :reason … …})\n"
    "  * a message DELIBERATELY pinned to bare prose downstream (conformance-DSL\n"
    "    :throw / :count) opts out with a `rf2:builder-bypass-ok` marker comment.\n"
    "  The `:reason` sentence usually already exists in the ex-data; reuse it."
)


def _report(findings: list[Finding], repo_root: Path) -> None:
    sys.stderr.write(
        f"\n{len(findings)} bare-keyword thrown-error message(s) found in "
        "framework source (rf2-vvixub / Spec 009 §The thrown-error shape):\n\n"
    )
    for f in findings:
        try:
            rel = f.path.relative_to(repo_root)
        except ValueError:
            rel = f.path
        rel_str = str(rel).replace("\\", "/")
        sys.stderr.write(f"  {f.kind}: {rel_str}:{f.line}\n      {f.snippet}\n")
    sys.stderr.write(f"\nFix:\n  {_FIX_HINT}\n")


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "rf2-vvixub / rf2-krrv87: fail on a framework `(ex-info …)` whose "
            "message is a bare `:rf.*` discriminator keyword (the retired "
            "keyword-only shape) OR a builder bypass — a raw ex-info carrying "
            "`:rf.error/id` whose message skips the builder + the "
            "`[:rf.<ns>/<id>]` token."
        ),
    )
    parser.add_argument(
        "--repo-root",
        default=None,
        help="Path to the repo root. Defaults to the script's grandparent.",
    )
    parser.add_argument(
        "--scan-dir",
        default=None,
        help=(
            "Directory (relative to repo-root) to scan. Defaults to "
            f"'{DEFAULT_SCAN_DIR}'."
        ),
    )
    parser.add_argument(
        "--include-tests",
        action="store_true",
        help=(
            "Scan test/ trees too. Off by default — a test may construct a "
            "bare-keyword ex-info to exercise a predicate."
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
            "scripts/_test_fixtures/check_thrown_error_messages/ and exit."
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

    scan_root = repo_root / (args.scan_dir or DEFAULT_SCAN_DIR)
    if not scan_root.exists():
        sys.stderr.write(f"error: scan dir {scan_root} does not exist.\n")
        return 2

    if args.verbose:
        n = sum(1 for _ in _iter_source_files(scan_root, args.include_tests))
        rel = str(scan_root.relative_to(repo_root)).replace("\\", "/")
        sys.stderr.write(
            f"scanning {n} framework source file(s) under {rel} "
            f"(tests {'included' if args.include_tests else 'excluded'})...\n"
        )

    findings = scan(scan_root, include_tests=args.include_tests)
    if findings:
        _report(findings, repo_root)
        return 1
    if args.verbose:
        sys.stderr.write(
            "no bare-keyword thrown-error messages in framework source.\n"
        )
    return 0


# --------------------------------------------------------------------------
# Self-tests (fixture-driven) — prove the gate FIRES on each bad shape and
# stays GREEN on every conformant counterpart.
# --------------------------------------------------------------------------

_SELF_TEST_FIXTURE_ROOT = (
    Path(__file__).resolve().parent
    / "_test_fixtures"
    / "check_thrown_error_messages"
)


def _run_self_tests(verbose: bool = False) -> int:
    cases: list[tuple[str, int]] = [
        # (fixture-file relative to fixture-root, expected finding count)
        # --- positives: each bare-keyword message shape must FIRE ---
        ("positive/literal_keyword_string.cljc",     1),
        ("positive/literal_keyword_multiline.cljc",  1),
        ("positive/str_literal_keyword.cljc",        1),
        ("positive/str_error_kw_var.cljc",           1),
        ("positive/str_error_id_var.cljc",           1),
        ("positive/str_id_of_payload.cljc",          1),
        # --- positives for the WIDENED builder-bypass rule (rf2-krrv87) ---
        ("positive/bypass_str_concat_no_token.cljc",     1),
        ("positive/bypass_plain_string_no_token.cljc",   1),
        # --- negatives: every conformant counterpart must stay GREEN ---
        ("negative/human_message_builder.cljc",      0),
        ("negative/throw_error_bang.cljc",           0),
        ("negative/str_concat_human_text.cljc",      0),
        ("negative/keyword_as_reason_value.cljc",    0),
        ("negative/keyword_in_exdata.cljc",          0),
        ("negative/keyword_in_comment.cljc",         0),
        ("negative/keyword_in_docstring.cljc",       0),
        ("negative/inline_human_token_slim.cljc",    0),
        # --- negatives for the WIDENED builder-bypass rule (rf2-krrv87) ---
        ("negative/bypass_inline_token_str_concat.cljc", 0),
        ("negative/bypass_human_message_with_id.cljc",   0),
        ("negative/bypass_marker_exempt.cljc",           0),
        ("negative/bypass_no_error_id.cljc",             0),
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
        got = len(scan(path, include_tests=True))
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
