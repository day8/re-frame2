#!/usr/bin/env python3
"""THE COMPLAINT-CATALOGUE GATE for implementation/hicasso/ (rf2-hic-021).

`docs/design/hicasso/product/complaints.md` is the REGISTER of Hicasso's
diagnostic ids: every complaint the substrate can raise, every spelling
claimed for a refusal whose surface is not built yet, and every spelling
retired and therefore dead forever. That register is a document of
PROMISES, and a promise nothing checks is a promise that rots. This gate
is what makes it a contract.

WHAT THIS GATE IS NOT
---------------------
It is not a second copy of `scripts/check_keyword_catalogue_drift.py`.
That gate defends the repo-wide Spec 009 catalogue in both directions —
every emitted `:rf.error/*` has a row (CHECK A), every active row has an
emitter (CHECK C) — and it already covers every Hicasso id that EXISTS.
Nothing here restates that.

What it adds is the axis Spec 009 structurally cannot hold: **status**.
A row in Spec 009 means "the runtime raises this today"; CHECK C enforces
exactly that, so an id whose surface has not been built CANNOT have a
Spec 009 row, and an id that has been retired must not have one either.
Those two populations — the RESERVED and the RETIRED — are precisely
the ones a stability contract is about, and until this register they
lived nowhere. A refusal the guide teaches by mechanism with no id at all
satisfies both directions of a raise-set/catalogue-set round trip
VACUOUSLY: it is raised by nothing and appears in no catalogue, so the
two enumerations agree and the coverage is still missing. The register's
reserved section is the third input that closes that hole, and R3 below
is what keeps it honest.

THE TEN RULES
-------------
  R1  NO GAP.        Every `:rf.error/*` id emitted by the package's
                     shipped source or its test kit has a `live` row.
  R2  NO DEAD ENTRY. Every `live` row is emitted by one of those.
  R3  RESERVATIONS ARE HONEST.  A `reserved` id is emitted nowhere in
                     the package and has no Spec 009 row. A reservation
                     that quietly became real is a register that has
                     stopped describing the runtime — promoting it is a
                     deliberate act (write the emitter, write the Spec
                     009 row, move the register row to `live`), never a
                     drift.
  R4  TOMBSTONES STAY DEAD.  A `retired` id is emitted nowhere and has
                     no ACTIVE Spec 009 row.
  R5  NO REUSE.      No id appears under two of the three EXCLUSIVE
                     statuses (`live`, `reserved`, `retired`). This is
                     the stability rule mechanised: a retired spelling
                     cannot come back as a reservation, and a live one
                     cannot be quietly re-minted for a different
                     meaning.
  R6  THE TWO CATALOGUES AGREE.  Every `live` row has a Spec 009 row.
                     The register indexes; Spec 009 states the meaning,
                     the payload and the recovery. One owner per fact,
                     bound by id.
  R7  A PENDING RETIREMENT IS STILL LIVE.  An id recorded as retiring
                     later must be live TODAY. Without this the section
                     becomes a list of predictions nobody re-reads: an
                     id could be deleted outright and its pending row
                     would sit there describing a runtime that no longer
                     has it.
  R8  THE ANCHOR RESOLVES.  Every guide chapter a row cites exists, and
                     names that id. A pointer to a page that does not
                     mention the complaint is worse than no pointer.
  R9  THE INDEX IS THE ROUTE IN.  The guide's troubleshooting complaint
                     index carries exactly ONE explicit anchor per `live`
                     id, indexes nothing that is not live, and shows for
                     each id only recovery keywords that id's Spec 009
                     row actually carries. See R9 IS A ROUND TRIP below
                     for which directions that is, and which it is not.
  R10 THE RUNTIME IS THE ANCHOR.  Every `:recovery` keyword the package
                     RAISES for an id Spec 009 rows in its Hicasso
                     section appears in that row's recovery cell — read at
                     EVERY emit site, so an id needs both a site the gate
                     can read and no site it cannot.  R1–R9 reconcile
                     documents with documents;
                     this is the only rule that opens the source and
                     reads what the runtime actually passes.  See R10 IS
                     THE RUNTIME ANCHOR below, which is careful about
                     what it does NOT close.

THE DIRECTION R8 DELIBERATELY DOES NOT CHECK is the reverse one: an id
whose `Taught in` cell is `—` is NOT required to be absent from the
guide. The guide is a live draft under active rewrite, and a gate that
reddened whenever a chapter gained a citation would put this register in
the rewrite's path for no correctness gain — the register's subject is
the id lifecycle, and the anchor is a convenience on top of it. If the
guide ever stops moving, that direction is worth taking.

R9 IS A ROUND TRIP, AND HERE IS EXACTLY WHICH ONE
-------------------------------------------------
"Round trip" is worth spelling out, because a gate that checks one
direction and reads like it checks two is the failure mode that never
announces itself — it passes.

R9 RUNS BOTH WAYS between the index and the RAISE SET:

  forward   every id the runtime raises is indexed.  R9 compares the
            index against `live`, and `live` is pinned to the raise set
            from both sides already — R1 forbids an emitted id with no
            `live` row and R2 forbids a `live` row with no emitter, so
            `live` IS the raise set and this is that comparison.
  backward  every id the index lists is actually raised.  Same identity,
            read the other way: an indexed id that is not `live` is one
            nothing raises — a reservation the page promoted early, a
            tombstone the page never buried, or a typo.

It also runs one direction on the RECOVERY keyword: every keyword the
index shows for an id must appear in that id's Spec 009 recovery cell.
Subset, not equality, and deliberately — two corpus-owned rows quote
option and payload names in their recovery prose (`:url-strategy`,
`:map-props`, `:frame`), which are not recoveries, and `ui-tree-malformed`
carries fourteen arms the page summarises as `:no-recovery`. Requiring
equality would red on those and teach the next author to weaken the rule.

WHAT R9 CANNOT SEE — the honest limit, and it is a real one:

  * IT NEVER READS THE RUNTIME'S OWN `:recovery` ARGUMENT.  It reconciles
    two DOCUMENTS.  R10 is the rule that opens the source, and the two are
    not the same claim — read R10 IS THE RUNTIME ANCHOR before assuming
    the pair of them covers more than it does.
  * It does not check that the index's PROSE is true, only its keywords.
  * It reads ONE page.  A complaint documented somewhere else in the
    guide, or twice, is outside its subject.

R10 IS THE RUNTIME ANCHOR — AND IT DOES NOT CLOSE rf2-15bqc
------------------------------------------------------------
This rule was filed (rf2-5zmul) against a sentence that used to stand
here: that R9's document-to-document limit "is rf2-15bqc exactly".  It is
not, and the correction matters more than the rule does, because the next
reader would otherwise believe a hole had been closed that has not been.

WHAT ACTUALLY HAPPENED IN rf2-15bqc.  Four recovery keywords went on
spelling the callback form `h-fn` after it was renamed to `h/event`.  The
fix commit is `3f2dba74da`, and its own subject line says how they were
wrong: "runtime and catalogue TOGETHER".  Before it, `impl/intent` passed
`:write-an-h-fn-at-a-value-first-position` and Spec 009's row carried
`:write-an-h-fn-at-a-value-first-position` — the same string.  R10
compares those two.  **R10 would have been green for the whole life of
that defect**, and any rule that reconciles the runtime against the
catalogue would have been.  What was wrong was not the agreement between
them; it was that both named an API form that no longer existed.  Seeing
THAT needs a rule about whether a recovery keyword still names something
the API has — a naming-census question, and not this register's.

WHAT R10 DOES CLOSE, which is narrower and real: a recovery renamed on
ONE side and not the other.  With R9 already binding Spec 009 to the
guide, the chain runtime -> Spec 009 -> guide now has no unchecked link,
so a single-document edit reds somewhere along it.  Only a COORDINATED
edit of all three stays green — which is what rf2-15bqc's fix was, and
what its defect was too.

R10 RUNS ONE DIRECTION ONLY: every recovery the runtime RAISES is rowed.
The reverse — every rowed keyword is raised by something — is NOT checked,
for the same reason R9's keyword arm is subset-and-not-equality: the
recovery cell is prose, and it quotes payload and option KEY names beside
the recovery itself.  Measured on this tree, requiring the reverse reds on
three rows that name `:callbacks`, `:event` and `:target` — none of them
recoveries.  Requiring it would teach the next author to weaken the rule,
which is worse than not requiring it.

R10'S SUBJECT STOPS AT THE SECTION BOUNDARY, AND HOLDS NO EXEMPTIONS.  An
earlier attempt at this rule (rf2-hic-068) measured a cheaper version at
75 of 76 and abandoned it, because the one exception looked like it needed
a hand-maintained exemption list — and a list like that rots silently.  It
does not need one.  The three ids that fall out are the three Hicasso
REUSES from the wider framework rather than mints, and Spec 009 already
says which those are by rowing them in its MAIN catalogue instead of its
Hicasso section.  `read_spec_hicasso_ids` reads that structure.  Nothing
is listed here, and a fourth reused spelling needs no edit to this file.

  Not the `hicasso-` id prefix, which is the other thing that looks like
  it would work: `:rf.error/no-frame-prop` is Hicasso's own, is rowed in
  the Hicasso section, and carries no prefix (the register records this
  under *Open, and not settled here*).  A prefix rule would drop it from
  the subject without saying so — smaller coverage, same green.

WHAT R10 CANNOT SEE:

  * A recovery that is CORRECT against Spec 009 and wrong about the API.
    That is the paragraph above, and it is the whole of rf2-15bqc.
  * WHAT a recovery MEANS.  The reader folds an argument expression to
    keywords; it does not evaluate anything, and a site that computes its
    recovery from a lookup or a function call is not read — it is FAILED,
    per the paragraph below.  Two expression shapes are admitted and
    `_resolve` is the whole grammar: a keyword literal, and a two-branch
    `if`/`if-not` over keyword literals, which reads as the SET of arms
    because both are answers the id can give (`impl/codec` writes one).
  * The three corpus-owned spellings, deliberately, per the subject rule
    above.  `:rf.error/no-frame-context` is rowed `:supply-frame` for
    `re-frame.frame`'s emitter while Hicasso's boundary passes
    `:mount-under-a-frame`; both are right where they stand, and no rule
    here has an opinion.

  THE COVERAGE ARM IS PER EMIT SITE, AND HAD TO BE CORRECTED TO SAY SO
  (rf2-5zmul, audit of #8315).  A gate that reads emit sites can always
  read FEWER of them than it claims to — a new constructor shape, and the
  rule quietly covers less while still reporting OK.  The first version
  of this arm asked only whether an id had ANY recovery the reader
  understood, and that is not the same question: `recoveries_in` is keyed
  by id, so one site passing a literal made the id look accounted for and
  a SIBLING site passing `(recovery-for x)` was dropped in silence.
  Measured: a fixture with one call, and the same file plus a second call
  for the SAME id through an unreadable expression, produced byte-for-byte
  identical output — and R1's emitted-id input was identical too, because
  the sibling names the same id.  So the second site could raise a keyword
  no row carries with every arm green.  The scan now KEEPS such a site and
  R10 fails on it, which is why both halves of the fixture are pinned in
  `--self-test`.  MEASURED COST ON THIS TREE: 105 constructor call sites.
  One is a constructor's own body forwarding its `id` parameter, which has
  no id to attribute and is read where its CALLERS stand.  Of the other
  104: 103 pass a keyword literal, 1 passes the `impl/codec` conditional,
  and 0 are unreadable.  The rule tightens and nothing needed an
  exemption — which is the evidence that it is worth its keep.

WHAT COUNTS AS AN EMIT
----------------------
A `:rf.error/…` keyword LITERAL in code, after `;` comments and string
literals are masked out. The masking is not optional: this package's
docstrings cite ids they do not raise — `impl/collector` names
`:rf.error/frame-destroyed` and `:rf.error/no-such-sub` while explaining
what core does on a cold read, and `impl/error`'s own docstring names
three ids to explain why their payload column reads `(none)`. An
unmasked scan would demand `live` rows for all of them and the register
would claim Hicasso raises complaints it merely describes.

USAGE
    python hicasso/scripts/check_complaint_catalogue.py --self-test
    python hicasso/scripts/check_complaint_catalogue.py
    python hicasso/scripts/check_complaint_catalogue.py --list
"""

import argparse
import os
import re
import sys

SCRIPTS_DIR = os.path.dirname(os.path.abspath(__file__))
PACKAGE_ROOT = os.path.dirname(SCRIPTS_DIR)                       # implementation/hicasso
REPO_ROOT = os.path.dirname(os.path.dirname(PACKAGE_ROOT))        # repo root

REGISTER = os.path.join(REPO_ROOT, "docs", "design", "hicasso", "product", "complaints.md")
SPEC_009 = os.path.join(REPO_ROOT, "spec", "009-Instrumentation.md")
# The guide R8 resolves its chapter anchors against.  It shipped from
# `docs/design/hicasso/draft-guide/`; rf2-0yp7w promoted that corpus into the
# published tree, where the chapter FILENAMES (`NN-*.md`) are unchanged, so
# `guide_chapter` needed no other edit.
GUIDE_DIR = os.path.join(REPO_ROOT, "docs", "core", "hicasso")

# The reader's route into both catalogues (rf2-hic-068).  R9's subject.
TROUBLESHOOTING = os.path.join(GUIDE_DIR, "troubleshooting.md")

# The surfaces a consumer can actually reach: the published package and the
# supported test kit.  `test/` and `testbed/` ASSERT ids rather than owning
# them, so they are not an emit surface — but they are part of the package
# for the purposes of R3/R4, where the question is "does this spelling occur
# at all".
EMIT_ROOTS = [
    os.path.join(PACKAGE_ROOT, "src"),
    os.path.join(PACKAGE_ROOT, "test_kit", "src"),
]

SOURCE_EXTS = (".clj", ".cljc", ".cljs")

STATUSES = ("live", "reserved", "pending-retirement", "retired")

# The three an id can hold ONE of.  `pending-retirement` is deliberately not
# among them: it annotates an id that is still live (R7), so treating it as a
# fourth exclusive state would make the register contradict itself.
EXCLUSIVE_STATUSES = ("live", "reserved", "retired")

# `:rf.error/id` is the ex-data KEY every complaint is stamped with, not a
# complaint. It reads as one to a scan of `:rf.error/…` literals, and it is
# the only member of that family, so it is named here rather than filtered by
# a pattern that would also swallow a real id somebody spelled badly.
NOT_A_COMPLAINT = frozenset({":rf.error/id"})

_MARKER_RE = re.compile(r"<!--\s*rf2-hic-021:\s*status=([a-z-]+)\s*-->")
_ERROR_ID_RE = re.compile(r":rf\.error/[a-zA-Z0-9*+!_'?<>=-]+")
_ROW_ID_RE = re.compile(r"^\|\s*~{0,2}`(:rf\.error/[^`]+)`~{0,2}\s*\|")
_CHAPTER_RE = re.compile(r"\bch(\d{2})\b")

# An index entry: an explicit anchor immediately above the heading that names
# the id.  Explicit because the anchor is the deep-link target every other page
# and every error message points at, and a heading's GENERATED slug is a
# rendering detail that changes when the heading's wording does.
_ENTRY_RE = re.compile(
    r'^<a id="([^"]+)"></a>\r?\n#### `(:rf\.error/[^`]+)`\s*$', re.MULTILINE)
# A recovery keyword as either document writes it: a backticked plain keyword.
_RECOVERY_TOKEN_RE = re.compile(r"`(:[a-z][a-z0-9-]*)`")
# Markdown table cells.  An escaped pipe is content, not a cell boundary — no
# live row uses one today, and splitting on it would silently mis-shape a row
# that later does, shifting the recovery cell one place left.
_CELL_SPLIT_RE = re.compile(r"(?<!\\)\|")


# ---------------------------------------------------------------------------
# Reading source
# ---------------------------------------------------------------------------

def mask(text, strings_as_atoms=False):
    """`text` with `;` comments and string literals replaced by spaces.

    Character literals (`\\;`, `\\"`) are consumed as two characters so a
    quote or semicolon written as a value cannot open a phantom string or
    comment.  Newlines survive UNDER BOTH MODES so a caller can still
    report line numbers — R10 names the emit site it refuses to read, and
    a gate that names the wrong line is worse than one that names none.

    `strings_as_atoms` collapses each string literal to the single
    character `#` instead of a same-width run of spaces.  R10 needs it and
    the id scan must NOT have it: blanking a string DELETES an argument,
    because whitespace is what separates one from the next.  A refusal
    whose reason is a plain string reads as `(fail! id where :recovery {})`
    once blanked — the recovery has moved one place left, and a positional
    reader believes it.  Two of this package's `fail!` sites are written
    that way, so this is the difference between reading the argument and
    reading the one after it.
    """
    out = []
    i, n = 0, len(text)
    in_string = False
    while i < n:
        c = text[i]
        if in_string:
            if c == "\\" and i + 1 < n:
                if not strings_as_atoms:
                    out.append("  ")
                i += 2
                continue
            if not strings_as_atoms:
                out.append("\n" if c == "\n" else " ")
            elif c == "\n":
                # The atom is already written; a newline AFTER it separates
                # arguments exactly as it did before, so keeping it costs the
                # reader nothing and buys back the line number.  Dropping it
                # made R10 name a line 1850 short of the site it had found.
                out.append("\n")
            if c == '"':
                in_string = False
            i += 1
            continue
        if c == "\\" and i + 1 < n:
            # A character literal.  Two characters, neither of them syntax.
            out.append("  ")
            i += 2
            continue
        if c == '"':
            in_string = True
            out.append("#" if strings_as_atoms else " ")
            i += 1
            continue
        if c == ";":
            while i < n and text[i] != "\n":
                if not strings_as_atoms:
                    out.append(" ")
                i += 1
            continue
        out.append(c)
        i += 1
    return "".join(out)


def _source_files(roots):
    for root in roots:
        for dirpath, _dirnames, filenames in os.walk(root):
            for name in sorted(filenames):
                if name.endswith(SOURCE_EXTS):
                    yield os.path.join(dirpath, name)


def read_ids(roots):
    """`{id: [relative path, …]}` for every masked `:rf.error/…` literal."""
    found = {}
    for path in _source_files(roots):
        with open(path, encoding="utf-8") as fh:
            code = mask(fh.read())
        for match in sorted(set(_ERROR_ID_RE.findall(code)) - NOT_A_COMPLAINT):
            rel = os.path.relpath(path, REPO_ROOT).replace(os.sep, "/")
            found.setdefault(match, []).append(rel)
    return found


# ---------------------------------------------------------------------------
# Reading the RUNTIME's `:recovery` argument (R10)
# ---------------------------------------------------------------------------
#
# Everything below reads code as FORMS rather than as lines, because the
# subject is an ARGUMENT POSITION and a line-oriented scan cannot find one:
# the reason argument beside it is routinely a multi-line `(str …)`.

_OPEN, _CLOSE = "([{", ")]}"
_SYMBOL = r"[A-Za-z0-9*+!_'?<>=.-]+"
_DEFN_RE = re.compile(r"\(defn-?\s+(" + _SYMBOL + r")(?=[\s\n])")
_PARAM_VEC_RE = re.compile(r"\[([^]\[]*)\]")
_KEYWORD_RE = re.compile(r"^:[a-zA-Z][a-zA-Z0-9*+!_'?<>=./-]*$")


def _forms(code, head_pattern):
    """Yield `(match, body)` for each paren-balanced form the head opens.

    `body` is everything between the head and the closing bracket.  An
    unbalanced tail yields nothing rather than raising: this is a gate, and
    a file it cannot read must show up as MISSING COVERAGE (R10's first
    arm) rather than as a traceback that says nothing about the register.
    """
    for match in re.finditer(head_pattern, code):
        depth = 0
        for position in range(match.start(), len(code)):
            if code[position] in _OPEN:
                depth += 1
            elif code[position] in _CLOSE:
                depth -= 1
                if depth == 0:
                    yield match, code[match.end():position]
                    break


def _call_args(body):
    """A form body split into its TOP-LEVEL arguments, in order."""
    found, depth, start = [], 0, None
    for position, char in enumerate(body):
        if char in _OPEN:
            if depth == 0 and start is None:
                start = position
            depth += 1
        elif char in _CLOSE:
            depth -= 1
            if depth == 0:
                found.append(body[start:position + 1])
                start = None
        elif depth == 0:
            if char.isspace():
                if start is not None:
                    found.append(body[start:position])
                    start = None
            elif start is None:
                start = position
    if start is not None:
        found.append(body[start:])
    return found


def _resolve(argument):
    """The keyword answers one ARGUMENT EXPRESSION can yield, or None.

    A deliberately small grammar, and small is the point.  Two shapes are
    admitted, because two shapes are what the package writes:

      * a literal keyword — 103 of the 104 attributable call sites;
      * a two-branch `if`/`if-not` over literal keywords — the one other,
        `impl/codec`'s `(if (fn? head) :call-it-or-make-it-a-view
        :supply-a-valid-hiccup-head)`, which reads as the SET of arms
        because both are answers the id can give.

    ANYTHING ELSE IS None, meaning UNREADABLE, and the caller must keep
    the site rather than drop it.  The predecessor of this function
    scavenged every keyword out of the expression with a bare regex, which
    is worse than not reading it: `(get advice :fallback)` would have been
    reported as raising `:fallback`, and R10 would then have gone on to
    reconcile an invention against Spec 009.  A reader that cannot fold an
    expression must say so; guessing puts a false keyword into the one
    rule that opens the source (rf2-5zmul, audit of #8315).
    """
    if _KEYWORD_RE.match(argument):
        return [argument]
    if argument.startswith("(") and argument.endswith(")"):
        cells = _call_args(argument[1:-1])
        if len(cells) == 4 and cells[0] in ("if", "if-not") \
                and all(_KEYWORD_RE.match(cell) for cell in cells[2:]):
            return cells[2:]
    return None


def _literal_pairs(body):
    """`(id, recovery)` for each literal map pairing both, as literals.

    The shape a refusal minted WITHOUT a constructor takes — `impl/error`'s
    own meta-refusal is the one in the package.
    """
    for _match, inner in _forms(body, r"\{"):
        cells = _call_args(inner)
        pairs = dict(zip(cells[0::2], cells[1::2]))
        error_id, recovery = pairs.get(":rf.error/id"), pairs.get(":recovery")
        if error_id and recovery and _ERROR_ID_RE.match(error_id) \
                and _KEYWORD_RE.match(recovery):
            yield error_id, recovery


def _map_sources(body, params):
    """`(id-source, recovery-source)` off a literal ex-data map, or None.

    Each half resolves to a literal keyword or to one of `params` by NAME —
    which is what makes a constructor's recovery POSITION derived rather
    than declared. `fail!` writes `{:rf.error/id id … :recovery recovery}`,
    so the reader learns "argument 3" by asking the constructor instead of
    carrying a table of arities that goes stale the moment one changes.
    """
    for _match, inner in _forms(body, r"\{"):
        cells = _call_args(inner)
        pairs = dict(zip(cells[0::2], cells[1::2]))
        error_id, recovery = pairs.get(":rf.error/id"), pairs.get(":recovery")
        if not (error_id and recovery):
            continue
        id_source = (("fixed", error_id) if _ERROR_ID_RE.match(error_id)
                     else ("param", params.index(error_id)) if error_id in params
                     else None)
        recovery_source = (("fixed", recovery) if _KEYWORD_RE.match(recovery)
                           else ("param", params.index(recovery)) if recovery in params
                           else None)
        if id_source and recovery_source:
            return (id_source, recovery_source)
    return None


def read_constructors(sources):
    """`{name: (id-source, recovery-source)}` — the package's refusal minters.

    A source is `("fixed", keyword)` or `("param", index)`, and a
    constructor is DISCOVERED rather than named here, because a list of
    names is a list that goes stale the first time somebody adds a fifth
    helper.  A `defn` qualifies when its body mints a refusal — a literal
    ex-data map, or a call to a constructor already known — AND at least
    one of the id and the recovery comes from ITS OWN PARAMETERS.

    That last clause is the whole rule.  A `defn` whose id and recovery are
    both LITERAL is not a constructor, it is an ordinary emit site, and the
    call-site scan reads it where it stands.  Admitting one would attribute
    its refusal to every caller of an ordinary function: dropping the
    clause resolves 133 "constructors" here instead of 4, and reports a
    refusal against `:rf.error/hicasso-deferred-read-at-boundary` that no
    call site raises.
    """
    definitions = []
    for _rel, code in sources:
        for match, body in _forms(code, _DEFN_RE.pattern):
            name = _DEFN_RE.match(code[match.start():]).group(1)
            params = _PARAM_VEC_RE.search(body)
            definitions.append((name, params.group(1).split() if params else [], body))

    known = {}
    for _pass in range(len(definitions) + 1):
        grew = False
        for name, params, body in definitions:
            if name in known:
                continue
            sources_found = _map_sources(body, params) \
                or _forwarded(body, params, known)
            if sources_found and any(kind == "param" for kind, _ in sources_found):
                known[name] = sources_found
                grew = True
        if not grew:
            break
    return known


def _forwarded(body, params, known):
    """The id/recovery sources a body inherits from a constructor it calls."""
    for other, (id_source, recovery_source) in sorted(known.items()):
        head = r"\((?:%s/)?%s(?=[\s)])" % (_SYMBOL, re.escape(other))
        for _match, inner in _forms(body, head):
            arguments = _call_args(inner)

            def lift(source):
                kind, value = source
                if kind == "fixed":
                    return source
                if value >= len(arguments):
                    return None
                argument = arguments[value]
                if _KEYWORD_RE.match(argument):
                    return ("fixed", argument)
                return ("param", params.index(argument)) if argument in params else None

            lifted = (lift(id_source), lift(recovery_source))
            if all(lifted):
                return lifted
    return None


def read_runtime_recoveries(roots):
    """`({id: {recovery, …}}, [unreadable site, …])` as the PACKAGE passes them.

    A set rather than one keyword because a site may compute the recovery —
    `(if (fn? head) :call-it-or-make-it-a-view :supply-a-valid-hiccup-head)`
    is written in `impl/codec` today — and both arms are then answers the
    id can give.

    The second half is the sites the reader could NOT fold, one per call,
    and it is a LIST rather than a set of ids on purpose: the claim R10
    makes is per emit site, so an id with three readable sites and one
    unreadable one is not covered, and only a per-site record can say so.
    """
    sources = []
    for path in _source_files(roots):
        with open(path, encoding="utf-8") as fh:
            sources.append((os.path.relpath(path, REPO_ROOT).replace(os.sep, "/"),
                            mask(fh.read(), strings_as_atoms=True)))
    return recoveries_in(sources)


def recoveries_in(sources):
    """[[read_runtime_recoveries]] over already-masked `(path, code)` pairs.

    Split out so the readers can be run over source this checkout does not
    have — a historical tree, a fixture — without a tree to walk.

    A CALL WHOSE ID RESOLVES AND WHOSE RECOVERY DOES NOT IS KEPT, as
    `(id, where, constructor, argument-text)`.  Dropping it was the hole
    the #8315 audit found: the aggregate is keyed by id, so a sibling call
    passing a literal made the id look accounted for and R10 asked no
    further question.  Two fixtures — one call for an id, and the same
    file plus a second call for the SAME id through `(recovery-for x)` —
    produced byte-for-byte identical output, and the second call could
    therefore raise an unrowed recovery with every arm green.

    A call whose ID does not resolve is still dropped, and that is not the
    same case: with no id there is no Spec 009 row to reconcile against,
    and the shape occurs in the package already — a constructor's own body
    forwarding its `id` parameter, which the call-site scan reads where
    its CALLERS stand instead.
    """
    constructors = read_constructors(sources)
    found, unreadable = {}, []

    def record(error_id, keywords):
        found.setdefault(error_id, set()).update(keywords)

    for name, (id_source, recovery_source) in constructors.items():
        if id_source[0] == "fixed" and recovery_source[0] == "fixed":
            record(id_source[1], [recovery_source[1]])
    for rel, code in sources:
        for error_id, recovery in _literal_pairs(code):
            record(error_id, [recovery])
        for name, (id_source, recovery_source) in constructors.items():
            head = r"\((?:%s/)?%s(?=[\s)])" % (_SYMBOL, re.escape(name))
            for match, inner in _forms(code, head):
                arguments = _call_args(inner)

                def read(source):
                    kind, value = source
                    if kind == "fixed":
                        return [value]
                    if value >= len(arguments):
                        return None
                    return _resolve(arguments[value])

                ids = [error_id for error_id in (read(id_source) or [])
                       if _ERROR_ID_RE.match(error_id)]
                if not ids:
                    continue
                recoveries = read(recovery_source)
                if recoveries:
                    for error_id in ids:
                        record(error_id, recoveries)
                    continue
                _kind, position = recovery_source
                argument = (arguments[position]
                            if position < len(arguments) else "<no such argument>")
                where = "%s:%d" % (rel, code[:match.start()].count("\n") + 1)
                for error_id in ids:
                    unreadable.append(
                        (error_id, where, name, " ".join(argument.split())))
    return found, unreadable


# ---------------------------------------------------------------------------
# Reading the register
# ---------------------------------------------------------------------------

def read_register(path):
    """`{status: {id: [cited chapter numbers]}}`, read off the markers.

    Status is STRUCTURAL — a `<!-- rf2-hic-021: status=… -->` marker opens a
    section and the next marker closes it — rather than read off a heading's
    wording or a list kept beside the tables.  Same posture as Spec 009's
    retire-in-place convention: the row says what it is.
    """
    with open(path, encoding="utf-8") as fh:
        lines = fh.read().splitlines()
    register = {status: {} for status in STATUSES}
    current = None
    for line in lines:
        marker = _MARKER_RE.search(line)
        if marker:
            current = marker.group(1)
            if current not in STATUSES:
                raise ValueError("unknown status marker %r in %s" % (current, path))
            continue
        if current is None:
            continue
        row = _ROW_ID_RE.match(line)
        if row:
            cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
            chapters = _CHAPTER_RE.findall(cells[-1]) if len(cells) > 1 else []
            register[current][row.group(1)] = chapters
    return register


def read_spec_ids(path):
    """`(active, retired)` id sets, read off Spec 009's catalogue rows."""
    active, retired = set(), set()
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            match = _ROW_ID_RE.match(line)
            if match:
                first = line.strip().strip("|").split("|")[0].strip()
                (retired if first.startswith("~~") else active).add(match.group(1))
    return active, retired


def read_spec_recoveries(path):
    """`{id: [recovery keyword, …]}` off Spec 009's recovery cell.

    The recovery is the SECOND-TO-LAST cell of a catalogue row (`| id |
    severity | channel | meaning | recovery | payload |`), read
    positionally from the right so a meaning cell's own length and
    punctuation cannot move it.
    """
    recoveries = {}
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            match = _ROW_ID_RE.match(line)
            if not match:
                continue
            cells = [cell.strip()
                     for cell in _CELL_SPLIT_RE.split(line.strip().strip("|"))]
            if len(cells) >= 2:
                recoveries[match.group(1)] = _RECOVERY_TOKEN_RE.findall(cells[-2])
    return recoveries


def read_spec_hicasso_ids(path):
    """The ids Spec 009 rows UNDER A HICASSO HEADING — R10's subject.

    Read off the heading STACK rather than the nearest heading, so a row
    keeps its owner through nesting.  The kit's fifteen ids live under an
    `#####` inside a `####` about adapter and harness categories, and only
    the stack says they are still Hicasso's.

    This is the structural fact that lets R10 hold no exemption list.  A
    row in Spec 009's MAIN catalogue is a spelling Hicasso reuses from the
    wider framework rather than one it mints — `no-frame-context`,
    `routing-artefact-missing`, `ui-tree-malformed` are rowed there today —
    and that row's recovery is written for the CORPUS's emitter, which is
    a different call site with different advice to give.  Hicasso's
    boundary passes `:mount-under-a-frame` where the row says
    `:supply-frame`, and both are right where they stand.  Requiring them
    equal would be requiring the wrong thing, not merely an inconvenient
    one — so R10's subject stops at the section boundary.
    """
    ids, stack = set(), []
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            heading = re.match(r"^(#{1,6})\s+(.*)", line)
            if heading:
                level = len(heading.group(1))
                stack = [(depth, text) for depth, text in stack if depth < level]
                stack.append((level, heading.group(2)))
                continue
            match = _ROW_ID_RE.match(line)
            if match and any("Hicasso" in text for _depth, text in stack):
                ids.add(match.group(1))
    return ids


def read_index(path):
    """`(entries, recoveries)` off the troubleshooting complaint index.

    `entries` is `{id: anchor-count}` — a COUNT rather than a set, because
    "exactly one" is the rule and a second copy of an entry is a page
    where a reader's two routes can disagree.  `recoveries` is `{id:
    [keyword, …]}`, read from the `Recovery:` block inside that id's own
    section: the line that opens it plus the lines that continue it, up
    to the first blank one.

    A mis-slugged anchor is reported as `entries` disagreeing with the
    heading rather than silently repaired, and shows up in `mismatched`.
    """
    if not os.path.isfile(path):
        return {}, {}, []
    with open(path, encoding="utf-8") as fh:
        text = fh.read()
    entries, recoveries, mismatched = {}, {}, []
    matches = list(_ENTRY_RE.finditer(text))
    for position, match in enumerate(matches):
        anchor, error_id = match.group(1), match.group(2)
        entries[error_id] = entries.get(error_id, 0) + 1
        if anchor != error_id.split("/", 1)[-1]:
            mismatched.append((error_id, anchor))
        end = matches[position + 1].start() if position + 1 < len(matches) else len(text)
        found = []
        lines = text[match.end():end].splitlines()
        for number, line in enumerate(lines):
            if line.startswith("Recovery:"):
                block = [line]
                for following in lines[number + 1:]:
                    if not following.strip():
                        break
                    block.append(following)
                found.extend(_RECOVERY_TOKEN_RE.findall(" ".join(block)))
        recoveries.setdefault(error_id, []).extend(found)
    return entries, recoveries, mismatched


def guide_chapter(number):
    """The guide file whose name starts `<number>-`, or None."""
    if not os.path.isdir(GUIDE_DIR):
        return None
    for name in sorted(os.listdir(GUIDE_DIR)):
        if name.startswith(number + "-") and name.endswith(".md"):
            return os.path.join(GUIDE_DIR, name)
    return None


# ---------------------------------------------------------------------------
# The rules
# ---------------------------------------------------------------------------

def check(register, emitted, package_ids, spec_active, spec_retired, chapter_text,
          index_entries=None, index_recoveries=None, index_mismatched=None,
          spec_recoveries=None, runtime_recoveries=None, unreadable_sites=None,
          spec_hicasso_ids=None):
    """Every rule, against already-read inputs.

    Pure so `--self-test` can drive each red by doctoring one input rather
    than by writing a fixture tree: the reds this gate must produce are
    statements about the RELATION between four documents, and a fixture
    would only re-create that relation less honestly.
    """
    failures = []
    live = register["live"]
    reserved = register["reserved"]
    pending = register["pending-retirement"]
    retired = register["retired"]
    index_entries = {} if index_entries is None else index_entries
    index_recoveries = {} if index_recoveries is None else index_recoveries
    index_mismatched = [] if index_mismatched is None else index_mismatched
    spec_recoveries = {} if spec_recoveries is None else spec_recoveries
    runtime_recoveries = {} if runtime_recoveries is None else runtime_recoveries
    unreadable_sites = [] if unreadable_sites is None else unreadable_sites
    spec_hicasso_ids = set() if spec_hicasso_ids is None else spec_hicasso_ids

    # R1 — no gap.
    for error_id in sorted(emitted):
        if error_id not in live:
            failures.append(
                "R1 %s is emitted by %s and has no `live` row in the register"
                % (error_id, ", ".join(emitted[error_id])))

    # R2 — no dead entry.
    for error_id in sorted(live):
        if error_id not in emitted:
            failures.append(
                "R2 %s has a `live` row and is emitted by nothing. A documented "
                "complaint with no emitter is a promise the substrate cannot keep"
                % error_id)

    # R3 — reservations are honest.
    for error_id in sorted(reserved):
        if error_id in package_ids:
            failures.append(
                "R3 %s is `reserved` but occurs in %s. Promote it: write the Spec "
                "009 row and move the register row to `live`"
                % (error_id, ", ".join(package_ids[error_id])))
        if error_id in spec_active or error_id in spec_retired:
            failures.append(
                "R3 %s is `reserved` but has a Spec 009 row. A reservation names a "
                "refusal nothing raises yet, and Spec 009 rows are for refusals that "
                "exist" % error_id)

    # R4 — tombstones stay dead.
    for error_id in sorted(retired):
        if error_id in package_ids:
            failures.append(
                "R4 %s is `retired` and still occurs in %s"
                % (error_id, ", ".join(package_ids[error_id])))
        if error_id in spec_active:
            failures.append(
                "R4 %s is `retired` and still has an ACTIVE Spec 009 row (retire it "
                "in place with a struck first cell)" % error_id)

    # R5 — no reuse.  Over the three EXCLUSIVE statuses only: `pending-
    # retirement` is an annotation on a live id rather than a fourth state,
    # and R7 is what requires it to co-occur with `live`.
    seen = {}
    for status in EXCLUSIVE_STATUSES:
        for error_id in register[status]:
            seen.setdefault(error_id, []).append(status)
    for error_id, statuses in sorted(seen.items()):
        if len(statuses) > 1:
            failures.append(
                "R5 %s is registered under %s at once. An id has one status; a "
                "retired spelling is never re-minted"
                % (error_id, " and ".join(sorted(statuses))))

    # R6 — the two catalogues agree.
    for error_id in sorted(live):
        if error_id not in spec_active:
            failures.append(
                "R6 %s has a `live` row and no active Spec 009 catalogue row"
                % error_id)

    # R7 — a pending retirement is still live.
    for error_id in sorted(pending):
        if error_id not in live:
            failures.append(
                "R7 %s is recorded as retiring later but has no `live` row. Either "
                "it is still raised (register it `live`) or the retirement already "
                "happened (move it to `retired`)" % error_id)

    # R8 — the anchor resolves.
    for status in STATUSES:
        for error_id, chapters in sorted(register[status].items()):
            for number in chapters:
                text = chapter_text.get(number)
                if text is None:
                    failures.append(
                        "R8 %s cites guide chapter ch%s, which does not exist"
                        % (error_id, number))
                elif error_id not in text:
                    failures.append(
                        "R8 %s cites guide chapter ch%s, which never names it"
                        % (error_id, number))

    # R9 — the index is the route in.  Both directions against `live`, which
    # R1 and R2 have already pinned to the raise set, so this IS the index /
    # raise-set round trip.  See the header for what it cannot see.
    for error_id in sorted(live):
        count = index_entries.get(error_id, 0)
        if count == 0:
            failures.append(
                "R9 %s is raised by the package and has no anchor in the "
                "troubleshooting index. A complaint a reader cannot look up is "
                "a stable id with nowhere to spend it" % error_id)
        elif count > 1:
            failures.append(
                "R9 %s is anchored %d times in the troubleshooting index. Two "
                "entries are two answers that can drift apart; keep one"
                % (error_id, count))
    for error_id in sorted(index_entries):
        if error_id not in live:
            where = [status for status in EXCLUSIVE_STATUSES
                     if error_id in register[status]]
            failures.append(
                "R9 %s is anchored in the troubleshooting index and is not "
                "`live` (%s). The index documents what the runtime raises "
                "TODAY" % (error_id, " and ".join(where) or "in no register row"))
    for error_id, anchor in sorted(index_mismatched):
        failures.append(
            "R9 %s is indexed under the anchor %r. The anchor is the deep-link "
            "target; it is the id's local part or inbound links rot"
            % (error_id, anchor))
    for error_id in sorted(index_entries):
        if error_id not in live:
            continue                      # already reported, one failure each
        shown = index_recoveries.get(error_id) or []
        if not shown:
            failures.append(
                "R9 %s is indexed with no recovery keyword. The keyword is what "
                "the reader matches against what they caught" % error_id)
            continue
        rowed = set(spec_recoveries.get(error_id, []))
        for keyword in sorted(set(shown) - rowed):
            failures.append(
                "R9 %s is indexed with the recovery %s, which its Spec 009 row "
                "does not carry. `:recovery` is advice about a LIVE api and "
                "moves when that api is renamed — move it in one pass across "
                "the runtime, Spec 009 and this page (rf2-15bqc)"
                % (error_id, keyword))

    # R10 — the runtime is the anchor.  One direction only; the header says
    # which, why the reverse is not checked, and what this does NOT close.
    for error_id in sorted(set(live) & set(spec_hicasso_ids)):
        passed = runtime_recoveries.get(error_id)
        if not passed:
            failures.append(
                "R10 %s is rowed in Spec 009's Hicasso section and NO emit site "
                "this gate can read passes a `:recovery` for it. Either the id "
                "is minted through a shape the reader does not know — in which "
                "case R10 is silently covering less than it claims, and the "
                "reader is what needs the edit — or the id is not raised at all "
                "and R2 is about to say so" % error_id)
            continue
        rowed = set(spec_recoveries.get(error_id, []))
        for keyword in sorted(set(passed) - rowed):
            failures.append(
                "R10 %s is RAISED with the recovery %s, which its Spec 009 row "
                "does not carry (the row has %s). The runtime is the anchor: "
                "either the emit site is giving advice no catalogue documents, "
                "or Spec 009 has been left behind by a rename. Move it in one "
                "pass — runtime, Spec 009, then the guide R9 checks"
                % (error_id, keyword,
                   ", ".join(sorted(rowed)) or "no recovery keyword at all"))
    for error_id, where, minter, argument in sorted(unreadable_sites):
        if error_id not in live or error_id not in spec_hicasso_ids:
            continue
        failures.append(
            "R10 %s is raised at %s through `%s` with a `:recovery` this gate "
            "cannot read: %s. A SIBLING call passing a literal is not cover — "
            "the claim is per emit site, and this one can raise a keyword no "
            "row carries. Pass a keyword literal, or an `if`/`if-not` over two "
            "of them; if the recovery genuinely has to be computed, the reader "
            "in this script is what needs the edit"
            % (error_id, where, minter, argument))
    return failures


def report(failures):
    """Print `failures` in the gate's voice, and return the exit code 1.

    Both entry points red through here, so the rule that broke is the first
    thing on screen whichever one you ran.
    """
    print("FAIL: Hicasso complaint catalogue\n")
    for failure in failures:
        print("  " + failure)
    print("\nThe register is docs/design/hicasso/product/complaints.md; the "
          "rule each R-number names is in this script's header.")
    return 1


def read_all():
    register = read_register(REGISTER)
    emitted = read_ids(EMIT_ROOTS)
    package_ids = read_ids([PACKAGE_ROOT])
    spec_active, spec_retired = read_spec_ids(SPEC_009)
    cited = set()
    for status in STATUSES:
        for chapters in register[status].values():
            cited.update(chapters)
    chapter_text = {}
    for number in sorted(cited):
        path = guide_chapter(number)
        if path is None:
            continue
        with open(path, encoding="utf-8") as fh:
            chapter_text[number] = fh.read()
    index_entries, index_recoveries, index_mismatched = read_index(TROUBLESHOOTING)
    runtime_recoveries, unreadable_sites = read_runtime_recoveries(EMIT_ROOTS)
    return dict(register=register, emitted=emitted, package_ids=package_ids,
                spec_active=spec_active, spec_retired=spec_retired,
                chapter_text=chapter_text, index_entries=index_entries,
                index_recoveries=index_recoveries,
                index_mismatched=index_mismatched,
                spec_recoveries=read_spec_recoveries(SPEC_009),
                runtime_recoveries=runtime_recoveries,
                unreadable_sites=unreadable_sites,
                spec_hicasso_ids=read_spec_hicasso_ids(SPEC_009))


# ---------------------------------------------------------------------------
# --self-test: prove the gate classifies, rather than merely runs
# ---------------------------------------------------------------------------

def self_test():
    # THIS SELF-TEST'S OWN CLAIMS MUST NOT BE DELETABLE (rf2-uyhh). `python -O`
    # — and `PYTHONOPTIMIZE` in the environment, which needs no flag at the
    # call site — strips every `assert` below, leaving a function that runs to
    # its success line having verified nothing. That is a control failing
    # GREEN, the one direction that never announces itself. The check is
    # empirical rather than a reading of `__debug__`, so it also catches a
    # `.pyc` compiled under `-O` and run without it.
    try:
        assert False
    except AssertionError:
        pass
    else:
        raise SystemExit(
            "assertions are disabled (python -O / PYTHONOPTIMIZE), so this "
            "self-test would prove nothing. Re-run without -O."
        )

    assert mask('(fail! :rf.error/a "text :rf.error/in-a-string")') \
        .count(":rf.error/") == 1, "a string literal must not emit an id"
    assert ":rf.error/" not in mask("; :rf.error/in-a-comment\n"), \
        "a comment must not emit an id"
    assert mask('(= \\" :rf.error/after-a-quote-char)').count(":rf.error/") == 1, \
        "a quote character literal must not open a string"

    # R9's readers are proved on written-down fixtures rather than only
    # through `check`, because a reader that silently found NOTHING would make
    # every R9 arm below vacuous in the one direction that passes.
    assert read_spec_recoveries.__doc__, "the recovery reader is documented"
    assert _ENTRY_RE.search('<a id="hicasso-x"></a>\n#### `:rf.error/hicasso-x`\n'), \
        "the index entry shape must match with a bare newline"
    assert _ENTRY_RE.search('<a id="hicasso-x"></a>\r\n#### `:rf.error/hicasso-x`\r\n'), \
        "and with CRLF, which is what these files actually carry"
    assert _CELL_SPLIT_RE.split(r"a \| b | c") == [r"a \| b ", " c"], \
        "an escaped pipe is content, not a cell boundary"

    inputs = read_all()
    # Every red below is driven by doctoring ONE input of a green tree, so a
    # tree that is already red cannot be tested against.  That is a finding
    # about the register rather than about this self-test, and it is by far
    # the likeliest way this entry point fails on a PR — so it is reported
    # exactly as a plain run reports it.  An `assert` here would bury the rule
    # that broke behind an AssertionError.
    failures = check(**inputs)
    if failures:
        code = report(failures)
        print("\n(--self-test drives its reds by doctoring a GREEN tree's "
              "inputs, so it stops here.)")
        return code

    register = inputs["register"]
    emitted = inputs["emitted"]
    package_ids = inputs["package_ids"]
    spec_active = inputs["spec_active"]
    chapters = inputs["chapter_text"]
    index_entries = inputs["index_entries"]
    index_recoveries = inputs["index_recoveries"]

    def red(prefix, **overrides):
        args = dict(inputs)
        args.update(overrides)
        found = check(**args)
        assert any(f.startswith(prefix) for f in found), \
            "%s did not red: %r" % (prefix, found)

    def with_status(status, key, value):
        copy = {name: dict(rows) for name, rows in register.items()}
        copy[status][key] = value
        return copy

    a_live = sorted(register["live"])[0]
    a_reserved = sorted(register["reserved"])[0]

    # R1 — an emitted id the register does not carry.
    red("R1", emitted=dict(emitted, **{":rf.error/hicasso-unregistered":
                                       ["implementation/hicasso/src/x.cljs"]}))
    # R2 — a register row nothing raises.
    red("R2", register=with_status("live", ":rf.error/hicasso-never-raised", []))
    # R3 — a reservation that has quietly become real.
    red("R3", package_ids=dict(package_ids,
                               **{a_reserved: ["implementation/hicasso/src/x.cljs"]}))
    red("R3", spec_active=spec_active | {a_reserved})
    # R4 — a tombstone with an emitter, and one with an active Spec 009 row.
    red("R4", register=with_status("retired", ":rf.error/hicasso-tombstoned", []),
        package_ids=dict(package_ids,
                         **{":rf.error/hicasso-tombstoned":
                            ["implementation/hicasso/src/x.cljs"]}))
    red("R4", register=with_status("retired", a_live, []))   # also reds R5
    # R5 — one id, two statuses.
    red("R5", register=with_status("reserved", a_live, []))
    # R6 — a live row with no Spec 009 row.
    red("R6", spec_active=spec_active - {a_live})
    # R7 — a pending retirement that is no longer live.
    red("R7", register=with_status("pending-retirement",
                                   ":rf.error/hicasso-not-live", []))
    # R8 — an anchor that does not resolve, and one that resolves to a page
    # that never names the id.
    red("R8", register=with_status("live", a_live, ["99"]))
    red("R8", register=with_status("live", a_live, ["02"]),
        chapter_text=dict(chapters, **{"02": "a chapter naming nothing"}))

    # R9 — one control per way the index and the raise set can part company.
    # The first is not hypothetical: it is the state this rule was written to
    # end.  `:rf.error/hicasso-overlay-anchor-missing` was promoted from
    # `reserved` to `live` by rf2-1ppe0 and the index was not extended, and
    # every rule R1–R8 stayed green over it — the register agreed with the
    # runtime, the runtime agreed with Spec 009, and the page nobody checked
    # was the only document that was wrong.
    without = dict(index_entries)
    without.pop(a_live)
    red("R9", index_entries=without)                     # a live id, unindexed
    red("R9", index_entries=dict(index_entries, **{a_live: 2}))   # indexed twice
    red("R9", index_entries=dict(index_entries,
                                 **{a_reserved: 1}))     # a reservation, indexed
    red("R9", index_entries=dict(index_entries,
                                 **{":rf.error/hicasso-not-a-complaint": 1}))
    red("R9", index_mismatched=[(a_live, "an-anchor-nothing-points-at")])
    red("R9", index_recoveries=dict(index_recoveries, **{a_live: []}))
    red("R9", index_recoveries=dict(index_recoveries,
                                    **{a_live: [":a-recovery-spec-009-never-rowed"]}))
    # And the drift that started this: the page keeping a spelling the runtime
    # and Spec 009 have moved on from (rf2-15bqc's four `h-fn` keywords).
    red("R9", index_recoveries=dict(
        index_recoveries,
        **{":rf.error/hicasso-intent-needs-the-event":
           [":write-an-h-fn-at-a-value-first-position"]}))

    # R10's READERS, on written-down fixtures.  A reader that quietly found
    # NOTHING would leave every rule arm below vacuous in the one direction
    # that passes — which is how this gate's own subject went uncovered
    # until rf2-5zmul.
    assert mask("(fail! :rf.error/a 'w \"reason\" :fix {})", strings_as_atoms=True) \
        == "(fail! :rf.error/a 'w # :fix {})", \
        "a plain-string reason must survive as ONE argument, not vanish"
    assert mask('(fail! :rf.error/a "r")').count(":rf.error/") == 1, \
        "and the default masking is unchanged, because R1-R9 read it"
    assert mask('(str "a\nb") :fix', strings_as_atoms=True) == '(str #\n) :fix', \
        "a newline inside a collapsed string SURVIVES, or every line number " \
        "R10 prints is short by the length of the reasons above the site"
    assert _call_args(" :rf.error/a 'w (str # #) :fix {}") \
        == [":rf.error/a", "'w", "(str # #)", ":fix", "{}"], \
        "arguments split at the TOP level, a multi-line (str …) counting once"
    assert _resolve("(if (fn? head) :call-it :supply-a-head)") \
        == [":call-it", ":supply-a-head"], \
        "a conditional recovery reads as the SET of arms the id can answer"
    assert _resolve(":supply-a-head") == [":supply-a-head"], "and a literal is one"
    assert _resolve("(get advice :fallback)") is None, \
        "a lookup is UNREADABLE, not a site that raises :fallback — scavenging " \
        "keywords out of an expression invents a recovery to reconcile"
    assert _resolve("(if (fn? head) :call-it (advise head))") is None, \
        "and a conditional is admitted only when BOTH arms are literals"

    def constructors_of(text):
        return read_constructors([("fixture.cljc", mask(text, strings_as_atoms=True))])

    def read_fixture(text):
        return recoveries_in([("fixture.cljs", mask(text, strings_as_atoms=True))])

    minter = ("(defn fail! [id where reason recovery extra]"
              "  (throw (ex-info reason {:rf.error/id id :where where"
              "                          :reason reason :recovery recovery})))")
    assert constructors_of(minter) == {"fail!": (("param", 0), ("param", 3))}, \
        "a constructor's recovery POSITION is derived from its own parameters"
    assert constructors_of(
        minter + "(defn walk [x] (fail! :rf.error/hicasso-true-child 'w"
                 " \"r\" :use-nil-or-false {}))").keys() == {"fail!"}, \
        "a defn whose id AND recovery are literal is an EMIT SITE, not a " \
        "constructor — admitting it attributes its refusal to every caller"
    assert constructors_of(
        minter + "(defn refuse-opaque! [id reason extra]"
                 "  (fail! id 'w reason :assert-it-at-l3 extra))"
    )["refuse-opaque!"] == (("param", 0), ("fixed", ":assert-it-at-l3")), \
        "a helper that fixes the recovery and takes the id from its caller " \
        "is itself a constructor, and its call sites are emit sites"

    # THE KNOWN-PLUS-UNREADABLE-SIBLING FIXTURE, pinned exactly (rf2-5zmul,
    # audit of #8315).  Before the per-site correction these two texts read
    # byte-for-byte identically, and that identity IS the defect: the second
    # site can raise anything at all and the id still looks accounted for.
    one_site = minter + ('(defn a [] (fail! :rf.error/example (quote w) "r"'
                         '                  :known-recovery {}))')
    sibling = one_site + ('(defn b [x] (fail! :rf.error/example (quote w) "r"'
                          '                   (recovery-for x) {}))')
    known, quiet_sites = read_fixture(one_site)
    also_known, kept = read_fixture(sibling)
    assert known == {":rf.error/example": {":known-recovery"}} and not quiet_sites, \
        "a readable site is read, and reports nothing unreadable"
    assert also_known == known, \
        "the AGGREGATE is blind to the sibling — this is why it cannot be the " \
        "whole of the rule, and asserting it keeps the reason on the record"
    assert [(error_id, minter_name) for error_id, _where, minter_name, _argument
            in kept] == [(":rf.error/example", "fail!")], \
        "so the unreadable sibling is KEPT as a site, which is the fix"
    assert kept[0][3] == "(recovery-for x)" and kept[0][1].startswith("fixture.cljs:"), \
        "and it names the expression and the place, because the reader's " \
        "own limit is what the author has to act on"

    # R10 — the rule, both arms, plus the subject boundary that is the whole
    # reason it needs no exemption list.
    runtime = inputs["runtime_recoveries"]
    anchored = sorted(set(register["live"]) & set(inputs["spec_hicasso_ids"]))
    assert anchored, "R10 has a subject at all"
    an_anchored = anchored[0]
    red("R10", runtime_recoveries=dict(
        runtime, **{an_anchored: {":a-recovery-spec-009-never-rowed"}}))
    unaccounted = dict(runtime)
    unaccounted.pop(an_anchored, None)
    red("R10", runtime_recoveries=unaccounted)      # covering less than it claims
    # And the arm the audit of #8315 added: an id with a perfectly good
    # readable site is STILL uncovered while one of its siblings is unreadable.
    # `runtime_recoveries` is left untouched here on purpose — that input is
    # exactly what the aggregate could not distinguish.
    red("R10", unreadable_sites=[(an_anchored, "impl/codec.cljs:1", "fail!",
                                  "(recovery-for x)")])

    corpus_owned = sorted(set(register["live"]) - set(inputs["spec_hicasso_ids"]))
    assert corpus_owned, \
        "the corpus-owned population is what R10's subject rule exists for"
    quiet = check(**dict(inputs, runtime_recoveries=dict(
        runtime, **{corpus_owned[0]: {":a-recovery-no-catalogue-rows-anywhere"}})))
    assert not [failure for failure in quiet if failure.startswith("R10")], \
        "R10 must hold NO opinion on a spelling Hicasso reuses from the " \
        "corpus: that row's recovery answers for a different emitter"
    quiet = check(**dict(inputs, unreadable_sites=[
        (corpus_owned[0], "impl/x.cljs:1", "fail!", "(recovery-for x)")]))
    assert not [failure for failure in quiet if failure.startswith("R10")], \
        "and the per-site arm stops at the SAME boundary, or the subject rule " \
        "would hold for two arms and not the third"

    print("OK: check_complaint_catalogue self-test passed")
    return 0


# ---------------------------------------------------------------------------

def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--self-test", action="store_true",
                        help="prove the gate's red/green classification, then exit")
    parser.add_argument("--list", action="store_true",
                        help="print every id the package emits, with its emit sites")
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()

    if args.list:
        for error_id, paths in sorted(read_ids(EMIT_ROOTS).items()):
            print("%-58s %s" % (error_id, ", ".join(paths)))
        return 0

    inputs = read_all()
    failures = check(**inputs)
    if failures:
        return report(failures)
    register = inputs["register"]
    anchored = sorted(set(register["live"]) & set(inputs["spec_hicasso_ids"]))
    print("OK: %d live, %d reserved, %d pending retirement, %d retired; every "
          "live row is emitted and rowed in Spec 009, every reservation is "
          "unbuilt, every anchor resolves, and the troubleshooting index "
          "carries all %d live ids exactly once with Spec 009's recoveries.\n"
          "    R10: %d of those are rowed in Spec 009's Hicasso section, and "
          "every `:recovery` the package raises for them is in its row — at "
          "every emit site, with none the reader had to skip. The other %d are "
          "corpus-owned spellings rowed in the main catalogue, whose recovery "
          "answers for a different emitter."
          % (len(register["live"]), len(register["reserved"]),
             len(register["pending-retirement"]), len(register["retired"]),
             len(register["live"]), len(anchored),
             len(register["live"]) - len(anchored)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
