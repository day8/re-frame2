#!/usr/bin/env python3
"""Every sample in the Hicasso guide is pinned, and every verb it names is real (rf2-qgbu5).

WHY THIS EXISTS
---------------
`docs/core/hicasso/` is the front door: 23 chapters, 188 fenced blocks, and
the first thing a new reader is shown.  It arrived there by promotion
(rf2-0yp7w phase 5) out of `docs/design/hicasso/draft-guide/`, and the
promotion moved the prose without moving any coverage — because the corpus it
DISPLACED had some.  The Freehand guide carried a roster in
`implementation/freehand/test/re_frame/freehand/guide/samples_coverage_jvm_test.clj`
pinning 138 fenced blocks by digest, so editing any block reds a suite.  The
promoted guide inherited none of that, and the asymmetry was measured rather
than assumed: a line added inside the first fenced block of
`00-installation.md` left the Freehand suite at exit 0, which is the CORRECT
result — its guide-dir is the literal `docs/core/freehand` — and is also the
hole.

A guide whose samples are unexecuted teaches whatever rots first.

WHY NOT SIMPLY RE-POINT THE FREEHAND SUITE
------------------------------------------
Because its second half cannot be re-pointed, and its first half cannot live
where the re-pointing would put it.

The Freehand mechanism is TWO guarantees wearing one coat.  A roster pins each
block's text (drift between page and digest), and each roster row names a
CLJS fixture var that a `deftest` actually renders (a sample that no longer
compiles or runs).  The second half is 2,600 lines of fixture transcribing
Freehand samples onto the real classpath.

Transcribing this guide the same way would not produce that guarantee, it
would LAUNDER its absence.  The guide is deliberately ahead of the code in
the places it names in its own Status block, so a fixture would have to be
written in today's spellings — `h/hframe` where the page teaches `h/frame` —
and a fixture proving a different program from the one on the page is worse
than no fixture, because its roster row reads as coverage.  The example used
to be `h/hfn` against a taught `h/fn`; rf2-hic-066 closed that one by
respelling BOTH sides `h/event`, which is the outcome R3 exists to force.
Chapter 18 is written
against a `re-frame.hicasso.server` that does not exist at all; there is no
spelling that compiles.

And the suite could not live beside the package anyway.
`implementation/hicasso` is deliberately absent from
`scripts/test-jvm-implementation.sh` — the comment there says so and names the
reason — because `check_jvm_lane_rosters.py` refuses a roster entry with no
matching `.github/workflows/test.yml` job, and that workflow is hot-zone.  A
`deftest` here would run in no lane at all.  So this is Python, chained into
`npm run test:hicasso-invariants`, which is the shape five sibling hicasso
invariants already use for exactly that reason (`check_freeze.py`,
`check_lint_export.py`, `check_complaint_catalogue.py`, `check_budget_ledger.py`,
`check_example_fence_coverage.py`).

WHAT IS CHECKED — SIX RULES
---------------------------
R1  PINNED.  Every fenced block in the guide carries a roster row, in document
    order, and the row's digest still matches the block's text.  Adding a
    block, editing one, deleting one, adding a page or deleting a page each
    red, naming the page and the ordinal and printing the row to paste.

    The digest is over the block's text normalised for LINE ENDINGS and
    TRAILING WHITESPACE only.  Nothing else is normalised, deliberately: a
    `;; =>` comment inside a sample usually carries the claim the sample is
    making, and a rule that ignored comments would ignore those.

R2  RESOLVED.  Every `alias/verb` a Clojure block names — where `alias` is one
    the guide's OWN `:require` forms bind to a `re-frame.hicasso*` namespace —
    is defined in that namespace's source, or carries a row in `DIVERGENCES`
    below.  A renamed or deleted verb reds here.  This is the half the
    Freehand fixtures bought with a compile; it is bought here with a read,
    which is weaker (it proves the name exists, not that the sample runs) and
    is the strongest thing available while the guide leads the code.

    The alias set is DERIVED from the guide's `:require` forms, never listed,
    so `js/`, `rf/`, `react/`, `str/` and a page's own `old/` / `new/` compare
    aliases are out of scope by construction rather than by a filter someone
    has to maintain.  It is the `re-frame.hicasso*` FILTER over the one
    derivation R5 also reads (see [[bound_aliases]]), rather than a second
    reader that could drift from it — and it is measured: narrowing that
    derivation from raw page text to actual `ns` bindings left this rule's
    104 use-sites and 10 aliases identical.

    R2 POOLS THOSE ALIASES ACROSS THE WHOLE GUIDE, and that is correct FOR R2:
    its question is "does this verb exist in that namespace", and answering it
    needs only a name-to-namespace map, no matter which page bound it.  What
    the pooling cannot do is answer a DIFFERENT question — "does this block
    bind the alias it uses" — and for a long time nothing asked it.  That is
    R5 below, and the two are kept apart deliberately rather than by folding
    a scope test into R2 and giving one rule two jobs.

R5  SELF-CONTAINED.  The `(ns …)` form governing a Clojure block's section
    binds every `::alias/name` the block reads, and every `alias/name` symbol
    whose alias the guide binds anywhere.  A block that names `rf/reg-event`
    while its own `ns` form requires no `re-frame.core` cannot compile, and
    before this rule nothing here could see it (rf2-vz6dg).

    SCOPE IS PER SECTION, not per page and not per block.  Per block is wrong
    because the guide legitimately teaches an `ns` form once and then shows
    several blocks under it; per page is wrong MEASURED — it reports 21 sites
    on a corpus that is correct, because a page routinely finishes with one
    namespace and starts illustrating another without a new `ns` form.  A
    markdown heading is where the guide changes subject, so a heading ends the
    scope.  Measured on the corpus this rule shipped against: 0 problems,
    and 6 on the pre-repair cookbook, which is every defect rf2-hic-060 found
    by hand plus the two further sites in the same recipes.

    IT READS AUTO-RESOLVED KEYWORDS, and that is the half R2 is blind to by
    construction.  `::subs/row-count` needs `subs` bound AT READ TIME, so a
    missing alias there is not an undefined var but a hard reader failure —
    `Invalid keyword` — a recipe that never parsed at all.  R2 correctly does
    not treat such a keyword as a VAR USE (its own self-test asserts so, and
    that stays true); R5 reads the same form for its ALIAS only.  This is why
    the rule that catches the cookbook's two defects is ONE rule: a missing
    `re-frame.core` and a missing `my.app.subs` are the same failure, seen
    once through a symbol and once through a keyword.

    THE TWO KINDS ARE NOT HELD TO THE SAME STANDARD, because the two
    spellings are not equally certain, and the headline above says so rather
    than claiming a reach the code does not have.

    A `::alias/name` keyword is checked UNCONDITIONALLY.  The spelling means
    "resolve this alias, now" and can mean nothing else, so an alias the
    guide binds NOWHERE is not an out-of-scope qualifier — it is the reader
    failure this rule exists to catch, and it is exactly where a TYPO lands,
    a typo being by definition bound nowhere.  Gating it on "an alias the
    guide binds somewhere else" made the rule blindest at its own centre.

    A bare `alias/name` SYMBOL is checked only when the guide itself binds
    that alias somewhere, by the derived construction R2 uses: the spelling
    is ambiguous — `js/`, `goog/`, a fully qualified namespace, a reader's
    own — and the alternative is the maintained exclusion list both rules
    exist to avoid.  This is a deliberate ceiling, not an oversight: a var
    use of an alias no page anywhere requires goes unseen here.

    DISCOVERY READS BINDINGS, NOT TEXT THAT LOOKS LIKE ONE.  That sentence
    cost two repairs, because `[re-frame.core :as rf]` can appear in a block
    four ways and only one of them requires anything.

    The first repair MASKS the inert spellings: a `;; [re-frame.core :as rf]`
    the author commented out is precisely the require the sample is MISSING,
    and a `#_[re-frame.core :as rf]` inside a live `:require` is text the
    reader throws away.  The second stops reading the masked BODY at all and
    reads the `ns` form's own `:require` clauses instead, because live text
    is not thereby a binding either — `(def documented-shape '[re-frame.core
    :as rf])` is a datum and `(comment [re-frame.core :as rf])` is a no-op,
    and each was taken for the missing require.

    THE SAME RULE GOVERNS THE VOCABULARY, which is the same defect seen from
    the other end and fails the other way.  When the alias set was scraped
    from raw page text, a commented-out require in PROSE made its alias look
    guide-bound, and a `sbus/row-count` symbol the var arm is meant to ignore
    became an error instead.  One derivation now feeds scope, vocabulary and
    R2's namespace map alike, so a green run means bindings throughout.

R6  READABLE.  Every Clojure block is inside the subset of Clojure this gate
    reads, and the gate REFUSES the rest rather than guessing.

    `#_` elides the next FORM and forms nest, so bounding one in general is a
    reader's job rather than a matcher's.  Three shapes are bounded exactly —
    a delimited form, a string, a plain token — and that is where the line is
    drawn, because a guess is wrong in BOTH directions at once: read too
    short, inert text goes on binding aliases and R5 certifies a sample that
    cannot compile; read too long, live code is swallowed and R5 reds on one
    that can.

    This is the file's one FAIL-CLOSED arm, and it is what keeps the repair
    above from needing a compiler lane or a Clojure reader.  A shape the gate
    cannot bound is a shape it declines to certify.

R3  THE LEDGER IS LIVE.  This is the rule the Freehand mechanism did not have,
    and the reason the promotion's coverage loss is worth more than a digest.

    Every `DIVERGENCES` row must still describe reality: the spelling the
    guide teaches is still ABSENT from the door, and the spelling the row says
    is exported instead is still PRESENT.  So when a gap CLOSES — `h/frame` is
    finally exported under that name, or `re-frame.hicasso.server` lands — the
    row goes stale and this reds, naming the chapters that must now change.
    That is not hypothetical: rf2-hic-066 retired the `fn` row by respelling
    the callback form `h/event` on both sides at once.

    Prose cannot notice a gap closing.  Neither can a digest, and neither
    could a fixture.  A guide that stays wrong AFTER the code catches up is
    the exact failure the Freehand roster was built to end, and R3 is the only
    half of this that ends it.

R4  THE PAGE MATCHES THE LEDGER.  Every `DIVERGENCES` row is declared as a
    bullet in the Status block on `docs/core/hicasso/index.md`, and every
    such bullet has a row.  R3 watches the code move under the ledger; this
    watches the PAGE move out from under it, which is the same failure seen
    from the other side and the one the reader actually meets.

    It exists because that failure was observed rather than imagined
    (rf2-hni7l).  When `re-frame.hicasso.server` and `h/hydrate!` shipped,
    R3 red on the two stale rows exactly as designed and both rows went; the
    Status block's two paragraphs describing those doors as ABSENT stayed,
    and the page went on telling readers to treat a shipped chapter as an
    intended contract.  R1, R2 and R3 were green the whole time — none of
    them reads prose, and this rule is the narrowest thing that does.

    It checks NAME CORRESPONDENCE, not sentence truth: a bullet can still be
    wrong about what `h/mount!` takes and stay green.  That is the intended
    ceiling.  The cheap mechanical half of the invariant is worth having, and
    the expensive half — is this paragraph's prose accurate? — is what review
    is for.

USAGE
    python hicasso/scripts/check_guide_samples.py --self-test
    python hicasso/scripts/check_guide_samples.py
    python hicasso/scripts/check_guide_samples.py --emit-roster
    python hicasso/scripts/check_guide_samples.py --list

SCHEDULING NOTE — the hole this note used to record is CLOSED (rf2-r5iy7).
`npm run test:hicasso-invariants` reaches CI through test.yml's `cljs` job,
which is armed by `implementation/hicasso/**`, and a guide-ONLY edit still arms
nothing: `.github/scripts/report-changed-surfaces.sh
docs/core/hicasso/00-installation.md` reports `cljs_node_test=false`
(measured).  So the chain alone would leave this gate running in the local
fast-PR spine and NOT in required CI.  Of the two ways out — an unconditional
`test.yml` job, the shape `hicasso-complaint-catalogue` and
`hicasso-budget-ledger` already use, or a `docs/core/hicasso/**` arming case —
rf2-r5iy7 measured the arming case and REJECTED it: the only output that would
reach this checker is `cljs_node_test`, the ~10-minute node build, which would
then be scheduled by a prose typo.  It took the unconditional job instead, so
`hicasso-guide-samples` carries no `if:` and a guide-only PR runs this gate.
That is also why `docs/core` is DECLARED rather than armed in
`implementation/scripts/_changed-surfaces.test.cjs`.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import sys

SCRIPTS_DIR = os.path.dirname(os.path.abspath(__file__))
PACKAGE_ROOT = os.path.dirname(SCRIPTS_DIR)                 # implementation/hicasso
REPO_ROOT = os.path.dirname(os.path.dirname(PACKAGE_ROOT))  # repo root

GUIDE_DIR = os.path.join(REPO_ROOT, "docs", "core", "hicasso")

# Where a `re-frame.hicasso*` namespace's source may live.  `test_kit/src` is
# on the list because the guide's testing chapter is written against
# `re-frame.hicasso.test` / `.test.mounted`, which ship from there — the
# supported L0-L2 surface, kept off `:paths` so a consumer who writes no test
# carries none of it.
SOURCE_ROOTS = [
    os.path.join(PACKAGE_ROOT, "src"),
    os.path.join(PACKAGE_ROOT, "test_kit", "src"),
]

SOURCE_EXTS = (".cljc", ".cljs", ".clj")

# The fence languages whose bodies R2 reads.  Everything else in the guide —
# `bash`, `css`, `text`, `html` — is still PINNED by R1; it simply carries no
# Clojure verb for R2 to resolve.
CLOJURE_LANGS = frozenset({"clojure", "clj", "cljs", "cljc", "edn"})


# ---------------------------------------------------------------------------
# The divergence ledger
# ---------------------------------------------------------------------------
#
# The guide is written against the INTENDED authoring surface, and says so in
# the Status block on `docs/core/hicasso/index.md`.  Each row below is one of
# those deliberate gaps, and each row is a CLAIM that R3 re-checks every run:
# `guide_spelling` is still absent, and `exported_as` (when given) is still
# present.  Close the gap and the row goes stale and reds.
#
# `exported_as = None` means the door carries no counterpart at all today, so
# there is nothing for R3 to require the presence of — only the absence.
#
# A row here is NOT an excuse column.  It is admissible only for a spelling
# the guide's own Status block already declares to the reader, so the ledger
# and the page cannot drift apart silently: an entry with no matching bullet
# in the Status block is a guide that has begun lying.
#
# That sentence used to end "and the reviewer sees it as an added row in this
# file" — which was true of an ADDED row and silent about a DROPPED one.  Two
# rows left this table when their gaps closed, their bullets did not, and no
# reviewer saw it (rf2-hni7l).  R4 now enforces the correspondence in both
# directions, so this comment states an invariant the gate keeps rather than
# one it hopes for.

# `("re-frame.hicasso", "fn") -> "hfn"` LEFT THIS TABLE in rf2-hic-066, and
# R3 is what would have caught it had it not: naming-ledger row 1's operator
# ruling respelled the one callback form `h/event` on BOTH sides at once, so
# the gap closed rather than moved.  The guide taught `h/fn` in 47 places
# against an export named `hfn` and a door carrying no `fn` at all, which is
# the one divergence here that was never a candidate-versus-candidate
# question: `h/fn` was ruled OUT, so the row described a spelling nothing was
# waiting to take.  Its Status-block bullet on `index.md` left in the same
# pass -- R4 enforces that correspondence in both directions.
#
# `("re-frame.hicasso", "mount!") -> "root!"` LEFT THIS TABLE in rf2-7mtcf,
# and it is the row R3 was BUILT for: it recorded a gap the guide could not
# close by respelling anything, because the divergence was never only a name.
# The row's own reason said so -- *"`root!` takes the frame keyword
# positionally and carries no `:initial-events` option"* -- so naming-ledger
# row 13's `root!` -> `mount!` could not be taken as the mechanical rename its
# packet called it, and rf2-hic-066's sweep correctly STOPPED here rather than
# publishing the taught name against a door that could not serve it.  What
# closed the gap was the CONTRACT: row 20's `(node config view)` shape, already
# ruled *keep as taught*, plus `impl.mount/ensure-frame!` supplying the
# `:initial-events` the row named as missing.  Its Status-block bullet on
# `index.md` left in the same pass -- R4 enforces that correspondence in both
# directions.
DIVERGENCES = {
    ("re-frame.hicasso", "frame"): (
        "hframe",
        "a bare `frame` would shadow on a `:refer`; naming-ledger row 18 "
        "retires the verb rather than respelling it, and that retirement "
        "waits on a seam rather than on a name (rf2-hic-066)",
    ),
}

# A namespace the guide `:require`s that does not exist yet.  Same contract as
# a verb row: R3 requires it to still be absent, so the chapter written
# against it is forced back open the day it lands.
#
# EMPTY SINCE rf2-b6jkj, and R3 worked exactly as designed on the way out.
# `re-frame.hicasso.server` was the one entry; the module landed, the row went
# stale, and R3 red naming it -- which is the whole point of the rule, since
# prose, a digest and a fixture would each have stayed green while chapter 18
# quietly became true.  `h/hydrate!` left DIVERGENCES in the same pass and for
# the same reason: the hold on it was the absent server counterpart, so the two
# gaps closed together or not at all.
ABSENT_NAMESPACES = {}


# ---------------------------------------------------------------------------
# Splitting a page into blocks, and digesting one
# ---------------------------------------------------------------------------


def sha12(text: str) -> str:
    """First 12 hex digits of the SHA-256 — short enough to read in a diff."""
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:12]


def normalise(body_lines: list[str]) -> str:
    """Line endings and trailing whitespace ONLY.

    A `.md` checked out CRLF on Windows and LF on Linux is the same sample,
    and an editor stripping a trailing space did not change what the page
    teaches.  Nothing else is normalised — see R1.
    """
    lines = [line.rstrip() for line in body_lines]
    while lines and not lines[-1].strip():
        lines.pop()
    return "\n".join(lines)


def blocks(text: str) -> list[dict]:
    """Every fenced block in `text`, in document order.

    A fence is a line opening with three backticks; the block runs to the next
    such line.  That is the whole parser — this is deliberately not a markdown
    interpreter, and it never evaluates a line of Clojure.
    """
    lines = text.replace("\r\n", "\n").split("\n")
    out: list[dict] = []
    i = 0
    n = 0
    while i < len(lines):
        if not lines[i].startswith("```"):
            i += 1
            continue
        lang = lines[i][3:].strip() or "none"
        j = i + 1
        while j < len(lines) and not lines[j].startswith("```"):
            j += 1
        body = lines[i + 1:j]
        n += 1
        first = next((ln for ln in body if ln.strip()), "")
        out.append(
            {
                "n": n,
                "lang": lang,
                "body": body,
                "digest": sha12(normalise(body)),
                "first_line": first.strip(),
            }
        )
        i = j + 1
    return out


def corpus(guide_dir: str = GUIDE_DIR) -> dict[str, list[dict]]:
    """`{page-filename: [block, ...]}` for every `.md` page in the guide."""
    out: dict[str, list[dict]] = {}
    for entry in sorted(os.listdir(guide_dir)):
        if entry.endswith(".md"):
            with open(os.path.join(guide_dir, entry), encoding="utf-8") as fh:
                out[entry] = blocks(fh.read())
    return out


# ---------------------------------------------------------------------------
# Masking: inert text, in ONE pass
# ---------------------------------------------------------------------------
#
# ONE pass rather than strings-then-comments, because the two orders each get
# a case wrong: comments-first eats a `;` inside a string literal, and
# strings-first lets a lone `"` inside a `;;` comment open a phantom string
# that swallows the rest of the file.  A single state machine has neither.
#
# FOUR KINDS OF INERT TEXT, not two.  Strings and `;` comments were the
# original pair; character literals and `#_` reader discards joined them in
# rf2-vz6dg's second pass, and each is here for a measured reason rather than
# for completeness:
#
#   `\(`, `\;` and `\"` are VALUES.  Reading one as a delimiter, a comment or
#   a quote is how the delimiter matcher below loses its place — and the
#   matcher is what tells an `ns` form's `:require` clause from a lookalike
#   vector somewhere else in the block.
#
#   `#_form` is text the READER throws away, so a discarded `:require` vector
#   binds nothing and a discarded `::alias/name` uses nothing.  Leaving it
#   visible was wrong in BOTH directions at once: the first made R5 green on a
#   sample that does not compile, the second made it red on one that does.
#
# The one pass runs over the JOINED text rather than line by line, because a
# `#_` form does not stop at the end of a line.  Blanking never touches a
# newline, so the line count and every line's length survive it.

_CLOSE_OF = {"(": ")", "[": "]", "{": "}"}

# Where a plain token ends.  A `#_` eliding one runs to the first of these.
_TOKEN_STOP = frozenset(' \t\r\n,;"\'`~@()[]{}')


def _string_end(text: str, i: int) -> int:
    """Index just past the `"` closing the string opening at `i` (or the end)."""
    n = len(text)
    k = i + 1
    while k < n:
        if text[k] == "\\":
            k += 2
            continue
        if text[k] == '"':
            return k + 1
        k += 1
    return n


def _char_literal_end(text: str, i: int) -> int:
    """Index just past the character literal opening at the `\\` at `i`.

    `\\a`, `\\newline` and `\\u00a0` are one token; `\\(` and `\\"` are one
    character.  Outside a string a backslash can begin nothing else.
    """
    n = len(text)
    k = i + 1
    if k < n and text[k].isalnum():
        while k < n and text[k].isalnum():
            k += 1
        return k
    return min(k + 1, n)


def _balanced_end(text: str, i: int) -> int | None:
    """Index just past the delimited form opening at `i`, or None if it never
    closes.

    Strings, `;` comments and character literals are skipped WHOLE, so a `)`
    inside one closes nothing.  This is a delimiter matcher and deliberately
    nothing more — it never reads a symbol, resolves a name or evaluates a
    form.
    """
    n = len(text)
    stack: list[str] = []
    k = i
    while k < n:
        c = text[k]
        if c == '"':
            k = _string_end(text, k)
            continue
        if c == ";":
            e = text.find("\n", k)
            k = n if e < 0 else e
            continue
        if c == "\\":
            k = _char_literal_end(text, k)
            continue
        if c in _CLOSE_OF:
            stack.append(_CLOSE_OF[c])
            k += 1
            continue
        if c in ")]}":
            if not stack or stack[-1] != c:
                return None
            stack.pop()
            k += 1
            if not stack:
                return k
            continue
        k += 1
    return None


def _discard_end(text: str, i: int) -> tuple[int, str | None]:
    """Where the `#_` at `i` stops eliding — and a REFUSAL when that is unknown.

    `#_` elides the next FORM, and forms nest, so bounding one in general is a
    reader's job rather than a matcher's.  Three shapes CAN be bounded exactly:
    a delimited form, a string, and a plain token.  Everything else — `#_#_`,
    `#_^:meta`, `#_#(…)`, `#_'x` — is refused rather than guessed at, because
    a guess is wrong in both directions (see [[r6_unreadable_discard]]).
    """
    n = len(text)
    j = i + 2
    while j < n and (text[j].isspace() or text[j] == ","):
        j += 1
    if j >= n:
        return i + 2, "`#_` with no form after it"
    c = text[j]
    if c in _CLOSE_OF:
        end = _balanced_end(text, j)
        if end is None:
            return i + 2, f"`#_{c}…` whose `{_CLOSE_OF[c]}` never arrives"
        return end, None
    if c == '"':
        return _string_end(text, j), None
    if c not in _TOKEN_STOP and c not in "#^\\":
        k = j
        while k < n and text[k] not in _TOKEN_STOP:
            k += 1
        return k, None
    return i + 2, f"`#_` followed by `{c}`"


def _mask_scan(lines: list[str]) -> tuple[list[str], list[str]]:
    """Masked lines, and the reader discards this gate will not guess at."""
    if not lines:
        return [], []
    text = "\n".join(lines)
    out = list(text)
    refusals: list[str] = []
    n = len(text)

    def blank(a: int, b: int) -> None:
        for k in range(max(a, 0), min(b, n)):
            if out[k] != "\n":
                out[k] = " "

    i = 0
    while i < n:
        c = text[i]
        if c == '"':
            end = _string_end(text, i)
            blank(i + 1, end - 1 if text[end - 1:end] == '"' else end)
            i = end
            continue
        if c == ";":
            e = text.find("\n", i)
            e = n if e < 0 else e
            blank(i, e)
            i = e
            continue
        if c == "\\":
            end = _char_literal_end(text, i)
            blank(i, end)
            i = end
            continue
        if c == "#" and text[i + 1:i + 2] == "_":
            end, refusal = _discard_end(text, i)
            if refusal is not None:
                refusals.append(refusal)
            blank(i, end)
            i = max(end, i + 2)
            continue
        i += 1
    return "".join(out).split("\n"), refusals


def mask(lines: list[str]) -> list[str]:
    """Blank every kind of inert text, length-preserving and line-preserving."""
    return _mask_scan(lines)[0]


def discard_refusals(lines: list[str]) -> list[str]:
    """The `#_` discards in `lines` whose extent this gate refuses to guess."""
    return _mask_scan(lines)[1]


# ---------------------------------------------------------------------------
# Reading the guide's own aliases, and the door's own names
# ---------------------------------------------------------------------------

# Which namespaces R2 is about.  The alias itself is read from the guide's own
# `ns` forms rather than listed here, so the alias set cannot drift from the
# pages.
_HICASSO_NS_RE = re.compile(r"re-frame\.hicasso[a-z.-]*")

# A namespace-qualified symbol in code position.  The lookbehind is what keeps
# `::h/value` out: the marker keywords are auto-resolved keywords, not vars,
# and the door's docstring is explicit that they need no export.
_QUALIFIED_RE = re.compile(
    r"(?<![:\w/.*+!?<>='-])([a-zA-Z][\w.-]*)/([a-zA-Z*+!?<>=_-][\w*+!?<>=_.'-]*)"
)

# `(def…)`, `(defn…)`, `(defmacro…)`, and the namespace-qualified defining
# macros a module uses on itself — `(h/defview buffered-field …)` mints
# `forms/buffered-field`, and reading only bare `def*` heads would miss it and
# report a verb the guide correctly teaches as missing.
_DEF_RE = re.compile(
    r"\((?:[a-zA-Z0-9.*+!?<>=_-]+/)?(def[a-z-]*)\s+"
    r"(?:\^\{[^{}]*\}\s*|\^:[\w-]+\s*|\^[\w.]+\s*)*"
    r"([a-zA-Z*+!?<>=_-][\w*+!?<>=_.'-]*)"
)


def namespace_source(ns: str) -> str | None:
    """The file backing `ns`, or None when the namespace does not exist."""
    rel = ns.replace("-", "_").replace(".", os.sep)
    for root in SOURCE_ROOTS:
        for ext in SOURCE_EXTS:
            candidate = os.path.join(root, rel + ext)
            if os.path.isfile(candidate):
                return candidate
    return None


def defined_names(path: str) -> set[str]:
    """Every simple name the file at `path` defines at any `def*` head."""
    with open(path, encoding="utf-8") as fh:
        text = "\n".join(mask(fh.read().replace("\r\n", "\n").split("\n")))
    return {name for _head, name in _DEF_RE.findall(text)}


# Every `[some.namespace :as alias]`, hicasso or not.  R5 needs the WHOLE
# require vocabulary — the cookbook's two defects were `re-frame.core` and
# `my.app.subs`, and R2's own `re-frame.hicasso*` set is a filter over this
# one rather than a second reader.
_ANY_REQUIRE_RE = re.compile(r"\[([a-zA-Z][\w.-]*)\s+:as\s+([a-zA-Z][\w.-]*)\]")

# The head of an `(ns …)` form, and the `:require` clauses inside one.  These
# are only ever matched at a position [[_top_level_ns]] and [[ns_bindings]]
# have already established, never searched for loose in a body.
_NS_FORM_RE = re.compile(r"\(ns\s+[a-zA-Z]")
_REQUIRE_CLAUSE_RE = re.compile(r"\(:require(?:-macros)?(?![\w-])")


def _top_level_ns(code: str) -> int | None:
    """Index of the `(` opening the first TOP-LEVEL `(ns …)` form, or None.

    `code` must already be [[mask]]ed.  Depth is the whole point: an `ns` form
    nested inside anything — `(comment (ns …))`, a quoted datum — is not the
    block's `ns` form, and a block that opens none declares no compilation
    unit for R5 to hold it to.
    """
    n = len(code)
    depth = 0
    i = 0
    while i < n:
        c = code[i]
        if c == '"':
            i = _string_end(code, i)
            continue
        if c == "\\":
            i = _char_literal_end(code, i)
            continue
        if c in _CLOSE_OF:
            if depth == 0 and c == "(" and _NS_FORM_RE.match(code, i):
                return i
            depth += 1
            i += 1
            continue
        if c in ")]}":
            depth = max(0, depth - 1)
            i += 1
            continue
        i += 1
    return None


def ns_bindings(code: str) -> dict[str, str] | None:
    """`{alias: namespace}` the block's own `ns` form BINDS, or None.

    `code` must already be [[mask]]ed.  `None` means the block opens no
    top-level `ns` form; an EMPTY dict does not — `(ns my.app.views)` is a
    compilation unit that binds nothing, so every alias it uses is unbound.

    ONLY the `(:require …)` and `(:require-macros …)` clauses inside the form
    are read, because that is the only place `[ns :as alias]` is a BINDING.
    Elsewhere those characters are a datum, and the difference is not
    cosmetic: `(def documented-shape '[re-frame.core :as rf])` and
    `(comment [re-frame.core :as rf])` each bind nothing at all, yet scanning
    the whole body for the vector took both for the very require the sample
    was missing — the second false green rf2-vz6dg recorded, after the first
    was repaired by masking alone.  Masking answers "is this text inert"; this
    answers the question masking cannot, "is this live text a BINDING".
    """
    start = _top_level_ns(code)
    if start is None:
        return None
    end = _balanced_end(code, start)
    form = code[start:len(code) if end is None else end]
    out: dict[str, str] = {}
    for m in _REQUIRE_CLAUSE_RE.finditer(form):
        stop = _balanced_end(form, m.start())
        clause = form[m.start():len(form) if stop is None else stop]
        for ns, alias in _ANY_REQUIRE_RE.findall(clause):
            out[alias] = ns
    return out


def bound_aliases(corp: dict[str, list[dict]]) -> dict[str, str]:
    """Every alias an `ns` form ANYWHERE in the guide actually binds.

    Derived, never listed, and now derived from BINDINGS rather than from
    text that merely looks like one.  A token the guide never requires —
    `js/`, `goog/` — stays out of scope by construction rather than by a
    filter someone has to maintain, which is the property R2 and R5 are both
    built on; what changed in rf2-vz6dg's second pass is that a `;; [my.fake.ns
    :as sbus]` in prose no longer counts as requiring anything.  That one
    mattered in the OTHER direction: it made `sbus` look guide-bound, and a
    deliberately-ignored ambiguous `sbus/row-count` symbol became an R5 error.
    """
    out: dict[str, str] = {}
    for _page, blks in sorted(corp.items()):
        for block in blks:
            if block["lang"] not in CLOJURE_LANGS:
                continue
            binds = ns_bindings("\n".join(mask(block["body"])))
            if binds:
                out.update(binds)
    return out


def guide_aliases(corp: dict[str, list[dict]]) -> dict[str, str]:
    """`{alias: namespace}` for the `re-frame.hicasso*` half — R2's map.

    POOLED ACROSS THE GUIDE ON PURPOSE — see R2.  This map answers "which
    namespace does this alias mean", which no page boundary changes.  It is
    NOT a statement that the alias is in scope anywhere in particular, and
    reading it as one is the hole rf2-vz6dg recorded; [[section_scopes]] is
    what knows where an alias is actually bound.
    """
    return {alias: ns for alias, ns in bound_aliases(corp).items()
            if _HICASSO_NS_RE.fullmatch(ns)}

# `::alias/name`.  The lookbehind rejects `:::`; a SINGLE-colon `:alias/name`
# is a plain qualified keyword that needs no alias and is deliberately not
# matched.  R2 reads this form for nothing — an auto-resolved keyword is not a
# var use — and R5 reads it for its ALIAS only, which is what has to be bound
# at read time.
_AUTO_KEYWORD_RE = re.compile(
    r"(?<!:)::([a-zA-Z][\w.-]*)/([a-zA-Z*+!?<>=_-][\w*+!?<>=_.'-]*)"
)

_HEADING_RE = re.compile(r"^#{1,6}\s")


def section_scopes(text: str) -> list[dict[str, str] | None]:
    """The alias scope in force at each fenced block, in document order.

    One entry per block, aligned with [[blocks]] so a caller can zip the two.
    `None` means no `(ns …)` form governs the block — it is an illustrative
    fragment that declares nothing, so R5 asks nothing of it.

    A markdown heading ENDS a scope.  That is the whole scoping model, and it
    is measured rather than assumed: inheriting across headings instead
    reports 21 sites on a corpus that is correct, because a page regularly
    finishes with one namespace and begins illustrating another under the
    next heading without a fresh `ns` form.

    THE SCOPE IS WHAT THE `ns` FORM BINDS, and both halves of that sentence
    took a repair to become true (rf2-vz6dg, in two passes).

    First, the form and its requires are read from [[mask]]ed text, never the
    raw body: a `;; [re-frame.core :as rf]` the author commented out is
    precisely the require the sample is MISSING, and reading it as a binding
    let the rule certify a block that does not compile.  The same masking is
    why a commented-out `(ns …)` opens no scope at all, and why a
    `#_[re-frame.core :as rf]` inside a live `:require` binds nothing.

    Second — and masking cannot reach this one — the requires are read from
    the `ns` form's own `:require` clauses via [[ns_bindings]], not from
    wherever in the block the vector happens to sit.  Live text is not
    thereby a binding: `(def documented-shape '[re-frame.core :as rf])` is a
    datum and `(comment [re-frame.core :as rf])` is a no-op, and scanning the
    masked BODY still took each for the missing require.
    """
    lines = text.replace("\r\n", "\n").split("\n")
    out: list[dict[str, str] | None] = []
    scope: dict[str, str] | None = None
    i = 0
    while i < len(lines):
        if _HEADING_RE.match(lines[i]):
            scope = None
            i += 1
            continue
        if not lines[i].startswith("```"):
            i += 1
            continue
        lang = lines[i][3:].strip() or "none"
        j = i + 1
        while j < len(lines) and not lines[j].startswith("```"):
            j += 1
        body = lines[i + 1:j]
        if lang in CLOJURE_LANGS:
            binds = ns_bindings("\n".join(mask(body)))
            if binds is not None:
                scope = binds
        out.append(scope)
        i = j + 1
    return out


def used_aliases(body: list[str]) -> set[tuple[str, str, str]]:
    """`{(alias, name, kind)}` for every alias a block's code names.

    Two kinds, because the guide's two recorded defects were one of each and
    the failure they cause differs: a `var` use of an unbound alias is an
    undeclared var at compile time, and a `keyword` use of one is a READER
    error — the sample never parses.
    """
    out: set[tuple[str, str, str]] = set()
    for line in mask(body):
        for alias, verb in _QUALIFIED_RE.findall(line):
            out.add((alias, verb, "var"))
        for alias, name in _AUTO_KEYWORD_RE.findall(line):
            out.add((alias, name, "keyword"))
    return out


def qualified_uses(corp: dict[str, list[dict]], aliases: dict[str, str]) -> dict:
    """`{(ns, verb): [site, ...]}` for every hicasso alias used in a code block."""
    uses: dict[tuple[str, str], list[str]] = {}
    for page, blks in sorted(corp.items()):
        for block in blks:
            if block["lang"] not in CLOJURE_LANGS:
                continue
            for line in mask(block["body"]):
                for alias, verb in _QUALIFIED_RE.findall(line):
                    ns = aliases.get(alias)
                    if ns is None:
                        continue
                    uses.setdefault((ns, verb), []).append(f"{page} block {block['n']}")
    return uses


# ---------------------------------------------------------------------------
# The rules, each pure so --self-test can feed it synthetic input
# ---------------------------------------------------------------------------


def r1_pin_drift(corp: dict[str, list[dict]], roster: dict) -> list[str]:
    """R1 — the roster and the corpus agree, page by page and block by block."""
    problems: list[str] = []
    for page in sorted(set(corp) | set(roster)):
        found = corp.get(page)
        rows = roster.get(page)
        if found is None:
            problems.append(
                f"{page} — the roster names a page that is not in the guide"
            )
            continue
        if rows is None:
            problems.append(f"{page} — a guide page with no roster entry")
            continue
        if len(found) != len(rows):
            problems.append(
                f"{page} — the page has {len(found)} fenced blocks; "
                f"the roster has {len(rows)} rows"
            )
        for block, row in zip(found, rows):
            if block["digest"] != row[1]:
                problems.append(
                    f"{page} block {block['n']} has changed — paste "
                    f'({block["n"]}, "{block["digest"]}") (was "{row[1]}"); '
                    f"first line: {block['first_line']!r}"
                )
        for block in found[len(rows):]:
            problems.append(
                f"{page} block {block['n']} has no roster row — "
                f"first line: {block['first_line']!r}"
            )
        for row in rows[len(found):]:
            problems.append(
                f"{page} — roster row for a block that is gone: "
                f'({row[0]}, "{row[1]}")'
            )
    return problems


def r2_unresolved(
    uses: dict,
    exports: dict[str, set[str] | None],
    divergences: dict | None = None,
    absent: dict | None = None,
) -> list[str]:
    """R2 — every hicasso verb the guide names is defined, or has a ledger row.

    The two ledgers are PARAMETERS defaulting to the live tables, for the
    reason `check_facade_inventory.py` gives about its own two lists: a
    self-test that drives the live table stops testing the rule the moment
    the table empties, which is exactly when the rule is least exercised
    and most worth keeping honest.  rf2-b6jkj emptied `ABSENT_NAMESPACES`
    and the absent-namespace arm went from *passing* to *unrunnable* in
    the same commit; parameterising is what keeps both arms drivable from
    fixtures forever.
    """
    divergences = DIVERGENCES if divergences is None else divergences
    absent = ABSENT_NAMESPACES if absent is None else absent
    problems: list[str] = []
    for (ns, verb), sites in sorted(uses.items()):
        if (ns, verb) in divergences:
            continue
        names = exports.get(ns)
        if names is None:
            if ns in absent:
                continue
            problems.append(
                f"{ns}/{verb} — the guide requires a namespace with no source "
                f"({len(sites)} site(s), e.g. {sites[0]}); add it to "
                f"ABSENT_NAMESPACES only if the gap is declared in the guide's "
                f"Status block"
            )
            continue
        if verb not in names:
            problems.append(
                f"{ns}/{verb} — named by the guide at {len(sites)} site(s) "
                f"(e.g. {sites[0]}) but defined nowhere in {ns}"
            )
    return problems


def r3_stale_ledger(
    exports: dict[str, set[str] | None],
    divergences: dict | None = None,
    absent: dict | None = None,
) -> list[str]:
    """R3 — every declared divergence still describes reality.

    The rule that catches a gap CLOSING, which is the failure prose, a digest
    and a fixture all miss alike.  Both ledgers are parameters for
    [[r2_unresolved]]'s reason.
    """
    divergences = DIVERGENCES if divergences is None else divergences
    absent = ABSENT_NAMESPACES if absent is None else absent
    problems: list[str] = []
    for (ns, verb), (exported_as, note) in sorted(divergences.items()):
        names = exports.get(ns)
        if names is None:
            problems.append(
                f"DIVERGENCES[{ns}/{verb}] — the namespace itself has no source; "
                f"the row cannot be true"
            )
            continue
        if verb in names:
            problems.append(
                f"DIVERGENCES[{ns}/{verb}] IS STALE — `{verb}` is now defined in "
                f"{ns}, so the gap has CLOSED. Drop this row and let the guide "
                f"teach the real spelling; the reason it recorded was: {note}"
            )
        if exported_as is not None and exported_as not in names:
            problems.append(
                f"DIVERGENCES[{ns}/{verb}] IS STALE — it says {ns} exports "
                f"`{exported_as}` instead, and {ns} defines no such name"
            )
    for ns, note in sorted(absent.items()):
        if namespace_source(ns) is not None:
            problems.append(
                f"ABSENT_NAMESPACES[{ns}] IS STALE — the namespace now exists, so "
                f"the chapter written against it must be checked against the real "
                f"contract. The reason it recorded was: {note}"
            )
    return problems


# The Status block's bullets, as the page writes them: `- **`h/fn`** …`.  The
# bold-backticked lead is the whole shape this rule reads — a bullet may be
# reworded freely, but it cannot stop naming the door it is about without
# ceasing to be that bullet.  Backticked names elsewhere in the block (the
# closing paragraph's roster of what IS exported) are deliberately out of
# reach: they are not claims of divergence.
_STATUS_BULLET_RE = re.compile(r"^\s*-\s*\*\*`([^`]+)`\*\*", re.MULTILINE)

STATUS_PAGE = "index.md"


def status_block(pages: dict[str, str], page: str = STATUS_PAGE) -> str | None:
    """The `## Status` section of the guide's index page, heading excluded."""
    text = pages.get(page)
    if text is None:
        return None
    m = re.search(r"^##\s+Status\s*$", text, re.MULTILINE)
    if not m:
        return None
    rest = text[m.end():]
    nxt = re.search(r"^##\s+", rest, re.MULTILINE)
    return rest[: nxt.start()] if nxt else rest


def r4_status_block_declares(
    pages: dict[str, str],
    aliases: dict[str, str],
    divergences: dict | None = None,
    absent: dict | None = None,
) -> list[str]:
    """R4 — the ledger and the Status block declare the SAME gaps.

    R3 catches a gap closing in the code.  This catches the other half of the
    same failure: the PAGE going stale against the ledger, in either
    direction.  A row with no bullet is a gap the reader is never told about;
    a bullet with no row is the guide claiming a door is missing that ships
    today, which is how `h/hydrate!` and `re-frame.hicasso.server` went on
    being described as absent for as long as it took a human to notice
    (rf2-hni7l).  R1, R2 and R3 were all green throughout, because none of
    them reads prose.

    The rule the file's own ledger comment already asserted — a row is
    "admissible only for a spelling the guide's own Status block already
    declares to the reader" — enforced rather than left to a reviewer's eye.
    Ledgers are parameters for [[r2_unresolved]]'s reason.
    """
    divergences = DIVERGENCES if divergences is None else divergences
    absent = ABSENT_NAMESPACES if absent is None else absent
    block = status_block(pages)
    if block is None:
        return [
            f"{STATUS_PAGE} has no `## Status` section — the ledger below has "
            f"nowhere to be declared to the reader"
        ]

    by_ns: dict[str, list[str]] = {}
    for alias, ns in aliases.items():
        by_ns.setdefault(ns, []).append(alias)

    declared = set(_STATUS_BULLET_RE.findall(block))

    # What the ledger says the block must declare, and the spelling a reader
    # would look for: the guide's own alias, falling back to the namespace.
    required: dict[str, str] = {}
    for ns, verb in divergences:
        spellings = [f"{a}/{verb}" for a in sorted(by_ns.get(ns, []))]
        spellings.append(f"{ns}/{verb}")
        hit = next((s for s in spellings if s in declared), None)
        required[hit or spellings[0]] = f"DIVERGENCES[{ns}/{verb}]"
    for ns in absent:
        required[ns] = f"ABSENT_NAMESPACES[{ns}]"

    problems: list[str] = []
    for spelling, row in sorted(required.items()):
        if spelling not in declared:
            problems.append(
                f"{row} is NOT declared in {STATUS_PAGE}'s Status block — add a "
                f"bullet leading `**`{spelling}`**`, or drop the row. A ledger "
                f"row the page does not carry is a gap the reader never hears "
                f"about"
            )
    for spelling in sorted(declared - set(required)):
        problems.append(
            f"{STATUS_PAGE}'s Status block declares `{spelling}` as a "
            f"divergence and NO ledger row carries it — either the gap has "
            f"CLOSED and the bullet must go (check the door before you "
            f"believe the page), or the row was dropped without the page "
            f"following"
        )
    return problems


def r5_unbound_alias(
    pages: dict[str, str],
    corp: dict[str, list[dict]],
    vocabulary: dict[str, str],
) -> list[str]:
    """R5 — a block's own section binds the aliases the block uses.

    The rule R1 and R2 could not between them express.  R1 pins BYTES, so it
    certifies "nobody edited this", not "this compiles"; R2 pools aliases
    across the guide, so a block missing its own `:require` still resolved
    against some other page's.  Both were green on a cookbook recipe that
    could not be read, let alone run (rf2-vz6dg).

    A block governed by no `ns` form is not checked.  That is not a gap: such
    a block declares no compilation unit, so there is nothing it could be
    failing to declare.  The rule speaks exactly where the guide has made a
    claim about what a namespace contains.

    THE TWO KINDS ARE HELD TO DIFFERENT STANDARDS, and `vocabulary` gates the
    var arm ONLY — see the module docstring for why.  A `::alias/name` keyword
    is checked unconditionally, because that spelling has exactly one meaning
    and an alias bound nowhere in the guide is the reader failure this rule
    exists to catch rather than an out-of-scope qualifier.
    """
    problems: list[str] = []
    for page in sorted(corp):
        scopes = section_scopes(pages[page])
        for block, scope in zip(corp[page], scopes):
            if block["lang"] not in CLOJURE_LANGS or scope is None:
                continue
            for alias, name, kind in sorted(used_aliases(block["body"])):
                if alias in scope:
                    continue
                if kind == "keyword":
                    detail = (
                        "an auto-resolved keyword needs its alias AT READ "
                        "TIME, so this block does not parse"
                    )
                elif alias in vocabulary:
                    detail = "an undeclared var"
                else:
                    continue
                # A keyword's alias can be one the guide binds NOWHERE — the
                # typo case — so the namespace it should name is unknown.
                wanted = (
                    f"`{vocabulary[alias]}`" if alias in vocabulary
                    else f"any namespace under the alias `{alias}`"
                )
                problems.append(
                    f"{page} block {block['n']} uses `{alias}/{name}` but "
                    f"the `ns` form governing its section does not require "
                    f"{wanted} — {detail}. Add the alias to "
                    f"that `ns` form, or move the block under one that has "
                    f"it"
                )
    return problems


def r6_unreadable_discard(corp: dict[str, list[dict]]) -> list[str]:
    """R6 — every Clojure block is in the subset of Clojure this gate reads.

    The one place the gate FAILS CLOSED, and the reason it can stay a gate
    rather than becoming a compiler lane.

    `#_` elides the next FORM and forms nest, so bounding one in the general
    case is a reader's job.  [[_discard_end]] bounds the three shapes it can
    bound exactly — a delimited form, a string, a plain token — and this rule
    refuses the rest, because a guess is wrong in BOTH directions at once: a
    discard read too short leaves inert text binding aliases, and R5 goes
    green on a sample that cannot compile; one read too long swallows live
    code, and R5 goes red on a sample that can.  Neither error is visible
    from the outside, which is precisely the failure this file exists to end.

    Refusing costs the guide nothing it wanted.  `#_#_`, `#_^:meta` and
    `#_#(…)` teach a reader nothing the plainer spelling does not, and a page
    that needs one can say so in prose — where R1 pins it and no rule here
    reads it as code.

    THE CEILING, stated rather than left to be discovered: this reads the
    GUIDE's blocks.  The door's own sources go through the same masker inside
    [[defined_names]], and an unsupported discard there degrades that reader
    silently instead of refusing — it is a `#_`-discarded `def` that would go
    on being counted as exported.  Guide text is what this file governs;
    source text is what a compiler already governs.
    """
    problems: list[str] = []
    for page, blks in sorted(corp.items()):
        for block in blks:
            if block["lang"] not in CLOJURE_LANGS:
                continue
            for refusal in discard_refusals(block["body"]):
                problems.append(
                    f"{page} block {block['n']} carries {refusal} — this gate "
                    f"reads a reader discard only where it elides a delimited "
                    f"form, a string or a plain token, and REFUSES the rest "
                    f"rather than guess its extent. Respell the sample, or "
                    f"delete the text instead of discarding it"
                )
    return problems


# ---------------------------------------------------------------------------
# The roster
# ---------------------------------------------------------------------------
#
# `page -> [(block-ordinal, digest), ...]`, ordinals in document order.
# Regenerate with `--emit-roster` after checking the change is one you meant.

ROSTER = {
    "00-installation.md": [
        (1, "58a74f5bab9d"), (2, "6f09d655927e"), (3, "08dacefb6313"),
        (4, "3a2dc0ae21eb"), (5, "550f961b9370"), (6, "34a2229c1f65"),
        (7, "2d389a8f20ce"), (8, "0bfa30966c57"), (9, "43cfb5c82dc6"),
        (10, "e273338344de"), (11, "caa27172a376"), (12, "6dde20a4d272"),
        (13, "d85146b3bb90"),
    ],
    "01-getting-started.md": [
        (1, "dc6634f5c3ae"), (2, "603c4568fa1d"),
    ],
    "02-views-and-reads.md": [
        (1, "bb29b99233c9"), (2, "118c8d71e8ee"), (3, "f0803664bbc5"),
        (4, "02942a9b2322"), (5, "28a40a9664f3"), (6, "2e152ea0d775"),
        (7, "5c9329c7d0f9"), (8, "980246a34b41"), (9, "0bbad61e30ab"),
        (10, "10631db95cb8"),
    ],
    "03-events-as-data.md": [
        (1, "2b67f773aebd"), (2, "42648249c10c"), (3, "98efe91f23d1"),
        (4, "e1694d6b7447"), (5, "b8fb07ec9bcd"), (6, "38e590175d58"),
        (7, "c5607b0a00dc"), (8, "62e15de47d92"), (9, "1a4b943717b9"),
        (10, "c39ffbfc89d3"), (11, "adac13035c37"), (12, "536d5a306c8a"),
    ],
    "04-controlled-inputs.md": [
        (1, "ba89d487d7cf"), (2, "8f7dd4e07635"), (3, "6e98aa1ec9f1"),
        (4, "96adde92a751"), (5, "f7ba3cfb6c4b"), (6, "65663fbffed1"),
        (7, "7ea3a9b445a8"),
    ],
    "05-forms.md": [
        (1, "bc61f2ceccf2"), (2, "449e1e243001"), (3, "b90870f173ed"),
        (4, "9358bcf8bd4f"), (5, "0bb57c2152a3"), (6, "64e33597203a"),
        (7, "c81e9b22d066"), (8, "51950e9321d2"), (9, "1dbe40ed185e"),
    ],
    "06-lists-and-collections.md": [
        (1, "e3ad40798a1d"), (2, "7a1a7bc358f3"), (3, "440e99a73966"),
        (4, "97737ce3a063"), (5, "1b9ea4bc1856"),
    ],
    "07-routing-and-navigation.md": [
        (1, "2b75aefcc8ef"), (2, "a5ec3aba1114"), (3, "848ba8fe0ef2"),
        (4, "81896520facc"), (5, "bbacc1666deb"), (6, "d3d9f3f439be"),
        (7, "d856df91e176"), (8, "8cb66633b54d"), (9, "77dee5e57403"),
        (10, "880064f09512"), (11, "7e562cf63a90"), (12, "ed9e07f50ef6"),
    ],
    "08-async-resources.md": [
        (1, "9f7ad2ec9a3e"), (2, "7debf048a372"), (3, "d6e6f80bd973"),
        (4, "f42ea0cae625"), (5, "ca7d1ab52809"), (6, "5014db84d87c"),
        (7, "9dd804953d1a"), (8, "c5c918c43613"),
    ],
    "09-interop.md": [
        (1, "f9c121ead9b4"), (2, "add77099a3b1"), (3, "4d24d4fa878e"),
        (4, "6910bfcdcf02"), (5, "0e7d4ab43d08"), (6, "5e464064f747"),
        (7, "54e5bd3cc034"), (8, "65e58252ea6e"), (9, "1b342e21ee48"),
        (10, "041c0256d5f7"), (11, "d3b8639b64a6"), (12, "60b51664545b"),
    ],
    "10-native-tier.md": [
        (1, "59b22de7d68b"), (2, "2af314d1e90f"), (3, "96671d4cc41d"),
        (4, "959bfe0e04a7"), (5, "9509a6e64c37"), (6, "bba8cd7f8cc1"),
        (7, "47c073f5d3d5"),
    ],
    "11-ephemeral-state.md": [
        (1, "dd4ba49feb87"), (2, "5da9310b7de0"), (3, "f897b4819048"),
        (4, "11d5492b72af"),
    ],
    "12-motion-and-presence.md": [
        (1, "b9e054bd1679"), (2, "1c2d32590c08"), (3, "4e4a36dda625"),
        (4, "bc4f62b03373"), (5, "61230129cca2"),
    ],
    "13-overlays-and-focus.md": [
        (1, "e3b14e8ae74c"), (2, "a54a7e56f163"), (3, "848856bedd5a"),
        (4, "6a2ed6a42644"), (5, "0559b75a96e4"),
    ],
    "14-theming-and-i18n.md": [
        (1, "5fd55032ff3b"), (2, "ee5ec2973262"), (3, "802096bcbb9b"),
        (4, "f7219b24410c"), (5, "cf5e46f8a0d7"), (6, "26791eb8c9e7"),
        (7, "f009629a1f06"), (8, "82a5a60462ff"), (9, "8fe650df969f"),
    ],
    "15-testing.md": [
        (1, "828de9540735"), (2, "31c95b74c50e"), (3, "431a804223b2"),
        (4, "735cb1ae09d7"), (5, "09f62ec4bf3a"), (6, "2659a5859904"),
        (7, "0b0c815f01b6"), (8, "c5f8dca505dc"), (9, "e91705fcabfe"),
        (10, "9de44c266a7e"), (11, "72766d8f98a6"), (12, "f77d451a99f3"),
        (13, "82018d567eb0"), (14, "ce4d5c6d9fd1"),
    ],
    "16-diagnostics.md": [
        (1, "4689d0951345"), (2, "3a75ae9a1f41"), (3, "5f42d58c26e7"),
        (4, "23372302c2df"),
    ],
    "17-errors.md": [
        (1, "be9e41cb66f8"), (2, "93d6d9a08407"), (3, "e93637c26b57"),
        (4, "e9fb53513bac"), (5, "8d45738ab32b"), (6, "56e9871ae834"),
        (7, "3b39539af691"), (8, "0d4ab665b51a"),
    ],
    "18-ssr-and-hydration.md": [
        (1, "644f5382ad39"), (2, "89f33394d0da"), (3, "fe1701a48eaf"),
        (4, "e5e0a9d5d054"), (5, "649b1aa42632"), (6, "0585e4e19782"),
        (7, "5a593e4480a6"), (8, "501bae85d0b4"), (9, "20c7b9dda504"),
    ],
    "19-performance.md": [
        (1, "420670fe4aa0"), (2, "25c1b982e2c2"), (3, "0d74284379c5"),
        (4, "ed68a56c031f"),
    ],
    "20-migration-from-reagent.md": [
        (1, "05266e238486"), (2, "725a850c468a"), (3, "0cd3efd6ab04"),
        (4, "a379f2d0d30b"), (5, "544a7421da01"), (6, "5eb83e1674c2"),
    ],
    "21-code-splitting.md": [
        (1, "9270a7a2b630"), (2, "29b87f8aa55f"), (3, "3c63a1bae370"),
        (4, "b60015abcca7"), (5, "999ae000dc6c"), (6, "494c1fa8453f"),
    ],
    "22-accessibility.md": [
        (1, "b173bea96195"), (2, "8460777aca5a"), (3, "e8f31ee17a6d"),
        (4, "1307fa5e3992"), (5, "d620a0798605"), (6, "43c9c8987e1c"),
    ],
    "api-reference.md": [
        (1, "3247d9b570b0"), (2, "a13e85fcc198"), (3, "44af83529233"),
        (4, "f1999f8fc8b7"), (5, "562a97fb9604"), (6, "4a251af566d5"),
        (7, "2abb72bfc986"), (8, "d7264e522936"), (9, "b075dcf88e03"),
        (10, "8e66b02d3bb1"), (11, "eed6eb60376e"), (12, "a437409962c7"),
        (13, "b1d234e40ff4"), (14, "c107f9bb79f3"),
    ],
    "cookbook.md": [
        (1, "803b3d84398e"), (2, "62a75c1aa78f"), (3, "bbe7b860ee1b"),
        (4, "9b8d99593eec"), (5, "6a0159a9786f"), (6, "4dc57a743a45"),
        (7, "e747ae84531f"), (8, "df9e1779abeb"), (9, "2aa80a5b4f1a"),
        (10, "3fd40e3b7179"), (11, "5e06ff1b903c"), (12, "0f16159d2a56"),
        (13, "fafe7bd0f47c"), (14, "daef6339a740"),
    ],
    "escape-ladder.md": [],
    "glossary.md": [
        (1, "8e41503852f0"), (2, "a8c48ad15a44"), (3, "293a430df1b0"),
        (4, "3d0eeb7d7630"), (5, "00ef4677d93c"), (6, "1fce61ddf3b9"),
        (7, "cb82a84e9e1f"), (8, "875347e0e1c9"), (9, "a17475d88d3b"),
        (10, "d736d0f8d2dd"), (11, "fe9a46c664ad"), (12, "8c5dd00e1013"),
        (13, "0fa30f465faf"), (14, "58363a4b87fd"), (15, "3b828e2ca15e"),
        (16, "4689d0951345"), (17, "e3bd70ac860a"),
    ],
    "index.md": [],
    "troubleshooting.md": [
        (1, "89520c8d61d9"),
    ],
}


# ---------------------------------------------------------------------------
# Entry points
# ---------------------------------------------------------------------------


def emit_roster(corp: dict[str, list[dict]]) -> str:
    """Print the roster literal for the corpus as it stands."""
    out = ["ROSTER = {"]
    for page, blks in sorted(corp.items()):
        if not blks:
            out.append(f'    "{page}": [],')
            continue
        out.append(f'    "{page}": [')
        row_strs = [f'({b["n"]}, "{b["digest"]}")' for b in blks]
        for i in range(0, len(row_strs), 3):
            out.append("        " + ", ".join(row_strs[i:i + 3]) + ",")
        out.append("    ],")
    out.append("}")
    return "\n".join(out)


def run_check(verbose: bool = False) -> int:
    pages = {}
    for entry in sorted(os.listdir(GUIDE_DIR)):
        if entry.endswith(".md"):
            with open(os.path.join(GUIDE_DIR, entry), encoding="utf-8") as fh:
                pages[entry] = fh.read()
    corp = corpus()
    aliases = guide_aliases(corp)
    uses = qualified_uses(corp, aliases)

    exports: dict[str, set[str] | None] = {}
    for ns in sorted(set(aliases.values()) | {k[0] for k in DIVERGENCES}):
        src = namespace_source(ns)
        exports[ns] = defined_names(src) if src else None

    vocabulary = bound_aliases(corp)

    failures: list[tuple[str, list[str]]] = [
        ("R1 PINNED", r1_pin_drift(corp, ROSTER)),
        ("R2 RESOLVED", r2_unresolved(uses, exports)),
        ("R3 LEDGER IS LIVE", r3_stale_ledger(exports)),
        ("R4 THE PAGE MATCHES THE LEDGER", r4_status_block_declares(pages, aliases)),
        ("R5 SELF-CONTAINED", r5_unbound_alias(pages, corp, vocabulary)),
        ("R6 READABLE", r6_unreadable_discard(corp)),
    ]

    total_blocks = sum(len(b) for b in corp.values())
    code_blocks = sum(
        1 for b in corp.values() for x in b if x["lang"] in CLOJURE_LANGS
    )
    scoped_blocks = sum(
        1
        for page in corp
        for block, scope in zip(corp[page], section_scopes(pages[page]))
        if block["lang"] in CLOJURE_LANGS and scope is not None
    )

    bad = False
    for rule, problems in failures:
        if problems:
            bad = True
            print(f"\nFAIL {rule}", file=sys.stderr)
            for line in problems:
                print(f"  {line}", file=sys.stderr)

    if bad:
        print(
            "\nRegenerate the roster with --emit-roster once the change is "
            "one you meant.",
            file=sys.stderr,
        )
        return 1

    # WHAT THIS LINE MAY CLAIM is exactly what the rules above enforce, and
    # the gap between the two is what rf2-vz6dg's audit measured twice.  So it
    # names the SOURCE of every scope — the block's own `ns` form, not the
    # guide at large and not text that merely looks like a require — and it
    # states R5's var-arm ceiling as a count rather than implying there is
    # none.  A headline that promises bindings while the code accepts text
    # binding nothing is the same defect as the code's, printed.
    print(
        f"Hicasso guide samples: {total_blocks} fenced blocks pinned across "
        f"{len(corp)} pages ({code_blocks} Clojure), "
        f"{len(uses)} hicasso verb use-sites resolved, "
        f"{scoped_blocks} block(s) checked self-contained against the aliases "
        f"their own `ns` form's `:require` binds "
        f"(every `::alias/name`; var uses against the {len(vocabulary)} "
        f"alias(es) an `ns` form in the guide binds), "
        f"{len(DIVERGENCES)} declared divergence(s) + "
        f"{len(ABSENT_NAMESPACES)} absent namespace(s) still live."
    )
    if verbose:
        for (ns, verb), sites in sorted(uses.items()):
            mark = "  (declared divergence)" if (ns, verb) in DIVERGENCES else ""
            print(f"  {ns}/{verb:24s} {len(sites):3d} site(s){mark}")
    return 0


def self_test() -> int:
    """Drive each rule red against synthetic input.

    A gate that cannot fail is the staleness it was built to end, so every way
    this one is supposed to red is exercised here rather than trusted.
    """
    failures: list[str] = []

    def check(label: str, condition: bool) -> None:
        if not condition:
            failures.append(label)

    # ---- R1 -----------------------------------------------------------
    base_corpus = {
        "x.md": [
            {"n": 1, "digest": "aaaaaaaaaaaa", "first_line": "(h/sub [:x])",
             "lang": "clojure", "body": ["(h/sub [:x])"]},
            {"n": 2, "digest": "bbbbbbbbbbbb", "first_line": ".a{}",
             "lang": "css", "body": [".a{}"]},
        ]
    }
    base_roster = {"x.md": [(1, "aaaaaaaaaaaa"), (2, "bbbbbbbbbbbb")]}

    check("R1 agreement is silence", r1_pin_drift(base_corpus, base_roster) == [])

    edited = {"x.md": [dict(base_corpus["x.md"][0], digest="cccccccccccc"),
                       base_corpus["x.md"][1]]}
    check("R1 catches an EDITED sample",
          any("has changed" in p for p in r1_pin_drift(edited, base_roster)))

    added = {"x.md": base_corpus["x.md"] + [
        {"n": 3, "digest": "dddddddddddd", "first_line": "(h/fn [])",
         "lang": "clojure", "body": ["(h/fn [])"]}]}
    problems = r1_pin_drift(added, base_roster)
    check("R1 catches an ADDED sample", any("no roster row" in p for p in problems))
    check("R1 catches the count mismatch on add",
          any("fenced blocks" in p for p in problems))

    removed = {"x.md": [base_corpus["x.md"][0]]}
    problems = r1_pin_drift(removed, base_roster)
    check("R1 catches a REMOVED sample",
          any("a block that is gone" in p for p in problems))

    check("R1 catches a NEW PAGE",
          any("no roster entry" in p
              for p in r1_pin_drift({**base_corpus, "new.md": []}, base_roster)))
    check("R1 catches a DELETED PAGE",
          any("not in the guide" in p
              for p in r1_pin_drift(base_corpus,
                                    {**base_roster, "gone.md": [(1, "e" * 12)]})))

    # The digest is content-sensitive in the way R1 claims.
    d = lambda s: sha12(normalise([s]))
    check("a moved verb changes the digest", d("(h/sub [:x])") != d("(h/subscribe [:x])"))
    check("an expected value in a comment changes the digest",
          d(";; => ::h/value") != d(";; => ::h/text"))
    check("trailing whitespace does not", d("(h/sub [:x])   ") == d("(h/sub [:x])"))

    # ---- R2 -----------------------------------------------------------
    #
    # Both ledgers are FIXTURES here and never the live tables.  The live
    # `ABSENT_NAMESPACES` is empty since rf2-b6jkj shipped
    # `re-frame.hicasso.server`, and a self-test reading it would have gone
    # from proving the rule to proving nothing, silently -- the same rot
    # `check_facade_inventory.py` parameterises its two lists against.
    fx_div = {("re-frame.hicasso", "fn"): ("hfn", "an open naming decision")}
    fx_abs = {"re-frame.hicasso.server": "not yet built, in this fixture"}
    exports = {"re-frame.hicasso": {"sub", "defview", "hfn", "hframe", "root!"},
               "re-frame.hicasso.server": None}
    r2 = lambda uses, exp=exports: r2_unresolved(uses, exp, fx_div, fx_abs)
    check("R2 silence when every verb resolves",
          r2({("re-frame.hicasso", "sub"): ["x.md block 1"]}) == [])
    check("R2 catches a MOVED verb",
          any("defined nowhere" in p for p in r2(
              {("re-frame.hicasso", "subscribe"): ["x.md block 1"]})))
    check("R2 lets a DECLARED divergence through",
          r2({("re-frame.hicasso", "fn"): ["x.md block 1"]}) == [])
    check("R2 lets a DECLARED absent namespace through",
          r2({("re-frame.hicasso.server", "render"): ["x.md"]}) == [])
    check("R2 catches an UNDECLARED absent namespace",
          any("no source" in p for p in r2(
              {("re-frame.hicasso.ghost", "boo"): ["x.md block 1"]},
              {**exports, "re-frame.hicasso.ghost": None})))

    # ---- R3 -----------------------------------------------------------
    r3 = lambda exp: r3_stale_ledger(exp, fx_div, {})
    live = {"re-frame.hicasso": {"hfn", "hframe", "root!", "sub"}}
    check("R3 silence while every gap is still open", r3(live) == [])
    closed = {"re-frame.hicasso": {"fn", "hfn", "hframe", "root!", "sub"}}
    check("R3 catches a gap that has CLOSED",
          any("IS STALE" in p and "gap has CLOSED" in p for p in r3(closed)))
    renamed = {"re-frame.hicasso": {"hframe", "root!", "sub"}}
    check("R3 catches the replacement itself being renamed",
          any("no such name" in p for p in r3(renamed)))
    # The absent-namespace arm of R3, which the live table can no longer
    # drive: a namespace that EXISTS while a row still claims it does not.
    # This is the rule that red on `re-frame.hicasso.server` landing.
    check("R3 catches an absent-namespace row whose namespace now EXISTS",
          any("IS STALE" in p and "namespace now exists" in p
              for p in r3_stale_ledger(
                  {"re-frame.hicasso": {"hfn", "hframe", "root!", "sub"}},
                  {},
                  {"re-frame.hicasso.server": "this fixture claims it is absent"})))

    # ---- R4 -----------------------------------------------------------
    #
    # Fixtures throughout, for R2's reason. The page text is the real shape:
    # an admonition whose bullets lead with a bold-backticked door.
    fx_aliases = {"h": "re-frame.hicasso"}
    page = (
        "# Guide\n\n## Status\n\n"
        "!!! warning \"Pre-alpha\"\n\n"
        "    - **`h/fn`** is exported today as `h/hfn`.\n"
        "    - **`h/mount!`** is exported today as `h/root!`.\n\n"
        "    Everything else — `h/sub`, `h/hydrate!` — ships.\n\n"
        "## Next\n\nnot the status block: **`h/ghost`**\n"
    )
    two = {("re-frame.hicasso", "fn"): ("hfn", ""),
           ("re-frame.hicasso", "mount!"): ("root!", "")}
    r4 = lambda p, div, abs_=None: r4_status_block_declares(
        {"index.md": p}, fx_aliases, div, {} if abs_ is None else abs_)

    check("R4 silence when page and ledger agree", r4(page, two) == [])
    check("R4 catches a ROW the page does not declare",
          any("is NOT declared" in p and "h/frame" in p for p in r4(
              page, {**two, ("re-frame.hicasso", "frame"): ("hframe", "")})))
    # The observed failure: a gap closes, R3 reds, the row goes, the BULLET
    # stays, and the page keeps calling a shipped door absent.
    check("R4 catches a BULLET whose row has gone",
          any("NO ledger row carries it" in p and "h/mount!" in p
              for p in r4(page, {("re-frame.hicasso", "fn"): ("hfn", "")})))
    check("R4 reads only the Status block, not the whole page",
          not any("h/ghost" in p for p in r4(page, two)))
    check("R4 does not mistake the exported roster for a declaration",
          not any("h/sub" in p or "h/hydrate!" in p for p in r4(page, two)))
    check("R4 declares an ABSENT NAMESPACE by its own name",
          any("is NOT declared" in p and "re-frame.hicasso.server" in p
              for p in r4(page, two, {"re-frame.hicasso.server": ""})))
    check("R4 reds when the Status section is missing entirely",
          any("no `## Status` section" in p
              for p in r4("# Guide\n\nno status here\n", two)))

    # ---- R5 -----------------------------------------------------------
    #
    # Both recorded defects are reproduced here in their real shape, because
    # this rule exists for them specifically (rf2-vz6dg): a var use whose
    # alias no `ns` form bound, and an auto-resolved KEYWORD whose alias no
    # `ns` form bound.  The second is the one that never parsed.
    fx_vocab = {"rf": "re-frame.core", "h": "re-frame.hicasso",
                "subs": "my.app.subs", "str": "clojure.string"}

    def r5(page_text: str):
        pages_ = {"p.md": page_text}
        return r5_unbound_alias(pages_, {"p.md": blocks(page_text)}, fx_vocab)

    ok_page = (
        "## A recipe\n\n"
        "```clojure\n(ns my.app.views\n"
        "  (:require [re-frame.core :as rf]\n"
        "            [re-frame.hicasso :as h]))\n```\n\n"
        "```clojure\n(rf/reg-event :x)\n(h/sub [:y])\n```\n"
    )
    check("R5 silence when the section's ns form binds what it uses",
          r5(ok_page) == [])

    # Defect 1: `rf/reg-event` in a later block, no `re-frame.core` required.
    missing_var = (
        "## A recipe\n\n"
        "```clojure\n(ns my.app.views\n"
        "  (:require [re-frame.hicasso :as h]))\n```\n\n"
        "```clojure\n(rf/reg-event :x)\n```\n"
    )
    problems = r5(missing_var)
    check("R5 catches a VAR use whose alias the section never required",
          any("`rf/reg-event`" in p and "re-frame.core" in p for p in problems))
    check("R5 names the var case as an undeclared var",
          any("an undeclared var" in p for p in problems))

    # Defect 2, the reader failure: `::subs/row-count` with no `subs` alias,
    # in the SAME block as the ns form that omits it.
    missing_kw = (
        "## A recipe\n\n"
        "```clojure\n(ns my.app.views\n"
        "  (:require [re-frame.hicasso :as h]))\n\n"
        "(h/defview ledger [_]\n"
        "  (let [total (h/sub [::subs/row-count])]\n"
        "    [:div total]))\n```\n"
    )
    problems = r5(missing_kw)
    check("R5 catches an AUTO-RESOLVED KEYWORD whose alias is unbound",
          any("`subs/row-count`" in p for p in problems))
    check("R5 explains the keyword case as a READ-TIME failure",
          any("AT READ TIME" in p and "does not parse" in p for p in problems))

    # A heading ENDS the scope — the measured difference between this rule
    # reporting 0 sites on a correct corpus and reporting 21.
    after_heading = (
        "## First\n\n"
        "```clojure\n(ns my.app.views\n"
        "  (:require [re-frame.core :as rf]))\n```\n\n"
        "## Second\n\n"
        "```clojure\n(rf/reg-event :x)\n```\n"
    )
    check("R5 does not inherit a scope across a heading",
          r5(after_heading) == [])

    check("R5 asks nothing of a block no ns form governs",
          r5("## Fragment\n\n```clojure\n(rf/reg-event :x)\n```\n") == [])
    check("R5 ignores an alias the guide never binds",
          r5("## S\n\n```clojure\n(ns a (:require [re-frame.core :as rf]))\n"
             "(js/setTimeout f 0)\n```\n") == [])
    check("R5 reads no verb from a non-Clojure fence",
          r5("## S\n\n```clojure\n(ns a (:require [re-frame.core :as rf]))\n```\n\n"
             "```css\n.x{}\n```\n") == [])
    check("R5 ignores a plain qualified keyword, which needs no alias",
          r5("## S\n\n```clojure\n(ns a (:require [re-frame.core :as rf]))\n"
             "(def m {:subs/row-count 1})\n```\n") == [])
    check("R5 is silent on an alias used inside a string or comment",
          r5("## S\n\n```clojure\n(ns a (:require [re-frame.core :as rf]))\n"
             '(def d "subs/row-count") ; str/join\n```\n') == [])

    # The two false greens the merged-PR audit of #8321 found in this rule as
    # it first shipped.  Both are the SAME mistake read from opposite ends —
    # trusting text that binds nothing — and both are reproduced in the exact
    # shape the audit used, because each one certified a sample that does not
    # compile.

    # 1. The require is COMMENTED OUT, so it is not a binding.  Scope
    # discovery read the raw body and took it for one, which made the rule
    # green on precisely the omission it is for.
    commented_require = (
        "## A recipe\n\n"
        "```clojure\n(ns my.app.views\n"
        "  ;; [re-frame.core :as rf]\n"
        "  (:require [re-frame.hicasso :as h]))\n\n"
        "(rf/reg-event :x)\n```\n"
    )
    check("R5 does not accept a COMMENTED-OUT require as a binding",
          any("`rf/reg-event`" in p for p in r5(commented_require)))
    check("a commented-out require binds nothing in the scope itself",
          section_scopes(commented_require) == [{"h": "re-frame.hicasso"}])
    # The other half of the same masking edit: a commented-out `ns` form is
    # not an `ns` form, so it opens no scope for the rule to check against.
    check("a commented-out ns form opens no scope",
          section_scopes("## S\n\n```clojure\n;; (ns my.app.views\n"
                         ";;   (:require [re-frame.core :as rf]))\n"
                         "(rf/reg-event :x)\n```\n") == [None])

    # 2. `::sbus/row-count` under an ns binding only `h`, with `sbus` bound
    # NOWHERE in the guide.  Gating the keyword arm on the derived vocabulary
    # let every misspelt alias through — and a typo is bound nowhere by
    # definition, so the guard was weakest exactly where the reader error is.
    unknown_kw = (
        "## A recipe\n\n"
        "```clojure\n(ns my.app.views\n"
        "  (:require [re-frame.hicasso :as h]))\n\n"
        "(h/defview ledger [_]\n"
        "  (let [total (h/sub [::sbus/row-count])]\n"
        "    [:div total]))\n```\n"
    )
    problems = r5(unknown_kw)
    check("R5 catches a KEYWORD alias the guide binds NOWHERE",
          any("`sbus/row-count`" in p for p in problems))
    check("R5 names no namespace for an alias it has never seen bound",
          any("any namespace under the alias `sbus`" in p for p in problems))
    # The ceiling that keeps the var arm derived rather than filtered: the
    # same unknown alias as a SYMBOL stays out of scope, which is what lets
    # `js/` and `goog/` need no exclusion list.
    check("R5 leaves an unknown alias in VAR position alone",
          r5("## S\n\n```clojure\n(ns a (:require [re-frame.hicasso :as h]))\n"
             "(sbus/row-count 1)\n```\n") == [])

    # The FOUR shapes the merged-PR audit of #8331 found still standing after
    # the repair above.  Masking had made INERT text stop binding; these are
    # the ones where the text is perfectly live and still binds nothing, which
    # no amount of masking can reach.  Each is reproduced in the exact shape
    # the audit used, and each was measured wrong before this pass: all three
    # scope shapes returned zero R5 problems on a sample that cannot compile.
    only_h = {"h": "re-frame.hicasso"}

    # 1. A `:require`-shaped VECTOR that is a datum.  Live code, quoted, and a
    # binding of nothing whatever.
    data_vector = (
        "## A recipe\n\n"
        "```clojure\n(ns my.app.views\n"
        "  (:require [re-frame.hicasso :as h]))\n\n"
        "(def documented-shape '[re-frame.core :as rf])\n\n"
        "(rf/reg-event :x)\n```\n"
    )
    check("R5 does not accept a QUOTED require-shaped vector as a binding",
          any("`rf/reg-event`" in p for p in r5(data_vector)))
    check("a require-shaped datum binds nothing in the scope itself",
          section_scopes(data_vector) == [only_h])

    # 2. `(comment …)`, which evaluates its body to nil and requires nothing.
    comment_macro = (
        "## A recipe\n\n"
        "```clojure\n(ns my.app.views\n"
        "  (:require [re-frame.hicasso :as h]))\n\n"
        "(comment [re-frame.core :as rf])\n\n"
        "(rf/reg-event :x)\n```\n"
    )
    check("R5 does not accept a (comment …) vector as a binding",
          any("`rf/reg-event`" in p for p in r5(comment_macro)))
    check("a commented-out require binds nothing even inside live code",
          section_scopes(comment_macro) == [only_h])

    # 3. A reader discard INSIDE the live `:require` — the one shape here that
    # masking rather than scoping has to catch, since it sits exactly where a
    # binding would be.
    discarded_require = (
        "## A recipe\n\n"
        "```clojure\n(ns my.app.views\n"
        "  (:require [re-frame.hicasso :as h]\n"
        "            #_[re-frame.core :as rf]))\n\n"
        "(rf/reg-event :x)\n```\n"
    )
    check("R5 does not accept a #_ DISCARDED require as a binding",
          any("`rf/reg-event`" in p for p in r5(discarded_require)))
    check("a discarded require binds nothing in the scope itself",
          section_scopes(discarded_require) == [only_h])

    # The same masking read from the other end: a discarded USE is not a use,
    # in either kind.  Without this the repair would trade a false green for a
    # false red — a sample that compiles, failed for text the reader deletes.
    check("R5 reads no VAR use and no KEYWORD use out of a #_ discarded form",
          r5("## S\n\n```clojure\n(ns my.app.views\n"
             "  (:require [re-frame.hicasso :as h]))\n\n"
             "#_(rf/reg-event :x)\n#_::subs/row-count\n```\n") == [])

    # 4. The vocabulary's mirror of the same defect, and it fails the OTHER
    # way.  Scraped from raw page text, a require commented out in PROSE made
    # its alias look guide-bound, which promoted a `sbus/row-count` symbol the
    # var arm deliberately ignores into an error.
    def r5_derived(page_text: str):
        corp_ = {"p.md": blocks(page_text)}
        return r5_unbound_alias({"p.md": page_text}, corp_, bound_aliases(corp_))

    prose_require = (
        "# Page\n\nOutside any fence, in prose:\n\n"
        ";; [my.fake.ns :as sbus]\n\n"
        "## A recipe\n\n"
        "```clojure\n(ns my.app.views\n"
        "  (:require [re-frame.hicasso :as h]))\n\n"
        "(sbus/row-count 1)\n```\n"
    )
    check("a require in PROSE binds no alias into the vocabulary",
          "sbus" not in bound_aliases({"p.md": blocks(prose_require)}))
    check("R5 does not promote an ignored symbol on a prose-only require",
          r5_derived(prose_require) == [])
    check("the vocabulary is every alias an ns form DOES bind",
          bound_aliases({"p.md": blocks(prose_require)}) == only_h)
    check("R2's map is the hicasso filter over that same derivation",
          guide_aliases({"p.md": blocks(
              "```clojure\n(ns a (:require [re-frame.hicasso.forms :as forms]\n"
              "                [re-frame.core :as rf]))\n```\n")})
          == {"forms": "re-frame.hicasso.forms"})

    # ---- what an `ns` form binds -------------------------------------
    nsb = lambda code: ns_bindings("\n".join(mask(code.split("\n"))))
    check("a block with no ns form binds nothing and is not checked",
          nsb("(rf/reg-event :x)") is None)
    check("an ns form with no :require is a scope that binds NOTHING",
          nsb("(ns my.app.views)\n(rf/reg-event :x)") == {})
    check("an ns form nested in (comment …) is not the block's ns form",
          nsb("(comment (ns my.app (:require [re-frame.core :as rf])))") is None)
    check("a #_ discarded ns form opens no scope",
          nsb("#_(ns my.app (:require [re-frame.core :as rf]))") is None)
    check("requires are read across lines",
          nsb("(ns a\n  (:require [re-frame.core :as rf]\n"
              "            [clojure.string :as str]))")
          == {"rf": "re-frame.core", "str": "clojure.string"})
    check(":require-macros binds an alias too",
          nsb("(ns a (:require-macros [my.macros :as m]))") == {"m": "my.macros"})
    check("an ns form's OTHER clauses are not require clauses",
          nsb('(ns a "doc" (:import [java.util Date]) '
              '(:require [re-frame.core :as rf]))') == {"rf": "re-frame.core"})

    # ---- R6, the one place this gate fails CLOSED ---------------------
    #
    # `#_` elides the next FORM and forms nest.  Guessing is wrong in both
    # directions at once, so the unbounded shapes are refused instead.
    check("a #_ eliding a delimited form is bounded", discard_refusals(
        ["(list #_[a :as b] c)"]) == [])
    check("a #_ eliding a plain token is bounded",
          discard_refusals(["#_:clj-kondo/ignore", "(def x 1)"]) == [])
    check("a #_ eliding a string is bounded", discard_refusals(['#_"text" x']) == [])
    check("a #_ form is elided across LINES",
          "rf/reg-event" not in "".join(mask(["#_(rf/reg-event", "   :x)", "(h/sub)"])))
    check("R6 refuses a NESTED discard", discard_refusals(["#_#_ a b"]) != [])
    check("R6 refuses a discard carrying metadata",
          discard_refusals(["#_^:private (def x 1)"]) != [])
    check("R6 refuses a discarded dispatch form", discard_refusals(["#_#(inc %)"]) != [])
    check("R6 refuses a discarded quoted form", discard_refusals(["#_'sym"]) != [])
    check("R6 refuses a discard whose form never closes",
          discard_refusals(["#_(a b"]) != [])
    check("R6 names the page and block of a refusal",
          any("p.md block 1" in p and "REFUSES" in p for p in r6_unreadable_discard(
              {"p.md": [{"n": 1, "lang": "clojure", "body": ["#_#_ a b"]}]})))
    check("R6 reads no discard from a non-Clojure fence",
          r6_unreadable_discard(
              {"p.md": [{"n": 1, "lang": "text", "body": ["#_#_ a b"]}]}) == [])

    # `section_scopes` stays aligned with `blocks`, which is what lets R1's
    # ordinals and R5's scopes describe the same block.
    aligned = ok_page + "\n## Next\n\n```css\n.a{}\n```\n"
    check("section_scopes is one entry per fenced block, in the same order",
          len(section_scopes(aligned)) == len(blocks(aligned)))

    # ---- masking ------------------------------------------------------
    check("mask blanks a `;` comment", mask(["(h/sub) ; h/ghost"])[0].strip()
          == "(h/sub)")
    check("mask blanks a string body", "ghost" not in mask(['(def x "h/ghost")'])[0])
    check("mask survives a lone quote inside a comment",
          "h/sub" in mask([';; a " quote', "(h/sub [:x])"])[1])
    check("mask keeps line length", len(mask(["(h/sub) ; x"])[0]) == len("(h/sub) ; x"))
    check("mask keeps the line count", len(mask(["#_(a", " b)", "(h/sub)"])) == 3)
    # A character literal is a VALUE.  Reading `\\;` as a comment, `\\"` as a
    # quote or `\\)` as a delimiter is how the matcher that finds an `ns`
    # form's `:require` clause loses its place.
    check("a `\\;` character literal starts no comment",
          "h/sub" in mask([r"(str \; (h/sub))"])[0])
    check("a `\\\"` character literal opens no string",
          "h/sub" in mask([r'(str \" x)', "(h/sub [:x])"])[1])
    check("a `\\)` character literal closes no form",
          nsb(r"(ns a (:require [re-frame.core :as rf])) (str \))")
          == {"rf": "re-frame.core"})

    # ---- the qualified-symbol reader ----------------------------------
    aliases = {"h": "re-frame.hicasso"}
    corp = {"p.md": [{"n": 1, "lang": "clojure",
                      "body": ["(h/sub [:x]) ::h/value :h/thing"]}]}
    seen = qualified_uses(corp, aliases)
    check("a var use is seen", ("re-frame.hicasso", "sub") in seen)
    check("an auto-resolved KEYWORD is not a var use",
          ("re-frame.hicasso", "value") not in seen)
    check("a plain qualified keyword is not a var use",
          ("re-frame.hicasso", "thing") not in seen)
    check("an unknown alias is out of scope",
          qualified_uses({"p.md": [{"n": 1, "lang": "clojure",
                                    "body": ["(js/setTimeout f 0)"]}]}, aliases) == {})
    check("a non-Clojure fence carries no verb",
          qualified_uses({"p.md": [{"n": 1, "lang": "css",
                                    "body": ["(h/sub [:x])"]}]}, aliases) == {})

    # ---- the def reader -----------------------------------------------
    check("a namespace-qualified defining macro mints a name",
          "buffered-field" in {n for _h, n in _DEF_RE.findall(
              "(h/defview buffered-field [props] [:input])")})
    check("a metadata-carrying def mints a name",
          "sub" in {n for _h, n in _DEF_RE.findall(
              '(def ^{:doc "x"}\n  sub impl/sub)')})

    if failures:
        print("SELF-TEST FAILED:", file=sys.stderr)
        for line in failures:
            print(f"  {line}", file=sys.stderr)
        return 1
    print("check_guide_samples self-test: all rules fire.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--self-test", action="store_true",
                        help="drive every rule red against synthetic input")
    parser.add_argument("--emit-roster", action="store_true",
                        help="print the roster literal for the guide as it stands")
    parser.add_argument("--list", "--verbose", dest="verbose", action="store_true",
                        help="list every resolved verb and its use-site count")
    args = parser.parse_args()

    if args.self_test:
        return self_test()
    if args.emit_roster:
        print(emit_roster(corpus()))
        return 0
    return run_check(verbose=args.verbose)


if __name__ == "__main__":
    sys.exit(main())
