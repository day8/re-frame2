#!/usr/bin/env python3
"""EP-0007 (One Name Per Fact) §Enforcement — retired-spelling CI gate.

EP-0007 rule 2 ("no stable accepted synonyms") gets the no-floor-lint
treatment "where shapes allow": a *retired* spelling reappearing in
repo source is a CI failure, not a doc note. Three renames have merged,
so three retired spellings are now lintable:

  (a) The bare `:frame` event-context COEFFECT (sweep item 1, rf2-1m6rf1).
      EP-0002 R3 "one carrier, one name" retired the duplicate `:frame`
      coeffect: the frame stamp travels in the event context under
      `:rf.frame/id` ONLY. The framework once injected `:frame` beside it
      and ~10 internal sites read it back.

  (b) The redirect-target keys `:url` / `:to` on an SSR redirect effect
      (sweep item 4, rf2-vngir). The `:rf.server/redirect` /
      `:rf.server/safe-redirect` fx writes an HTTP `Location` response
      header, so it uses header vocabulary — the canonical (and only)
      target key is `:location`. `:url` / `:to` are retired and now throw
      `:rf.error/redirect-retired-target-key`.

  (c) Route metadata `:query-retain` (EP-0037 R5, rf2-jlmgt). A destination
      address is taken LITERALLY: the router no longer folds ambient
      current-route query state into a route the caller authored. The key is
      retired with NO alias — `reg-route` rejects it as an unknown bare key —
      and it no longer widens the query-key promotion vocabulary. Carrying
      query state across routes is application policy spelled as a pure
      function over the destination address.

WHY THE SHAPES ARE SCOPED PRECISELY (the "where shapes allow" caveat)

`:frame` and `:url` are both heavily SANCTIONED elsewhere — a bare grep for
either keyword fires on 100+ legitimate sites. EP-0007 rule 3 records the
cross-layer vocabulary rules that make those uses correct:

  * `:frame` is the public dispatch/subscribe opt, the dispatch envelope
    key, the binary fx-handler ctx key (Spec 002 §The binary fx-handler
    signature) + the HTTP-interceptor ctx key (Spec 014), and the
    trace / error-record tag. ALL sanctioned. Only the *coeffect read* was
    retired.

  * `:url` is the canonical CLIENT-navigation key (routing / `navigate`
    surfaces) per the HTTP-response-vs-navigation vocabulary rule in
    spec/Conventions.md §The naming rules. Only its use as an SSR
    redirect-TARGET key was retired.

So this gate does NOT grep for the bare keyword. It scopes to the exact
retired SHAPES:

  (a) the coeffect-READ forms the rf2-1m6rf1 rename removed —
        - (get-coeffect <ctx> :frame ...)        ;; the canonical accessor
        - (:frame (:coeffects <x>))              ;; keyword-fn off :coeffects
        - (get-in <ctx> [:coeffects :frame] ...) ;; get-in path via :coeffects
      None of these can match the public opt, the trace tag, the dispatch
      envelope, or the fx-handler `{:keys [frame]}` destructuring (which is
      an fx-CONTEXT read, sanctioned). The framework reads coeffects through
      these three forms and nothing else (verified against the rename diff).

  (b) a `:url` / `:to` KEY appearing in a map that is — in the same form —
      tagged as an SSR redirect: a literal `{... :url ...}` (or `:to`) whose
      enclosing form also names `:rf.server/redirect` or
      `:rf.server/safe-redirect`. This catches the
      `(dispatch [... [:rf.server/redirect {:url "/x"}]])` /
      `{:fx [[:rf.server/redirect {:to "/x"}]]}` reintroduction without
      firing on `(navigate {:url "/x"})` client-nav, where no
      `:rf.server/*redirect` tag is present.

  (c) `:query-retain` as a Clojure KEYWORD TOKEN — the keyword delimited on
      both sides (start-of-line or one of `(`/`[`/`{`/whitespace/`,` before;
      a closing delimiter, whitespace or end-of-line after). Unlike (a) and
      (b) the retired key has NO sanctioned code use whatsoever, so the three
      USE shapes EP-0037 row 9 enumerates all reduce to that one token shape:

        - a `reg-route` metadata map    `{:query-retain #{:theme :locale}}`
        - the accepted-key roster       `#{... :query-defaults :query-retain}`
        - the promotion vocabulary      `[:query :query-defaults :query-retain]`

      What the token boundary buys is prose safety. Every legitimate in-tree
      mention NAMES the key as retired, and every one of them spells it as
      backticked prose — `` `:query-retain` `` — so the opening backtick
      denies the token-start boundary and the rule cannot fire on a retirement
      note even where comment/string masking does not reach. The mentions:
      `implementation/routing/src/re_frame/routing/{classification,navigate,
      registry}.cljc` (comments + one docstring, masked as well as
      backticked), `skills/re-frame2/references/tooling/routing.md`,
      `spec/Spec-Schemas.md`, and
      `spec/conformance/fixtures/routing-query-keyword-discipline.edn`. The
      last three carry a suffix this gate never opens (`.md`, `.edn`) — the
      `skills/` one sits inside a scanned TREE, so it is the suffix filter and
      not the roster that keeps it out. The boundary is what keeps the rule
      correct if the suffix filter is ever widened, and the self-test pins all
      five verbatim.

WHERE A SHAPE IS TOO AMBIGUOUS TO LINT WITHOUT NOISE (documented, not shipped)

EP-0007 §Enforcement says "where shapes allow"; two shapes are deliberately
NOT linted because they cannot be distinguished statically from sanctioned
code without unacceptable false-positive noise:

  * A redirect args map bound to a NAME far from the fx id, e.g.
        (let [r {:url "/x"}] (dispatch [... [:rf.server/redirect r]]))
    The `:url` key and the `:rf.server/redirect` tag are in different forms;
    binding them requires dataflow analysis a regex gate can't do. The
    RUNTIME guard (`reject-retired-redirect-keys!` in
    re-frame.ssr.response) is the real backstop here — it throws on the
    retired key regardless of how the map was constructed. This lint is a
    fast static defence-in-depth for the common inline-literal shape, not a
    replacement for the runtime throw.

  * The bare `:frame` keyword as a coeffect read via an unusual accessor
    (e.g. a user `(get (:coeffects ctx) :frame)`). The three canonical
    forms above are what the framework uses; an exotic accessor is rare,
    framework-author-only, and would need whole-form dataflow to bind the
    map to `:coeffects`. Not worth the false-positive surface; the
    `event_context_coeffect_keys_test` conformance test pins the exact
    framework-injected coeffect key set so a `:frame` coeffect cannot ride
    back IN even if a read of it slipped past this gate.

SCAN SURFACE

Every Clojure source tree in the repo — `.clj` / `.cljc` / `.cljs` under
`implementation/`, `examples/`, `tools/`, `skills/`, `testbeds/`, `migration/`
and `docs/tools/`. See `DEFAULT_SCAN_DIRS` for the roster, the two trees
deliberately left off it, and why `implementation/` alone was not enough
(rf2-kqxe6.25). The default excludes `test/` trees: tests legitimately ASSERT
the retired spelling is gone (the `event_context_coeffect_keys_test` checks
`(not (contains? cofx :frame))`, and the SSR end-to-end test feeds `:url` /
`:to` to assert the throw). A test fixture deliberately exercising a retired
spelling is correct, not drift. Pass `--include-tests` to scan them too
(used by the self-test fixtures, which live under a `test`-named dir).

Exit code:
    0  no retired spelling in any scanned source tree
    1  at least one retired spelling (results printed file:line)
    2  invocation / setup error

Dependency-light — Python stdlib only.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path
from typing import Iterable, NamedTuple

# --------------------------------------------------------------------------
# Scan surface
# --------------------------------------------------------------------------

# EVERY Clojure source tree in the repo, minus the two documented exclusions
# below. rf2-kqxe6.25: the surface used to be `implementation/` alone, on the
# reasoning that `tools/` is bundle-isolated dev tooling and `examples/` is
# consumer-shaped, so only framework source needed the ratchet. That reasoning
# does not survive EP-0037 R5, which retired `:query-retain` with NO alias and
# then had rf2-kqxe6.12 / .13 migrate `examples/` and `skills/` off it. A
# reintroduction in the trees a reader COPIES FROM is the likeliest
# reintroduction there is, and it was the one the ratchet could not see: the
# rule fired correctly on a planted `examples/` occurrence the moment it was
# pointed at it, so only the invocation was scoped.
#
# The roster covers every top-level directory that carries a tracked
# `.clj`/`.cljc`/`.cljs` file today, so no tree is unratcheted. Verify with:
#
#   git ls-files | grep -E '\.clj[cs]?$' | sed -E 's#/.*##' | sort -u
#
# Two trees on that list are deliberately absent, and both would break the gate
# rather than merely widen it:
#
#   * `scripts/` — `scripts/_test_fixtures/` is where this gate's own POSITIVE
#     self-test fixtures live. They plant the retired spellings on purpose; a
#     live scan over them would be red by construction, forever.
#   * `docs/` — mkdocs stages `spec/` and `migration/` into `docs/spec/` and
#     `docs/migration/` at build time (see `.gitignore`), so a checkout that has
#     run `mkdocs build` carries GENERATED copies of trees scanned here already.
#     Scanning `docs/` would double-report a real finding and could report a
#     stale one from a copy predating the fix. `docs/tools` — the playground SCI
#     source, the only tracked Clojure under `docs/` — is rostered directly, so
#     nothing is lost.
#
# `spec/` carries no Clojure source at all (prose + EDN fixtures), and this
# gate's suffix filter is source-only, so it is not a candidate: the retirement
# NOTES there are held by the self-test's verbatim prose phase instead.
DEFAULT_SCAN_DIRS = (
    "implementation",
    "examples",
    "tools",
    "skills",
    "testbeds",
    "migration",
    "docs/tools",
)

_SOURCE_SUFFIXES = (".clj", ".cljc", ".cljs")

# Directory names whose contents are never scannable source for this gate.
_EXCLUDE_DIR_NAMES = frozenset({
    "node_modules",
    "target",
    ".shadow-cljs",
    ".git",
    ".beads",
    ".cpcache",
})

# `test` / `tests` dirs are excluded by default (see module docstring): a test
# that ASSERTS a retired spelling is gone, or feeds one to assert the runtime
# throw, legitimately names it. `--include-tests` lifts the exclusion (the
# self-test fixtures rely on this so their `*_test`-named files are scanned).
_TEST_DIR_NAMES = frozenset({"test", "tests"})


# --------------------------------------------------------------------------
# Retired-shape patterns
# --------------------------------------------------------------------------

# (a) The retired `:frame` event-context COEFFECT read. Three forms, each of
#     which the rf2-1m6rf1 rename removed. `\b` after `:frame` forbids
#     matching `:frame/id` or `:frame-foo`; a `:rf.frame/id` read never
#     matches because the keyword there is `:rf.frame/id`, not `:frame`.

# 1. (get-coeffect <ctx> :frame)  — optionally namespace-qualified accessor
#    (interceptor/get-coeffect, rf/get-coeffect, bare get-coeffect).
_COEFFECT_GET_RE = re.compile(
    r"\(\s*(?:[\w.+!*?<>=/-]+/)?get-coeffect\s+[^()]+?:frame\b(?!/)"
)

# 2. (:frame (:coeffects <x>))    — keyword-fn read off the :coeffects map.
#    Whitespace-tolerant; the inner (:coeffects ...) is the load-bearing
#    signal that this is a coeffect read, not a public-opt / trace-tag use.
_COEFFECT_KWFN_RE = re.compile(
    r"\(\s*:frame\b(?!/)\s*\(\s*:coeffects\b"
)

# 3. (get-in <ctx> [:coeffects :frame] ...) — get-in path through :coeffects.
_COEFFECT_GETIN_RE = re.compile(
    r"\[\s*:coeffects\s+:frame\b(?!/)\s*\]"
)

_COEFFECT_PATTERNS = (
    ("get-coeffect-frame", _COEFFECT_GET_RE),
    (":frame-of-:coeffects", _COEFFECT_KWFN_RE),
    ("get-in-:coeffects-:frame", _COEFFECT_GETIN_RE),
)

# (b) The retired `:url` / `:to` redirect-TARGET key. Scoped to a form that
#     also names an SSR redirect fx id, so client-navigation `:url` (no
#     `:rf.server/*redirect` tag nearby) never matches. We detect, within a
#     window that mentions `:rf.server/redirect` or `:rf.server/safe-redirect`,
#     a `:url` or `:to` MAP KEY (a keyword immediately followed by whitespace
#     and a value — the map-entry shape, distinguishing a key from a bare
#     keyword used as a value).
_SSR_REDIRECT_FX_RE = re.compile(r":rf\.server/(?:safe-)?redirect\b")

# A retired redirect-target key in map-entry position: `:url <something>` or
# `:to <something>` where the keyword opens a map entry. The trailing
# `(?=\s)` + `\b` ensure `:url` not `:urls`/`:url/x`, and `:to` not
# `:token`/`:to/x`. Matched only inside an SSR-redirect window (see below).
_RETIRED_REDIRECT_KEY_RE = re.compile(r":(?:url|to)\b(?!/)\s")

# (c) The retired route-metadata key `:query-retain` (EP-0037 R5). Matched as a
#     delimited Clojure KEYWORD TOKEN, which is what every USE shape has in
#     common — a `reg-route` metadata-map key, a member of the accepted-key
#     roster, a member of the promotion vocabulary — and what no retirement
#     NOTE has, since those spell it as backticked prose. The trailing
#     lookahead also rejects `:query-retain/x` and `:query-retains`.
_RETIRED_QUERY_RETAIN_RE = re.compile(
    r"(?:^|(?<=[\s(\[{,]))"      # keyword-token start (a backtick denies it)
    r":query-retain"
    r"(?=[\s)\]},]|$)"           # keyword-token end
)


class Finding(NamedTuple):
    path: Path
    line: int
    kind: str
    snippet: str


# --------------------------------------------------------------------------
# Clojure-comment masking
# --------------------------------------------------------------------------
#
# The retired spellings appear extensively in PROSE: docstrings, `;;` line
# comments, and the rationale comments around the runtime guards (e.g. the
# `retired-redirect-target-keys` def + its docstring in
# re-frame.ssr.response). Those are documentation, not reintroduced code —
# the gate must not fire on them. We mask:
#
#   * `;`-to-end-of-line comments (length-preserving so column offsets and
#     the keyword `\b` boundaries are unchanged), AND
#   * the contents of "..." string literals (docstrings + prose strings),
#     again length-preserving.
#
# `#"..."` regex literals and `\"` escapes inside strings are handled by the
# simple state machine below. The `retired-redirect-target-keys` DEF — the
# vector literal `[:url :to]` that names the retired keys — is intentionally
# NOT a redirect-fx form (it carries no `:rf.server/*redirect` tag in the same
# window), so it does not match (b) regardless. And `[:url :to]` as bare
# keywords are not in map-entry position (no value follows), so the
# map-entry shape would not match them anyway.

_LINE_COMMENT_RE = re.compile(r";.*$")


def _mask_strings(line: str, in_string: bool) -> tuple[str, bool]:
    """Replace "..."-string-literal contents with spaces (length-preserving).

    Returns (masked-line, still-in-string?). Tracks multi-line strings via the
    carried `in_string` flag. Backslash-escaped quotes inside a string do not
    close it.
    """
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


def _mask_comment(line: str) -> str:
    """Blank a `;`-to-EOL line comment, length-preserving.

    Applied AFTER string masking so a `;` inside a (now-blanked) string is not
    treated as a comment, and a `;` inside a real string is already gone.
    """
    m = _LINE_COMMENT_RE.search(line)
    if not m:
        return line
    start = m.start()
    return line[:start] + (" " * (len(line) - start))


def _masked_lines(text: str) -> list[str]:
    """Return per-line content with string-literals + `;` comments blanked.

    Length-preserving so 1-based line numbers and reported snippets line up
    with the source. Carries the in-string flag across newlines for multi-line
    docstrings.
    """
    masked: list[str] = []
    in_string = False
    for raw in text.splitlines():
        line, in_string = _mask_strings(raw, in_string)
        line = _mask_comment(line)
        masked.append(line)
    return masked


# --------------------------------------------------------------------------
# File iteration
# --------------------------------------------------------------------------


def _iter_source_files(scan_root: Path, include_tests: bool) -> Iterable[Path]:
    """Yield scannable source files under scan_root.

    Excludes generated/vendor dirs always, and `test`/`tests` dirs unless
    `include_tests` is set.

    PRUNED, not filtered-after (rf2-76c76; method proven by rf2-e1xx0 in
    `check_retired_image_keys.py`). `_EXCLUDE_DIR_NAMES` is dropped from
    `os.walk`'s dirnames IN PLACE, so a built checkout never descends into
    `implementation/.shadow-cljs` (34.8k entries), `node_modules` (3.4k) or
    `target`. `rglob("*")` enumerated all 51.6k entries under
    `implementation/` and discarded them one at a time — 4.9s of this gate's
    8.1s wall clock on a built tree.

    The surviving sequence is IDENTICAL, set and order:
      * pruning drops only what the `_EXCLUDE_DIR_NAMES` test below already
        dropped — nothing under an excluded directory could survive either
        path, so the sets match;
      * the collected matches go through ONE GLOBAL `sorted()`, reproducing
        `sorted(rglob("*"))`'s whole-subtree ordering rather than os.walk's
        directory-grouped order.

    Note `_EXCLUDE_DIR_NAMES` here deliberately does NOT carry `out` (unlike
    the sibling gates) — adding it would narrow this gate's scope, which is a
    scope decision and not this change's business.
    """
    if scan_root.is_file():
        # Direct-file mode (used by --self-test fixtures pointing at one file).
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
        # Kept as the belt to the pruning's braces, and now on string ops:
        # `Path.relative_to` was pure pathlib object churn for a prefix strip.
        parts = set(path.as_posix()[scan_prefix_len:].split("/"))
        if parts & _EXCLUDE_DIR_NAMES:
            continue
        if not include_tests and (parts & _TEST_DIR_NAMES):
            continue
        yield path


# --------------------------------------------------------------------------
# Per-file scan
# --------------------------------------------------------------------------


def _scan_text(path: Path, text: str) -> list[Finding]:
    """Return retired-spelling findings in `text` (already file-attributed).

    Pattern-matching runs over the MASKED lines (string-literals + `;`
    comments blanked) so prose never fires the gate, but the reported snippet
    is the RAW source line so the diagnostic shows the actual offending text.
    """
    findings: list[Finding] = []
    masked = _masked_lines(text)
    raw = text.splitlines()

    def raw_snippet(line_no: int) -> str:
        # line_no is 1-based; raw may be shorter than masked only if the file
        # ends without a trailing newline edge-case, so guard the index.
        return raw[line_no - 1].strip() if 0 <= line_no - 1 < len(raw) else ""

    # (a) Coeffect-read forms — line-local; none of the three spans lines.
    for line_no, line in enumerate(masked, start=1):
        for kind, pat in _COEFFECT_PATTERNS:
            if pat.search(line):
                findings.append(
                    Finding(path, line_no, f"retired-frame-coeffect:{kind}",
                            raw_snippet(line_no))
                )

    # (b) SSR-redirect retired target key. We work over a small sliding
    #     window of masked lines: a retired `:url` / `:to` map-entry key counts
    #     only when a `:rf.server/(safe-)redirect` tag appears within the same
    #     window. The window is the enclosing top-level-ish form approximated
    #     as +/- a few lines, which covers the realistic inline shapes
    #     (`[:rf.server/redirect {:url ...}]`, possibly wrapped across 2-3
    #     lines) without binding distant `let`-bound maps (documented
    #     ambiguity limit).
    _WINDOW = 3
    for line_no, line in enumerate(masked, start=1):
        if not _RETIRED_REDIRECT_KEY_RE.search(line):
            continue
        lo = max(0, line_no - 1 - _WINDOW)
        hi = min(len(masked), line_no + _WINDOW)
        window_text = "\n".join(masked[lo:hi])
        if _SSR_REDIRECT_FX_RE.search(window_text):
            findings.append(
                Finding(path, line_no, "retired-redirect-target-key",
                        raw_snippet(line_no))
            )

    # (c) The retired `:query-retain` route-metadata key — line-local, and no
    #     enclosing-form window is needed: the key has no sanctioned code use,
    #     so a delimited keyword token IS the violation wherever it appears.
    for line_no, line in enumerate(masked, start=1):
        if _RETIRED_QUERY_RETAIN_RE.search(line):
            findings.append(
                Finding(path, line_no, "retired-query-retain-key",
                        raw_snippet(line_no))
            )
    return findings


def scan(scan_root: Path, include_tests: bool = False) -> list[Finding]:
    """Scan source under scan_root for retired spellings."""
    findings: list[Finding] = []
    for path in _iter_source_files(scan_root, include_tests):
        text = path.read_text(encoding="utf-8", errors="replace")
        findings.extend(_scan_text(path, text))
    return findings


# --------------------------------------------------------------------------
# Reporting
# --------------------------------------------------------------------------

_FIX_HINTS = {
    "retired-frame-coeffect": (
        "The bare `:frame` event-context coeffect was retired by EP-0002 R3 / "
        "rf2-1m6rf1 (one carrier, one name). Read the frame stamp from the "
        "`:rf.frame/id` coeffect instead — e.g. "
        "`(interceptor/get-coeffect ctx :rf.frame/id)`. (The public `:frame` "
        "dispatch/subscribe opt, the dispatch envelope key, the binary "
        "fx-handler ctx `:frame`, and the trace `:frame` tag are all "
        "sanctioned and unaffected.)"
    ),
    "retired-redirect-target-key": (
        "The redirect-target keys `:url` / `:to` were retired by rf2-vngir / "
        "EP-0007. The SSR redirect fx writes an HTTP `Location` header, so it "
        "uses header vocabulary: the canonical (and only) redirect-target key "
        "is `:location`. Rewrite the `:rf.server/redirect` / "
        "`:rf.server/safe-redirect` arg as `{:location \"...\"}`. (`:url` is "
        "still the canonical key on CLIENT navigation surfaces — different "
        "concept, different word, per spec/Conventions.md §The naming rules.)"
    ),
    "retired-query-retain-key": (
        "Route metadata `:query-retain` was retired by EP-0037 R5 with no "
        "alias — `reg-route` rejects it as an unknown bare key, it is not in "
        "the accepted-key roster, and it no longer widens the query-key "
        "promotion vocabulary. A destination address is taken LITERALLY. "
        "Declare the keys the route owns in `:query` / `:query-defaults`; "
        "carry query state across routes with an ordinary pure function over "
        "the destination address at the call site (Spec 012 §Carrying query "
        "state across routes). To edit the CURRENT route's query, use the "
        "in-place `:query` / `:query-merge` request."
    ),
}


def _report(findings: list[Finding], repo_root: Path) -> None:
    sys.stderr.write(
        f"\n{len(findings)} retired spelling(s) found in repo source "
        "(EP-0007 §Enforcement):\n\n"
    )
    for f in findings:
        try:
            rel = f.path.relative_to(repo_root)
        except ValueError:
            rel = f.path
        sys.stderr.write(f"  {f.kind}: {rel}:{f.line}\n      {f.snippet}\n")
    # Group fix hints by family so the message stays terse.
    families = {k.split(":", 1)[0] for k in (f.kind for f in findings)}
    sys.stderr.write("\nFix:\n")
    for fam in sorted(families):
        sys.stderr.write(f"  * {_FIX_HINTS[fam]}\n")


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "EP-0007 §Enforcement: fail on a retired spelling reappearing in "
            "repo source (the bare :frame coeffect; redirect :url/:to; "
            "route :query-retain)."
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
        dest="scan_dirs",
        help=(
            "Directory (relative to repo-root) to scan. Repeatable. Defaults to "
            "every Clojure source tree in the repo: "
            f"{', '.join(DEFAULT_SCAN_DIRS)}."
        ),
    )
    parser.add_argument(
        "--include-tests",
        action="store_true",
        help=(
            "Scan test/ trees too. Off by default — tests legitimately name "
            "the retired spelling to assert it is gone / rejected."
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
            "scripts/_test_fixtures/check_retired_spellings/ and exit."
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

    scan_dirs = args.scan_dirs or list(DEFAULT_SCAN_DIRS)
    scan_roots = [repo_root / d for d in scan_dirs]

    # A rostered tree that has been renamed or deleted is NOT skipped. Skipping
    # is how a widened gate quietly narrows again — it would report success for
    # a tree it never opened, which is the whole defect class rf2-kqxe6.25 is
    # about. Same posture as the test-lane bijection gate's phantom-path rule.
    missing = [r for r in scan_roots if not r.exists()]
    if missing:
        for root in missing:
            sys.stderr.write(f"error: scan dir {root} does not exist.\n")
        return 2

    if args.verbose:
        n = sum(
            1
            for root in scan_roots
            for _ in _iter_source_files(root, args.include_tests)
        )
        sys.stderr.write(
            f"scanning {n} source file(s) under "
            f"{', '.join(str(r.relative_to(repo_root)) for r in scan_roots)} "
            f"(tests {'included' if args.include_tests else 'excluded'})...\n"
        )

    findings: list[Finding] = []
    for root in scan_roots:
        findings.extend(scan(root, include_tests=args.include_tests))
    if findings:
        _report(findings, repo_root)
        return 1
    if args.verbose:
        sys.stderr.write("no retired spellings in any scanned source tree.\n")
    return 0


# --------------------------------------------------------------------------
# Self-tests (fixture-driven) — prove the gate FIRES on each retired shape
# and stays GREEN on every sanctioned counterpart.
# --------------------------------------------------------------------------

_SELF_TEST_FIXTURE_ROOT = (
    Path(__file__).resolve().parent / "_test_fixtures" / "check_retired_spellings"
)

# Sanctioned `:query-retain` mentions that live OUTSIDE this gate's scan surface
# (a skill reference, a spec schema comment, a conformance fixture). Pinned
# VERBATIM and scanned as raw single lines rather than as `.cljc` fixtures,
# because that is the point: a Markdown bullet gets no comment/string masking,
# so only the keyword-token boundary keeps the rule off it. If the surface ever
# widens to these trees, this phase is what says it stayed correct.
_SANCTIONED_PROSE_MENTIONS: tuple[tuple[str, str], ...] = (
    ("skills/re-frame2/references/tooling/routing.md",
     "and never gains query keys from whichever route was current. There is no "
     "`:query-retain` (retired, no alias; declaring it is rejected as an "
     "unknown bare key), no query middleware, no per-route carry policy."),
    ("spec/Spec-Schemas.md",
     "   [:query-defaults  {:optional true} [:map-of :keyword :any]]  "
     ";; Destination-LOCAL. EP-0037 R5 retired `:query-retain`; cross-route "
     "carry is the application's pure fold over the destination address."),
    ("spec/conformance/fixtures/routing-query-keyword-discipline.edn",
     ";;      EP-0037 R5 retired the third source, `:query-retain`: a key that "
     "was keyword-promoted solely by its `:query-retain #{:theme}` declaration "
     "must now be declared in `:query` / `:query-defaults`."),
)


def _run_self_tests(verbose: bool = False) -> int:
    """Scan each fixture file and assert the expected finding count.

    Each fixture is a single `.cljc` file. Positive fixtures plant ONE retired
    shape (expected=1); negative fixtures exercise the sanctioned counterparts
    that MUST stay green (expected=0). Fixtures live under a `negative`/`positive`
    dir; we scan with --include-tests semantics (direct-file mode) so the
    test-dir exclusion does not hide them.

    A second phase scans `_SANCTIONED_PROSE_MENTIONS` — verbatim retirement
    notes from trees this gate does not scan, where no masking applies — as raw
    lines, so the shape scoping is proven independently of the masking.
    """
    cases: list[tuple[str, int]] = [
        # (fixture-file relative to fixture-root, expected finding count)
        # --- positives: each retired shape must FIRE ---
        ("positive/get_coeffect_frame.cljc",        1),
        ("positive/frame_of_coeffects.cljc",        1),
        ("positive/get_in_coeffects_frame.cljc",    1),
        ("positive/redirect_url_key.cljc",          1),
        ("positive/safe_redirect_to_key.cljc",      1),
        ("positive/redirect_url_multiline.cljc",    1),
        # EP-0037 R5 `:query-retain` — the three USE shapes row 9 enumerates.
        ("positive/query_retain_reg_route_meta.cljc",     1),
        ("positive/query_retain_accepted_key_roster.cljc", 1),
        ("positive/query_retain_promotion_vocabulary.cljc", 1),
        # --- negatives: every sanctioned counterpart must stay GREEN ---
        ("negative/public_frame_opt.cljc",          0),
        ("negative/frame_trace_tag.cljc",           0),
        ("negative/fx_handler_ctx_frame.cljc",      0),
        ("negative/rf_frame_id_coeffect.cljc",      0),
        ("negative/client_navigate_url.cljc",       0),
        ("negative/redirect_location_key.cljc",     0),
        ("negative/frame_in_comment.cljc",          0),
        ("negative/frame_in_docstring.cljc",        0),
        ("negative/retired_keys_def.cljc",          0),
        # EP-0037 R5 `:query-retain` — every sanctioned in-tree mention names
        # the key as RETIRED; none of them may fire the rule.
        ("negative/query_retain_sanctioned_mentions.cljc", 0),
        ("negative/query_retain_lookalikes.cljc",   0),
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
        # Direct-file scan (the fixture IS the surface).
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

    # Phase 2: the out-of-surface sanctioned prose mentions, scanned raw.
    for origin, line in _SANCTIONED_PROSE_MENTIONS:
        got = len(_scan_text(Path(origin), line))
        if got == 0:
            if verbose:
                sys.stderr.write(f"self-test PASS: prose mention in {origin}\n")
        else:
            sys.stderr.write(
                f"self-test FAIL: sanctioned prose mention in {origin} fired "
                f"{got} finding(s):\n      {line}\n"
            )
            failures += 1

    if failures:
        sys.stderr.write(f"\n{failures} self-test failure(s).\n")
        return 1
    if verbose:
        total = len(cases) + len(_SANCTIONED_PROSE_MENTIONS)
        sys.stderr.write(f"all {total} self-tests passed.\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
