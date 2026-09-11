#!/usr/bin/env python3
"""rf2-7ntc — SSR prop-drop roster drift gate.

`strip-prop?` in `implementation/ssr/src/re_frame/ssr/html_helpers.cljc` is the
SINGLE realisation point for which props are dropped at SSR static-markup
emission. Two spec pages enumerate that roster in prose, and prose enumerations
go stale silently — nothing errors, and a SHORT roster reads exactly like a
complete one. This gate makes that drift fail CI.

Same shape as `scripts/check_keyword_catalogue_drift.py` (which is itself the
api-manifest pattern applied to keywords): read a roster out of live source,
require the enumerating documents to carry it.

WHY THIS GATE EXISTS — THE MEASURED RECURRENCE
----------------------------------------------
Four documents enumerate the roster. THREE went stale:

  * `spec/011-SSR.md`             — short from 2026-05-15 to 2026-05-27, ~3.5
                                    months (rf2-2fes)
  * `spec/Security.md`            — the same classes missing (rf2-twyh)
  * the `attr-string` docstring   — written 2026-05-21, short SIX DAYS later
                                    when the JSX class landed 400 lines above
                                    it IN THE SAME FILE (rf2-twyh)
  * `strip-prop?`'s own docstring — the only one that stayed correct

The repairs are done. This gate exists to stop the RECURRENCE, and the reason it
clears the bar an ordinary prose-drift gate would not is WHAT the roster is: the
names are `__proto__` / `constructor` / `prototype` and
`dangerouslySetInnerHTML`, and one of the two graded pages is
`spec/Security.md`. A short roster there is a security document understating a
security control.

WHAT IT CHECKS
--------------
Every literal member of every name set `strip-prop?` reads must appear, as a
WORD, on each of the two enumerating spec pages. The page list below is the
gate's only configuration.

The name sets are DERIVED, not listed here: the `(contains? <set> …)` arms of
`strip-prop?`'s own body name them, and each named set's `def ^:private <set>
#{…}` form supplies its members. So a fifth name set landing in `strip-prop?`
is graded on arrival rather than silently ignored — which is precisely how the
`attr-string` gloss went short six days after it was written. There is no
roster in this script to go stale; if the source shape moves, the parse fails
CLOSED (exit 2) rather than grading a subset in silence.

TWO LIMITS ON WHAT A GREEN MEANS. BOTH ARE REAL; READ THEM.
------------------------------------------------------------
A gate whose green is over-read is worse than no gate, so the floor is stated
here and PINNED by the self-tests rather than left as a claim.

  1. IT GRADES FOUR OF THE SIX CLASSES. `strip-prop?` is a six-arm `or`: four
     `contains?` calls against literal name sets, plus `(event-handler-name? nm)`
     — a regex plus an allowlist, not an enumerable roster — and `(fn? v)`, a
     type predicate with no name at all. The two unnamed classes are exactly the
     two that were NEVER stale, so this gate covers the part that drifts. But a
     green is not "the enumeration is complete".

  2. WHOLE-FILE MATCHING MEANS A GREEN IS A FLOOR, NOT A CEILING. A green says
     "no literal name is WHOLLY ABSENT from the page", and nothing stronger. It
     does not say the names sit in the roster, or together, or in one list.
     `key`, `ref` and `children` occur on these pages for unrelated reasons —
     `:children` is a key into a structured-children map in 011's path-segment
     addressing section, and `:rf.mcp/ref` is an unrelated MCP structure in
     Security.md — so a name deleted from the roster while such a mention
     survives elsewhere reads here as PRESENT.

     That contamination is why this gate is NOT section-scoped, rather than an
     argument for scoping it (ruled 2026-09-11). Contamination makes the check
     UNDER-REPORT; it can never invent a failure. So the whole-file form is
     sound in one direction — it cannot cry wolf — and at the pre-repair
     revision it went RED ON BOTH PAGES, which is the whole question, because
     those are the two drifts that actually happened. Section scoping buys the
     remaining names at the price of a heading-range parser that must track
     where the roster lives on each page: a second thing that can go stale,
     added to a gate whose entire purpose is catching staleness.

     If a false green on a contaminated name is ever MET IN PRACTICE, that is
     the trigger to add scoping. One incident, not a theory.

WHY A WORD MATCH ON THE BARE NAME, AND NOT THE `:keyword` SPELLING
------------------------------------------------------------------
Measured at trunk, both pages CORRECT: requiring a leading `:` reports
`__proto__`, `constructor` and `prototype` missing from BOTH pages — they are
JS property names and both pages spell them bare. A colon-requiring gate would
therefore cry wolf on two conformant documents on day one, which is the one
thing this gate must never do. The set members are bare JS/React property
names, so the bare name is what is matched; the boundary is
`[A-Za-z0-9_]`, so `` `:key` `` and `` `:ref` `` satisfy `key` / `ref` while
`keyword` and `prefer` do not.

MEASURED, on the two pages this gate grades:

  at a0e482433 (pre-repair)   011 RED  — missing the three JSX names +
                                         dangerouslySetInnerHTML
                              Security RED — those four + children
  at trunk (both repaired)    011 GREEN, Security GREEN — 0 missing

DELIBERATELY OUT OF SCOPE
-------------------------
  * The `attr-string` docstring gloss. Grading it needs a per-site allowance —
    it names `:key`/`:ref`/`:children`/`:dangerouslySetInnerHTML` but says
    "reserved prototype-pollution keys" and "JSX source-coord props" in prose,
    which is the right register for a downstream gloss — and that allowance is
    itself a roster that can go stale. rf2-twyh has already relabelled it a
    GLOSS naming `strip-prop?` as the single realisation point.
  * Any COUNT or structural invariant. Two CORRECT documents already disagree
    on the count: merged 011 has five list items for six classes because it
    groups `on*` and function-valued props into one entry, while
    `spec/Security.md` says "six classes". Both are right. Legitimate grouping
    differences mean only the literal-NAME test is sound.
  * The CLJS-side / conformance-fixture cross-check of the same four name sets.
    Needs its own bead.

Exit code:
    0  no drift (every literal name appears on every enumerating page)
    1  at least one page is missing at least one name
    2  invocation / setup error, INCLUDING a source parse that lost the roster
Python stdlib only.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# --------------------------------------------------------------------------
# Scan surface — the whole of this gate's configuration
# --------------------------------------------------------------------------

_SOURCE = "implementation/ssr/src/re_frame/ssr/html_helpers.cljc"

# The pages that ENUMERATE the roster. The tracked spec sources, never a
# generated `docs/` mirror. Two entries, and that is the lot: the `attr-string`
# gloss is deliberately not here (see §Deliberately out of scope).
_PAGES = (
    "spec/011-SSR.md",
    "spec/Security.md",
)

# --------------------------------------------------------------------------
# Source grammar
# --------------------------------------------------------------------------
#
# All three patterns anchor at COLUMN 0, which is what keeps a commented-out or
# quoted form from being read as the live one: a top-level Clojure form starts
# in column 0 and a `;;` comment line cannot.

# `strip-prop?`'s definition, from its own `(defn` through the line before the
# next top-level form. The body is what names the sets.
_STRIP_PROP_RE = re.compile(
    r"^\(defn\s+strip-prop\?.*?(?=^\()", re.MULTILINE | re.DOTALL
)

# A `(contains? <set-name> …)` arm. Group 1 is the name-set var. Clojure symbol
# chars, minus the `#` and `/` that cannot open one.
_CONTAINS_RE = re.compile(r"\(contains\?\s+([A-Za-z][A-Za-z0-9*+!?<>=_.'-]*)")


def _name_set_def_re(var: str) -> re.Pattern[str]:
    """`(def ^:private <var> #{…})`, group 1 the set body. Tolerant of the set
    sitting on the def line or the next one; anchored at column 0."""
    return re.compile(
        r"^\(def\s+\^:private\s+" + re.escape(var) + r"\s+#\{(.*?)\}\)",
        re.MULTILINE | re.DOTALL,
    )


_STRING_LITERAL_RE = re.compile(r'"([^"]*)"')


class RosterParseError(RuntimeError):
    """The roster could not be read out of live source.

    This gate's population IS the parse, so a parse that finds nothing is not a
    clean run — it is a check that did not run. The sibling keyword gate learnt
    the same lesson under rf2-66czz, where a renamed heading made the checker
    exit 0 having verified nothing. So the parser fails CLOSED: a gate that can
    fail to RUN must exit non-zero when it does not run.

    Every message below names the ONE edit that silences it, because the honest
    fix for a deliberate refactor of `strip-prop?` is to update this script in
    the SAME PR that reshapes the source.
    """


# --------------------------------------------------------------------------
# Extraction
# --------------------------------------------------------------------------


def name_set_vars(source_text: str) -> list[str]:
    """The name-set vars `strip-prop?` reads, in body order and de-duplicated.

    DERIVED FROM THE BODY rather than listed in this script, so a fifth name
    set is graded on arrival. That matters here specifically: the recurrence
    this gate exists to stop is a NEW CLASS landing and the enumerations not
    following — the `attr-string` gloss went short six days after it was
    written, when the JSX class landed 400 lines above it in the same file. A
    hardcoded four-name list in this script would have gone stale the same way,
    silently, and a gate that quietly stops covering is the defect it is
    supposed to catch.

    The two unnamed arms — `(event-handler-name? nm)` and `(fn? v)` — carry no
    `contains?` and so contribute nothing, which is limit 1 in the module
    docstring."""
    body = _STRIP_PROP_RE.search(source_text)
    if body is None:
        raise RosterParseError(
            f"no top-level `(defn strip-prop? …)` form in {_SOURCE} — the roster"
            " cannot be read, so this gate cannot run. If `strip-prop?` was"
            " renamed or moved, update _STRIP_PROP_RE / _SOURCE in this script"
            " IN THE SAME PR."
        )
    seen: list[str] = []
    for var in _CONTAINS_RE.findall(body.group(0)):
        if var not in seen:
            seen.append(var)
    if not seen:
        raise RosterParseError(
            "`strip-prop?` names ZERO `(contains? <set> …)` name sets — the"
            " six-arm `or` no longer has the shape this gate reads, so it would"
            " grade nothing and exit 0. If the predicate was legitimately"
            " restructured, update _CONTAINS_RE in this script IN THE SAME PR."
        )
    return seen


def name_set_members(source_text: str, var: str) -> list[str]:
    """The literal string members of `(def ^:private <var> #{…})`, sorted.

    Raises `RosterParseError` when the form is absent or yields no literals —
    both are the parse losing a set `strip-prop?` demonstrably reads, and
    neither can be reported as a finding, because a finding is computed FROM
    the members."""
    m = _name_set_def_re(var).search(source_text)
    if m is None:
        raise RosterParseError(
            f"`strip-prop?` reads `{var}`, but {_SOURCE} carries no top-level"
            f" `(def ^:private {var} #{{…}})` form. Either the set moved out of"
            " this file, or it stopped being a literal `#{}` of strings; either"
            " way the members cannot be read and this gate would grade a subset"
            " in silence."
        )
    members = _STRING_LITERAL_RE.findall(m.group(1))
    if not members:
        raise RosterParseError(
            f"`(def ^:private {var} #{{…}})` yielded ZERO string literals. An"
            " empty prop-drop roster is never a legitimate reading — it would"
            " grade nothing and exit 0 — so this fails closed."
        )
    return sorted(members)


def roster(source_text: str) -> dict[str, list[str]]:
    """`{name-set-var: [literal members]}` for every set `strip-prop?` reads."""
    return {var: name_set_members(source_text, var) for var in name_set_vars(source_text)}


# --------------------------------------------------------------------------
# The check
# --------------------------------------------------------------------------


def _word_re(name: str) -> re.Pattern[str]:
    """`name` as a WORD, with `[A-Za-z0-9_]` as the word class.

    Chosen over both alternatives on measurement (module docstring §Why a word
    match): a bare SUBSTRING lets `prefer` answer for `ref` and `keyword` for
    `key`, while requiring the `:keyword` spelling cries wolf on `__proto__` /
    `constructor` / `prototype`, which both pages correctly spell bare. The
    boundary admits the punctuation these names actually sit in — `` `:key` ``,
    `:key`, `(:key m)` — and refuses the words they hide inside."""
    return re.compile(
        r"(?<![A-Za-z0-9_])" + re.escape(name) + r"(?![A-Za-z0-9_])"
    )


def missing_from_page(page_text: str, names: list[str]) -> list[str]:
    """The `names` that do not appear as a word anywhere in `page_text`.

    PURE over its two inputs, so the self-tests enter at the layer that holds
    the logic instead of underneath it."""
    return [name for name in names if not _word_re(name).search(page_text)]


def run_checks(repo_root: Path) -> tuple[dict[str, list[str]], dict[str, list[str]]]:
    """`(roster, findings)` — the derived roster, and `{page: [missing names]}`
    for each page that is short. A page with nothing missing does not appear."""
    source_text = (repo_root / _SOURCE).read_text(encoding="utf-8")
    sets = roster(source_text)
    every_name = sorted({n for members in sets.values() for n in members})

    findings: dict[str, list[str]] = {}
    for page in _PAGES:
        missing = missing_from_page(
            (repo_root / page).read_text(encoding="utf-8"), every_name
        )
        if missing:
            findings[page] = missing
    return sets, findings


# --------------------------------------------------------------------------
# Reporting
# --------------------------------------------------------------------------

_FIX = (
    "Each name above is a literal member of a name set `strip-prop?` reads, and\n"
    "  it appears NOWHERE on the page listed. `strip-prop?` is the SINGLE\n"
    "  realisation point for SSR prop dropping, and these pages enumerate it in\n"
    "  prose; a page missing a name is a document understating a live control —\n"
    "  and on spec/Security.md that is a security document understating a\n"
    "  security control. Two honest fixes, and no third:\n"
    "    * the class is REAL and the page is STALE — add the name to that page's\n"
    "      roster, in the SAME PR as the source change that introduced it. This\n"
    "      is the drift rf2-2fes / rf2-twyh repaired and the recurrence this\n"
    "      gate exists to stop.\n"
    "    * the class was REMOVED from `strip-prop?` — then it is not in the\n"
    "      derived roster either, so this check cannot be what is red. Re-read\n"
    "      the finding.\n"
    "  Do NOT silence this by adding the name somewhere else on the page: the\n"
    "  check is whole-file and would go green, but the enumeration the reader\n"
    "  actually consults would still be short. Put it in the roster."
)


def report(findings: dict[str, list[str]]) -> None:
    sys.stderr.write(
        f"\n{len(findings)} enumerating page(s) missing at least one literal "
        "prop-drop name:\n\n"
    )
    for page in sorted(findings):
        sys.stderr.write(f"  {page}\n      missing: {', '.join(findings[page])}\n")
    sys.stderr.write(f"\nFix:\n  {_FIX}\n")


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "rf2-7ntc: fail when a spec page that enumerates the SSR prop-drop "
            "roster is missing a literal name `strip-prop?` actually drops."
        ),
    )
    parser.add_argument(
        "--repo-root",
        default=None,
        help="Repo root. Defaults to the script's grandparent.",
    )
    parser.add_argument(
        "--verbose", "-v", action="store_true", help="Print scan summary."
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="Run in-memory teeth self-tests (prove the check fires) and exit.",
    )
    args = parser.parse_args(argv)

    if args.self_test:
        return _run_self_tests(verbose=args.verbose)

    repo_root = (
        Path(args.repo_root).resolve()
        if args.repo_root
        else Path(__file__).resolve().parent.parent
    )
    if not (repo_root / "mkdocs.yml").is_file():
        sys.stderr.write(
            f"error: {repo_root} is not the re-frame2 repo root (no mkdocs.yml).\n"
        )
        return 2
    for rel in (_SOURCE, *_PAGES):
        if not (repo_root / rel).is_file():
            sys.stderr.write(f"error: expected {rel} under {repo_root}.\n")
            return 2

    # A lost roster parse is a SETUP error, not a clean run. Exit 2 keeps it
    # distinguishable from a drift finding while still failing CI, and it is
    # raised BEFORE any verdict, so a gate that could not read its own
    # population never gets to print one.
    try:
        sets, findings = run_checks(repo_root)
    except RosterParseError as exc:
        sys.stderr.write(f"error: {exc}\n")
        return 2

    if args.verbose:
        summary = "; ".join(f"{var} ({len(m)})" for var, m in sets.items())
        total = len({n for m in sets.values() for n in m})
        sys.stderr.write(
            f"derived {len(sets)} name set(s) from `strip-prop?`: {summary} — "
            f"{total} literal name(s) required on each of {len(_PAGES)} page(s). "
            "NOTE: `strip-prop?`'s other two arms (`event-handler-name?`, `fn?`) "
            "carry no name set and are NOT graded; a green here is a floor.\n"
        )
    if findings:
        report(findings)
        return 1
    if args.verbose:
        sys.stderr.write(
            "SSR prop-drop roster clean: every literal name appears on every "
            "enumerating page.\n"
        )
    return 0


# --------------------------------------------------------------------------
# Self-tests — prove the check FIRES on an injected violation and stays green
# on the conformant counterpart, and that the parse fails CLOSED on each shape
# that could lose the roster. In-memory (no fixture files).
# --------------------------------------------------------------------------


# A miniature of the live source: the four-arm `contains?` shape, the two arms
# that carry no name set, a commented-out decoy def at column 0's left, and one
# `def ^:private` set that `strip-prop?` does NOT read (so the derivation is
# shown to take its population from the BODY rather than from every set in the
# file).
_SYNTHETIC_SOURCE = '''\
;; A commented-out decoy. Column-0 anchoring must keep this out of the roster.
;; (def ^:private decoy-names
;;   #{"decoyName"})

(def ^:private reserved-prop-keys
  #{"__proto__" "constructor" "prototype"})

(def ^:private jsx-source-prop-names
  #{"_jsxFileName" "_jsxLineNumber" "_jsxColumnNumber"})

(def ^:private structural-slot-names
  #{"key" "ref"})

(def ^:private content-channel-names
  #{"children" "dangerouslySetInnerHTML"})

(def ^:private unrelated-style-names
  #{"flexGrow"})

(defn strip-prop?
  "Docstring prose naming `structural-slot-names` and `content-channel-names`
  without calling them, plus a mention of unrelated-style-names."
  [[k v]]
  (let [nm (name k)]
    (or (event-handler-name? nm)
        (fn? v)
        (contains? reserved-prop-keys (str/lower-case nm))
        (contains? jsx-source-prop-names nm)
        (contains? structural-slot-names nm)
        (contains? content-channel-names nm))))

(defn- next-top-level-form [x] x)
'''


def _run_self_tests(verbose: bool = False) -> int:
    failures = 0

    def expect(name: str, cond: bool) -> None:
        nonlocal failures
        if cond:
            if verbose:
                sys.stderr.write(f"self-test PASS: {name}\n")
        else:
            sys.stderr.write(f"self-test FAIL: {name}\n")
            failures += 1

    # ---- extraction ------------------------------------------------------
    sets = roster(_SYNTHETIC_SOURCE)
    expect(
        "extract: the four name sets come from strip-prop?'s BODY, in body order",
        list(sets) == [
            "reserved-prop-keys",
            "jsx-source-prop-names",
            "structural-slot-names",
            "content-channel-names",
        ],
    )
    expect(
        "extract: all ten literal members",
        sorted(n for m in sets.values() for n in m) == sorted([
            "__proto__", "constructor", "prototype",
            "_jsxFileName", "_jsxLineNumber", "_jsxColumnNumber",
            "key", "ref",
            "children", "dangerouslySetInnerHTML",
        ]),
    )
    # The derivation's population is the BODY, not the file: a `def ^:private`
    # set `strip-prop?` never reads contributes nothing, so an unrelated roster
    # in the same file cannot silently widen what the spec pages must carry.
    expect(
        "extract: a name set strip-prop? does NOT read is not in the roster",
        "unrelated-style-names" not in sets
        and "flexGrow" not in {n for m in sets.values() for n in m},
    )
    # Column-0 anchoring, in the direction that matters: a commented-out def is
    # not a live one.
    expect(
        "extract: a commented-out def at column 0's left does not contribute",
        "decoyName" not in {n for m in sets.values() for n in m},
    )
    # Limit 1, pinned rather than merely claimed: the two arms with no name set
    # are invisible to this gate by construction.
    expect(
        "extract: the two UNNAMED arms contribute nothing (limit 1 is real)",
        not {"event-handler-name?", "fn?"} & set(sets),
    )

    # ---- the parse fails CLOSED -----------------------------------------
    #
    # Each shape below is a way the roster can be LOST. A gate whose population
    # can silently collapse to zero is a gate that can fail to RUN while
    # exiting 0 — the rf2-66czz shape, one gate over.
    def parse_raises(text: str) -> bool:
        try:
            roster(text)
            return False
        except RosterParseError:
            return True

    expect("closed: the valid source parses (the control)",
           not parse_raises(_SYNTHETIC_SOURCE))
    expect("closed: a RENAMED strip-prop? fails closed",
           parse_raises(_SYNTHETIC_SOURCE.replace("(defn strip-prop?",
                                                  "(defn strip-prop-2?")))
    expect("closed: a body with no `contains?` arms fails closed",
           parse_raises(_SYNTHETIC_SOURCE.replace("(contains? ", "(member-of? ")))
    expect("closed: a name set whose def form is GONE fails closed",
           parse_raises(_SYNTHETIC_SOURCE.replace(
               '(def ^:private structural-slot-names\n  #{"key" "ref"})', "")))
    expect("closed: a name set that stopped being ^:private fails closed",
           parse_raises(_SYNTHETIC_SOURCE.replace(
               "(def ^:private structural-slot-names", "(def structural-slot-names")))
    expect("closed: an EMPTIED name set fails closed",
           parse_raises(_SYNTHETIC_SOURCE.replace('#{"key" "ref"}', "#{}")))
    # …and the rule does not over-reach: a FIFTH name set is graded on arrival,
    # which is the whole reason the roster is derived rather than listed here.
    fifth = _SYNTHETIC_SOURCE.replace(
        "(contains? content-channel-names nm)",
        "(contains? content-channel-names nm)\n        (contains? newly-landed-names nm)",
    ).replace(
        "(defn strip-prop?",
        '(def ^:private newly-landed-names\n  #{"newlyLandedProp"})\n\n(defn strip-prop?',
    )
    expect("closed: a FIFTH name set is picked up, not silently ignored",
           "newlyLandedProp" in {n for m in roster(fifth).values() for n in m})

    # ---- the page check: teeth, and the conformant counterpart -----------
    every_name = sorted(n for m in sets.values() for n in m)

    conformant_page = (
        "## Prop-drop roster at static-markup emission\n"
        "\n"
        "`strip-prop?` drops six classes. Reserved prototype-pollution keys\n"
        "(`__proto__`, `constructor`, `prototype`); JSX source-coord props\n"
        "(`:_jsxFileName`, `:_jsxLineNumber`, `:_jsxColumnNumber`); React's\n"
        "structural slots (`:key`, `:ref`); React's content channels\n"
        "(`:children`, `:dangerouslySetInnerHTML`); `on*` event-handler props;\n"
        "and function-valued props.\n"
    )
    expect("page: a conformant page passes",
           missing_from_page(conformant_page, every_name) == [])

    # The rf2-2fes drift, reproduced: the JSX class landed and the page did not
    # follow. This is the shape that was live on spec/011-SSR.md for ~3.5
    # months, and it must be RED.
    stale_page = (
        conformant_page
        .replace("`:_jsxFileName`, `:_jsxLineNumber`, `:_jsxColumnNumber`", "…")
        .replace("(`:children`, `:dangerouslySetInnerHTML`)", "(`:children`)")
    )
    expect("page: the rf2-2fes stale shape FIRES, naming exactly what is missing",
           missing_from_page(stale_page, every_name)
           == ["_jsxColumnNumber", "_jsxFileName", "_jsxLineNumber",
               "dangerouslySetInnerHTML"])
    expect("page: a page missing ONE name fires on exactly that name",
           missing_from_page(conformant_page.replace("`__proto__`, ", ""),
                             every_name) == ["__proto__"])
    expect("page: an EMPTY page fires on every name",
           missing_from_page("", every_name) == every_name)

    # ---- the word boundary, in both directions ---------------------------
    #
    # It must ADMIT the punctuation these names sit in and REFUSE the words
    # they hide inside. Each case below is a spelling that actually occurs.
    for spelling in ("`:key`", ":key", "(:key m)", "{:key 1}", "key", '"key"',
                     "`key`", "- `:key` —"):
        expect(f"boundary: admits {spelling!r}",
               missing_from_page(spelling, ["key"]) == [])
    for decoy in ("keyword", "monkey", "keys", "KEY"):
        expect(f"boundary: {decoy!r} does NOT answer for `key`",
               missing_from_page(decoy, ["key"]) == ["key"])
    expect("boundary: `prefer`/`reference` do NOT answer for `ref`",
           missing_from_page("prefer a reference", ["ref"]) == ["ref"])
    expect("boundary: matching is case-SENSITIVE, as strip-prop? is for these",
           missing_from_page("dangerouslysetinnerhtml",
                             ["dangerouslySetInnerHTML"])
           == ["dangerouslySetInnerHTML"])
    expect("boundary: a leading-underscore name is admitted after `:`",
           missing_from_page("`:_jsxFileName`", ["_jsxFileName"]) == [])
    expect("boundary: `_jsxFileNameX` does NOT answer for `_jsxFileName`",
           missing_from_page("_jsxFileNameX", ["_jsxFileName"]) == ["_jsxFileName"])

    # ---- LIMIT 2, pinned: contamination reads as PRESENT ------------------
    #
    # This is the floor, and it is asserted here so the docstring's statement of
    # it is a tested property rather than a claim. Both decoys below are REAL
    # text from the graded pages: `:children` as a key into 011's
    # structured-children map, and `:rf.mcp/ref` in Security.md. A page carrying
    # only these, with no roster at all, is GREEN for those two names.
    #
    # A test that pins a LIMIT is easy to misread as endorsing it, so: this is
    # the gate UNDER-REPORTING, which is the direction that never cries wolf.
    # The day a false green on a contaminated name is met in practice is the day
    # to add section scoping — and this case is where its absence is recorded.
    expect("limit 2: unrelated `:children` on the page reads as PRESENT (floor)",
           missing_from_page("the `:children` key of the structured-children map",
                             ["children"]) == [])
    expect("limit 2: unrelated `:rf.mcp/ref` on the page reads as PRESENT (floor)",
           missing_from_page("the `:rf.mcp/dedup-table` + `:rf.mcp/ref` structure",
                             ["ref"]) == [])
    # …and the floor is a floor, not a hole: the names that CANNOT be
    # contaminated — the ones whose absence is the security-relevant case —
    # still fire on the very same page.
    expect("limit 2: the uncontaminatable names still FIRE on that page",
           missing_from_page(
               "the `:children` key of the map, and `:rf.mcp/ref`",
               ["__proto__", "_jsxFileName", "dangerouslySetInnerHTML"])
           == ["__proto__", "_jsxFileName", "dangerouslySetInnerHTML"])

    if failures:
        sys.stderr.write(f"\n{failures} self-test failure(s).\n")
        return 1
    if verbose:
        sys.stderr.write("all self-tests passed.\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
