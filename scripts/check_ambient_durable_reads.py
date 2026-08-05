#!/usr/bin/env python3
"""EP-0010 (Causal World Inputs) §Validation/Conformance — ambient-durable-read gate.

EP-0010 makes the frame fold honest: a transition's DURABLE result must be a
function of prior frame-state plus explicit causal tokens, never of a host fact
read ambiently at the durable write site. The accepted spec's
§Validation/Conformance (step 9 of the EP errata ledger) asks a conforming impl
to ship a STATIC LINT that flags direct ambient reads in code paths that can
write durable frame-state:

  - clock:   `interop/now-ms`, `interop/epoch-now-ms`, `js/Date.now`,
             `(.now js/Date)`;
  - random:  `rand`, `rand-int`, `rand-nth`, `random-uuid`,
             `js/crypto.getRandomValues`;
  - browser: `js/location`, `navigator`, `localStorage`, `sessionStorage`,
             media-query (`matchMedia`).

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
staleness without WRITING it durably — rf2-95b0lc), and the sanctioned
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
— never to read the host here. (EP-0017 retired the `:rf.world/inputs` envelope
in favour of the flat `:rf.cofx` map, with no alias; see docs/spec/002-Frames.md
§Recordable coeffects.)


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
     opt out with a reviewed annotation rather than silently). The old generic
     `interop/debug-enabled?` perf-probe window allowlist was REMOVED (rf2-
     nftz2s §3): a debug probe being NEAR a durable write is not a structural
     guarantee the write itself is diagnostic, so it let a real durable
     `:updated-at (now-ms)` slip past CI. A genuine diagnostic read in a
     durable-write file now uses the explicit per-site `#_:rf.world/ambient-ok`
     escape, not ambient proximity to a debug flag.

The gate is line-local on the field-key match but consults a small +/-3-line
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

Exit code:
    0  no ambient durable read in any durable-write namespace
    1  at least one ambient durable read (results printed file:line)
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
    # machine reply + snapshot writer
    "implementation/machines/src/re_frame/machines/reply.cljc",
    "implementation/machines/src/re_frame/machines/lifecycle_fx/update_snapshot.cljc",
    # routing reply handler
    "implementation/routing/src/re_frame/routing/reply.cljc",
    # restore / hydration installers
    "implementation/ssr/src/re_frame/ssr/hydrate.cljc",
    "implementation/resources/src/re_frame/resources/ssr.cljc",
)

_SOURCE_SUFFIXES = (".clj", ".cljc", ".cljs")

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
#   clock:  (interop/now-ms) (interop/epoch-now-ms) (js/Date.now) (.now js/Date)
#   random: (rand) (rand-int ...) (rand-nth ...) (random-uuid)
#           js/crypto.getRandomValues  (.getRandomValues js/crypto)
#   browser: js/location  js/navigator  navigator.  js/localStorage
#            js/sessionStorage  (.matchMedia ...)  js/matchMedia
# Each entry is NAMED so a finding can say which read form produced it and the
# self-test can hold the roster to owning a fixture (rf2-g1xpb). The names are
# the roster's identity; the joined alternation below is byte-for-byte the
# union that was here before, so the gate matches exactly what it always did.
_AMBIENT_READ_FORMS: tuple[tuple[str, str], ...] = (
    # clock
    ("now-ms",       r"\(\s*(?:interop/)?(?:epoch-)?now-ms\b[^)]*\)"),
    ("js/Date.now",  r"\(\s*js/Date\.now\b[^)]*\)"),
    (".now js/Date", r"\(\s*\.now\s+js/Date\b[^)]*\)"),
    # random
    ("rand",         r"\(\s*rand\b[^)]*\)"),
    ("rand-int",     r"\(\s*rand-int\b[^)]*\)"),
    ("rand-nth",     r"\(\s*rand-nth\b[^)]*\)"),
    ("random-uuid",  r"\(\s*random-uuid\b[^)]*\)"),
    ("js/crypto.getRandomValues",   r"js/crypto\.getRandomValues\b"),
    (".getRandomValues js/crypto",  r"\(\s*\.getRandomValues\s+js/crypto\b"),
    # browser / host facts
    ("js/location",       r"js/location\b"),
    ("js/navigator",      r"js/navigator\b"),
    ("navigator.",        r"navigator\.\w"),
    ("js/localStorage",   r"js/localStorage\b"),
    ("js/sessionStorage", r"js/sessionStorage\b"),
    (".matchMedia",       r"\(\s*\.matchMedia\b"),
    ("js/matchMedia",     r"js/matchMedia\b"),
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
# DELIBERATELY NOT a wrapper (rf2-nftz2s §3): `interop/debug-enabled?`. The old
# generic debug-window allowlist exempted ANY durable field←ambient pair merely
# because a `(when interop/debug-enabled? ...)` perf probe sat within +/-6 lines
# — so a REAL durable `:updated-at (interop/now-ms)` write slipped past CI just
# by being NEAR an unrelated debug probe (a genuine false negative, not a
# contrived one). A debug perf probe is not a STRUCTURAL guarantee that the
# nearby durable write is itself diagnostic. The replacements: (a) `trace/emit!`
# still exempts a genuine trace payload structurally; (b) a deliberate
# diagnostic read in a durable-write file that is NOT inside a trace payload
# annotates the EXACT site with the reviewed `#_:rf.world/ambient-ok` reader-
# discard escape — an explicit per-site opt-out, not an ambient proximity
# heuristic. A perf-probe's own `(when interop/debug-enabled? (now-ms))` elapsed
# read writes no durable field key, so it never matched the violating shape and
# needs no window exemption.
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
    # is one constant for every finding, so without these a fixture can only
    # ever prove that SOMETHING matched — which is how 19 of the 26 keys and 12
    # of the 16 read forms went unexercised (rf2-g1xpb). Attribution costs
    # nothing on the live path: it is read off the match already made, and
    # `_report` is unchanged.
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
    """
    if scan_root.is_file():
        if scan_root.suffix in _SOURCE_SUFFIXES:
            yield scan_root
        return
    for path in sorted(scan_root.rglob("*")):
        if path.suffix not in _SOURCE_SUFFIXES:
            continue
        parts = set(path.relative_to(scan_root).parts)
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


# The two roster read forms NO fixture can exercise, and why. Both carry the
# literal text `getRandomValues`, which is itself an `_ALLOWLIST_WINDOW_RE`
# wrapper — so any line matching either of them sits inside its own exemption
# window and can never produce a finding. That is deliberate (rider c: effect-
# side crypto is sanctioned), and it is stated here rather than left as a silent
# gap in the coverage assertion. The self-test holds this roster BOTH ways: an
# entry here that no fixture covers is fine, but an entry here that a fixture
# DOES cover is a stale exemption and fails.
_UNEXERCISABLE_READ_FORMS: dict[str, str] = {
    "js/crypto.getRandomValues":
        "the text `getRandomValues` is itself an _ALLOWLIST_WINDOW_RE wrapper, "
        "so a line matching this form always exempts itself",
    ".getRandomValues js/crypto":
        "same — the form's own text is the allowlist wrapper",
}

# What each positive fixture must yield, as `key<-read-form` witnesses. A pair
# is written once per (durable key, read form) the fixture proves; a fixture
# planting N witnesses on N lines is asserted by NAME, not by the number N.
_POSITIVE_WITNESSES: dict[str, frozenset[str]] = {
    "positive/now_ms_into_loaded_at.cljc":
        frozenset({"loaded-at<-now-ms"}),
    "positive/date_now_into_started_at.cljc":
        frozenset({"started-at<-.now js/Date"}),
    "positive/epoch_now_into_settled_at.cljc":
        frozenset({"settled-at<-now-ms"}),
    "positive/random_uuid_into_id.cljc":
        frozenset({"id<-random-uuid"}),
    "positive/now_ms_multiline.cljc":
        frozenset({"stale-at<-now-ms"}),
    # `(rand-nth …)` matches the `rand` roster entry as well as its own, so this
    # one line honestly witnesses two entries.
    "positive/rand_nth_into_temp_id.cljc":
        frozenset({"temp-id<-rand", "temp-id<-rand-nth"}),
    "positive/durable_write_near_debug_probe.cljc":
        frozenset({"updated-at<-now-ms"}),
    # Written out, NOT derived from `_DURABLE_TIMESTAMP_KEYS`. A comprehension
    # over the roster shrinks in lockstep when a roster entry is deleted, so the
    # fixture would keep passing having stopped testing that entry — the exact
    # self-blindness this bead is about. Verified by deleting `deadline-at`:
    # derived expectations stayed green, these red naming it.
    "positive/every_durable_timestamp_key.cljc": frozenset({
        "started-at<-now-ms", "deadline-at<-now-ms", "loaded-at<-now-ms",
        "stale-at<-now-ms", "invalidated-at<-now-ms", "settled-at<-now-ms",
        "created-at<-now-ms", "completed-at<-now-ms", "errored-at<-now-ms",
        "restored-at<-now-ms", "installed-at<-now-ms", "registered-at<-now-ms",
        "updated-at<-now-ms", "detected-at<-now-ms", "fetched-at<-now-ms",
        "cached-at<-now-ms", "expires-at<-now-ms", "refreshed-at<-now-ms",
    }),
    "positive/every_durable_id_key.cljc": frozenset({
        "id<-random-uuid", "entry-id<-random-uuid", "request-id<-random-uuid",
        "instance-id<-random-uuid", "mutation-id<-random-uuid",
        "temp-id<-random-uuid", "correlation-id<-random-uuid",
        "resource-id<-random-uuid",
    }),
    "positive/every_ambient_read_form.cljc": frozenset({
        "instance-id<-now-ms",
        "instance-id<-js/Date.now",
        "instance-id<-.now js/Date",
        "instance-id<-rand",
        "instance-id<-rand-int",
        "instance-id<-rand-nth",
        "instance-id<-random-uuid",
        "instance-id<-js/location",
        "instance-id<-js/navigator",
        "instance-id<-navigator.",
        "instance-id<-js/localStorage",
        "instance-id<-js/sessionStorage",
        "instance-id<-.matchMedia",
        "instance-id<-js/matchMedia",
    }),
}

_NEGATIVE_FIXTURES: tuple[str, ...] = (
    # threaded causal time from the reply token (the correct pattern)
    "negative/threaded_completed_at.cljc",
    # effect-side crypto for a session token (rider c)
    "negative/effect_side_crypto_token.cljc",
    # a trace/diagnostic timestamp (allowlisted wrapper)
    "negative/trace_diagnostic_timestamp.cljc",
    # a perf probe gated on debug-enabled? (its reads bind locals + write no
    # durable key; the durable :updated-at threads the causal token) — green on
    # its own merits, NOT via a debug-proximity allowlist (rf2-nftz2s §3)
    "negative/debug_enabled_perf_probe.cljc",
    # the conscious #_:rf.world/ambient-ok escape
    "negative/ambient_ok_escape.cljc",
    # the symbol in a docstring / `;;` comment
    "negative/now_ms_in_docstring.cljc",
    # a freshness DECISION read (compared, not written durably)
    "negative/freshness_decision_read.cljc",
)


def _run_self_tests(verbose: bool = False) -> int:
    """Scan each fixture and assert the EXACT set of witnesses it yields.

    A witness is `<durable key><-<ambient read form>` — the two halves of the
    cross-product this gate detects, read off the match that produced the
    finding. Direct-file scan mode is used so the suffix allow-list does not
    hide the fixtures (the fixture IS the durable-write surface under test).

    Four assertions, each closing a different way for the gate to go quietly
    toothless (the shape `check_retired_image_keys` arrived at, rf2-e1xx0):

      1. Per positive fixture, the witness set must match EXACTLY, and the
         finding COUNT must equal it — so a dead detector reds by NAME rather
         than being masked by a sibling witness in the same file, and two
         witnesses collapsing onto one line cannot hide inside a set.
      2. Every negative fixture stays green.
      3. Every durable key in `_ALL_DURABLE_KEYS` owns a witness. Nineteen of
         the twenty-six did not, so widening the roster carried no obligation
         and a key added with a typo'd spelling was green forever (rf2-g1xpb).
      4. Every read form in `_AMBIENT_READ_FORMS` owns a witness, except the
         declared-unexercisable pair — and a declared entry that DOES get
         covered fails too, so the exemption cannot go stale.
    """
    failures = 0
    checked = 0
    covered_keys: set[str] = set()
    covered_reads: set[str] = set()
    fake_root = _SELF_TEST_FIXTURE_ROOT  # repo_root is unused in direct-file mode

    for fixture, expected in _POSITIVE_WITNESSES.items():
        path = _SELF_TEST_FIXTURE_ROOT / fixture
        if not path.is_file():
            sys.stderr.write(
                f"self-test FAIL: fixture {fixture!r} missing at {path}\n"
            )
            failures += 1
            continue
        checked += 1
        findings = scan(path, fake_root, include_tests=True)
        actual = frozenset(
            f"{f.key}<-{form}" for f in findings for form in f.reads
        )
        covered_keys |= {f.key for f in findings}
        covered_reads |= {form for f in findings for form in f.reads}
        if actual != expected:
            failures += 1
            sys.stderr.write(f"self-test FAIL: {fixture}\n")
            missing = sorted(expected - actual)
            extra = sorted(actual - expected)
            if missing:
                sys.stderr.write(
                    "      DETECTOR DEAD — this fixture plants "
                    f"{', '.join(missing)} and the gate did not see it\n"
                )
            if extra:
                sys.stderr.write(f"      UNEXPECTED: {', '.join(extra)}\n")
            continue
        # A set hides duplicates, and a duplicate is a witness that can die
        # unseen: if two findings attribute identically, either may vanish and
        # the set is unchanged. Every finding must therefore be distinguishable.
        # (Two witnesses on ONE line is fine and expected — `(rand-nth …)`
        # matches the `rand` roster entry as well as its own.)
        elif len({(f.key, f.reads) for f in findings}) != len(findings):
            failures += 1
            sys.stderr.write(
                f"self-test FAIL: {fixture} has {len(findings)} findings that "
                "attribute to fewer distinct witnesses — a duplicate can die "
                "without changing the set. Plant each witness once.\n"
            )
        elif verbose:
            sys.stderr.write(
                f"self-test PASS: {fixture} ({len(findings)} witness(es))\n"
            )

    for fixture in _NEGATIVE_FIXTURES:
        path = _SELF_TEST_FIXTURE_ROOT / fixture
        if not path.is_file():
            sys.stderr.write(
                f"self-test FAIL: fixture {fixture!r} missing at {path}\n"
            )
            failures += 1
            continue
        checked += 1
        findings = scan(path, fake_root, include_tests=True)
        if findings:
            failures += 1
            sys.stderr.write(
                f"self-test FAIL: {fixture} is a sanctioned counterpart and "
                f"must stay GREEN; got {len(findings)} finding(s):\n"
            )
            for f in findings:
                sys.stderr.write(
                    f"      line {f.line}: {f.key} <- "
                    f"{', '.join(sorted(f.reads)) or '?'}: {f.snippet}\n"
                )
        elif verbose:
            sys.stderr.write(f"self-test PASS: {fixture} (green)\n")

    uncovered_keys = sorted(set(_ALL_DURABLE_KEYS) - covered_keys)
    if uncovered_keys:
        failures += 1
        sys.stderr.write(
            "self-test FAIL: durable key(s) in the roster with no positive "
            f"witness: {', '.join(uncovered_keys)}\n"
            "      Plant each one in a positive fixture and declare its witness "
            "in _POSITIVE_WITNESSES.\n"
        )

    roster_reads = {name for name, _pattern in _AMBIENT_READ_FORMS}
    uncovered_reads = sorted(
        roster_reads - covered_reads - set(_UNEXERCISABLE_READ_FORMS)
    )
    if uncovered_reads:
        failures += 1
        sys.stderr.write(
            "self-test FAIL: ambient read form(s) in the roster with no "
            f"positive witness: {', '.join(uncovered_reads)}\n"
            "      Plant each one in a positive fixture, or — if it genuinely "
            "cannot fire — declare it in _UNEXERCISABLE_READ_FORMS with the "
            "reason.\n"
        )
    stale = sorted(set(_UNEXERCISABLE_READ_FORMS) & covered_reads)
    if stale:
        failures += 1
        sys.stderr.write(
            "self-test FAIL: read form(s) declared unexercisable that a fixture "
            f"DID exercise: {', '.join(stale)}\n"
            "      The exemption is stale — delete the entry and keep the "
            "witness.\n"
        )
    unknown = sorted(set(_UNEXERCISABLE_READ_FORMS) - roster_reads)
    if unknown:
        failures += 1
        sys.stderr.write(
            "self-test FAIL: _UNEXERCISABLE_READ_FORMS names form(s) that are "
            f"not in the roster at all: {', '.join(unknown)}\n"
        )

    if failures:
        sys.stderr.write(f"\n{failures} self-test failure(s).\n")
        return 1
    if verbose:
        sys.stderr.write(
            f"all {checked} self-test fixture(s) passed; all "
            f"{len(_ALL_DURABLE_KEYS)} durable key(s) and "
            f"{len(roster_reads) - len(_UNEXERCISABLE_READ_FORMS)} of "
            f"{len(roster_reads)} ambient read form(s) covered "
            f"({len(_UNEXERCISABLE_READ_FORMS)} declared unexercisable).\n"
        )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
