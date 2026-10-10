#!/usr/bin/env python3
"""EP-0010 (Causal World Inputs) §Validation/Conformance — ambient-durable-read gate.

EP-0010 makes the frame fold honest: a transition's DURABLE result must be a
function of prior frame-state plus explicit causal tokens, never of a host fact
read ambiently at the durable write site. The accepted spec's
§Validation/Conformance (step 9 of the EP errata ledger) asks a conforming impl
to ship a STATIC LINT that flags direct ambient reads in code paths that can
write durable frame-state:

  - clock:   `interop/now-ms`, `interop/epoch-now-ms` (and their canonical
             dotted spellings `rf.interop/now-ms` / `rf.interop/epoch-now-ms`),
             `js/Date.now`, `(.now js/Date)`;
  - random:  `rand`, `rand-int`, `rand-nth`, `random-uuid`,
             `js/crypto.getRandomValues`;
  - browser: `js/location`, `navigator`, `localStorage`, `sessionStorage`,
             media-query (`matchMedia`) — each in BOTH the bare-symbol
             spelling and the getter / property / callable spelling that is
             how the fact is actually read: `(.getItem js/localStorage …)`,
             `(.-href js/location)`, `(.-language js/navigator)`,
             `(js/matchMedia …)`, and this repo's own authoring idiom
             `(some-> (.-localStorage js/globalThis) (.getItem …))`.

The durable-write code paths the spec enumerates are: resource reducers,
work-ledger writers, reply handlers, mutation handlers, restore/hydration
installers, and machine snapshot writers.

The spec says the lint MAY be conservative and SHOULD allowlist:

  - trace and performance-measurement code;
  - effect interpreters BEFORE they dispatch a reply token (the causal
    boundary read);
  - timer scheduling and cancellation;
  - host-transient side-table maintenance;
  - diagnostics that do not influence durable writes;
  - and (Open-Issue 3 rider c) effect-side crypto — session tokens / keys /
    nonces are excluded from recordable world inputs entirely, so an
    effect-interpreter `getRandomValues` is sanctioned, not a violation.


WHY A BARE-SYMBOL GREP IS WRONG (the "conservative" caveat)

`interop/now-ms` alone fires on 100+ legitimate framework sites — trace `:time`
stamps, `(when interop/debug-enabled? (interop/now-ms))` perf probes, timer
deadlines, freshness DECISION reads (which read the live clock to DECIDE
staleness without WRITING it durably), and the sanctioned
transport-boundary `:completed-at` causal read. A gate that fired on all of
those would be noise and would be turned off. So this gate, like
`check_retired_spellings.py`, scopes to the exact violating SHAPE rather than
the bare symbol:

  A DURABLE-STATE FIELD KEY whose VALUE is a direct ambient read, INSIDE a
  durable-write namespace.

i.e. the map-entry shape the EP's own examples describe — "a resource reply
handler calls `now-ms` while writing `:loaded-at`", "a work-ledger writer calls
`now-ms` while writing `:started-at`" (EP-0010 §The Boundary Is Already Visible):

    {:loaded-at  (interop/now-ms)        ;; FLAGGED
     :started-at (.now js/Date)          ;; FLAGGED
     :entry-id   (random-uuid)}          ;; (if a durable id key) FLAGGED

The durable field-key set is the spec's own enumerated durable timestamps
(`:started-at`, `:deadline-at`, `:loaded-at`, `:stale-at`, `:invalidated-at`,
`:settled-at`) plus the adjacent durable-write timestamps the same namespaces
mint (`:created-at`, `:completed-at`, `:errored-at`, `:restored-at`,
`:installed-at`, `:registered-at`, `:updated-at`, `:detected-at`) and the
durable-id keys (`:id`, `:entry-id`, `:request-id`, `:instance-id`,
`:mutation-id`, `:temp-id`, `:correlation-id`). The CORRECT replacement is to
thread the value from the reply/dispatch token's flat `:rf.cofx` recordable-
coeffect map — `(:rf/time-ms (:rf.cofx envelope))` for durable wall-clock time,
or a supplied uuid/random recordable coeffect declared via `:rf.cofx/requires`
— never to read the host here. (There is no `:rf.world/inputs` envelope and no
alias for one: the flat `:rf.cofx` map carries the recordable coeffects; see
spec/002-Frames.md §Recordable coeffects.)


WHY SCOPE BY NAMESPACE TOO (defence against false positives)

The shape alone is precise, but two layers of scoping keep the gate quiet on
the sanctioned sites the spec calls out:

  1. NAMESPACE allowlist. Trace, diagnostics, timer, transport/effect-
     interpreter-boundary, and host-transient side-table namespaces are NOT in
     the durable-write set, so a `:detected-at (interop/now-ms)` inside
     `(trace/emit! ...)` (router/diagnostics) or the `:completed-at
     (interop/epoch-now-ms)` transport-boundary causal read (http_transport —
     the read that FEEDS the reply token's `:rf.cofx`, sanctioned by EP-0010
     step 4) is
     never scanned. The frame-container `:created-at` lifecycle stamp
     (frame.cljc) is a host-transient frame-instance side table, also out of
     scope.

  2. FORM allowlist within a durable-write file. Even inside a scanned
     namespace, a field-key←ambient-read pair is exempted when its enclosing
     window names a sanctioned STRUCTURAL wrapper: `trace/emit!` (the read is a
     trace payload, not a durable frame-state write), `getRandomValues`
     (effect-side crypto, rider c), or an explicit `#_:rf.world/ambient-ok`
     reader-discard escape (the conscious-allowlist marker for a deliberate
     diagnostic read in a durable-write file — documented so a future author can
     opt out with a reviewed annotation rather than silently). There is no
     `interop/debug-enabled?` perf-probe window allowlist: a debug probe being
     NEAR a durable write is not a structural guarantee the write itself is
     diagnostic, so such a window would let a real durable
     `:updated-at (now-ms)` slip past CI. A genuine diagnostic read in a
     durable-write file uses the explicit per-site `#_:rf.world/ambient-ok`
     escape, not ambient proximity to a debug flag.

The gate is line-local on the field-key match but consults a small +/-6-line
window for the form-allowlist wrappers (mirrors the SSR-redirect window in
`check_retired_spellings.py`). It does NOT do dataflow: a value bound to a name
far from the durable key (`(let [t (interop/now-ms)] {:loaded-at t})`) is the
documented ambiguity limit — the conformance replay fixtures (EP-0010 §the
strongest property: equal durable projections after replay) are the runtime
backstop there.


SCAN SURFACE

The durable-write namespaces under `implementation/`, by path. The set is an
explicit allow-list of file-path suffixes (below) rather than a directory tree,
so adding a durable-write namespace is a conscious one-line edit here. `.clj` /
`.cljc` / `.cljs`. `test/` trees are excluded by default (a fixture
deliberately exercising the violating shape is correct, not drift);
`--include-tests` lifts that for the self-test fixtures.

The gate scans SOURCE trees. Generated and vendored copies are out of scope —
a durable-write source file copied into a build cache (`out/`, `.shadow-cljs/`,
`node_modules/`, `target/`, `.cpcache/`) is not a durable-write namespace, it is
an artefact OF one, and the genuine violation is still flagged at its real
authored path. The sibling shared-walk checkers assume the same;
`_EXCLUDE_DIR_NAMES` below makes it explicit here rather than leaving it to
the `endswith` suffix match.

Exit code:
    0  no ambient durable read in any durable-write namespace
    1  at least one ambient durable read (results printed file:line)
    2  invocation / setup error

Dependency-light — Python stdlib only.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
import tempfile
from pathlib import Path
from typing import Iterable, NamedTuple

# --------------------------------------------------------------------------
# Scan surface — the durable-write namespaces (EP-0010 enumeration)
# --------------------------------------------------------------------------
#
# Path SUFFIXES (POSIX-slash, relative to repo root) of the namespaces whose
# code can write durable frame-state. This is the spec's enumerated set:
# resource reducers + reply, work-ledger writers, mutation handlers + reply +
# runtime, machine reply + snapshot writers, routing reply, and the SSR /
# resource restore-hydration installers.
#
# DELIBERATELY EXCLUDED (the namespace allowlist — see module docstring):
#   - re_frame/trace.cljc, router/diagnostics.cljc  -> trace / diagnostics
#   - re_frame/frame.cljc                           -> frame-instance host-transient side table
#   - resources/timers.cljc                         -> timer scheduling / cancellation
#   - http/http_transport*.cljc, resources/transport*  -> effect-interpreter
#       boundary (the causal `:completed-at` read that FEEDS the token's :rf.cofx)
#   - cofx.cljc, router.cljc :rf.cofx minting        -> the causal boundary itself
DURABLE_WRITE_SUFFIXES: tuple[str, ...] = (
    # resource reducers + reply handlers
    "implementation/resources/src/re_frame/resources/events.cljc",
    "implementation/resources/src/re_frame/resources/reply.cljc",
    "implementation/resources/src/re_frame/resources/state.cljc",
    "implementation/resources/src/re_frame/resources/registry.cljc",
    "implementation/resources/src/re_frame/resources/route.cljc",
    "implementation/resources/src/re_frame/resources/revalidate_listeners.cljc",
    # work-ledger writers
    "implementation/resources/src/re_frame/resources/work_ledger.cljc",
    # mutation handlers + runtime + reply
    "implementation/resources/src/re_frame/resources/mutation_events.cljc",
    "implementation/resources/src/re_frame/resources/mutation_runtime.cljc",
    "implementation/resources/src/re_frame/resources/mutation_registry.cljc",
    # core resource facade + core reply
    "implementation/core/src/re_frame/core_resources.cljc",
    "implementation/core/src/re_frame/reply.cljc",
    # http reply handler
    "implementation/http/src/re_frame/http/reply.cljc",
    # machine reply handler
    "implementation/machines/src/re_frame/machines/reply.cljc",
    # routing reply handler
    "implementation/routing/src/re_frame/routing/reply.cljc",
    # restore / hydration installers
    "implementation/ssr/src/re_frame/ssr/hydrate.cljc",
    "implementation/resources/src/re_frame/resources/ssr.cljc",
)

_SOURCE_SUFFIXES = (".clj", ".cljc", ".cljs")

# Directory names whose contents are generated or vendored, never authored
# durable-write source. Matches the roster the sibling shared-walk checkers
# carry. See the module docstring's SCAN SURFACE note for the scope it sets.
_EXCLUDE_DIR_NAMES = frozenset({
    "node_modules",
    "target",
    "out",
    ".shadow-cljs",
    ".git",
    ".beads",
    ".cpcache",
})

_TEST_DIR_NAMES = frozenset({"test", "tests"})


# --------------------------------------------------------------------------
# The violating shape: a DURABLE field key whose VALUE is an ambient read
# --------------------------------------------------------------------------
#
# Durable-state field keys the durable-write namespaces mint. The first group
# is the spec's enumerated durable timestamps (§Conformance fixtures); the
# second is the adjacent durable-write timestamps the same code paths write;
# the third is durable-id keys (a `(random-uuid)` minted INTO one is the
# random/UUID-into-durable-id violation EP-0010 §Randomness governs).
_DURABLE_TIMESTAMP_KEYS = (
    "started-at", "deadline-at", "loaded-at", "stale-at", "invalidated-at",
    "settled-at",
    # adjacent durable-write timestamps the same namespaces mint
    "created-at", "completed-at", "errored-at", "restored-at", "installed-at",
    "registered-at", "updated-at", "detected-at", "fetched-at", "cached-at",
    "expires-at", "refreshed-at",
)
_DURABLE_ID_KEYS = (
    "id", "entry-id", "request-id", "instance-id", "mutation-id", "temp-id",
    "correlation-id", "resource-id",
)

# The ambient READ forms (the value side). Each is the EXACT read shape
# EP-0010 §Validation enumerates. `\b` boundaries keep `now-ms` from matching
# `now-ms-foo` and `rand` from matching `random` / `rand-something`.
#
#   clock:  (interop/now-ms) (interop/epoch-now-ms) (rf.interop/now-ms)
#           (rf.interop/epoch-now-ms) (js/Date.now) (.now js/Date)
#   random: (rand) (rand-int ...) (rand-nth ...) (random-uuid)
#           js/crypto.getRandomValues  (.getRandomValues js/crypto)
#   browser: js/location  js/navigator  navigator.  js/localStorage
#            js/sessionStorage  (.matchMedia ...)  js/matchMedia
#            (.getItem js/localStorage ...)  (.getItem js/sessionStorage ...)
#            (.-prop js/location)  (.-prop js/navigator)  (js/matchMedia ...)
#            (some-> (.-localStorage js/globalThis) (.getItem ...))  + twin
# Each entry is NAMED so a finding can say which read form produced it. The
# names are the roster's identity.
#
# WHY THE CALL-WRAPPED BROWSER FORMS ARE ENUMERATED
#
# `_VIOLATION_RE` anchors the read at the START of the durable key's value, so
# only a read sitting IMMEDIATELY in value position matches. The clock and
# random families carry a roster entry per call spelling — `(.now js/Date …)`,
# `(.getRandomValues js/crypto …)`, `(.matchMedia …)` — and the browser host
# facts need the same. With only their bare-symbol spelling they would be
# reachable only as `:restored-at js/localStorage`, which nobody writes, while
# the getter form `:restored-at (.getItem js/localStorage "session")` — which is
# how the fact is actually read, and the same EP-0010 defect — would produce
# ZERO findings. A roster naming `localStorage` while missing `.getItem`
# advertises coverage it does not have, so the getter, property and callable
# spellings are enumerated here as their own entries, each ANCHORED at
# value-form start exactly like `.now js/Date`.
#
# ENUMERATED, deliberately — NOT "an ambient read anywhere inside the value
# form", which would fire on a legitimately threaded value that merely mentions
# a host symbol somewhere in its form. Each pattern below names a complete read
# whose result IS the host fact, so there is no threading it can mistake.
_AMBIENT_READ_FORMS: tuple[tuple[str, str], ...] = (
    # clock
    # BOTH alias spellings of `re-frame.interop`, and the bare symbol. An
    # artefact on the spec/Conventions.md §Require-alias dialect calls the
    # clock `(rf.interop/now-ms)`, and a pattern that knew only the bare leaf
    # `interop/` would be BLIND on it — fail-open, since this gate forbids a
    # shape.
    ("now-ms",       r"\(\s*(?:(?:rf\.)?interop/)?(?:epoch-)?now-ms\b[^)]*\)"),
    ("js/Date.now",  r"\(\s*js/Date\.now\b[^)]*\)"),
    (".now js/Date", r"\(\s*\.now\s+js/Date\b[^)]*\)"),
    # random
    ("rand",         r"\(\s*rand\b[^)]*\)"),
    ("rand-int",     r"\(\s*rand-int\b[^)]*\)"),
    ("rand-nth",     r"\(\s*rand-nth\b[^)]*\)"),
    ("random-uuid",  r"\(\s*random-uuid\b[^)]*\)"),
    ("js/crypto.getRandomValues",   r"js/crypto\.getRandomValues\b"),
    (".getRandomValues js/crypto",  r"\(\s*\.getRandomValues\s+js/crypto\b"),
    # browser / host facts — the BARE symbol standing in the value position
    ("js/location",       r"js/location\b"),
    ("js/navigator",      r"js/navigator\b"),
    ("navigator.",        r"navigator\.\w"),
    ("js/localStorage",   r"js/localStorage\b"),
    ("js/sessionStorage", r"js/sessionStorage\b"),
    (".matchMedia",       r"\(\s*\.matchMedia\b"),
    ("js/matchMedia",     r"js/matchMedia\b"),
    # browser / host facts — the CALL-WRAPPED spellings that actually read them.
    # A line matching one of these also matches the bare entry it wraps, so it
    # honestly witnesses BOTH — the same double attribution `(rand-nth …)` has
    # against `rand`.
    (".getItem js/localStorage",
     r"\(\s*\.getItem\s+js/localStorage\b"),
    (".getItem js/sessionStorage",
     r"\(\s*\.getItem\s+js/sessionStorage\b"),
    (".-prop js/location",   r"\(\s*\.-\w+\s+js/location\b"),
    (".-prop js/navigator",  r"\(\s*\.-\w+\s+js/navigator\b"),
    ("(js/matchMedia ...)",  r"\(\s*js/matchMedia\b"),
    # This repo's own authoring idiom for a storage read — as written in
    # implementation/core/src/re_frame/cofx.cljc and examples/core/todomvc/db.cljs
    # (both correctly OUTSIDE the scan surface: an ambient cofx supplier and an
    # example, neither a durable-write namespace). Anchored from `(some->` so it
    # matches whether the `(.getItem …)` step follows on the same line or the
    # next — the value is the storage read either way.
    ("some-> .-localStorage js/globalThis",
     r"\(\s*some->\s+\(\s*\.-localStorage\s+js/globalThis\b"),
    ("some-> .-sessionStorage js/globalThis",
     r"\(\s*some->\s+\(\s*\.-sessionStorage\s+js/globalThis\b"),
)
_AMBIENT_READ_ALT = "|".join(pattern for _name, pattern in _AMBIENT_READ_FORMS)

# The same patterns, individually compiled, used ONLY to attribute a finding
# (and only once one exists) — the hot path stays the single alternation above.
# ALL matching names are reported, not the first: `rand` matches `(rand-nth …)`
# and `(rand-int …)` too, so a fixture for either genuinely exercises two roster
# entries and saying otherwise would overstate what it proves.
_AMBIENT_READ_RES: tuple[tuple[str, re.Pattern[str]], ...] = tuple(
    (name, re.compile(pattern)) for name, pattern in _AMBIENT_READ_FORMS
)


def _read_forms_of(text: str) -> frozenset[str]:
    """Every roster read-form name that matches `text`."""
    return frozenset(name for name, rx in _AMBIENT_READ_RES if rx.search(text))

# A durable field-key immediately followed (same form, possibly across a line
# break within the window) by an ambient read. We match line-locally on
# `:KEY <ambient-read>` — the keyword in map-entry KEY position with the
# ambient read as its value. Whitespace-tolerant.
_ALL_DURABLE_KEYS = _DURABLE_TIMESTAMP_KEYS + _DURABLE_ID_KEYS
_DURABLE_KEY_ALT = "|".join(re.escape(k) for k in _ALL_DURABLE_KEYS)

_VIOLATION_RE = re.compile(
    r":(?P<key>" + _DURABLE_KEY_ALT + r")\b(?!/)\s+(?P<read>"
    + _AMBIENT_READ_ALT + r")"
)

# Same shape but allowing the ambient read on the NEXT line (the common
# multi-line map-entry shape). We detect the durable key at end-of-(masked)-form
# and an ambient read opening the following line. Handled in the window pass.
_DURABLE_KEY_TRAILING_RE = re.compile(
    r":(?P<key>" + _DURABLE_KEY_ALT + r")\b(?!/)\s*$"
)
_AMBIENT_READ_LEADING_RE = re.compile(
    r"^\s*(?P<read>" + _AMBIENT_READ_ALT + r")"
)


# --------------------------------------------------------------------------
# Form-level allowlist wrappers (within a scanned durable-write file)
# --------------------------------------------------------------------------
#
# Even inside a durable-write namespace, a field-key←ambient pair is EXEMPT when
# the enclosing +/-N-line window names a sanctioned STRUCTURAL wrapper:
#   - trace/emit!            -> the read is a trace/diagnostic payload (the
#       payload IS a trace event — structurally not a durable frame-state write)
#   - getRandomValues        -> effect-side crypto (rider c) — already handled
#       by NOT listing it as a durable-id violation when wrapped, but a
#       getRandomValues into a durable :token/:nonce key is exempt regardless
#   - #_:rf.world/ambient-ok -> the explicit conscious-allowlist reader-discard
#       escape for a reviewed deliberate diagnostic read in a durable-write file
#
# DELIBERATELY NOT a wrapper: `interop/debug-enabled?`. A debug-window
# allowlist would exempt ANY durable field←ambient pair merely because a
# `(when interop/debug-enabled? ...)` perf probe sat within +/-6 lines — so a
# REAL durable `:updated-at (interop/now-ms)` write would slip past CI just by
# being NEAR an unrelated debug probe. A debug perf probe is not a STRUCTURAL
# guarantee that the nearby durable write is itself diagnostic. Instead:
# (a) `trace/emit!` exempts a genuine trace payload structurally; (b) a
# deliberate diagnostic read in a durable-write file that is NOT inside a trace
# payload annotates the EXACT site with the reviewed `#_:rf.world/ambient-ok`
# reader-discard escape — an explicit per-site opt-out, not an ambient
# proximity heuristic. A perf-probe's own `(when interop/debug-enabled?
# (now-ms))` elapsed read writes no durable field key, so it never matches the
# violating shape and needs no window exemption.
_ALLOWLIST_WINDOW_RE = re.compile(
    r"trace/emit!|getRandomValues|#_:rf\.world/ambient-ok"
)
# A `(trace/emit! ... {... :detected-at (now-ms)})` payload map runs ~6 lines
# (the real router/diagnostics.cljc site spans 6), so the trace-payload window
# is generous. This widens ONLY the EXEMPTION reach inside a scanned durable-
# write file; the namespace allowlist (trace.cljc / diagnostics.cljc never
# scanned) is the primary defence, so a slightly-too-wide exemption window only
# ever risks a FALSE NEGATIVE on a contrived adjacency, never a false positive.
# The `#_:rf.world/ambient-ok` reader-discard escape is the precise per-site
# opt-out when an author wants a diagnostic read tighter than this heuristic.
_ALLOWLIST_WINDOW = 6


class Finding(NamedTuple):
    path: Path
    line: int
    kind: str
    snippet: str
    # WHICH durable key and WHICH ambient read form fired. The reported `kind`
    # is one constant for every finding, so without these a fixture could only
    # ever prove that SOMETHING matched, leaving individual keys and read forms
    # unexercised. Attribution costs nothing on the live path: it is read off
    # the match already made, and `_report` does not print it.
    key: str = ""
    reads: frozenset[str] = frozenset()


# --------------------------------------------------------------------------
# Clojure-comment / string masking (length-preserving) — same as the
# retired-spellings gate: the symbols appear extensively in docstrings + `;;`
# prose, which must never fire the gate.
# --------------------------------------------------------------------------

_LINE_COMMENT_RE = re.compile(r";.*$")


def _mask_strings(line: str, in_string: bool) -> tuple[str, bool]:
    """Replace "..."-string-literal contents with spaces (length-preserving)."""
    out: list[str] = []
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


def _mask_comment(line: str) -> str:
    """Blank a `;`-to-EOL line comment, length-preserving.

    NOTE: the `#_:rf.world/ambient-ok` escape is a reader DISCARD, not a `;`
    comment, so it survives masking and is visible to the allowlist-window
    regex. A `;; ... ambient-ok` prose mention would be masked away (correct —
    only the real reader-discard form opts out).
    """
    m = _LINE_COMMENT_RE.search(line)
    if not m:
        return line
    start = m.start()
    return line[:start] + (" " * (len(line) - start))


def _masked_lines(text: str) -> list[str]:
    """Per-line content with string-literals + `;` comments blanked.

    Length-preserving so 1-based line numbers + reported snippets line up.
    Carries the in-string flag across newlines for multi-line docstrings.
    """
    masked: list[str] = []
    in_string = False
    for raw in text.splitlines():
        line, in_string = _mask_strings(raw, in_string)
        line = _mask_comment(line)
        masked.append(line)
    return masked


# --------------------------------------------------------------------------
# File iteration — the explicit durable-write suffix allow-list
# --------------------------------------------------------------------------


def _is_durable_write_file(path: Path, repo_root: Path) -> bool:
    """True iff `path` is one of the enumerated durable-write namespaces."""
    try:
        rel = path.relative_to(repo_root).as_posix()
    except ValueError:
        rel = path.as_posix()
    return any(rel.endswith(suf) for suf in DURABLE_WRITE_SUFFIXES)


def _iter_durable_write_files(
    scan_root: Path, repo_root: Path, include_tests: bool
) -> Iterable[Path]:
    """Yield durable-write source files under scan_root.

    Direct-file mode (scan_root.is_file()) bypasses the suffix allow-list — the
    self-test fixtures ARE the durable-write surface for the purposes of the
    test, so a fixture file is scanned regardless of its path.

    PRUNED, not filtered-after. `_EXCLUDE_DIR_NAMES` is dropped from
    `os.walk`'s dirnames IN PLACE, so a built checkout never descends into
    `.shadow-cljs`, `node_modules`, `out` or `target`. This gate scans the
    WHOLE repo root, and walking every entry of a built tree to reach the
    allow-listed files would dominate its wall clock.

    The prune is also a scope decision, not a pure speed-up: the suffix
    allow-list is matched with `endswith`, which matches the tail of a nested
    path, so without it a durable-write source file COPIED into a build cache
    would be scanned. Dropping those is deliberate — see the module docstring.
    On the real tree it drops none of the allow-listed files.

    The collected matches go through ONE GLOBAL `sorted()`, giving the
    whole-subtree ordering `sorted(rglob("*"))` would rather than os.walk's
    directory-grouped order.
    """
    if scan_root.is_file():
        if scan_root.suffix in _SOURCE_SUFFIXES:
            yield scan_root
        return
    scan_prefix_len = len(scan_root.as_posix()) + 1
    matches: list[Path] = []
    for dirpath, dirnames, filenames in os.walk(scan_root):
        dirnames[:] = [d for d in dirnames if d not in _EXCLUDE_DIR_NAMES]
        for name in filenames:
            if os.path.splitext(name)[1] in _SOURCE_SUFFIXES:
                matches.append(Path(dirpath) / name)
    for path in sorted(matches):
        parts = set(path.as_posix()[scan_prefix_len:].split("/"))
        if parts & _EXCLUDE_DIR_NAMES:
            continue
        if not include_tests and (parts & _TEST_DIR_NAMES):
            continue
        if _is_durable_write_file(path, repo_root):
            yield path


# --------------------------------------------------------------------------
# Per-file scan
# --------------------------------------------------------------------------


def _window_allowlisted(masked: list[str], line_no: int) -> bool:
    """True iff the +/-N-line window around `line_no` names a sanctioned wrapper."""
    lo = max(0, line_no - 1 - _ALLOWLIST_WINDOW)
    hi = min(len(masked), line_no + _ALLOWLIST_WINDOW)
    window = "\n".join(masked[lo:hi])
    return bool(_ALLOWLIST_WINDOW_RE.search(window))


def _scan_text(path: Path, text: str) -> list[Finding]:
    """Return ambient-durable-read findings in `text` (already file-attributed).

    Pattern-matching runs over MASKED lines (strings + `;` comments blanked) so
    prose never fires; the reported snippet is the RAW source line.
    """
    findings: list[Finding] = []
    masked = _masked_lines(text)
    raw = text.splitlines()

    def raw_snippet(n: int) -> str:
        return raw[n - 1].strip() if 0 <= n - 1 < len(raw) else ""

    for line_no, line in enumerate(masked, start=1):
        key = ""
        read_text = ""
        # Same-line shape: `:loaded-at (interop/now-ms)`.
        m = _VIOLATION_RE.search(line)
        if m:
            key, read_text = m.group("key"), m.group("read")
        # Cross-line shape: durable key ends the line, ambient read opens next.
        elif line_no < len(masked):
            trailing = _DURABLE_KEY_TRAILING_RE.search(line)
            leading = (
                _AMBIENT_READ_LEADING_RE.search(masked[line_no])
                if trailing else None
            )
            if trailing and leading:
                key, read_text = trailing.group("key"), leading.group("read")
        if not key:
            continue
        if _window_allowlisted(masked, line_no):
            continue
        findings.append(
            Finding(
                path, line_no, "ambient-durable-read", raw_snippet(line_no),
                key=key, reads=_read_forms_of(read_text),
            )
        )
    return findings


def scan(
    scan_root: Path, repo_root: Path, include_tests: bool = False
) -> list[Finding]:
    """Scan durable-write namespaces under scan_root for ambient durable reads."""
    findings: list[Finding] = []
    for path in _iter_durable_write_files(scan_root, repo_root, include_tests):
        text = path.read_text(encoding="utf-8", errors="replace")
        findings.extend(_scan_text(path, text))
    return findings


# --------------------------------------------------------------------------
# Reporting
# --------------------------------------------------------------------------

_FIX_HINT = (
    "EP-0010 §The Boundary Is Already Visible: a transition that performs a "
    "DURABLE write must be deterministic w.r.t. the host clock / RNG. Read the "
    "value from the reply/dispatch token's flat `:rf.cofx` recordable-coeffect "
    "map — `(:rf/time-ms (:rf.cofx envelope))` for durable wall-clock time, or a "
    "supplied uuid/random recordable coeffect declared via `:rf.cofx/requires` "
    "— and thread it into the durable field — "
    "do NOT read `interop/now-ms` / `js/Date.now` / `random-uuid` etc. at the "
    "durable write site. If this read is genuinely diagnostic (does not "
    "influence a durable write) move it into trace/perf code, or annotate the "
    "exact site with the reviewed `#_:rf.world/ambient-ok` reader-discard escape."
)


def _report(findings: list[Finding], repo_root: Path) -> None:
    sys.stderr.write(
        f"\n{len(findings)} ambient durable-world read(s) found in durable-write "
        "namespaces (EP-0010 §Validation/Conformance):\n\n"
    )
    for f in findings:
        try:
            rel = f.path.relative_to(repo_root)
        except ValueError:
            rel = f.path
        sys.stderr.write(f"  {f.kind}: {rel}:{f.line}\n      {f.snippet}\n")
    sys.stderr.write(f"\nFix:\n  * {_FIX_HINT}\n")


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "EP-0010 §Validation/Conformance: fail on a direct ambient host "
            "read (clock / RNG / browser fact) written into a DURABLE frame-"
            "state field inside a durable-write namespace."
        ),
    )
    parser.add_argument(
        "--repo-root",
        default=None,
        help="Path to the repo root. Defaults to the script's grandparent.",
    )
    parser.add_argument(
        "--scan-path",
        default=None,
        help=(
            "Path (relative to repo-root) to scan. A directory is filtered to "
            "the durable-write suffix allow-list; a file is scanned directly. "
            "Defaults to the repo root (scans every durable-write namespace)."
        ),
    )
    parser.add_argument(
        "--include-tests",
        action="store_true",
        help="Scan test/ trees too (used by the self-test fixtures).",
    )
    parser.add_argument(
        "--verbose", "-v", action="store_true", help="Print progress to stderr."
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help=(
            "Run the bundled fixture-based self-tests in "
            "scripts/_test_fixtures/check_ambient_durable_reads/ and exit."
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

    scan_root = repo_root / args.scan_path if args.scan_path else repo_root
    if not scan_root.exists():
        sys.stderr.write(f"error: scan path {scan_root} does not exist.\n")
        return 2

    if args.verbose:
        n = sum(
            1 for _ in _iter_durable_write_files(
                scan_root, repo_root, args.include_tests
            )
        )
        sys.stderr.write(
            f"scanning {n} durable-write namespace file(s)...\n"
        )

    findings = scan(scan_root, repo_root, include_tests=args.include_tests)
    if findings:
        _report(findings, repo_root)
        return 1
    if args.verbose:
        sys.stderr.write(
            "no ambient durable-world reads in any durable-write namespace.\n"
        )
    return 0


# --------------------------------------------------------------------------
# Self-tests (fixture-driven) — prove the gate FLAGS each planted ambient
# durable read AND PASSES every sanctioned counterpart.
# --------------------------------------------------------------------------

_SELF_TEST_FIXTURE_ROOT = (
    Path(__file__).resolve().parent
    / "_test_fixtures"
    / "check_ambient_durable_reads"
)

# What each positive fixture must yield, as `key<-read-form` witnesses.
_POSITIVE_WITNESSES: dict[str, frozenset[str]] = {
    "positive/dotted_alias_now_ms_into_loaded_at.cljc":
        frozenset({"loaded-at<-now-ms"}),
    "positive/now_ms_multiline.cljc":
        frozenset({"stale-at<-now-ms"}),
    "positive/durable_write_near_debug_probe.cljc":
        frozenset({"updated-at<-now-ms"}),
    "positive/ambient_read_forms.cljc": frozenset({
        "instance-id<-now-ms",
        "instance-id<-js/Date.now",
        "instance-id<-.now js/Date",
        "instance-id<-rand",
        "instance-id<-random-uuid",
        "instance-id<-.getItem js/localStorage",
        "instance-id<-js/localStorage",
        "instance-id<-.-prop js/location",
        "instance-id<-js/location",
        "instance-id<-.matchMedia",
        "instance-id<-some-> .-localStorage js/globalThis",
    }),
}

_NEGATIVE_FIXTURES: tuple[str, ...] = (
    "negative/now_ms_in_docstring.cljc",         # prose is masked
    "negative/trace_diagnostic_timestamp.cljc",  # the trace/emit! wrapper
    "negative/effect_side_crypto_token.cljc",    # effect-side crypto (rider c)
    "negative/ambient_ok_escape.cljc",           # the #_:rf.world/ambient-ok escape
)


def _run_scope_self_test(verbose: bool = False) -> int:
    """The authored durable-write path is scanned; a copy in a build cache is not.

    Built in a temp tree because the real repo contains no cache-nested copy.
    """
    suffix = DURABLE_WRITE_SUFFIXES[0]
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp).resolve()
        authored = root / suffix
        for path in (authored, root / "implementation" / "node_modules" / suffix):
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(
                "(defn install [e] (assoc e :loaded-at (interop/now-ms)))\n",
                encoding="utf-8",
            )
        flagged = sorted({f.path.resolve() for f in scan(root, root)})

    if flagged != [authored]:
        sys.stderr.write(
            f"self-test FAIL: scope — expected exactly {authored}, got "
            f"{', '.join(str(p) for p in flagged) or '(none)'}\n"
        )
        return 1
    if verbose:
        sys.stderr.write("self-test PASS: scope\n")
    return 0


def _run_self_tests(verbose: bool = False) -> int:
    """Each fixture must yield EXACTLY its witnesses (none for a negative).

    Direct-file scan mode, so the suffix allow-list does not hide the fixtures.
    """
    failures = 0
    cases = list(_POSITIVE_WITNESSES.items())
    cases += [(fixture, frozenset()) for fixture in _NEGATIVE_FIXTURES]
    for fixture, expected in cases:
        path = _SELF_TEST_FIXTURE_ROOT / fixture
        if not path.is_file():
            sys.stderr.write(f"self-test FAIL: fixture {fixture!r} missing\n")
            failures += 1
            continue
        findings = scan(path, _SELF_TEST_FIXTURE_ROOT, include_tests=True)
        actual = frozenset(f"{f.key}<-{form}" for f in findings for form in f.reads)
        if actual != expected or (findings and not expected):
            failures += 1
            sys.stderr.write(
                f"self-test FAIL: {fixture}: expected {sorted(expected)}, got "
                f"{[(f.line, f.key, sorted(f.reads)) for f in findings]}\n"
            )
        elif verbose:
            sys.stderr.write(f"self-test PASS: {fixture}\n")

    failures += _run_scope_self_test(verbose=verbose)

    if failures:
        sys.stderr.write(f"\n{failures} self-test failure(s).\n")
        return 1
    if verbose:
        sys.stderr.write(f"all {len(cases)} self-test fixture(s) passed.\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
