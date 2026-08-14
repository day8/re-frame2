#!/usr/bin/env python3
"""Every public name on the door owes an inventory row (rf2-gz4bq).

`dispositions.md` section 3 states the constraint in its own words --
*"Adding a public surface adds an id.  A surface that reaches the facade
without a row here has escaped the inventory, and the Phase 4 exit --
every inventory id pointing at an applicable green row -- silently stops
meaning anything."*  Until this gate there was nothing that could tell
whether it held, and three surfaces had already escaped: `h/route-link`,
`h/use-subs` and `h/reg-state` reached the door with no row anywhere,
CHECKPOINT 4 found them by hand, and `rf2-2l8pw` minted HS-40, HS-41 and
HS-42 on witnesses.

THE MECHANISM, WHICH THE THREE ROWS DID NOT CLOSE
-------------------------------------------------
Section 2.1's **Source** column is spec-section-shaped -- `4`, `4.1`,
`4.2`, `4.3`, `7`, `8` -- so it invites a reader to mint rows FROM
`specification.md`.  And `specification.md` names none of those three
surfaces: measured at source, `use-subs`, `reg-state` and `route-link`
occur in it zero times each.  The inventory was not carelessly built; it
was built from a source that does not contain them, and the same
procedure would miss the next name the specification does not happen to
mention.

So the roster is derived HERE from the artefact the inventory is about --
`re-frame.hicasso` itself -- and diffed against sections 2.1 and 2.2.
The document cannot vouch for its own completeness; the door can.

THE ROSTER, AND WHY IT IS NOT THE TWO GREPS
-------------------------------------------
`facade_roster_ssr_dom_cljs_test.cljs`'s namespace docstring records two
greps that re-derive the roster by hand -- one for `^   \\(defmacro [a-z]`
and one for the alias block's exact indentation and arity.  They were
right when they were written and they are the reason this gate could be
written at all, but both are SHAPE-fragile: a macro at a different indent,
an alias whose right-hand side is not `impl-x/y`, a plain `defn` on the
door, and the name is invisible to the procedure again -- the same failure
one level up.

This gate reads the door as CODE instead.  [[code_only]] blanks strings,
comments and reader-discarded forms, and what survives is walked for every
`(def…)` head:

  * every form whose head symbol begins with `def` counts, including one
    nobody has written yet.  A `defsomething` this file has never heard of
    is treated as PUBLIC and owes a row, which is the direction that fails
    loudly rather than silently;
  * `defn-`, `^:private` and `^{:private true}` are the way to say a name
    is not surface;
  * a `def` whose name is not a literal symbol -- the two inside the
    macros' own syntax-quoted expansions -- is reported and skipped.  A
    computed name is not a public spelling anyone can write.

The door's docstrings are dense with worked examples -- `(defhost modal
Modal …)`, `(def article-card* (h/as-component article-card))`, `(defn
todo-row-body [props] …)` -- and every one of them is prose.  A grep-based
roster picks up all three; [[code_only]] does not.

WHAT COUNTS AS *HAVING A ROW* -- THE RULE, AND WHY THIS ONE
------------------------------------------------------------
A rule demanding a literal name match would redden on rows that are
correct: HS-20 is *"Portal helper"* and HS-21 is *"Outward bridge: a
Hicasso view under a native React parent"*, and both are the right way to
write a row about a surface whose name is an implementation spelling.  A
rule accepting any mention anywhere would pass on almost anything: `h/sub`
is named in four rows that are not HS-02, so a surface could be
"inventoried" by an incidental sentence in somebody else's disposition.

So attribution is by one of exactly two routes, and both are checked:

  1. **BY NAME.**  The code span `` `h/<name>` `` appears in the row's
     **Public surface** cell -- the cell that says what the row IS, not
     the prose about what was witnessed.  Whole-code-span equality, so
     `h/sub` does not match `h/subs`.  Eleven of the door's fifteen names
     are attributed this way and need no maintenance at all.

  2. **BY DECLARATION.**  For a surface the inventory carries by
     DESCRIPTION, [[DECLARED]] names the id and says which words in the
     row are the surface.  Three entries today.

[[HELD]] is the third list and it is not an exemption: it is a name on
the door with NO row in either section, recorded with the bead that owns
minting one.  A hold is printed on every run under its own heading and
counted separately, so a green here never reads as *the inventory is
complete*.

NEITHER LIST CAN ROT, WHICH IS THE POINT OF HAVING THEM
--------------------------------------------------------
An exemption list is a hole the moment it stops being read.  Both are
checked in BOTH directions, so an entry retires itself:

  * an entry naming a name that has left the door FAILS (stale);
  * an entry whose name is now attributable BY NAME FAILS (redundant --
    the row was written, so delete the entry);
  * a [[DECLARED]] entry citing an id that is not a row in 2.1 or 2.2
    FAILS (phantom id, or a row that was deleted underneath it);
  * a [[HELD]] entry with no bead reference FAILS.

FAILING CLOSED
--------------
An absence check that inspected nothing reports the same green as one
that inspected everything, so the structural reads are failures rather
than empty results: a section heading that does not resolve, a table with
no header row, a table with no data rows, a header with no *Public
surface* column, and a roster with no names are each a FAIL naming the
input it could not read.

WHERE IT RUNS
-------------
Two input families, and they do not arm the same lane:

  implementation/hicasso/src/re_frame/hicasso.cljc   the door
  docs/design/hicasso/product/dispositions.md        the inventory

Chained into `npm run test:hicasso-invariants` -- the local spine lane and
the artefact's one-command run -- which is a step of the `cljs` job under
`cljs_node_test`, armed by `implementation/hicasso/*`.  That is the
direction that matters: **the escape happens when the door grows, not when
the document changes**, and a door change arms it.

The document side arms nothing (`docs/**` measures `false` at every
classifier output), so `test.yml` carries an UNCONDITIONAL job as well --
the same routing, for the same reason, as `hicasso-complaint-catalogue`
and `hicasso-budget-ledger` above it.  A row deleted out from under a
DECLARED entry, or a 2.2 row deleted from under a name, would otherwise
next redden on somebody else's source PR.

SELF-TEST
---------
`--self-test` drives every rule red in turn over synthetic inputs, then
re-asserts the live tree's own premises.  It runs the detector rather than
trusting a bare `assert`, and it checks first that assertions are enabled
at all -- `python -O` strips them, which would leave a control that passes
having verified nothing.
"""

import os
import re
import sys

SCRIPTS_DIR = os.path.dirname(os.path.abspath(__file__))
PACKAGE_ROOT = os.path.dirname(SCRIPTS_DIR)                    # implementation/hicasso
REPO_ROOT = os.path.dirname(os.path.dirname(PACKAGE_ROOT))     # repo root

FACADE = os.path.join(PACKAGE_ROOT, "src", "re_frame", "hicasso.cljc")
DISPOSITIONS = os.path.join(
    REPO_ROOT, "docs", "design", "hicasso", "product", "dispositions.md"
)

# The door this gate reads, and the prefix its rows spell names under.
#
# ONE DOOR, deliberately.  `re-frame.hicasso.native` and the three optional
# module doors are public surfaces too and section 3's constraint reaches them
# -- but their rosters are not "every non-private def".  The native tier's
# `el`, `props*`, `component`, `marker` and `declared-server` are the runtime
# targets its three macros expand INTO: public to the compiler, no part of the
# authoring surface, and telling one from the other is a judgement only that
# tier's owner can record.  `re-frame.hicasso` has no such population -- every
# one of its fifteen defs is a name an author writes -- which is what makes it
# checkable today.  Adding a second door here is a data change; deciding what
# its public roster IS is not, and is filed rather than guessed.
DOOR_NS = "re-frame.hicasso"
DOOR_PREFIX = "h/"

# The two inventory sections, by their heading text.  Matched on the heading
# LINE rather than by number alone so that a renumbering fails here instead of
# silently reading the wrong table.
SECTIONS = [
    ("2.1", "### 2.1 Surface inventory and dispositions"),
    ("2.2", "### 2.2 Public surfaces with no server-render behavior"),
]

# Names inventoried by DESCRIPTION rather than by their own spelling.  Each
# entry names the id and quotes the words in that row which ARE the surface,
# because an attribution nobody can check is an attribution nobody will
# maintain.  Every entry is checked both ways -- see the module docstring.
DECLARED = {
    "hfn": {
        "id": "HS-03",
        # The row is titled `h/handler`, a name with no referent -- and it
        # says so itself: *"there is no `h/handler` on the facade
        # (`hicasso.cljc`'s alias block is the roster); the surface as
        # shipped is the literal intent vector plus `h/hfn`, and that is what
        # is witnessed"*.  So the row already claims this name, in its own
        # words, in the cell that records what was measured.  It is a
        # DECLARED attribution rather than a BY NAME one only because the
        # claim is in the disposition cell instead of the title.
        "why": "HS-03's disposition cell names `h/hfn` as the surface as "
               "shipped, the row's own title `h/handler` having no referent",
    },
    "portal": {
        "id": "HS-20",
        # *"Portal helper"*, witnessed by `portal-dom-cljs-test`, whose
        # disposition is about the portalled subtree, a declared `:fallback`
        # and `:rf.error/hicasso-portal-no-target`.  There is exactly one
        # portal helper on the door and this row is about it.
        "why": "HS-20 is titled `Portal helper` and its disposition is this "
               "helper's -- absent server bytes, declared `:fallback`, "
               "`:rf.error/hicasso-portal-no-target`",
    },
    "as-component": {
        "id": "HS-21",
        # *"Outward bridge: a Hicasso view under a native React parent"* --
        # which is this var's docstring almost word for word (*"the outward
        # bridge … so a native parent mounts a minted Hicasso view"*).  The
        # row's disposition measures `native-abi-cljs-test` server-rendering
        # a bridged view, which is this function's only job.
        "why": "HS-21 is titled `Outward bridge: a Hicasso view under a "
               "native React parent`, which is this var's own docstring",
    },
}

# ON THE DOOR WITH NO ROW IN EITHER SECTION.  Not an exemption: an open gap,
# owned, and printed on every run so that a green summary cannot be read as a
# complete inventory.  A hold retires itself -- the moment its name acquires a
# row the entry fails as redundant.
HELD = {
    "hframe": {
        "bead": "rf2-lvelh",
        # Found by this gate on its first live run (rf2-gz4bq).  `hframe` is
        # the ambient frame-id read, and `grep -c hframe dispositions.md` is
        # 0 -- no row in 2.1, none in 2.2, and nothing in either section
        # describes reading the ambient frame id.  Section 2.1's three
        # mentions of a *frame provider* are HS-11, HS-14 and HS-21's
        # canonical-matrix class, which is the root's PROVIDER element; this
        # var is the read of that context and is a different surface.
        #
        # WHY IT ESCAPED IS THIS BEAD'S OWN THESIS, one turn sharper than the
        # three rows that prompted it.  `specification.md` section 4 does not
        # merely fail to name it -- it says *"Existing `rf/current-frame-id`
        # and `rf/capture-frame` remain the frame doors; Hicasso should not
        # duplicate them"*.  A Source column shaped like a spec section could
        # never reach a name the specification declines to have.  Whether the
        # repair is a row or a removal is a membership question, and
        # `naming-ledger.md` plus `rf2-hic-065`'s sitting own it.
        "why": "no row in 2.1 or 2.2; specification.md section 4 says the "
               "frame doors stay `rf/current-frame-id` and `rf/capture-frame` "
               "and that Hicasso should not duplicate them, so a "
               "spec-shaped Source column cannot reach this name",
    },
}


# ---------------------------------------------------------------------------
# Reading Clojure: strings, comments and inert forms are not code
# ---------------------------------------------------------------------------
#
# [[form_end]], [[strip_inert]] and [[code_only]] are DELIBERATELY duplicated
# from `check_optional_module_reachability.py` rather than shared (rf2-t9t0).
# These six checkers are self-contained single files with no cross-imports;
# `self_test` and `main` are duplicated across them for the same reason.  The
# copies here are that file's exactly, which is a stronger claim than it looks
# -- its pair had already dropped the character-literal branches because
# [[code_only]] blanks those before either runs.

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

    The third shape is the one this gate needs most.  Both `defview` and
    `defhost` end in a syntax-quoted `(def …)` template, and a roster that
    counted those would report two public names spelled `~(if`.  Quoting is
    recognised only where a form may START, because `'` is a legal symbol
    character in Clojure (`next'`).
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

    The door documents itself at length and its docstrings contain worked
    `(defhost …)`, `(def …)` and `(defn …)` examples.  A roster read off the
    raw text counts every one of them.

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
# The roster: every public name the door defines
# ---------------------------------------------------------------------------

SYMBOL_TAIL = r"[A-Za-z0-9_.*+!?<>=$%&/'-]"
DEF_HEAD_RE = re.compile(r"\((def" + SYMBOL_TAIL + r"*)")
NAME_RE = re.compile(r"[A-Za-z*+!?<>=$%&_-]" + SYMBOL_TAIL + r"*")


def facade_roster(text):
    """`(public_names, computed)` for a door source.

    `public_names` is the sorted list of names an author can write; `computed`
    is the list of def-forms whose name is not a literal symbol, reported so
    that a skip is visible rather than assumed.  Private forms -- `defn-`,
    `^:private`, `^{:private true}` -- are neither.
    """
    source = code_only(text)
    public, computed = [], []
    for match in DEF_HEAD_RE.finditer(source):
        head = match.group(1)
        i = match.end()
        private = head.endswith("-")  # defn-
        # Skip any number of metadata forms: `^{:private true}`, `^:private`,
        # `^Symbol`.  The door writes its docstrings this way, so this is the
        # ordinary case rather than an edge one.
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
    return sorted(set(public)), computed


# ---------------------------------------------------------------------------
# The inventory: sections 2.1 and 2.2 of dispositions.md
# ---------------------------------------------------------------------------

CODE_SPAN_RE = re.compile(r"`([^`]+)`")


def split_row(line):
    """A markdown table row's cells, edge pipes dropped."""
    stripped = line.strip()
    if stripped.startswith("|"):
        stripped = stripped[1:]
    if stripped.endswith("|"):
        stripped = stripped[:-1]
    return [cell.strip() for cell in stripped.split("|")]


def parse_section(md, heading):
    """`(rows, problems)` for the table under `heading`.

    `rows` is `[(id, surface_cell)]`.  Every structural failure is a problem
    rather than an empty result: a section this gate could not read is not a
    section with nothing in it, and the difference is the whole reason an
    absence check needs saying so.
    """
    lines = md.splitlines()
    try:
        start = lines.index(heading)
    except ValueError:
        return [], [
            "dispositions.md: no heading line `{}`. The gate reads its "
            "inventory from that heading, so it could not read one at all "
            "-- if the section was renamed or renumbered, update SECTIONS "
            "here in the same commit.".format(heading)
        ]

    header, surface_col, rows = None, None, []
    for line in lines[start + 1:]:
        if line.startswith("#"):
            break
        if not line.lstrip().startswith("|"):
            continue
        cells = split_row(line)
        if header is None:
            header = cells
            for index, cell in enumerate(cells):
                if cell.lower() == "public surface":
                    surface_col = index
            continue
        if set("".join(cells).replace(" ", "")) <= set("-:"):
            continue  # the `|---|---|` separator
        rows.append(cells)

    problems = []
    if header is None:
        problems.append(
            "dispositions.md `{}`: no table header row found under the "
            "heading.".format(heading)
        )
        return [], problems
    if surface_col is None:
        problems.append(
            "dispositions.md `{}`: the table header has no `Public surface` "
            "column (columns: {}). That cell is what this gate reads a row's "
            "subject from.".format(heading, ", ".join(repr(c) for c in header))
        )
        return [], problems
    if not rows:
        problems.append(
            "dispositions.md `{}`: the table has a header and no data rows, "
            "so every name would be reported as escaped.".format(heading)
        )
        return [], problems

    parsed = []
    for cells in rows:
        if len(cells) != len(header):
            problems.append(
                "dispositions.md `{}`: row `{}` has {} cells and the header "
                "has {}. A miscounted row shifts every column after it, so "
                "the `Public surface` cell this gate reads would be some "
                "other cell.".format(
                    heading, cells[0] if cells else "?", len(cells), len(header)
                )
            )
            continue
        parsed.append((cells[0], cells[surface_col]))
    return parsed, problems


def inventory(md):
    """`(ids, surface_spans, problems)`.

    `ids` is every row id in both sections; `surface_spans` maps a code span
    written in some row's `Public surface` cell to the ids that write it.
    """
    ids, spans, problems = [], {}, []
    for _, heading in SECTIONS:
        rows, section_problems = parse_section(md, heading)
        problems.extend(section_problems)
        for row_id, surface in rows:
            ids.append(row_id)
            for span in CODE_SPAN_RE.findall(surface):
                spans.setdefault(span, []).append(row_id)
    return ids, spans, problems


# ---------------------------------------------------------------------------
# The diff
# ---------------------------------------------------------------------------

def check(facade_text, md_text, declared_table=None, held_table=None):
    """`(report, problems)`.

    `report` is `[(name, route, detail)]` over every public name on the door,
    in roster order, PLUS one `(head, "SKIPPED", …)` entry per def-form whose
    name is not a literal symbol -- so that the gate can SAY what it inspected
    on a green run as well as on a red one, skips included.  A skip nobody
    prints is indistinguishable from a name nobody has.

    The two manifests are ARGUMENTS rather than globals so that the self-test
    drives the real rules over synthetic ones.  A self-test that could only
    reach [[DECLARED]] and [[HELD]] as they stand would have to be rewritten
    every time an entry lands, which is how a self-test comes to be deleted.
    """
    declared_table = DECLARED if declared_table is None else declared_table
    held_table = HELD if held_table is None else held_table
    names, computed = facade_roster(facade_text)
    ids, spans, problems = inventory(md_text)
    id_set = set(ids)

    if not names:
        problems.append(
            "{}: no public names found on the door. A roster that came back "
            "empty would report a complete inventory over nothing.".format(
                os.path.relpath(FACADE, REPO_ROOT).replace(os.sep, "/")
            )
        )
    if problems:
        return [], problems

    report = []
    for name in names:
        rows = spans.get(DOOR_PREFIX + name, [])
        declared = declared_table.get(name)
        held = held_table.get(name)

        if rows:
            if declared:
                problems.append(
                    "`{prefix}{name}` is named in the `Public surface` cell of "
                    "{rows}, so its DECLARED entry (citing {cited}) is "
                    "redundant. Delete the entry: the row was written and the "
                    "gate no longer needs telling.".format(
                        prefix=DOOR_PREFIX, name=name, rows=", ".join(rows),
                        cited=declared["id"],
                    )
                )
            if held:
                problems.append(
                    "`{prefix}{name}` now has a row -- it is named in the "
                    "`Public surface` cell of {rows} -- so its HELD entry "
                    "(owned by {bead}) is stale. Delete the entry and close "
                    "the bead.".format(
                        prefix=DOOR_PREFIX, name=name, rows=", ".join(rows),
                        bead=held["bead"],
                    )
                )
            report.append((name, "BY NAME", ", ".join(rows)))
            continue

        if declared:
            if declared["id"] not in id_set:
                problems.append(
                    "`{prefix}{name}` is DECLARED as {cited}, and {cited} is "
                    "not a row in section 2.1 or 2.2. Either the id is a "
                    "typo or the row was deleted underneath the "
                    "entry.".format(
                        prefix=DOOR_PREFIX, name=name, cited=declared["id"]
                    )
                )
            report.append((name, "DECLARED", declared["id"]))
            continue

        if held:
            if not held.get("bead"):
                problems.append(
                    "`{prefix}{name}` is HELD with no bead reference. A hold "
                    "nobody owns is an exemption, which is the shape this "
                    "gate exists to prevent.".format(
                        prefix=DOOR_PREFIX, name=name
                    )
                )
            report.append((name, "HELD", held["bead"]))
            continue

        problems.append(
            "`{prefix}{name}` reaches the public door and has NO row in "
            "section 2.1 or 2.2 of dispositions.md. Section 3: *a surface "
            "that reaches the facade without a row has escaped the "
            "inventory, and the Phase 4 exit silently stops meaning "
            "anything*. Append a row -- 2.1 if the surface has a server "
            "render, 2.2 if it has none -- or, if the row carries the "
            "surface by DESCRIPTION rather than by name, add a DECLARED "
            "entry citing it in {gate}.".format(
                prefix=DOOR_PREFIX, name=name,
                gate=os.path.basename(__file__),
            )
        )

    # Both lists, in the direction the loop above cannot see: an entry whose
    # name has left the door.
    for table, label in ((declared_table, "DECLARED"), (held_table, "HELD")):
        for name in sorted(table):
            if name not in names:
                problems.append(
                    "the {label} entry for `{prefix}{name}` names something "
                    "that is not on the door any more. Delete it: an entry "
                    "for an absent name is an exemption waiting for a "
                    "collision.".format(
                        label=label, prefix=DOOR_PREFIX, name=name
                    )
                )

    for head in computed:
        report.append((head, "SKIPPED", "def-form with a computed name"))

    return report, problems


# ---------------------------------------------------------------------------

def read(path):
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def describe(report, ids_count):
    """The lines a run prints, green or red. What was inspected, by name."""
    lines = []
    by_route = {}
    for name, route, detail in report:
        by_route.setdefault(route, []).append((name, detail))
    width = max([len(n) for n, _, _ in report] or [1]) + len(DOOR_PREFIX)
    for route, heading in (
        ("BY NAME", "attributed BY NAME (the row's `Public surface` cell)"),
        ("DECLARED", "attributed BY DECLARATION (carried by description)"),
        ("HELD", "HELD -- on the door with NO row, owned by a bead"),
        ("SKIPPED", "SKIPPED -- not a public spelling anyone can write"),
    ):
        entries = by_route.get(route)
        if not entries:
            continue
        lines.append("  {} {}:".format(len(entries), heading))
        for name, detail in entries:
            prefix = "" if route == "SKIPPED" else DOOR_PREFIX
            lines.append(
                "    {:<{width}}  {}".format(prefix + name, detail, width=width)
            )
    named = [entry for entry in report if entry[1] != "SKIPPED"]
    lines.append(
        "  {} names on `{}`, {} inventory rows read".format(
            len(named), DOOR_NS, ids_count
        )
    )
    return lines


def self_test():
    # THIS SELF-TEST'S OWN CLAIMS MUST NOT BE DELETABLE (rf2-uyhh). `python -O`
    # -- and `PYTHONOPTIMIZE` in the environment, which needs no flag at the
    # call site -- strips every `assert` below, leaving a function that runs to
    # its success line having verified nothing. Empirical rather than a read of
    # `__debug__`, so it catches a `.pyc` compiled under `-O` too.
    try:
        assert False
    except AssertionError:
        pass
    else:
        raise SystemExit(
            "assertions are disabled (python -O / PYTHONOPTIMIZE), so this "
            "self-test would prove nothing. Re-run without -O."
        )

    # ---- the roster ------------------------------------------------------
    door = (
        '(ns re-frame.hicasso "A door.")\n'
        '#?(:clj (defmacro defview [sym & body] `(def ~sym ~@body)))\n'
        '#?(:cljs (do\n'
        '  (def ^{:doc "See (def article-card* (h/as-component x)) and\n'
        '  (defn helper [x] x) -- both PROSE."} sub impl/sub)\n'
        '  (defn ^:private hidden [] 1)\n'
        '  (defn- also-hidden [] 2)\n'
        '  ;; (def commented-out impl/x)\n'
        '  #_(def discarded impl/y)\n'
        '  (def root! impl/root!)))\n'
    )
    names, computed = facade_roster(door)
    assert names == ["defview", "root!", "sub"], names
    # The two macro-expansion templates are the shape that made the greps
    # necessary in the first place; they must be skipped, not counted.
    assert computed == [], computed

    # A def whose name is computed is REPORTED, not silently dropped, and it
    # is not a public name.
    names, computed = facade_roster("(def ~(symbol s) 1)\n(def real 2)")
    assert names == ["real"], names
    assert computed == ["def"], computed

    # A `defsomething` nobody has written yet counts as public. The direction
    # that fails loudly is the one this gate wants.
    names, _ = facade_roster("(defwidget gadget [] 1)")
    assert names == ["gadget"], names

    # ---- the inventory ---------------------------------------------------
    md = (
        "### 2.1 Surface inventory and dispositions\n\n"
        "| Id | Public surface | Source | Operative disposition (today) |\n"
        "|---|---|---|---|\n"
        "| HS-01 | `h/defview` boundary | 4 | Render -- and `h/sub` is read "
        "here too, in prose |\n"
        "| HS-20 | Portal helper | 4.3 | Client-only |\n\n"
        "### 2.2 Public surfaces with no server-render behavior\n\n"
        "| Id | Public surface | Source | Disposition |\n"
        "|---|---|---|---|\n"
        "| HS-10 | `h/root!` | 4 | Lifecycle command |\n\n"
        "## 3. Append protocol\n"
    )
    ids, spans, problems = inventory(md)
    assert problems == [], problems
    assert ids == ["HS-01", "HS-20", "HS-10"], ids
    # PROSE IN ANOTHER ROW IS NOT A ROW. `h/sub` is written in HS-01's
    # disposition cell and must not attribute anything -- that is the half of
    # the rule which stops "any mention anywhere" from passing on almost
    # everything.
    assert "h/sub" not in spans, spans
    assert spans["h/defview"] == ["HS-01"], spans
    assert spans["h/root!"] == ["HS-10"], spans

    # ---- the diff --------------------------------------------------------
    # The manifests are supplied per case, so these rows drive the REAL rules
    # over inputs that do not move when an entry lands or retires.
    none, none_held = {}, {}
    two_names = '(def defview 1)\n(def root! 2)\n'
    report, problems = check(two_names, md, none, none_held)
    assert problems == [], problems
    assert report == [("defview", "BY NAME", "HS-01"),
                      ("root!", "BY NAME", "HS-10")], report

    # THE FAILURE THIS GATE EXISTS FOR: a name on the door with no row.
    report, problems = check(two_names + "(def brand-new 3)\n", md, none, none_held)
    assert len(problems) == 1, problems
    assert "`h/brand-new` reaches the public door and has NO row" in problems[0], \
        problems
    assert "escaped the inventory" in problems[0], problems

    # A name carried by DESCRIPTION passes through the manifest, and only
    # through it: HS-20 is *"Portal helper"* and names no span at all.
    portal_declared = {"portal": {"id": "HS-20", "why": "titled `Portal helper`"}}
    report, problems = check(
        two_names + "(def portal 3)\n", md, portal_declared, none_held)
    assert problems == [], problems
    assert ("portal", "DECLARED", "HS-20") in report, report

    # A DECLARED entry citing an id no section carries FAILS. Driven by
    # deleting the ROW rather than by editing the entry, because that is how
    # it happens: somebody else's PR renumbers the table.
    without_hs20 = md.replace("| HS-20 | Portal helper | 4.3 | Client-only |\n", "")
    report, problems = check(
        two_names + "(def portal 3)\n", without_hs20, portal_declared, none_held)
    assert len(problems) == 1, problems
    assert "DECLARED as HS-20, and HS-20 is not a row" in problems[0], problems

    # A DECLARED entry whose row now names it FAILS as redundant, so the
    # manifest shrinks as the document improves instead of accreting.
    named = md.replace("| HS-20 | Portal helper |", "| HS-20 | `h/portal` |")
    report, problems = check(
        two_names + "(def portal 3)\n", named, portal_declared, none_held)
    assert len(problems) == 1, problems
    assert "its DECLARED entry (citing HS-20) is redundant" in problems[0], problems

    # A HELD name is reported, not failed -- and it IS reported, in the lines
    # a green run prints. A hold that a reader never sees is an exemption.
    hframe_held = {"hframe": {"bead": "rf2-lvelh", "why": "no row"}}
    report, problems = check(
        two_names + "(def hframe 3)\n", md, none, hframe_held)
    assert problems == [], problems
    assert ("hframe", "HELD", "rf2-lvelh") in report, report
    assert any("h/hframe" in line for line in describe(report, 3)), \
        describe(report, 3)
    assert any("HELD" in line for line in describe(report, 3)), describe(report, 3)

    # A hold with no owner FAILS: that is an exemption with a comment on it.
    report, problems = check(
        two_names + "(def hframe 3)\n", md, none, {"hframe": {"bead": ""}})
    assert len(problems) == 1, problems
    assert "HELD with no bead reference" in problems[0], problems

    # A HELD name that acquires a row FAILS, so a hold retires itself rather
    # than outliving the gap it records.
    with_hframe = md.replace(
        "| HS-20 | Portal helper |", "| HS-20 | `h/hframe`, and Portal helper |"
    )
    report, problems = check(
        two_names + "(def hframe 3)\n", with_hframe, none, hframe_held)
    assert len(problems) == 1, problems
    assert "its HELD entry" in problems[0] and "is stale" in problems[0], problems

    # An entry for a name that has left the door FAILS, in both tables.
    report, problems = check(two_names, md, portal_declared, hframe_held)
    assert len(problems) == 2, problems
    assert any("DECLARED entry for `h/portal` names something that is not on "
               "the door" in p for p in problems), problems
    assert any("HELD entry for `h/hframe` names something that is not on "
               "the door" in p for p in problems), problems

    # ---- failing closed --------------------------------------------------
    # Each of these would otherwise be a green over nothing.
    for broken, marker in (
        (md.replace(SECTIONS[1][1], "### 2.2 Renamed"), "no heading line"),
        (md.replace("| Id | Public surface | Source | Disposition |",
                    "| Id | Surface | Source | Disposition |"),
         "no `Public surface` column"),
        (md.replace("| HS-10 | `h/root!` | 4 | Lifecycle command |\n", ""),
         "header and no data rows"),
        (md.replace("| HS-20 | Portal helper | 4.3 | Client-only |",
                    "| HS-20 | Portal helper | Client-only |"),
         "has 3 cells and the header has 4"),
    ):
        _, problems = check(two_names, broken, none, none_held)
        assert any(marker in p for p in problems), (marker, problems)

    # A def the roster SKIPS is printed rather than dropped. A computed name
    # is not a public spelling and owes no row, but a skip nobody sees reads
    # exactly like a name nobody has -- which is this gate's own subject.
    report, problems = check(
        two_names + "(def ~(symbol s) 3)\n", md, none, none_held)
    assert problems == [], problems
    assert ("def", "SKIPPED", "def-form with a computed name") in report, report
    assert any("SKIPPED" in line for line in describe(report, 3)), \
        describe(report, 3)
    # ...and it is not counted as a name on the door.
    assert "2 names on" in describe(report, 3)[-1], describe(report, 3)

    # A door the roster could not read is a failure too, and it reports THAT
    # rather than the fifteen escapes an empty roster would otherwise invent.
    report, problems = check(";; nothing here\n", md, none, none_held)
    assert len(problems) == 1, problems
    assert "no public names found on the door" in problems[0], problems

    # ---- the live tree's own premises ------------------------------------
    facade_text, md_text = read(FACADE), read(DISPOSITIONS)

    # The premise the whole gate rests on: the door is where the roster comes
    # from, and it has one.
    live_names, live_computed = facade_roster(facade_text)
    assert len(live_names) >= 10, live_names
    assert "defview" in live_names and "sub" in live_names, live_names
    # The two syntax-quoted `(def …)` templates inside `defview` and
    # `defhost` are the roster's sharpest false positive and they are gone
    # before this runs, so nothing is left to report as computed.
    assert live_computed == [], live_computed

    # The PRESENT CONTROL for the rule (rf2-gz4bq). This gate's BY NAME route
    # is an attribution check, and one that had quietly stopped matching
    # anything would push every name into the manifest and read green there.
    # So: the live document must attribute a majority of the door BY NAME.
    live_report, live_problems = check(facade_text, md_text)
    assert live_problems == [], live_problems
    by_name = [n for n, route, _ in live_report if route == "BY NAME"]
    assert len(by_name) > len(live_report) / 2, (by_name, live_report)

    # And the premise under THAT: `specification.md` cannot reach the names
    # the inventory had to mint by hand, which is why the roster is derived
    # from the door rather than from the spec. If this ever stops holding the
    # bead's finding has been repaired at the source and this gate should be
    # re-read, not silently kept.
    spec = read(os.path.join(
        REPO_ROOT, "docs", "design", "hicasso", "product", "specification.md"
    ))
    for unreachable in ("use-subs", "reg-state", "route-link", "hframe"):
        assert unreachable not in spec, (
            "specification.md now names `{}`. The Source column's reach has "
            "changed; re-read rf2-gz4bq before trusting this gate's "
            "premise.".format(unreachable)
        )

    print("hicasso facade inventory: self-test OK")
    return 0


def main(argv):
    if "--self-test" in argv:
        return self_test()

    facade_text, md_text = read(FACADE), read(DISPOSITIONS)
    report, problems = check(facade_text, md_text)
    ids, _, _ = inventory(md_text)

    if problems:
        sys.stderr.write("hicasso facade inventory: FAIL\n")
        for problem in problems:
            sys.stderr.write("  " + problem + "\n")
        if report:
            sys.stderr.write("what was inspected:\n")
            for line in describe(report, len(ids)):
                sys.stderr.write(line + "\n")
        return 1

    held = [name for name, route, _ in report if route == "HELD"]
    headline = "hicasso facade inventory: OK"
    if held:
        headline += " -- {} name(s) HELD with no inventory row".format(len(held))
    print(headline)
    for line in describe(report, len(ids)):
        print(line)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
