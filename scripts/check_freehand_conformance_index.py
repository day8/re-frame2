#!/usr/bin/env python3
"""Validate the Freehand conformance index — ids, citations, fixtures, sections,
and the census that says every active row is actually PROVEN where it claims.

The Freehand view substrate proves its two-mode / multi-host parity row by row.
Each law carries a permanent `FH-<AREA>-<NNN>` id, and the index at
`spec/conformance/freehand/conformance-index.md` binds that id to the canonical
spec paragraph that OWNS the law, the modes and hosts it binds, the fixture that
proves it, and its status.  The scheme, the allocation rule, and the column
grammar are documented in `spec/conformance/freehand/README.md`.

Two independent guards live here, and the difference between them matters:

    * The STRUCTURAL check (`check`) validates the index as a document — that
      every id is well-formed, unique, filed under its own area, cites a real
      spec anchor, and names a fixture file that exists.
    * The EXECUTION CENSUS (`census`) reconciles the index against the suites
      that RUN it — that every active row's fixture is reached by an ASSERTION,
      that the assertions reaching it run in lanes serving every mode/host cell
      the row claims, and that a row claiming the `compiled` mode is proven
      through the compiled tier rather than merely labelled with it.

Neither executes a fixture; running a law is the harness's job
(`spec/conformance/freehand/README.md` §What this is not).  What the census adds
is the missing edge between the ledger and the runs: without it a row can name a
real fixture nobody ever reads, and the structural check will call that green.

The census reads FORMS, not characters, and the distinction is the whole of its
credibility.  A scan of raw source counts a commented-out proof, a `#_`-discarded
one, an id written in a docstring, and a `def` no test ever reads — four ways to
delete a law's proof while leaving its row standing.  It also has to read the
reader: a `#?(:clj (deftest …))` in a `.cljc` suite is discovered by both runners
and asserts in only one, so taking the lane from the filename credits the row
with a lane it never enters.

Reading forms is necessary and it is not sufficient, which two merged-PR audits
established the hard way.  A form-aware scan that seeds from the whole `deftest`
still counts `(comment fx)` written beside the assertions, and still walks to a
fixture down a helper's `(when false …)` branch — proofs by co-location, in code
nothing evaluates.  So reachability starts at the ASSERTING STATEMENTS of a test
body, not at the test.  And the lane cannot see the mode: `interpreted jvm` and
`compiled jvm` are the same lane, so a row relabelled from one to the other kept
every cell served and every claim unchecked.  `compiled` is the mode a
declaration opts into, so it is the mode that can be witnessed.

Then PRESENCE is not proof either, which was the second audit.  A witness read
off the FILE — a `:require` at the top, a declaration anywhere in it — vouches
for assertions that touch neither, so the compile-tier control could keep its
`[… .compiler.check :as check]` line, assert on the raw fixture instead, and
still claim the compiled mode.  And a helper is not a bare word: reading
`sup/expect-tree` as `expect-tree` and looking it up in one global pool let a
stranger's `is` vouch for this suite's own non-asserting helper of the same name,
and let a `#?(:clj (is …) :cljs x)` helper be credited in a node lane that runs
the other arm.  So a name resolves in the NAMESPACE that wrote it, read as ONE
PLATFORM at a time, and both the fixture and the mode witness are whatever the
asserting statement REACHES through those names.

And a NAME is not any run of symbol characters, which was the third audit and the
last of the class.  The scan started wherever a symbol character appeared, so it
read the tails of tokens rather than tokens: a keyword is a symbol run behind a
`:`, so `(is (= :panel (first fx)))` yielded `panel`, and a
`(v/defview panel {:compiled true} …)` in scope made that DATUM witness a
compiled tier the assertion never entered.  The same promotion let `:fx` prove a
row bound to `fx`, and `:check/findings` stand in for the compile-tier alias.  A
keyword is data and a Var reference is a symbol, so a symbol is read from a TOKEN
BOUNDARY — the character before it decides — and numeric literals stop yielding
names with it.  Every reading here is self-tested with the DEFECT KIND pinned, not
just the count, and every green fixture is falsified by removing the thing that
makes it green.

An id is an address.  An address that resolves to nothing is worse than no
address at all, so this guard fails the build when:

    * ILL-FORMED ID        — the id is not `FH-<AREA>-<NNN>` with a roster AREA
                             and a three-digit ordinal.
    * DUPLICATE ID         — the same id appears on two rows (an id is a
                             permanent citation; two rows make it ambiguous).
    * AREA MISMATCH        — the row's AREA is not the section it sits in.
    * OUT-OF-ORDER ID      — ordinals within a section are dense and ascending;
                             appending out of order means the allocation rule was
                             not followed and a collision is one merge away.
    * GAP IN AREA          — the other half of that rule, and the half that was
                             documented but never enforced: ordinals are DENSE.
                             A gap means an id was deleted where the convention
                             is to retire it, and the id is now free to be
                             re-allocated to a different law.
    * MISSING CITATION     — the canonical-paragraph cell is not a markdown link
                             with an anchor.
    * BROKEN CITATION      — the cited spec file does not exist, the anchor does
                             not resolve, or the target is not under `spec/`.
    * MISSING FIXTURE      — an `active` row names a fixture file that does not
                             exist, or names none at all.
    * MISPLACED FIXTURE    — a `planned` / `retired` row names a fixture.
    * BAD APPLICABILITY    — an unknown token, no mode token or two, or no host.
    * BAD STATUS           — a status outside the closed vocabulary.
    * BAD ROW              — a table row whose column count is not six.
    * ORPHAN ROW           — a row before the first area section.
    * BAD TABLE HEADER     — a section's table header is not the six columns.
    * UNKNOWN / DUPLICATE / MISSING AREA SECTION — the section roster no longer
                             matches the area roster.

and, from the execution census:

    * UNPROVEN ROW         — an `active` row whose fixture no test in
                             `implementation/freehand/test/` reads.  The row
                             names a file, the file exists, and nothing asserts
                             against it: a law proven by nobody.
    * DEAD PROOF SITE      — the id IS written in a suite, and no ASSERTION
                             reaches it: commented out, `#_`-discarded, sitting
                             in a docstring, bound to a name nothing asserts on,
                             read only from a statement that asserts nothing, or
                             read through a helper that asserts in some OTHER
                             namespace and not in the one that wrote the call.
                             The shape a text scan calls proof.
    * UNWITNESSED MODE     — an `active` row claims the `compiled` mode on a
                             host, and no assertion proving it REACHES the
                             compiled tier in a lane that serves that cell.  The
                             lane axis cannot see the mode axis — the JVM lane
                             serves `interpreted jvm` and `compiled jvm` alike —
                             so without a witness the mode token is parsed and
                             never evidenced; a witness merely PRESENT in the
                             file evidences nothing about the assertion beside
                             it, and one in a reader branch the cell never reads
                             evidences nothing about that cell.
    * UNEXECUTED CELL      — an `active` row claims a (mode, host) cell that no
                             lane among its ASSERTING tests serves.  A
                             `common jvm browser` row read only from a `.cljs`
                             suite is a claim about the JVM the JVM never sees;
                             so is one whose only assertion sits in a
                             `#?(:clj …)` arm — or behind a helper whose `is`
                             sits in one — which never enters the node lane that
                             proves the browser column's structural cell.
    * DANGLING PROOF       — a test reads a fixture for an id the index does not
                             carry as an `active` row (a deleted or retired law
                             whose suite outlived it).
    * ORPHAN FIXTURE       — a fixture file on disk no `active` row names.  A
                             deletion that removes the row but leaves the bytes
                             is how a resurrected id acquires a stale meaning.
    * EMPTY AREA           — a roster area carrying no `active` row.  The area
                             roster is a claim that the substrate has laws in all
                             fifteen; an area with none means the law was never
                             written, and an index of empty sections satisfies
                             every structural rule above trivially.
    * DANGLING CITATION    — an `FH-…` id cited somewhere under `spec/` that the
                             index carries no row for, at any status.  This is
                             the one detector for a TOP-of-area deletion: it
                             leaves no ordinal gap, so nothing in the index can
                             see it, and the surviving citations are the only
                             evidence the id was ever allocated.

Every defect names the offending row (its id, or its raw first cell when the id
itself is the defect) and its line number.

Exit code:
    0  the index is valid
    1  at least one defect
    2  invocation / setup error

Anchor resolution reuses the slug index from `check_doc_slugs.py` rather than
re-deriving it, so a citation resolves under exactly the rule that governs every
other link in the corpus — one slugifier, one authority.  That import is the
script's only dependency beyond the stdlib (it pulls in pymdown-extensions,
already pinned in requirements.txt).
"""

from __future__ import annotations

import argparse
import functools
import re
import sys
import tempfile
from pathlib import Path
from typing import NamedTuple

# One slugifier for the whole corpus (see module docstring).  check_doc_slugs
# lives beside this file and exits(2) itself if pymdown-extensions is absent.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_doc_slugs import _slug_index  # noqa: E402

# One reader for shadow-cljs build configuration, borrowed from the gate whose
# whole thesis is that a lane roster is READ and never listed
# (check_test_lane_bijection.py, "THE LANES ARE READ, NEVER LISTED").  This
# census consults exactly two of those builds — the ones that schedule its CLJS
# lanes — and a second EDN reader written here to do it would be the drift class
# both gates exist to close.
from check_test_lane_bijection import (  # noqa: E402
    SHADOW_DEFAULT_NS_REGEXP,
    match_delimiter,
    read_map_entries,
    strip_edn_comments,
    unescape_edn_string,
)

INDEX_REL = Path("spec/conformance/freehand/conformance-index.md")
FIXTURES_REL = Path("spec/conformance/freehand/fixtures")
TESTS_REL = Path("implementation/freehand/test")
SHADOW_CLJS_REL = Path("implementation/shadow-cljs.edn")

# This script's own checkout.  The build configuration is a fact about the repo
# that DEFINES the lanes, not about whatever tree a `--repo-root` names, and the
# census self-test runs against synthetic trees that carry no build at all.
_SCRIPT_REPO = Path(__file__).resolve().parent.parent

# The area roster, in index order.  Closed at any given moment — an id or a
# section naming an area that is not here fails — but the programme EP
# introduces the roster with "Areas include ...", so it is extended by a
# recorded ruling in the change that needs it, never by an author reaching for
# a token mid-allocation.  Keep in step with the table in
# spec/conformance/freehand/README.md.
AREAS: tuple[str, ...] = (
    "CALL",
    "PROPS",
    "EVENT",
    "INPUT",
    "SUB",
    "CTRL",
    "PRESENCE",
    "TOPLAYER",
    "BEHAVIOR",
    "REACT",
    "ERROR",
    "ROOT",
    # Routing link.  The programme's own parity tables treat the routing link
    # as its own surface; it is not an EVENT law wearing a different hat.
    "ROUTELINK",
    "STRUCT",
    "DIAG",
)

COLUMNS: tuple[str, ...] = (
    "Id",
    "Law",
    "Canonical paragraph",
    "Applicability",
    "Fixture",
    "Status",
)

STATUSES = frozenset({"planned", "active", "retired"})

# Applicability is two axes: exactly one mode token, one or more host tokens.
MODE_TOKENS = frozenset({"common", "interpreted", "compiled"})
HOST_TOKENS = frozenset({"jvm", "browser", "ssr"})
_QUALIFIED_HOST_RE = re.compile(r"^host:[a-z0-9]+(?:-[a-z0-9]+)*$")

# The em dash is the "no fixture" cell.  Nothing else stands in for it.
NO_FIXTURE = "—"

_ID_RE = re.compile(r"^FH-([A-Z]+)-(\d{3})$")
# `### FH-CALL — Calls`; the token is what binds, the prose name is for readers.
_SECTION_RE = re.compile(r"^###\s+FH-([A-Z]+)\b")
_FENCE_RE = re.compile(r"^\s*(```|~~~)")
_LINK_RE = re.compile(r"^\[[^\]]+\]\(([^)\s]+)\)$")
_SEPARATOR_CELL_RE = re.compile(r"^:?-{3,}:?$")

# --------------------------------------------------------------------------
# The execution census
# --------------------------------------------------------------------------
#
# A proof site is a call to the fixture-loading macro,
# `re-frame.freehand.conformance/fixture`, under any alias:
#
#     (def props-001 (conf/fixture :FH-PROPS-001))
#     [(conformance/fixture :FH-STRUCT-001) ...]
#
# Reading the SITE rather than a hand-kept manifest is the whole point: the
# manifest is DERIVED, so it cannot drift from the tests, and a second copy of
# the mapping — which is what a hand-kept manifest is — cannot go stale because
# it does not exist.
#
# But a site is not a proof.  What makes a row proven is that an ASSERTION runs
# against the fixture, and twelve shapes are indistinguishable from a proof to a
# scan that stops short of the assertions:
#
#     ;; (conf/fixture :FH-CALL-001)              a comment
#     #_(conf/fixture :FH-CALL-001)               a reader-discarded form
#     (def unused (conf/fixture :FH-CALL-001))    a def no test ever reads
#     "see (conf/fixture :FH-CALL-001)"           a docstring
#     (deftest t (is true) (comment fx))          a comment FORM, inside a test
#     (deftest t (is true) (helper))              a helper whose read is in a
#                                                 branch that never runs
#     (defn helper [x] (identity x))              a helper that asserts nothing,
#     (deftest t (helper fx))                     sharing a NAME with one that
#                                                 does, in another namespace
#     (defn expect [x] #?(:clj (is …) :cljs x))   a helper that asserts on ONE
#     (deftest t (expect fx))                     platform, credited on both
#     (def fx (conf/fixture :FH-CALL-001))        a KEYWORD spelled like the
#     (deftest t (is (= :fx 1)))                  binding, read as the Var
#     (def fx (conf/fixture :FH-CALL-001))        the binding QUOTED — the token,
#     (deftest t (is (= `fx 1)))                  and not a reference to it
#     (def fx (conf/fixture :FH-CALL-001))        the quoted datum ANNOTATED, so
#     (deftest t (is (vector? '^{:a 1} [fx])))    the quote's EXTENT was miscounted
#     (def fx (conf/fixture :FH-CALL-001))        the quoted datum TAGGED — the
#     (deftest t (is (= '#js [fx] 1)))            same extent, one prefix along
#
# The first three are the false greens the first merged-PR audit found; the
# fourth was the same mistake waiting; the next two are what the SECOND audit
# found still standing after the first fix, because reading forms is not the same
# as reading assertions; the next two are the THIRD, because reading assertions is
# not the same as reading them in the namespace and on the platform that has them;
# the ninth is the FOURTH, because a name is not any run of symbol characters —
# `:fx` was read as `fx`, and a datum stood in for a Var; the tenth is the FIFTH,
# because a token is not a reference either — `` `fx `` IS the token `fx`, and
# quoted data is data; the eleventh is the SIXTH, because knowing WHICH datum is
# quoted still leaves HOW FAR it reaches, and `^` reaches over two datums rather
# than one; and the last is the SEVENTH, because `^` was never the only prefix of
# that shape — `#tag datum` is two datums as well, and `#js` is the one a CLJS
# author writes daily.  Each one lets a law be retired by deletion — comment out
# the proof, keep the line — with the gate still calling the row proven.
#
# So the scan reads FORMS, then the ASSERTIONS, then the NAMES — and it reads a
# name from a TOKEN BOUNDARY, in an EVALUATED position.  Three reductions come
# first, one per question, and every reading downstream is taken off the same
# reduced text so that none of them can drift from another:
#
#     _strip      what is not code          comments, strings, character
#                                           literals, `#_` discards, and
#                                           `(comment …)` at any depth
#     _read_as    what THIS PLATFORM reads  `#?` / `#?@` reduced to one branch
#     _evaluated  what EVALUATES            every quoted datum — `'x`, `` `x ``,
#                                           `(quote x)` — with `~x` islands
#                                           inside a syntax quote recovered
#
# All three ask `_datum_end` how far a datum reaches, so its arithmetic is the one
# place a mistake reaches all of them: `#_` discards a datum, a reader conditional
# pairs its arms as datums, and a quote blanks one.  Two shapes get that arithmetic
# wrong if they are filed with the one-datum prefixes, because each consumes a
# datum AND the datum after it: `^`, which takes its metadata and then the target
# that metadata annotates, and `#tag`, which takes the tag and then the datum the
# tag reads — see `_META_PREFIX` and `_TAG_DISPATCH_RE`.
#
# Then `_top_level_forms` splits what survives on balanced parens; `_SYMBOL_RE`
# matches a symbol only where one STARTS, so a keyword stays data; `_read_tree`
# binds every definition to the namespace that wrote it, once per platform; and a
# site counts only when an ASSERTING STATEMENT of a `deftest` reaches it — written
# in one, or bound to a name one uses, directly or through a helper it calls, with
# "the same name" meaning the same binding rather than the same spelling.  A
# statement that asserts nothing contributes nothing, and a file carrying no
# `deftest` proves nothing, whatever its name.
_FIXTURE_CALL_RE = re.compile(
    r"\(\s*(?:[A-Za-z0-9.*+!_'?<>=-]+/)?fixture\s+(:FH-[A-Z]+-\d{3})\b"
)
# `(def x …)`, `(defn- helper …)`, `(v/defview panel …)` — the top-level forms
# that BIND a name, so a fixture read in one is reachable from a test that names
# it.  The head is any `def…` macro, qualified or not, because a Freehand
# declaration is bound by `v/defview` and a proof that reaches THAT is what
# witnesses the compiled mode.  `deftest` is excluded: it is a test, not a
# binding a test reads.  The optional `^:private` / `^{:doc …}` metadata sits
# between the head and the name.
_DEFINER_RE = re.compile(
    r"^\(\s*(?:[A-Za-z0-9.*+!_'?<>=-]+/)?def(?!test\b)[A-Za-z0-9*+!_'?<>=-]*\s+"
    r"(?:\^:?[A-Za-z0-9*+!_'?<>=.:/-]+\s+|\^\{[^}]*\}\s+)*"
    r"([A-Za-z0-9*+!_'?<>=.<>-]+)"
)
_DEFTEST_RE = re.compile(r"^\(\s*deftest\b")
# `(comment …)`, at any depth.  The lookahead is what keeps `(commentary …)`
# from being read as a comment.
_COMMENT_FORM_RE = re.compile(r"\(\s*(?:clojure\.core/)?comment(?=[\s()\[\]{}])")
# clojure.test's assertion forms.  `is` is the whole vocabulary the Freehand
# suites use; `are` is here because it is the other one clojure.test ships, and a
# suite reaching for it should not be read as asserting nothing.
_ASSERTION_HEAD_RE = re.compile(
    r"\(\s*(?:[A-Za-z0-9.*+!_'?<>=-]+/)?(?:is|are)(?=[\s()\[\]{}])"
)
# The ns form.  A name is resolved in the namespace that WROTE it, so the ns
# form is read for its own name, its aliases and its referrals — see
# `_ns_context`.
_NS_FORM_RE = re.compile(r"^\(\s*ns\s+([A-Za-z0-9.*+!_'?<>=-]+)")
_REQUIRE_CLAUSE_RE = re.compile(r"^\(\s*:(?:require|use)")
# The two surfaces that make a proof a COMPILED-mode proof.  A declaration opts
# INTO compiled lowering with `{:compiled true}`, and the compile tier itself —
# the analyzer, the checker, the grammar, the manifest env — is compiled-mode
# machinery whatever the declarations it is pointed at carry.  See
# `_cell_lanes` for why the second half is not optional.
_COMPILED_DECLARATION_RE = re.compile(r":compiled\s+true\b")
_COMPILE_TIER_NS_RE = re.compile(r"\bre-frame\.freehand\.compiler\b")
# A SYMBOL, read from a TOKEN BOUNDARY.
#
# The body class carries the `/` that qualifies a symbol, because the qualifier
# is what says which namespace the name lives in: `sup/expect-tree` is a claim
# about `re-frame.freehand.support`, and splitting it into two bare words is what
# made helper identity global.  The head class is the body minus the characters a
# symbol cannot START with — a digit, and the `.` `/` `-` `$` that begin no name
# this graph resolves.
#
# The LOOKBEHIND is the part a merged-PR audit had to ask for twice.  Without it
# the scan started wherever a symbol character appeared, so it did not read tokens
# at all — it read the tails of them.  A keyword is a symbol character run behind
# a `:`, so `(is (= :panel (first fx)))` yielded `panel`, and with a
# `(v/defview panel {:compiled true} …)` in scope that DATUM resolved to the Var
# and vouched for a compiled tier the assertion never entered.  The same promotion
# let `:fx` prove a row bound to `fx`, and `:check/findings` stand in for the
# compile-tier alias `check`.  A keyword is DATA; a Var reference is a symbol; and
# the one lexical fact that separates them is what precedes the token.  So a match
# begins only where the previous character is neither a symbol character nor the
# `:` that makes the token a keyword — after a delimiter, a reader prefix, or the
# start of the form.  Numeric literals fall out with them (`1e5` no longer yields
# `e5`), since a digit cannot be followed into a name either.
#
# It is a boundary, not a reader: this classifies the token's first character and
# nothing more.  Comments, strings, character literals and discarded data are
# already gone (`_strip`), so a match here is a token in code.  The residue is a
# missed edge rather than a spurious one — a name the head class cannot begin
# (`->tree`, `.getName`, `-main`) yields nothing, exactly as it resolved to
# nothing before — and a missed edge costs a noisy `DEAD PROOF SITE` on an honest
# row, never a silent green.  It fails to the loud side, like the unresolved
# libspec in `_ns_context`.
_SYMBOL_BODY = r"A-Za-z0-9*+!_'?<>=.$/-"
_SYMBOL_HEAD = r"A-Za-z*+!_'?<>="
_SYMBOL_RE = re.compile(
    rf"(?<![:{_SYMBOL_BODY}])[{_SYMBOL_HEAD}][{_SYMBOL_BODY}]*"
)

_CLJ_SUFFIXES = (".clj", ".cljc", ".cljs")


def _strip(text: str) -> str:
    """Blank everything that LOOKS like code and is not.

    Returns a string of the same length as `text` — offsets are preserved, so
    the result can be scanned for forms and reported against the original — with
    line comments, string literals, character literals, regex literals and
    `#_`-discarded data replaced by spaces.  Newlines survive so a line number
    still means something.

    Same length, not same shape, is the contract: paren balance is preserved
    because a `(` inside a string or after a `\\` was never a paren.
    """
    out = list(text)
    n = len(text)
    i = 0
    while i < n:
        c = text[i]
        if c == ";":
            while i < n and text[i] != "\n":
                out[i] = " "
                i += 1
        elif c == "\\":
            # A character literal: `\(`, `\"`, `\space`.  The backslash stays so
            # the token is still one token; the payload is blanked so a `\(`
            # cannot open a form.
            i += 1
            if i < n:
                out[i] = " "
                i += 1
        elif c == '"':
            # Keep both delimiters — a discarded `#_"x"` needs a findable extent
            # — and blank the body.
            i += 1
            while i < n:
                if text[i] == "\\":
                    out[i] = " "
                    i += 1
                    if i < n:
                        out[i] = " "
                        i += 1
                    continue
                if text[i] == '"':
                    i += 1
                    break
                out[i] = " "
                i += 1
        else:
            i += 1

    # `#_` discards the NEXT datum, and `#_#_` discards two.  Done after the pass
    # above so the datum's extent is read off code, never off a comment.
    cleaned = "".join(out)
    while True:
        at = cleaned.find("#_")
        if at < 0:
            break
        end = _datum_end(cleaned, at + 2)
        cleaned = (
            cleaned[:at]
            + _blank(cleaned[at:end])
            + cleaned[end:]
        )

    # `(comment …)` is the third way to write code that never runs, and the only
    # one of the three that is a FORM: the reader reads it, it expands to nil,
    # and its body is evaluated by nobody.  Blanked at ANY depth, because inside
    # a `deftest` is exactly where it hides a deleted proof — the shape a merged
    # PR audit found still counting as one.  After the `#_` pass, so a discarded
    # `(comment …)` has already gone.
    while True:
        opened = _COMMENT_FORM_RE.search(cleaned)
        if not opened:
            break
        at = opened.start()
        end = _form_end(cleaned, at)
        cleaned = cleaned[:at] + _blank(cleaned[at:end]) + cleaned[end:]
    return cleaned


def _blank(chunk: str) -> str:
    """`chunk` with everything but its newlines replaced by spaces."""
    return "".join("\n" if c == "\n" else " " for c in chunk)


_OPENERS = {"(": ")", "[": "]", "{": "}"}
# The reader macro prefixes that belong to the ONE datum following them: `#`,
# `'`, `` ` ``, `~`, `~@` and `@`.  One fact and therefore one authority —
# `_datum_end` needs it to find a datum's extent and `_evaluated` needs it to
# find where a quoted datum begins.  `~@` is two of these characters and both are
# in the set, so one loop reads it.  `#` is in the set for the DELIMITED dispatch
# forms only — `#(`, `#{`, `#"`, `#'` — because a `#` that heads a TAG reaches
# over two datums instead; see `_TAG_DISPATCH_RE`.
_PREFIXES = "#'`~@"
# `^` is the prefix that is NOT one of them, and reading it as one was a false
# green a merged-PR audit had to reproduce.  `^` consumes TWO datums — its
# metadata and then the target that metadata annotates — so `'^{:audit true} [fx]`
# is a quote over the whole annotated vector, not a quote over the map with the
# vector left standing beside it.  Counted the wrong way, `_evaluated` blanked
# `'^{:audit true}` and left `[fx]` exposed as a live reference, so a `deftest`
# whose only mention of the fixture was inside quoted data proved a row — while
# the `(quote ^{:audit true} [fx])` spelling of the same datum correctly red,
# because there the whole list is blanked and no extent is counted.  Its own
# constant, and its own step in `_datum_end`, because "belongs to the datum that
# follows" is exactly the sentence that is untrue of it.
_META_PREFIX = "^"
# AND `^` WAS NEVER THE ONLY ONE.  A TAGGED LITERAL is `#tag datum` — the same
# two-datum shape, one prefix along — and it was read as consuming the tag alone,
# so `_datum_end("#js {:a fx}")` came back `#js`.  Both false-green axes of the
# family follow from that and were reproduced: `'#js [fx]` blanked `'#js` and left
# `[fx]` standing as a live reference, so a `deftest` naming the fixture only
# inside quoted data proved a row; and `#_#?(:clj (def fx …))` discarded only the
# `#?`, so the `(:clj (def fx …))` list survived to bind a fixture the reader
# never sees.  The arithmetic is `^`'s, so it is the same step in `_datum_end`.
#
# WHAT THE TAG NEEDS AND `^` DID NOT IS AN EXCLUSION LIST, because `#` heads the
# delimited dispatch forms too and each of those is ONE datum.  So the rule is
# stated positively — a `#` heads a two-datum dispatch when a SYMBOL could start
# where it points, or a `:` does — and then three shapes are named out of it:
#
#     #'x            a VAR QUOTE, one datum, and `'` is inside `_SYMBOL_HEAD`
#     #_x            a DISCARD, skipped upstream, and `_` is in there as well
#     ##Inf  ##NaN   a SYMBOLIC VALUE, one token, whose second `#` would
#                    otherwise read as a tag over `Inf` and swallow what follows
#
# The first two are why this needs an exclusion list at all: the naive "a symbol
# character follows the `#`" rule mis-reads `#'x`, because `'` and `_` are both
# symbol characters.  The third is a LOOKBEHIND rather than a lookahead, and it is
# not hypothetical — `##NaN` is written straight into the adapter suites.
#
# `#(`, `#{` and `#"` need no naming: `(`, `{` and `"` are not symbol heads, so
# they never match, and each stays the delimited datum it already was.  What falls
# IN is `#?` and `#?@`, because `?` is a symbol head and a reader conditional
# really is `#?` over the arm list; `#js`, `#inst`, `#uuid`, `#queue` and any
# qualified `#foo/Bar`; and `#:ns{…}` / `#::{…}`, the namespaced map, which is the
# same two-datum reach spelled with a `:` instead of a tag name.
_TAG_DISPATCH_RE = re.compile(rf"(?<!#)#(?=[:{_SYMBOL_HEAD}])(?!['_])")


def _prefix_end(text: str, start: int) -> int:
    """The index of the datum the run of reader prefixes at `start` introduces.

    `start` itself when there is no prefix there, so this is safe to call on any
    position a datum may begin at.  The two-datum prefixes are not among them and
    stop the run: the datum a `^` or a `#tag` introduces begins AT the `^` or the
    `#`, and how far it then reaches is `_datum_end`'s question rather than this
    one's.  `^` is outside `_PREFIXES` outright; a tag `#` is inside it and has to
    be told apart from `#'`, `#(`, `#{` and `#"`, which are one datum each.
    """
    i, n = start, len(text)
    while i < n and text[i] in _PREFIXES and not _TAG_DISPATCH_RE.match(text, i):
        i += 1
    return i


def _datum_end(text: str, start: int) -> int:
    """The index one past the datum beginning at or after `start`.

    Whitespace and stacked `#_`s are skipped first, so `#_ #_ a b` discards both
    `a` and `b` the way the reader does.

    `^` and `#tag` each reach over TWO datums — the metadata and its target, the
    tag and the datum it reads — and the step is recursive so that the shapes
    which stack fall out of one rule rather than five: `^:private ^:const x`
    annotates twice, `^{:doc "d"} 'x` annotates a datum carrying its own prefix,
    `^:m ^:n [a b]` ends where the vector ends, `#js ^{:m 1} [a]` tags an
    annotated one, and `#?(:cljs #js [a])` nests a tag inside a conditional.
    """
    i = start
    n = len(text)
    while i < n:
        if text[i].isspace():
            i += 1
        elif text.startswith("#_", i):
            i = _datum_end(text, i + 2)
        else:
            break
    if i >= n:
        return n
    i = _prefix_end(text, i)
    if i < n and (text[i] == _META_PREFIX or _TAG_DISPATCH_RE.match(text, i)):
        # A two-datum prefix: the metadata datum and then the datum it
        # annotates, or the tag datum and then the datum the tag reads.  One
        # step, because it is one piece of arithmetic.
        return _datum_end(text, _datum_end(text, i + 1))
    if i < n and text[i] in _OPENERS:
        return _form_end(text, i)
    if i < n and text[i] == '"':
        j = i + 1
        while j < n and text[j] != '"':
            j += 1
        return min(j + 1, n)
    while i < n and not text[i].isspace() and text[i] not in "()[]{}":
        i += 1
    return i


def _form_end(text: str, start: int) -> int:
    """The index one past the balanced form opening at `start`."""
    depth = 0
    i = start
    n = len(text)
    while i < n:
        c = text[i]
        if c in _OPENERS:
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return n


def _datums(text: str) -> list[str]:
    """Every datum in `text`, in source order.

    The one splitter: a `deftest`'s statements, a reader conditional's arms and
    an `(:require …)` clause's specs are all "the datums of this form's body",
    and reading them three ways is three chances to read one of them wrong.
    """
    items: list[str] = []
    i, n = 0, len(text)
    while i < n:
        if text[i].isspace():
            i += 1
            continue
        end = _datum_end(text, i)
        if end <= i:
            break
        items.append(text[i:end])
        i = end
    return items


def _inner(form: str) -> str:
    """`form` without its delimiters — `(a b)` -> `a b`."""
    return form[1:-1] if len(form) > 1 and form[-1] in ")]}" else form[1:]


def _top_level_forms(cleaned: str) -> list[str]:
    """Every balanced top-level form in `cleaned`, in source order."""
    forms: list[str] = []
    i = 0
    n = len(cleaned)
    while i < n:
        if cleaned[i] == "(":
            end = _form_end(cleaned, i)
            forms.append(cleaned[i:end])
            i = end
        else:
            i += 1
    return forms


def _branch(body: str, platform: str) -> str:
    """The branch of a reader conditional's `body` that `platform` reads.

    Falls back to `:default`, and answers empty when the conditional names
    neither — which is the honest reading: the form is not there at all on that
    platform.
    """
    items = _datums(body)
    fallback = ""
    for k in range(0, len(items) - 1, 2):
        if items[k].strip() == ":" + platform:
            return items[k + 1]
        if items[k].strip() == ":default":
            fallback = items[k + 1]
    return fallback


def _read_as(cleaned: str, platform: str) -> str:
    """`cleaned` as `platform` READS it — every `#?` / `#?@` conditional reduced
    to its branch.

    This is where a `.cljc` file stops being one file.  A
    `#?(:clj (deftest …))` runs on the JVM and NOWHERE else, so a row proven only
    inside that branch is not proven in the node lane however the file is named —
    and the reverse for `#?(:cljs …)`.  Deriving the lane from the filename alone
    credits a row with a lane it never enters.
    """
    out: list[str] = []
    i, n = 0, len(cleaned)
    while i < n:
        if cleaned.startswith("#?(", i) or cleaned.startswith("#?@(", i):
            open_at = cleaned.index("(", i)
            end = _form_end(cleaned, open_at)
            out.append(_read_as(_branch(cleaned[open_at + 1:end - 1], platform),
                                platform))
            i = end
        else:
            out.append(cleaned[i])
            i += 1
    return "".join(out)


# `(quote …)`, the form both reader prefixes expand into.  The lookahead is what
# keeps a name like `quoted-tree` from being read as a quote, the same way
# `_COMMENT_FORM_RE`'s keeps `(commentary …)` from being read as a comment.
_QUOTE_FORM_RE = re.compile(r"\(\s*(?:clojure\.core/)?quote(?=[\s()\[\]{}])")
# A `'` that really is a QUOTE, read from the same token boundary `_SYMBOL_RE`
# reads a name from and off the same one character class, because it is the same
# question asked of the same alphabet.  A symbol may CONTAIN a `'` — `state'`,
# `x'y` — and so may a keyword, so a `'` inside a token belongs to the token; and
# `#'x` is a VAR quote, which evaluates to the Var and is a genuine reference
# rather than data.
_QUOTE_PREFIX_RE = re.compile(rf"(?<![:#{_SYMBOL_BODY}])'")


def _evaluated(text: str) -> str:
    """`text` reduced to what it EVALUATES — every quoted datum blanked.

    The third and last reduction, after `_strip` (what is not code) and
    `_read_as` (what this platform reads).  Same length, and balanced: a quoted
    datum's extent is blanked whole, so a matched pair of delimiters leaves
    together and every offset downstream still means what it meant.

    QUOTED DATA IS DATA, and that is a fact about EVALUATION CONTEXT — which is
    the one thing a token boundary cannot see.  `_SYMBOL_RE` reads `fx` from
    `(quote fx)` and from `` `fx `` because both really do contain the token `fx`;
    what neither contains is a reference to the Var.  A merged-PR audit found both
    spellings standing a row up: `(is (= (quote fx) 1))` "reached" a fixture bound
    to `fx`, and `(is (= `panel (first fx)))` beside a
    `(v/defview panel {:compiled true} …)` witnessed a compiled mode the assertion
    never entered.  Its predecessor found the same hole spelled `:fx` — a keyword
    — so this is the same mistake read one level up: the census was classifying
    tokens when what it needs is which tokens the reader hands to `eval`.

    Three spellings, one rule.  `'x` and `` `x `` are reader prefixes over the
    datum that follows; `(quote x)` is the form they expand into.  All three are
    blanked, so the graph gets no edge from any of them — where before, two of
    the three were silent and the third was red only by lexical accident (the
    `'` fell inside `_SYMBOL_HEAD`, so `'fx` came back as the unresolvable token
    `'fx`).  Accidentally loud is not the same as right.

    WHICH datum is quoted is only half of it; the other half is HOW FAR the datum
    reaches, and that is `_datum_end`'s arithmetic rather than this function's.
    A merged-PR audit found the two prefix spellings standing a row up again
    through `'^{:audit true} [fx]`, because `^` was filed with the prefixes that
    introduce ONE datum: the blank ended at the metadata map's `}` and left
    `[fx]` beside it in code.  The `(quote ^{:audit true} [fx])` spelling of the
    same datum was red throughout — its extent is the LIST, so no `^` was
    counted — and that difference is what made it arithmetic and not policy.
    Fixed where extent lives, so `#_` and the reader conditionals get the
    correction with it.

    AND IT IS A NARROWING, NOT A REFUSAL, which is the half that keeps it honest.
    Inside a syntax quote, `~x` and `~@x` ARE evaluated — that is what they are
    for — so those islands are recovered and read as code, recursively.  A
    genuinely evaluated reference stays a reference however deep in a template it
    is written.

    Two residues, both missed edges rather than spurious ones, so both cost a
    noisy defect on an honest row instead of a silent green:

      * A nested syntax quote raises the level, and `~x` inside it belongs to the
        INNER quote — data at this one.  Read that way here, which is correct; the
        double-unquote that would climb back out (`` `(a `(b ~~c)) ``) is not
        modelled, and yields nothing.
      * `#'x` is a var quote and a real reference.  It resolved to nothing before
        this change and resolves to nothing after — the leading `'` is not a
        symbol head any binding is written with — so no edge is lost, and none is
        invented.
    """
    out: list[str] = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == "`":
            end = _datum_end(text, i)
            out.append(_syntax_quoted(text[i:end]))
            i = end
        elif c == "'" and _QUOTE_PREFIX_RE.match(text, i):
            end = _datum_end(text, i)
            out.append(_blank(text[i:end]))
            i = end
        elif c == "(" and _QUOTE_FORM_RE.match(text, i):
            end = _form_end(text, i)
            out.append(_blank(text[i:end]))
            i = end
        else:
            out.append(c)
            i += 1
    return "".join(out)


def _syntax_quoted(chunk: str) -> str:
    """One syntax-quoted datum, blanked but for the islands it evaluates.

    `chunk` starts at the `` ` ``.  Everything in it is data except what a `~` or
    `~@` unquotes, and each of those is code again — so it goes back through
    `_evaluated`, which is what makes `` `(a ~(b 'c)) `` read `b` and not `c`.
    """
    out = list(_blank(chunk))
    i, n = _prefix_end(chunk, 0), len(chunk)
    while i < n:
        if chunk[i] == "`":
            # A nested syntax quote: its `~`s are its own, and data at this
            # level.  Skipped whole rather than walked into.
            i = _datum_end(chunk, i)
        elif chunk[i] == "~":
            at = _prefix_end(chunk, i)
            end = _datum_end(chunk, at)
            out[at:end] = _evaluated(chunk[at:end])
            i = end
        else:
            i += 1
    return "".join(out)


# Which lanes can run a form the given platform reads.  Crossed with the lanes
# the FILE runs in (`_lanes_for`), this is what a proof site actually reaches.
_PLATFORM_LANES: dict[str, frozenset[str]] = {
    "clj": frozenset({"jvm"}),
    "cljs": frozenset({"node", "browser"}),
}


def _platforms(path: Path) -> tuple[str, ...]:
    if path.suffix == ".clj":
        return ("clj",)
    if path.suffix == ".cljs":
        return ("cljs",)
    return ("clj", "cljs")


def _body_forms(form: str) -> list[str]:
    """`form`'s datums after its head — the STATEMENTS of a test body.

    A `deftest`'s body is a sequence of statements, and each one either takes
    part in an assertion or it does not.  That is the granularity the census
    reads at: finer would need real dataflow (the fixture bound in a `let` head
    is what the `is` in its body asserts on, and no scan of the `is` alone can
    see it), and coarser is the reading a merged PR audit broke — the whole
    `deftest` counted, so a statement beside the assertions counted too.

    The `deftest`'s own name comes back among the datums.  It is a symbol, it
    contains no assertion, and it is dropped by the same filter as any other
    non-asserting statement, so it needs no special case.
    """
    return _datums(_inner(form))[1:]


class _NsContext(NamedTuple):
    """What a namespace needs to resolve a name IT wrote.

    `aliases` maps an alias to the namespace it stands for, `referred` maps a
    name pulled in by `:refer` to the namespace that defines it, and `tier` is
    the subset of aliases standing for a compile-tier namespace — the qualifier
    that makes `check/findings` a compiled-mode reference.
    """

    aliases: dict[str, str]
    referred: dict[str, str]
    tier: frozenset[str]


class _Def(NamedTuple):
    """One binding in the test tree, as one platform reads it."""

    ns: str
    asserts: bool
    compiled: bool
    ids: frozenset[str]
    refs: frozenset[str]


class _Graph(NamedTuple):
    """The test tree's bindings as ONE platform reads them, plus the three
    facts a proof needs to be read off them.

    `asserting`, `compiled` and `ids` are the transitive closures over resolved
    references: whether a binding asserts, whether it reaches the compiled tier,
    and which fixtures it reads.  All three are keyed `(namespace, name)`,
    because a name is only meaningful in the namespace that wrote it.
    """

    source: dict[Path, str]
    ns_of: dict[Path, str]
    context: dict[str, _NsContext]
    defs: dict[tuple[str, str], _Def]
    asserting: frozenset[tuple[str, str]]
    compiled: frozenset[tuple[str, str]]
    ids: dict[tuple[str, str], frozenset[str]]


def _ns_context(cleaned: str, fallback: str) -> tuple[str, _NsContext]:
    """`(namespace, context)` read off a file's `ns` form.

    The libspec shapes read are the ones the Freehand suites write:
    `[ns :as alias]`, `[ns :as-alias alias]` and `[ns :refer [names]]` /
    `:refer-macros`, under `:require` or `:require-macros`, with any `#?`
    conditional already reduced to this platform's branch.  A shape not read
    here — a prefix list, say — leaves its alias unresolved, and an unresolved
    alias costs a `DEAD PROOF SITE` on an honest row rather than a silent green:
    it fails towards the noisy side, which is the side to fail on.

    A file with no readable `ns` form gets `fallback` — a name derived from its
    path — so its bindings stay private to it rather than pooling with every
    other anonymous file's.
    """
    form = next(
        (f for f in _top_level_forms(cleaned) if _NS_FORM_RE.match(f)), ""
    )
    named = _NS_FORM_RE.match(form)
    if not named:
        return fallback, _NsContext({}, {}, frozenset())
    aliases: dict[str, str] = {}
    referred: dict[str, str] = {}
    for clause in _datums(_inner(form)):
        if not _REQUIRE_CLAUSE_RE.match(clause):
            continue
        for spec in _datums(_inner(clause))[1:]:
            if not spec.startswith("["):
                continue
            items = _datums(_inner(spec))
            if not items:
                continue
            target = items[0]
            for key, value in zip(items[1::2], items[2::2]):
                if key in (":as", ":as-alias"):
                    aliases[value] = target
                elif key in (":refer", ":refer-macros"):
                    for name in _SYMBOL_RE.findall(value):
                        referred[name] = target
    tier = frozenset(
        alias for alias, target in aliases.items()
        if _COMPILE_TIER_NS_RE.search(target)
    )
    return named.group(1), _NsContext(aliases, referred, tier)


def _compiled_here(text: str, context: _NsContext) -> bool:
    """Does `text` ITSELF reach the compiled tier?

    Two surfaces, and the second is why a qualified symbol is read whole: a
    `{:compiled true}` declaration, and a reference to compile-tier machinery —
    written out in full, or through the alias the file gave it.

    The reading is textual, and the bound is worth stating: a statement that
    merely mentions `:compiled true` as data counts.  What the census now
    requires is that the marker be REACHED from the asserting statement, which
    is the half that was missing when a `:require` line at the top of a file
    witnessed every row in it.
    """
    if _COMPILED_DECLARATION_RE.search(text) or _COMPILE_TIER_NS_RE.search(text):
        return True
    return any(
        symbol.partition("/")[0] in context.tier
        for symbol in _SYMBOL_RE.findall(text)
        if "/" in symbol
    )


def _resolve(
    graph_defs: dict[tuple[str, str], _Def],
    namespaces: frozenset[str],
    context: _NsContext,
    ns: str,
    symbol: str,
) -> tuple[str, str] | None:
    """The binding `symbol` names when written in `ns`, or None.

    THIS is what a merged-PR audit found missing.  Reading `sup/expect-tree` as
    the bare word `expect-tree` and looking it up in one global pool means any
    file's asserting helper vouches for every same-named helper anywhere — so a
    suite could call its OWN non-asserting `helper` and be credited with a
    stranger's `is`.  A qualifier resolves through the file's own aliases, an
    unqualified name binds to the namespace that wrote it, and a `:refer`red
    name to the namespace it came from.
    """
    if "/" in symbol:
        qualifier, _, name = symbol.partition("/")
        target = context.aliases.get(qualifier)
        if target is None and qualifier in namespaces:
            target = qualifier
        key = (target, name) if target else None
    elif (ns, symbol) in graph_defs:
        key = (ns, symbol)
    elif symbol in context.referred:
        key = (context.referred[symbol], symbol)
    else:
        key = None
    return key if key in graph_defs else None


def _read_tree(repo_root: Path, platform: str) -> _Graph:
    """Every binding under the test tree, as `platform` reads it.

    Read per PLATFORM, because a `.cljc` helper written
    `#?(:clj (is (seq x)) :cljs x)` asserts on the JVM and returns its argument
    in the node lane.  One platform-blind reading credits its caller in both,
    which is a claim about the node lane nothing in the node lane checks.

    Support namespaces are read alongside the suites: a shared `expect-…` and a
    census of `{:compiled true}` declarations live beside the tests by design, so
    a graph that held only discovered test files could not follow a proof to
    either.
    """
    source: dict[Path, str] = {}
    ns_of: dict[Path, str] = {}
    context: dict[str, _NsContext] = {}
    defs: dict[tuple[str, str], _Def] = {}
    test_root = repo_root / TESTS_REL
    if not test_root.is_dir():
        return _Graph({}, {}, {}, {}, frozenset(), frozenset(), {})

    for path in sorted(test_root.rglob("*")):
        if path.suffix not in _CLJ_SUFFIXES or platform not in _platforms(path):
            continue
        cleaned = _evaluated(
            _read_as(_strip(path.read_text(encoding="utf-8")), platform)
        )
        source[path] = cleaned
        ns, ctx = _ns_context(cleaned, path.as_posix())
        ns_of[path] = ns
        was = context.get(ns)
        if was is None:
            context[ns] = ctx
        else:
            was.aliases.update(ctx.aliases)
            was.referred.update(ctx.referred)
            context[ns] = was._replace(tier=was.tier | ctx.tier)

    for path, cleaned in source.items():
        ns = ns_of[path]
        for form in _top_level_forms(cleaned):
            if _DEFTEST_RE.match(form):
                continue
            binder = _DEFINER_RE.match(form)
            if not binder:
                continue
            fresh = _Def(
                ns=ns,
                asserts=bool(_ASSERTION_HEAD_RE.search(form)),
                compiled=_compiled_here(form, context[ns]),
                ids=frozenset(k[1:] for k in _FIXTURE_CALL_RE.findall(form)),
                refs=frozenset(_SYMBOL_RE.findall(form)),
            )
            key = (ns, binder.group(1))
            was = defs.get(key)
            defs[key] = fresh if was is None else _Def(
                ns, was.asserts or fresh.asserts,
                was.compiled or fresh.compiled,
                was.ids | fresh.ids, was.refs | fresh.refs,
            )

    namespaces = frozenset(context)
    edges = {
        key: {
            target
            for target in (
                _resolve(defs, namespaces, context[definition.ns],
                         definition.ns, symbol)
                for symbol in definition.refs
            )
            if target is not None and target != key
        }
        for key, definition in defs.items()
    }

    asserting = {key for key, d in defs.items() if d.asserts}
    compiled = {key for key, d in defs.items() if d.compiled}
    ids = {key: set(d.ids) for key, d in defs.items()}
    changed = True
    while changed:
        changed = False
        for key, targets in edges.items():
            if key not in asserting and targets & asserting:
                asserting.add(key)
                changed = True
            if key not in compiled and targets & compiled:
                compiled.add(key)
                changed = True
            reached = ids[key]
            for target in targets:
                if not ids[target] <= reached:
                    reached |= ids[target]
                    changed = True
    return _Graph(
        source, ns_of, context, defs,
        frozenset(asserting), frozenset(compiled),
        {key: frozenset(value) for key, value in ids.items()},
    )


def _reached(path: Path, graph: _Graph) -> dict[str, bool]:
    """The fixture ids a file's ASSERTIONS reach, each paired with whether a
    compiled-tier witness is reachable from the SAME assertion.

    Reachability starts at the ASSERTING STATEMENTS of the `deftest` forms — not
    at the `deftest` — and follows resolved bindings from there: a
    `(def props-001 (conf/fixture :FH-PROPS-001))` counts when an asserting
    statement names `props-001`, and so does a `defn-` helper such a statement
    calls that reads a fixture of its own.

    Seeding at the whole `deftest` is what a merged PR audit broke, and the two
    shapes it broke it with are worth naming, because both survive a form-aware
    scan that stops short of the assertions:

        (deftest t (is true) (comment fx))      ; a comment beside a proof
        (defn helper [] (when false fx))        ; a branch that never runs,
        (deftest t (is true) (helper))          ; called for its symbol alone

    In both, `fx` is mentioned inside a `deftest` and asserted on by nobody, so
    the suite stays green when the fixture it names is broken — which is the one
    thing a row's green is supposed to mean.  Under the reading here neither
    statement asserts, so neither contributes, and a `deftest` whose statements
    never assert proves nothing at all.

    The compiled witness rides the same walk, and a SECOND merged-PR audit is
    why.  A witness read off the file — a `:require` at the top, a declaration
    anywhere in it — vouches for assertions that never touch either, so the
    shipped compile-tier control could keep its `[… .compiler.check :as check]`
    line, assert `(is (seq fx))` instead of `(is (seq (check/findings fx)))`,
    and still claim the compiled mode.  The witness is now whatever the asserting
    statement itself reaches.

    What this establishes is bounded, and the bound is worth stating plainly: an
    assertion is CO-LOCATED with the fixture read, in one statement, and the
    statement's value flows through the assertion in every ordinary test shape.
    It is not a proof that the assertion would FAIL if the fixture changed —
    that is mutation testing, and it needs the suite to run.  What it rules out
    is the shape this gate exists for: a proof deleted by commenting it out, or
    left standing in code nothing evaluates, with the row still calling itself
    proven.
    """
    ns = graph.ns_of[path]
    context = graph.context[ns]
    namespaces = frozenset(graph.context)
    out: dict[str, bool] = {}
    for form in _top_level_forms(graph.source[path]):
        if not _DEFTEST_RE.match(form):
            continue
        for statement in _body_forms(form):
            bound = {
                target
                for target in (
                    _resolve(graph.defs, namespaces, context, ns, symbol)
                    for symbol in _SYMBOL_RE.findall(statement)
                )
                if target is not None
            }
            if not (_ASSERTION_HEAD_RE.search(statement)
                    or bound & graph.asserting):
                continue
            witness = (_compiled_here(statement, context)
                       or bool(bound & graph.compiled))
            ids = {k[1:] for k in _FIXTURE_CALL_RE.findall(statement)}
            for key in bound:
                ids |= graph.ids[key]
            for row_id in ids:
                out[row_id] = out.get(row_id, False) or witness
    return out

# Which lane runs a test file (`_lanes_for`).  Three discovery rules, and where
# each one comes from:
#
#   jvm      `clojure -M:test` in implementation/freehand.  The runner discovers
#            namespaces ending `-test` over the `:clj` platform, which is `.clj`
#            + `.cljc`.  Stated here, because the rule lives in that artefact's
#            deps.edn `:test` alias rather than in any file this census reads.
#   node     `npm run test:freehand` — the :node-test-freehand build.
#   browser  `npm run test:browser` — the :browser-test build.  The mounted tier:
#            real DOM, real listeners.
LANES: tuple[str, ...] = ("jvm", "node", "browser")

# rf2-k41ph — the two CLJS lanes name their BUILD and nothing else.  Each build's
# `:ns-regexp` is read from the config at `_cljs_lane_selectors`, never restated
# here: a copy of a selector is a second authority with nothing holding it in
# step with the first, so an edit to either side silently desynchronises this
# census's lane model from the selection the build actually performs — and a
# census whose lane model is wrong keeps certifying rows against lanes they have
# left.  Deriving it makes that divergence unrepresentable.
_CLJS_LANE_BUILDS: dict[str, str] = {
    "node": ":node-test-freehand",
    "browser": ":browser-test",
}

_QUALIFIED_HOST_LANES = frozenset({"browser"})

# rf2-49upn — where each lane's EXECUTION is scheduled, so the census can name
# what has to be green beside it.
#
# This gate proves the STATIC half of a row: an assertion exists, is reachable,
# and runs in a lane that serves every cell the row claims.  It cannot prove the
# DYNAMIC half — that the assertion PASSES — because it never runs a suite; that
# is the lane exit codes.  So a green census is a conditional claim, and this
# table is the condition: the CI outputs that schedule the lanes the rows depend
# on, armed for this ledger (index + fixtures) by
# `.github/scripts/report-changed-surfaces.sh`, so both halves land on one
# commit under `all-required-passed` rather than in two unrelated runs.
#
# Named here, not gated here: the executable pin is the regression row in
# implementation/scripts/_changed-surfaces.test.cjs, which asserts the arm in
# both directions.  Keep the two in step if an output is ever renamed.
_LANE_CI: dict[str, tuple[str, str]] = {
    "jvm": ("implementation_jvm", "jvm-freehand"),
    "node": ("cljs_node_test", "cljs"),
    "browser": ("cljs_browser", "cljs-browser"),
}


def _binding_note(needed: set[str]) -> str:
    """One sentence naming the lanes the applicable rows require, and the CI
    jobs whose green makes this gate's green mean anything."""
    if not needed:
        return ""
    where = "; ".join(
        f"{lane} -> {_LANE_CI[lane][1]} (armed by {_LANE_CI[lane][0]})"
        for lane in LANES
        if lane in needed
    )
    return (
        "  Execution is DERIVED here, never observed: this gate proves the "
        "assertions exist and are reachable, and the lane exit codes prove they "
        f"pass.  The rows above require {where}.  "
        "`.github/scripts/report-changed-surfaces.sh` arms those outputs for "
        "this ledger, so the lanes run on the same commit this index is "
        "certified on (rf2-49upn).\n"
    )


def _cells(line: str) -> list[str]:
    """Split a markdown table row into trimmed cells (outer pipes dropped)."""
    return [c.strip() for c in line.strip().strip("|").split("|")]


def _unquote(cell: str) -> str:
    """Strip the backticks an index cell wraps a literal in."""
    return cell.strip().strip("`").strip()


def _check_citation(
    cell: str,
    index_dir: Path,
    repo_root: Path,
) -> str | None:
    """Return None when the canonical-paragraph cell resolves, else the reason."""
    m = _LINK_RE.match(cell)
    if not m:
        return "not a markdown link - expected [text](../../00X-Doc.md#anchor)"
    dest = m.group(1)
    path_part, _, anchor = dest.partition("#")
    if not anchor:
        return f"`{dest}` has no #anchor - a row cites a paragraph, not a document"
    if not path_part.endswith(".md"):
        return f"`{dest}` does not point at a markdown file"

    target = (index_dir / path_part).resolve()
    try:
        rel = target.relative_to(repo_root.resolve())
    except ValueError:
        return f"`{dest}` resolves outside the repository"
    if rel.parts[0] != "spec":
        return (
            f"`{dest}` resolves to {rel.as_posix()} - a canonical paragraph "
            "lives under spec/, not in a design record or guide"
        )
    if not target.is_file():
        return f"`{dest}` - no such file ({rel.as_posix()})"
    if anchor not in _slug_index(target):
        return f"`{dest}` - {rel.as_posix()} has no anchor #{anchor}"
    return None


def _check_applicability(cell: str) -> str | None:
    """Return None when the applicability cell is well-formed, else the reason."""
    tokens = cell.split()
    if not tokens:
        return "empty - name one mode token and at least one host token"
    unknown = [
        t
        for t in tokens
        if t not in MODE_TOKENS
        and t not in HOST_TOKENS
        and not _QUALIFIED_HOST_RE.match(t)
    ]
    if unknown:
        return (
            f"unknown token(s) {', '.join(unknown)} - expected one of "
            "common/interpreted/compiled plus jvm/browser/ssr/host:<name>"
        )
    modes = [t for t in tokens if t in MODE_TOKENS]
    if len(modes) != 1:
        return (
            f"{len(modes)} mode token(s) ({', '.join(modes) or 'none'}) - "
            "exactly one of common/interpreted/compiled is required"
        )
    hosts = [t for t in tokens if t not in MODE_TOKENS]
    if not hosts:
        return "no host token - name at least one of jvm/browser/ssr/host:<name>"
    return None


def _check_fixture(cell: str, status: str, repo_root: Path) -> str | None:
    """Return None when the fixture cell agrees with the row's status."""
    raw = cell.strip()
    if status == "active":
        if raw == NO_FIXTURE or not raw:
            return "an `active` row must name the fixture that proves it"
        path = _unquote(raw)
        if not (repo_root / path).is_file():
            return f"`{path}` - no such file"
        return None
    if raw != NO_FIXTURE:
        return (
            f"a `{status}` row must leave the fixture cell as {NO_FIXTURE} "
            f"(found `{_unquote(raw)}`)"
        )
    return None


def check(repo_root: Path, verbose: bool = False) -> int:
    """Validate the Freehand conformance index.  Return the defect count."""
    index = repo_root / INDEX_REL
    if not index.is_file():
        sys.stderr.write(f"error: no Freehand conformance index at {index}\n")
        return 1
    index_dir = index.parent

    defects: list[str] = []

    def defect(line_no: int, kind: str, row: str, detail: str) -> None:
        defects.append(f"  {kind}: {INDEX_REL.as_posix()}:{line_no} [{row}] {detail}")

    seen_ids: dict[str, int] = {}
    sections_seen: list[str] = []
    section_area: str | None = None
    section_has_header = False
    last_ordinal: dict[str, int] = {}
    row_count = 0
    by_status: dict[str, int] = {}

    in_fence = False
    for line_no, raw in enumerate(
        index.read_text(encoding="utf-8").splitlines(), start=1
    ):
        if _FENCE_RE.match(raw):
            in_fence = not in_fence
            continue
        if in_fence:
            continue

        section = _SECTION_RE.match(raw)
        if section:
            section_area = section.group(1)
            section_has_header = False
            sections_seen.append(section_area)
            continue

        if not raw.lstrip().startswith("|"):
            continue

        cells = _cells(raw)

        # Separator rows carry no data.
        if cells and all(_SEPARATOR_CELL_RE.match(c) for c in cells):
            continue

        if section_area is None:
            defect(line_no, "ORPHAN ROW", cells[0] if cells else "",
                   "table row before the first area section")
            continue

        # The first table row in a section is its header, and it is pinned so a
        # later slice cannot quietly add, drop, or rename a column.
        if not section_has_header:
            section_has_header = True
            if tuple(cells) != COLUMNS:
                defect(
                    line_no,
                    "BAD TABLE HEADER",
                    f"FH-{section_area}",
                    f"expected | {' | '.join(COLUMNS)} |",
                )
            continue

        if len(cells) != len(COLUMNS):
            defect(
                line_no,
                "BAD ROW",
                cells[0] if cells else "",
                f"{len(cells)} column(s), expected {len(COLUMNS)}",
            )
            continue

        row_count += 1
        id_cell, law, citation, applicability, fixture, status = cells
        row_id = _unquote(id_cell)
        label = row_id or "<empty id>"

        m = _ID_RE.match(row_id)
        if not m:
            defect(
                line_no,
                "ILL-FORMED ID",
                label,
                "expected FH-<AREA>-<NNN> with a roster AREA and three digits",
            )
            continue
        area, ordinal_text = m.group(1), m.group(2)
        ordinal = int(ordinal_text)

        if area not in AREAS:
            defect(line_no, "ILL-FORMED ID", label,
                   f"`{area}` is not in the area roster ({', '.join(AREAS)})")
            continue

        duplicate = row_id in seen_ids
        if duplicate:
            defect(line_no, "DUPLICATE ID", label,
                   f"already allocated at line {seen_ids[row_id]}")
        else:
            seen_ids[row_id] = line_no

        if area != section_area:
            defect(line_no, "AREA MISMATCH", label,
                   f"filed under the FH-{section_area} section")
        elif not duplicate:
            # Ordering is only meaningful for a fresh id — a duplicate has
            # already been reported and would otherwise report twice.
            previous = last_ordinal.get(area)
            if previous is not None and ordinal <= previous:
                defect(line_no, "OUT-OF-ORDER ID", label,
                       f"follows {area}-{previous:03d}; ids ascend within an area")
            elif previous is not None and ordinal > previous + 1:
                missing = ", ".join(
                    f"FH-{area}-{n:03d}" for n in range(previous + 1, ordinal)
                )
                defect(line_no, "GAP IN AREA", label,
                       f"follows {area}-{previous:03d}, so {missing} is missing; "
                       "ordinals are DENSE within an area - a withdrawn law "
                       "keeps its row at status `retired`, it is not deleted")
            last_ordinal[area] = max(ordinal, previous or 0)

        if not law:
            defect(line_no, "BAD ROW", label, "empty Law cell")

        reason = _check_citation(citation, index_dir, repo_root)
        if reason:
            kind = "MISSING CITATION" if not _LINK_RE.match(citation) else "BROKEN CITATION"
            defect(line_no, kind, label, reason)

        reason = _check_applicability(applicability)
        if reason:
            defect(line_no, "BAD APPLICABILITY", label, reason)

        if status not in STATUSES:
            defect(line_no, "BAD STATUS", label,
                   f"`{status}` - expected one of {', '.join(sorted(STATUSES))}")
        else:
            by_status[status] = by_status.get(status, 0) + 1
            reason = _check_fixture(fixture, status, repo_root)
            if reason:
                kind = "MISSING FIXTURE" if status == "active" else "MISPLACED FIXTURE"
                defect(line_no, kind, label, reason)

    for area in sections_seen:
        if area not in AREAS:
            defects.append(
                f"  UNKNOWN AREA SECTION: {INDEX_REL.as_posix()} has a "
                f"### FH-{area} section; the roster is closed "
                f"({', '.join(AREAS)})"
            )
    for area in sorted({a for a in sections_seen if sections_seen.count(a) > 1}):
        defects.append(
            f"  DUPLICATE AREA SECTION: {INDEX_REL.as_posix()} has more than one "
            f"### FH-{area} section; one section per area keeps allocation honest"
        )
    for area in AREAS:
        if area not in sections_seen:
            defects.append(
                f"  MISSING AREA SECTION: {INDEX_REL.as_posix()} has no "
                f"### FH-{area} section; every roster area carries one"
            )

    if defects:
        sys.stderr.write(
            f"\n{len(defects)} Freehand conformance-index defect(s) found:\n\n"
        )
        for line in defects:
            sys.stderr.write(line + "\n")
        sys.stderr.write(
            "\nFix: see spec/conformance/freehand/README.md - an FH id is a "
            "permanent address, so it must be well-formed, unique, filed under "
            "its own area, and resolve to a real spec paragraph and (when "
            "active) a real fixture.\n"
        )
    elif verbose:
        # The status breakdown is spelled out because two different numbers are
        # true at once and a reader who takes the wrong one draws the wrong
        # conclusion: the TOTAL counts every id ever allocated, `active` counts
        # the laws that currently BIND, and only the second is the applicable-row
        # count the census reconciles.
        breakdown = ", ".join(
            f"{by_status[s]} {s}" for s in ("active", "planned", "retired")
            if by_status.get(s)
        )
        sys.stderr.write(
            f"Freehand conformance index OK: {row_count} row(s) ({breakdown}) "
            f"across {len(sections_seen)} area section(s).\n"
        )

    return len(defects)


# --------------------------------------------------------------------------
# The execution census
# --------------------------------------------------------------------------


def _parse_rows(index: Path) -> list[tuple[int, str, str, str, str]]:
    """`(line_no, id, applicability, fixture_path, status)` for each parseable
    row, at every status.

    A second, deliberately forgiving pass over the index: the census answers a
    different question from `check`, and a row too malformed to parse here has
    already been reported there.  Reporting it twice, in two vocabularies,
    would bury the one defect that matters under its own echo.
    """
    rows: list[tuple[int, str, str, str, str]] = []
    in_fence = False
    for line_no, raw in enumerate(
        index.read_text(encoding="utf-8").splitlines(), start=1
    ):
        if _FENCE_RE.match(raw):
            in_fence = not in_fence
            continue
        if in_fence or not raw.lstrip().startswith("|"):
            continue
        cells = _cells(raw)
        if len(cells) != len(COLUMNS):
            continue
        row_id = _unquote(cells[0])
        if not _ID_RE.match(row_id):
            continue
        rows.append((line_no, row_id, cells[3], _unquote(cells[4]), cells[5]))
    return rows


# Where an `FH-…` id may be cited.  `spec/` is the normative corpus and the
# whole reason ids are permanent: a citation there is what a `retired` row keeps
# honest.  Two files are excluded, and both for the same reason — they are the
# documents that DEFINE the scheme, so they speak in illustrative ids
# (`FH-PROPS-007`, `FH-AREA-NNN`) that are examples rather than citations.
_CITATION_ROOT = Path("spec")
_CITATION_EXCLUDED = (
    INDEX_REL,
    Path("spec/conformance/freehand/README.md"),
)
_CITATION_SUFFIXES = (".md",)


def _namespace_of(path: Path, test_root: Path) -> str:
    """The namespace a test file under `test_root` declares.

    Munged from the path rather than read from the `(ns …)` form, because that is
    what the runners do: a namespace whose name disagreed with its path could not
    be loaded from it at all.
    """
    return ".".join(
        path.relative_to(test_root).with_suffix("").parts
    ).replace("_", "-")


@functools.lru_cache(maxsize=None)
def _cljs_lane_selectors() -> tuple[tuple[str, re.Pattern[str]], ...]:
    """`(lane, selector)` for each CLJS lane, READ from the build that schedules
    it — see `_CLJS_LANE_BUILDS` for which build owns which lane, and why the
    selector is not written down here (rf2-k41ph).

    Two things make the derivation load-bearing rather than decorative.  Selection
    is shadow-cljs's `re-find`, so `search` and not `match`; and each derived
    selector is held to PARTITIONING this repo's Freehand test tree — selecting at
    least one namespace and rejecting at least one.  A derivation is the one
    mechanism a restatement cannot drift from, and also the one that can fail
    SILENTLY: a pattern that matches nothing quietly empties a lane, and a pattern
    that matches everything quietly serves every cell of every row.  Both look
    exactly like a green census.  The floor is what makes them look like a red one.
    """
    config = _SCRIPT_REPO / SHADOW_CLJS_REL
    text = strip_edn_comments(config.read_text(encoding="utf-8"))
    at = re.search(r":builds\s*\{", text)
    if not at:
        raise SystemExit(
            f"error: {SHADOW_CLJS_REL.as_posix()} declares no :builds map, so no "
            "CLJS lane selector can be derived (rf2-k41ph)."
        )
    builds = dict(read_map_entries(
        text[at.end():match_delimiter(text, at.end() - 1) - 1]
    ))
    test_root = _SCRIPT_REPO / TESTS_REL
    namespaces = [_namespace_of(p, test_root)
                  for p in sorted(test_root.rglob("*"))
                  if p.suffix in _CLJ_SUFFIXES]
    selectors: list[tuple[str, re.Pattern[str]]] = []
    for lane, build in _CLJS_LANE_BUILDS.items():
        if build not in builds:
            raise SystemExit(
                f"error: {SHADOW_CLJS_REL.as_posix()} declares no {build} build, "
                f"so the {lane} lane's selector cannot be derived.  A renamed "
                "build is a lane this census can no longer model (rf2-k41ph)."
            )
        found = re.search(r':ns-regexp\s+"((?:[^"\\]|\\.)*)"', builds[build])
        # shadow-cljs's own default when a test build omits the key — modelling it
        # is what keeps a legal config from reading as a broken derivation.
        selector = re.compile(unescape_edn_string(found.group(1)) if found
                              else SHADOW_DEFAULT_NS_REGEXP)
        selected = [ns for ns in namespaces if selector.search(ns)]
        if not selected or len(selected) == len(namespaces):
            raise SystemExit(
                f"error: {build}'s :ns-regexp {selector.pattern!r} selects "
                f"{'every one of' if selected else 'none'} of the "
                f"{len(namespaces)} namespace(s) under {TESTS_REL.as_posix()}/, "
                f"so the {lane} lane's derived membership is vacuous — it would "
                "empty the lane, or serve every cell of every row, without "
                "reddening a thing (rf2-k41ph)."
            )
        selectors.append((lane, selector))
    return tuple(selectors)


def _lanes_for(path: Path, test_root: Path) -> frozenset[str]:
    """The lanes that run the test file at `path` (see LANES).

    The platform a lane runs on gates it before its selector does: a `.clj` file
    is nothing to a CLJS build however its namespace is spelled.
    """
    lanes = set()
    if path.suffix in (".clj", ".cljc") and path.stem.endswith("_test"):
        lanes.add("jvm")
    if path.suffix in (".cljs", ".cljc"):
        ns = _namespace_of(path, test_root)
        lanes |= {lane for lane, selector in _cljs_lane_selectors()
                  if selector.search(ns)}
    return frozenset(lanes)


class _Proof(NamedTuple):
    """One file's proof of one row: where it is, the lanes its assertions run
    in, and the lanes in which those assertions reach the compiled tier.

    Two lane sets rather than a lane set and a flag, because a witness is only
    evidence where it RUNS.  A `#?(:cljs …)` compile-tier reference vouches for
    the node lane and says nothing about the JVM, and a row claiming
    `compiled jvm` needs the witness on the JVM.
    """

    path: Path
    lanes: frozenset[str]
    compiled: frozenset[str]


def _scan_proof_sites(
    repo_root: Path,
) -> tuple[dict[str, list[_Proof]], dict[str, list[Path]]]:
    """`(proving, dead)` — for each `FH` id, the test files whose ASSERTIONS
    reach its fixture, and the files that name it where no assertion can.

    A file's lanes and a proof's lanes are not the same set.  The file's come
    from where the runners discover it; the proof's are those crossed with the
    platforms whose reading of the file contains the reaching test.  A
    `#?(:clj (deftest …))` in a `-cljs-test.cljc` file is discovered by both
    runners and asserts in only one.
    """
    proving: dict[str, list[_Proof]] = {}
    dead: dict[str, list[Path]] = {}
    test_root = repo_root / TESTS_REL
    if not test_root.is_dir():
        return proving, dead
    graphs = {platform: _read_tree(repo_root, platform)
              for platform in _PLATFORM_LANES}
    for path in sorted(test_root.rglob("*")):
        file_lanes = _lanes_for(path, test_root)
        if path.suffix not in _CLJ_SUFFIXES or not file_lanes:
            continue
        reached: dict[str, tuple[set[str], set[str]]] = {}
        for platform in _platforms(path):
            served = file_lanes & _PLATFORM_LANES[platform]
            for row_id, witness in _reached(path, graphs[platform]).items():
                lanes, witnessed = reached.setdefault(row_id, (set(), set()))
                lanes |= served
                if witness:
                    witnessed |= served
        for row_id, (lanes, witnessed) in sorted(reached.items()):
            proving.setdefault(row_id, []).append(
                _Proof(path, frozenset(lanes), frozenset(witnessed))
            )
        # Named but unreachable — the shape a commented-out, reader-discarded or
        # never-read fixture leaves behind.  Read off the RAW text: the whole
        # point is to see what `_strip` threw away.
        for keyword in sorted(set(_FIXTURE_CALL_RE.findall(
            path.read_text(encoding="utf-8")
        ))):
            if keyword[1:] not in reached:
                dead.setdefault(keyword[1:], []).append(path)
    return proving, dead


def _cell_lanes(mode: str, host: str) -> frozenset[str]:
    """The lanes that execute the tier proving the `(mode, host)` cell.

    An applicability cell is a pair, not two independent claims, so this is the
    ONE authority the census asks — there is no mode-blind reading of a host
    beside it to disagree with.  Every reading below is Spec 008 §The host/mode
    matrix, not this script's invention:

        Mode \\ Host | jvm        | browser              | ssr        | qualified
        common      | structural | structural           | tree -> 011| mounted
        interpreted | structural | structural · mounted | tree -> 011| mounted
        compiled    | structural | structural · mounted | tree -> 011| mounted

    * The `browser` column's STRUCTURAL cell "is the host-neutral tree proven in
      the node runtime; it needs no real DOM", so the node lane serves it and
      the Chromium lane serves the mounted cell of the same column.
    * `common` is ONE cell, not two arms — "the law binds identically in both
      modes" — so a `common` row is proven once, at the structural tier.  It has
      no mounted cell; `interpreted` and `compiled` name both tiers on
      `browser`, so either lane serves those.
    * `ssr` is "structural tree -> 011": the runner proves the tree — and for
      Freehand the JVM render IS the server shell — while 011 owns emission and
      hydration.  So the JVM lane serves `ssr`.
    * A qualified host is opaque to the structural render and is "proven by
      connecting the real behaviour or wrapper in a browser", so `host:<name>`
      is served by the browser lane alone.

    What the mode axis does NOT do here is demand two executions of one law.
    That reading is available and it is wrong: the matrix gives `common` a single
    cell per host, and a `common` row is a claim that ONE proof binds in both
    modes.  Nor is a `compiled` row's mode verifiable by asking which LOWERING
    its proof drives — `FH-DIAG-001` is a `compiled` law proven through the
    compile CHECKER, over declarations that deliberately carry no
    `{:compiled true}`, so a "compiled rows must render a compiled view" rule
    would red an honest row and push the next author into writing a compiled twin
    for the gate's benefit.  What the mode axis DOES get is the witness in
    the witness in `_compiled_here`, which admits the checker for exactly that
    reason.
    """
    if host in ("jvm", "ssr"):
        return frozenset({"jvm"})
    if host == "browser":
        return (frozenset({"node"}) if mode == "common"
                else frozenset({"node", "browser"}))
    return _QUALIFIED_HOST_LANES


# THE MODE WITNESS lives in `_reached` and `_compiled_here`, not in a pass of
# its own, and that is the point of it.
#
# The lane axis cannot see the mode axis: `interpreted jvm` and `compiled jvm`
# are both served by the JVM lane, so a row relabelled from one to the other
# keeps every cell it claims served, and before the witness the census had
# nothing to say about it.  That is a claim parsed and never evidenced.
#
# `compiled` is the mode that can be witnessed, because it is the mode a
# declaration OPTS INTO.  Two surfaces count, and the second is not optional:
#
#     {:compiled true}                 a declaration lowered at expansion
#     re-frame.freehand.compiler.…     the compile tier itself
#
# The compile tier counts on its own because `FH-DIAG-001` is a compiled-mode
# law proven through the CHECKER, over declarations deliberately carrying no
# `{:compiled true}` — the exact row a narrower rule would have reddened.
#
# What a merged-PR audit found is that PRESENCE is not proof.  A witness read off
# the file credited every assertion in it, so keeping the `:require` and asserting
# something else kept the row's `compiled` claim standing.  The witness is
# therefore whatever the ASSERTING STATEMENT reaches — itself, or through the
# bindings it names, across a `:refer`/`:as` hop into the support namespace where
# a census of declarations lives.  Suite presence buys nothing.
#
# And a witness is evidence only where it RUNS, so it is carried per LANE like
# the proof itself.  A compile-tier reference in a `#?(:cljs …)` branch vouches
# for the node lane and says nothing about the JVM, so a `compiled jvm` row needs
# its witness on the JVM.  The defect names the host for that reason.
#
# Reachability is only as sound as NAME RESOLUTION, which is where this last got
# out — twice, one level apart.  Both surfaces are reached by symbol, and a keyword
# is a symbol run behind a `:` — so `:panel` witnessed a
# `(v/defview panel {:compiled true} …)` and `:check/findings` witnessed the alias
# `check`, from data the assertion compares against and never calls.  `_SYMBOL_RE`
# reads from a token boundary for that reason.  And a token boundary was still not
# enough, because `` `panel `` and `(quote panel)` ARE the token: what they are not
# is a reference to the Var.  `_evaluated` blanks quoted data for that reason, and
# BOTH halves of the witness are read off its output — so a quoted
# `{:compiled true}` and a quoted `re-frame.freehand.compiler.…` witness nothing
# either, and the marker cannot disagree with the names beside it.
#
# And blanking quoted data is only as sound as the EXTENT of the datum blanked.
# `'^{:audit true} [panel]` had its quote counted as reaching the metadata map
# alone, so `[panel]` stayed in code and witnessed a compiled tier the assertion
# never entered — while the same datum written `(quote ^{:audit true} [panel])`
# red, because a list's extent needs no prefix arithmetic.  `_datum_end` reads `^`
# as the two-datum prefix it is for that reason (`_META_PREFIX`), and every pass
# that asks how far a datum reaches gets the correction.
#
# What stays TEXTUAL within what does evaluate, and is stated rather than hidden,
# is the marker itself: a statement mentioning a live `{:compiled true}` map
# literal or a spelled-out `re-frame.freehand.compiler.…` as DATA still witnesses.
# Reachability and evaluation context are what were missing and are enforced;
# distinguishing a marker used as data from one used as code, inside evaluated
# code, is not claimed.
#
# `interpreted` and `common` get no witness, and saying so is the honest half of
# this.  Interpreted is the DEFAULT lowering — a declaration is interpreted by
# carrying nothing — so there is no marker to demand, and a rule every proof
# satisfies would dress a green up as evidence rather than supplying any.  The
# census evidences the mode axis in the one direction it can, and claims nothing
# in the other.


def _scan_citations(repo_root: Path) -> dict[str, list[Path]]:
    """Map each `FH` id cited in the normative corpus to the files citing it."""
    cited: dict[str, list[Path]] = {}
    root = repo_root / _CITATION_ROOT
    if not root.is_dir():
        return cited
    excluded = {repo_root / rel for rel in _CITATION_EXCLUDED}
    for path in sorted(root.rglob("*")):
        if path.suffix not in _CITATION_SUFFIXES or path in excluded:
            continue
        text = path.read_text(encoding="utf-8")
        for row_id in sorted(set(re.findall(r"\bFH-[A-Z]+-\d{3}\b", text))):
            cited.setdefault(row_id, []).append(path)
    return cited


def census(repo_root: Path, verbose: bool = False, report: bool = False) -> int:
    """Reconcile the index against the suites that run it.  Return the defect
    count.

    The manifest is DERIVED — from the `(conf/fixture :FH-…)` sites in
    `implementation/freehand/test/` and the lane each file runs in — so there is
    no second copy of the mapping to keep in step.  Eight facts fall out, and
    each is a defect shape: a row nothing reads, a row whose id is written where
    no assertion can reach it, a row asserted only from lanes that do not serve
    the (mode, host) cells it claims, a `compiled` row nothing compiled-tier
    proves, a fixture or a proof site left behind by a row that no longer exists,
    a roster area holding no proven law at all, and an id the corpus cites that
    the index does not carry.

    Only `active` rows are CLAIMS, so only they are reconciled against the
    suites.  A `retired` row is a burnt id: it proves nothing and needs no
    fixture, but it is still a row, so a citation to it resolves.
    """
    index = repo_root / INDEX_REL
    if not index.is_file():
        sys.stderr.write(f"error: no Freehand conformance index at {index}\n")
        return 1

    all_rows = _parse_rows(index)
    rows = [(n, i, a, f) for n, i, a, f, status in all_rows if status == "active"]
    sites, dead_sites = _scan_proof_sites(repo_root)
    defects: list[str] = []
    table: list[tuple[str, str, str, str, str]] = []
    # The ledger's derived EXECUTION requirement: the union, over every
    # applicable row, of the lanes that serve the cells it claims.  Accumulated
    # from the claim rather than from what the proofs happen to reach, so it
    # stays the requirement even on a row that is currently unproven.
    needed: set[str] = set()

    for line_no, row_id, applicability, fixture in rows:
        tokens = applicability.split()
        mode = next((t for t in tokens if t in MODE_TOKENS), "?")
        hosts = [t for t in tokens if t not in MODE_TOKENS]
        proving = sites.get(row_id, [])
        lanes: set[str] = set()
        for proof in proving:
            lanes |= proof.lanes

        if not proving and row_id in dead_sites:
            where = ", ".join(
                p.relative_to(repo_root).as_posix() for p in dead_sites[row_id]
            )
            defects.append(
                f"  DEAD PROOF SITE: {INDEX_REL.as_posix()}:{line_no} [{row_id}] "
                f"is named in {where}, but no ASSERTION there reaches it - the "
                "reference is commented out, reader-discarded, bound to a name "
                "nothing asserts on, or read only from a statement that asserts "
                "nothing.  A law is proven by an assertion RUNNING against its "
                "fixture, not by the id appearing in a file"
            )
        elif not proving:
            defects.append(
                f"  UNPROVEN ROW: {INDEX_REL.as_posix()}:{line_no} [{row_id}] "
                f"no test under {TESTS_REL.as_posix()}/ reads `{fixture}` - an "
                "active row names the fixture that PROVES it, so a fixture "
                "nobody reads is a law nobody proves"
            )
        else:
            witnessed: set[str] = set()
            for proof in proving:
                witnessed |= proof.compiled
            for host in hosts:
                serving = _cell_lanes(mode, host)
                if not lanes & serving:
                    defects.append(
                        f"  UNEXECUTED CELL: {INDEX_REL.as_posix()}:{line_no} "
                        f"[{row_id}] claims the `{mode}` x `{host}` cell, which "
                        f"the {'/'.join(sorted(serving))} lane(s) prove (Spec "
                        "008 #the-hostmode-matrix), but the tests that ASSERT "
                        f"on its fixture run in the "
                        f"{'/'.join(sorted(lanes)) or 'no'} lane(s) - narrow "
                        "the applicability cell or prove the law where it "
                        "claims to bind"
                    )
                    continue
                if mode != "compiled" or witnessed & serving:
                    continue
                where = ", ".join(sorted(p.path.name for p in proving))
                defects.append(
                    f"  UNWITNESSED MODE: {INDEX_REL.as_posix()}:{line_no} "
                    f"[{row_id}] claims the `compiled` mode on `{host}`, but no "
                    f"ASSERTION proving it in {where} reaches the compiled tier "
                    f"in the {'/'.join(sorted(serving))} lane(s) that cell runs "
                    "in - neither a `{:compiled true}` declaration nor a "
                    "`re-frame.freehand.compiler.*` reference is reachable from "
                    "the asserting statement, directly or through the bindings "
                    "it names.  A `:require` line at the top of the file is not "
                    "a witness, and neither is one in a reader branch this cell "
                    "never reads: the JVM lane serves `interpreted jvm` and "
                    "`compiled jvm` alike, so without a reachable witness the "
                    "mode token is a claim nobody checked.  Prove the law "
                    "THROUGH the compiled tier, or say `interpreted` / `common` "
                    "and mean it"
                )
        for host in hosts:
            needed |= _cell_lanes(mode, host)
        table.append((
            row_id,
            mode,
            " ".join(hosts),
            ",".join(l for l in LANES if l in lanes) or "-",
            ", ".join(p.path.name for p in proving) or "-",
        ))

    # Acceptance: no area is empty.  Every structural rule above is satisfied by
    # an index of bare section headers, so without this the strongest claim the
    # gate can make about a roster area is that nobody wrote it down wrong.
    populated = {_ID_RE.match(row_id).group(1) for _, row_id, _, _ in rows}
    for area in AREAS:
        if area not in populated:
            defects.append(
                f"  EMPTY AREA: {INDEX_REL.as_posix()} has no `active` row in "
                f"### FH-{area}; the roster carries an area because a law lives "
                "there, so an area with no proven law is a law that was never "
                "written"
            )

    active_ids = {row_id for _, row_id, _, _ in rows}
    for row_id in sorted(set(sites) - active_ids):
        where = ", ".join(
            p.path.relative_to(repo_root).as_posix() for p in sites[row_id]
        )
        defects.append(
            f"  DANGLING PROOF: {where} reads a fixture for [{row_id}], which "
            f"is not an `active` row in {INDEX_REL.as_posix()} - a suite "
            "outlived its law"
        )

    fixtures_dir = repo_root / FIXTURES_REL
    if fixtures_dir.is_dir():
        named = {fixture for _, _, _, fixture in rows}
        for path in sorted(fixtures_dir.glob("*.edn")):
            rel = path.relative_to(repo_root).as_posix()
            if rel not in named:
                defects.append(
                    f"  ORPHAN FIXTURE: {rel} [{path.stem.upper()}] is named by "
                    f"no `active` row in {INDEX_REL.as_posix()} - a deletion "
                    "that removes the row and leaves the bytes is how a re-used "
                    "id acquires a stale meaning"
                )

    # The one detector for a deletion at the TOP of an area: it leaves no
    # ordinal gap, so the index cannot see it, and the citations that outlive it
    # are the only surviving evidence the id was ever allocated.  Every row
    # counts here, whatever its status — that is the whole point of retiring an
    # id rather than freeing it.
    addressed = {row_id for _, row_id, _, _, _ in all_rows}
    for row_id, paths in sorted(_scan_citations(repo_root).items()):
        if row_id not in addressed:
            where = ", ".join(p.relative_to(repo_root).as_posix() for p in paths)
            defects.append(
                f"  DANGLING CITATION: {where} cites [{row_id}], which "
                f"{INDEX_REL.as_posix()} carries no row for - an id is an "
                "address, and a withdrawn law keeps its row at status `retired` "
                "so the address still answers"
            )

    if report:
        widths = [max(len(r[i]) for r in ([("Id", "Mode", "Hosts", "Lanes", "Proving tests")] + table))
                  for i in range(5)]
        header = ("Id", "Mode", "Hosts", "Lanes", "Proving tests")
        sys.stdout.write(
            "  ".join(h.ljust(w) for h, w in zip(header, widths)).rstrip() + "\n"
        )
        sys.stdout.write("  ".join("-" * w for w in widths) + "\n")
        for row in table:
            sys.stdout.write(
                "  ".join(c.ljust(w) for c, w in zip(row, widths)).rstrip() + "\n"
            )
        sys.stdout.write(
            f"\n{len(table)} APPLICABLE row(s) - every `active` row, the ones "
            "whose laws bind - of which "
            f"{sum(1 for r in table if r[3] != '-')} are reached by an ASSERTION "
            "in a lane serving every mode/host cell they claim.  "
            f"{len(all_rows)} row(s) in the index in total; the remaining "
            f"{len(all_rows) - len(table)} are addressed ids that do not bind "
            "(`retired`, `planned`) and are not applicable rows.\n"
            f"  Of those, {sum(1 for r in table if r[1] == 'compiled')} claim "
            "the `compiled` mode, and in each the compiled tier is reachable "
            "FROM the asserting statement - suite presence is not a witness.  "
            "`interpreted` and `common` carry no mode witness and none is "
            "claimed: interpreted is the DEFAULT lowering, so there is no marker "
            "to demand (see THE MODE WITNESS above `_cell_lanes`).\n"
        )
        sys.stdout.write(_binding_note(needed))

    if defects:
        sys.stderr.write(
            f"\n{len(defects)} Freehand conformance-census defect(s) found:\n\n"
        )
        for line in defects:
            sys.stderr.write(line + "\n")
        sys.stderr.write(
            "\nFix: an `active` row is a claim that a law is PROVEN on the modes "
            "and hosts its applicability cell names - so either write the proof, "
            "or narrow the cell to what is proven (spec/008-Testing.md "
            "#the-hostmode-matrix).  An id that stops binding keeps its row at "
            "status `retired` rather than being deleted, so every citation still "
            "resolves (spec/conformance/freehand/README.md).\n"
        )
    elif verbose:
        burnt = len(all_rows) - len(rows)
        sys.stderr.write(
            f"Freehand conformance census OK: {len(table)} APPLICABLE row(s) "
            f"across {len(AREAS)} area(s), none empty, each reached by an "
            "assertion in a lane serving every mode/host cell it claims, and "
            f"each of the {sum(1 for r in table if r[1] == 'compiled')} "
            "`compiled` row(s) proven through a compiled tier REACHABLE from "
            f"that assertion; {burnt} further id(s) addressed but not binding.\n"
        )
        sys.stderr.write(_binding_note(needed))

    return len(defects)


# --------------------------------------------------------------------------
# Self-tests — hermetic fixtures generated into a temp dir, mirroring
# scripts/check_ep_status_sync.py's style.  Each fixture is a mini-repo: an
# mkdocs.yml at the root (the repo-root guard), a spec/ doc with a real
# heading to cite, an existing fixture file, and one index.
#
# The clean case and the EMPTY case both pass; every other case injects one
# defect shape and must fail.  An index this file's production copy is empty
# would otherwise be validated by a guard that has never said no.
# --------------------------------------------------------------------------

_HEADER = "| " + " | ".join(COLUMNS) + " |\n|---|---|---|---|---|---|\n"

_GOOD_CITATION = "[004-Views.md#template-grammar](../../004-Views.md#template-grammar)"


def _row(
    row_id: str = "FH-CALL-001",
    law: str = "a declared view is a vector-called descriptor",
    citation: str = _GOOD_CITATION,
    applicability: str = "common jvm browser",
    fixture: str = "`spec/conformance/freehand/fixtures/fh-call-001.edn`",
    status: str = "active",
) -> str:
    return (
        f"| `{row_id}` | {law} | {citation} | {applicability} | "
        f"{fixture} | {status} |\n"
    )


def _filler_id(area: str) -> str:
    return f"FH-{area}-001"


def _write_fixture(root: Path, sections: dict[str, str], fill: bool = False) -> None:
    """Write a mini-repo whose index carries `sections` (area -> row block).

    `fill` gives every area the caller did not name its own clean row, so a
    census fixture can isolate one defect without also tripping the
    every-area-carries-a-law rule.  The structural cases leave it off: they
    predate that rule, and an index of empty sections is exactly the shape
    several of them exist to walk over.  Naming an area with an EMPTY block
    keeps it empty under `fill`, which is how the rule is driven red.
    """
    (root / "mkdocs.yml").write_text("site_name: fixture\n", encoding="utf-8")
    spec = root / "spec"
    fixtures = root / FIXTURES_REL
    fixtures.mkdir(parents=True, exist_ok=True)
    (spec / "004-Views.md").write_text(
        "# Views\n\n## Template grammar\n\nx\n", encoding="utf-8"
    )
    (root / "docs").mkdir(exist_ok=True)
    (root / "docs" / "note.md").write_text("# Note\n\n## Template grammar\n\nx\n",
                                           encoding="utf-8")
    (fixtures / "fh-call-001.edn").write_text("{}\n", encoding="utf-8")
    body = ["# Freehand Conformance Index\n"]
    for area in AREAS:
        body.append(f"\n### FH-{area} — {area.title()}\n\n")
        body.append(_HEADER)
        block = sections.get(area)
        if block is None and fill:
            name = f"fh-{area.lower()}-001.edn"
            (fixtures / name).write_text("{}\n", encoding="utf-8")
            block = _row(
                row_id=_filler_id(area),
                fixture=f"`{(FIXTURES_REL / name).as_posix()}`",
            )
        body.append(block or "")
    (root / INDEX_REL).write_text("".join(body), encoding="utf-8")


def _write_census_fixture(
    root: Path,
    sections: dict[str, str],
    tests: dict[str, str],
    extra_fixtures: tuple[str, ...] = (),
    citations: tuple[str, ...] = (),
) -> None:
    """A census mini-repo: a FULL index — every area carrying a clean row unless
    the caller overrode it — plus a test tree that reads (or fails to read) its
    fixtures.  `tests` maps a filename under
    `implementation/freehand/test/re_frame/freehand/` to its source; the filler
    rows get one `.cljc` suite of their own, so each case's own tests carry only
    the defect it is isolating."""
    _write_fixture(root, sections, fill=True)
    test_dir = root / TESTS_REL / "re_frame" / "freehand"
    test_dir.mkdir(parents=True, exist_ok=True)
    filler = "".join(
        f"(def fx-{area.lower()} (conf/fixture :{_filler_id(area)}))\n"
        f"(deftest filler-{area.lower()} (is (seq fx-{area.lower()})))\n"
        for area in AREAS
        if area not in sections
    )
    (test_dir / "filler_cljs_test.cljc").write_text(
        "(ns filler)\n" + filler, encoding="utf-8"
    )
    for name, source in tests.items():
        (test_dir / name).write_text(source, encoding="utf-8")
    for name in extra_fixtures:
        (root / FIXTURES_REL / name).write_text("{}\n", encoding="utf-8")
    if citations:
        (root / _CITATION_ROOT / "099-Citing.md").write_text(
            "# Citing\n\n" + "\n".join(f"see {c}" for c in citations) + "\n",
            encoding="utf-8",
        )


def _proof(row_id: str = "FH-CALL-001") -> str:
    """A real proof: a fixture bound to a name, and a `deftest` that asserts on
    it.  The binding ALONE is not a proof, which is what several cases below
    exist to say — so the green fixtures have to be honest about it too, or the
    census is being self-tested against the very shape it is meant to reject."""
    name = f"fx-{row_id.lower()}"
    return (
        f"(ns x)\n"
        f"(def {name} (conf/fixture :{row_id}))\n"
        f"(deftest proves-{row_id.lower()} (is (seq {name})))\n"
    )


def _build_census_fixtures(base: Path) -> None:
    """One mini-repo per census rule.  Each pins exactly one reading — of the
    host/mode matrix, or of what counts as a proof — so a change to
    `_cell_lanes`, `_lanes_for` or the form scan that is not also a change to
    Spec 008 fails here first."""
    cases: dict[
        str, tuple[dict[str, str], dict[str, str], tuple[str, ...], tuple[str, ...]]
    ] = {
        # Green: a `common jvm browser` row read from a `.cljc` suite, which
        # runs in the JVM lane AND the node lane.
        "census_clean": ({"CALL": _row()}, {"a_cljs_test.cljc": _proof()}, (), ()),
        # Green: `ssr` is served by the JVM lane — the JVM render IS the server
        # shell, and 011 owns emission (Spec 008 §The host/mode matrix).
        "census_ssr_served_by_jvm": (
            {"CALL": _row(applicability="common jvm ssr")},
            {"a_cljs_test.cljc": _proof()},
            (),
            (),
        ),
        # Green: a qualified host is proven by connecting the real wrapper in a
        # browser, so a mounted `-dom-cljs-test` suite serves it.
        "census_qualified_host": (
            {"CALL": _row(applicability="interpreted host:ag-grid")},
            {"a_dom_cljs_test.cljs": _proof()},
            (),
            (),
        ),
        # Green: only an `active` row is a claim, so the `planned` row beside a
        # proven one needs no proof of its own.
        "census_planned_row_is_not_a_claim": (
            {"CALL": _row() + _row(row_id="FH-CALL-002",
                                   fixture=NO_FIXTURE, status="planned")},
            {"a_cljs_test.cljc": _proof()},
            (),
            (),
        ),
        # Green: the proving `deftest` lives in a `#?(:clj …)` arm, and the row
        # claims only cells the JVM lane proves.  The narrowing has to be a
        # narrowing, not a refusal.
        "census_reader_conditional_clj_arm_serves_jvm": (
            {"CALL": _row(applicability="common jvm ssr")},
            {"a_cljs_test.cljc": "(ns x)\n(def fx (conf/fixture :FH-CALL-001))\n"
                                 "#?(:clj (deftest t (is (seq fx))))\n"},
            (),
            (),
        ),
        # Red: the row names a real fixture nobody reads.
        "census_unproven_row": ({"CALL": _row()}, {}, (), ()),
        # Red: the id is there, the assertions are not.  A law retired by
        # commenting out its proof, with the row left standing.
        "census_commented_proof_site": (
            {"CALL": _row()},
            {"a_cljs_test.cljc": "(ns x)\n"
                                 ";; (def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t (is true))\n"},
            (),
            (),
        ),
        # Red: a reader-discarded form.  It survives every text scan and the
        # reader never sees it.
        "census_discarded_proof_site": (
            {"CALL": _row()},
            {"a_cljs_test.cljc": "(ns x)\n"
                                 "#_(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t (is true))\n"},
            (),
            (),
        ),
        # Red: the fixture is read, bound, and never asserted on - the dead def.
        # The file has real tests; none of them names `fx`.
        "census_dead_def": (
            {"CALL": _row()},
            {"a_cljs_test.cljc": "(ns x)\n(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t (is (= 1 1)))\n"},
            (),
            (),
        ),
        # Red: A KEYWORD IS NOT THE VAR IT IS SPELLED LIKE, on the reachability
        # axis.  The suite is `census_dead_def` above with `1` swapped for `:fx`,
        # and before the symbol reader anchored to a token boundary that one
        # character turned the same dead def green: `:fx` was read as the Var
        # `fx`, so the asserting statement "reached" a fixture it only names in a
        # datum.  Its control is `census_dead_def` itself - same shape, a literal
        # spelled differently, red both before and after.
        "census_keyword_shaped_like_the_fixture_binding": (
            {"CALL": _row()},
            {"a_cljs_test.cljc": "(ns x)\n(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t (is (= :fx 1)))\n"},
            (),
            (),
        ),
        # Red: A QUOTED SYMBOL IS NOT THE VAR IT NAMES, which is the same dead
        # def again and the axis a FOURTH merged-PR audit found still open.  The
        # token boundary above reads `:fx` as data because a keyword is not a
        # token; it reads `(quote fx)` as the Var because this one IS the token
        # `fx`.  What separates them is not spelling, it is EVALUATION CONTEXT —
        # the reader hands `=` a symbol, not the fixture — and no boundary can
        # see that.  Three spellings, one rule, all three pinned: the family is
        # what makes it a rule rather than two examples.
        "census_quote_form_shaped_like_the_fixture_binding": (
            {"CALL": _row()},
            {"a_cljs_test.cljc": "(ns x)\n(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t (is (= (quote fx) 1)))\n"},
            (),
            (),
        ),
        # Red: the same datum syntax-quoted.  `` ` `` is the spelling a template
        # reaches for, so it is the one most likely to be written by accident.
        "census_syntax_quote_shaped_like_the_fixture_binding": (
            {"CALL": _row()},
            {"a_cljs_test.cljc": "(ns x)\n(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t (is (= `fx 1)))\n"},
            (),
            (),
        ),
        # Red: the reader-prefix spelling, and the reason the family is pinned
        # whole.  This shape was ALREADY red - and only by accident, because `'`
        # fell inside `_SYMBOL_HEAD`, so the scan handed back the unresolvable
        # token `'fx`.  Accidentally loud is not the same as right: a census
        # whose verdict turns on which of three equivalent spellings an author
        # reached for is reporting a coincidence.  Red by rule now.
        "census_reader_quote_shaped_like_the_fixture_binding": (
            {"CALL": _row()},
            {"a_cljs_test.cljc": "(ns x)\n(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t (is (= 'fx 1)))\n"},
            (),
            (),
        ),
        # Red: A QUOTED DATUM MAY BE ANNOTATED, and reading `^` as a one-datum
        # prefix let the annotation carry the quote away from what it quoted.  The
        # extent of `'^{:audit true} [fx]` is the whole annotated vector; counted
        # as `'` over the MAP, the blank ended at the `}` and `[fx]` stood there
        # in code, so a `deftest` naming the fixture only inside quoted data
        # proved the row.  This assertion is true in Clojure with no `fx` binding
        # in the image at all - which is the plainest statement of what it does
        # not read.
        "census_metadata_reader_quote_shaped_like_the_fixture_binding": (
            {"CALL": _row()},
            {"a_cljs_test.cljc": "(ns x)\n(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t\n"
                                 "  (is (vector? '^{:audit true} [fx])))\n"},
            (),
            (),
        ),
        # Red: the syntax-quoted twin of the same annotated datum.  Both spellings
        # go through one extent calculation, so pinning both is what says the fix
        # is the extent rather than a patch on the `'` branch.
        "census_metadata_syntax_quote_shaped_like_the_fixture_binding": (
            {"CALL": _row()},
            {"a_cljs_test.cljc": "(ns x)\n(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t\n"
                                 "  (is (vector? `^{:audit true} [fx])))\n"},
            (),
            (),
        ),
        # Red, AND THE CONTROL THAT LOCALISED THE FAULT.  The same annotated datum
        # in the `(quote …)` spelling was ALREADY red, because there the extent is
        # the LIST and no `^` is counted - so the census's verdict turned on which
        # of three equivalent spellings the author reached for, and the difference
        # between them was the prefix arithmetic and nothing else.  Red by rule
        # now, like its two twins above.
        "census_metadata_quote_form_shaped_like_the_fixture_binding": (
            {"CALL": _row()},
            {"a_cljs_test.cljc": "(ns x)\n(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t\n"
                                 "  (is (vector? (quote ^{:audit true} [fx]))))\n"},
            (),
            (),
        ),
        # Red: annotations STACK, and `^` reaches over each of them to the target.
        # One `^` handled and the next left standing would be the same bug with a
        # smaller radius, so the extent step recurses rather than special-casing
        # one caret.
        "census_stacked_metadata_on_a_quoted_fixture_binding": (
            {"CALL": _row()},
            {"a_cljs_test.cljc": "(ns x)\n(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t (is (= '^:audit ^:slow fx 1)))\n"},
            (),
            (),
        ),
        # Red: THE SAME EXTENT READ BY A DIFFERENT PASS, and a false green found by
        # asking where else a datum's reach is counted rather than only where the
        # audit pointed.  `#_` discards the next datum, and the next datum here is
        # the ANNOTATED `def` - so the whole binding is discarded.  Counted the
        # short way, only the metadata went and the `def` survived to bind a
        # fixture the reader never sees, which is `census_discarded_proof_site`
        # defeated by one annotation.
        "census_metadata_on_a_discarded_proof_site": (
            {"CALL": _row()},
            {"a_cljs_test.cljc": "(ns x)\n"
                                 "#_^{:audit true}\n"
                                 "(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t (is (seq fx)))\n"},
            (),
            (),
        ),
        # Green, and the control for OVER-tightening on this axis.  An annotation
        # is not a quote: `^{:audit true} fx` in an evaluated position IS the Var
        # reference, metadata and all.  Read the extent as "the caret swallows the
        # target" rather than "the caret's DATUM is the pair" and this honest row
        # reds.
        "census_metadata_on_an_evaluated_fixture_reference": (
            {"CALL": _row()},
            {"a_cljs_test.cljc": "(ns x)\n(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t (is (seq ^{:audit true} fx)))\n"},
            (),
            (),
        ),
        # Green, and the half of the extent fix that RECOVERS an edge rather than
        # removing one.  `~` unquotes the datum after it, and when that datum is
        # annotated the datum is the pair - so `~^{:audit true} fx` evaluates the
        # Var inside the template.  Counted the short way the unquote reached only
        # the metadata MAP and `fx` stayed blanked with the rest of the quote, so
        # this honest row red at `DEAD PROOF SITE`: the miscount cost a green here
        # and gave one away above, from one arithmetic error in one place.
        "census_metadata_on_an_unquote_island_reads_the_fixture": (
            {"CALL": _row()},
            {"a_cljs_test.cljc": "(ns x)\n(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t\n"
                                 "  (is (= `(a ~^{:audit true} fx) 1)))\n"},
            (),
            (),
        ),
        # Red: A QUOTED DATUM MAY BE TAGGED, which is the annotated shape one
        # prefix along and the commoner of the two by two orders of magnitude —
        # `#js` is the reader macro a CLJS author writes daily.  `#tag datum` is
        # two datums exactly as `^meta target` is, and read as consuming the tag
        # alone the quote's blank ended after `'#js` and left `[fx]` standing in
        # code.  This assertion is true in Clojure with no `fx` binding in the
        # image at all.
        "census_tagged_reader_quote_shaped_like_the_fixture_binding": (
            {"CALL": _row()},
            {"a_cljs_test.cljc": "(ns x)\n(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t (is (vector? '#js [fx])))\n"},
            (),
            (),
        ),
        # Red: the syntax-quoted twin.  One extent calculation serves both
        # spellings, so pinning both is what says the fix is the arithmetic rather
        # than a patch on the `'` branch.
        "census_tagged_syntax_quote_shaped_like_the_fixture_binding": (
            {"CALL": _row()},
            {"a_cljs_test.cljc": "(ns x)\n(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t (is (vector? `#js [fx])))\n"},
            (),
            (),
        ),
        # Red: THE TAG EXTENT READ BY THE DISCARD PASS, which is the second axis of
        # the same arithmetic and the one that hides a whole binding.  `#_`
        # discards the next datum, and the next datum here is `#js (def fx …)` —
        # the tag AND the form it tags.  Counted the short way only `#_#js` went,
        # so the `(def fx …)` survived AT TOP LEVEL, bound the fixture, and the
        # `deftest` beside it proved the row off a binding the reader never makes:
        # `census_discarded_proof_site` defeated by one tag.  The exact shape of
        # `census_metadata_on_a_discarded_proof_site`, one prefix along.
        "census_tagged_literal_on_a_discarded_proof_site": (
            {"CALL": _row()},
            {"a_cljs_test.cljc": "(ns x)\n"
                                 "#_#js (def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t (is (seq fx)))\n"},
            (),
            (),
        ),
        # Red — AND RED BEFORE THIS FIX TOO, which is worth saying rather than
        # leaving for the next audit to discover.  This is the `#?` spelling of the
        # case above, the one the bead named, and it does NOT discriminate: the
        # extent fix does not change its verdict.
        #
        # WHY, measured rather than assumed.  Read short, `#_#?(:clj (def fx …))`
        # discards only the `#?` and leaves `(:clj (def fx …))` standing — so the
        # fixture site really does survive into the stripped text, exactly as the
        # bead says.  But `_DEFINER_RE` is anchored to the HEAD of a top-level
        # form, and the survivor's head is `:clj`, not `def`.  No binding is
        # registered, `fx` resolves to nothing, and the row reds either way.  The
        # conditional wrapper the miscount leaves behind is inert.
        #
        # So it is kept as a REGRESSION GUARD, not as evidence: it pins that the
        # top-level anchoring is the second line of defence here, and it is the
        # case that would fire if `_DEFINER_RE` were ever loosened to match a
        # `def` at depth.  A case that cannot fail for the reason it was written
        # for is worth keeping only if it says so, because a probe that does not
        # discriminate is indistinguishable from a gate that did not fire.
        "census_reader_conditional_on_a_discarded_proof_site": (
            {"CALL": _row()},
            {"a_cljs_test.cljc": "(ns x)\n"
                                 "#_#?(:clj (def fx (conf/fixture :FH-CALL-001)))\n"
                                 "(deftest t (is (seq fx)))\n"},
            (),
            (),
        ),
        # Green, and the over-tightening control on the DISCARD pass.  A `#_` over
        # a tagged literal takes the tag and its target and stops: the `(def fx …)`
        # on the next line is a THIRD datum and survives.  Read the tag as reaching
        # one datum too far and the discard swallows the binding, reddening an
        # honest row — the same failure as the red above, in the other direction.
        "census_discarded_tagged_literal_spares_the_binding": (
            {"CALL": _row()},
            {"a_cljs_test.cljc": "(ns x)\n"
                                 "#_#js {:stub 1}\n"
                                 "(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t (is (seq fx)))\n"},
            (),
            (),
        ),
        # Green, and the control for OVER-tightening on this axis.  A tag is not a
        # quote: `#js [fx]` in an evaluated position really does read the Var, tag
        # and all.  Read the extent as "the tag swallows the target" rather than
        # "the tag's DATUM is the pair" and this honest row reds — the same trap
        # the annotated-datum control above guards, one prefix along.
        "census_tagged_literal_in_an_evaluated_position_reads_the_fixture": (
            {"CALL": _row()},
            {"a_cljs_test.cljc": "(ns x)\n(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t (is (seq #js [fx])))\n"},
            (),
            (),
        ),
        # Green, and the half of the tag fix that RECOVERS an edge.  `~` unquotes
        # the datum after it, and when that datum is tagged the datum is the pair,
        # so `~#js [fx]` evaluates the Var inside the template.  Counted the short
        # way the unquote reached only the tag `js` and `fx` stayed blanked with
        # the rest of the quote, so this honest row red at `DEAD PROOF SITE`: one
        # arithmetic error in one place cost a green here and gave one away above.
        "census_tagged_literal_in_an_unquote_island_reads_the_fixture": (
            {"CALL": _row()},
            {"a_cljs_test.cljc": "(ns x)\n(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t\n"
                                 "  (is (= `(a ~#js [fx]) 1)))\n"},
            (),
            (),
        ),
        # Green, and the control that says the tag's TARGET may be a string.  A
        # `#inst` reads the literal beside it and stops there, so the quote covers
        # `'#inst "2020"` and no more — `(first fx)` is a live read in the same
        # statement and the row is proven.  Drop the string branch from the extent
        # step and the quote runs on through the call, reddening an honest row.
        "census_tagged_instant_extent_stops_at_its_string": (
            {"CALL": _row()},
            {"a_cljs_test.cljc": "(ns x)\n(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t\n"
                                 '  (is (= \'#inst "2020" (first fx))))\n'},
            (),
            (),
        ),
        # Green, and the control the EXCLUSION LIST exists for.  `#'fx` is a VAR
        # QUOTE and ONE datum, so `'#'fx` reaches exactly that far and the
        # `(seq fx)` beside it stays live code.  Both `'` and `_` are inside
        # `_SYMBOL_HEAD`, so a tag rule written as "a symbol character follows the
        # `#`" reads `#'fx` as the tag `'fx` over the next datum, swallows the
        # call, and reds this row.  The list is the reviewable half of the fix and
        # this is what checks it.
        "census_var_quote_inside_a_quote_is_one_datum": (
            {"CALL": _row()},
            {"a_cljs_test.cljc": "(ns x)\n(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t (is (= '#'fx (seq fx))))\n"},
            (),
            (),
        ),
        # Green, and the tag extent read by a THIRD pass.  A reader conditional
        # pairs its arms as datums, so an arm VALUE that is a tagged literal
        # shifts every pair after it: read as two datums, `#js {}` made `:clj` an
        # arm VALUE and the `(conf/fixture …)` beside it belonged to nobody, so
        # the JVM branch came back empty and the fixture site vanished.  Loud
        # rather than silent — but a lane losing a real proof is the same
        # arithmetic failing the other way.
        "census_reader_conditional_arm_after_a_tagged_literal": (
            {"CALL": _row(applicability="common jvm ssr")},
            {"a_cljs_test.cljc": "(ns x)\n"
                                 "(def fx #?(:cljs #js {}\n"
                                 "           :clj (conf/fixture :FH-CALL-001)))\n"
                                 "(deftest t (is (seq fx)))\n"},
            (),
            (),
        ),
        # Red: the same reduction on the OTHER surface a datum reaches a fixture
        # through.  Here the quoted datum is the `(conf/fixture …)` CALL itself,
        # so the id is read straight off the assertion without a binding in
        # between - and the call no more runs than the symbol resolves.
        "census_quoted_fixture_call_is_not_a_read": (
            {"CALL": _row()},
            {"a_cljs_test.cljc": "(ns x)\n"
                                 "(deftest t\n"
                                 "  (is (= '(conf/fixture :FH-CALL-001) 1)))\n"},
            (),
            (),
        ),
        # Green, AND THE CONTROL THAT KEEPS THE RULE A NARROWING.  Inside a
        # syntax quote `~fx` IS evaluated - that is what unquote is for - so the
        # island is read as code and the row is proven.  Without this the rule
        # above would be a refusal to read templates at all, which is breakage
        # wearing strictness.
        "census_unquote_inside_a_syntax_quote_reads_the_fixture": (
            {"CALL": _row()},
            {"a_cljs_test.cljc": "(ns x)\n(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t (is (= `(a ~fx) 1)))\n"},
            (),
            (),
        ),
        # Red: a NESTED syntax quote raises the level, and a `~` inside it
        # belongs to the inner quote - so `fx` is quoted by the outer one and
        # evaluates nowhere.  The green above and this red differ by one
        # backtick, which is why the level is tracked rather than the character
        # counted.
        "census_nested_syntax_quote_does_not_read_the_fixture": (
            {"CALL": _row()},
            {"a_cljs_test.cljc": "(ns x)\n(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t (is (= `(a `(b ~fx)) 1)))\n"},
            (),
            (),
        ),
        # Green, and the control for OVER-tightening, which is the other way to
        # get this wrong.  A `'` inside a token belongs to the token: `fx'` is
        # one name, not a quote over `fx`.  Read the boundary the wrong way and
        # this honest row reds.
        "census_prime_suffixed_binding_is_one_token": (
            {"CALL": _row()},
            {"a_cljs_test.cljc": "(ns x)\n(def fx' (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t (is (seq fx')))\n"},
            (),
            (),
        ),
        # Red: the id appears inside a docstring.  Prose about a law is not a
        # proof of it, and prose is where an id is MOST likely to be written.
        "census_proof_site_in_a_docstring": (
            {"CALL": _row()},
            {"a_cljs_test.cljc": '(ns x "see (conf/fixture :FH-CALL-001)")\n'
                                 "(deftest t (is true))\n"},
            (),
            (),
        ),
        # Red: a `(comment …)` INSIDE the `deftest`.  The first of the two false
        # greens a merged PR audit found in the previous reading: the reference
        # is written where a test can see it and where nothing evaluates it, so
        # the suite stays green with the fixture broken.
        "census_comment_form_inside_a_deftest": (
            {"CALL": _row()},
            {"a_cljs_test.cljc": "(ns x)\n(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t (is true) (comment fx))\n"},
            (),
            (),
        ),
        # Red: the same false green wearing a helper.  The `deftest` calls
        # `helper`, so a scan that seeds from every symbol in the test walks
        # straight to `fx` - down a branch that never runs.
        "census_dead_branch_in_a_called_helper": (
            {"CALL": _row()},
            {"a_cljs_test.cljc": "(ns x)\n(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(defn helper [] (when false fx))\n"
                                 "(deftest t (is true) (helper))\n"},
            (),
            (),
        ),
        # Red: the degenerate case the two above are shaped from - a `deftest`
        # that reads the fixture and asserts nothing at all.
        "census_deftest_that_asserts_nothing": (
            {"CALL": _row()},
            {"a_cljs_test.cljc": "(ns x)\n(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t (identity fx))\n"},
            (),
            (),
        ),
        # Green: the assertion is the HELPER's, and the row is proven all the
        # same.  Without this the rule above would red every suite that shares
        # its expectations - which is most of them - and the honest shape has to
        # stay green or the strictness is just breakage.
        "census_helper_carries_the_assertion": (
            {"CALL": _row()},
            {"support.cljc": "(ns re-frame.freehand.support)\n"
                             "(defn expect-tree [x] (is (seq x)))\n",
             "a_cljs_test.cljc": "(ns re-frame.freehand.a-cljs-test\n"
                                 "  (:require [re-frame.freehand.support :as sup]))\n"
                                 "(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t (sup/expect-tree fx))\n"},
            (),
            (),
        ),
        # Green: the same shape with the helper `:refer`red rather than aliased.
        # Both are ways of saying which namespace the name came from, and the
        # rule is about identity, not about spelling.
        "census_referred_helper_carries_the_assertion": (
            {"CALL": _row()},
            {"support.cljc": "(ns re-frame.freehand.support)\n"
                             "(defn expect-tree [x] (is (seq x)))\n",
             "a_cljs_test.cljc": "(ns re-frame.freehand.a-cljs-test\n"
                                 "  (:require [re-frame.freehand.support\n"
                                 "             :refer [expect-tree]]))\n"
                                 "(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t (expect-tree fx))\n"},
            (),
            (),
        ),
        # Red: A QUOTED ASSERTION ASSERTS NOTHING - the reduction on the third
        # surface a quoted datum reached through, and the one that makes the fix
        # a pipeline stage rather than three special cases.  The helper RETURNS
        # `'(is (seq fx))`: a list.  Read textually it looked like the honest
        # helper two cases above, so `helper` was credited with an `is`, the
        # `(helper)` statement counted as asserting, and `fx` came along in the
        # same quoted list.  One reduction, applied once per file, closes all
        # three surfaces at once - names, fixture calls and assertion heads.
        "census_quoted_assertion_in_a_called_helper": (
            {"CALL": _row()},
            {"a_cljs_test.cljc": "(ns x)\n(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(defn helper [] '(is (seq fx)))\n"
                                 "(deftest t (is true) (helper))\n"},
            (),
            (),
        ),
        # Red: A HELPER IS ONLY THE HELPER ITS OWN NAMESPACE RESOLVES - the
        # second false green the SECOND merged-PR audit found.  A stranger's
        # asserting `helper` vouched for this suite's own non-asserting one,
        # because helper identity was one global pool of bare names.  The
        # proving file does not even require the file that asserts.
        "census_same_name_helper_in_an_unrelated_file": (
            {"CALL": _row()},
            {"support.cljc": "(ns re-frame.freehand.support)\n"
                             "(defn helper [x] (is (seq x)))\n",
             "a_cljs_test.cljc": "(ns re-frame.freehand.a-cljs-test)\n"
                                 "(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(defn helper [x] (identity x))\n"
                                 "(deftest t (helper fx))\n"},
            (),
            (),
        ),
        # Red: A HELPER ASSERTS PER PLATFORM - the third.  The helper is real,
        # the require is real, and its `is` exists on the JVM alone, so crediting
        # its caller in the node lane is a claim about a runtime that runs the
        # `:cljs` arm instead.  The `common jvm browser` row loses its browser
        # cell, which the node lane proves.
        "census_platform_split_helper_asserts_on_one_host": (
            {"CALL": _row(applicability="common jvm browser")},
            {"support.cljc": "(ns re-frame.freehand.support)\n"
                             "(defn expect [x] #?(:clj (is (seq x)) :cljs x))\n",
             "a_cljs_test.cljc": "(ns re-frame.freehand.a-cljs-test\n"
                                 "  (:require [re-frame.freehand.support :as sup]))\n"
                                 "(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t (sup/expect fx))\n"},
            (),
            (),
        ),
        # Green: the same suite, the row narrowed to the host its helper
        # actually asserts on.  The narrowing has to be a narrowing.
        "census_platform_split_helper_narrowed_to_its_host": (
            {"CALL": _row(applicability="common jvm")},
            {"support.cljc": "(ns re-frame.freehand.support)\n"
                             "(defn expect [x] #?(:clj (is (seq x)) :cljs x))\n",
             "a_cljs_test.cljc": "(ns re-frame.freehand.a-cljs-test\n"
                                 "  (:require [re-frame.freehand.support :as sup]))\n"
                                 "(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t (sup/expect fx))\n"},
            (),
            (),
        ),
        # Green: a fixture bound in a support namespace and asserted on through
        # the alias.  Reachability crosses a file the same way the mode witness
        # does - one graph, resolved names, no special case for either payload.
        "census_fixture_bound_in_a_required_support_ns": (
            {"CALL": _row()},
            {"support.cljc": "(ns re-frame.freehand.support)\n"
                             "(def fx (conf/fixture :FH-CALL-001))\n",
             "a_cljs_test.cljc": "(ns re-frame.freehand.a-cljs-test\n"
                                 "  (:require [re-frame.freehand.support :as sup]))\n"
                                 "(deftest t (is (seq sup/fx)))\n"},
            (),
            (),
        ),
        # Red: the missing arm.  A `common jvm browser` row whose only assertion
        # is in a `#?(:clj …)` arm never enters the node lane, so the browser
        # cell - structural, proven in the node runtime - is unproven, however
        # the file is named.
        "census_reader_conditional_hides_the_browser_arm": (
            {"CALL": _row(applicability="common jvm browser")},
            {"a_cljs_test.cljc": "(ns x)\n(def fx (conf/fixture :FH-CALL-001))\n"
                                 "#?(:clj (deftest t (is (seq fx))))\n"},
            (),
            (),
        ),
        # Red: a `common jvm browser` row read only from a mounted `.cljs`
        # suite — node and browser, never the JVM it claims.
        "census_unexecuted_host": (
            {"CALL": _row()},
            {"a_dom_cljs_test.cljs": _proof()},
            (),
            (),
        ),
        # Red: a qualified host claimed by a suite that never enters a browser.
        "census_qualified_host_needs_a_browser": (
            {"CALL": _row(applicability="interpreted host:ag-grid")},
            {"a_cljs_test.cljc": _proof()},
            (),
            (),
        ),
        # Green, and the control for the pair below: the same suite proving the
        # same fixture for an `interpreted jvm` row.  Interpreted is the default
        # lowering, so nothing beyond the lane is asked of it.
        "census_interpreted_jvm_row": (
            {"CALL": _row(applicability="interpreted jvm")},
            {"a_cljs_test.cljc": _proof()},
            (),
            (),
        ),
        # Red: THE MODE RELABEL - the second false green the merged PR audit
        # found.  One token changed on the row above, nothing changed in the
        # suite, and the JVM lane serves both cells; before the witness, the
        # census had nothing to say.
        "census_mode_relabelled_to_compiled": (
            {"CALL": _row(applicability="compiled jvm")},
            {"a_cljs_test.cljc": _proof()},
            (),
            (),
        ),
        # Green: the same `compiled jvm` row, proven by an assertion that
        # reaches a declaration lowered at expansion.
        "census_compiled_declaration_witnesses_the_mode": (
            {"CALL": _row(applicability="compiled jvm")},
            {"a_cljs_test.cljc": "(ns x)\n"
                                 "(v/defview panel {:compiled true} [_] [:div])\n"
                                 "(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t (is (= (count fx) (count [panel]))))\n"},
            (),
            (),
        ),
        # Red, AND THE NEGATIVE CONTROL FOR THE GREEN ABOVE - the false green a
        # merged-PR audit had to record twice.  The declaration is the same, the
        # fixture is the same, and the assertion names NOTHING: `:panel` is a
        # keyword, a datum the test compares against, and the symbol reader used
        # to hand back `panel` from behind the `:` and resolve it to the Var.  So
        # a `compiled jvm` row sat at defects=0 on an assertion that entered
        # neither the Var nor the compiled tier.
        "census_keyword_shaped_like_the_compiled_declaration": (
            {"CALL": _row(applicability="compiled jvm")},
            {"a_cljs_test.cljc": "(ns x)\n"
                                 "(v/defview panel {:compiled true} [_] [:div])\n"
                                 "(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t (is (= :panel (first fx))))\n"},
            (),
            (),
        ),
        # Red: THE OTHER HALF OF THAT PAIR.  One character of the keyword
        # changed, nothing else - and this shape was ALREADY red, which is what
        # made the one above a defect rather than a policy: the census's verdict
        # turned on how a datum was SPELLED.  Both red now, so the spelling
        # cannot be what carries a green; the Var-naming green above is.
        "census_keyword_not_shaped_like_the_compiled_declaration": (
            {"CALL": _row(applicability="compiled jvm")},
            {"a_cljs_test.cljc": "(ns x)\n"
                                 "(v/defview panel {:compiled true} [_] [:div])\n"
                                 "(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t (is (= :not-panel (first fx))))\n"},
            (),
            (),
        ),
        # Red: THE SAME PAIR ONE LEVEL UP, on the witness axis - the second half
        # of what the FOURTH merged-PR audit reproduced.  `(quote panel)` really
        # does contain the token `panel`, so the token boundary that closed
        # `:panel` has nothing to say about it, and the graph walked to the
        # `{:compiled true}` declaration from a datum.  The fixture still comes
        # through `(first fx)`, so nothing but the mode witness is in question:
        # the axis is isolated, exactly as the audit isolated it.
        "census_quote_form_shaped_like_the_compiled_declaration": (
            {"CALL": _row(applicability="compiled jvm")},
            {"a_cljs_test.cljc": "(ns x)\n"
                                 "(v/defview panel {:compiled true} [_] [:div])\n"
                                 "(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t (is (= (quote panel) (first fx))))\n"},
            (),
            (),
        ),
        # Red: the syntax-quoted spelling of the same witness.
        "census_syntax_quote_shaped_like_the_compiled_declaration": (
            {"CALL": _row(applicability="compiled jvm")},
            {"a_cljs_test.cljc": "(ns x)\n"
                                 "(v/defview panel {:compiled true} [_] [:div])\n"
                                 "(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t (is (= `panel (first fx))))\n"},
            (),
            (),
        ),
        # Red: the reader-prefix spelling, already red by the same lexical
        # accident as its fixture-axis twin and red by rule now.
        "census_reader_quote_shaped_like_the_compiled_declaration": (
            {"CALL": _row(applicability="compiled jvm")},
            {"a_cljs_test.cljc": "(ns x)\n"
                                 "(v/defview panel {:compiled true} [_] [:div])\n"
                                 "(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t (is (= 'panel (first fx))))\n"},
            (),
            (),
        ),
        # Red: THE ANNOTATED-DATUM EXTENT ON THE WITNESS AXIS.  The fixture is
        # genuinely read - `(map? fx)` is a real reference in the same statement -
        # so nothing but the mode witness is in question, exactly as the audit
        # isolated it.  The declaration is named ONLY inside a quoted annotated
        # vector, and the short extent left `[panel]` standing in code, so a
        # `compiled jvm` row was witnessed by a datum.
        "census_metadata_reader_quote_shaped_like_the_compiled_declaration": (
            {"CALL": _row(applicability="compiled jvm")},
            {"a_cljs_test.cljc": "(ns x)\n"
                                 "(v/defview panel {:compiled true} [_] [:div])\n"
                                 "(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t\n"
                                 "  (is (and (map? fx)\n"
                                 "           (vector? '^{:audit true} [panel]))))\n"},
            (),
            (),
        ),
        # Red: the syntax-quoted twin on the witness axis.  Four reds is what the
        # family costs: two spellings x two axes, because one extent calculation
        # serves both and a pin on one axis alone would not say so.
        "census_metadata_syntax_quote_shaped_like_the_compiled_declaration": (
            {"CALL": _row(applicability="compiled jvm")},
            {"a_cljs_test.cljc": "(ns x)\n"
                                 "(v/defview panel {:compiled true} [_] [:div])\n"
                                 "(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t\n"
                                 "  (is (and (map? fx)\n"
                                 "           (vector? `^{:audit true} [panel]))))\n"},
            (),
            (),
        ),
        # Red, and the already-red control for the pair above: the `(quote …)`
        # spelling of the same annotated vector, whose extent is the list and was
        # therefore never miscounted.  Its twins differed from it by the prefix
        # arithmetic alone.
        "census_metadata_quote_form_shaped_like_the_compiled_declaration": (
            {"CALL": _row(applicability="compiled jvm")},
            {"a_cljs_test.cljc": "(ns x)\n"
                                 "(v/defview panel {:compiled true} [_] [:div])\n"
                                 "(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t\n"
                                 "  (is (and (map? fx)\n"
                                 "           (vector? (quote ^{:audit true} "
                                 "[panel])))))\n"},
            (),
            (),
        ),
        # Green, and the over-tightening control on the witness axis.  The same
        # vector UNQUOTED: `[^{:audit true} panel]` evaluates its annotated
        # element, so the assertion really does reach the declaration and the
        # `compiled` claim really is witnessed.  One `'` apart from the first red
        # above, and the rule has to keep them apart in both directions.
        "census_metadata_on_an_evaluated_declaration_reference": (
            {"CALL": _row(applicability="compiled jvm")},
            {"a_cljs_test.cljc": "(ns x)\n"
                                 "(v/defview panel {:compiled true} [_] [:div])\n"
                                 "(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t\n"
                                 "  (is (and (map? fx)\n"
                                 "           (vector? [^{:audit true} panel]))))\n"},
            (),
            (),
        ),
        # Red: THE TAG EXTENT ON THE WITNESS AXIS.  The fixture is genuinely read
        # — `(map? fx)` is a real reference in the same statement — so nothing but
        # the mode witness is in question.  The declaration is named ONLY inside a
        # quoted TAGGED vector, and the short extent left `[panel]` standing in
        # code, so a `compiled jvm` row was witnessed by a datum.
        "census_tagged_reader_quote_shaped_like_the_compiled_declaration": (
            {"CALL": _row(applicability="compiled jvm")},
            {"a_cljs_test.cljc": "(ns x)\n"
                                 "(v/defview panel {:compiled true} [_] [:div])\n"
                                 "(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t\n"
                                 "  (is (and (map? fx)\n"
                                 "           (vector? '#js [panel]))))\n"},
            (),
            (),
        ),
        # Red: the syntax-quoted twin on the witness axis.  Four reds is what this
        # family costs too — two spellings x two axes — because one extent
        # calculation serves both and a pin on one axis alone would not say so.
        "census_tagged_syntax_quote_shaped_like_the_compiled_declaration": (
            {"CALL": _row(applicability="compiled jvm")},
            {"a_cljs_test.cljc": "(ns x)\n"
                                 "(v/defview panel {:compiled true} [_] [:div])\n"
                                 "(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t\n"
                                 "  (is (and (map? fx)\n"
                                 "           (vector? `#js [panel]))))\n"},
            (),
            (),
        ),
        # Green, and the over-tightening control for the pair above.  The same
        # vector UNQUOTED: `#js [panel]` evaluates its element, so the assertion
        # really does reach the declaration and the `compiled` claim really is
        # witnessed.  One `'` apart from the first red above, and the rule has to
        # keep them apart in both directions.
        "census_tagged_literal_in_an_evaluated_position_witnesses_the_mode": (
            {"CALL": _row(applicability="compiled jvm")},
            {"a_cljs_test.cljc": "(ns x)\n"
                                 "(v/defview panel {:compiled true} [_] [:div])\n"
                                 "(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t\n"
                                 "  (is (and (map? fx)\n"
                                 "           (vector? #js [panel]))))\n"},
            (),
            (),
        ),
        # Green: the narrowing control on THIS axis.  The declaration is named
        # inside a template and UNQUOTED, so the assertion really does evaluate
        # the Var and really does reach the compiled tier.  One `~` apart from
        # the red above.
        "census_unquote_inside_a_syntax_quote_witnesses_the_mode": (
            {"CALL": _row(applicability="compiled jvm")},
            {"a_cljs_test.cljc": "(ns x)\n"
                                 "(v/defview panel {:compiled true} [_] [:div])\n"
                                 "(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t\n"
                                 "  (is (= `(a ~panel) (first fx))))\n"},
            (),
            (),
        ),
        # Red: the reduction reaching the TEXTUAL half of the witness too.  The
        # marker reading stays textual - a statement mentioning `{:compiled true}`
        # as an evaluated map literal still witnesses, and that bound is stated -
        # but a QUOTED marker is not mentioned in code at all, so it witnesses
        # nothing.  Reading the marker off the same reduced text as the names is
        # what keeps the two halves from disagreeing.
        "census_quoted_compiled_marker_does_not_witness": (
            {"CALL": _row(applicability="compiled jvm")},
            {"a_cljs_test.cljc": "(ns x)\n"
                                 "(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t\n"
                                 "  (is (= '{:compiled true} (first fx))))\n"},
            (),
            (),
        ),
        # Red: the other textual marker, quoted.  A quoted namespace SYMBOL is a
        # name the assertion compares against, not a tier it enters - the same
        # thing `:check/findings` was, spelled the other way.
        "census_quoted_tier_namespace_does_not_witness": (
            {"CALL": _row(applicability="compiled jvm")},
            {"a_cljs_test.cljc": "(ns x)\n"
                                 "(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t\n"
                                 "  (is (= 're-frame.freehand.compiler.check\n"
                                 "         (first fx))))\n"},
            (),
            (),
        ),
        # Green: the FH-DIAG-001 shape.  A compiled-mode law proven through the
        # CHECKER, over declarations deliberately carrying no `{:compiled true}`
        # - the row a narrower witness would have reddened.  The fixture goes
        # THROUGH `check/…`, which is the whole difference from the probe below.
        "census_compile_tier_witnesses_the_mode": (
            {"CALL": _row(applicability="compiled jvm")},
            {"a_jvm_test.clj": "(ns re-frame.freehand.a-jvm-test\n"
                               "  (:require [re-frame.freehand.compiler.check "
                               ":as check]))\n"
                               "(def fx (conf/fixture :FH-CALL-001))\n"
                               "(deftest t (is (seq (check/findings fx))))\n"},
            (),
            (),
        ),
        # Red: the same keyword promotion on the OTHER witness surface, and its
        # green is the case above.  A NAMESPACED keyword is qualified by an
        # alias-shaped word too, so `:check/findings` used to be read as
        # `check/findings`, resolve its qualifier through the file's `:as`, and
        # witness the compile tier - from a datum the assertion merely compares
        # against.  A keyword names no namespace this file required.
        "census_namespaced_keyword_shaped_like_the_tier_alias": (
            {"CALL": _row(applicability="compiled jvm")},
            {"a_jvm_test.clj": "(ns re-frame.freehand.a-jvm-test\n"
                               "  (:require [re-frame.freehand.compiler.check "
                               ":as check]))\n"
                               "(def fx (conf/fixture :FH-CALL-001))\n"
                               "(deftest t (is (= :check/findings (first fx))))\n"},
            (),
            (),
        ),
        # Red: THE COMPILE-TIER WITNESS UNREACHED - the first false green the
        # SECOND merged-PR audit found.  One `is` changed on the row above: the
        # `:require` stays, the alias stays, and the fixture never enters the
        # compiled machinery.  Suite presence is not proof.
        "census_compile_tier_required_but_not_asserted_through": (
            {"CALL": _row(applicability="compiled jvm")},
            {"a_jvm_test.clj": "(ns re-frame.freehand.a-jvm-test\n"
                               "  (:require [re-frame.freehand.compiler.check "
                               ":as check]))\n"
                               "(def fx (conf/fixture :FH-CALL-001))\n"
                               "(deftest t (is (seq fx)))\n"},
            (),
            (),
        ),
        # Red: the same disconnect wearing a declaration.  `{:compiled true}` is
        # in the file and the assertion never reaches it, which is exactly what
        # a file-granularity witness could not tell from the green above.
        "census_compiled_declaration_not_reached_by_the_assertion": (
            {"CALL": _row(applicability="compiled jvm")},
            {"a_cljs_test.cljc": "(ns x)\n"
                                 "(v/defview panel {:compiled true} [_] [:div])\n"
                                 "(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t (is (seq fx)))\n"},
            (),
            (),
        ),
        # Red: the witness one statement away.  The `deftest` DOES reach the
        # declaration - in a different statement from the one that reads the
        # fixture - so the fixture still never meets the compiled tier.  The
        # granularity is the statement, here as everywhere else.
        "census_compiled_witness_in_a_neighbouring_statement": (
            {"CALL": _row(applicability="compiled jvm")},
            {"a_cljs_test.cljc": "(ns x)\n"
                                 "(v/defview panel {:compiled true} [_] [:div])\n"
                                 "(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t (is (seq fx)) (is (some? panel)))\n"},
            (),
            (),
        ),
        # Red: the witness in a reader branch the claimed cell never reads.  The
        # compile-tier marker is real and it is `:cljs`-only, so it vouches for
        # the node lane and says nothing about the JVM the row claims.
        "census_compiled_witness_only_in_the_other_reader_branch": (
            {"CALL": _row(applicability="compiled jvm")},
            {"a_cljs_test.cljc": "(ns x)\n"
                                 "(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(defn probe [] #?(:cljs {:compiled true}\n"
                                 "                  :clj nil))\n"
                                 "(deftest t (is (= (seq fx) (probe))))\n"},
            (),
            (),
        ),
        # Green: the same suite on the row that cell actually witnesses.  The
        # `browser` column is proven in the node lane, which is where the `:cljs`
        # branch runs - so the narrowing is a narrowing, not a refusal.
        "census_compiled_witness_serves_the_cell_its_branch_runs_in": (
            {"CALL": _row(applicability="compiled browser")},
            {"a_cljs_test.cljc": "(ns x)\n"
                                 "(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(defn probe [] #?(:cljs {:compiled true}\n"
                                 "                  :clj nil))\n"
                                 "(deftest t (is (= (seq fx) (probe))))\n"},
            (),
            (),
        ),
        # Green: the witness carried across a `:require` hop, which is the shape
        # the real manifest suites have - the census of `{:compiled true}`
        # declarations lives in a support namespace and the assertion reads it
        # through the roster that names them.
        "census_compiled_witness_in_a_required_support_ns": (
            {"CALL": _row(applicability="compiled jvm")},
            {"support.cljc": "(ns re-frame.freehand.support)\n"
                             "(v/defview panel {:compiled true} [_] [:div])\n"
                             "(def by-name {:panel panel})\n",
             "a_cljs_test.cljc": "(ns re-frame.freehand.a-cljs-test\n"
                                 "  (:require [re-frame.freehand.support :as sup]))\n"
                                 "(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t\n"
                                 "  (is (= (count fx) (count sup/by-name))))\n"},
            (),
            (),
        ),
        # Red: a suite that outlived its law.
        "census_dangling_proof": (
            {"CALL": _row()},
            {"a_cljs_test.cljc": _proof() + _proof("FH-CALL-002")},
            (),
            (),
        ),
        # Red: a deletion that took the row and left the bytes.
        "census_orphan_fixture": (
            {"CALL": _row()},
            {"a_cljs_test.cljc": _proof()},
            ("fh-call-002.edn",),
            (),
        ),
        # Red: a roster area holding no proven law — the shape an index of bare
        # section headers has, which every structural rule passes trivially.
        "census_empty_area": (
            {"CALL": _row(), "DIAG": ""},
            {"a_cljs_test.cljc": _proof()},
            (),
            (),
        ),
        # Red: the corpus cites an id the index carries no row for — the one
        # detector for a deletion at the TOP of an area, which leaves no gap.
        "census_dangling_citation": (
            {"CALL": _row()},
            {"a_cljs_test.cljc": _proof()},
            (),
            ("FH-CALL-002",),
        ),
        # Green: the same citation once the withdrawn law keeps its row at
        # `retired` — a burnt id still answers, and needs no fixture or proof.
        "census_retired_row_answers_a_citation": (
            {"CALL": _row() + _row(row_id="FH-CALL-002",
                                   fixture=NO_FIXTURE, status="retired")},
            {"a_cljs_test.cljc": _proof()},
            (),
            ("FH-CALL-002",),
        ),
    }
    for name, (sections, tests, extra, cited) in cases.items():
        root = base / name
        root.mkdir(parents=True, exist_ok=True)
        _write_census_fixture(root, sections, tests, extra, cited)


def _build_self_test_fixtures(base: Path) -> None:
    cases: dict[str, dict[str, str]] = {
        # Both green cases: a real row, and the empty index this slice ships.
        "clean": {"CALL": _row()},
        "empty": {},
        "clean_planned": {"CALL": _row(fixture=NO_FIXTURE, status="planned")},
        "clean_qualified_host": {
            "CALL": _row(applicability="compiled host:ag-grid")
        },
        # One defect each.
        "duplicate_id": {"CALL": _row() + _row(law="a second row, same id")},
        "ill_formed_id": {"CALL": _row(row_id="FH-CALL-1")},
        "unknown_area_in_id": {"CALL": _row(row_id="FH-BANANA-001")},
        "area_mismatch": {"PROPS": _row()},
        "out_of_order_id": {
            "CALL": _row(row_id="FH-CALL-002") + _row(row_id="FH-CALL-001")
        },
        # The other half of the dense-and-ascending rule: a deletion where the
        # convention is a retirement, leaving the id free to be re-allocated.
        "gap_in_area": {
            "CALL": _row(row_id="FH-CALL-001") + _row(row_id="FH-CALL-003")
        },
        "citation_not_a_link": {"CALL": _row(citation="004-Views.md#template-grammar")},
        "citation_no_anchor": {"CALL": _row(citation="[v](../../004-Views.md)")},
        "citation_missing_file": {
            "CALL": _row(citation="[v](../../004Z-Gone.md#template-grammar)")
        },
        "citation_missing_anchor": {
            "CALL": _row(citation="[v](../../004-Views.md#no-such-anchor)")
        },
        "citation_outside_spec": {
            "CALL": _row(citation="[v](../../../docs/note.md#template-grammar)")
        },
        "fixture_missing": {
            "CALL": _row(fixture="`spec/conformance/freehand/fixtures/nope.edn`")
        },
        "fixture_absent_on_active": {"CALL": _row(fixture=NO_FIXTURE)},
        "fixture_on_planned": {"CALL": _row(status="planned")},
        "applicability_unknown_token": {"CALL": _row(applicability="common node")},
        "applicability_two_modes": {
            "CALL": _row(applicability="common compiled browser")
        },
        "applicability_no_host": {"CALL": _row(applicability="common")},
        "applicability_no_mode": {"CALL": _row(applicability="browser jvm")},
        "bad_status": {"CALL": _row(status="green")},
        "bad_row_shape": {"CALL": "| `FH-CALL-001` | law | too few |\n"},
        "empty_law": {"CALL": _row(law="")},
    }
    for name, sections in cases.items():
        root = base / name
        root.mkdir(parents=True, exist_ok=True)
        _write_fixture(root, sections)

    # Section-roster defects need surgery on the rendered index, not a row.
    dropped = base / "missing_area_section"
    dropped.mkdir(parents=True, exist_ok=True)
    _write_fixture(dropped, {})
    text = (dropped / INDEX_REL).read_text(encoding="utf-8")
    (dropped / INDEX_REL).write_text(
        text.replace(f"### FH-{AREAS[-1]} — {AREAS[-1].title()}\n\n" + _HEADER, ""),
        encoding="utf-8",
    )

    unknown = base / "unknown_area_section"
    unknown.mkdir(parents=True, exist_ok=True)
    _write_fixture(unknown, {})
    text = (unknown / INDEX_REL).read_text(encoding="utf-8")
    (unknown / INDEX_REL).write_text(
        text + "\n### FH-BANANA — Banana\n\n" + _HEADER, encoding="utf-8"
    )

    duplicate = base / "duplicate_area_section"
    duplicate.mkdir(parents=True, exist_ok=True)
    _write_fixture(duplicate, {})
    text = (duplicate / INDEX_REL).read_text(encoding="utf-8")
    (duplicate / INDEX_REL).write_text(
        text + f"\n### FH-{AREAS[0]} — Again\n\n" + _HEADER, encoding="utf-8"
    )

    header = base / "bad_table_header"
    header.mkdir(parents=True, exist_ok=True)
    _write_fixture(header, {})
    text = (header / INDEX_REL).read_text(encoding="utf-8")
    (header / INDEX_REL).write_text(
        text.replace("| Law |", "| Rule |", 1), encoding="utf-8"
    )

    orphan = base / "orphan_row"
    orphan.mkdir(parents=True, exist_ok=True)
    _write_fixture(orphan, {})
    text = (orphan / INDEX_REL).read_text(encoding="utf-8")
    (orphan / INDEX_REL).write_text(
        text.replace("# Freehand Conformance Index\n",
                     "# Freehand Conformance Index\n\n" + _HEADER + _row(), 1),
        encoding="utf-8",
    )


# HOW FAR A DATUM REACHES, pinned directly.
#
# Every other self-test here builds a mini-repo and reads a defect count off it,
# which is the right shape for a rule about PROOFS.  `_datum_end`'s exclusion list
# is a rule about the READER, and most of it has no census-level symptom at all:
# nothing writes `'#{a b}` beside a fixture, so a mini-repo could not tell a
# correct reading of it from a lucky one.  A rule proven only where it happens to
# show is not checked, and this arithmetic is the one place a mistake reaches
# `_strip`, `_read_as` and `_evaluated` alike.
#
# Both directions, because both are wrong.  Reading a two-datum prefix short
# leaves the target standing in code and hands a quote-only mention a SILENT
# GREEN; reading a one-datum dispatch long swallows the live reference beside it
# and reds an honest row.
_EXTENT_CASES: tuple[tuple[str, str], ...] = (
    # TWO DATUMS: the prefix reaches its target.  The first four are the extents
    # a merged-PR audit reproduced short.
    ("#js {:a fx} tail", "#js {:a fx}"),
    ('#inst "2020" tail', '#inst "2020"'),
    ("#?(:clj x :cljs y) tail", "#?(:clj x :cljs y)"),
    ("#?@(:clj [x]) tail", "#?@(:clj [x])"),
    ("#uuid \"0-0\" tail", "#uuid \"0-0\""),
    ("#foo.bar/Baz [x] tail", "#foo.bar/Baz [x]"),
    # A namespaced map is the same reach spelled with a `:` where the tag goes.
    ("#:ns{:a 1} tail", "#:ns{:a 1}"),
    ("#::{:a 1} tail", "#::{:a 1}"),
    # The compositions: a tag under a quote, under a syntax quote, over and under
    # an annotation, stacked on itself, and nested inside a conditional.
    ("'#js [fx] tail", "'#js [fx]"),
    ("`#js [fx] tail", "`#js [fx]"),
    ("#js ^{:m 1} [a] tail", "#js ^{:m 1} [a]"),
    ("^{:m 1} #js [a] tail", "^{:m 1} #js [a]"),
    ("#js #js [a] tail", "#js #js [a]"),
    ("#?(:clj #js [a]) tail", "#?(:clj #js [a])"),
    # `^`, unchanged — the rule this one is modelled on.
    ("^:private x tail", "^:private x"),
    # ONE DATUM: the dispatch reaches no further than itself.  `'` and `_` are
    # both symbol characters, so the first two are what the exclusion list is for;
    # `##Inf` is why the rule also looks BEHIND the `#`, and it is written live in
    # the adapter suites rather than hypothetical.
    ("#'fx tail", "#'fx"),
    ("'#'fx tail", "'#'fx"),
    ("#(inc %) tail", "#(inc %)"),
    ("#{a b} tail", "#{a b}"),
    ('#"re" tail', '#"re"'),
    ("##Inf tail", "##Inf"),
    ("##-Inf tail", "##-Inf"),
    ("##NaN tail", "##NaN"),
    ("'fx tail", "'fx"),
    ("[fx] tail", "[fx]"),
    # `#_` is not a prefix over a datum at all — it is SKIPPED, and the extent
    # runs to the end of the datum after it.  Stated here because "unchanged" is a
    # claim, and this is the shape it is a claim about.
    ("#_x y tail", "#_x y"),
)


def _run_extent_self_tests(verbose: bool = False) -> int:
    """Check `_EXTENT_CASES`.  Returns the failure count."""
    failures = 0
    for text, expected in _EXTENT_CASES:
        got = text[:_datum_end(text, 0)]
        if got != expected:
            sys.stderr.write(
                f"self-test FAIL: _datum_end({text!r}) expected {expected!r}, "
                f"got {got!r}\n"
            )
            failures += 1
        elif verbose:
            sys.stderr.write(f"self-test PASS: _datum_end({text!r}) -> {got!r}\n")
    return failures


def _run_self_tests(verbose: bool = False) -> int:
    # Both arms pin the defect a case must red WITH, not merely how many.  The
    # census arm below said why first, and the reasoning is not census-specific:
    # a case that reds for a NEIGHBOUR reason is a detector proven by its
    # neighbour.  Several of these 26 do exactly that when their own rule is
    # deleted — `unknown_area_in_id` falls through to AREA MISMATCH and
    # `citation_no_anchor` to the missing-anchor lookup, both still at one
    # defect (rf2-m147k).  Each entry names substrings unique to ITS rule's
    # diagnostic, so the case cannot be answered by the rule next door.
    cases: list[tuple[str, int, tuple[str, ...] | None]] = [
        ("clean", 0, None),
        ("empty", 0, None),
        ("clean_planned", 0, None),
        ("clean_qualified_host", 0, None),
        ("duplicate_id", 1, ("DUPLICATE ID", "already allocated at line")),
        ("ill_formed_id", 1,
         ("ILL-FORMED ID", "expected FH-<AREA>-<NNN> with a roster AREA")),
        ("unknown_area_in_id", 1,
         ("ILL-FORMED ID", "is not in the area roster")),
        ("area_mismatch", 1, ("AREA MISMATCH", "filed under the FH-")),
        ("out_of_order_id", 1, ("OUT-OF-ORDER ID", "ids ascend within an area")),
        ("gap_in_area", 1, ("GAP IN AREA", "ordinals are DENSE within an area")),
        ("citation_not_a_link", 1,
         ("MISSING CITATION", "not a markdown link")),
        ("citation_no_anchor", 1, ("BROKEN CITATION", "has no #anchor")),
        ("citation_missing_file", 1, ("BROKEN CITATION", "- no such file (")),
        ("citation_missing_anchor", 1, ("BROKEN CITATION", "has no anchor #")),
        ("citation_outside_spec", 1,
         ("BROKEN CITATION", "a canonical paragraph lives under spec/")),
        ("fixture_missing", 1, ("MISSING FIXTURE", "- no such file")),
        ("fixture_absent_on_active", 1,
         ("MISSING FIXTURE", "must name the fixture that proves it")),
        ("fixture_on_planned", 1,
         ("MISPLACED FIXTURE", "must leave the fixture cell as")),
        ("applicability_unknown_token", 1,
         ("BAD APPLICABILITY", "unknown token(s)")),
        ("applicability_two_modes", 1,
         ("BAD APPLICABILITY", "2 mode token(s)")),
        ("applicability_no_host", 1, ("BAD APPLICABILITY", "no host token")),
        ("applicability_no_mode", 1,
         ("BAD APPLICABILITY", "0 mode token(s)")),
        ("bad_status", 1, ("BAD STATUS", "expected one of")),
        ("bad_row_shape", 1, ("BAD ROW", "column(s), expected")),
        ("empty_law", 1, ("BAD ROW", "empty Law cell")),
        ("missing_area_section", 1, ("MISSING AREA SECTION",)),
        ("unknown_area_section", 1, ("UNKNOWN AREA SECTION",)),
        ("duplicate_area_section", 1, ("DUPLICATE AREA SECTION",)),
        ("bad_table_header", 1, ("BAD TABLE HEADER", "expected | Id | Law |")),
        # A block before the first section orphans both its header and its row,
        # and the two are named separately — an aggregate 2 is equally satisfied
        # by either one firing twice.
        ("orphan_row", 2,
         ("[Id] table row before the first area section",
          "[`FH-CALL-001`] table row before the first area section")),
    ]
    # The census cases pin the defect KIND as well as the count, because this
    # guard exists to stop a green being read off the wrong evidence and a
    # self-test that only counts makes exactly that mistake about itself.  Six of
    # these cases red at 1 defect for two different reasons, and only one of them
    # is the reason the case was written for.
    census_cases: list[tuple[str, int, str | None]] = [
        ("census_clean", 0, None),
        ("census_ssr_served_by_jvm", 0, None),
        ("census_qualified_host", 0, None),
        ("census_planned_row_is_not_a_claim", 0, None),
        ("census_reader_conditional_clj_arm_serves_jvm", 0, None),
        ("census_unproven_row", 1, "UNPROVEN ROW"),
        ("census_commented_proof_site", 1, "DEAD PROOF SITE"),
        ("census_discarded_proof_site", 1, "DEAD PROOF SITE"),
        ("census_dead_def", 1, "DEAD PROOF SITE"),
        ("census_keyword_shaped_like_the_fixture_binding", 1,
         "DEAD PROOF SITE"),
        ("census_quote_form_shaped_like_the_fixture_binding", 1,
         "DEAD PROOF SITE"),
        ("census_syntax_quote_shaped_like_the_fixture_binding", 1,
         "DEAD PROOF SITE"),
        ("census_reader_quote_shaped_like_the_fixture_binding", 1,
         "DEAD PROOF SITE"),
        ("census_metadata_reader_quote_shaped_like_the_fixture_binding", 1,
         "DEAD PROOF SITE"),
        ("census_metadata_syntax_quote_shaped_like_the_fixture_binding", 1,
         "DEAD PROOF SITE"),
        ("census_metadata_quote_form_shaped_like_the_fixture_binding", 1,
         "DEAD PROOF SITE"),
        ("census_stacked_metadata_on_a_quoted_fixture_binding", 1,
         "DEAD PROOF SITE"),
        ("census_metadata_on_a_discarded_proof_site", 1, "DEAD PROOF SITE"),
        ("census_metadata_on_an_evaluated_fixture_reference", 0, None),
        ("census_metadata_on_an_unquote_island_reads_the_fixture", 0, None),
        ("census_tagged_reader_quote_shaped_like_the_fixture_binding", 1,
         "DEAD PROOF SITE"),
        ("census_tagged_syntax_quote_shaped_like_the_fixture_binding", 1,
         "DEAD PROOF SITE"),
        ("census_tagged_literal_on_a_discarded_proof_site", 1,
         "DEAD PROOF SITE"),
        # Red before this fix as well — kept as a regression guard on the
        # top-level anchoring, and labelled as such at the fixture.
        ("census_reader_conditional_on_a_discarded_proof_site", 1,
         "DEAD PROOF SITE"),
        ("census_discarded_tagged_literal_spares_the_binding", 0, None),
        ("census_tagged_literal_in_an_evaluated_position_reads_the_fixture",
         0, None),
        ("census_tagged_literal_in_an_unquote_island_reads_the_fixture", 0, None),
        ("census_tagged_instant_extent_stops_at_its_string", 0, None),
        ("census_var_quote_inside_a_quote_is_one_datum", 0, None),
        ("census_reader_conditional_arm_after_a_tagged_literal", 0, None),
        ("census_quoted_fixture_call_is_not_a_read", 1, "DEAD PROOF SITE"),
        ("census_unquote_inside_a_syntax_quote_reads_the_fixture", 0, None),
        ("census_nested_syntax_quote_does_not_read_the_fixture", 1,
         "DEAD PROOF SITE"),
        ("census_prime_suffixed_binding_is_one_token", 0, None),
        ("census_proof_site_in_a_docstring", 1, "DEAD PROOF SITE"),
        ("census_comment_form_inside_a_deftest", 1, "DEAD PROOF SITE"),
        ("census_dead_branch_in_a_called_helper", 1, "DEAD PROOF SITE"),
        ("census_deftest_that_asserts_nothing", 1, "DEAD PROOF SITE"),
        ("census_helper_carries_the_assertion", 0, None),
        ("census_referred_helper_carries_the_assertion", 0, None),
        ("census_quoted_assertion_in_a_called_helper", 1, "DEAD PROOF SITE"),
        ("census_same_name_helper_in_an_unrelated_file", 1, "DEAD PROOF SITE"),
        ("census_platform_split_helper_asserts_on_one_host", 1,
         "UNEXECUTED CELL"),
        ("census_platform_split_helper_narrowed_to_its_host", 0, None),
        ("census_fixture_bound_in_a_required_support_ns", 0, None),
        ("census_interpreted_jvm_row", 0, None),
        ("census_mode_relabelled_to_compiled", 1, "UNWITNESSED MODE"),
        ("census_compiled_declaration_witnesses_the_mode", 0, None),
        ("census_keyword_shaped_like_the_compiled_declaration", 1,
         "UNWITNESSED MODE"),
        ("census_keyword_not_shaped_like_the_compiled_declaration", 1,
         "UNWITNESSED MODE"),
        ("census_quote_form_shaped_like_the_compiled_declaration", 1,
         "UNWITNESSED MODE"),
        ("census_syntax_quote_shaped_like_the_compiled_declaration", 1,
         "UNWITNESSED MODE"),
        ("census_reader_quote_shaped_like_the_compiled_declaration", 1,
         "UNWITNESSED MODE"),
        ("census_metadata_reader_quote_shaped_like_the_compiled_declaration", 1,
         "UNWITNESSED MODE"),
        ("census_metadata_syntax_quote_shaped_like_the_compiled_declaration", 1,
         "UNWITNESSED MODE"),
        ("census_metadata_quote_form_shaped_like_the_compiled_declaration", 1,
         "UNWITNESSED MODE"),
        ("census_metadata_on_an_evaluated_declaration_reference", 0, None),
        ("census_tagged_reader_quote_shaped_like_the_compiled_declaration", 1,
         "UNWITNESSED MODE"),
        ("census_tagged_syntax_quote_shaped_like_the_compiled_declaration", 1,
         "UNWITNESSED MODE"),
        ("census_tagged_literal_in_an_evaluated_position_witnesses_the_mode",
         0, None),
        ("census_unquote_inside_a_syntax_quote_witnesses_the_mode", 0, None),
        ("census_quoted_compiled_marker_does_not_witness", 1,
         "UNWITNESSED MODE"),
        ("census_quoted_tier_namespace_does_not_witness", 1,
         "UNWITNESSED MODE"),
        ("census_compile_tier_witnesses_the_mode", 0, None),
        ("census_namespaced_keyword_shaped_like_the_tier_alias", 1,
         "UNWITNESSED MODE"),
        ("census_compile_tier_required_but_not_asserted_through", 1,
         "UNWITNESSED MODE"),
        ("census_compiled_declaration_not_reached_by_the_assertion", 1,
         "UNWITNESSED MODE"),
        ("census_compiled_witness_in_a_neighbouring_statement", 1,
         "UNWITNESSED MODE"),
        ("census_compiled_witness_only_in_the_other_reader_branch", 1,
         "UNWITNESSED MODE"),
        ("census_compiled_witness_serves_the_cell_its_branch_runs_in", 0, None),
        ("census_compiled_witness_in_a_required_support_ns", 0, None),
        ("census_reader_conditional_hides_the_browser_arm", 1, "UNEXECUTED CELL"),
        ("census_unexecuted_host", 1, "UNEXECUTED CELL"),
        ("census_qualified_host_needs_a_browser", 1, "UNEXECUTED CELL"),
        ("census_dangling_proof", 1, "DANGLING PROOF"),
        ("census_orphan_fixture", 1, "ORPHAN FIXTURE"),
        ("census_empty_area", 1, "EMPTY AREA"),
        ("census_dangling_citation", 1, "DANGLING CITATION"),
        ("census_retired_row_answers_a_citation", 0, None),
    ]
    failures = _run_extent_self_tests(verbose)
    # A case that expects a RED must say what it expects to red WITH.  Without
    # this, the next case added to either arm can arrive kind-less and be back
    # to proof-by-count on the day it lands — which is how all 26 check-arm
    # cases got there (rf2-m147k).
    undeclared = [
        name for name, expected, kind in (*cases, *census_cases)
        if expected and not kind
    ]
    if undeclared:
        sys.stderr.write(
            "self-test FAIL: case(s) expecting a defect but naming none: "
            f"{', '.join(undeclared)}\n"
            "      Name substring(s) unique to the rule's own diagnostic; a "
            "bare count is answered by the rule next door.\n"
        )
        failures += 1
    with tempfile.TemporaryDirectory(prefix="fh_index_selftest_") as tmp:
        base = Path(tmp)
        _build_self_test_fixtures(base)
        _build_census_fixtures(base)
        both: tuple[tuple, ...] = ((check, cases), (census, census_cases))
        for guard, guard_cases in both:
            for fixture, expected, kind in guard_cases:
                # The census arm writes one string, the check arm a tuple of
                # them; both mean "every one of these must appear".
                wanted = (kind,) if isinstance(kind, str) else tuple(kind or ())
                root = base / fixture
                saved_stderr = sys.stderr
                sys.stderr = captured = _Captured()
                try:
                    got = guard(root, verbose=False)
                finally:
                    sys.stderr = saved_stderr
                absent = [k for k in wanted if k not in captured.text]
                if got != expected:
                    sys.stderr.write(
                        f"self-test FAIL: {fixture} expected defects={expected}, "
                        f"got {got}\n"
                    )
                    failures += 1
                elif absent:
                    # The case red, but not for the reason it was written for —
                    # which is the same mistake the guard itself exists to catch.
                    fired = next(
                        (l.strip() for l in captured.text.splitlines()
                         if l.startswith("  ") and l.strip()),
                        "(no defect line)",
                    )
                    sys.stderr.write(
                        f"self-test FAIL: {fixture} red with the right COUNT and "
                        f"the wrong defect: expected {'; '.join(absent)}, got "
                        f"{fired!r}\n"
                    )
                    failures += 1
                elif verbose:
                    sys.stderr.write(
                        f"self-test PASS: {fixture} (defects={got}"
                        f"{'; ' + '; '.join(wanted) if wanted else ''})\n"
                    )
    if failures:
        sys.stderr.write(f"\n{failures} self-test failure(s).\n")
        return 1
    if verbose:
        sys.stderr.write(
            f"all {len(_EXTENT_CASES) + len(cases) + len(census_cases)} "
            "self-tests passed.\n"
        )
    return 0


class _Captured:
    """A stderr stand-in that keeps what was written, so a self-test can ask
    WHICH defect fired and not merely how many."""

    def __init__(self) -> None:
        self.text = ""

    def write(self, s: str, *_args, **_kwargs) -> int:
        self.text += s
        return len(s)

    def flush(self) -> None:  # pragma: no cover
        return None


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description="Validate the Freehand conformance index (FH-<AREA>-<NNN>).",
    )
    parser.add_argument(
        "--repo-root",
        default=None,
        help="Path to the repo root.  Defaults to the script's grandparent.",
    )
    parser.add_argument(
        "--verbose", "-v", action="store_true", help="Print progress to stderr."
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="Run the bundled fixture-based self-tests and exit.",
    )
    parser.add_argument(
        "--report",
        action="store_true",
        help=(
            "Print the per-row applicable-arm table to stdout: every active "
            "row, its mode and hosts, the lanes that prove it, and the tests "
            "that read its fixture."
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
            "(no mkdocs.yml).  Pass --repo-root explicitly.\n"
        )
        return 2

    defects = check(repo_root, verbose=args.verbose)
    defects += census(repo_root, verbose=args.verbose, report=args.report)
    return 0 if defects == 0 else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
