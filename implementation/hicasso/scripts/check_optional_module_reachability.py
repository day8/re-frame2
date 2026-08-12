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

SELF-TEST
---------
`--self-test` runs the detector over synthetic sources first, because a
scan that silently stopped finding requires would report a clean tree
forever.  It pins both directions — a require IS flagged, a docstring
mention is NOT — and asserts the roster's own premise: every module
door and engine file named below must exist, so a renamed file fails
here rather than reporting a green absence for a namespace nobody
compiles any more.
"""

import re
import sys
from pathlib import Path

PACKAGE_ROOT = Path(__file__).resolve().parent.parent
SRC = PACKAGE_ROOT / "src"

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
        # rf2-hic-034.  The native tier is the module the native-boundary
        # law names in terms: *the native namespace is separately
        # reachable; an interpreted-only production dependency graph and
        # bundle contain neither native-tier runtime nor UIx code*
        # (`docs/design/hicasso/product/lanes/design-laws.md`).  This row
        # is the DEPENDENCY-GRAPH half of that sentence.
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
]

NS_FORM = re.compile(r"\(ns\s+([A-Za-z0-9_.*+!?<>=$%&/-]+)")


def ns_of(text):
    """The namespace a source file declares, or None. Read from code rather
    than prose for the same reason the requires are — a docstring here opens
    with `(ns my.app …)` as its usage example."""
    m = NS_FORM.search(code_only(text))
    return m.group(1) if m else None


def code_only(text):
    """`text` with every string literal and line comment blanked out.

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
    return "".join(out)


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


def scan(sources):
    """`sources` is `{path: text}`. Answer the list of violation strings."""
    problems = []
    for module in MODULES:
        owned = {module["door"], *module["engine"]}
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


def read_src():
    return {
        str(p.relative_to(PACKAGE_ROOT)).replace("\\", "/"): p.read_text(
            encoding="utf-8"
        )
        for p in sorted(SRC.rglob("*"))
        if p.suffix in (".cljs", ".cljc", ".clj")
    }


def self_test():
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

    # The roster's premise: every file it names exists. A rename must fail
    # here rather than report a green absence for a file nobody compiles.
    for module in MODULES:
        for rel in module["files"]:
            path = SRC / rel
            assert path.exists(), "roster names a missing file: {}".format(path)

    print("hicasso optional-module reachability: self-test OK")
    return 0


def main(argv):
    if "--self-test" in argv:
        return self_test()

    problems = scan(read_src())
    if problems:
        sys.stderr.write("hicasso optional-module reachability: FAIL\n")
        for p in problems:
            sys.stderr.write("  " + p + "\n")
        return 1

    names = ", ".join(m["name"] for m in MODULES)
    print(
        "hicasso optional-module reachability: OK "
        "({} unreachable from the public door)".format(names)
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
