#!/usr/bin/env python3
"""An optional module must be ABSENT when unused (rf2-hic-053).

The product invariant is one line — *optional libraries: named consumer
required; zero reachable production code when absent*
(`docs/design/hicasso/product/invariants.md`, section 1) — and under a
whole-program compiler it reduces to one structural fact: **nothing a
consumer reaches through the public door may lead to the module.**  A
single `:require` of `re-frame.hicasso.motion` in `re-frame.hicasso`
would put the retention machine, the phase transform and the React
component into the bundle of every application that ever aliased `h`,
and nothing about the running page would say so.

So the property is not observable from inside a test: by the time a
suite can ask a question, the suite itself has required the module.  It
is a property of the SOURCE, and this is where it is checked.

THE TWO EDGES
-------------
For each module below the gate asserts, over `src/`:

  1. **Nothing outside the module imports the module's door.**  This is
     the edge that matters: an import here is what makes the module
     reachable from the public door, whether it is written in
     `hicasso.cljc` itself or in any `impl.*` namespace the door leads
     to.  Scanning the whole of `src/` rather than only the door is
     deliberate — the door reaches every `impl.*` file transitively, so
     a require buried three namespaces down has exactly the same effect
     and would pass a door-only check.

  2. **Nothing outside the module imports the module's engine.**  The
     module's door is thin; its weight is in the namespaces it names as
     private.  Were the door alone protected, a stray
     `impl.presence-react` require would drag the same code in past a
     green gate.

Both edges are checked over `:require` / `:require-macros` FORMS, never
over prose: a docstring that names a namespace is provenance, and this
gate follows `frozen-sources.edn`'s rule of parsing the ns form rather
than grepping the file.

THE ROSTER ALSO DRIVES THE COMPILE (rf2-okhdf)
----------------------------------------------
`--module-namespaces` prints [[MODULES]]'s doors and engines, one per
line, and `hicasso/test/re_frame/bench/hicasso/compile_gate.cjs` reads
that list to decide what it compiles.  The two arms are the SAME roster
read in opposite directions, which is why the emission lives here rather
than as a second list over there: this file forbids anything outside a
module from requiring it, and the compile gate then compiles it anyway,
by name, because nothing else will.

That second consumer exists because the first one's success created the
hole.  An optional module is unreachable from the public door BY
CONSTRUCTION — that is the invariant above, and it holds — so the
`:hicasso-release` bundle contains none of this code, and neither does
any other compile that starts from the door.  Measured on the produced
bundle: `anchorName`, `positionAnchor`, `showPopover`, `rf-overlay-` and
`buffered-field` all answer ZERO, against `hicasso` at 75.  Nothing was
wrong with that; what was wrong is that no compile which treats warnings
as failures ever reached the code either, so four `:infer-warning`s on
`(.. el -style -anchorName)` sat in `impl/overlay.cljs` until a worker
read them off a browser build by hand (rf2-9zz0y).  Under `:advanced`
that property would have been renamed and the trigger claim would have
broken silently.

A NEW MODULE IS THEREFORE COVERED BY CONSTRUCTION, which is the whole
point of emitting rather than restating.  Adding a row below puts the
module under the compile gate in the same edit that puts it under this
one; there is no second place to remember.  What neither arm can do is
notice a module that was never rowed at all — but that is already true
of the reachability check, so the roster is not one more thing to keep
in step, it is the one thing, and it has always been.

WHAT THIS GATE IS NOT
---------------------
It is not a bundle measurement.  A release build with the module absent
would weigh the claim in bytes, and that is a heavier instrument than
the property needs: the module is unreachable or it is not, and
reachability is decided in the source.  What a byte count would add is
confidence about the COMPILER, which is not what regresses here.

For the `native` row there IS such a companion, and the division of
labour is worth stating because neither instrument subsumes the other.
`scripts/check_bundle_isolation.cjs` (rf2-hic-034) reads the
`:hicasso-release` bundle for the tier's two sentinel strings, which is
the linker's half of the same claim — but it can only see the parts of
the tier that have a production footprint, and a consumer who writes
only `n/$` with literal props leaves none at all.  THIS gate is the one
that decides the property exhaustively, because a `:require` is a
`:require` whether or not Closure keeps anything from it.

THE FORBIDDEN IMPORT, WHICH IS A DIFFERENT SHAPE
------------------------------------------------
Everything above is a RELATIVE claim — module `m` is unreachable from
everything that is not `m` — and the roster names both sides of each
edge.  The native-boundary law's other half is ABSOLUTE and has only
one side: *an interpreted-only production dependency graph and bundle
contain neither native-tier runtime **nor UIx code***
(`docs/design/hicasso/product/lanes/design-laws.md`, clause 6).  UIx is
not a Hicasso module that must stay unreached; it is a library this
package must never require AT ALL, from anywhere in `src/`.

That clause fell through the seam between two honest artefacts
(rf2-b3gy).  `check_bundle_isolation.cjs` declines it in terms and says
why — the measured `:hicasso-release` bundle CONTAINS UIx and that is
correct, because Hicasso ships no reactive adapter and the exemplar
consumer picks one — and it refers the clause here, to "the source-side
gate's kind of question".  This file's `native` row then quoted the law
in full and called itself "the DEPENDENCY-GRAPH half of that sentence"
while asking only the module question.  Each file was right about
itself; the clause was measured by neither.  So it is measured here
now, in TWO ARMS, which is the shape
`implementation/scripts/check-ui-adapter-isolation.cjs` already uses
for the same claim about `re-frame2-ui`:

  1. **SOURCE** — no namespace under `src/` requires UIx.  Read off
     `:require` forms by the same parser the module rows use, so the
     seventeen places this package's docstrings NAME UIx stay legal:
     prose is provenance, and `impl/controlled.cljs` citing
     `uix/compiler/input.cljs` by line number is exactly the kind of
     provenance that must not redden a gate.
  2. **COORDINATE** — the production `:deps` of `hicasso/deps.edn`
     names no UIx artefact.  This arm has a live subject rather than a
     theoretical one: the `:test` alias DOES declare
     `day8/re-frame2-uix`, legally and necessarily — the Node lane's
     suites need a reactive substrate, and plain-atom has none — so the
     property is not "the file never says uix" but "the PRODUCTION map
     never does".  A gate that grepped the file would be red today, and
     promoting that test dependency into `:deps` is the realistic way
     this clause breaks.

WHAT THE SECOND CLAUSE NEEDED, AND WHY IT DID NOT NEED A BUNDLE
---------------------------------------------------------------
Row 6 of the conformance matrix carries a second clause — *native
bundles contain no UIx unless the application imports it* — and it was
read as owing a native-tier release build, which does not exist: there
is exactly one release build, `:hicasso-release`, and it is the
interpreted-only one.

It owes no such build.  Split the clause and it is two conjuncts: what
the APPLICATION imports is the application's own affair and nothing
this package can assert about, and what remains is *the native tier's
own source drags no UIx in* — which is arm 1, over `native.cljc` along
with every other file in `src/`, decided exhaustively.  A release build
would weigh the same claim in bytes and add confidence about the
COMPILER, and the paragraph above already says why this gate declines
that instrument for the module rows: reachability is decided in the
source, and the compiler is not what regresses here.  The clause is
therefore measured, and by the arm that can see the parts of the tier a
bundle cannot — a consumer who writes only `n/$` with literal props
leaves no production footprint at all.

SELF-TEST
---------
`--self-test` runs the detector over synthetic sources first, because a
scan that silently stopped finding requires would report a clean tree
forever.  It pins both directions — a require IS flagged, a docstring
mention is NOT — and asserts the roster's own premise: every module
door and engine file named below must exist, so a renamed file fails
here rather than reporting a green absence for a namespace nobody
compiles any more.

The forbidden-import arms are pinned the same way, and arm 2 carries a
PRESENT control of its own: the real `deps.edn`'s `:test` alias must
still declare the UIx coordinate.  Without that row a green arm 2 could
mean "the production map is clean" or "nobody mentions uix anywhere any
more", and only the first is the claim.
"""

import re
import sys
from pathlib import Path

PACKAGE_ROOT = Path(__file__).resolve().parent.parent
SRC = PACKAGE_ROOT / "src"
DEPS_EDN = PACKAGE_ROOT / "deps.edn"

# rf2-b3gy.  The libraries no `src/` namespace may require and no production
# coordinate may name.  One entry today; the list shape is what keeps a second
# one from being a rewrite.
#
# `prefixes` are matched as NAMESPACE SEGMENTS rather than as substrings, so
# `uix` catches `uix.core` and `uix.dom.server` and does NOT catch a namespace
# that merely begins with those letters.  There is no such namespace in this
# tree today; the segment rule is what stops one from being a silent hole
# later, and it costs a `split(".")`.
FORBIDDEN_IMPORTS = [
    {
        "name": "UIx",
        "prefixes": ["uix", "re-frame.adapter.uix"],
        # Any coordinate whose symbol names this library. Matched
        # case-insensitively over the whole symbol, because a coordinate is a
        # published name rather than a namespace and `day8/re-frame2-uix`
        # shares no segment with `uix.core`.
        "coordinate_marker": "uix",
        # ASCII only, like every other emitted string in this file. The
        # message is written to `stderr`, and a `cp1252` console raises
        # `UnicodeEncodeError` on an em dash -- which would turn this gate's
        # RED into a traceback on the one platform where a maintainer is
        # most likely to read it first.
        "why": (
            "native-boundary law clause 6 says an interpreted-only production "
            "dependency graph contains no UIx code. Hicasso ships no reactive "
            "adapter: the consumer picks one, and this package must not pick "
            "for them"
        ),
    },
]

NS_FORM = re.compile(r"\(ns\s+([A-Za-z0-9_.*+!?<>=$%&/-]+)")

# Each optional module: its public door, the private namespaces that carry its
# weight, and the files that must exist for the roster to mean anything.
MODULES = [
    {
        "name": "motion",
        "door": "re-frame.hicasso.motion",
        "engine": [
            "re-frame.hicasso.impl.presence",
            "re-frame.hicasso.impl.presence-react",
        ],
        "files": [
            "re_frame/hicasso/motion.cljs",
            "re_frame/hicasso/impl/presence.cljs",
            "re_frame/hicasso/impl/presence_react.cljs",
        ],
    },
    {
        # rf2-hic-052.  The overlay module's shape is `motion`'s exactly:
        # a thin door onto one private namespace that carries its weight,
        # so both edges have something to guard and the door-only check
        # would be the hole it was written to close.
        "name": "overlay",
        "door": "re-frame.hicasso.overlay",
        "engine": [
            "re-frame.hicasso.impl.overlay",
        ],
        "files": [
            "re_frame/hicasso/overlay.cljs",
            "re_frame/hicasso/impl/overlay.cljs",
        ],
    },
    {
        # rf2-hic-034.  The native tier is the module the native-boundary
        # law names in terms: *the native namespace is separately
        # reachable; an interpreted-only production dependency graph and
        # bundle contain neither native-tier runtime nor UIx code*
        # (`docs/design/hicasso/product/lanes/design-laws.md`).  This row
        # is the NATIVE-TIER half of that sentence's dependency graph; the
        # *nor UIx* half is [[FORBIDDEN_IMPORTS]] below, and it is a
        # different shape rather than another roster entry — see the
        # module docstring.  The row used to claim the whole sentence and
        # ask only this much of it, which is how the clause came to fall
        # between this file and `check_bundle_isolation.cjs` (rf2-b3gy).
        #
        # NO ENGINE, and the emptiness is the design rather than an
        # omission.  `motion`'s weight is in two private namespaces it
        # alone reaches, so protecting its door alone would leave a
        # `impl.presence-react` require as an unguarded way in.  The
        # native tier owns exactly one file: everything else it touches —
        # `impl.error`, `impl.slot`, `impl.collector` — is the SHARED
        # runtime that the public door already reaches, so naming any of
        # them here would forbid the interpreted tier its own machinery.
        # There is therefore one edge to guard and it is the door.
        #
        # The two-languages fence is what makes that true: `n/$` reads its
        # own form and never a `defview` body, so no interpreted path has
        # any reason to reach for the tier, and neither embedding bridge
        # names it — `h/as-component` outward, `h/defhost` and `[:>]`
        # inward, both of which cross to a foreign React component without
        # caring which tier minted it (rf2-hic-032).  A require appearing
        # here would therefore be a genuine architectural change and not a
        # convenience.
        "name": "native",
        "door": "re-frame.hicasso.native",
        "engine": [],
        "files": [
            "re_frame/hicasso/native.cljc",
        ],
    },
    {
        # rf2-sh56.  The forms module — `forms/buffered-field` and the
        # three events its commit protocol is made of — shipped into v0
        # by operator ruling because `docs/core/hicasso/05-forms.md`
        # documents it and the code owed the chapter a namespace.
        #
        # NO ENGINE, for the `native` row's reason and not for a
        # different one.  This module owns exactly one file; everything
        # it reaches — `re-frame.events`, `re-frame.fx`, the public door
        # and `impl.state` — is either core's or the SHARED runtime the
        # public door already leads to, so naming any of them here would
        # forbid the rest of the package its own machinery.
        #
        # The direction that matters is therefore the door, and it is a
        # live risk rather than a theoretical one: `buffered-field` is a
        # view, and a single convenience `:require` in `hicasso.cljc` —
        # to re-export it beside `h/portal`, say — would put the
        # protocol, the `reg-state` concern and the boundary into the
        # bundle of every application that ever aliased `h`.  The public
        # door names no optional module, and this row is what keeps that
        # true.
        "name": "forms",
        "door": "re-frame.hicasso.forms",
        "engine": [],
        "files": [
            "re_frame/hicasso/forms.cljs",
        ],
    },
]

def ns_of(text):
    """The namespace a source file declares, or None. Read from code rather
    than prose for the same reason the requires are — a docstring here opens
    with `(ns my.app …)` as its usage example.

    The FIRST `(ns …)` in the live text, which is only the file's own because
    [[code_only]] has already dropped the forms the reader discards.  Before
    it did, a `#_(ns re-frame.hicasso.motion)` sitting above the real form won
    the search and [[scan]] then exempted the file as the module's own engine
    — the whole reachability check switched off for it, silently and green
    (rf2-df9b).
    """
    m = NS_FORM.search(code_only(text))
    return m.group(1) if m else None


OPENERS = "([{"
CLOSERS = ")]}"


def form_end(text, i):
    """Index just past the ONE form beginning at or after `text[i]`.

    `text` has already been through [[code_only]]'s string, comment and
    character-literal passes, so every remaining bracket is structural.
    """
    n = len(text)
    while i < n and text[i].isspace():
        i += 1
    if i >= n:
        return n
    if text[i] not in OPENERS:  # a bare symbol, keyword or number
        while i < n and not text[i].isspace() and text[i] not in OPENERS + CLOSERS:
            i += 1
        return i
    depth = 0
    while i < n:
        ch = text[i]
        if ch in OPENERS:
            depth += 1
        elif ch in CLOSERS:
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return n


# `(comment …)` and not `(comment-on …)`: the lookahead is the symbol-tail
# class, so only the bare core form opens a comment.
COMMENT_FORM_RE = re.compile(r"\(\s*comment(?![\w.*+!?<>=$%&|:'/-])")


def strip_inert(text):
    """`text` with the three INERT form shapes blanked out (rf2-df9b).

    A form the reader discards is not a claim about anything, and this gate
    decides two questions from claims — which namespace a file DECLARES, and
    which it REQUIRES:

        #_(ns re-frame.hicasso.motion)        reader discard
        (comment (ns re-frame.hicasso.motion))  comment macro
        '(ns re-frame.hicasso.motion)         quoted

    Those three and no more.  DELIBERATELY NOT HANDLED, because none occurs
    in this tree and a general Clojure reader is a far larger artefact than
    this gate deserves: reader conditionals (`#?(:clj …)`), dead-by-value
    branches (`(when false …)`), stacked discards (`#_#_`), `#_` applied to
    a set or prefixed form (`#_#{…}`, `#_'x`), a fully-qualified
    `(clojure.core/comment …)`, and `comment` shadowed by a local binding.

    Blanking preserves length and newlines so the surviving text keeps its
    offsets, and the walk resumes at the END of each inert form — a live
    form sitting beside a discarded one still counts, which is what stops
    this narrowing from becoming a false RED of its own.

    This function and [[form_end]] are DELIBERATELY duplicated from
    check_example_fence_coverage.py rather than shared (rf2-t9t0).  The
    copies are NOT identical and must not be naively merged: [[code_only]]
    has already blanked every character literal before these run, so both
    copies here drop the `\\` branches that script's pair needs.  Sharing
    would mean a flag argument on a twenty-line pure function, and these
    six checkers are deliberately self-contained single files with no
    cross-imports — `self_test` and `main` are duplicated six times over
    for the same reason.
    """
    chars = list(text)
    i, n = 0, len(text)
    while i < n:
        ch = text[i]
        if text.startswith("#_", i):
            start, body = i, i + 2
        elif ch in "'`" and (i == 0 or text[i - 1].isspace() or text[i - 1] in OPENERS):
            # `'` is a legal SYMBOL character in Clojure (`next'`), so it
            # quotes only where a form may start. Without that guard the gate
            # would blank a LIVE require and report an unreachable module.
            start, body = i, i + 1
        elif COMMENT_FORM_RE.match(text, i):
            start, body = i, i  # the `(comment …)` form is inert entire
        else:
            i += 1
            continue
        end = form_end(text, body)
        for j in range(start, end):
            if chars[j] != "\n":
                chars[j] = " "
        i = end
    return "".join(chars)


def code_only(text):
    """`text` reduced to the forms the reader would actually EVALUATE.

    Everything downstream reads CODE, and this is the one place that
    distinction is made.  It has teeth: this package's namespaces document
    themselves at length, and both the public door and the motion module
    carry a worked `(:require …)` EXAMPLE inside their docstrings.  A scanner
    that looked for `:require` in the raw text found those examples and
    reported the door as importing the module it had just been changed to
    stop importing — a false red that was caught only because the live scan
    was run against a tree already known to be clean.

    `\\"` is a character literal in Clojure, not a string, so a backslash
    outside a string consumes the character after it; inside a string it is
    the escape it looks like.

    Prose is only half of what is not code, though, and the other half read
    green for longer: a form the reader DISCARDS is not a claim either, so
    [[strip_inert]] runs last over what survives.  It runs last because the
    passes above are what make its walk safe — a `)` inside a string or a
    line comment would otherwise unbalance it — and because by then every
    character literal is gone, so every remaining bracket is structural.
    """
    out = []
    i, n = 0, len(text)
    in_string = False
    while i < n:
        ch = text[i]
        if in_string:
            if ch == "\\":
                i += 2
                continue
            if ch == '"':
                in_string = False
            i += 1
            continue
        if ch == "\\":  # character literal: \" \newline \a
            out.append(" ")
            i += 2
            continue
        if ch == ";":
            while i < n and text[i] != "\n":
                i += 1
            continue
        if ch == '"':
            in_string = True
            out.append(" ")
            i += 1
            continue
        out.append(ch)
        i += 1
    return strip_inert("".join(out))


def required_namespaces(text):
    """Every namespace named inside this file's `:require` /
    `:require-macros` clauses.

    The clause is read by BRACKET DEPTH from the `:require` keyword rather
    than by a line regex, so `[a.b :as c]`, a bare `a.b`, a reader-conditional
    branch and a multi-line clause all reduce to the same set.  Prose is
    already gone: [[code_only]] removed it before this ran, which is what
    lets a docstring name a namespace as provenance — the same allowance
    `frozen-sources.edn` makes for its own import gate.
    """
    source = code_only(text)
    found = set()
    for kw in (":require", ":require-macros"):
        start = 0
        while True:
            i = source.find(kw, start)
            if i < 0:
                break
            start = i + len(kw)
            depth = 1  # we are inside the enclosing `(:require …)` list
            j = start
            while j < len(source) and depth > 0:
                ch = source[j]
                if ch in "([":
                    depth += 1
                elif ch in ")]":
                    depth -= 1
                j += 1
            clause = source[start:j]
            for token in re.findall(r"[A-Za-z][A-Za-z0-9_.*+!?<>=$%&-]*", clause):
                if "." in token:
                    found.add(token)
    return found


def owned_namespaces(module):
    """The namespaces `module` OWNS — its door and its engine.

    The one place that set is spelled, because both directions of the roster
    read it: [[scan]] forbids anything outside the module from requiring it,
    and [[module_namespaces]] hands it to the compile gate to be compiled.
    Two spellings of the same set is how one arm silently stops covering what
    the other one guards.
    """
    return {module["door"], *module["engine"]}


def module_namespaces():
    """Every namespace the optional modules own, sorted (rf2-okhdf).

    Printed one per line by `--module-namespaces` and read by
    `compile_gate.cjs`, which compiles them under `:infer-externs :auto` with
    warnings treated as failures. See the module docstring for why that
    consumer exists and why it reads the roster rather than restating it.
    """
    found = set()
    for module in MODULES:
        found |= owned_namespaces(module)
    return sorted(found)


def scan(sources):
    """`sources` is `{path: text}`. Answer the list of violation strings."""
    problems = []
    for module in MODULES:
        owned = owned_namespaces(module)
        for path, text in sorted(sources.items()):
            declared = ns_of(text)
            if declared in owned:
                continue  # the module may of course reach its own engine
            reached = required_namespaces(text) & owned
            for target in sorted(reached):
                problems.append(
                    "{path}: `{declared}` requires `{target}`, which belongs to "
                    "the OPTIONAL `{name}` module. An optional module must be "
                    "absent from an application that never requires it, and "
                    "this import makes it reachable from the public door. "
                    "Require it in the application instead.".format(
                        path=path,
                        declared=declared,
                        target=target,
                        name=module["name"],
                    )
                )
    return problems


SYMBOL_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_.*+!?<>=$%&/-:"
SYMBOL_RE = re.compile(r"[A-Za-z][A-Za-z0-9_.*+!?<>=$%&/-]*")


def top_level_value(text, key):
    """The form following `key` at the TOP LEVEL of `text`'s outermost map.

    `deps.edn` is one map and arm 2 asks about exactly one of its entries.
    DEPTH is what makes that question answerable at all: `:aliases` holds an
    `:extra-deps` map that legally names the very coordinate the production
    `:deps` map may not, so a reader that ignored nesting would fold the two
    together and report a correct file red.

    Answers None when the key is absent at that depth, and the caller treats
    that as a FAILURE rather than as a pass — a `deps.edn` this arm could not
    read is not a clean one.
    """
    source = code_only(text)
    depth, i, n = 0, 0, len(source)
    while i < n:
        ch = source[i]
        if ch in OPENERS:
            depth += 1
        elif ch in CLOSERS:
            depth -= 1
        elif depth == 1 and source.startswith(key, i):
            before = source[i - 1] if i else " "
            after = source[i + len(key)] if i + len(key) < n else " "
            if before not in SYMBOL_CHARS and after not in SYMBOL_CHARS:
                start = i + len(key)
                return source[start:form_end(source, start)]
        i += 1
    return None


def names_library(namespace, prefixes):
    """Does `namespace` belong to a library named by `prefixes`?

    Compared SEGMENT-wise rather than by `startswith`, so `uix` claims
    `uix.core` and `uix.dom.server` and does not claim a hypothetical
    `uixels.foo`. Nothing in this tree is named that way; the rule is what
    keeps the arm honest if something ever is.
    """
    for prefix in prefixes:
        want = prefix.split(".")
        if namespace.split(".")[: len(want)] == want:
            return True
    return False


def scan_forbidden_imports(sources):
    """ARM 1 (rf2-b3gy). `sources` is `{path: text}`; answer the violations.

    Reuses [[required_namespaces]] rather than grepping, which is the whole
    reason this arm belongs in this file: seventeen docstrings across `src/`
    name UIx as provenance — `impl/controlled.cljs` cites
    `uix/compiler/input.cljs` by line number, `native.cljc` explains that a
    UIx parent must not have to require the tier — and every one of them
    must stay legal. A grep-based arm would have to be deleted or exempted
    file by file within a week of landing.
    """
    problems = []
    for rule in FORBIDDEN_IMPORTS:
        for path, text in sorted(sources.items()):
            declared = ns_of(text)
            for target in sorted(required_namespaces(text)):
                if names_library(target, rule["prefixes"]):
                    problems.append(
                        "{path}: `{declared}` requires `{target}`, which is "
                        "{name}. Production Hicasso source may not require it, "
                        "because {why}. Prose naming {name} is provenance and "
                        "stays legal; a `:require` is not prose.".format(
                            path=path,
                            declared=declared,
                            target=target,
                            name=rule["name"],
                            why=rule["why"],
                        )
                    )
    return problems


def scan_forbidden_coordinates(deps_text):
    """ARM 2 (rf2-b3gy): the production `:deps` map names no forbidden library.

    Only the top-level `:deps` map. The `:test` alias's `:extra-deps` names
    `day8/re-frame2-uix` and MUST go on naming it — the Node lane's suites
    need a reactive substrate and plain-atom has none, so a subscription
    under it would never notify and every commit assertion would pass
    vacuously. The self-test pins that as a present control, because a green
    arm 2 over a file that had simply stopped mentioning UIx would read
    identically to the one that matters.
    """
    production = top_level_value(deps_text, ":deps")
    if production is None:
        return [
            "deps.edn: no top-level `:deps` map. This arm reads the PRODUCTION "
            "dependency map and could not find one, so its silence would mean "
            "nothing — which is the failure an absence check is most exposed to."
        ]
    problems = []
    for rule in FORBIDDEN_IMPORTS:
        marker = rule["coordinate_marker"].lower()
        for token in SYMBOL_RE.findall(production):
            if marker in token.lower():
                problems.append(
                    "deps.edn: the production `:deps` map declares `{token}`, "
                    "which is {name}, and {why}. If a suite needs it, it "
                    "belongs in the `:test` alias's `:extra-deps`, where it "
                    "already is.".format(
                        token=token, name=rule["name"], why=rule["why"]
                    )
                )
    return problems


def read_deps_edn():
    return DEPS_EDN.read_text(encoding="utf-8")


def read_src():
    return {
        str(p.relative_to(PACKAGE_ROOT)).replace("\\", "/"): p.read_text(
            encoding="utf-8"
        )
        for p in sorted(SRC.rglob("*"))
        if p.suffix in (".cljs", ".cljc", ".clj")
    }


def self_test():
    # THIS SELF-TEST'S OWN CLAIMS MUST NOT BE DELETABLE (rf2-uyhh). `python -O`
    # — and `PYTHONOPTIMIZE` in the environment, which needs no flag at the
    # call site — strips every `assert` below, leaving a function that runs to
    # its success line having verified nothing. That is a control failing
    # GREEN, which is this gate's own subject: rf2-df9b below is the same
    # shape one level down, a form the reader drops still counted as a claim.
    # The check is empirical rather than a reading of `__debug__`, so it also
    # catches a `.pyc` compiled under `-O` and run without it.
    try:
        assert False
    except AssertionError:
        pass
    else:
        raise SystemExit(
            "assertions are disabled (python -O / PYTHONOPTIMIZE), so this "
            "self-test would prove nothing. Re-run without -O."
        )

    door = "re-frame.hicasso.motion"
    engine = "re-frame.hicasso.impl.presence-react"

    # A require of the door IS flagged.
    flagged = scan(
        {
            "src/x.cljs": "(ns re-frame.hicasso\n"
            "  (:require [{}  :as m]))".format(door)
        }
    )
    assert len(flagged) == 1, flagged
    assert door in flagged[0], flagged

    # A require of the ENGINE is flagged too, from anywhere in src/.
    flagged = scan(
        {
            "src/y.cljs": "(ns re-frame.hicasso.impl.boundary\n"
            "  (:require [{} :as p]))".format(engine)
        }
    )
    assert len(flagged) == 1, flagged
    assert engine in flagged[0], flagged

    # Prose is NOT a require: a docstring naming the module stays clean.
    clean = scan(
        {
            "src/z.cljs": '(ns re-frame.hicasso\n  "See {} and {}."\n'
            "  (:require [re-frame.hicasso.impl.codec :as codec]))".format(
                door, engine
            )
        }
    )
    assert clean == [], clean

    # And prose is not a require even when the prose IS a require: both real
    # namespaces carry a worked `(:require …)` example in their docstrings,
    # and the first draft of this gate reported the door as importing the
    # module on the strength of one. The fixture that missed it had a
    # docstring without a `:require` in it, which is exactly the fixture a
    # green would have been meaningless against.
    clean = scan(
        {
            "src/q.cljs": '(ns re-frame.hicasso\n'
            '  "Require the module yourself:\n\n'
            "      (:require [re-frame.hicasso :as h]\n"
            "                [{} :as motion])\n\n"
            '  and a character literal \\" must not open a string either."\n'
            "  (:require [re-frame.hicasso.impl.codec :as codec]))".format(door)
        }
    )
    assert clean == [], clean

    # A `;` comment is not code either — a require commented out is not one.
    clean = scan(
        {
            "src/c.cljs": "(ns re-frame.hicasso\n"
            "  ;; (:require [{} :as motion])\n"
            "  (:require [re-frame.hicasso.impl.codec :as codec]))".format(door)
        }
    )
    assert clean == [], clean

    # The module's own door may reach its own engine.
    clean = scan(
        {
            "src/m.cljs": "(ns {}\n  (:require [{} :as p]))".format(door, engine)
        }
    )
    assert clean == [], clean

    # AN INERT FORM IS NOT A DECLARATION (rf2-df9b), and the exemption above
    # is what gave that teeth: `scan` skips any file whose declared ns the
    # module owns, so a file that could pass itself off as the engine escaped
    # the reachability check entirely. `ns_of` took the FIRST `(ns …)` in the
    # file, and a discarded one sits happily above the real one.
    #
    # The rows below share one body and differ only in what precedes it, so
    # what they compare is the DISGUISE and nothing else.
    body = "(ns re-frame.hicasso.core\n  (:require [{} :as mo]))".format(door)

    # The honest file is flagged. This row is what keeps the three reds below
    # honest: a checker that had simply stopped exempting owned modules would
    # satisfy them all and be worth nothing.
    flagged = scan({"src/honest.cljs": body})
    assert len(flagged) == 1, flagged
    assert door in flagged[0], flagged

    # None of these three declares anything, so none may claim the exemption.
    # Each was reproduced GREEN — the whole file exempted — against the
    # landed scanner.
    for disguise in (
        "#_(ns {})\n".format(door),
        "(comment (ns {}))\n".format(door),
        "(def forms ['(ns {})])\n".format(door),
    ):
        evader = scan({"src/evader.cljs": disguise + body})
        assert len(evader) == 1, (disguise, evader)
        assert door in evader[0], (disguise, evader)

    # And the exemption still WORKS, which is the half a checker reading
    # every file would fail: the door declared for real, discard above it.
    clean = scan(
        {
            "src/m.cljs": "#_(ns re-frame.hicasso.core)\n"
            "(ns {}\n  (:require [{} :as p]))".format(door, engine)
        }
    )
    assert clean == [], clean

    # Blanking stops at the END of the inert form rather than running to the
    # end of the file — otherwise every file with a `#_` at the top would
    # lose its ns and be scanned as an anonymous one.
    assert ns_of("#_(ns {})\n(ns re-frame.hicasso.core)".format(door)) == \
        "re-frame.hicasso.core"

    # `'` closes over a form only where a form may START, because it is also
    # a legal symbol character. `next'` must not swallow the require after it
    # — that direction is a false GREEN, this gate's own subject.
    flagged = scan(
        {
            "src/t.cljs": "(ns re-frame.hicasso.core\n"
            "  (:require [{} :as mo]))\n(def next' 1)".format(door)
        }
    )
    assert len(flagged) == 1, flagged

    # A require the reader discards is not a require: the module stays absent
    # from the bundle, so flagging it would be a false RED.
    clean = scan(
        {
            "src/d.cljs": "(ns re-frame.hicasso.core\n"
            "  (:require #_[{} :as mo] [re-frame.hicasso.impl.codec :as c]))".format(
                door
            )
        }
    )
    assert clean == [], clean

    # rf2-hic-034 — the native tier's door, required from a `src/`
    # namespace that is not it.  Driven as its own case rather than left
    # to the generic loop above: that row has an engine and this one does
    # not, so the fixtures written for `motion` exercise a shape the
    # native row does not have, and a roster entry nothing drives is an
    # entry that can stop working in silence.
    flagged = scan(
        {
            "src/n.cljs": "(ns re-frame.hicasso.impl.codec\n"
            "  (:require [re-frame.hicasso.native :as n]))"
        }
    )
    assert len(flagged) == 1, flagged
    assert "re-frame.hicasso.native" in flagged[0], flagged
    assert "`native` module" in flagged[0], flagged

    # rf2-sh56 — the forms module's door, required from a `src/`
    # namespace that is not it.  Driven as its own case for the reason
    # the native one is: a roster entry nothing drives is an entry that
    # can stop working in silence.  The fixture is the exact shape the
    # risk has — the public door re-exporting the module's view.
    flagged = scan(
        {
            "src/f.cljs": "(ns re-frame.hicasso\n"
            "  (:require [re-frame.hicasso.forms :as forms]))"
        }
    )
    assert len(flagged) == 1, flagged
    assert "re-frame.hicasso.forms" in flagged[0], flagged
    assert "`forms` module" in flagged[0], flagged

    # And the direction that must stay clean: the module reaching the
    # public door and the shared `impl.state` is exactly how it is
    # written, and neither is an import OF the module.
    clean = scan(
        {
            "src/g.cljs": "(ns re-frame.hicasso.forms\n"
            "  (:require [re-frame.hicasso :as h]\n"
            "            [re-frame.hicasso.impl.state :as impl-state]))"
        }
    )
    assert clean == [], clean

    # And the shape the real tree actually has: `impl/codec.cljs` carries
    # four `[[re-frame.hicasso.native/…]]` docstring links today.  Prose is
    # provenance, so the live scan below has to stay green over them.
    clean = scan(
        {
            "src/p.cljs": '(ns re-frame.hicasso.impl.codec\n'
            '  "The sibling door [[re-frame.hicasso.native/declared-server]]."\n'
            "  (:require [re-frame.hicasso.impl.slot :as slot]))"
        }
    )
    assert clean == [], clean

    # rf2-hic-052 — the overlay row, driven for a different reason than
    # the native one above.  Its SHAPE is `motion`'s, so the fixtures at
    # the top already exercise the code path; what they cannot exercise is
    # whether THIS row names the namespaces the tree actually declares. A
    # typo in either string leaves the live scan green (nothing requires a
    # namespace that does not exist), and the file check below would still
    # pass because it reads paths rather than ns forms.
    flagged = scan(
        {
            "src/o.cljs": "(ns re-frame.hicasso\n"
            "  (:require [re-frame.hicasso.overlay :as overlay]\n"
            "            [re-frame.hicasso.impl.overlay :as impl-overlay]))"
        }
    )
    assert len(flagged) == 2, flagged
    assert "`overlay` module" in flagged[0], flagged
    for module in MODULES:
        if module["name"] == "overlay":
            for ns in [module["door"], *module["engine"]]:
                declared = {
                    ns_of(SRC.joinpath(rel).read_text(encoding="utf-8"))
                    for rel in module["files"]
                }
                assert ns in declared, (ns, declared)

    # The roster's premise: every file it names exists. A rename must fail
    # here rather than report a green absence for a file nobody compiles.
    for module in MODULES:
        for rel in module["files"]:
            path = SRC / rel
            assert path.exists(), "roster names a missing file: {}".format(path)

    # -----------------------------------------------------------------------
    # rf2-okhdf — the emission the compile gate reads
    # -----------------------------------------------------------------------

    # It is the roster and nothing but the roster. Both directions are pinned
    # because both are silent when they break: a MISSING namespace leaves the
    # compile gate green over code it no longer compiles, which is the exact
    # defect this emission was added to close, and an EXTRA one names a
    # namespace `compile_gate.cjs` would then fail to resolve — a red that
    # would send its reader to the wrong file.
    emitted = module_namespaces()
    assert emitted == sorted(set(emitted)), emitted
    for module in MODULES:
        for ns in owned_namespaces(module):
            assert ns in emitted, (ns, emitted)
    assert set(emitted) == {
        ns for module in MODULES for ns in owned_namespaces(module)
    }, emitted

    # And every emitted namespace is one this package really DECLARES. The
    # roster's file rows are checked just above, but they are checked as
    # PATHS: a door renamed inside its file leaves those green and hands the
    # compile gate a namespace that does not exist. Read off `src/` rather
    # than off `module["files"]` so the two roster columns cannot agree with
    # each other while both being wrong.
    declared = {ns_of(text) for text in read_src().values()}
    for ns in emitted:
        assert ns in declared, (
            "the roster emits `{}`, which no file under src/ declares. "
            "compile_gate.cjs would be handed a namespace that cannot be "
            "resolved.".format(ns)
        )

    # -----------------------------------------------------------------------
    # rf2-b3gy — the forbidden-import arms
    # -----------------------------------------------------------------------

    # ARM 1, the direction that must RED: a require of UIx from `src/`, by
    # either of the two spellings a real slip would take. `uix.core` is what
    # an author writing a component reaches for; `re-frame.adapter.uix` is
    # what an author wiring a substrate reaches for, and it is the likelier
    # of the two — the test tree requires it in eleven namespaces, so it is
    # already in the fingers of anyone working here.
    for spelling in ("uix.core", "uix.dom.server", "re-frame.adapter.uix"):
        flagged = scan_forbidden_imports(
            {
                "src/u.cljs": "(ns re-frame.hicasso.impl.collector\n"
                "  (:require [{} :as u]))".format(spelling)
            }
        )
        assert len(flagged) == 1, (spelling, flagged)
        assert spelling in flagged[0], (spelling, flagged)
        assert "re-frame.hicasso.impl.collector" in flagged[0], (spelling, flagged)

    # ARM 1, the direction that must stay GREEN, and it is the one with teeth:
    # `src/` names UIx in seventeen docstrings today. This fixture is the
    # sharpest of them, `impl/controlled.cljs`'s citation of the UIx port by
    # file and line — a grep-based arm dies on it.
    clean = scan_forbidden_imports(
        {
            "src/c.cljs": '(ns re-frame.hicasso.impl.controlled\n'
            '  "UIx\'s answer to this is a wrapper\n'
            '  (`uix/compiler/input.cljs:132-143`); this one is not that\n'
            '  wrapper. See also [[uix.core/memo]] and\n'
            '  (:require [uix.core :as uix]) which a CONSUMER writes."\n'
            "  (:require [re-frame.hicasso.impl.slot :as slot]))"
        }
    )
    assert clean == [], clean

    # And a require the reader discards is not one here either — the arm
    # inherits [[code_only]] whole, and this row is what says so rather than
    # leaving it to be assumed from the module rows above.
    clean = scan_forbidden_imports(
        {
            "src/d.cljs": "(ns re-frame.hicasso.impl.codec\n"
            "  (:require #_[uix.core :as uix]\n"
            "            ;; (:require [re-frame.adapter.uix :as a])\n"
            "            [re-frame.hicasso.impl.slot :as slot]))"
        }
    )
    assert clean == [], clean

    # A namespace that merely STARTS with the letters is not the library.
    clean = scan_forbidden_imports(
        {"src/s.cljs": "(ns re-frame.hicasso.core\n  (:require [uixels.grid :as g]))"}
    )
    assert clean == [], clean

    # ARM 2, the direction that must RED: the coordinate promoted out of the
    # `:test` alias into the production map, which is the realistic way this
    # clause breaks.
    flagged = scan_forbidden_coordinates(
        '{:paths ["src"]\n'
        " :deps {day8/re-frame2 {:local/root \"../core\"}\n"
        '        day8/re-frame2-uix {:local/root "../adapters/uix"}}}'
    )
    assert len(flagged) == 1, flagged
    assert "day8/re-frame2-uix" in flagged[0], flagged

    # ARM 2, the direction that must stay GREEN, and the reason the arm reads
    # by DEPTH rather than by search: the same coordinate under the `:test`
    # alias is correct and must not redden. A substring gate over this file
    # fails here, which is why one was not written.
    clean = scan_forbidden_coordinates(
        '{:paths ["src"]\n'
        " :deps {day8/re-frame2 {:local/root \"../core\"}}\n"
        " :aliases {:test {:extra-deps {day8/re-frame2-uix\n"
        '                               {:local/root "../adapters/uix"}}}}}'
    )
    assert clean == [], clean

    # ARM 2 fails CLOSED on a file it cannot read, rather than reporting the
    # green that an empty search would otherwise produce.
    unreadable = scan_forbidden_coordinates('{:paths ["src"]}')
    assert len(unreadable) == 1, unreadable
    assert "no top-level `:deps` map" in unreadable[0], unreadable

    # THE PRESENT CONTROL for arm 2, read off the REAL file (rf2-b3gy). The
    # live arm is an absence check, and an absence check whose subject has
    # quietly left the building is a green that means nothing. This asserts
    # the coordinate is still there to be kept out of production, so the
    # distinction the arm draws still has two sides.
    deps_text = read_deps_edn()
    assert "day8/re-frame2-uix" in code_only(deps_text), (
        "deps.edn no longer declares the UIx coordinate anywhere, so arm 2's "
        "green no longer distinguishes a clean production map from a file "
        "that stopped mentioning UIx at all."
    )
    assert scan_forbidden_coordinates(deps_text) == [], (
        "the real deps.edn must be green: " + repr(scan_forbidden_coordinates(deps_text))
    )
    aliases = top_level_value(deps_text, ":aliases")
    assert aliases is not None and "day8/re-frame2-uix" in aliases, (
        "the UIx coordinate must live under `:aliases`, which is what makes "
        "the production map's cleanliness a real distinction"
    )

    print("hicasso optional-module reachability: self-test OK")
    return 0


def main(argv):
    if "--self-test" in argv:
        return self_test()

    # rf2-okhdf. Machine-readable and side-effect free: the ONLY thing on
    # stdout is the namespaces, one per line, so a consumer can read the
    # stream without parsing around a banner.
    if "--module-namespaces" in argv:
        for ns in module_namespaces():
            print(ns)
        return 0

    sources = read_src()
    problems = scan(sources)
    problems += scan_forbidden_imports(sources)
    problems += scan_forbidden_coordinates(read_deps_edn())
    if problems:
        sys.stderr.write("hicasso optional-module reachability: FAIL\n")
        for p in problems:
            sys.stderr.write("  " + p + "\n")
        return 1

    names = ", ".join(m["name"] for m in MODULES)
    forbidden = ", ".join(r["name"] for r in FORBIDDEN_IMPORTS)
    print(
        "hicasso optional-module reachability: OK "
        "({} unreachable from the public door; {} required by no `src/` "
        "namespace and named by no production coordinate)".format(names, forbidden)
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
