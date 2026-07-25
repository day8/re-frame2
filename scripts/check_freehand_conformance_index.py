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

Reading forms is necessary and it is not sufficient, which a merged-PR audit
established the hard way.  A form-aware scan that seeds from the whole `deftest`
still counts `(comment fx)` written beside the assertions, and still walks to a
fixture down a helper's `(when false …)` branch — proofs by co-location, in code
nothing evaluates.  So reachability starts at the ASSERTING STATEMENTS of a test
body, not at the test.  And the lane cannot see the mode: `interpreted jvm` and
`compiled jvm` are the same lane, so a row relabelled from one to the other kept
every cell served and every claim unchecked.  `compiled` is the mode a
declaration opts into, so it is the mode that can be witnessed, and it now is.
Every reading here is self-tested with the DEFECT KIND pinned, not just the
count, and every green fixture is falsified by removing the thing that makes it
green.

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
                             or read only from a statement that asserts nothing.
                             The shape a text scan calls proof.
    * UNWITNESSED MODE     — an `active` row claims the `compiled` mode and
                             nothing proving it exercises the compiled tier.
                             The lane axis cannot see the mode axis — the JVM
                             lane serves `interpreted jvm` and `compiled jvm`
                             alike — so without a witness the mode token is
                             parsed and never evidenced.
    * UNEXECUTED CELL      — an `active` row claims a (mode, host) cell that no
                             lane among its ASSERTING tests serves.  A
                             `common jvm browser` row read only from a `.cljs`
                             suite is a claim about the JVM the JVM never sees;
                             so is one whose only assertion sits in a
                             `#?(:clj …)` arm, which never enters the node lane
                             that proves the browser column's structural cell.
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
import re
import sys
import tempfile
from pathlib import Path

# One slugifier for the whole corpus (see module docstring).  check_doc_slugs
# lives beside this file and exits(2) itself if pymdown-extensions is absent.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_doc_slugs import _slug_index  # noqa: E402

INDEX_REL = Path("spec/conformance/freehand/conformance-index.md")
FIXTURES_REL = Path("spec/conformance/freehand/fixtures")
TESTS_REL = Path("implementation/freehand/test")

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
# against the fixture, and six shapes are indistinguishable from a proof to a
# scan that stops short of the assertions:
#
#     ;; (conf/fixture :FH-CALL-001)              a comment
#     #_(conf/fixture :FH-CALL-001)               a reader-discarded form
#     (def unused (conf/fixture :FH-CALL-001))    a def no test ever reads
#     "see (conf/fixture :FH-CALL-001)"           a docstring
#     (deftest t (is true) (comment fx))          a comment FORM, inside a test
#     (deftest t (is true) (helper))              a helper whose read is in a
#                                                 branch that never runs
#
# The first three are the false greens the first merged-PR audit found; the
# fourth was the same mistake waiting; the last two are what the SECOND audit
# found still standing after the first fix, because reading forms is not the same
# as reading assertions.  Each one lets a law be retired by deletion — comment
# out the proof, keep the line — with the gate still calling the row proven.
#
# So the scan reads FORMS, and then reads the ASSERTIONS.  `_strip` blanks
# comments, string and character literals, `#_`-discarded data and `(comment …)`
# forms at any depth; `_top_level_forms` splits what survives on balanced parens;
# and a site counts only when an ASSERTING STATEMENT of a `deftest` reaches it —
# written in one, or bound to a name one uses, directly or through a helper it
# calls.  A statement that asserts nothing contributes nothing, and a file
# carrying no `deftest` proves nothing, whatever its name.
_FIXTURE_CALL_RE = re.compile(
    r"\(\s*(?:[A-Za-z0-9.*+!_'?<>=-]+/)?fixture\s+(:FH-[A-Z]+-\d{3})\b"
)
# `(def x …)`, `(defn- helper …)`, `(defonce …)` — the top-level forms that BIND
# a name, so a fixture read in one is reachable from a test that names it.  The
# optional `^:private` / `^{:doc …}` metadata sits between the head and the name.
_DEFINER_RE = re.compile(
    r"^\(\s*(?:def|def-|defn|defn-|defonce|defmacro)\s+"
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
# The ns form, and the namespaces a file requires from the Freehand family.  Used
# to carry a mode witness across the one hop that matters: a suite whose compiled
# declarations live in a support namespace beside it.
_NS_FORM_RE = re.compile(r"^\(\s*ns\s+([A-Za-z0-9.*+!_'?<>=-]+)")
_FREEHAND_NS_RE = re.compile(r"re-frame\.freehand[A-Za-z0-9.*+!_'?<>=-]*")
# The two surfaces that make a proof a COMPILED-mode proof.  A declaration opts
# INTO compiled lowering with `{:compiled true}`, and the compile tier itself —
# the analyzer, the checker, the grammar, the manifest env — is compiled-mode
# machinery whatever the declarations it is pointed at carry.  See
# `_cell_lanes` for why the second half is not optional.
_COMPILED_DECLARATION_RE = re.compile(r":compiled\s+true\b")
_COMPILE_TIER_NS_RE = re.compile(r"\bre-frame\.freehand\.compiler\b")
# Clojure symbol characters.  Deliberately generous: this reads a form for the
# NAMES it mentions, and a name matched too widely costs a spurious edge in the
# reachability graph, never a missed one.
_SYMBOL_RE = re.compile(r"[A-Za-z*+!_'?<>=][A-Za-z0-9*+!_'?<>=.$-]*")

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


def _datum_end(text: str, start: int) -> int:
    """The index one past the datum beginning at or after `start`.

    Whitespace and stacked `#_`s are skipped first, so `#_ #_ a b` discards both
    `a` and `b` the way the reader does.
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
    # A reader macro prefix (`#`, `'`, `` ` ``, `~`, `@`, `^meta`) belongs to the
    # datum that follows it.
    while i < n and text[i] in "#'`~@^":
        i += 1
        if i < n and text[i] == "@":
            i += 1
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
    items: list[str] = []
    i, n = 0, len(body)
    while i < n:
        if body[i].isspace():
            i += 1
            continue
        end = _datum_end(body, i)
        if end <= i:
            break
        items.append(body[i:end])
        i = end
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
    inner = form[1:-1] if form.endswith(")") else form[1:]
    items: list[str] = []
    i, n = 0, len(inner)
    while i < n:
        if inner[i].isspace():
            i += 1
            continue
        end = _datum_end(inner, i)
        if end <= i:
            break
        items.append(inner[i:end])
        i = end
    return items[1:]


def _asserting_names(repo_root: Path) -> frozenset[str]:
    """Every name defined in the test tree whose body ASSERTS, transitively.

    A suite that delegates to a helper — `(expect-tree fx …)`, `(run-cases fx)`
    — is asserting, and a reading that recognised only an `is` written in the
    `deftest` itself would call that shape unproven and red an honest row.  So
    the helpers are read first, across the whole test tree rather than one file
    at a time, because a shared `expect-…` lives in a support namespace by
    design.  A definition asserts when its body contains a `clojure.test`
    assertion or names another definition that does.

    Names are matched unqualified: the call site reads `sup/expect-tree`, the
    definition is `expect-tree`, and the symbol scan yields both halves.  Two
    files may therefore share a name.  The cost is an extra asserting name,
    never a missing one — the same generosity that governs the reachability
    graph below, and in the same direction: a spurious edge, never a lost one.
    """
    bodies: dict[str, tuple[bool, set[str]]] = {}
    test_root = repo_root / TESTS_REL
    if not test_root.is_dir():
        return frozenset()
    for path in sorted(test_root.rglob("*")):
        if path.suffix not in _CLJ_SUFFIXES:
            continue
        for form in _top_level_forms(_strip(path.read_text(encoding="utf-8"))):
            binder = _DEFINER_RE.match(form)
            if not binder:
                continue
            asserts = bool(_ASSERTION_HEAD_RE.search(form))
            refs = set(_SYMBOL_RE.findall(form))
            was = bodies.get(binder.group(1))
            bodies[binder.group(1)] = (
                (was[0] or asserts, was[1] | refs) if was else (asserts, refs)
            )

    asserting = {name for name, (yes, _refs) in bodies.items() if yes}
    changed = True
    while changed:
        changed = False
        for name, (_yes, refs) in bodies.items():
            if name not in asserting and refs & asserting:
                asserting.add(name)
                changed = True
    return frozenset(asserting)


def _proven_ids(text: str, platform: str, asserting: frozenset[str]) -> set[str]:
    """The fixture ids a file's ASSERTIONS reach when read as `platform`.

    Reachability starts at the ASSERTING STATEMENTS of the `deftest` forms — not
    at the `deftest` — and follows named bindings from there: a
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

    What this establishes is bounded, and the bound is worth stating plainly: an
    assertion is CO-LOCATED with the fixture read, in one statement, and the
    statement's value flows through the assertion in every ordinary test shape.
    It is not a proof that the assertion would FAIL if the fixture changed —
    that is mutation testing, and it needs the suite to run.  What it rules out
    is the shape this gate exists for: a proof deleted by commenting it out, or
    left standing in code nothing evaluates, with the row still calling itself
    proven.
    """
    cleaned = _read_as(_strip(text), platform)
    definitions: dict[str, tuple[set[str], set[str]]] = {}
    tests: list[str] = []

    for form in _top_level_forms(cleaned):
        if _DEFTEST_RE.match(form):
            tests.append(form)
            continue
        binder = _DEFINER_RE.match(form)
        if binder:
            definitions[binder.group(1)] = (
                {k[1:] for k in _FIXTURE_CALL_RE.findall(form)},
                set(_SYMBOL_RE.findall(form)),
            )

    proven: set[str] = set()
    seen: set[str] = set()
    frontier: list[str] = []
    for form in tests:
        for statement in _body_forms(form):
            names = set(_SYMBOL_RE.findall(statement))
            if not (_ASSERTION_HEAD_RE.search(statement) or names & asserting):
                continue
            proven |= {k[1:] for k in _FIXTURE_CALL_RE.findall(statement)}
            frontier.extend(names)
    while frontier:
        name = frontier.pop()
        if name in seen or name not in definitions:
            continue
        seen.add(name)
        ids, refs = definitions[name]
        proven |= ids
        frontier.extend(refs)

    return proven

# Which lane runs a test file, derived from the three discovery rules in force —
# not asserted here, mirrored from where they are actually configured:
#
#   jvm      `clojure -M:test` in implementation/freehand.  cognitect-test-runner
#            discovers namespaces ending `-test` over the `:clj` platform, which
#            is `.clj` + `.cljc`.
#   node     `npm run test:freehand` — the :node-test-freehand build's ns-regexp
#            `^re-frame\.freehand\..+-cljs-test$`, which matches `-cljs-test` AND
#            `-dom-cljs-test`, over `.cljs` + `.cljc`.
#   browser  `npm run test:browser` — the :browser-test build's ns-regexp
#            `-dom-cljs-test$`, `.cljs` only.  The mounted tier: real DOM, real
#            listeners.
LANES: tuple[str, ...] = ("jvm", "node", "browser")

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


def _lanes_for(path: Path) -> frozenset[str]:
    """The lanes that run the test file at `path` (see LANES)."""
    name = path.name
    if name.endswith("_test.clj"):
        return frozenset({"jvm"})
    if name.endswith("_test.cljc"):
        return frozenset({"jvm", "node"} if name.endswith("_cljs_test.cljc")
                         else {"jvm"})
    if name.endswith("_dom_cljs_test.cljs"):
        return frozenset({"node", "browser"})
    if name.endswith("_cljs_test.cljs"):
        return frozenset({"node"})
    return frozenset()


def _scan_proof_sites(
    repo_root: Path,
) -> tuple[dict[str, list[tuple[Path, frozenset[str]]]], dict[str, list[Path]]]:
    """`(proving, dead)` — for each `FH` id, the test files whose ASSERTIONS
    reach its fixture PAIRED WITH THE LANES those assertions run in, and the
    files that name it where no assertion can reach it.

    A file's lanes and a proof's lanes are not the same set.  The file's come
    from where the runners discover it; the proof's are those crossed with the
    platforms whose reading of the file contains the reaching test.  A
    `#?(:clj (deftest …))` in a `-cljs-test.cljc` file is discovered by both
    runners and asserts in only one.
    """
    proving: dict[str, list[tuple[Path, frozenset[str]]]] = {}
    dead: dict[str, list[Path]] = {}
    test_root = repo_root / TESTS_REL
    if not test_root.is_dir():
        return proving, dead
    asserting = _asserting_names(repo_root)
    for path in sorted(test_root.rglob("*")):
        file_lanes = _lanes_for(path)
        if path.suffix not in _CLJ_SUFFIXES or not file_lanes:
            continue
        text = path.read_text(encoding="utf-8")
        reached: dict[str, set[str]] = {}
        for platform in _platforms(path):
            for row_id in _proven_ids(text, platform, asserting):
                reached.setdefault(row_id, set()).update(
                    file_lanes & _PLATFORM_LANES[platform]
                )
        for row_id, lanes in sorted(reached.items()):
            proving.setdefault(row_id, []).append((path, frozenset(lanes)))
        # Named but unreachable — the shape a commented-out, reader-discarded or
        # never-read fixture leaves behind.  Read off the RAW text: the whole
        # point is to see what `_strip` threw away.
        for keyword in sorted(set(_FIXTURE_CALL_RE.findall(text))):
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
    `_compiled_witnesses`, which admits the checker for exactly that reason.
    """
    if host in ("jvm", "ssr"):
        return frozenset({"jvm"})
    if host == "browser":
        return (frozenset({"node"}) if mode == "common"
                else frozenset({"node", "browser"}))
    return _QUALIFIED_HOST_LANES


def _compiled_witnesses(repo_root: Path) -> frozenset[Path]:
    """The test files that EXERCISE the compiled tier.

    The lane axis cannot see the mode axis: `interpreted jvm` and `compiled jvm`
    are both served by the JVM lane, so a row relabelled from one to the other
    keeps every cell it claims served and the census had nothing to say about it.
    That is a claim parsed and never evidenced, and a merged PR audit called it
    what it was.

    `compiled` is the mode that can be witnessed, because it is the mode a
    declaration OPTS INTO.  Two surfaces count, and the second is not optional:

        {:compiled true}                 a declaration lowered at expansion
        re-frame.freehand.compiler.…     the compile tier itself

    The compile tier counts on its own because `FH-DIAG-001` is a compiled-mode
    law proven through the CHECKER, over declarations deliberately carrying no
    `{:compiled true}` — the exact row a narrower rule would have reddened.  The
    witness is read at SUITE granularity: the file, or a Freehand namespace it
    requires, since a suite whose compiled declarations live in a support
    namespace beside it is exercising the compiled tier just as squarely.

    `interpreted` and `common` get no such witness, and saying so is the honest
    half of this.  Interpreted is the DEFAULT lowering — a declaration is
    interpreted by carrying nothing — so there is no marker to demand, and a rule
    every proof satisfies would dress a green up as evidence rather than
    supplying any.  The census therefore evidences the mode axis in the one
    direction it can, and claims nothing in the other.
    """
    test_root = repo_root / TESTS_REL
    if not test_root.is_dir():
        return frozenset()
    ns_of: dict[Path, str] = {}
    requires: dict[Path, set[str]] = {}
    witnesses: set[Path] = set()
    for path in sorted(test_root.rglob("*")):
        if path.suffix not in _CLJ_SUFFIXES:
            continue
        cleaned = _strip(path.read_text(encoding="utf-8"))
        ns_form = next(
            (f for f in _top_level_forms(cleaned) if _NS_FORM_RE.match(f)), ""
        )
        named = _NS_FORM_RE.match(ns_form)
        if named:
            ns_of[path] = named.group(1)
        requires[path] = set(_FREEHAND_NS_RE.findall(ns_form))
        if (_COMPILED_DECLARATION_RE.search(cleaned)
                or _COMPILE_TIER_NS_RE.search(cleaned)):
            witnesses.add(path)

    by_ns = {ns: path for path, ns in ns_of.items()}
    changed = True
    while changed:
        changed = False
        for path, required in requires.items():
            if path in witnesses:
                continue
            if any(by_ns.get(ns) in witnesses for ns in required):
                witnesses.add(path)
                changed = True
    return frozenset(witnesses)


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
    compiled_witnesses = _compiled_witnesses(repo_root)
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
        for _path, proof_lanes in proving:
            lanes |= proof_lanes

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
            for host in hosts:
                serving = _cell_lanes(mode, host)
                if lanes & serving:
                    continue
                defects.append(
                    f"  UNEXECUTED CELL: {INDEX_REL.as_posix()}:{line_no} "
                    f"[{row_id}] claims the `{mode}` x `{host}` cell, which "
                    f"the {'/'.join(sorted(serving))} lane(s) prove (Spec 008 "
                    "#the-hostmode-matrix), but the tests that ASSERT on its "
                    f"fixture run in the {'/'.join(sorted(lanes)) or 'no'} "
                    "lane(s) - narrow the applicability cell or prove the law "
                    "where it claims to bind"
                )
            if mode == "compiled" and not any(
                path in compiled_witnesses for path, _lanes in proving
            ):
                where = ", ".join(sorted(p.name for p, _ in proving))
                defects.append(
                    f"  UNWITNESSED MODE: {INDEX_REL.as_posix()}:{line_no} "
                    f"[{row_id}] claims the `compiled` mode, but nothing in "
                    f"{where} exercises the compiled tier - no "
                    "`{:compiled true}` declaration and no "
                    "`re-frame.freehand.compiler.*` namespace, in the suite or "
                    "in a Freehand namespace it requires.  The JVM lane serves "
                    "`interpreted jvm` and `compiled jvm` alike, so without a "
                    "witness the mode token is a claim nobody checked: prove the "
                    "law through the compiled tier, or say `interpreted` / "
                    "`common` and mean it"
                )
        for host in hosts:
            needed |= _cell_lanes(mode, host)
        table.append((
            row_id,
            mode,
            " ".join(hosts),
            ",".join(l for l in LANES if l in lanes) or "-",
            ", ".join(p.name for p, _ in proving) or "-",
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
            p.relative_to(repo_root).as_posix() for p, _ in sites[row_id]
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
            "the `compiled` mode and each is proven through the compiled tier.  "
            "`interpreted` and `common` carry no mode witness and none is "
            "claimed: interpreted is the DEFAULT lowering, so there is no marker "
            "to demand (see `_compiled_witnesses`).\n"
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
            "`compiled` row(s) proven through the compiled tier; "
            f"{burnt} further id(s) addressed but not binding.\n"
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
        # Green: the same `compiled jvm` row, proven by a suite that lowers a
        # declaration at expansion.
        "census_compiled_declaration_witnesses_the_mode": (
            {"CALL": _row(applicability="compiled jvm")},
            {"a_cljs_test.cljc": "(ns x)\n"
                                 "(v/defview panel {:compiled true} [_] [:div])\n"
                                 + _proof().partition("\n")[2]},
            (),
            (),
        ),
        # Green: the FH-DIAG-001 shape.  A compiled-mode law proven through the
        # CHECKER, over declarations deliberately carrying no `{:compiled true}`
        # - the row a narrower witness would have reddened.
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
        # Green: the witness carried one hop, which is the shape the real
        # manifest suites have - the declarations live in a support namespace
        # beside the assertions.
        "census_compiled_witness_in_a_required_support_ns": (
            {"CALL": _row(applicability="compiled jvm")},
            {"support.cljc": "(ns re-frame.freehand.support)\n"
                             "(v/defview panel {:compiled true} [_] [:div])\n",
             "a_cljs_test.cljc": "(ns re-frame.freehand.a-cljs-test\n"
                                 "  (:require [re-frame.freehand.support :as sup]))\n"
                                 "(def fx (conf/fixture :FH-CALL-001))\n"
                                 "(deftest t (is (seq fx)) (is (some? sup/panel)))\n"},
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


def _run_self_tests(verbose: bool = False) -> int:
    cases: list[tuple[str, int]] = [
        ("clean", 0),
        ("empty", 0),
        ("clean_planned", 0),
        ("clean_qualified_host", 0),
        ("duplicate_id", 1),
        ("ill_formed_id", 1),
        ("unknown_area_in_id", 1),
        ("area_mismatch", 1),
        ("out_of_order_id", 1),
        ("gap_in_area", 1),
        ("citation_not_a_link", 1),
        ("citation_no_anchor", 1),
        ("citation_missing_file", 1),
        ("citation_missing_anchor", 1),
        ("citation_outside_spec", 1),
        ("fixture_missing", 1),
        ("fixture_absent_on_active", 1),
        ("fixture_on_planned", 1),
        ("applicability_unknown_token", 1),
        ("applicability_two_modes", 1),
        ("applicability_no_host", 1),
        ("applicability_no_mode", 1),
        ("bad_status", 1),
        ("bad_row_shape", 1),
        ("empty_law", 1),
        ("missing_area_section", 1),
        ("unknown_area_section", 1),
        ("duplicate_area_section", 1),
        ("bad_table_header", 1),
        # A block before the first section orphans both its header and its row.
        ("orphan_row", 2),
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
        ("census_proof_site_in_a_docstring", 1, "DEAD PROOF SITE"),
        ("census_comment_form_inside_a_deftest", 1, "DEAD PROOF SITE"),
        ("census_dead_branch_in_a_called_helper", 1, "DEAD PROOF SITE"),
        ("census_deftest_that_asserts_nothing", 1, "DEAD PROOF SITE"),
        ("census_helper_carries_the_assertion", 0, None),
        ("census_interpreted_jvm_row", 0, None),
        ("census_mode_relabelled_to_compiled", 1, "UNWITNESSED MODE"),
        ("census_compiled_declaration_witnesses_the_mode", 0, None),
        ("census_compile_tier_witnesses_the_mode", 0, None),
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
    failures = 0
    with tempfile.TemporaryDirectory(prefix="fh_index_selftest_") as tmp:
        base = Path(tmp)
        _build_self_test_fixtures(base)
        _build_census_fixtures(base)
        both: tuple[tuple, ...] = (
            (check, [(name, n, None) for name, n in cases]),
            (census, census_cases),
        )
        for guard, guard_cases in both:
            for fixture, expected, kind in guard_cases:
                root = base / fixture
                saved_stderr = sys.stderr
                sys.stderr = captured = _Captured()
                try:
                    got = guard(root, verbose=False)
                finally:
                    sys.stderr = saved_stderr
                if got != expected:
                    sys.stderr.write(
                        f"self-test FAIL: {fixture} expected defects={expected}, "
                        f"got {got}\n"
                    )
                    failures += 1
                elif kind and kind not in captured.text:
                    # The case red, but not for the reason it was written for —
                    # which is the same mistake the guard itself exists to catch.
                    fired = next(
                        (l.strip() for l in captured.text.splitlines()
                         if l.startswith("  ") and l.strip()),
                        "(no defect line)",
                    )
                    sys.stderr.write(
                        f"self-test FAIL: {fixture} red with the right COUNT and "
                        f"the wrong defect: expected {kind}, got {fired!r}\n"
                    )
                    failures += 1
                elif verbose:
                    sys.stderr.write(
                        f"self-test PASS: {fixture} (defects={got}"
                        f"{', ' + kind if kind else ''})\n"
                    )
    if failures:
        sys.stderr.write(f"\n{failures} self-test failure(s).\n")
        return 1
    if verbose:
        sys.stderr.write(
            f"all {len(cases) + len(census_cases)} self-tests passed.\n"
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
