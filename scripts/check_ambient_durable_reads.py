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
_AMBIENT_READ_ALT = "|".join([
    # clock
    r"\(\s*(?:interop/)?(?:epoch-)?now-ms\b[^)]*\)",
    r"\(\s*js/Date\.now\b[^)]*\)",
    r"\(\s*\.now\s+js/Date\b[^)]*\)",
    # random
    r"\(\s*rand\b[^)]*\)",
    r"\(\s*rand-int\b[^)]*\)",
    r"\(\s*rand-nth\b[^)]*\)",
    r"\(\s*random-uuid\b[^)]*\)",
    r"js/crypto\.getRandomValues\b",
    r"\(\s*\.getRandomValues\s+js/crypto\b",
    # browser / host facts
    r"js/location\b",
    r"js/navigator\b",
    r"navigator\.\w",
    r"js/localStorage\b",
    r"js/sessionStorage\b",
    r"\(\s*\.matchMedia\b",
    r"js/matchMedia\b",
])

# A durable field-key immediately followed (same form, possibly across a line
# break within the window) by an ambient read. We match line-locally on
# `:KEY <ambient-read>` — the keyword in map-entry KEY position with the
# ambient read as its value. Whitespace-tolerant.
_ALL_DURABLE_KEYS = _DURABLE_TIMESTAMP_KEYS + _DURABLE_ID_KEYS
_DURABLE_KEY_ALT = "|".join(re.escape(k) for k in _ALL_DURABLE_KEYS)

_VIOLATION_RE = re.compile(
    r":(?:" + _DURABLE_KEY_ALT + r")\b(?!/)\s+(?:" + _AMBIENT_READ_ALT + r")"
)

# Same shape but allowing the ambient read on the NEXT line (the common
# multi-line map-entry shape). We detect the durable key at end-of-(masked)-form
# and an ambient read opening the following line. Handled in the window pass.
_DURABLE_KEY_TRAILING_RE = re.compile(
    r":(?:" + _DURABLE_KEY_ALT + r")\b(?!/)\s*$"
)
_AMBIENT_READ_LEADING_RE = re.compile(r"^\s*(?:" + _AMBIENT_READ_ALT + r")")


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
        hit = False
        # Same-line shape: `:loaded-at (interop/now-ms)`.
        if _VIOLATION_RE.search(line):
            hit = True
        # Cross-line shape: durable key ends the line, ambient read opens next.
        elif _DURABLE_KEY_TRAILING_RE.search(line) and line_no < len(masked):
            if _AMBIENT_READ_LEADING_RE.search(masked[line_no]):
                hit = True
        if not hit:
            continue
        if _window_allowlisted(masked, line_no):
            continue
        findings.append(
            Finding(path, line_no, "ambient-durable-read", raw_snippet(line_no))
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


def _run_self_tests(verbose: bool = False) -> int:
    """Scan each fixture file and assert the expected finding count.

    Positive fixtures plant ONE ambient durable read (expected=1); negative
    fixtures exercise the sanctioned counterparts that MUST stay green
    (expected=0). Direct-file scan mode is used so the suffix allow-list does
    not hide them (the fixture IS the durable-write surface under test).
    """
    cases: list[tuple[str, int]] = [
        # --- positives: each planted ambient durable read must FLAG ---
        ("positive/now_ms_into_loaded_at.cljc",        1),
        ("positive/date_now_into_started_at.cljc",     1),
        ("positive/epoch_now_into_settled_at.cljc",    1),
        ("positive/random_uuid_into_id.cljc",          1),
        ("positive/now_ms_multiline.cljc",             1),
        ("positive/rand_nth_into_temp_id.cljc",        1),
        # rf2-nftz2s §3: a real durable write near a debug probe now FLAGS
        # (the old generic debug-window allowlist hid it — false negative).
        ("positive/durable_write_near_debug_probe.cljc", 1),
        # --- negatives: every sanctioned counterpart must stay GREEN ---
        # threaded causal time from the reply token (the correct pattern)
        ("negative/threaded_completed_at.cljc",        0),
        # effect-side crypto for a session token (rider c)
        ("negative/effect_side_crypto_token.cljc",     0),
        # a trace/diagnostic timestamp (allowlisted wrapper)
        ("negative/trace_diagnostic_timestamp.cljc",   0),
        # a perf probe gated on debug-enabled? (its reads bind locals + write
        # no durable key; the durable :updated-at threads the causal token) —
        # green on its own merits, NOT via a debug-proximity allowlist (rf2-nftz2s §3)
        ("negative/debug_enabled_perf_probe.cljc",     0),
        # the conscious #_:rf.world/ambient-ok escape
        ("negative/ambient_ok_escape.cljc",            0),
        # the symbol in a docstring / `;;` comment
        ("negative/now_ms_in_docstring.cljc",          0),
        # a freshness DECISION read (compared, not written durably)
        ("negative/freshness_decision_read.cljc",      0),
    ]

    failures = 0
    fake_root = _SELF_TEST_FIXTURE_ROOT  # repo_root is unused in direct-file mode
    for fixture, expected in cases:
        path = _SELF_TEST_FIXTURE_ROOT / fixture
        if not path.is_file():
            sys.stderr.write(
                f"self-test FAIL: fixture {fixture!r} missing at {path}\n"
            )
            failures += 1
            continue
        got = len(scan(path, fake_root, include_tests=True))
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
