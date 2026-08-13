#!/usr/bin/env python3
"""Every witness package under `examples/` is claimed by some import fence (rf2-689l).

WHY THIS EXISTS, AND WHY A LIST WOULD NOT DO
--------------------------------------------
Each witness application under
`test/re_frame/hicasso/examples/` carries an import fence — a
`surface-cljs-test` namespace asserting the application was written on
the PUBLIC DOOR and reaches no `impl.*`, no benchmark tree, no `tools/`
namespace and no test kit.  rf2-ccuw made each fence's POPULATION
derived, so a namespace added inside a fenced package cannot escape.

It bought nothing for a package no fence names at all, because the
roster of WHICH packages are fenced is spread across the fences' own
`ns` forms.  Adding a package fences nothing until somebody writes
another fence file, and the dates say that is not a mechanism:

    editor + grid fence   2026-08-11
    slice fence           2026-08-11
    todo fence            2026-08-11
    forms fence           2026-08-12
    navigation LANDS      2026-08-12   <- unfenced
    typeahead fence       2026-08-12
    ledger fence          2026-08-13   <- still nobody noticed

`navigation` arrived the day after four sibling fences already existed,
and two more were authored after it by two more people without anyone
seeing the hole.  It was not inert while it sat there: its `routes.cljs`
ends on a bare top-level `(register!)` writing two paths into the
PROCESS-GLOBAL route registrar at load, and the node bundle loads every
application in the tree into one process.

The usual objection to a checker — *eight packages is a list, and a list
is cheaper than a mechanism* — does not survive the shape of this one.
This roster is not a list anyone can read.  It exists only as the union
of six files' `ns` forms in six directories, so there is no place a
reviewer could look to notice one missing.  A list that rots can at
least be eyeballed; four people walked past this one because there was
nothing to eyeball.

WHAT IS CHECKED, IN ONE DIRECTION
---------------------------------
Every package directly under `examples/` that holds application code is
named by at least one `emit-application-namespaces` call somewhere under
`examples/` that the reader actually EVALUATES — a disabled, quoted or
commented-out call is not a claim about anything, and counting one let a
package look covered by a fence that no longer ran (rf2-t9kd).  That macro
argument IS the fence's declaration of what it fences, which is what makes
the PACKAGE -> FENCE mapping n:m rather than
1:1: `witness_surface_cljs_test.cljs` at the examples root names
`…examples.editor` AND `…examples.grid`, so the cheap check — *every
package directory contains a `surface_cljs_test.cljs`* — would false-red
two packages that are properly fenced.

The INVERSE rot — a fence naming a package that no longer exists — needs
nothing here.  `require-graph/application-namespaces` already throws at
macro-expansion time (*"no application namespace found for package … the
fence would pass vacuously"*), loudly and by name, so a second reporter
for it would be a duplicate.

This gate answers COVERAGE, not quality.  That a package is claimed does
not make its fence good; the fence's own `every-fence-predicate-fires`
and `the-graph-is-populated` rows are what say that, and they run in the
node lane where they belong.

WHY A CHECKER, AND WHY HERE
---------------------------
`check_lint_export.py` beside this file gives the argument in full and
it applies unchanged: a JVM `deftest` is refused by two of this repo's
own gates (a lane exists only through the artefact rosters, and a roster
entry is only legal with a matching `test.yml` job — and
`.github/workflows/` is hot-zone), so a scheduling decision would have
to be taken to host one.  A structural read over the source tree is this
artefact's own idiom instead: `check_freeze.py`,
`check_optional_module_reachability.py`, `check_complaint_catalogue.py`
and `check_budget_ledger.py` are the same shape, each with a
`--self-test` proving its own red.

It reads the FILESYSTEM at run time rather than the compiler at
expansion time, and that is deliberate.  The tempting alternative — an
atom in `require-graph` that each fence registers into as it expands,
read by a root suite — inverts rf2-ccuw's warm-cache boundary onto the
dangerous side.  That boundary is stale-and-safe today (a plant deleted
from disk was still named by a warm expansion; CI compiles cold).  A
registration atom is stale-and-UNSAFE: a fence a warm incremental build
does not recompile never re-registers, so the root suite would see a
partial registry and go RED on a healthy package in every local dev
loop.

    python scripts/check_example_fence_coverage.py             run the gate
    python scripts/check_example_fence_coverage.py --self-test prove it fires

Run from `implementation/` as part of `npm run test:hicasso-invariants`,
whose hosted invocation is a step of `test.yml`'s `cljs` job under
`cljs_node_test`.  This gate's WHOLE input surface is
`implementation/hicasso/**`, which is the family that arms that output —
so unlike the case `hicasso-complaint-catalogue` was split out to repair,
there is no scheduling hole to declare here.
"""

import os
import re
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ARTEFACT_ROOT = os.path.dirname(HERE)

# PLURAL, for the reason `require-graph/package-directories` is plural: this
# artefact has more than one test root by design (`shadow-cljs.edn` puts both
# on `:source-paths`, and `test_kit/test` exists precisely so a witness can be
# written while `hicasso/test` is held by another branch). A single-root walk
# would narrow the population silently, which is the same bug one level up
# from the one this gate exists to stop having.
EXAMPLE_ROOTS = [
    os.path.join(ARTEFACT_ROOT, "test", "re_frame", "hicasso", "examples"),
    os.path.join(ARTEFACT_ROOT, "test_kit", "test", "re_frame", "hicasso", "examples"),
]

PACKAGE_PREFIX = "re-frame.hicasso.examples."

# ClojureScript sources only, matching `require-graph/application-namespaces`:
# a `.clj` under `examples/` is a JVM macro namespace and no part of any
# compiled application.
SOURCE_EXTENSIONS = (".cljs", ".cljc")

# The macro argument is the fence's own declaration of what it fences. The
# alias is optional so an unaliased call still counts.
CLAIM_RE = re.compile(
    r"\(\s*(?:[\w.*+!?<>=$%&|-]+/)?emit-application-namespaces\s+([\w.*+!?<>=$%&|-]+)\s*\)"
)

STRING_RE = re.compile(r'"(?:[^"\\]|\\.)*"', re.S)
COMMENT_RE = re.compile(r";.*")

# `(comment …)` and not `(comment-on …)`: the lookahead is the symbol-tail
# class, so only the bare core form opens a comment.
COMMENT_FORM_RE = re.compile(r"\(\s*comment(?![\w.*+!?<>=$%&|:'/-])")

OPENERS = "([{"
CLOSERS = ")]}"


def form_end(text, i):
    """Index just past the ONE form beginning at or after `text[i]`.

    `text` has already been through the string and line-comment passes, so
    every remaining bracket is structural.  A character literal is the one
    exception and is skipped whole: `\\(` is a paren that closes nothing,
    and miscounting it would unbalance the rest of the file.
    """
    n = len(text)
    while i < n and text[i].isspace():
        i += 1
    if i >= n:
        return n
    if text[i] == "\\":  # character literal: `\(`, `\;`, `\newline`
        return min(i + 2, n)
    if text[i] not in OPENERS:  # a bare symbol, keyword or number
        while i < n and not text[i].isspace() and text[i] not in OPENERS + CLOSERS:
            i += 1
        return i
    depth = 0
    while i < n:
        ch = text[i]
        if ch == "\\":
            i += 2
            continue
        if ch in OPENERS:
            depth += 1
        elif ch in CLOSERS:
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return n


def strip_inert(text):
    """`text` with the three INERT form shapes blanked out (rf2-t9kd).

    A form the reader discards is not a claim about anything, so a gate that
    counted one could certify a package whose fence had been switched off:

        #_(rg/emit-application-namespaces …)              reader discard
        (comment (rg/emit-application-namespaces …))      comment macro
        '(rg/emit-application-namespaces …)               quoted

    Those three and no more.  DELIBERATELY NOT HANDLED, because none occurs
    in this tree and a general Clojure reader is a far larger artefact than
    this gate deserves: reader conditionals (`#?(:clj …)`), dead-by-value
    branches (`(when false …)`, `(if false …)`), stacked discards (`#_#_`),
    `#_` applied to a set or prefixed form (`#_#{…}`, `#_'x`), a
    fully-qualified `(clojure.core/comment …)`, and `comment` shadowed by a
    local binding.  Each of those still counts as a live claim; the failure
    mode is a package looking covered, which is the same one this function
    narrows rather than a new one.

    Blanking preserves length and newlines so the surviving text keeps its
    offsets, and the walk resumes at the END of each inert form — a live
    claim sitting beside a disabled one still counts.

    This function and [[form_end]] are DELIBERATELY duplicated in
    check_optional_module_reachability.py rather than shared (rf2-t9t0).
    The copies are NOT identical and must not be naively merged: that
    script runs its pair after a `code_only` pass has already blanked
    every character literal, so its copies drop the `\\` branches these
    two need.  Sharing would mean a flag argument on a twenty-line pure
    function, and these six checkers are deliberately self-contained
    single files with no cross-imports — `self_test` and `main` are
    duplicated six times over for the same reason.
    """
    chars = list(text)
    i, n = 0, len(text)
    while i < n:
        ch = text[i]
        if ch == "\\":  # character literal, as in `form_end`
            i += 2
            continue
        if text.startswith("#_", i):
            start, body = i, i + 2
        elif ch in "'`" and (i == 0 or text[i - 1].isspace() or text[i - 1] in OPENERS):
            # `'` is a legal SYMBOL character in Clojure (`next'`), so it
            # quotes only where a form may start. Without that guard the gate
            # would blank a LIVE claim and false-red a fenced package.
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


def read_forms(source):
    """`source` reduced to the forms the reader would actually EVALUATE.

    Read off LIVE FORMS, never off prose — the rule
    `check_optional_module_reachability.py` states for the same reason.
    These files carry long docstrings ABOUT the macro; one that quoted a
    call would otherwise register a claim nothing asserts.  Strings go
    first, so a `;` inside one cannot start a comment; line comments go
    second, so a `)` inside one cannot unbalance [[strip_inert]]'s walk.
    """
    return strip_inert(COMMENT_RE.sub("", STRING_RE.sub('""', source)))


def package_of(ns_symbol):
    """The package name a claimed namespace symbol names, or None.

    `re-frame.hicasso.examples.slice` -> `slice`.  A deeper symbol is not
    a package: a fence naming `…examples.slice.db` would be claiming a
    single namespace, which is not what the macro takes.
    """
    if not ns_symbol.startswith(PACKAGE_PREFIX):
        return None
    tail = ns_symbol[len(PACKAGE_PREFIX):]
    return tail if tail and "." not in tail else None


def read_tree(roots):
    """`{relative-posix-path: text}` for every ClojureScript source under `roots`.

    Keyed by the path RELATIVE to its root, because that is what names the
    package — so the same relative path under two roots would be two files
    claiming one namespace, which the compiler could not resolve either.
    That is a defect in its own right and is raised rather than silently
    shadowed.
    """
    tree = {}
    for root in roots:
        for dirpath, _dirnames, filenames in os.walk(root):
            for name in filenames:
                if not name.endswith(SOURCE_EXTENSIONS):
                    continue
                full = os.path.join(dirpath, name)
                rel = os.path.relpath(full, root).replace(os.sep, "/")
                if rel in tree:
                    raise SystemExit(
                        "examples/{} exists under two test roots, so two files "
                        "claim one namespace. Delete one.".format(rel)
                    )
                with open(full, encoding="utf-8") as handle:
                    tree[rel] = handle.read()
    return tree


def packages(tree):
    """Every package under `examples/`, as the DIRECTORY has them.

    A package is an immediate subdirectory holding at least one file that
    is not a test suite.  Deeper directories belong to the package above
    them — `slice/nested/foo.cljs` is `…examples.slice.nested.foo`, part
    of the slice — so only the first segment is a package.

    Test suites are excluded by the BUILD's own rule rather than a second
    one kept in step by hand: `:node-test` selects `cljs-test$`, so a
    file whose stem ends that way is a suite.  A directory holding
    nothing else is not an application and needs no fence.
    """
    found = set()
    for rel in tree:
        segments = rel.split("/")
        if len(segments) < 2:
            continue  # a file at the examples root is not in a package
        stem = segments[-1].rsplit(".", 1)[0].replace("_", "-")
        if stem.endswith("cljs-test"):
            continue
        found.add(segments[0].replace("_", "-"))
    return found


def claims(tree):
    """`{package: [file …]}` — every package some fence names, and where."""
    found = {}
    for rel, source in sorted(tree.items()):
        for ns_symbol in CLAIM_RE.findall(read_forms(source)):
            package = package_of(ns_symbol)
            if package:
                found.setdefault(package, []).append(rel)
    return found


def scan(tree):
    """The problems, as printable lines. Empty means the gate passes."""
    present = packages(tree)
    if not present:
        # Vacuity floor, the same one `application-namespaces` takes by
        # throwing rather than answering empty: a gate that walked an empty
        # tree would be green having checked nothing, which is precisely
        # the failure this whole family exists to refuse.
        return [
            "no witness package found under {} - the gate would pass "
            "vacuously. Has the examples tree moved?".format(
                " or ".join(EXAMPLE_ROOTS)
            )
        ]

    claimed = claims(tree)
    return [
        "examples/{0}/ holds application code that NO import fence claims. "
        "Nothing asserts it stays off `re-frame.hicasso.impl.*`, the "
        "benchmark tree, `tools/`, or the test kit - and if it registers a "
        "route, it writes into a process-global registrar the whole node "
        "bundle shares. Write examples/{0}/surface_cljs_test.cljs like its "
        "siblings, or name the package from an existing fence's "
        "`emit-application-namespaces`.".format(package)
        for package in sorted(present - set(claimed))
    ]


def self_test():
    # THIS SELF-TEST'S OWN CLAIMS MUST NOT BE DISCARDABLE. `python -O` deletes
    # every `assert` below, and the function would print OK having proved
    # nothing — a control silenced by a reader that drops its forms, which is
    # the exact defect rf2-t9kd exists to stop this gate having. It would be
    # a poor gate that could not catch its own case.
    try:
        assert False
    except AssertionError:
        pass
    else:
        raise SystemExit(
            "assertions are disabled (python -O / PYTHONOPTIMIZE), so this "
            "self-test would prove nothing. Re-run without -O."
        )

    # The n:m case, which is the one the cheap check gets wrong: two
    # packages, one fence file naming both, and no `surface_cljs_test.cljs`
    # in either directory.
    covered = scan(
        {
            "editor/views.cljs": "(ns re-frame.hicasso.examples.editor.views)",
            "grid/views.cljs": "(ns re-frame.hicasso.examples.grid.views)",
            "witness_surface_cljs_test.cljs":
                "(ns re-frame.hicasso.examples.witness-surface-cljs-test)\n"
                "(def a (rg/emit-application-namespaces "
                "re-frame.hicasso.examples.editor))\n"
                "(def b (rg/emit-application-namespaces "
                "re-frame.hicasso.examples.grid))",
        }
    )
    assert covered == [], covered

    # The rot itself: a package nothing names.
    flagged = scan(
        {
            "editor/views.cljs": "(ns re-frame.hicasso.examples.editor.views)",
            "navigation/routes.cljs": "(ns re-frame.hicasso.examples.navigation.routes)",
            "editor/surface_cljs_test.cljs":
                "(rg/emit-application-namespaces re-frame.hicasso.examples.editor)",
        }
    )
    assert len(flagged) == 1, flagged
    assert flagged[0].startswith("examples/navigation/"), flagged

    # A directory of nothing but suites is not an application and is not
    # demanded — `-dom-cljs-test` included, because the build's rule is the
    # suffix and not the word "dom".
    assert scan(
        {
            "editor/views.cljs": "(ns re-frame.hicasso.examples.editor.views)",
            "editor/surface_cljs_test.cljs":
                "(rg/emit-application-namespaces re-frame.hicasso.examples.editor)",
            "probes/l0_cljs_test.cljs": "(ns re-frame.hicasso.examples.probes.l0-cljs-test)",
            "probes/flow_dom_cljs_test.cljs":
                "(ns re-frame.hicasso.examples.probes.flow-dom-cljs-test)",
        }
    ) == []

    # A subdirectory belongs to the package above it rather than being one:
    # covering `slice` covers `slice/nested/` too.
    assert scan(
        {
            "slice/nested/http.cljs": "(ns re-frame.hicasso.examples.slice.nested.http)",
            "slice/surface_cljs_test.cljs":
                "(rg/emit-application-namespaces re-frame.hicasso.examples.slice)",
        }
    ) == []

    # PROSE IS NOT A CLAIM. A docstring quoting the call must not cover the
    # package, or the gate could be silenced by writing about it — the rule
    # `check_optional_module_reachability.py` states for `:require` forms.
    prose = scan(
        {
            "ghost/views.cljs": "(ns re-frame.hicasso.examples.ghost.views)",
            "notes_cljs_test.cljs":
                '(ns re-frame.hicasso.examples.notes-cljs-test\n'
                '  "Call (rg/emit-application-namespaces '
                're-frame.hicasso.examples.ghost) to fence it.")\n'
                ";; (rg/emit-application-namespaces re-frame.hicasso.examples.ghost)",
        }
    )
    assert len(prose) == 1, prose
    assert prose[0].startswith("examples/ghost/"), prose

    # AN INERT FORM IS NOT A CLAIM (rf2-t9kd). One unfenced package, one
    # fence file, and the only difference between the rows below is whether
    # the reader evaluates the call in it.
    def ghost(fence_body):
        return scan(
            {
                "ghost/views.cljs": "(ns re-frame.hicasso.examples.ghost.views)",
                "notes_cljs_test.cljs":
                    "(ns re-frame.hicasso.examples.notes-cljs-test)\n" + fence_body,
            }
        )

    # The LIVE claim is green, and it is what keeps the three reds honest: a
    # checker that had simply stopped counting `emit-application-namespaces`
    # would satisfy every negative row below and be worth nothing.
    live = ghost("(rg/emit-application-namespaces re-frame.hicasso.examples.ghost)")
    assert live == [], live

    # None of these three expands, so none installs a fence, so none may
    # certify coverage. Each was reproduced GREEN against the landed scanner.
    for inert in (
        "#_(rg/emit-application-namespaces re-frame.hicasso.examples.ghost)",
        "(comment\n  (rg/emit-application-namespaces re-frame.hicasso.examples.ghost))",
        "(def forms\n  ['(rg/emit-application-namespaces re-frame.hicasso.examples.ghost)])",
    ):
        disabled = ghost(inert)
        assert len(disabled) == 1, (inert, disabled)
        assert disabled[0].startswith("examples/ghost/"), (inert, disabled)

    # Blanking stops at the END of the inert form rather than running to the
    # end of the file: a live claim beside a disabled one still covers.
    beside = ghost(
        "#_(rg/emit-application-namespaces re-frame.hicasso.examples.gone)\n"
        "(rg/emit-application-namespaces re-frame.hicasso.examples.ghost)"
    )
    assert beside == [], beside

    # And `'` closes over a form only where a form may START, because it is
    # also a legal symbol character. `next'` must not swallow what follows it.
    tick = ghost(
        "(def next' 1)\n"
        "(rg/emit-application-namespaces re-frame.hicasso.examples.ghost)"
    )
    assert tick == [], tick

    # The vacuity floor fires rather than reporting a green nothing.
    empty = scan({})
    assert len(empty) == 1 and "vacuously" in empty[0], empty

    # THE SECOND ROOT IS ACTUALLY READ. `test_kit/test/…/examples` does not
    # exist today, so every assertion above would be identical had the roster
    # named one root — a widening nothing exercises is a widening that may not
    # work. Two real directories, a package in the SECOND one only, and the
    # walk must find it.
    with tempfile.TemporaryDirectory() as tmp:
        first = os.path.join(tmp, "a", "examples")
        second = os.path.join(tmp, "b", "examples")
        os.makedirs(os.path.join(first, "editor"))
        os.makedirs(os.path.join(second, "elsewhere"))
        open(os.path.join(first, "editor", "views.cljs"), "w").close()
        open(os.path.join(second, "elsewhere", "views.cljs"), "w").close()
        assert packages(read_tree([first, second])) == {"editor", "elsewhere"}

        # And a relative path present under both roots is two files claiming
        # one namespace, which is raised rather than silently shadowed.
        os.makedirs(os.path.join(second, "editor"))
        open(os.path.join(second, "editor", "views.cljs"), "w").close()
        try:
            read_tree([first, second])
        except SystemExit as clash:
            assert "editor/views.cljs" in str(clash), clash
        else:
            raise AssertionError("a namespace claimed under two roots was not raised")

    # And the premise the live run rests on: at least one of the roster's
    # roots is a real directory, so a move fails here rather than passing
    # vacuously. Not ALL of them — `test_kit/test/…/examples` is a root a
    # witness MAY be written under, not one that must exist.
    assert any(os.path.isdir(root) for root in EXAMPLE_ROOTS), EXAMPLE_ROOTS

    print("hicasso example-fence coverage: self-test OK")
    return 0


def main(argv):
    if "--self-test" in argv:
        return self_test()

    tree = read_tree(EXAMPLE_ROOTS)
    problems = scan(tree)
    if problems:
        sys.stderr.write("hicasso example-fence coverage: FAIL\n")
        for problem in problems:
            sys.stderr.write("  " + problem + "\n")
        return 1

    names = ", ".join(sorted(packages(tree)))
    print(
        "hicasso example-fence coverage: OK "
        "({} packages, all claimed by a fence: {})".format(
            len(packages(tree)), names
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
