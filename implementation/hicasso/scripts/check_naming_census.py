#!/usr/bin/env python3
"""Every public name in `implementation/hicasso` owes a naming-ledger row (rf2-hxbhe).

`dispositions.md` section 3 states the constraint unconditionally -- *adding
a public surface adds an id* -- and `check_facade_inventory.py` enforces it
over exactly ONE door, `re-frame.hicasso`, by design and says so in its own
module docstring:

>   Adding a second door here is a data change; deciding what its public
>   roster IS is not, and is filed rather than guessed.

That was the right call for an inventory gate whose rule is *every name owes
a DISPOSITION*, because the native tier's `el`, `props*`, `component` and
`marker` are compiler targets rather than authoring surface and only that
tier's owner can say which is which. It is the wrong scope for the weaker
question this gate asks -- *is the name WRITTEN DOWN anywhere in the naming
ledger* -- which needs no such judgement and reaches all ten shipped
namespaces. `rf2-hic-065`'s census measured what the gap costs: 105 public
names, of which 60 carried no ledger row, and 58 of those sat outside every
existing gate's reach.

The ledger is now complete (rows 47-54, PR #8252) and this gate is what
keeps it complete. It is the standing form of a census that was, until now,
a one-shot measurement reproduced in `naming-packet.md` section 7.


## Why this is not a grep

`re-frame.hicasso`'s doors document themselves at length and their
docstrings are dense with worked examples -- `(defhost modal Modal …)`,
`(def article-card* (h/as-component article-card))`, `(defn todo-row-body
[props] …)`. Every one of those is PROSE, and a grep-derived roster counts
all three as public names. So the source is read as CODE: [[code_only]]
blanks strings, comments, character literals and reader-discarded forms,
and what survives is walked for `(def…)` heads.


## A DEFINITION OPENS A LINE

`check_facade_inventory.py` accepts a `def`-headed form anywhere on a line,
which is safe on the one door it reads because nothing there CALLS a
`def*`-named function. It is not safe here. `evidence.cljs` calls two --

    (let [ds (defects p)]
      … (defects-message ds) …)

-- and reading those two calls as definitions mints the phantom public
names `p` and `ds`, which no ledger will ever roster and no author can
ever write. So a definition must OPEN its line (leading whitespace aside),
and a `def`-headed form found mid-line is reported as a CALL rather than
silently dropped. A skip nobody prints reads exactly like a name nobody
has, which is this gate's own subject.

The rule's limit, stated rather than left to be discovered: a `def`-headed
CALL that happens to begin its own line would still be read as a
definition. No such call exists in the ten namespaces -- both of
`evidence.cljs`'s are mid-line, which is why this rule is sufficient today
-- and the failure it would produce is a phantom name reported UNROSTERED,
which is a false RED. That is the safe direction, and it is why a bracket
depth tracker is not being built for it.


## The root is an ABSOLUTE argument, and it is printed

    python3 check_naming_census.py <ABSOLUTE repo root>

A script that derives its repository root from its own invocation path can
walk a SIBLING WORKTREE and report that as the census -- a complete,
internally consistent run about somebody else's tree, which is far harder
to catch than a broken one. So the root is supplied, a relative path is
REFUSED rather than resolved against the working directory (the same hazard
wearing a different hat), and the root actually read is printed on every
run, green or red, as the first line of output.

That has one consequence worth stating, because it is a real cost and not
an oversight: `--self-test` runs over SYNTHETIC trees only and asserts
nothing about the live one. `check_facade_inventory.py`'s self-test can
re-assert its live premises because it derives its own root; this gate
declines to derive a root at all, so it cannot. The live premise is the
live run, which is a separate invocation and says which tree it read.


## Duplicated, not imported

[[form_end]], [[strip_inert]] and [[code_only]] are copied from
`check_facade_inventory.py` rather than imported from it, per rf2-t9t0:
these checkers are self-contained single files with no cross-imports, and
that file's own docstring records the same decision about the same three
functions when IT copied them from `check_optional_module_reachability.py`.
The one-shot census in `naming-packet.md` section 7 loaded the sibling by
path with `importlib`, which is fine for a script quoted in a document and
would have been the first cross-import among the checkers had it been
committed that way.

The copies were measured to agree: both this gate and
`check_facade_inventory.py` derive the same 16 names for `re-frame.hicasso`,
an agreement neither was arranged to produce.


## Failing closed

An absence check that inspected nothing reports the same green as one that
inspected everything. Each of these is a REFUSAL (exit 2) naming the input
it could not read, rather than an empty result: a declared namespace whose
source is missing, a namespace whose roster came back empty, a missing
ledger, and a ledger from which no code span could be read at all.


## Self-test, and the positive control that ships

`--self-test` builds throwaway trees and runs the real [[census]] over
them, which is `check_allocation_non_claim.py`'s model rather than
`check_provenance_pins.py`'s -- both of those carry self-tests, and the
allocation scan is the closer one twice over: it is the other absence check
in the tree, and its fixtures are whole synthetic corpora rather than
in-memory rule cases, which is what a gate that WALKS A TREE needs.

The positive control is the fixture that matters. `naming-packet.md`
section 7 described it as a manual dance -- plant a public `(defn …)`,
confirm the count rises by one, revert, verify the restore with `git
hash-object`. Here it is a fixture: a seeded export the census must catch,
run on every invocation of `--self-test`, so the control proves itself
rather than waiting for a worker to remember it.


## Where it should run -- and it is NOT wired yet

Two input families that do not arm the same lane, exactly as
`check_facade_inventory.py` documents for exactly this reason:

    implementation/hicasso/src/**, test_kit/src/**   the ten namespaces
    docs/design/hicasso/product/naming-ledger.md     the ledger

A name escapes when the SOURCE grows, and a source change arms the `cljs`
job under `cljs_node_test`. The document side arms nothing (`docs/**`
measures false at every classifier output), so the doc-side direction --
a ledger row deleted out from under a name -- needs an unconditional lane
or it next reddens on somebody else's source PR.

`.github/workflows/*` is hot zone and arming this gate is filed separately
rather than done here. Until that lands this gate is runnable and proven
but not standing, and `naming-packet.md` section 7 says so.
"""

import os
import re
import sys

# The ten shipped namespaces, their sources relative to `implementation/hicasso`,
# and the alias each one's names are spelled under in the ledger.
PUBLIC = [
    ("re-frame.hicasso", "src/re_frame/hicasso.cljc", "h"),
    ("re-frame.hicasso.native", "src/re_frame/hicasso/native.cljc", "n"),
    ("re-frame.hicasso.forms", "src/re_frame/hicasso/forms.cljs", "forms"),
    ("re-frame.hicasso.overlay", "src/re_frame/hicasso/overlay.cljs", "overlay"),
    ("re-frame.hicasso.motion", "src/re_frame/hicasso/motion.cljs", "motion"),
    ("re-frame.hicasso.server", "src/re_frame/hicasso/server.cljs", "server"),
    ("re-frame.hicasso.tool", "src/re_frame/hicasso/tool.cljs", "tool"),
    ("re-frame.hicasso.evidence", "src/re_frame/hicasso/evidence.cljs", "evidence"),
    ("re-frame.hicasso.test", "test_kit/src/re_frame/hicasso/test.cljs", "ht"),
    ("re-frame.hicasso.test.mounted",
     "test_kit/src/re_frame/hicasso/test/mounted.cljs", "hm"),
]

PACKAGE_REL = ("implementation", "hicasso")
LEDGER_REL = ("docs", "design", "hicasso", "product", "naming-ledger.md")

EXIT_OK = 0
EXIT_UNROSTERED = 1
EXIT_REFUSED = 2


# ---------------------------------------------------------------------------
# Reading Clojure: strings, comments and inert forms are not code
# ---------------------------------------------------------------------------
#
# Copied from `check_facade_inventory.py`, which copied them from
# `check_optional_module_reachability.py` -- see the module docstring and
# rf2-t9t0.  No cross-imports between these checkers.

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


COMMENT_FORM_RE = re.compile(r"\(\s*comment(?![\w.*+!?<>=$%&|:'/-])")


def strip_inert(text):
    """`text` with the INERT form shapes blanked out.

    A form the reader discards is not a definition:

        #_(def gone 1)          reader discard
        (comment (def gone 1))  comment macro
        `(def ~name ~value)     syntax-quoted -- a macro's EXPANSION, not a def

    Quoting is recognised only where a form may START, because `'` is a legal
    symbol character in Clojure (`next'`).
    """
    chars = list(text)
    i, n = 0, len(text)
    while i < n:
        ch = text[i]
        if text.startswith("#_", i):
            start, body = i, i + 2
        elif ch in "'`" and (i == 0 or text[i - 1].isspace() or text[i - 1] in OPENERS):
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

    `\\"` is a character literal in Clojure, not a string, so a backslash
    outside a string consumes the character after it; inside a string it is
    the escape it looks like.  [[strip_inert]] runs last, over text whose
    brackets are all structural by then.
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


# ---------------------------------------------------------------------------
# The roster: every public name a namespace defines
# ---------------------------------------------------------------------------

SYMBOL_TAIL = r"[A-Za-z0-9_.*+!?<>=$%&/'-]"
DEF_HEAD_RE = re.compile(r"\((def" + SYMBOL_TAIL + r"*)")
NAME_RE = re.compile(r"[A-Za-z*+!?<>=$%&_-]" + SYMBOL_TAIL + r"*")


def roster(text):
    """`(public, computed, midline)` for one namespace's source.

    `public` is the sorted list of names an author can write.  `computed` is
    the def-forms whose name is not a literal symbol.  `midline` is the
    def-headed forms that did NOT open their line -- those are CALLS, and
    reading them as definitions is what minted `p` and `ds` out of
    `evidence.cljs`.  Both skips are returned so a run can print them.
    """
    source = code_only(text)
    public, computed, midline = [], [], []
    for match in DEF_HEAD_RE.finditer(source):
        head = match.group(1)
        line_start = source.rfind("\n", 0, match.start()) + 1
        if source[line_start:match.start()].strip():
            midline.append(head)
            continue
        i = match.end()
        private = head.endswith("-")  # defn-
        # Skip any number of metadata forms: `^{:private true}`, `^:private`,
        # `^Symbol`.  These namespaces write their docstrings this way, so
        # this is the ordinary case rather than an edge one.
        while True:
            while i < len(source) and source[i].isspace():
                i += 1
            if i < len(source) and source[i] == "^":
                end = form_end(source, i + 1)
                if ":private" in source[i:end]:
                    private = True
                i = end
                continue
            break
        name_match = NAME_RE.match(source, i)
        if not name_match:
            computed.append(head)
            continue
        if private:
            continue
        public.append(name_match.group(0))
    return sorted(set(public)), computed, sorted(set(midline))


# ---------------------------------------------------------------------------
# The ledger
# ---------------------------------------------------------------------------

CODE_SPAN_RE = re.compile(r"`([^`]+)`")


def ledger_spans(text):
    """Every code span written anywhere in the naming ledger.

    Deliberately the whole document rather than one column: the question is
    *is this name written down*, not *which row owns it*.  Attribution to a
    particular row is `check_facade_inventory.py`'s stricter job, over the
    one door where the answer is decidable.
    """
    return set(CODE_SPAN_RE.findall(text))


# ---------------------------------------------------------------------------
# The census
# ---------------------------------------------------------------------------

def read(path):
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def census(root, public=None):
    """`(rows, missing, notes, problems)` for the tree at `root`.

    `rows` is `[(namespace, spelled_name, covered)]` over every public name
    found; `missing` is the unrostered subset; `notes` records what was
    skipped and why; `problems` is non-empty only for a REFUSAL -- an input
    the gate could not read, which is not the same as an input with nothing
    in it.

    `public` is an argument so the self-test drives the real rules over
    synthetic namespaces, rather than over a manifest that moves whenever a
    namespace ships.
    """
    public = PUBLIC if public is None else public
    rows, missing, notes, problems = [], [], [], []

    ledger_path = os.path.join(root, *LEDGER_REL)
    if not os.path.isfile(ledger_path):
        problems.append(
            "the naming ledger is not at {}. Every name is measured against "
            "that document, so a census without it would report all of them "
            "unrostered.".format(os.path.join(*LEDGER_REL))
        )
        return rows, missing, notes, problems

    spans = ledger_spans(read(ledger_path))
    if not spans:
        problems.append(
            "{} yielded no code spans at all. A ledger this gate could not "
            "read is not a ledger with nothing in it, and the difference is "
            "the whole reason an absence check has to say so.".format(
                os.path.join(*LEDGER_REL)
            )
        )
        return rows, missing, notes, problems

    package = os.path.join(root, *PACKAGE_REL)
    for ns, rel, alias in public:
        path = os.path.join(package, *rel.split("/"))
        if not os.path.isfile(path):
            problems.append(
                "{} is declared a shipped public namespace and its source is "
                "not at {}. Either the namespace moved -- update this gate's "
                "PUBLIC list in the same commit -- or it was deleted and the "
                "entry is stale.".format(ns, rel)
            )
            continue
        names, computed, midline = roster(read(path))
        if not names:
            problems.append(
                "{} produced an EMPTY roster. A namespace the reader came "
                "back empty on would report a complete census over "
                "nothing.".format(ns)
            )
            continue
        for name in names:
            spelled = "{}/{}".format(alias, name)
            covered = spelled in spans or name in spans
            rows.append((ns, spelled, covered))
            if not covered:
                missing.append((ns, spelled))
        if computed:
            notes.append(
                "{}: {} def-form(s) with a computed name skipped -- not a "
                "public spelling anyone can write: {}".format(
                    ns, len(computed), ", ".join(sorted(set(computed)))
                )
            )
        if midline:
            notes.append(
                "{}: {} def-headed form(s) read as CALLS because they do not "
                "open a line: {}".format(ns, len(midline), ", ".join(midline))
            )

    return rows, missing, notes, problems


# ---------------------------------------------------------------------------
# self-test
# ---------------------------------------------------------------------------
#
# Throwaway trees, run through the real [[census]] -- the model is
# `scripts/check_allocation_non_claim.py`, the tree's other absence check,
# whose fixtures are whole synthetic corpora for the same reason: a gate
# that walks a tree is not exercised by an in-memory rule case.

_FIXTURE_NS = [
    ("fixture.one", "src/fixture/one.cljs", "f"),
    ("fixture.two", "src/fixture/two.cljs", "g"),
]

# `one.cljs` exercises every way a name can fail to be public, all at once:
# a docstring carrying worked `(def …)` examples, a `defn-`, a `^:private`,
# a reader discard, a line comment, and two def-headed CALLS mid-line.
_ONE = (
    '(ns fixture.one)\n'
    '(def ^{:doc "Worked examples, all PROSE:\n'
    '  (def article-card* (h/as-component article-card))\n'
    '  (defn todo-row-body [props] …)"} alpha impl/alpha)\n'
    '(defn- hidden [] 1)\n'
    '(defn ^:private also-hidden [] 2)\n'
    ';; (def commented-out impl/x)\n'
    '#_(def discarded impl/y)\n'
    '(defn beta []\n'
    '  (let [ds (defects p)]\n'
    '    (when (seq ds)\n'
    '      (throw (ex-info (defects-message ds) {})))))\n'
)

_TWO = '(ns fixture.two)\n(def gamma impl/gamma)\n'

# Every name in `_ONE` and `_TWO`, rostered.  This is the green baseline the
# positive control moves.
_LEDGER = (
    "# Naming ledger\n\n"
    "| # | Name |\n|---|---|\n"
    "| 1 | `f/alpha` |\n"
    "| 2 | `f/beta` |\n"
    "| 3 | `g/gamma` |\n"
)


def _write_tree(root, one=_ONE, two=_TWO, ledger=_LEDGER, omit=()):
    package = os.path.join(root, *PACKAGE_REL)
    for rel, text in (("src/fixture/one.cljs", one), ("src/fixture/two.cljs", two)):
        if rel in omit:
            continue
        path = os.path.join(package, *rel.split("/"))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(text)
    if ledger is not None:
        path = os.path.join(root, *LEDGER_REL)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(ledger)
    return root


def self_test():
    # THIS SELF-TEST'S OWN CLAIMS MUST NOT BE DELETABLE (rf2-uyhh). `python -O`
    # -- and `PYTHONOPTIMIZE` in the environment, which needs no flag at the
    # call site -- strips every `assert` below, leaving a function that runs to
    # its success line having verified nothing.  Empirical rather than a read
    # of `__debug__`, so it catches a `.pyc` compiled under `-O` too.
    try:
        assert False
    except AssertionError:
        pass
    else:
        raise SystemExit(
            "assertions are disabled (python -O / PYTHONOPTIMIZE), so this "
            "self-test would prove nothing. Re-run without -O."
        )

    import tempfile

    checks = 0

    def case(label, expect_missing, expect_refusal=False, **kwargs):
        nonlocal checks
        with tempfile.TemporaryDirectory() as tmp:
            root = _write_tree(os.path.join(tmp, "tree"), **kwargs)
            rows, missing, notes, problems = census(root, _FIXTURE_NS)
        checks += 1
        if expect_refusal:
            assert problems, (label, rows, missing, problems)
            assert any(expect_refusal in p for p in problems), (label, problems)
        else:
            assert not problems, (label, problems)
            assert [s for _, s in missing] == expect_missing, (label, missing)
        print("  ok  {}".format(label))
        return rows, missing, notes, problems

    # ---- the roster, read as CODE rather than grepped ---------------------
    names, computed, midline = roster(_ONE)
    # `alpha` and `beta` are the only public names.  Everything else in that
    # file is a docstring example, a private, an inert form or a CALL.
    assert names == ["alpha", "beta"], names
    assert computed == [], computed
    # THE PHANTOM NAMES.  A def-headed form mid-line is a call: reading
    # `(defects p)` and `(defects-message ds)` as definitions is what minted
    # `p` and `ds` out of `evidence.cljs`.
    assert midline == ["defects", "defects-message"], midline
    assert "p" not in names and "ds" not in names, names
    checks += 1
    print("  ok  a definition OPENS a line -- mid-line def-headed forms are CALLS")

    # A `defsomething` nobody has written yet counts as public: the direction
    # that fails loudly is the one this gate wants.
    later, _, _ = roster("(defwidget gadget [] 1)")
    assert later == ["gadget"], later
    # ...and a computed name is REPORTED, not silently dropped.
    _, computed, _ = roster("(def ~(symbol s) 1)\n(def real 2)")
    assert computed == ["def"], computed
    checks += 1
    print("  ok  an unknown `def*` head is public; a computed name is reported")

    # ---- the green baseline ----------------------------------------------
    rows, _, notes, _ = case("a fully rostered tree is GREEN", [])
    assert len(rows) == 3, rows
    assert any("read as CALLS" in n for n in notes), notes

    # ---- THE SEEDED POSITIVE CONTROL -------------------------------------
    # A public export the ledger does not roster.  This is the control that
    # `naming-packet.md` section 7 described as a manual plant-and-revert; as
    # a fixture it runs on every invocation instead of when somebody
    # remembers.  It must move the count by exactly one and NAME the export.
    seeded = _TWO + "(defn seeded-export [] :escaped)\n"
    after, missing, _, _ = case(
        "a SEEDED public export with no ledger row is caught",
        ["g/seeded-export"],
        two=seeded,
    )
    assert len(after) == len(rows) + 1, (len(after), len(rows))

    # The control's other half: the same export, once rostered, goes green --
    # so the gate is checking the ledger rather than banning new names.
    case(
        "the same export, once rostered, is GREEN",
        [],
        two=seeded,
        ledger=_LEDGER + "| 4 | `g/seeded-export` |\n",
    )

    # A private export is not a public name and owes no row.
    case("a PRIVATE export owes no row", [], two=_TWO + "(defn- quiet [] 1)\n")

    # ---- failing closed ---------------------------------------------------
    # Each of these would otherwise be a green -- or a red for the wrong
    # reason -- over an input the gate never read.
    case("a missing namespace source is REFUSED", None,
         "its source is not at", omit=("src/fixture/two.cljs",))
    case("an empty roster is REFUSED", None,
         "produced an EMPTY roster", two="(ns fixture.two)\n;; nothing\n")
    case("a missing ledger is REFUSED", None,
         "the naming ledger is not at", ledger=None)
    case("a ledger with no code spans is REFUSED", None,
         "yielded no code spans", ledger="# Naming ledger\n\nNo spans here.\n")

    print("\nhicasso naming census: self-test OK ({} checks).".format(checks))
    return EXIT_OK


# ---------------------------------------------------------------------------

def main(argv):
    if "--self-test" in argv:
        return self_test()

    if len(argv) != 1:
        sys.stderr.write(
            "usage: check_naming_census.py <ABSOLUTE repo root>\n"
            "       check_naming_census.py --self-test\n\n"
            "The root is supplied rather than derived, because a script that\n"
            "derives its root from its own invocation path can walk a sibling\n"
            "worktree and report that as the census.\n"
        )
        return EXIT_REFUSED

    root = argv[0]
    if not os.path.isabs(root):
        sys.stderr.write(
            "REFUSED: {!r} is not an absolute path. A relative root resolves\n"
            "against the working directory, which is the same hazard the\n"
            "argument exists to close -- pass the absolute repository root.\n".format(root)
        )
        return EXIT_REFUSED

    print("CENSUS_REPO_ROOT={}".format(root))
    rows, missing, notes, problems = census(root)

    for note in notes:
        print("  note {}".format(note))

    if problems:
        sys.stderr.write("hicasso naming census: REFUSED\n")
        for problem in problems:
            sys.stderr.write("  - " + problem + "\n")
        return EXIT_REFUSED

    for ns, spelled, covered in rows:
        print("{} {:32s} {}".format("OK  " if covered else "MISS", ns, spelled))

    print("\nCENSUS: {} public names across {} shipped namespaces; {} NOT "
          "rostered in naming-ledger.md".format(len(rows), len(PUBLIC), len(missing)))

    if missing:
        for ns, spelled in missing:
            print("  UNROSTERED  {}  {}".format(ns, spelled))
        sys.stderr.write(
            "\nhicasso naming census: FAIL -- {} public name(s) with no naming-ledger "
            "row.\ndispositions.md section 3: adding a public surface adds an id. Add a "
            "row to\ndocs/design/hicasso/product/naming-ledger.md, or make the name "
            "private.\n".format(len(missing))
        )
        return EXIT_UNROSTERED

    print("OK: every public name in implementation/hicasso is rostered.")
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
