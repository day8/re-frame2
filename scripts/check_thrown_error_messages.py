#!/usr/bin/env python3
"""rf2-vvixub thrown-error HUMAN-MESSAGE contract — corpus-driven CI gate.

Spec 009 §The thrown-error shape rules the framework thrown-error message
contract (rf2-vvixub):

  1. `(ex-message e)` is a human-actionable one-line sentence (the public
     concept + the expected fix + key context). It is NOT the bare
     stringified `:rf.error/<id>` discriminator keyword.
  2. `:rf.error/id` (ex-data) is the SOLE canonical machine discriminator;
     tools and tests branch on it, never on the message.
  3. The message is stable in MEANING, not bytes.
  4. The message carries a trailing `[:rf.error/<id>]` greppability token.

`re-frame.error/throw-error!` / `re-frame.error/thrown-ex-info` are the
canonical builders: they DERIVE the message from `:reason` + the token, so a
keyword-only message is impossible to emit. The per-feature artefacts route
their throws through them (directly, or via thin helpers like
`flows.registry/flow-error`, `routing.registry/route-error`).

WHY THIS GATE EXISTS (the rf2-6bb3pg finding)

The original conformance test
(`implementation/core/test/re_frame/thrown_error_message_conformance_cljs_test.cljc`)
is a CURATED allow-list: it `:require`s a handful of namespaces and exercises
those sites directly. A NEW (or never-converted) `(ex-info ":rf.error/…" …)`
site is invisible to it. rf2-6bb3pg found 50+ such sites still shipping the
bare keyword as `ex-message` — the exact regression rf2-vvixub abolished —
that CI could not see. This gate replaces the allow-list with a CORPUS SWEEP
that fails on ANY framework `(ex-info …)` whose MESSAGE position is a bare
`:rf.*` discriminator keyword, so the rollout cannot silently regress.

It mirrors the source-scanning drift guards already in this tree
(`check_retired_spellings.py` / `check_ambient_durable_reads.py` /
the EP-0011 reply-vocab + EP-0015 privacy-vocab projection guards): a
comment/string-masked scan over framework source, a `--self-test` fixture
mode that proves the gate FIRES on each bad shape and stays GREEN on the
conformant counterparts, and a fix hint.

THE FLAGGED SHAPE (the bare-keyword MESSAGE — `ex-info`'s FIRST argument)

The contract violation is entirely about the FIRST argument of `ex-info`
(the message). A conformant site passes a human sentence there — usually
`(human-message …)` / `(error/human-message …)`, or a builder
(`thrown-ex-info` / `throw-error!` / `flow-error` / `route-error` / …) that
derives it. A NON-conformant site passes a bare error keyword:

  (a) a `:rf.*` keyword STRING LITERAL:        (ex-info ":rf.error/foo" …)
  (b) `(str <literal :rf.* keyword>)`:         (ex-info (str :rf.error/foo) …)
  (c) `(str <error-keyword-named var>)`:        (ex-info (str error-kw) …)
                                                (ex-info (str error-id) …)
  (d) `(str (:rf.error/id <map>))`:             (ex-info (str (:rf.error/id payload)) …)

All four render `ex-message` as exactly the stringified discriminator
keyword — the OLD shape. Forms (c)/(d) are the per-surface helper clones
(`registration-error`, `validation-error`, `raise-removed-reg-event!`, the
frame.cljc payload throws) that centralised the regression rather than
fixing it; they still ship the bare keyword and are equally caught.

We detect on the `ex-info` FIRST ARGUMENT only, so a `:rf.error/foo` keyword
appearing as a `:reason` value, an ex-data slot, a trace-emit arg, or inside
a docstring/comment never fires — those are sanctioned.

THE WIDENED RULE (the builder-BYPASS — rf2-krrv87)

The four patterns above catch a message that is LITERALLY the discriminator
keyword. But a site can ALSO bypass the canonical builder while putting
*some* human-ish text in the message — yet still skip the `[:rf.<ns>/…]`
greppability token (rule 4) and the central `human-message` derivation. The
error model is uniform behind `error/throw-error!` / `thrown-ex-info` /
`ex-info-from-data`; a raw `(ex-info …)` whose ex-data carries `:rf.error/id`
is a hand-rolled error that did NOT route through the builder, and a future
one would re-introduce exactly the drift those builders abolish.

  (e) builder bypass:   (throw (ex-info (str "raw " x) {… :rf.error/id …}))
                        (throw (ex-info "plain prose"  {… :rf.error/id …}))

The widened rule (`builder-bypass-message`) fires on ANY raw `(ex-info <msg>
<data>)` where `<data>` carries `:rf.error/id` AND `<msg>` is NOT conformant.
A CONFORMANT message (does not fire) is one of:

  - a `(human-message …)` / `(<ns>/human-message …)` call (the central
    derivation the builders use); or
  - a message that ALREADY embeds the `[:rf.<ns>/…]` token — a contiguous
    literal `"… [:rf.<ns>/…]"` OR the token ASSEMBLED across a `(str …)`
    concatenation `(str "… [" :rf.<ns>/<id> "]")` — the SANCTIONED
    bundle-isolated reagent-slim shape (it cannot `:require re-frame.error`,
    so it hand-rolls the human sentence + token inline); or
  - a bare LOCAL SYMBOL whose binding in the innermost enclosing `let` is
    itself conformant by the two rules above (rf2-u3otj) — the token lives on
    the bound form, one hop from the `ex-info` — AND ONLY when that `let` is
    provably the binding in scope at the call. A binder in between (an `fn`
    parameter, an `fn` SELF-REFERENCE NAME, a `catch` name, a destructured
    inner `let`) shadows the name, so the resolution fails closed and the
    finding stands.

A DELIBERATE exemption — a message pinned to bare prose downstream (the
conformance-DSL `:throw` / `:count` ops re-emit it as `:exception-message`,
and the conformance corpus pins the exact text) — opts out with a
`rf2:builder-bypass-ok` marker comment on the `ex-info` line or in the small
comment block directly above it. A new ACCIDENTAL bypass has no marker and
fails. The four bare-keyword patterns are a strict subset of this rule (their
message is a bare keyword, which is neither a `human-message` call nor a
token-bearing literal); a site matching both is reported once under its more
specific bare-keyword kind.

SCAN SURFACE

Framework source under `implementation/` — `.clj` / `.cljc` / `.cljs`. The
scope is a REACHABILITY RULE, not a directory list: the shape governs a
message that is read off-box (a consumer app catching the ex-info, an MCP
client shown the text), and not one whose only reader is the developer who
ran the tool. `DEFAULT_SCAN_DIRS` below carries the decidable part of that
rule plus the stated ground for every excluded tree — including the
`tools/` paths the rule DOES reach, which a text scan cannot identify. The
default also excludes `test/` trees (a test that asserts the old shape is gone, or
constructs a `(ex-info ":rf.error/…")` to exercise a predicate, legitimately
names it). The one framework artefact that CANNOT `:require` `re-frame.error`
(the bundle-isolated `reagent-slim` adapter) hand-rolls a human sentence
inline — so it too must clear the gate, and it is under `implementation/`.

THE WHERE-SYM RULE (rf2-z5lv) — the door the message names must EXIST

The rules above grade the MESSAGE. This one grades the other half of the same
promise. `thrown-ex-info` / `throw-error!` take a `where-sym` as their SECOND
positional argument and land it in `:where`, and its whole job — in
`re-frame.error`'s own words — is that "a grep-for-symbol lands on the call
site". A where-sym that names nothing reachable sends the author who reads the
message to a door that is not there.

Three beads paid for that one site at a time (rf2-z67m fixed seven, rf2-0v23 an
eighth, and a sweep measured twenty-one more), which is what makes it a gate's
job rather than a fix's.

  * THE RULE IS RESOLVABILITY, NOT A SPELLING. `re-frame.core`'s own
    `not-queryable-kinds` map writes `re-frame.flows/flows` fully qualified
    beside `rf/frame-ids` under the façade alias, in the same map, and both are
    right — the discriminator is whether the namespace exports the name. A
    fully-qualified symbol still fails when the var is private, which is the
    live `'re-frame.router/build-envelope` (a `defn-`) shape.
  * THE ORACLE IS THE PUBLIC VAR SET, NOT `spec/api-manifest.edn`, which rows
    DOCUMENTED publics only — measured on trunk, `re-frame.core` has 71 rows
    against 82 JVM publics, and the `^:no-doc` façade stubs `reset-frame!` /
    `reload-images!` carry none. The manifest is used instead as the
    INDEPENDENT CONTROL on the derived oracle (`oracle_problems`) — in ONE
    direction: a subset test catches the parser going blind, never the parser
    inventing a public. The exact-set assertion in the self-test owns that
    half, and it is what `(comment (defn ghost …))` got past.
  * IT READS ONLY THE FORMS THAT ARE ACTUALLY EVALUATED. `(comment …)` interns
    nothing, `#_` discards, and a quoted form is data; each once contributed a
    fictitious public, so a where-sym naming a var that exists only inside one
    resolved as a live door. `(do (defn …))` is the wrapper that really is
    transparent and still counts.
  * IT ASSERTS ABOUT BOTH RUNTIMES. The public set is read from source across
    both reader-conditional arms, because `rf/frame-provider` and
    `rf/frame-root` are `#?(:cljs (def …))` façade exports that a JVM-only
    oracle reports as dead doors.
  * IT DOES NOT RED THE TREE. `scripts/where-sym-baseline.edn` records what
    trunk already carries; an unlisted symbol has a floor of zero, so a NEW
    dead door fails, and a recorded floor ratchets DOWN only.

Both halves of the where-sym rule are defined over the ROSTERED scan surface,
so `--scan-dir` skips it rather than misgrading an ad-hoc corpus.

Exit code:
    0  no bare-keyword thrown-error message in framework source, and no
       where-sym over its recorded floor
    1  at least one (results printed file:line)
    2  invocation / setup error, or an instrument that cannot be trusted —
       the derived public-var oracle contradicting the generated manifest, or
       a where-sym population collapsed below `:min-where-syms`

Dependency-light — Python stdlib only (plus `git ls-files`, for the tracked
Clojure roster the where-sym oracle resolves against).
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path
from typing import Iterable, NamedTuple

# --------------------------------------------------------------------------
# Scan surface
# --------------------------------------------------------------------------

# THE SCOPE IS A REACHABILITY RULE, AND THE ROSTER BELOW IS ITS DECIDABLE PART.
#
#   THE RULE (rf2-eo2y5, amended 2026-07-26): the Spec 009 thrown-error shape
#   governs framework source, PLUS any `tools/` path whose thrown message is
#   RELAYED OFF-BOX. A message is in scope when someone other than the
#   developer at the keyboard reads it — a consumer application catching the
#   ex-info, or an MCP client (a consumer AI) shown the text. It is out of
#   scope when the only reader is a developer running an inspection tool, who
#   has the stack trace, the source, and the ex-data in front of them.
#
# The rule is stated here rather than mechanised because THIS GATE CANNOT
# DECIDE IT. Whether a throw's message is relayed off-box is an interprocedural
# fact spanning artefacts: `(ex-message e)` is read in a `catch` in one tree
# and the throw it re-narrates lives in another, reached through a call chain
# a text scan cannot follow. A roster is the part that IS decidable, so the
# roster carries the trees where the rule is unconditionally true and this
# comment carries the rest. A comment that names the rule beats a roster that
# silently means something narrower.
#
# It used to be a bare `DEFAULT_SCAN_DIR = "implementation"` with no note,
# which reads as unexamined — and an unexamined scope is how a gate silently
# covers less than its reader assumes, then gets "helpfully" widened by the
# next reader who cannot tell a decision from an omission. Enumerate the
# candidate trees with:
#
#   git ls-files | grep -E '\.clj[cs]?$' | sed -E 's#/.*##' | sort -u
#
# WHY THE ROSTER IS ONE TREE, when the sibling ratchet rosters them all. The
# two gates share a template but not a subject. `check_retired_spellings.py`
# rosters every Clojure tree because a retired SPELLING is drift wherever it
# appears — most of all in the trees a reader copies from (rf2-kqxe6.25). A
# thrown-error SHAPE is a contract only where something downstream reads it.
# `implementation/` is where that is true of every file.
#
# The excluded trees, and the ground for each:
#
#   * `tools/` — dev tooling: the reader of a tool's ex-info is the developer
#     who ran the tool. `tools/` also ships on its own tag prefixes
#     (`story-v…`, `xray-v…`, `machines-viz-v…`, `template-v…`), not on the
#     framework's `v…` tag. Ruled out of scope by rf2-eo2y5, and the 38-site
#     conversion sweep that widening would demand is refused.
#     THIS EXCLUSION IS NOT A CLAIM THE TREE IS CLEAN, and it is NOT
#     unconditional. Point `--scan-dir tools` at it and the gate reports
#     findings; the ruling declined to convert them rather than finding
#     nothing to convert. AND THE RULE ABOVE REACHES INTO IT: `tools/story-mcp`
#     relays `(ex-message e)` straight into an MCP tool result, so the
#     `tools/story` throws behind that relay are read by a consumer AI and
#     ARE governed. rf2-jquiy owns the traced sites. Converting them is the
#     fix; widening this roster is not — it would drag in the sites the
#     ruling refused along with the ones it claimed.
#   * `examples/`, `skills/`, `testbeds/`, `migration/`, `docs/tools/` —
#     consumer-SHAPED code: sample apps, teaching material, the docs
#     playground. An error thrown by an example app is the example's own,
#     not the framework's public error surface, and nothing downstream
#     re-narrates it.
#   * `scripts/` — `scripts/_test_fixtures/check_thrown_error_messages/`
#     holds this gate's own POSITIVE self-test fixtures. They plant the bad
#     shapes on purpose; a live scan over them would be red by construction,
#     forever. (Same ground the sibling states for the same directory.)
#   * `docs/` — mkdocs stages `spec/` and `migration/` into `docs/` at build
#     time (see `.gitignore`), so a checkout that has run `mkdocs build`
#     carries GENERATED copies. Scanning `docs/` would report a finding
#     twice, or report a stale one from a copy predating its fix.
#   * `.clj-kondo/` — lint hook code (`.clj-kondo/hooks/**`) that runs inside
#     the linter, never in a framework or consumer runtime.
#
# `spec/` carries no Clojure source at all (prose + EDN fixtures) and this
# gate's suffix filter is source-only, so it is not a candidate.
DEFAULT_SCAN_DIRS = ("implementation",)

_SOURCE_SUFFIXES = (".clj", ".cljc", ".cljs")

_EXCLUDE_DIR_NAMES = frozenset({
    "node_modules",
    "target",
    "out",
    ".shadow-cljs",
    ".git",
    ".beads",
    ".cpcache",
})

# `test` / `tests` dirs are excluded by default (see module docstring): a test
# may legitimately construct a bare-keyword ex-info to exercise a predicate, or
# assert the old shape is gone. `--include-tests` lifts the exclusion (the
# self-test fixtures rely on this — their files live under fixture dirs).
_TEST_DIR_NAMES = frozenset({"test", "tests"})


# --------------------------------------------------------------------------
# The bare-keyword MESSAGE patterns (ex-info's FIRST argument)
# --------------------------------------------------------------------------
#
# Every pattern anchors on `(ex-info` + optional whitespace + the bad first
# argument. The trailing whitespace/`)` after the keyword token ensures we
# matched the WHOLE message argument, not a longer expression that merely
# starts with the keyword (e.g. `(ex-info (str :rf.error/x " context") …)` —
# a `str` that CONCATENATES the keyword with human text is NOT a bare-keyword
# message and is intentionally not flagged).
#
# The `:rf\.[a-z]` namespace match covers every reserved discriminator root
# (`:rf.error/…`, `:rf.ssr/…`, …) without firing on app keywords.

# (a) A bare `:rf.*` keyword STRING LITERAL as the message.
#     (ex-info ":rf.error/foo" …)   — the quotes are part of the match so a
#     `:rf.error/foo` used as a non-message arg never fires here.
_LITERAL_STRING_RE = re.compile(
    r"""\(\s*ex-info\s+"(:rf\.[a-z][\w.-]*/[\w.*+!?<>=-]+)"\s*"""
)

# (b) (str <literal :rf.* keyword>) as the message.
#     (ex-info (str :rf.error/foo) …)
_STR_LITERAL_KW_RE = re.compile(
    r"\(\s*ex-info\s+\(\s*str\s+(:rf\.[a-z][\w.-]*/[\w.*+!?<>=-]+)\s*\)"
)

# (c) (str <error-keyword-named var>) as the message. The var name is the
#     load-bearing signal it holds a discriminator keyword: the per-surface
#     helper clones bind the keyword as `error-kw` / `error-id` /
#     `error-keyword`. Scoped to those exact names so a `(str some-human-var)`
#     never fires.
#     The accepted spellings are a NAMED roster so the self-test can hold each
#     to owning a fixture (rf2-n6ijg) — three of the five had none, and the
#     alternation is built from it, so the pattern is unchanged.
_STR_ERR_VAR_NAMES: tuple[str, ...] = (
    "error-kw", "error-id", "error-keyword", "err-kw", "err-id",
)
_STR_ERR_VAR_RE = re.compile(
    r"\(\s*ex-info\s+\(\s*str\s+(" + "|".join(_STR_ERR_VAR_NAMES) + r")\s*\)"
)

# (d) (str (:rf.error/id <map>)) as the message — pull the discriminator off a
#     payload map and stringify it. The frame.cljc payload throws use this.
_STR_ID_OF_MAP_RE = re.compile(
    r"\(\s*ex-info\s+\(\s*str\s+\(\s*:rf\.error/id\s+[\w.*+!?<>=/-]+\s*\)\s*\)"
)

_PATTERNS = (
    ("literal-keyword-string", _LITERAL_STRING_RE),
    ("str-literal-keyword", _STR_LITERAL_KW_RE),
    ("str-error-keyword-var", _STR_ERR_VAR_RE),
    ("str-id-of-payload", _STR_ID_OF_MAP_RE),
)


# --------------------------------------------------------------------------
# (e) The builder-BYPASS shape — a `(throw (ex-info <msg> {… :rf.error/id …}))`
#     whose MESSAGE bypasses the canonical builder (rf2-krrv87 gate widen).
# --------------------------------------------------------------------------
#
# The four bare-keyword patterns above catch a message that is LITERALLY the
# stringified discriminator. But a site can ALSO bypass the builder while
# putting *some* human-ish text in the message position — yet still skip the
# `[:rf.<ns>/<id>]` greppability token (rule 4) and the central
# `human-message` derivation. The canonical error model is uniform behind
# `error/throw-error!` / `error/thrown-ex-info` / `error/ex-info-from-data`;
# a raw `(ex-info …)` whose ex-data carries `:rf.error/id` is a hand-rolled
# error that did NOT route through the builder, and a future one would
# re-introduce exactly the drift those builders abolish.
#
# So: flag any raw `(ex-info <msg> <data>)` where
#   - the data argument carries `:rf.error/id` (it IS a framework error), AND
#   - the message is NOT conformant.
#
# A CONFORMANT message (does NOT fire) is one of:
#   - a `(human-message …)` / `(<ns>/human-message …)` call — the central
#     derivation (the builders use this under the hood; a few shared-payload
#     sites call it directly);
#   - a STRING LITERAL that already contains the bracketed `[:rf.<ns>/…]`
#     token — the SANCTIONED bundle-isolated reagent-slim shape, which cannot
#     `:require re-frame.error` and hand-rolls the human sentence + token
#     inline (module docstring §SCAN SURFACE).
#
# Everything else with `:rf.error/id` in ex-data is a builder bypass:
# a `(str …)` that is not the token form, a plain string literal WITHOUT the
# token, a bare var, a non-human-message fn call. The four bare-keyword
# patterns are a strict subset (their message IS a bare keyword, which is
# neither a human-message call nor a token-bearing literal) — the de-dup in
# `_scan_text` keeps a site reported once under its most specific kind.
#
# NOTE: the canonical builders (`thrown-ex-info` / `ex-info-from-data`) call
# `(ex-info (human-message …) …)` INTERNALLY in `re-frame.error`. Those are
# conformant by construction (the message IS a `human-message` call), so the
# rule does not fire on the builder definitions themselves.

# A `:rf.error/id` key appearing as a MAP KEY in the ex-data. (`:rf.error/id`
# is the canonical discriminator slot; its presence marks the form a framework
# error regardless of how the value is supplied.)
_HAS_ERROR_ID_RE = re.compile(r":rf\.error/id\b")

# A conformant message: a `(… human-message …)` call (any namespace alias) as
# the FIRST `ex-info` argument.
_HUMAN_MESSAGE_MSG_RE = re.compile(r"^\(\s*(?:[\w.*+!?<>=-]+/)?human-message\b")

# A conformant message: the message form ALREADY embeds the bracketed
# `[:rf.<ns>/…]` greppability token (rule 4) — whether as a pure string literal
# or inside a `(str … "[:rf.<ns>/…]")` concatenation. This is the SANCTIONED
# bundle-isolated reagent-slim shape: it cannot `:require re-frame.error`, so it
# hand-rolls the human sentence + the token inline. A message carrying the
# literal token IS conformant to rule 4, so it does not fire.
_INLINE_TOKEN_MSG_RE = re.compile(r"\[:rf\.[a-z][\w.-]*/[\w.*+!?<>=-]+\]")

# The same token ASSEMBLED across a `(str …)` concatenation — the open/close
# brackets are string-literal pieces and the keyword sits between them, e.g.
# `(str "… [" :rf.error/<id> "]")`. The runtime message is `[…:rf.<ns>/…]`,
# so it is equally conformant to rule 4; statically the bracket is not
# contiguous with the keyword, so this matches the assembled form: a `[` then
# (across quotes/whitespace) a `:rf.<ns>/…` keyword then a `]`.
#
# Both token regexes need the `:rf.<ns>/<id>` keyword and the human text to be
# LITERAL in the form they are handed. Two conformant shapes are not (rf2-jquiy
# documented them; rf2-u3otj resolves the first):
#
#   (a) a let-BOUND message —  (let [msg (str "… [:rf.error/x]")] (ex-info msg …))
#       `re-frame.story/configure!` (tools/story/src/re_frame/story.cljc:877,
#       :914) does exactly this, under a comment citing Spec 009. RESOLVED —
#       `_resolve_let_binding` below hands the BOUND form to these same
#       regexes, so the token is found one hop from the call.
#   (b) a COMPUTED discriminator — (ex-info (str reason " [" error-kw "]") …)
#       where `error-kw` is derived (`(keyword "rf.error" (str (name kind)
#       "-shape"))`) or arrives as a parameter of a shared throw helper. The
#       runtime message carries the token; the source text cannot. NOT
#       resolved, deliberately — see `_resolve_let_binding`'s note.
_ASSEMBLED_TOKEN_MSG_RE = re.compile(
    r'\[["\s]*:rf\.[a-z][\w.-]*/[\w.*+!?<>=-]+["\s]*\]'
)


def _message_is_conformant(form: str) -> bool:
    """Does this message FORM satisfy the thrown-error shape on its own text —
    a central `human-message` derivation, or a message already embedding the
    `[:rf.<ns>/…]` greppability token (contiguous, or assembled across a
    `(str …)`)?"""
    return bool(
        _HUMAN_MESSAGE_MSG_RE.match(form)
        or _INLINE_TOKEN_MSG_RE.search(form)
        or _ASSEMBLED_TOKEN_MSG_RE.search(form)
    )


# --------------------------------------------------------------------------
# READER WHITESPACE — a comma is whitespace, and forgetting it fails OPEN
# --------------------------------------------------------------------------
#
# `,` is WHITESPACE to the Clojure reader, so `(throw-error! :id 'ns/sym "r")`,
# `(,throw-error! :id 'ns/sym "r")` and `(throw-error! :id 'ns/sym, "r")` are
# the same three arguments — proved by JVM execution of all three spellings
# capturing IDENTICAL builder arguments (audit #9501). Python's `str.isspace()`
# and `\s` say otherwise, and the gap fails in the expensive direction: a comma
# anywhere the reader ignores it made a real where-sym VANISH and the gate exit
# 0 having observed only the sites it could still see.
#
# Nothing announces that. The population floor cannot catch it either —
# `:min-where-syms` was 173 against 193 observed, so twenty calls could go
# quiet before it noticed a thing. That is why the fixtures pin the SYMBOL AND
# LINE of each comma spelling rather than a total (the lesson PR #9496 landed
# on the sibling gate in this directory, which this one had not inherited).
#
# One notion, used everywhere a form boundary is found: the `[\s,]` class for
# the patterns, `_is_clj_ws` / `_clj_strip` for the hand-written scanners.
# `_QUOTED_SYM_RE` needs no comma of its own precisely BECAUSE every argument
# reaching it has come through `_split_first_arg`, which strips reader
# whitespace from both ends.
_CLJ_WS = r"[\s,]"
_CLJ_WS_EDGE_RE = re.compile(r"\A[\s,]+|[\s,]+\Z")


def _is_clj_ws(c: str) -> bool:
    """Is `c` whitespace to the Clojure reader? A comma is."""
    return c.isspace() or c == ","


def _clj_strip(s: str) -> str:
    """`str.strip()`, but over READER whitespace — commas included."""
    return _CLJ_WS_EDGE_RE.sub("", s)


def _split_first_arg(body: str) -> tuple[str, str]:
    """Split a balanced `ex-info` BODY (text after `ex-info` and its
    whitespace, up to but excluding the closing paren) into its FIRST argument
    (the message form) and the remainder (the ex-data + any extra).

    Returns `(first_arg, rest)` with both stripped of READER whitespace, which
    includes commas — see `_clj_strip`. Whitespace/quote/bracket aware so a
    `(str …)` / map / nested form is taken as one unit. A `;` comment cannot
    appear (the caller passes comment-masked text)."""
    body = _clj_strip(body)
    if not body:
        return "", ""
    i = 0
    n = len(body)
    depth = 0
    in_string = False
    # The first arg ends at the first top-level whitespace (depth 0, not in a
    # string) AFTER at least one token char — OR at the end of the body.
    started = False
    while i < n:
        c = body[i]
        if in_string:
            if c == "\\" and i + 1 < n:
                i += 2
                continue
            if c == '"':
                in_string = False
            i += 1
            continue
        if c == '"':
            in_string = True
            started = True
            i += 1
            continue
        if c in "([{":
            depth += 1
            started = True
            i += 1
            continue
        if c in ")]}":
            # A CLOSER AT DEPTH 0 ENDS THE ARGUMENT. The docstring's "balanced
            # body" is the contract for most callers, but the `:where`-slot
            # reader hands us the TAIL of an enclosing map, so the argument can
            # end at that map's `}` rather than at whitespace. Running past it
            # into depth -1 made the LAST entry of an ex-data map unreadable:
            # `{… :where 'ns/sym}` yielded `'ns/sym}`, which matches no symbol
            # pattern, so a real where-sym vanished and the gate exited 0.
            # Found by the control for the comma defect, and it is NOT the same
            # bug — `{… :where 'ns/sym}` and `{… :where, 'ns/sym}` both vanished
            # while both non-final spellings resolved.
            if depth == 0:
                break
            depth -= 1
            i += 1
            continue
        if _is_clj_ws(c):
            if started and depth == 0:
                break
            i += 1
            continue
        started = True
        i += 1
    return _clj_strip(body[:i]), _clj_strip(body[i:])


def _extract_ex_info_form(text: str, open_paren_idx: int) -> str | None:
    """Given the index of the `(` that opens an `(ex-info …)` form, return the
    BODY text between `ex-info` and the matching close paren (exclusive of the
    outer parens), or None if the form is unterminated. String/comment aware
    (caller passes comment-masked text; strings are tracked so a `)` inside a
    reason sentence does not close the form early)."""
    n = len(text)
    i = open_paren_idx
    depth = 0
    in_string = False
    start_body = None
    while i < n:
        c = text[i]
        if in_string:
            if c == "\\" and i + 1 < n:
                i += 2
                continue
            if c == '"':
                in_string = False
            i += 1
            continue
        if c == '"':
            in_string = True
            i += 1
            continue
        if c == "(":
            depth += 1
            if depth == 1:
                # Skip past `(ex-info` to the body start.
                m = re.match(r"\(\s*ex-info\b", text[i:])
                if not m:
                    return None
                start_body = i + m.end()  # index right after `ex-info`
            i += 1
            continue
        if c == ")":
            depth -= 1
            if depth == 0:
                if start_body is None:
                    return None
                return text[start_body:i]
            i += 1
            continue
        i += 1
    return None


_EX_INFO_OPEN_RE = re.compile(r"\(\s*ex-info\b")

# --------------------------------------------------------------------------
# Local-binding resolution (rf2-u3otj) — the message one hop from the call
# --------------------------------------------------------------------------
#
# A conformant message is sometimes bound before it is thrown:
#
#   (let [msg (str "configure! got unknown key(s): " … " [:rf.error/x]")]
#     (throw (ex-info msg {:rf.error/id :rf.error/x …})))
#
# The runtime message DOES carry the token, but the first `ex-info` argument is
# the bare symbol `msg`, so both token regexes saw nothing and the site was
# reported as a builder bypass. That false red is not free: it costs a round
# trip under the standing rule that a failing gate on a touched surface is
# never a flake, and the tempting "fix" is to DE-conformise working code to
# satisfy a blind scan. The trees where it currently bites sit outside
# `DEFAULT_SCAN_DIRS`, but the same shape under `implementation/` reds CI.
#
# So: when the message is a bare local symbol, resolve it through the innermost
# enclosing `let` and re-test the BOUND FORM with `_message_is_conformant` —
# the same predicates, no new ones. This is pure precision. A let-bound message
# WITHOUT a token still fires, because the bound form must clear the rule on
# its own text.
#
# DELIBERATELY NOT RESOLVED — the computed discriminator:
#
#   (ex-info (str reason " [" error-kw "]") {:rf.error/id error-kw …})
#
# where `error-kw` is derived, or arrives as a parameter of a shared throw
# helper (`story.plan/fail!`, `mcp-base.diff_encode/validate-against!`). The
# runtime message carries the token; the source cannot, and no static rule
# separates it from a genuine bypass. Treating a lone symbol between the
# brackets as a token would let ANY symbol pass — turning this false RED into a
# false GREEN, the more expensive defect. Those sites stay reported, and
# `rf2:builder-bypass-ok` exists for exactly that: a deliberate, documented
# exemption whose conformance a human has checked at runtime.
#
# `let` only. `loop` rebinds through `recur`, and `if-let`'s else branch does
# not see the binding — resolving either could green a site the reader cannot
# verify, which is the direction this gate must never move.
#
# …AND THE RESOLUTION MUST PROVE THE BINDING IS THE ONE IN SCOPE (rf2-u3otj,
# the #7045 audit). Finding an enclosing `let` that binds the name is not the
# same as finding the binding the reader would see at the call. Any binder
# BETWEEN the two shadows it:
#
#   (let [msg (str "outer conformant [:rf.error/outer]")]
#     (fn [msg]                               ; ← the parameter shadows it
#       (throw (ex-info msg {:rf.error/id :rf.error/inner-bypass …}))))
#
# `msg` at the throw is the tokenless parameter, so the site IS a bypass; a
# resolver that only searches enclosing `let`s finds the outer one and greens
# it — the false GREEN this gate must never emit. Every lexical binder does it
# (`fn`, `loop`, `if-let`, `doseq`, `for`, `catch`, `letfn`, `as->`, a
# DESTRUCTURED inner `let` the binding-name match cannot see, …), so the fix is
# not a longer list of binders to look for.
#
# It is the opposite: `_scope_is_provable` crosses only forms it can prove
# introduce nothing, and FAILS CLOSED on everything else. A crossed form is
# transparent when its head is ordinary control flow (`_TRANSPARENT_HEADS`), or
# when it binds through a leading vector (`_VECTOR_BINDER_HEADS`) that does not
# mention the symbol. An unrecognised head — a binding macro this gate has never
# heard of, an `fn` arity list, a `catch` — is not proven, so the finding stands.
# A whitelist is what makes the analysis SOUND: an unknown binder can only cost
# a false red, never buy a false green.
#
# …AND A VECTOR BINDER DOES NOT ALWAYS BIND IN ITS VECTOR (rf2-u3otj, the #7064
# audit). `fn` carries an optional SELF-REFERENCE NAME before the parameters:
#
#   (let [msg "outer conformant [:rf.error/outer]"]
#     (fn msg [x]                             ; ← the fn's own name shadows it
#       (throw (ex-info msg {:rf.error/id :rf.error/inner-bypass …}))))
#
# Checking only the parameter vector proved a transparency that is not there —
# `[x]` never mentions `msg`, so the crossing looked clean and the outer binding
# greened a genuine bypass. The proof therefore reads the WHOLE run from the
# head to the binding vector: the head binds nothing, and anything else standing
# there must not mention the symbol. That covers the `fn` name without naming
# `fn`, and keeps the fail-closed posture for whatever else turns up in that
# position. (The MULTI-ARITY spelling `(fn msg ([x] …))` was already caught, by
# the other arm — the arity list is an unrecognised head — but it is a distinct
# reading of the same source shape, so both are fixtures.)
_LET_OPEN_RE = re.compile(r"\(\s*let\b")

# Heads that introduce no bindings at all — the ordinary control flow that sits
# between a `let` and the `throw` it guards. Crossing one cannot change what a
# symbol means. Deliberately short: every addition is a promise about a macro's
# binding behaviour, and the cost of leaving one out is one honest red.
_TRANSPARENT_HEADS = frozenset({
    "do", "throw", "if", "if-not", "when", "when-not", "cond", "condp", "case",
    "try", "finally", "and", "or", "not", "->", "->>", "some->", "some->>",
    "doto",
})

# Heads that bind through a LEADING vector. Crossing one is proven safe exactly
# when that vector does not mention the symbol — AND neither does anything
# between the head and it. The whole vector is checked, not just its name
# positions: `for`/`doseq` interleave `:let`/`:when` modifiers and `let` names
# can be destructuring forms, so "mentioned anywhere in the binding vector" is
# the reading that cannot miss a binder. A value-position mention costs a red,
# which is the safe direction. The text BEFORE the vector is read the same way,
# because `fn` binds there too: `(fn msg [x] …)` binds `msg` to the function
# itself, and a proof that reads only the parameter vector greens a genuine
# shadow (rf2-u3otj, the #7064 audit).
_VECTOR_BINDER_HEADS = frozenset({
    "let", "loop", "fn", "if-let", "when-let", "if-some", "when-some",
    "when-first", "doseq", "for", "dotimes", "with-open", "with-local-vars",
    "with-redefs", "binding", "letfn",
})

# The head token of a `(head …)` form.
_FORM_HEAD_RE = re.compile(r"\(\s*([^\s()\[\]{}\"'`~@^;,]+)")

# A bare local symbol as the WHOLE message argument: no parens, no quotes, no
# `/` (a namespaced var is not a local binding), not a keyword, not a number.
_BARE_LOCAL_SYM_RE = re.compile(r"^[a-zA-Z*+!?<>=_-][\w*+!?<>=.-]*$")


def _balanced_extent(text: str, open_idx: int) -> int | None:
    """The index just past the delimiter matching the opener at `open_idx`, or
    None if the form is unterminated. String-aware (the caller passes
    comment-masked text, so a `(` inside a sentence cannot open a form)."""
    n = len(text)
    i = open_idx
    depth = 0
    in_string = False
    while i < n:
        c = text[i]
        if in_string:
            if c == "\\" and i + 1 < n:
                i += 2
                continue
            if c == '"':
                in_string = False
            i += 1
            continue
        if c == '"':
            in_string = True
        elif c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return None


def _split_forms(body: str) -> list[str]:
    """Every top-level form in `body`, in order — `_split_first_arg` applied
    until the body is consumed."""
    forms: list[str] = []
    rest = _clj_strip(body)
    while rest:
        first, rest = _split_first_arg(rest)
        if not first:
            break
        forms.append(first)
    return forms


def _mentions_symbol(text: str, sym: str) -> bool:
    """Does `sym` occur in `text` as a standalone symbol token? The boundary
    classes keep `msg` out of `:msg`, `'msg`, `msg-2`, `ns/msg` and `a.msg`,
    while letting it match inside a destructuring form (`{:keys [msg]}`)."""
    return bool(
        re.search(
            r"(?<![\w*+!?<>=./:'#-])" + re.escape(sym) + r"(?![\w*+!?<>=./-])",
            text,
        )
    )


def _enclosing_openers(masked: str, start: int, offset: int) -> list[int]:
    """The indices of the delimiters still OPEN at `offset`, among those opened
    at or after `start` — outermost first. String-aware, same convention as
    `_balanced_extent` (the caller passes comment-masked text)."""
    stack: list[int] = []
    i = start
    in_string = False
    while i < offset:
        c = masked[i]
        if in_string:
            if c == "\\":
                i += 2
                continue
            if c == '"':
                in_string = False
            i += 1
            continue
        if c == '"':
            in_string = True
        elif c in "([{":
            stack.append(i)
        elif c in ")]}":
            if stack:
                stack.pop()
        i += 1
    return stack


def _binder_vector(masked: str, open_idx: int, offset: int) -> tuple[str | None, list[str]]:
    """The BINDING VECTOR of the form opened at `open_idx` — its first
    direct-child vector, as text — together with the direct children that
    PRECEDE it (the head first). `(None, …)` when there is no such vector.

    Only the text before `offset` is read: every binding vector precedes the
    body the `ex-info` sits in, and the trailing child is the (truncated) one
    we are descending into, which `_split_forms` returns whole and we ignore.

    The PREFIX is returned because a binding vector is not always the only
    place a binder introduces a name: `fn` takes an optional self-reference
    name between the head and the parameters."""
    prefix: list[str] = []
    for form in _split_forms(masked[open_idx + 1:offset]):
        if form.startswith("["):
            return form, prefix
        prefix.append(form)
    return None, prefix


def _crossing_is_transparent(masked: str, open_idx: int, offset: int, sym: str) -> bool:
    """Can the form opened at `open_idx` be PROVEN not to rebind `sym`?

    Fail-closed: anything this gate does not recognise answers False, so an
    unknown binding macro costs an honest red rather than a silent green."""
    if masked[open_idx] != "(":
        return True  # a vector / map / set literal binds nothing
    # A reader conditional (`#?(:clj …)` / `#?@(…)`) is a branch selector, not a
    # binder; its head is a platform keyword, so it needs saying explicitly.
    if masked[max(0, open_idx - 3):open_idx].endswith(("#?", "#?@")):
        return True
    m = _FORM_HEAD_RE.match(masked, open_idx)
    if not m:
        return False
    head = m.group(1)
    if head in _TRANSPARENT_HEADS:
        return True
    if head in _VECTOR_BINDER_HEADS:
        vec, before = _binder_vector(masked, open_idx, offset)
        # A binder whose binding vector we cannot see (an `fn` written as arity
        # lists, a malformed form) proves nothing.
        if vec is None or _mentions_symbol(vec, sym):
            return False
        # …and neither does anything BETWEEN the head and that vector. `fn`
        # takes an optional SELF-REFERENCE NAME there — `(fn msg [x] …)` binds
        # `msg` to the function itself, shadowing an outer `msg` exactly as a
        # parameter would. Reading only the parameter vector greened it
        # (rf2-u3otj, the #7064 audit). `before[0]` is the head, which binds
        # nothing; everything after it must be clean.
        return not any(_mentions_symbol(f, sym) for f in before[1:])
    return False


def _scope_is_provable(masked: str, let_open: int, offset: int, sym: str) -> bool:
    """Is the `let` at `let_open` PROVABLY the binding of `sym` in scope at
    `offset` — i.e. does every form crossed on the way down introduce nothing
    named `sym`?"""
    return all(
        _crossing_is_transparent(masked, d, offset, sym)
        for d in _enclosing_openers(masked, let_open, offset)
        if d != let_open
    )


def _resolve_let_binding(masked: str, offset: int, sym: str) -> str | None:
    """The form `sym` is bound to by the innermost `let` whose form ENCLOSES
    `offset`, or None if no enclosing `let` binds it — or if some binder
    between that `let` and `offset` may shadow it.

    Shadowing resolves the way Clojure resolves it: among enclosing `let`s the
    innermost wins (they nest, so the latest start offset is the innermost),
    and within one binding vector the LAST binding of the symbol wins. A
    symbol bound only inside a destructuring form resolves nothing — the
    binding NAME must be exactly `sym`.

    A NON-`let` binder in between (an `fn` parameter, an `fn` self-reference
    name, a `catch` name, a destructured inner `let`, …) makes the resolution
    unsound, so it returns None and the caller keeps the finding (rf2-u3otj /
    the #7045 and #7064 audits)."""
    bound: str | None = None
    bound_at: int | None = None
    for m in _LET_OPEN_RE.finditer(masked, 0, offset):
        end = _balanced_extent(masked, m.start())
        if end is None or end <= offset:
            continue  # a sibling `let`, not an enclosing one
        vec_start = masked.find("[", m.end())
        # The binding vector must follow `let` immediately — only whitespace
        # between, or this is not the vector we think it is.
        if vec_start == -1 or masked[m.end():vec_start].strip():
            continue
        vec_end = _balanced_extent(masked, vec_start)
        if vec_end is None:
            continue
        forms = _split_forms(masked[vec_start + 1:vec_end - 1])
        for name, value in zip(forms[0::2], forms[1::2]):
            if name == sym:
                bound = value
                bound_at = m.start()
    if bound_at is None:
        return None
    # The innermost `let` binding the name is the only candidate; if it cannot
    # be proven to be the one in scope, nothing further out can be either.
    if not _scope_is_provable(masked, bound_at, offset, sym):
        return None
    return bound

# The inline opt-out marker for a DELIBERATE, sanctioned builder-bypass — a
# message that intentionally stays bare human prose WITHOUT the `[:rf.<ns>/…]`
# token. A handful of sites need this: the conformance-DSL `:throw` / `:count`
# ops surface a FIXTURE-supplied / builtin message downstream as the
# sub-exception trace's `:exception-message`, and the conformance corpus PINS
# that exact text — so the message must stay the prose, not carry the token
# (the canonical `:rf.error/id` still rides on ex-data for branching). A site
# carrying this marker on the `ex-info` line (or the line just above) is
# exempt; a NEW accidental bypass has no marker and fails the gate. The reagent-
# slim adapter does NOT need the marker — it embeds the token inline, so it is
# conformant under the token rule, not an exemption.
_BYPASS_OK_MARKER = "rf2:builder-bypass-ok"

# How many lines ABOVE the `ex-info` line the marker comment may sit (a
# multi-line rationale block precedes some exemptions). Small + bounded so the
# marker stays tied to its `ex-info`, never a distant unrelated comment.
_MARKER_LOOKBACK = 4


def _scan_builder_bypass(path: Path, masked: str, raw_lines: list[str]) -> list["Finding"]:
    """Find raw `(ex-info <msg> {… :rf.error/id …})` whose message bypasses the
    canonical builder (no `[:rf.<ns>/…]` token, not a `human-message` call).

    A DELIBERATE exemption opts out with a `rf2:builder-bypass-ok` comment on
    the `ex-info` line or the line directly above it."""
    findings: list[Finding] = []
    for m in _EX_INFO_OPEN_RE.finditer(masked):
        body = _extract_ex_info_form(masked, m.start())
        if body is None:
            continue
        first_arg, rest = _split_first_arg(body)
        # Only framework errors: the ex-data must carry the :rf.error/id slot.
        if not _HAS_ERROR_ID_RE.search(rest):
            continue
        # Conformant messages do not fire: a central `human-message` derivation,
        # or a message already embedding the `[:rf.<ns>/…]` greppability token
        # (the reagent-slim inline shape).
        if _message_is_conformant(first_arg):
            continue
        # …or a bare local symbol whose `let` binding is conformant — the token
        # lives one hop away (rf2-u3otj). Same predicates on the bound form, so
        # a let-bound message WITHOUT a token still fires.
        if _BARE_LOCAL_SYM_RE.match(first_arg):
            bound = _resolve_let_binding(masked, m.start(), first_arg)
            if bound is not None and _message_is_conformant(bound):
                continue
        line_no = _line_of_offset(masked, m.start())
        # A deliberate, documented exemption opts out via the marker comment on
        # the `ex-info` line or within the small comment block directly above it
        # (we scan the RAW lines — the marker lives in a `;`-comment, which the
        # masked text blanks). The window covers a multi-line rationale comment.
        lo = max(0, line_no - 1 - _MARKER_LOOKBACK)
        marker_window = raw_lines[lo:line_no]  # the ex-info line + lookback above
        if any(_BYPASS_OK_MARKER in ln for ln in marker_window):
            continue
        snippet = raw_lines[line_no - 1].strip() if 0 <= line_no - 1 < len(raw_lines) else ""
        findings.append(Finding(path, line_no, "builder-bypass-message", snippet))
    return findings


class Finding(NamedTuple):
    path: Path
    line: int
    kind: str
    snippet: str
    # The WHERE-SYM findings below carry the offending symbol here. The bare
    # keyword / builder-bypass kinds have no such datum and leave it empty.
    # A default keeps every existing construction site a 4-tuple.
    detail: str = ""


# --------------------------------------------------------------------------
# THE WHERE-SYM RULE (rf2-z5lv) — the door the message sends the author to
# must EXIST
# --------------------------------------------------------------------------
#
# `thrown-ex-info` / `throw-error!` take a `where-sym` as their SECOND
# positional argument and land it in the ex-data `:where` slot. Its whole job,
# in `re-frame.error`'s own words, is that "a grep-for-symbol lands on the call
# site" — so an author reads the thrown message, types the symbol, and expects
# to find something. When the symbol names nothing that resolves, the message
# sends them to a door that is not there.
#
# Three beads have now paid for that one site at a time: rf2-z67m fixed seven,
# rf2-0v23 an eighth, and a sweep for rf2-z5lv measured twenty-one more. This
# section is what stops the twenty-second being written.
#
# THE RULE IS RESOLVABILITY, NOT A SPELLING. `re-frame.core`'s own
# `not-queryable-kinds` map writes `re-frame.flows/flows` fully qualified
# BESIDE `rf/frame-ids` under the façade alias, in the same map, and both are
# right — the discriminator is whether the façade exports the name, not how the
# symbol is spelled. So a check that demanded fully-qualified symbols would be
# wrong about live, correct code. What is asserted here is only that the symbol
# NAMES SOMETHING REACHABLE.
#
# THE ORACLE IS THE PUBLIC VAR SET, NOT `spec/api-manifest.edn`. The manifest
# rows DOCUMENTED publics, so a `^:no-doc` façade export resolves perfectly and
# carries no row: measured on trunk, `re-frame.core` has 71 manifest rows
# against 82 JVM `ns-publics` (84 counting the two `#?(:cljs …)` arms), and
# `reset-frame!` / `reload-images!` — live `^:no-doc` façade stubs — carry zero
# rows apiece. A manifest-only oracle therefore reports non-resolving sites that
# resolve. The manifest is used here for something it IS authoritative about:
# see `_manifest_rows` and `oracle_problems` below, where it is the independent
# second instrument that catches this one going BLIND — and only blind. Being a
# subset test it cannot see the oracle going GENEROUS, which is the direction
# that turns a dead door green; the self-test's exact-set assertion owns that.
#
# WHICH RUNTIME. The public set is read from SOURCE, across BOTH reader-
# conditional arms, so a name public under `:clj` OR `:cljs` resolves. That is a
# deliberate union and it is measured, not assumed: `rf/frame-provider` and
# `rf/frame-root` are `#?(:cljs (def …))` façade exports, absent from the JVM's
# `ns-publics` and present in every CLJS build, and a JVM-only oracle would
# report both as dead doors. The union is also the only direction that cannot
# manufacture a false red on a legitimately platform-specific name. It does
# leave one honest gap — a where-sym naming a CLJS-only var thrown from a
# JVM-only path is a dead door for a JVM reader and passes here — which is a
# bound worth stating rather than a defect worth machinery: the name still
# exists in the tree, so the grep the where-sym exists to serve still lands.

# The forms that take a where-sym. A ROSTER, because there is no textual
# property that separates "an argument that is a where-sym" from "an argument
# that is a quoted symbol"; the self-test holds every entry to owning a fixture,
# the way `_STR_ERR_VAR_NAMES` and `_VECTOR_BINDER_HEADS` already are. A missing
# entry costs a MISSED site, so `:min-where-syms` in the baseline is what keeps
# that from being silent.
#
# The where-sym is taken as THE QUOTED-SYMBOL ARGUMENT rather than by index,
# which is why one roster serves forms whose where-sym sits in different
# positions (`throw-error!` second, `throw-inline-interceptor-removed!` first).
# Measured over the whole scan surface: 267 rostered calls, 180 with exactly one
# quoted-symbol argument and 87 with none — never two. A nested quoted symbol
# (inside an `:extra` map, say) is invisible to this, because only a TOP-LEVEL
# argument that is EXACTLY a quoted symbol is read.
_WHERE_SYM_FORMS: tuple[str, ...] = (
    "throw-error!",
    "thrown-ex-info",
    "throw-inline-interceptor-removed!",
)

# `\b` IS THE WRONG GUARD HERE AND FAILS SILENTLY. Every builder but one ends
# in `!`, and `!` is not a word character — so `throw-error!\b` requires a word
# character immediately after the `!`, which never occurs, and the pattern
# matches NOTHING while looking exactly like a pattern that works. Measured on a
# two-call sample: the `\b` form read 0, this one read 2. A trailing negative
# lookahead over the symbol-constituent class is the correct guard.
_SYM_TAIL = r"(?![\w*+!?<>=-])"
# `[\s,]*`, NOT `\s*`: a comma is reader whitespace, so `(,throw-error! …)`
# is the same call. `\s*` there made a real where-sym vanish — see the
# READER WHITESPACE section above. `_extract_call_body` and the self-test's
# `_crosses_call_head` build the same head pattern and carry the same class;
# all three must agree, or a site is matched here and dropped there.
_WHERE_CALL_RE = re.compile(
    r"\(" + _CLJ_WS + r"*(?:[\w.*+!?<>=-]+/)?("
    + "|".join(re.escape(f) for f in _WHERE_SYM_FORMS)
    + r")" + _SYM_TAIL
)

# A Clojure symbol, and the quoted symbol that is a where-sym. The `/` is a
# SEPARATE alternation rather than a member of the character class: putting it
# in the class would let `a/b/c` through, and leaving it out of the pattern
# altogether — which is the mistake that made a first pass of this scan report
# two where-syms where there are 193 — matches only the unqualified ones.
_CLJ_SYM = r"[A-Za-z0-9_.*+!?<>=&$%|-]+"
_QUOTED_SYM_RE = re.compile(r"^'(" + _CLJ_SYM + r"(?:/" + _CLJ_SYM + r")?)$")

# `:where <form>` inside an ex-data map. The canonical builders write this slot
# themselves, so it is only READ here for the raw `(ex-info … {… :rf.error/id …
# :where '<sym> …})` sites that build their payload by hand. Those are invisible
# to the builder scan above and are not a corner: six of the façade-family
# findings on trunk are `:where`-slot sites in `re-frame.conformance`.
_WHERE_KEY_RE = re.compile(r":where" + _SYM_TAIL)

# THE CANONICAL ALIAS DIALECT, from `spec/Conventions.md` §Require-alias dialect
# (the same mapping `scripts/check_require_alias_dialect.py` ratchets the tree
# onto): `re-frame.core` takes the bare root alias `rf`, every other framework
# namespace takes `rf.<full dotted tail>`. A where-sym is USER-FACING text — it
# tells an author what to type — so it is resolved against that one public
# dialect, never against the aliases of the file it happens to be written in.
_ROOT_ALIAS = "rf"
_ROOT_NS = "re-frame.core"
_FRAMEWORK_NS_PREFIX = "re-frame"


def _namespace_of_where_sym(qualifier: str) -> str:
    """The namespace a where-sym's QUALIFIER names, under the canonical dialect."""
    if qualifier == _ROOT_ALIAS:
        return _ROOT_NS
    if qualifier.startswith(_ROOT_ALIAS + "."):
        return _FRAMEWORK_NS_PREFIX + "." + qualifier[len(_ROOT_ALIAS) + 1:]
    return qualifier


def _is_framework_family(qualifier: str, namespace: str) -> bool:
    """Is this where-sym one the FRAMEWORK owns — so a namespace that does not
    exist is a finding rather than an unknowable third-party reference?"""
    return (
        qualifier == _ROOT_ALIAS
        or qualifier.startswith(_ROOT_ALIAS + ".")
        or qualifier.startswith(_ROOT_ALIAS + "-")
        or namespace == _FRAMEWORK_NS_PREFIX
        or namespace.startswith(_FRAMEWORK_NS_PREFIX + ".")
    )


# --------------------------------------------------------------------------
# The oracle: a namespace's PUBLIC VAR set, read statically from source
# --------------------------------------------------------------------------
#
# WHY STATIC. The truth is `ns-publics`, and this gate cannot call it: it runs
# as a bare `python scripts/…` step in the PR spine, in ~1s, with no JVM and no
# shadow-cljs compile (CLJS has no runtime `ns-publics` at all — the manifest
# generator's own CLJS probe has to read the ANALYZER's compilation env at
# compile time). So the public set is derived from source text, and the design
# question is not "is a static parse exact?" — it is "what catches it when it
# stops being?". Two things do, and they fail in opposite directions:
#
#   * the parse going BLIND fails CLOSED and loudly: publics vanish, so
#     where-syms that used to resolve stop resolving and the gate reds naming
#     each one. There is no silent-zero direction here. `oracle_problems` below
#     is the guard for it: `spec/api-manifest.edn` is generated from live
#     `ns-publics` by a different tool on a different runtime, so every rowed
#     var MUST appear in the derived set. It is a strict subset (the manifest
#     drops `^:no-doc`), which is exactly what makes it usable as a floor.
#     Measured on trunk: 472 rows across 48 namespaces, all present. That
#     control has already earned its place — it caught this parser missing
#     `(import-fn …)` re-exports in `re-frame.ssr.ring` and `(rf/reg-view Panel
#     …)` component vars across eleven Xray namespaces, both of which would
#     otherwise have produced confident, well-formed FALSE findings.
#   * the parse going TOO GENEROUS is the dangerous direction, AND NOTHING
#     ABOVE GUARDS IT. This comment used to claim the manifest did, and that
#     claim is what audit #9501 refuted: a SUBSET test detects public names
#     MISSING from the derived set and is structurally blind to EXTRA
#     fictitious ones. It is the wrong-direction instrument, and saying so
#     matters more than the defect it missed, because a stated guard is a
#     reason not to look for a real one.
#     What guards this direction is the exact-set assertion in
#     `_run_where_sym_self_tests`, over an oracle fixture that defines names
#     inside a `comment`, a `#_` discard, a quote and a syntax-quote and
#     nowhere else. `comment` shipped in `_TRANSPARENT_DEF_WRAPPERS` beside
#     `do` and the walk descended through the reader prefixes, so all four
#     were read as live publics — every one a dead door the gate blessed.
#
# THE RULE, NOT A ROSTER: a top-level form whose head — after any namespace
# qualifier — begins `def` defines its first non-metadata symbol argument.
# `defn-` and any other `def…-` spelling is private by convention, `^:private`
# / `^{:private true}` is private by declaration, and `defmethod` extends a
# multimethod rather than defining a var. That rule covers `def`, `defn`,
# `defmacro`, `defmulti`, `defrecord`, `defprotocol` and — the reason it is a
# rule and not a list — this tree's own var-defining macros
# (`defreg-macro reg-sub`, `defreg-event-macro reg-event`, `defwrapper`), which
# a list would have missed and which supply 27 of the façade's 84 publics.
# `_EXTRA_DEF_HEADS` carries the two var-defining macros that do NOT begin
# `def`; the manifest control above is what says when a third one appears.
_EXTRA_DEF_HEADS = frozenset({"reg-view", "import-fn"})

_FORM_HEAD_TOKEN_RE = re.compile(
    r"\(" + _CLJ_WS + r"*(:?[A-Za-z0-9_.*+!?<>=&$%|'/-]+)"
)
_PRIVATE_META_RE = re.compile(r"\^\s*(?::private\b|\{[^{}]*:private\s+true)")
# A reader-conditional arm head (`#?(:clj …)`) and the ONE transparent wrapper a
# top-level def hides behind. `#?(:cljs (def frame-root …))` is how the façade
# ships its two CLJS-only exports, so a scan that does not descend here reports
# them as dead doors — the exact false red this section exists to avoid.
_PLATFORM_KEYWORD_RE = re.compile(r"^:(clj|cljs|cljr|default)$")
# `comment` SHIPPED HERE BESIDE `do` AND IT IS NOT A TRANSPARENT WRAPPER
# (audit #9501). `(comment …)` evaluates to nil and interns nothing, so a
# `(comment (defn ghost …))` contributed `ghost` to the public set and a
# where-sym naming a var that exists only inside a comment resolved as a live
# door. `do` is the real one — it evaluates its body, so the `def` inside
# really does intern — and removing `comment` must not cost it, which is what
# the fixture's `(do (defn do-defined-public …))` control pins.
_TRANSPARENT_DEF_WRAPPERS = frozenset({"do"})
_NS_FORM_RE = re.compile(
    r"\(\s*ns\s+(?:\^\{[^{}]*\}\s+|\^[\w:.-]+\s+)*(" + _CLJ_SYM + r")"
)


# THE PREFIX AND ITS FORM ARE ONE UNIT. A first repair (PR #9511) decided
# inertness by looking back ONE character from the `(`, and audit #9511
# reproduced three shapes it cannot see, each of which interns nothing and each
# of which turned a dead door green:
#
#   * `#_#?(:clj (defn ghost …))` — the character before the `(` is `?`, so the
#     discard is never reached and the conditional's arms are walked as live;
#   * `'#?(:clj (defn ghost …))` — the same, through a quote;
#   * `#_#_ (def ignored 1) (defn ghost …)` — a STACKED discard, which look-back
#     cannot reach in principle: the second discard's target sits AFTER a form
#     the walker has already stepped past, so there is no prefix behind it.
#
# Reader-conditionals are the reason this matters more than the shapes it
# replaces: `#?` is ordinary `.cljc` and this tree is full of it.
#
# AND THE MANIFEST CONTROL CANNOT REACH ANY OF IT. `oracle_problems` is a
# SUBSET test, so it detects public names the parser has STOPPED seeing, never
# fictitious ones it has STARTED inventing. What guards this direction is the
# exact-set assertion in `_run_where_sym_self_tests`, over an oracle fixture
# that defines each of these names behind its prefix and nowhere else.
#
# The fix is to read forward the way the READER does. `#_` does not decorate
# the next balanced list — it consumes the next DATUM, whatever that datum is
# spelled as, and a discard met while reading that datum is itself consumed
# first, which is exactly why `#_#_ a b` neutralises BOTH. `_datum_end` is that
# rule and nothing more; it answers `#_#?`, `'#?`, `` `#? ``, `#_#?@`, `#_'`
# and any further stacking with the same six lines.
#
# Reader prefixes that DECORATE the datum after them, longest-first so `#?@` is
# never read as `#?`. `#_` is deliberately absent: a discard consumes its datum
# rather than decorating it, and that difference is the stacked-discard case.
_READER_PREFIXES: tuple[str, ...] = ("#?@", "#?", "#'", "~@", "'", "`", "~", "@")


def _string_end(text: str, open_idx: int) -> int:
    """The index just past the string literal opening at `open_idx`."""
    n = len(text)
    i = open_idx + 1
    while i < n:
        if text[i] == "\\":
            i += 2
            continue
        if text[i] == '"':
            return i + 1
        i += 1
    return n


def _at_datum_start(text: str, i: int) -> bool:
    """Is `i` the START of a datum rather than a character inside a token?

    `'` is BOTH the quote prefix and a legal symbol constituent (`foo'`), and
    `\\'` is the character literal — the reader trap that once swallowed half a
    file. Either read as a prefix would swallow the form after it, so a quote
    counts only where a datum could actually begin.
    """
    return i == 0 or _is_clj_ws(text[i - 1]) or text[i - 1] in "()[]{}"


def _skip_reader_discards(text: str, i: int) -> int:
    """Advance past reader whitespace and every complete `#_ <datum>` at `i`.

    STACKED DISCARDS FALL OUT OF THE RECURSION: reading the datum a `#_`
    discards goes through `_datum_end`, which skips a nested discard on its way
    to a datum — so `#_#_ a b` consumes `a` while reading the inner discard's
    target and then `b` as the outer one's, and neither reaches the caller.
    """
    n = len(text)
    while True:
        while i < n and _is_clj_ws(text[i]):
            i += 1
        if not text.startswith("#_", i):
            return i
        i = _datum_end(text, i + 2)


def _datum_end(text: str, i: int) -> int:
    """The index just past the next complete DATUM at or after `i`.

    A miniature reader, not a full one — it answers only "where does this datum
    end", which is all a walk deciding what to DESCEND INTO needs. It is
    string-aware through `_balanced_extent` and `_string_end`, prefix-aware
    through `_READER_PREFIXES`, and discard-aware through
    `_skip_reader_discards`.
    """
    n = len(text)
    i = _skip_reader_discards(text, i)
    if i >= n:
        return n
    for prefix in _READER_PREFIXES:
        if text.startswith(prefix, i):
            return _datum_end(text, i + len(prefix))
    c = text[i]
    if c == "^":
        # Metadata is read, then the datum it decorates.
        return _datum_end(text, _datum_end(text, i + 1))
    if c == "#":
        if i + 1 < n and (text[i + 1].isalpha() or text[i + 1] == ":"):
            # A tagged literal — `#js {…}`, `#inst "…"` — is tag then datum.
            return _datum_end(text, _datum_end(text, i + 1))
        # `#(`, `#{`, `#"…"`: dispatch on whatever follows.
        return _datum_end(text, i + 1)
    if c in "([{":
        end = _balanced_extent(text, i)
        return n if end is None else end
    if c == '"':
        return _string_end(text, i)
    j = i
    while j < n and not _is_clj_ws(text[j]) and text[j] not in '()[]{}"':
        j += 1
    return j if j > i else i + 1


def _top_level_forms(masked: str) -> Iterable[str]:
    """Every balanced top-level `( … )` form in comment-masked text, MINUS the
    ones the READER neutralises — see the prefix note above.

    DELIBERATELY NOT A FULL READER. Anything that is not a top-level `(`, a
    discard run or a quote is stepped over one character at a time, exactly as
    before, because this walker also serves `do` bodies and reader-conditional
    ARMS (`_public_names_defined_by`), where the leading `:clj` / `:cljs`
    keyword has to be walked past rather than parsed.
    """
    i = 0
    n = len(masked)
    while i < n:
        # Reader whitespace and `#_ <datum>` runs — stacked ones included —
        # vanish here, whatever the discarded datum turns out to be.
        after_noise = _skip_reader_discards(masked, i)
        if after_noise != i:
            i = after_noise
            continue
        c = masked[i]
        if c in "'`" and _at_datum_start(masked, i):
            # Quoted and syntax-quoted data define nothing, however the datum
            # is spelled: `'(defn …)`, `'#?(:clj (defn …))`, `` `#?(…) ``.
            i = _datum_end(masked, i)
            continue
        if masked.startswith("#?@", i):
            # Splicing is a reader ERROR at the top level, so it interns
            # nothing; consume it rather than descending into its arms. Inside
            # a collection it is legal, and that path is unchanged — the arms
            # are reached through the enclosing form.
            i = _datum_end(masked, i)
            continue
        if c == "(":
            end = _balanced_extent(masked, i)
            if end is None:
                return
            # `#?(…)` reaches here with the `#?` stepped over, which is what
            # keeps a LIVE reader-conditional's arms descendable: the façade
            # ships `rf/frame-root` as `#?(:cljs (def frame-root …))`.
            yield masked[i:end]
            i = end
            continue
        i += 1


def _public_names_defined_by(form: str) -> list[str]:
    """The PUBLIC var names a top-level form defines (see the rule above)."""
    m = _FORM_HEAD_TOKEN_RE.match(form)
    if not m:
        return []
    raw_head = m.group(1)
    head = raw_head.rsplit("/", 1)[-1]
    # The form's closing paren must come off, or a single-argument form's
    # only argument is returned WITH it and matches no symbol pattern. That
    # is not hypothetical: it is what hid every `(import-fn …)` re-export.
    body = form[m.end():-1]
    if _PLATFORM_KEYWORD_RE.match(raw_head):
        return [n for f in _top_level_forms(form[1:-1])
                for n in _public_names_defined_by(f)]
    if head in _TRANSPARENT_DEF_WRAPPERS:
        return [n for f in _top_level_forms(body)
                for n in _public_names_defined_by(f)]
    if head == "import-fn":
        # `(import-fn <ns>/<name>)` interns `<name>` in THIS namespace.
        forms = _split_forms(body)
        if forms and re.fullmatch(_CLJ_SYM + r"(?:/" + _CLJ_SYM + r")?", forms[0]):
            return [forms[0].rsplit("/", 1)[-1]]
        return []
    defines = (
        head.startswith("def") and not head.endswith("-") and head != "defmethod"
    ) or head in _EXTRA_DEF_HEADS
    if not defines:
        return []
    for arg in _split_forms(body):
        if arg.startswith("^"):
            if _PRIVATE_META_RE.search(arg):
                return []
            continue
        return [arg] if re.fullmatch(_CLJ_SYM, arg) else []
    return []


def _namespace_and_publics(path: Path) -> tuple[str | None, set[str]]:
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None, set()
    masked = "\n".join(_mask_comments(text))
    ns_match = _NS_FORM_RE.search(masked)
    names: set[str] = set()
    for form in _top_level_forms(masked):
        names.update(_public_names_defined_by(form))
    return (ns_match.group(1) if ns_match else None), names


class PublicVarIndex:
    """`namespace -> public var names`, resolved lazily by the standard
    `re-frame.core -> re_frame/core.clj[cs]` munge.

    LAZY BY MUNGED PATH, not an eager index of the tree. Eagerly reading all
    2820 tracked Clojure sources to learn their `ns` forms took 21s; resolving
    only the dozen-odd namespaces the where-syms actually name takes 0.03s to
    prepare and reads one file per namespace. The whole point of a gate hosted
    in the always-on PR spine is that it costs nothing to run.
    """

    def __init__(self, source_paths: Iterable[Path]):
        self._by_stem: dict[str, list[Path]] = {}
        for path in source_paths:
            posix = path.as_posix()
            for suffix in _SOURCE_SUFFIXES:
                if posix.endswith(suffix):
                    self._by_stem.setdefault(
                        posix[:-len(suffix)], []
                    ).append(path)
                    break
        self._cache: dict[str, set[str] | None] = {}

    def files_for(self, namespace: str) -> list[Path]:
        stem = namespace.replace("-", "_").replace(".", "/")
        return [
            path
            for key, paths in self._by_stem.items()
            if key == stem or key.endswith("/" + stem)
            for path in paths
        ]

    def publics_of(self, namespace: str) -> set[str] | None:
        """The namespace's public var names, or None when this tree defines no
        such namespace. None is a DIFFERENT ANSWER from the empty set, and
        conflating them is how a scan reports a confident clean sweep over a
        namespace it never opened."""
        if namespace in self._cache:
            return self._cache[namespace]
        paths = self.files_for(namespace)
        names: set[str] | None = None
        for path in paths:
            found_ns, publics = _namespace_and_publics(path)
            if found_ns != namespace:
                continue
            names = (names or set()) | publics
        self._cache[namespace] = names
        return names


class OracleUnavailable(Exception):
    """The tree the public-var oracle needs could not be enumerated."""


def _tracked_clojure_sources(repo_root: Path) -> list[Path]:
    """Every git-tracked Clojure source in the repository.

    GIT-TRACKED, not walked. An untracked scratch namespace must not be able to
    make a where-sym resolve, and a built checkout carries tens of thousands of
    compiler-output files a walk would have to prune. `scripts/_test_fixtures/`
    is excluded for the reason its siblings state: it is where every checker in
    this directory plants the defect it exists to catch, so a fixture's
    deliberately-broken namespace is not a public surface.

    An empty result is an ERROR, never an empty oracle. Recognising nothing and
    finding nothing are different outcomes, and only one of them is a clean
    tree — with an empty oracle EVERY where-sym would be reported as a dead
    door, which is loud, but the diagnosis would name the wrong thing.
    """
    import subprocess
    try:
        out = subprocess.run(
            ["git", "ls-files", "--", "*.clj", "*.cljc", "*.cljs"],
            cwd=repo_root, capture_output=True, text=True, check=True,
        ).stdout
    except (OSError, subprocess.CalledProcessError) as exc:
        raise OracleUnavailable(
            f"cannot enumerate git-tracked Clojure sources under {repo_root}: "
            f"{exc}. The where-sym rule resolves a symbol against the public "
            "vars of the namespace it names, which needs the tracked tree."
        ) from exc
    paths = [
        repo_root / rel
        for rel in out.split("\n")
        if rel and not rel.startswith("scripts/_test_fixtures/")
    ]
    if not paths:
        raise OracleUnavailable(
            f"no git-tracked Clojure sources under {repo_root}. The where-sym "
            "oracle would be empty, which is not the same as a clean tree."
        )
    return paths


_MANIFEST_REL = "spec/api-manifest.edn"
_MANIFEST_ROW_RE = re.compile(r':namespace "([^"]+)", :var "([^"]+)"')


def _manifest_rows(repo_root: Path) -> dict[str, set[str]]:
    """`namespace -> rowed var names` from the GENERATED public-API manifest."""
    text = (repo_root / _MANIFEST_REL).read_text(encoding="utf-8", errors="replace")
    rows: dict[str, set[str]] = {}
    for namespace, var in _MANIFEST_ROW_RE.findall(text):
        rows.setdefault(namespace, set()).add(var)
    return rows


def oracle_problems(index: PublicVarIndex, rows: dict[str, set[str]]) -> list[str]:
    """Every way the derived public-var oracle contradicts the manifest.

    The manifest is generated from LIVE `ns-publics` by a different tool on a
    different runtime, minus the `^:no-doc` carve-out — so it is a strict SUBSET
    of the truth this parser derives, and any rowed var missing from the derived
    set means the parser has stopped seeing a whole shape of definition. That is
    the failure that would otherwise arrive as confident FALSE findings, so it
    is an rc=2 'this instrument is not usable', never a finding.

    IT GUARDS ONE DIRECTION ONLY, and the header comment above used to say
    otherwise. Being a SUBSET test, it detects public names MISSING from the
    derived set; an EXTRA fictitious one — a name the parser invents, which is
    how a where-sym pointing at a var that exists only inside a `(comment …)`
    came to resolve — adds no row and contradicts nothing here. Nothing in this
    function can see that. `_run_where_sym_self_tests`' exact-set assertion is
    what does.
    """
    problems: list[str] = []
    for namespace in sorted(rows):
        publics = index.publics_of(namespace)
        if publics is None:
            problems.append(
                f"{namespace}: the manifest rows this namespace but no source "
                "file defines it — the oracle cannot see the tree it is "
                "grading."
            )
            continue
        missing = sorted(rows[namespace] - publics)
        if missing:
            problems.append(
                f"{namespace}: manifest-rowed public var(s) the derived oracle "
                f"does not see: {', '.join(missing)}. A var-defining form this "
                "parser does not recognise has appeared (see "
                "`_public_names_defined_by`); until it does, every where-sym "
                "naming one of these would be reported as a dead door."
            )
    return problems


def _mask_reader_noise(text: str) -> str:
    """Blank `;` comments AND string CONTENTS in one reader-faithful pass,
    length-preserving, keeping the quote characters so forms still balance.

    THE WHERE-SYM RULE NEEDS ITS OWN MASK, and cannot layer on `_mask_comments`
    — that function deliberately leaves string contents visible (pattern (a)
    MUST see a literal `":rf.error/…"` message), and it treats a CHARACTER
    LITERAL's payload as ordinary text. Both matter here:

      * a where-sym is CODE and can never live inside a string, while this
        repo's docstrings quote builder calls constantly — `re-frame.error`'s
        own docstring writes ``:where 'rf/reg-event`` — so leaving strings
        visible reports the DOCUMENTATION of this contract as violations of it;
      * `\\"` outside a string is the character `"`, not a delimiter. Missing
        that does not fail locally: it desynchronises from the reader and
        swallows THE REST OF THE FILE. Measured on
        `re-frame.ssr.html-helpers`, which writes `(= c \\")` at :310 — read as
        a quote, every `throw-error!` after it vanished, and the file's two
        real where-syms reported as zero. It fails in the REASSURING direction,
        because a scan that has gone blind reports FEWER findings, which reads
        as progress.

    `#"…"` regex literals are strings for this purpose and need no special
    case; a `;` inside a string is not a comment, which is why one pass has to
    do both jobs rather than two passes doing one each.
    """
    out = list(text)
    i = 0
    n = len(text)
    in_string = False
    while i < n:
        c = text[i]
        if in_string:
            if c == "\\" and i + 1 < n:
                # An escape pair inside a string is content: blank it as a
                # unit so `\"` cannot be read as the closing delimiter.
                out[i] = " "
                out[i + 1] = " "
                i += 2
                continue
            if c == '"':
                in_string = False
            elif c != "\n":
                out[i] = " "
            i += 1
            continue
        if c == "\\":
            i += 2          # a character literal: `\"`, `\;`, `\newline`, …
            continue
        if c == ";":
            j = text.find("\n", i)
            end = n if j == -1 else j
            for k in range(i, end):
                out[k] = " "
            i = end
            continue
        if c == '"':
            in_string = True
        i += 1
    return "".join(out)


class WhereSym(NamedTuple):
    path: Path
    line: int
    symbol: str
    source: str  # "builder" | "where-slot"
    snippet: str = ""


def _extract_call_body(text: str, open_paren_idx: int, head: str) -> str | None:
    """The BODY of the `(<head> …)` form opening at `open_paren_idx` — the text
    between the head token and the matching close paren. String-aware; the
    caller passes comment-masked text."""
    n = len(text)
    i = open_paren_idx
    depth = 0
    in_string = False
    start_body = None
    head_re = re.compile(
        r"\(" + _CLJ_WS + r"*(?:[\w.*+!?<>=-]+/)?" + re.escape(head) + _SYM_TAIL
    )
    while i < n:
        c = text[i]
        if in_string:
            if c == "\\" and i + 1 < n:
                i += 2
                continue
            if c == '"':
                in_string = False
            i += 1
            continue
        if c == '"':
            in_string = True
            i += 1
            continue
        if c == "(":
            depth += 1
            if depth == 1:
                m = head_re.match(text, i)
                if not m:
                    return None
                start_body = m.end()
            i += 1
            continue
        if c == ")":
            depth -= 1
            if depth == 0:
                return text[start_body:i] if start_body is not None else None
            i += 1
            continue
        i += 1
    return None


def _scan_where_syms(path: Path, text: str, raw_lines: list[str]) -> list[WhereSym]:
    """Every QUOTED where-sym in the file, from both places one can be written:
    a rostered builder call's quoted-symbol argument, and the `:where` slot of a
    hand-built framework ex-data map.

    A where-sym that is NOT a quoted symbol — a bare local (`where`,
    `where-sym`) threaded through a helper, a keyword, a computed form — is not
    returned at all. Nothing static can say what it will be at runtime, and
    guessing would trade this gate's false reds for false greens. `--verbose`
    reports how many were skipped so the silence is visible.
    """
    # This rule masks the RAW text itself rather than reusing the shared
    # comment mask — see `_mask_reader_noise` for the two reasons, both of
    # which produced measured wrong answers on live source.
    masked = _mask_reader_noise(text)

    def snippet(line_no: int) -> str:
        return raw_lines[line_no - 1].strip() if 0 <= line_no - 1 < len(raw_lines) else ""

    found: list[WhereSym] = []
    for m in _WHERE_CALL_RE.finditer(masked):
        body = _extract_call_body(masked, m.start(), m.group(1))
        if body is None:
            continue
        line_no = _line_of_offset(masked, m.start())
        for arg in _split_forms(body):
            q = _QUOTED_SYM_RE.match(arg)
            if q:
                found.append(
                    WhereSym(path, line_no, q.group(1), "builder", snippet(line_no))
                )
    for m in _EX_INFO_OPEN_RE.finditer(masked):
        body = _extract_ex_info_form(masked, m.start())
        if body is None:
            continue
        _, rest = _split_first_arg(body)
        if not _HAS_ERROR_ID_RE.search(rest):
            continue
        key = _WHERE_KEY_RE.search(rest)
        if not key:
            continue
        value, _ = _split_first_arg(rest[key.end():])
        q = _QUOTED_SYM_RE.match(value)
        if q:
            line_no = _line_of_offset(masked, m.start())
            found.append(
                WhereSym(path, line_no, q.group(1), "where-slot", snippet(line_no))
            )
    return found


def where_sym_findings(
    observed: list[WhereSym], index: PublicVarIndex
) -> list[Finding]:
    """The where-syms that name no reachable var."""
    findings: list[Finding] = []
    for site in observed:
        if "/" not in site.symbol:
            # An UNQUALIFIED where-sym names no namespace to check it against,
            # and inventing one would be guessing. Two exist on trunk
            # (`'defwrapper`, `'defreg-macro`); both name a macro a reader can
            # still grep for. Counted, never a finding.
            continue
        qualifier, name = site.symbol.rsplit("/", 1)
        namespace = _namespace_of_where_sym(qualifier)
        publics = index.publics_of(namespace)
        if publics is None:
            if _is_framework_family(qualifier, namespace):
                findings.append(Finding(
                    site.path, site.line, "where-sym-unknown-namespace",
                    site.snippet, site.symbol,
                ))
            # A genuinely third-party namespace cannot be checked from this
            # tree; saying so is honest where a green would not be.
            continue
        if name not in publics:
            findings.append(Finding(
                site.path, site.line, "where-sym-unresolvable",
                site.snippet, site.symbol,
            ))
    return sorted(findings, key=lambda f: (str(f.path), f.line, f.detail))


_WHERE_SYM_KINDS = ("where-sym-unresolvable", "where-sym-unknown-namespace")


# --------------------------------------------------------------------------
# The where-sym BASELINE — a per-symbol floor, not a zero gate
# --------------------------------------------------------------------------
#
# Eighty-two where-syms on trunk name nothing that resolves. A check that
# failed them all on the day it landed would red every open PR, so this is a
# RATCHET on the shape `scripts/require-alias-baseline.edn` already established
# here: `:sites` records what each offending symbol is allowed to carry, a
# symbol absent from the file has a floor of ZERO (so a NEW dead door reds
# immediately, which is the whole point), and a recorded floor may only fall.
#
# Recording a symbol is NOT a ruling that it should be fixed, and disposing of
# one is another item's work — the sweep that measured them says outright that
# every site needs reading before acting. What the file guarantees is only that
# the number cannot grow.
#
# THE TWO KEYS RATCHET IN OPPOSITE DIRECTIONS, and reading it as one rule gets
# half the file exactly wrong — the trap `check_require_alias_dialect.py` paid
# for twice and wrote down:
#
#   * a `:sites` floor is an ALLOWANCE, checked `found > floor -> fail`. Raising
#     it loosens the gate. It may only FALL.
#   * `:min-where-syms` is the ANTI-BLINDNESS guard, checked
#     `observed < min -> unusable`. LOWERING it loosens the gate — the inverse.
#     It may only RISE.
#
# `:min-where-syms` is the answer to this gate's own worst failure mode. Every
# finding here is the output of a search, and a search that has quietly stopped
# matching reports a clean sweep in the same words as a clean tree. If the
# extraction goes blind — a builder renamed out of `_WHERE_SYM_FORMS`, a
# character class that stops covering a spelling — the observed population
# collapses, and this floor turns that from a green into an rc=2 that says so.
# A proposed INCREASE to any site floor refuses the whole write rather than
# being dropped from it: a partial file matches neither the tree nor the
# previous baseline, and nothing in it records which happened. Deliberately
# raising a floor stays possible and stays a HAND EDIT, which is visible in
# review.

WHERE_SYM_BASELINE_REL = "scripts/where-sym-baseline.edn"


class WhereSymBaseline(NamedTuple):
    min_where_syms: int
    sites: dict[str, int]


class BaselineError(Exception):
    pass


def parse_where_sym_baseline(text: str) -> WhereSymBaseline:
    """Read the baseline EDN. Deliberately strict: anything this reader does
    not consume is an ERROR, not an ignored key. A baseline half-read is a floor
    half-applied, and that fails in the direction that admits a regression."""
    masked = "\n".join(_mask_comments(text))
    start = masked.find("{")
    if start == -1:
        raise BaselineError("no map in the baseline file")
    end = _balanced_extent(masked, start)
    if end is None:
        raise BaselineError("the baseline map does not balance")
    tokens = _split_forms(masked[start + 1:end - 1])
    if len(tokens) % 2 != 0:
        raise BaselineError("the baseline map is not a sequence of key/value pairs")
    min_where_syms: int | None = None
    sites: dict[str, int] | None = None
    for key, value in zip(tokens[0::2], tokens[1::2]):
        if key == ":min-where-syms":
            if not value.isdigit():
                raise BaselineError(
                    f":min-where-syms must be a non-negative integer, got {value!r}"
                )
            min_where_syms = int(value)
        elif key == ":sites":
            if not value.startswith("{"):
                raise BaselineError(":sites must be a map")
            inner = _split_forms(value[1:-1])
            if len(inner) % 2 != 0:
                raise BaselineError(":sites is not a sequence of key/value pairs")
            sites = {}
            for sym, floor in zip(inner[0::2], inner[1::2]):
                if not (sym.startswith('"') and sym.endswith('"') and len(sym) >= 2):
                    raise BaselineError(f"site keys must be strings, got {sym!r}")
                if not floor.isdigit():
                    raise BaselineError(
                        f"site {sym} floor must be a non-negative integer, got {floor!r}"
                    )
                sites[sym[1:-1]] = int(floor)
        else:
            raise BaselineError(f"unrecognised baseline key {key!r}")
    if min_where_syms is None:
        raise BaselineError("the baseline names no :min-where-syms floor")
    if sites is None:
        raise BaselineError("the baseline names no :sites map")
    return WhereSymBaseline(min_where_syms, sites)


class WhereSymBaselinePlan(NamedTuple):
    baseline: WhereSymBaseline
    notes: list[str]
    refusals: list[str]   # non-empty: write NOTHING


def plan_where_sym_baseline(
    counts: dict[str, int], total_observed: int,
    previous: WhereSymBaseline | None,
) -> WhereSymBaselinePlan:
    """The baseline `--write-where-sym-baseline` may write, given the one on
    disk. Monotonic in both keys, in opposite directions (see the block above)."""
    notes: list[str] = []
    refusals: list[str] = []
    if previous is None:
        return WhereSymBaselinePlan(
            WhereSymBaseline(int(total_observed * 0.9), dict(sorted(counts.items()))),
            [f"no readable baseline on disk: writing a fresh one "
             f"({len(counts)} symbol(s), {sum(counts.values())} site(s))."],
            [],
        )
    floors: dict[str, int] = {}
    for symbol in sorted(counts):
        found = counts[symbol]
        old = previous.sites.get(symbol)
        if old is None:
            floors[symbol] = found
            notes.append(
                f"NEW dead door {symbol}: floor set to its observed {found}. "
                "This is the one path by which debt enters the file."
            )
        elif found > old:
            floors[symbol] = old
            refusals.append(
                f"{symbol}: {found} site(s) against a floor of {old} — writing "
                f"it would RAISE the floor by {found - old} and bless the "
                "regression."
            )
        else:
            if found < old:
                notes.append(f"{symbol}: floor {old} -> {found}.")
            floors[symbol] = found
    for symbol in sorted(set(previous.sites) - set(counts)):
        notes.append(
            f"{symbol} resolves everywhere now: its floor of "
            f"{previous.sites[symbol]} is dropped."
        )
    recomputed = int(total_observed * 0.9)
    if recomputed >= previous.min_where_syms:
        min_where_syms = recomputed
    else:
        min_where_syms = previous.min_where_syms
        notes.append(
            f":min-where-syms HELD at {previous.min_where_syms} (the recompute "
            f"proposed {recomputed}). It is the anti-blindness guard, not an "
            "allowance: lowering it LOOSENS the gate, so a shrinking population "
            "is a thing to investigate by hand, never to write down."
        )
    return WhereSymBaselinePlan(
        WhereSymBaseline(min_where_syms, floors), notes, refusals
    )


def render_where_sym_baseline(baseline: WhereSymBaseline) -> str:
    width = max((len(s) for s in baseline.sites), default=0) + 4
    lines = [
        ";; Thrown-error where-sym baseline (rf2-z5lv) — regenerate with",
        ";;     python scripts/check_thrown_error_messages.py "
        "--write-where-sym-baseline",
        ";;",
        ";; A thrown error's `where-sym` names WHERE the failure happened so an",
        ";; author who reads the message can grep for it. `:sites` records the",
        ";; symbols that currently name nothing reachable, and how many sites",
        ";; each is allowed. A symbol NOT listed has a floor of zero, so a new",
        ";; dead door fails the gate. A listed floor ratchets DOWN only; the",
        ";; gate reports any symbol now below its floor so the number can be",
        ";; lowered, and reports one that has cleared entirely so the entry can",
        ";; be dropped. Recording a symbol is not a ruling that it must be",
        ";; fixed — each site needs reading before acting.",
        ";;",
        ";; `:min-where-syms` is a floor on the TOTAL quoted where-syms the scan",
        ";; observes. It is not an allowance: it is what stops this gate",
        ";; reporting a confident clean sweep after its extraction has gone",
        ";; blind. It ratchets the OTHER way — up only.",
        "{:min-where-syms " + str(baseline.min_where_syms),
        "",
        " :sites",
        " {",
    ]
    for symbol in sorted(baseline.sites):
        lines.append(f'  {("%s" % (chr(34) + symbol + chr(34))).ljust(width)}'
                     f'{baseline.sites[symbol]}')
    lines.append(" }}")
    return "\n".join(lines) + "\n"


class WhereSymVerdict(NamedTuple):
    violations: list[str]   # non-empty: the gate is RED
    notes: list[str]        # ratchet-down opportunities; never fatal
    unusable: list[str]     # non-empty: rc=2, the instrument cannot be trusted


def grade_where_syms(
    findings: list[Finding], total_observed: int, baseline: WhereSymBaseline,
) -> WhereSymVerdict:
    counts: dict[str, int] = {}
    for f in findings:
        counts[f.detail] = counts.get(f.detail, 0) + 1
    violations: list[str] = []
    notes: list[str] = []
    unusable: list[str] = []
    if total_observed < baseline.min_where_syms:
        unusable.append(
            f"only {total_observed} quoted where-sym(s) observed, against a "
            f"`:min-where-syms` floor of {baseline.min_where_syms}. This gate "
            "does not report a clean sweep over a corpus it has stopped being "
            "able to read: either the extraction has gone blind (a builder "
            "missing from `_WHERE_SYM_FORMS`, a pattern that no longer matches "
            "a live spelling) or the scan surface shrank. Investigate before "
            "touching the baseline — lowering the floor is how this guard gets "
            "given away."
        )
    for symbol in sorted(counts):
        floor = baseline.sites.get(symbol, 0)
        if counts[symbol] > floor:
            violations.append(
                f"{symbol}: {counts[symbol]} site(s) against a floor of {floor}"
            )
    for symbol in sorted(baseline.sites):
        found = counts.get(symbol, 0)
        if found < baseline.sites[symbol]:
            notes.append(
                f"{symbol}: {found} site(s) now, floor {baseline.sites[symbol]} — "
                + ("drop the entry" if found == 0 else "lower the floor")
                + f" in {WHERE_SYM_BASELINE_REL}."
            )
    return WhereSymVerdict(violations, notes, unusable)


# --------------------------------------------------------------------------
# Clojure-comment masking
# --------------------------------------------------------------------------
#
# `(ex-info (str error-kw) …)` and `(ex-info ":rf.error/…")` appear in PROSE:
# the `re-frame.error` rationale comment literally quotes the retired
# `(ex-info (str error-kw) …)` shape, and docstrings reference
# `:rf.error/…` ex-infos. Those are documentation, not reintroduced code.
#
# We mask `;`-to-EOL comments (length-preserving). We DO NOT mask "..." string
# literals — pattern (a) MUST see the `":rf.error/…"` literal message string,
# which is itself a string literal. A `:rf.error/…` mention inside an unrelated
# docstring cannot match any pattern (none of the patterns match a `:rf.*`
# keyword that is not the immediate `ex-info` first argument), so leaving
# string contents visible is safe — and (a) requires it. Multi-line docstrings
# that happen to contain the literal text `(ex-info ":rf.error/…"` would be a
# false positive, but that exact byte sequence does not occur in any docstring
# (a docstring describing the OLD shape spells it `(ex-info (str error-kw) …)`,
# which is a `;;` comment in re-frame.error and is masked).

_LINE_COMMENT_RE = re.compile(r";.*$")


def _mask_comments(text: str) -> list[str]:
    """Return per-line content with `;`-to-EOL comments blanked.

    Length-preserving so 1-based line numbers and reported snippets line up
    with the source. A `;` inside a "..." string literal is NOT a comment, so
    we track string state to avoid blanking a `;` that lives in a string (rare,
    but e.g. a reason sentence with a semicolon). Backslash-escaped quotes do
    not toggle string state.
    """
    masked: list[str] = []
    in_string = False
    for raw in text.splitlines():
        out = []
        i = 0
        n = len(raw)
        while i < n:
            c = raw[i]
            if in_string:
                if c == "\\" and i + 1 < n:
                    out.append(raw[i:i + 2])
                    i += 2
                    continue
                if c == '"':
                    in_string = False
                out.append(c)
                i += 1
                continue
            if c == '"':
                in_string = True
                out.append(c)
                i += 1
                continue
            if c == ";":
                # Comment to EOL — blank length-preserving and stop the line.
                out.append(" " * (n - i))
                i = n
                continue
            out.append(c)
            i += 1
        masked.append("".join(out))
    return masked


# --------------------------------------------------------------------------
# File iteration
# --------------------------------------------------------------------------


def _iter_source_files(scan_root: Path, include_tests: bool) -> Iterable[Path]:
    """Yield framework source files under scan_root.

    A FILE root yields just that file — the shape the fixture self-tests use
    (`scan(<one .cljc fixture>)`). It is deliberately NOT reachable from the
    CLI: `main` requires every `--scan-dir` to be a directory (rf2-un9fk), so
    a file passed there is rejected with rc=2 rather than quietly scanning
    one file — or, before that fix, zero.

    PRUNED, not filtered-after (rf2-76c76; method proven by rf2-e1xx0 in
    `check_retired_image_keys.py`). `_EXCLUDE_DIR_NAMES` is dropped from
    `os.walk`'s dirnames IN PLACE, so a built checkout never descends into
    `implementation/.shadow-cljs` (34.8k entries), `out` (9.7k) or
    `node_modules` (3.4k). `rglob("*")` enumerated all 51.6k entries under
    `implementation/` — the whole scan root — and discarded them one at a
    time: 3.2s of this gate's 3.4s wall clock on a built tree.

    The surviving sequence is IDENTICAL, set and order: pruning drops only
    what the `_EXCLUDE_DIR_NAMES` test below already dropped, and the
    collected matches go through ONE GLOBAL `sorted()`, reproducing
    `sorted(rglob("*"))`'s whole-subtree ordering rather than os.walk's
    directory-grouped order."""
    if scan_root.is_file():
        if scan_root.suffix in _SOURCE_SUFFIXES:
            yield scan_root
        return
    scan_prefix_len = len(scan_root.as_posix()) + 1
    matches: list[Path] = []
    for dirpath, dirnames, filenames in os.walk(scan_root):
        dirnames[:] = [d for d in dirnames if d not in _EXCLUDE_DIR_NAMES]
        for name in filenames:
            if os.path.splitext(name)[1] in _SOURCE_SUFFIXES:
                matches.append(Path(dirpath) / name)
    for path in sorted(matches):
        # Kept as the belt to the pruning's braces, and now on string ops:
        # `Path.relative_to` was pure pathlib object churn for a prefix strip.
        parts = set(path.as_posix()[scan_prefix_len:].split("/"))
        if parts & _EXCLUDE_DIR_NAMES:
            continue
        if not include_tests and (parts & _TEST_DIR_NAMES):
            continue
        yield path


# --------------------------------------------------------------------------
# Per-file scan
# --------------------------------------------------------------------------
#
# The four patterns can span lines (a builder call wrapped across newlines), so
# we run them over the WHOLE masked text and map each match back to its 1-based
# line number. `(ex-info\n  ":rf.error/…"` is a real shape (most converted /
# unconverted sites put the message on the line after `(ex-info`), so a
# line-local scan would miss them.


def _line_of_offset(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def _scan_text(
    path: Path, text: str, where_syms: list[WhereSym] | None = None,
) -> list[Finding]:
    masked = "\n".join(_mask_comments(text))
    raw_lines = text.splitlines()
    # The where-sym extraction rides the SAME masked text as everything else,
    # so it costs one pass rather than a second read of the tree. Collected
    # rather than graded here: a where-sym is judged against the public-var
    # oracle and the baseline, which are whole-run facts, not per-file ones.
    if where_syms is not None:
        where_syms.extend(_scan_where_syms(path, text, raw_lines))

    def raw_snippet(line_no: int) -> str:
        return raw_lines[line_no - 1].strip() if 0 <= line_no - 1 < len(raw_lines) else ""

    findings: list[Finding] = []
    for kind, pat in _PATTERNS:
        for m in pat.finditer(masked):
            line_no = _line_of_offset(masked, m.start())
            findings.append(Finding(path, line_no, kind, raw_snippet(line_no)))
    # The widened builder-BYPASS rule (rf2-krrv87): a raw `(ex-info …)` whose
    # ex-data carries `:rf.error/id` but whose message skips the builder/token.
    bypass = _scan_builder_bypass(path, masked, raw_lines)
    # A bare-keyword site (one of the four strict patterns) is ALSO a builder
    # bypass; report it once under the more specific bare-keyword kind. So drop
    # a bypass finding when the SAME line already has a more specific finding.
    bare_lines = {(f.path, f.line) for f in findings}
    findings.extend(f for f in bypass if (f.path, f.line) not in bare_lines)
    # De-dup (a single site cannot match two patterns, but guard anyway).
    seen = set()
    unique: list[Finding] = []
    for f in findings:
        key = (f.path, f.line, f.kind)
        if key not in seen:
            seen.add(key)
            unique.append(f)
    return sorted(unique, key=lambda f: (str(f.path), f.line))


def scan(
    scan_root: Path, include_tests: bool = False,
    where_syms: list[WhereSym] | None = None,
) -> list[Finding]:
    """Every message-shape finding under `scan_root`.

    `where_syms`, when supplied, also collects every quoted where-sym observed.
    It is an out-parameter rather than a second return value so the ~30 existing
    fixture cases keep calling `scan(path)` and reading a plain list."""
    findings: list[Finding] = []
    for path in _iter_source_files(scan_root, include_tests):
        text = path.read_text(encoding="utf-8", errors="replace")
        findings.extend(_scan_text(path, text, where_syms))
    return findings


# --------------------------------------------------------------------------
# Reporting
# --------------------------------------------------------------------------

_FIX_HINT = (
    "These sites bypass the canonical thrown-error builder (Spec 009 §The "
    "thrown-error shape) — either a BARE `:rf.error/…` keyword message (the OLD "
    "shape rf2-vvixub abolished) or a `builder-bypass-message`: a raw "
    "`(ex-info …)` whose ex-data carries `:rf.error/id` but whose message skips "
    "the builder + the `[:rf.<ns>/<id>]` token (rf2-krrv87). Route each through "
    "the canonical builder so the message LEADS with a human sentence and "
    "TRAILS with the `[:rf.<ns>/<id>]` token:\n"
    "  * an artefact that can `:require re-frame.error`:\n"
    "      (error/throw-error! :rf.error/<id> 'rf/<where> \"<human sentence>\" {:extra {...}})\n"
    "    or build-without-throwing via `error/thrown-ex-info`; a shared-payload\n"
    "    throw-and-emit site uses `error/ex-info-from-data`. A per-surface\n"
    "    helper (flow-error / route-error / registration-error / …) should\n"
    "    DELEGATE its message to `error/thrown-ex-info` rather than\n"
    "    `(ex-info (str error-id) …)`.\n"
    "  * the bundle-isolated reagent-slim adapter (cannot :require\n"
    "    re-frame.error): hand-roll the same shape inline —\n"
    "      (ex-info (str \"<human sentence> [\" :rf.error/<id> \"]\") {:rf.error/id … :reason … …})\n"
    "  * a message DELIBERATELY pinned to bare prose downstream (conformance-DSL\n"
    "    :throw / :count) opts out with a `rf2:builder-bypass-ok` marker comment.\n"
    "  The `:reason` sentence usually already exists in the ex-data; reuse it."
)


_WHERE_SYM_FIX_HINT = (
    "A thrown error's where-sym is the second positional argument of "
    "`error/throw-error!` / `error/thrown-ex-info` (and the `:where` slot it "
    "lands in). Its ONE job is that an author who reads the message can grep "
    "for the symbol and land on something — so it must NAME A REACHABLE VAR.\n"
    "  * The rule is RESOLVABILITY, not a spelling. `rf/reg-flow` and\n"
    "    `re-frame.flows/flows` are BOTH right — `re-frame.core`'s own\n"
    "    `not-queryable-kinds` map writes one of each, side by side — because\n"
    "    the discriminator is whether the namespace exports the name.\n"
    "  * So the fix is whichever of these is true of the site:\n"
    "      - the façade DOES export it: keep `'rf/<name>`;\n"
    "      - the owning namespace exports it but the façade does not: spell it\n"
    "        fully, `'re-frame.<ns>/<name>` (or `'rf.<tail>/<name>` under the\n"
    "        Conventions §Require-alias dialect);\n"
    "      - nothing exports it (it is private, or it is a MODULE name rather\n"
    "        than a fn): name the nearest PUBLIC entry point the author would\n"
    "        actually call.\n"
    "  * A private var does not resolve for a reader either — a fully-qualified\n"
    "    `'re-frame.router/build-envelope` still fails, because that var is\n"
    "    `defn-`.\n"
    "  Recorded, already-known dead doors live in " + WHERE_SYM_BASELINE_REL +
    "; that file\n"
    "  ratchets DOWN only, so clearing one is welcome and raising a floor is a\n"
    "  hand edit that shows up in review."
)


def _report_where_syms(
    findings: list[Finding], baseline: WhereSymBaseline,
    verdict: WhereSymVerdict, repo_root: Path,
) -> None:
    over = {v.split(":")[0] for v in verdict.violations}
    sys.stderr.write(
        f"\n{len(verdict.violations)} thrown-error where-sym(s) name nothing "
        "reachable, above the recorded floor (rf2-z5lv / Spec 009 §The "
        "thrown-error shape):\n\n"
    )
    for line in verdict.violations:
        sys.stderr.write(f"  {line}\n")
    sys.stderr.write("\n")
    for f in findings:
        if f.detail not in over:
            continue
        try:
            rel = f.path.relative_to(repo_root)
        except ValueError:
            rel = f.path
        rel_str = str(rel).replace("\\", "/")
        sys.stderr.write(f"  {f.kind}: {rel_str}:{f.line}  '{f.detail}\n")
        sys.stderr.write(f"      {f.snippet}\n")
    sys.stderr.write(f"\nFix:\n  {_WHERE_SYM_FIX_HINT}\n")


def _write_where_sym_baseline(
    repo_root: Path, findings: list[Finding], total_observed: int,
    previous: WhereSymBaseline | None,
) -> int:
    counts: dict[str, int] = {}
    for f in findings:
        counts[f.detail] = counts.get(f.detail, 0) + 1
    plan = plan_where_sym_baseline(counts, total_observed, previous)
    if plan.refusals:
        sys.stderr.write(
            "\nrefusing to write the where-sym baseline — every one of these "
            "would RAISE a floor:\n\n"
        )
        for refusal in plan.refusals:
            sys.stderr.write(f"  {refusal}\n")
        sys.stderr.write(
            "\nA symbol above its floor means the gate is ALREADY RED, so "
            "there is no state in which recording another symbol's win is the "
            "next thing to do. Fix the new site, or hand-edit the EDN if the "
            "raise is deliberate.\n"
        )
        return 1
    (repo_root / WHERE_SYM_BASELINE_REL).write_text(
        render_where_sym_baseline(plan.baseline), encoding="utf-8"
    )
    sys.stderr.write(f"wrote {WHERE_SYM_BASELINE_REL}\n")
    for note in plan.notes:
        sys.stderr.write(f"  {note}\n")
    sys.stderr.write(
        f"  :min-where-syms {plan.baseline.min_where_syms}, "
        f"{len(plan.baseline.sites)} symbol(s), "
        f"{sum(plan.baseline.sites.values())} site(s).\n"
    )
    return 0


def _report(findings: list[Finding], repo_root: Path) -> None:
    sys.stderr.write(
        f"\n{len(findings)} bare-keyword thrown-error message(s) found in "
        "framework source (rf2-vvixub / Spec 009 §The thrown-error shape):\n\n"
    )
    for f in findings:
        try:
            rel = f.path.relative_to(repo_root)
        except ValueError:
            rel = f.path
        rel_str = str(rel).replace("\\", "/")
        sys.stderr.write(f"  {f.kind}: {rel_str}:{f.line}\n      {f.snippet}\n")
    sys.stderr.write(f"\nFix:\n  {_FIX_HINT}\n")


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "rf2-vvixub / rf2-krrv87: fail on a framework `(ex-info …)` whose "
            "message is a bare `:rf.*` discriminator keyword (the retired "
            "keyword-only shape) OR a builder bypass — a raw ex-info carrying "
            "`:rf.error/id` whose message skips the builder + the "
            "`[:rf.<ns>/<id>]` token. rf2-z5lv: also fail on a thrown error "
            "whose WHERE-SYM names no reachable var, above the floor recorded "
            "in " + WHERE_SYM_BASELINE_REL + "."
        ),
    )
    parser.add_argument(
        "--repo-root",
        default=None,
        help="Path to the repo root. Defaults to the script's grandparent.",
    )
    parser.add_argument(
        "--scan-dir",
        action="append",
        default=None,
        dest="scan_dirs",
        help=(
            "Directory (relative to repo-root) to scan. Repeatable. Must be a "
            "DIRECTORY — a missing path or an existing file exits 2 rather "
            "than scanning nothing and reporting success (rf2-un9fk). "
            "Defaults to the rostered framework-source tree(s): "
            f"{', '.join(DEFAULT_SCAN_DIRS)}."
        ),
    )
    parser.add_argument(
        "--include-tests",
        action="store_true",
        help=(
            "Scan test/ trees too. Off by default — a test may construct a "
            "bare-keyword ex-info to exercise a predicate."
        ),
    )
    parser.add_argument(
        "--verbose", "-v", action="store_true", help="Print progress to stderr."
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help=(
            "Run the bundled fixture-based self-tests in "
            "scripts/_test_fixtures/check_thrown_error_messages/ and exit."
        ),
    )
    parser.add_argument(
        "--write-where-sym-baseline",
        action="store_true",
        help=(
            "Rewrite " + WHERE_SYM_BASELINE_REL + " from the current tree. "
            "MONOTONIC, and the two keys ratchet OPPOSITE ways: a site floor "
            "may only fall and :min-where-syms may only rise, so a proposed "
            "increase to any site floor refuses the whole write rather than "
            "blessing the regression."
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
            "(no mkdocs.yml). Pass --repo-root explicitly.\n"
        )
        return 2

    scan_roots = [repo_root / d for d in (args.scan_dirs or DEFAULT_SCAN_DIRS)]

    # A rostered tree that has been renamed or deleted is NOT skipped. Skipping
    # is how a declared scope quietly narrows again — the gate would report
    # success for a tree it never opened, which is the defect class this
    # roster exists to close. Same posture as the sibling ratchet and the
    # test-lane bijection gate's phantom-path rule.
    #
    # `is_dir`, NOT `exists` (rf2-un9fk). An EXISTING REGULAR FILE passed the
    # old `exists()` test, and `_iter_source_files`'s `rglob` over a file
    # yields nothing — so `--scan-dir README.md` scanned ZERO files and
    # reported success. That is the same false green the missing-path check
    # exists to prevent, one step further in: a gate that CANNOT RUN must
    # exit non-zero, never green over the work it did not do. Both invalid
    # shapes take the same rc=2 posture; the diagnostic names which it was so
    # a typo'd path and a file-for-directory are told apart at a glance.
    invalid = [r for r in scan_roots if not r.is_dir()]
    if invalid:
        for root in invalid:
            why = (
                "does not exist"
                if not root.exists()
                else "is not a directory (a scan root must be a directory; "
                     "scanning it would silently cover zero files)"
            )
            sys.stderr.write(f"error: scan dir {root} {why}.\n")
        return 2

    if args.verbose:
        n = sum(
            1
            for root in scan_roots
            for _ in _iter_source_files(root, args.include_tests)
        )
        rels = ", ".join(
            str(r.relative_to(repo_root)).replace("\\", "/") for r in scan_roots
        )
        sys.stderr.write(
            f"scanning {n} framework source file(s) under {rels} "
            f"(tests {'included' if args.include_tests else 'excluded'})...\n"
        )

    observed: list[WhereSym] = []
    findings: list[Finding] = []
    for root in scan_roots:
        findings.extend(
            scan(root, include_tests=args.include_tests, where_syms=observed)
        )

    # ---- the where-sym rule (rf2-z5lv) ------------------------------------
    #
    # Its oracle is the PUBLIC VAR SET of the namespace a where-sym names, so
    # it needs the whole tree rather than the scan surface: a where-sym written
    # under `implementation/` can name a namespace living anywhere.
    #
    # SKIPPED WHOLESALE UNDER `--scan-dir`, and that is the only honest option.
    # Both halves of the rule are defined over the ROSTERED surface: the site
    # floors record what that surface carries, and `:min-where-syms` is a floor
    # on what that surface yields. Point the scan somewhere else and the floor
    # fires on a corpus that is smaller for a legitimate reason, while grading
    # findings WITHOUT the floor would drop the one guard that separates "no
    # dead doors here" from "this scan stopped seeing them" — the confident
    # zero this whole gate is built to refuse. So an ad-hoc surface gets the
    # message rules and says plainly that it got nothing else.
    if args.scan_dirs:
        if findings:
            _report(findings, repo_root)
            return 1
        if args.verbose:
            sys.stderr.write(
                "no bare-keyword thrown-error messages under the requested "
                "scan dir(s). The where-sym rule was SKIPPED: its baseline and "
                f"its `:min-where-syms` floor both describe the rostered "
                f"surface ({', '.join(DEFAULT_SCAN_DIRS)}), so neither is "
                "meaningful here.\n"
            )
        return 0

    try:
        index = PublicVarIndex(_tracked_clojure_sources(repo_root))
    except OracleUnavailable as exc:
        sys.stderr.write(f"error: {exc}\n")
        return 2
    try:
        rows = _manifest_rows(repo_root)
    except OSError as exc:
        sys.stderr.write(
            f"error: cannot read {_MANIFEST_REL}: {exc}. It is this gate's "
            "independent control on its own public-var oracle; without it the "
            "where-sym rule cannot be trusted.\n"
        )
        return 2
    unusable = oracle_problems(index, rows)
    if unusable:
        sys.stderr.write(
            "\nerror: the where-sym public-var oracle disagrees with the "
            f"GENERATED {_MANIFEST_REL}, so it is not usable:\n\n"
        )
        for problem in unusable:
            sys.stderr.write(f"  {problem}\n")
        return 2

    ws_findings = where_sym_findings(observed, index)
    baseline: WhereSymBaseline | None = None
    try:
        baseline = parse_where_sym_baseline(
            (repo_root / WHERE_SYM_BASELINE_REL).read_text(encoding="utf-8")
        )
    except (OSError, BaselineError) as exc:
        # A MALFORMED baseline is fatal in both modes — half-read is a floor
        # half-applied. A MISSING one is fatal only when gating: the write mode
        # has to be able to create the file the first time.
        if not (args.write_where_sym_baseline and isinstance(exc, OSError)):
            sys.stderr.write(
                f"error: cannot read {WHERE_SYM_BASELINE_REL}: {exc}\n"
            )
            return 2

    if args.write_where_sym_baseline:
        return _write_where_sym_baseline(
            repo_root, ws_findings, len(observed), baseline
        )

    verdict = grade_where_syms(ws_findings, len(observed), baseline)
    if verdict.unusable:
        sys.stderr.write("\nerror: the where-sym scan is not usable:\n\n")
        for problem in verdict.unusable:
            sys.stderr.write(f"  {problem}\n")
        return 2

    if args.verbose:
        qualified = sum(1 for w in observed if "/" in w.symbol)
        sys.stderr.write(
            f"where-sym: {len(observed)} quoted where-sym(s) observed "
            f"({qualified} qualified, {len(observed) - qualified} unqualified "
            f"and therefore uncheckable), floor {baseline.min_where_syms}; "
            f"{len(ws_findings)} name nothing reachable, "
            f"{len(baseline.sites)} symbol(s) baselined.\n"
        )
        for note in verdict.notes:
            sys.stderr.write(f"  RATCHET DOWN: {note}\n")

    # A where-sym over its floor is reported with the same weight as a bad
    # message: both are the thrown-error shape failing its reader.
    if verdict.violations:
        _report_where_syms(ws_findings, baseline, verdict, repo_root)
    if findings:
        _report(findings, repo_root)
    if findings or verdict.violations:
        return 1
    if args.verbose:
        sys.stderr.write(
            "no bare-keyword thrown-error messages in framework source, and "
            "every where-sym over its baseline floor names a reachable var.\n"
        )
    return 0


# --------------------------------------------------------------------------
# Self-tests (fixture-driven) — prove the gate FIRES on each bad shape and
# stays GREEN on every conformant counterpart.
# --------------------------------------------------------------------------

_SELF_TEST_FIXTURE_ROOT = (
    Path(__file__).resolve().parent
    / "_test_fixtures"
    / "check_thrown_error_messages"
)


# A planted site NAMES ITSELF in its ex-data. Every fixture already wrote a
# distinct `:rf.error/id` on its `ex-info` line, which is the line a finding is
# reported at — so the witness name was sitting there unread while two fixtures
# aggregated seventeen sites under two counts (rf2-n6ijg).
_WITNESS_ID_RE = re.compile(r":rf\.error/id\s+(:[\w.*+!?<>=/-]+)")


def _witness_of(lines: list[str], fixture: str, line_no: int) -> str:
    """The name of the site reported at `line_no`: its own `:rf.error/id` when
    the ex-data opens on that line, else the fixture itself.

    The fallback is what makes single-site fixtures need no ceremony — the file
    IS the witness. It is also self-policing: two unnamed sites in one file
    collapse onto the same name, and the distinctness check below reds.
    """
    raw = lines[line_no - 1] if 0 <= line_no - 1 < len(lines) else ""
    m = _WITNESS_ID_RE.search(raw)
    return m.group(1) if m else f"<{fixture}>"


# Which fixture proves each roster, and (for the negative direction, where there
# is no finding to read a name off) which entries it is claimed to cross.
_BINDER_HEAD_FIXTURE = "negative/binder_heads_crossed_cleanly.cljc"
_TRANSPARENT_HEAD_FIXTURE = "negative/transparent_heads_crossed.cljc"


def _run_self_tests(verbose: bool = False) -> int:
    """Scan each fixture and assert the EXACT set of sites that fired.

    Counts were already exact. The hole was that a count cannot NAME a witness
    (rf2-n6ijg): `bypass_let_bound_shadowed.cljc` plants eleven distinct binder-
    family sites and `bypass_let_bound_no_token.cljc` six, and the whole proof
    was the pair `(file, 11)` and `(file, 6)`. A dead site red anonymously —
    "expected 11, got 10", with no way to say which — and any edit that made a
    neighbouring site fire twice restored the count and greened it.

    Each site is now asserted by NAME, read from the `:rf.error/id` it already
    carried. Plus the two structural assertions:

      * every `_VECTOR_BINDER_HEADS` and `_TRANSPARENT_HEADS` entry is crossed
        by the negative fixture that owns its roster. This is the direction that
        proves those rosters — a SHADOWING fixture cannot, because an
        unrecognised head fails closed and refuses for the same reason a
        recognised-but-shadowing one does, so deleting a head changed nothing.
      * every `_STR_ERR_VAR_NAMES` spelling appears in a positive fixture.
    """
    _NO_TOKEN = "positive/bypass_let_bound_no_token.cljc"
    BYPASS = "builder-bypass-message|"
    cases: list[tuple[str, frozenset[str]]] = [
        # (fixture-file relative to fixture-root, exact set of site names)
        # --- positives: each bare-keyword message shape must FIRE ---
        ("positive/literal_keyword_string.cljc",
         frozenset({"literal-keyword-string|<positive/literal_keyword_string.cljc>"})),
        ("positive/literal_keyword_multiline.cljc",
         frozenset({"literal-keyword-string|<positive/literal_keyword_multiline.cljc>"})),
        ("positive/str_literal_keyword.cljc",
         frozenset({"str-literal-keyword|<positive/str_literal_keyword.cljc>"})),
        ("positive/str_error_kw_var.cljc",
         frozenset({"str-error-keyword-var|<positive/str_error_kw_var.cljc>"})),
        ("positive/str_error_id_var.cljc",
         frozenset({"str-error-keyword-var|<positive/str_error_id_var.cljc>"})),
        # The three `_STR_ERR_VAR_NAMES` spellings that had no fixture.
        ("positive/str_remaining_err_var_names.cljc", frozenset({
            "str-error-keyword-var|:rf.error/str-error-keyword-var",
            "str-error-keyword-var|:rf.error/str-err-kw-var",
            "str-error-keyword-var|:rf.error/str-err-id-var",
        })),
        ("positive/str_id_of_payload.cljc",
         frozenset({"str-id-of-payload|<positive/str_id_of_payload.cljc>"})),
        # --- positives for the WIDENED builder-bypass rule (rf2-krrv87) ---
        ("positive/bypass_str_concat_no_token.cljc",
         frozenset({"builder-bypass-message|<positive/bypass_str_concat_no_token.cljc>"})),
        ("positive/bypass_plain_string_no_token.cljc",
         frozenset({"builder-bypass-message|<positive/bypass_plain_string_no_token.cljc>"})),
        # --- positives for the let-binding resolution (rf2-u3otj): resolving a
        #     local must not become a way to PASS. A bound form with no token,
        #     an unresolvable parameter, the computed discriminator, a sibling
        #     (non-enclosing) scope, an inner binding that shadows a
        #     token-bearing outer one, and a destructured name all still fire.
        #     The computed-discriminator site builds its id across lines, so it
        #     is the one that answers to the file rather than to an id.
        (_NO_TOKEN, frozenset({
            BYPASS + ":rf.error/bound-but-bare",
            BYPASS + ":rf.error/param-message",
            BYPASS + f"<{_NO_TOKEN}>",
            BYPASS + ":rf.error/sibling-scope",
            BYPASS + ":rf.error/shadowed-away",
            BYPASS + ":rf.error/destructured",
        })),
        # --- positives for the SCOPE PROOF (rf2-u3otj / the #7045 and #7064
        #     audits): a binder between the conformant outer `let` and the
        #     `ex-info` shadows the name, and every binder family must still
        #     fire — the #7045 nested-`fn`-parameter witness first, the #7064
        #     `fn` SELF-REFERENCE NAME (single- and multi-arity) last.
        ("positive/bypass_let_bound_shadowed.cljc", frozenset({
            BYPASS + ":rf.error/fn-param-shadow",
            BYPASS + ":rf.error/loop-shadow",
            BYPASS + ":rf.error/if-let-shadow",
            BYPASS + ":rf.error/doseq-shadow",
            BYPASS + ":rf.error/catch-shadow",
            BYPASS + ":rf.error/destructured-shadow",
            BYPASS + ":rf.error/letfn-shadow",
            BYPASS + ":rf.error/as-shadow",
            BYPASS + ":rf.error/arity-shadow",
            BYPASS + ":rf.error/fn-name-shadow",
            BYPASS + ":rf.error/fn-name-arity-shadow",
        })),
        # --- negatives: every conformant counterpart must stay GREEN ---
        ("negative/human_message_builder.cljc",      frozenset()),
        ("negative/throw_error_bang.cljc",           frozenset()),
        ("negative/str_concat_human_text.cljc",      frozenset()),
        ("negative/keyword_as_reason_value.cljc",    frozenset()),
        ("negative/keyword_in_exdata.cljc",          frozenset()),
        ("negative/keyword_in_comment.cljc",         frozenset()),
        ("negative/keyword_in_docstring.cljc",       frozenset()),
        ("negative/inline_human_token_slim.cljc",    frozenset()),
        # --- negatives for the WIDENED builder-bypass rule (rf2-krrv87) ---
        ("negative/bypass_inline_token_str_concat.cljc", frozenset()),
        ("negative/bypass_human_message_with_id.cljc",   frozenset()),
        ("negative/bypass_marker_exempt.cljc",           frozenset()),
        ("negative/bypass_no_error_id.cljc",             frozenset()),
        # --- negative for the let-binding resolution (rf2-u3otj): a conformant
        #     message bound one hop from the `ex-info` must stay GREEN.
        ("negative/bypass_let_bound_token.cljc",         frozenset()),
        # --- negative for the SCOPE PROOF: crossing ordinary control flow (the
        #     `when` guard `re-frame.story/configure!` writes, an if/do/cond
        #     chain, a nested `let` binding another name, an `fn` self-named
        #     something else, a reader conditional) introduces nothing, so the
        #     resolution still holds.
        ("negative/bypass_let_bound_guarded.cljc",       frozenset()),
        # --- negatives that OWN a roster: one crossing per entry (rf2-n6ijg) ---
        (_BINDER_HEAD_FIXTURE,      frozenset()),
        (_TRANSPARENT_HEAD_FIXTURE, frozenset()),
    ]

    failures = 0
    for fixture, expected in cases:
        path = _SELF_TEST_FIXTURE_ROOT / fixture
        if not path.is_file():
            sys.stderr.write(
                f"self-test FAIL: fixture {fixture!r} missing at {path}\n"
            )
            failures += 1
            continue
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        findings = scan(path, include_tests=True)
        # The KIND is half the witness. A site names WHERE the gate looked; the
        # kind names WHICH RULE answered, and the two are independent: deleting
        # three names from `_STR_ERR_VAR_NAMES` left every site in
        # `str_remaining_err_var_names.cljc` still firing — as
        # `builder-bypass-message` instead, since a bare `(str err-kw)` message
        # carries no token either. Same lines, same count, same site names, a
        # detector dead. Only the kind tells them apart.
        actual = frozenset(
            f"{f.kind}|{_witness_of(lines, fixture, f.line)}" for f in findings
        )
        if actual != expected:
            failures += 1
            sys.stderr.write(f"self-test FAIL: {fixture}\n")
            missing = sorted(expected - actual)
            extra = sorted(actual - expected)
            if missing:
                sys.stderr.write(
                    "      SITE DEAD — this fixture plants "
                    f"{', '.join(missing)} and the gate did not flag it\n"
                )
            if extra:
                sys.stderr.write(
                    f"      UNEXPECTED site(s): {', '.join(extra)}\n"
                )
            continue
        if len(findings) != len(actual):
            failures += 1
            sys.stderr.write(
                f"self-test FAIL: {fixture} has {len(findings)} findings for "
                f"{len(actual)} distinct site name(s) — a site that cannot be "
                "told from its neighbour can die unseen. Give each planted "
                "`ex-info` its own `:rf.error/id` on the `ex-info` line.\n"
            )
        elif verbose:
            sys.stderr.write(
                f"self-test PASS: {fixture} "
                f"({', '.join(sorted(actual)) or 'green'})\n"
            )

    failures += _run_roster_self_tests(verbose=verbose)
    failures += _run_where_sym_self_tests(verbose=verbose)
    failures += _run_cli_self_tests(verbose=verbose)

    if failures:
        sys.stderr.write(f"\n{failures} self-test failure(s).\n")
        return 1
    if verbose:
        sys.stderr.write(f"all {len(cases)} fixture self-tests passed.\n")
    return 0


def _crosses_head(text: str, head: str) -> bool:
    """Does `text` open a `(<head> …)` form? The trailing class keeps `cond`
    from answering for `condp` and `->` from answering for `->>`."""
    return bool(re.search(r"\(" + re.escape(head) + r"[\s\[]", text))


def _run_roster_self_tests(verbose: bool = False) -> int:
    """Hold every roster entry to owning a case. Returns the failure count.

    This is the assertion the fixtures could not make for themselves. Ten of the
    sixteen `_VECTOR_BINDER_HEADS`, thirteen of the nineteen `_TRANSPARENT_HEADS`
    and three of the five `_STR_ERR_VAR_NAMES` had no case at all, so each could
    be deleted from its roster — or arrive misspelled — with the whole self-test
    still green (rf2-n6ijg).
    """
    failures = 0
    rosters: tuple[tuple[str, tuple[str, ...], tuple[str, ...], str], ...] = (
        ("_VECTOR_BINDER_HEADS", tuple(sorted(_VECTOR_BINDER_HEADS)),
         (_BINDER_HEAD_FIXTURE,),
         "cross it with a clean binding vector; deleting the head must make "
         "that case fire"),
        ("_TRANSPARENT_HEADS", tuple(sorted(_TRANSPARENT_HEADS)),
         (_TRANSPARENT_HEAD_FIXTURE,),
         "nest a throw inside it; deleting the head must make that case fire"),
        ("_STR_ERR_VAR_NAMES", _STR_ERR_VAR_NAMES,
         ("positive/str_error_kw_var.cljc",
          "positive/str_error_id_var.cljc",
          "positive/str_remaining_err_var_names.cljc"),
         "plant `(ex-info (str <name>) …)` in a positive fixture"),
    )
    for roster_name, entries, fixtures, remedy in rosters:
        texts = []
        for fixture in fixtures:
            path = _SELF_TEST_FIXTURE_ROOT / fixture
            if not path.is_file():
                sys.stderr.write(
                    f"self-test FAIL: {roster_name}'s fixture {fixture!r} is "
                    f"missing at {path}\n"
                )
                failures += 1
                continue
            texts.append(path.read_text(encoding="utf-8", errors="replace"))
        joined = "\n".join(texts)
        if roster_name == "_STR_ERR_VAR_NAMES":
            uncovered = [e for e in entries if f"(str {e})" not in joined]
        else:
            uncovered = [e for e in entries if not _crosses_head(joined, e)]
        if uncovered:
            failures += 1
            sys.stderr.write(
                f"self-test FAIL: {roster_name} entr(y/ies) no fixture "
                f"exercises: {', '.join(uncovered)}\n"
                f"      In {', '.join(fixtures)}: {remedy}.\n"
            )
        elif verbose:
            sys.stderr.write(
                f"self-test PASS: all {len(entries)} {roster_name} "
                "entries have a case\n"
            )
    return failures


# --------------------------------------------------------------------------
# WHERE-SYM self-tests (rf2-z5lv)
# --------------------------------------------------------------------------
#
# EVERY CASE PINS LINE NUMBERS, NOT COUNTS. A count cannot separate "found the
# right sites" from "traded a real hit for a false positive", and the sibling
# gate in this directory shipped for a week matching only one of five
# reader-equivalent call spellings with its own self-test green throughout —
# because every fixture it had used that one spelling (audit #9491, repaired by
# #9496 with exactly this change). The fixtures here therefore vary the call
# head five ways, and the assertions read `(line, kind, symbol)`.

_WHERE_SYM_FIXTURE_ROOT = _SELF_TEST_FIXTURE_ROOT / "wheresym"
_WHERE_SYM_POSITIVE = "positive_where_syms.cljc"
_WHERE_SYM_NEGATIVE = "negative_where_syms.cljc"
_WHERE_SYM_ORACLE = "re_frame/fixture.cljc"


def _fixture_where_sym_index() -> PublicVarIndex:
    """An oracle built from the FIXTURE tree, never the live one. Grading the
    fixtures against the real `re-frame.core` would make a legitimate rename
    somewhere else change this suite's answers."""
    return PublicVarIndex(sorted(_WHERE_SYM_FIXTURE_ROOT.rglob("*.cljc")))


def _crosses_call_head(text: str, head: str) -> bool:
    """Does `text` contain a `(<head> …)` call, optionally namespace-qualified?"""
    return bool(re.search(
        r"\(" + _CLJ_WS + r"*(?:[\w.*+!?<>=-]+/)?" + re.escape(head) + _SYM_TAIL,
        text,
    ))


def _run_where_sym_self_tests(verbose: bool = False) -> int:
    failures = 0

    def fail(msg: str) -> None:
        nonlocal failures
        failures += 1
        sys.stderr.write(f"where-sym self-test FAIL: {msg}\n")

    for name in (_WHERE_SYM_POSITIVE, _WHERE_SYM_NEGATIVE, _WHERE_SYM_ORACLE):
        if not (_WHERE_SYM_FIXTURE_ROOT / name).is_file():
            fail(f"fixture {name!r} missing at {_WHERE_SYM_FIXTURE_ROOT / name}")
    if failures:
        return failures

    index = _fixture_where_sym_index()

    # ---- the ORACLE itself: which definition shapes are public --------------
    #
    # Asserted as an EXACT set. Each entry is a shape that has been got wrong:
    # `^:no-doc` is the manifest-oracle refutation, the two platform arms are
    # the runtime question, and `Panel` / `imported-fn` are the two
    # var-defining macros that do not begin `def` — both of which this parser
    # missed until the manifest control caught them. `do-defined-public` is the
    # valid exit-0 control for the audit-#9501 repair: dropping `comment` from
    # `_TRANSPARENT_DEF_WRAPPERS` must not cost the wrapper that really is
    # transparent.
    #
    # THE ABSENCES ARE HALF THE ASSERTION, and this is the only place that can
    # make them: three privacy spellings, a `defmethod`, the four inactive
    # forms (`comment`, `#_`, `'`, `` ` ``), the discard nested in the live
    # `do`, and — audit #9511's residual — the six names behind a COMPOSED
    # reader prefix (`#_#?`, `'#?`, `` `#? ``, `#_#?@` and both halves of a
    # stacked `#_#_`). The manifest control cannot stand in for it —
    # `oracle_problems` is a SUBSET test, so it catches public names the parser
    # has stopped seeing and is blind to fictitious ones it has started
    # inventing, which is exactly the direction `comment` failed in.
    #
    # `conditional-defined-public` is the twin that keeps the repair honest in
    # the other direction: the oracle fixture writes it as a LIVE `#?(…)` with
    # a body identical to the discarded one, so an inert-set widened until it
    # swallows reader-conditionals costs this name and the exact set says so.
    expected_publics = {
        "known-var", "known-public", "known-macro", "known-multi",
        "no-doc-public", "cljs-only-public", "clj-only-public",
        "Panel", "imported-fn", "do-defined-public",
        "conditional-defined-public",
    }
    got_publics = index.publics_of("re-frame.fixture")
    if got_publics != expected_publics:
        missing = sorted(expected_publics - (got_publics or set()))
        extra = sorted((got_publics or set()) - expected_publics)
        fail(
            "the derived public-var oracle disagrees with the fixture namespace"
            + (f"\n      NOT SEEN as public: {', '.join(missing)}" if missing else "")
            + (f"\n      WRONGLY public: {', '.join(extra)} — a private var or a "
               "`defmethod` leaking into the public set turns a real dead door "
               "GREEN" if extra else "")
        )
    elif verbose:
        sys.stderr.write(
            f"where-sym self-test PASS: oracle sees {len(expected_publics)} "
            "public(s) in the fixture namespace, and neither private var nor "
            "`defmethod` name among them\n"
        )

    # A namespace this tree does not define is None, NOT the empty set.
    # Conflating them is how a scan reports a clean sweep over a namespace it
    # never opened — here it would silently green every where-sym naming it.
    if index.publics_of("re-frame.nosuch") is not None:
        fail("an undefined namespace must answer None, not an empty public set")

    # ---- the two fixture files, pinned by (line, kind, symbol) --------------
    cases: list[tuple[str, tuple[tuple[int, str, str], ...]]] = [
        (_WHERE_SYM_POSITIVE, (
            # the five reader-equivalent formattings of the call head
            (31, "where-sym-unresolvable", "rf.fixture/ghost-one"),
            (37, "where-sym-unresolvable", "rf.fixture/ghost-two"),
            (43, "where-sym-unresolvable", "rf.fixture/ghost-three"),
            (50, "where-sym-unresolvable", "rf.fixture/ghost-four"),
            (57, "where-sym-unresolvable", "rf.fixture/ghost-five"),
            # the other two rostered builders
            (68, "where-sym-unresolvable", "rf.fixture/ghost-six"),
            (74, "where-sym-unresolvable", "rf.fixture/ghost-seven"),
            # the `:where` slot of a hand-built framework ex-data map
            (87, "where-sym-unresolvable", "rf.fixture/ghost-eight"),
            # the reasons a symbol does not resolve
            (95, "where-sym-unknown-namespace", "rf.nosuch/thing"),
            (101, "where-sym-unresolvable", "re-frame.fixture/private-fn"),
            (109, "where-sym-unresolvable", "rf.fixture/private-var"),
            (115, "where-sym-unresolvable", "rf.fixture/private-meta-var"),
            (121, "where-sym-unresolvable", "rf.fixture/foreign-multi"),
            # (3) a var that exists only inside an INACTIVE form. Each name is
            # defined in the oracle fixture and NOWHERE ELSE, so a generous
            # oracle greens exactly these five (audit #9501).
            (139, "where-sym-unresolvable", "rf.fixture/ghost-in-comment"),
            (145, "where-sym-unresolvable", "rf.fixture/ghost-discarded"),
            (151, "where-sym-unresolvable", "rf.fixture/ghost-quoted"),
            (157, "where-sym-unresolvable", "rf.fixture/ghost-syntax-quoted"),
            (164, "where-sym-unresolvable",
             "rf.fixture/ghost-discarded-inside-do"),
            # (4) commas, which are reader whitespace. The twins of these four
            # resolve in the negative fixture; the PAIR is the assertion,
            # because either half alone is green under a blind detector.
            (182, "where-sym-unresolvable", "rf.fixture/ghost-comma-one"),
            (188, "where-sym-unresolvable", "rf.fixture/ghost-comma-two"),
            (194, "where-sym-unresolvable", "rf.fixture/ghost-comma-three"),
            (200, "where-sym-unresolvable", "rf.fixture/ghost-comma-four"),
            # (5) the `:where` slot as the map's LAST entry — not a comma bug,
            # found by the control for one.
            (217, "where-sym-unresolvable", "rf.fixture/ghost-slot-last"),
            # (6) COMMAS AS THE SOLE SEPARATOR. These two exist because a
            # sabotage plant reverting `_is_clj_ws` came back GREEN over every
            # case in (4): each of those writes a comma beside whitespace, and
            # `_clj_strip` alone recovers that, so the splitter's own notion of
            # reader whitespace went untested. The fixtures had the pattern's
            # blind spot, which is the failure this gate's audit was about.
            (233, "where-sym-unresolvable", "rf.fixture/ghost-comma-five"),
            (236, "where-sym-unresolvable", "rf.fixture/ghost-comma-six"),
            # (7) COMPOSED READER PREFIXES — audit #9511's residual. The first
            # repair read the ONE character before the `(`, which is `?` for
            # both `#_#?(…)` and `'#?(…)`; the stacked `#_#_ a b` it could not
            # reach at all, because nothing stands behind the second form but
            # the one the first discard consumed. Each name is defined in the
            # oracle fixture behind its prefix and NOWHERE ELSE, and stripping
            # that prefix makes the form define its var for real — so the
            # prefix is the only thing under test here.
            (254, "where-sym-unresolvable",
             "rf.fixture/ghost-discarded-conditional"),
            (260, "where-sym-unresolvable",
             "rf.fixture/ghost-quoted-conditional"),
            (266, "where-sym-unresolvable",
             "rf.fixture/ghost-syntax-quoted-conditional"),
            (272, "where-sym-unresolvable",
             "rf.fixture/ghost-discarded-splice"),
            (279, "where-sym-unresolvable", "rf.fixture/ghost-stacked-first"),
            (285, "where-sym-unresolvable", "rf.fixture/ghost-stacked-second"),
        )),
        (_WHERE_SYM_NEGATIVE, ()),
    ]
    for fixture, expected in cases:
        path = _WHERE_SYM_FIXTURE_ROOT / fixture
        text = path.read_text(encoding="utf-8", errors="replace")
        observed = _scan_where_syms(path, text, text.splitlines())
        got = tuple(
            (f.line, f.kind, f.detail)
            for f in where_sym_findings(observed, index)
        )
        if got != expected:
            fail(
                f"{fixture}\n"
                f"      expected {list(expected)}\n"
                f"      got      {list(got)}"
            )
        elif verbose:
            sys.stderr.write(
                f"where-sym self-test PASS: {fixture} "
                f"({len(observed)} where-sym(s) observed, "
                f"{len(got)} finding(s) at lines {[l for l, _, _ in got]})\n"
            )

    # The NEGATIVE fixture must still be OBSERVING sites, or it proves nothing:
    # a mask that blanked the whole file would also report zero findings.
    neg_path = _WHERE_SYM_FIXTURE_ROOT / _WHERE_SYM_NEGATIVE
    neg_text = neg_path.read_text(encoding="utf-8", errors="replace")
    neg_observed = _scan_where_syms(neg_path, neg_text, neg_text.splitlines())
    if len(neg_observed) < 22:
        fail(
            f"the negative fixture observed only {len(neg_observed)} where-sym(s). "
            "A green there is only evidence while the sites are still being SEEN "
            "— zero findings over zero observations is what a dead scan looks like."
        )

    # THE COUNT ABOVE IS NOT THE ASSERTION FOR THE READER-EQUIVALENT SITES, and
    # this is the whole lesson of audit #9501. A resolving site is GREEN when it
    # is seen and green again when it has VANISHED, so the pair (fires in the
    # positive fixture, resolves here) proves nothing unless this half is pinned
    # as OBSERVED. Pinned by LINE, not by count: a count cannot separate "found
    # the right ones" from "traded a real hit for a false positive", which is
    # why `:min-where-syms` at 173 against 193 observed could not have caught a
    # single lost call.
    required_observations = (
        (133, "builder", "rf.fixture/do-defined-public"),
        (146, "builder", "rf.fixture/known-public"),
        (152, "builder", "rf.fixture/known-public"),
        (158, "where-slot", "rf.fixture/known-public"),
        (164, "where-slot", "rf.fixture/known-public"),
        # commas as the SOLE separator — the spelling a green sabotage plant
        # proved the pair above could not reach.
        (175, "builder", "rf.fixture/known-public"),
        (178, "where-slot", "rf.fixture/known-public"),
        # a LIVE reader-conditional, body-for-body the twin of the oracle
        # fixture's discarded one. Green here alone would also be what a
        # detector blind to the whole site looks like, so it is pinned SEEN.
        (190, "builder", "rf.fixture/conditional-defined-public"),
    )
    got_observations = {(w.line, w.source, w.symbol) for w in neg_observed}
    for pin in required_observations:
        if pin not in got_observations:
            fail(
                f"{_WHERE_SYM_NEGATIVE}: no where-sym observed at {pin}. This is "
                "a RESOLVING site written in a reader-equivalent spelling (a "
                "comma where the reader ignores one, a `:where` slot ending its "
                "map, a var interned by a live `do`). Its twin in "
                f"{_WHERE_SYM_POSITIVE} fires; if this one is not even SEEN, the "
                "detector has gone blind to the spelling and both halves report "
                "green."
            )

    neg_symbols = {w.symbol for w in neg_observed}
    for ghost in ("rf.fixture/ghost-in-a-docstring", "rf.fixture/ghost-in-a-comment",
                  "rf.fixture/ghost-nested", "rf.fixture/ghost-not-a-framework-error"):
        if ghost in neg_symbols:
            fail(
                f"{ghost} was read as a where-sym. It is planted in prose, in a "
                "nested `:extra` map, or in an ex-data map with no "
                "`:rf.error/id` — none of which is a where-sym."
            )

    # ---- rosters: every entry owns a case ----------------------------------
    pos_text = (_WHERE_SYM_FIXTURE_ROOT / _WHERE_SYM_POSITIVE).read_text(
        encoding="utf-8", errors="replace"
    )
    uncrossed = [h for h in _WHERE_SYM_FORMS if not _crosses_call_head(pos_text, h)]
    if uncrossed:
        fail(
            f"_WHERE_SYM_FORMS entr(y/ies) no fixture calls: {', '.join(uncrossed)}. "
            f"Plant a `({uncrossed[0]} … '<unresolvable sym> …)` site in "
            f"{_WHERE_SYM_POSITIVE}; deleting the entry must make it go quiet."
        )
    elif verbose:
        sys.stderr.write(
            f"where-sym self-test PASS: all {len(_WHERE_SYM_FORMS)} "
            "_WHERE_SYM_FORMS entries have a case\n"
        )
    oracle_text = (_WHERE_SYM_FIXTURE_ROOT / _WHERE_SYM_ORACLE).read_text(
        encoding="utf-8", errors="replace"
    )
    unexercised = [h for h in sorted(_EXTRA_DEF_HEADS)
                   if not _crosses_call_head(oracle_text, h)]
    if unexercised:
        fail(
            f"_EXTRA_DEF_HEADS entr(y/ies) the oracle fixture does not define a "
            f"var with: {', '.join(unexercised)}. Each entry is a promise that a "
            "macro NOT beginning `def` defines a public var; an unexercised one "
            "can be deleted, after which every where-sym naming its output "
            "becomes a false finding."
        )
    elif verbose:
        sys.stderr.write(
            f"where-sym self-test PASS: all {len(_EXTRA_DEF_HEADS)} "
            "_EXTRA_DEF_HEADS entries have a case\n"
        )

    # ---- the reader mask, in BOTH directions -------------------------------
    #
    # A search whose failure mode is a silent false zero needs a control that
    # BITES, not only one that comes back clean. Each pair below is one probe
    # that must find something and one that must not, sharing the shape.
    mask_cases: list[tuple[str, str, list[str]]] = [
        ("a character literal is not a string delimiter",
         '(= c \\")\n(throw-error! :rf.error/x \'rf/after-char-literal "r")',
         ["rf/after-char-literal"]),
        ("a docstring quoting a builder call is not a call",
         '(defn f "see (throw-error! :rf.error/x \'rf/ghost r)" [] 1)',
         []),
        ("a `;` comment quoting a builder call is not a call",
         ";; (throw-error! :rf.error/x 'rf/ghost r)\n"
         "(throw-error! :rf.error/y 'rf/live r)",
         ["rf/live"]),
        ("a `;` INSIDE a string does not start a comment",
         '(throw-error! :rf.error/x \'rf/after-semicolon-string "a; b")',
         ["rf/after-semicolon-string"]),
    ]
    for name, src, expected_syms in mask_cases:
        got_syms = [w.symbol for w in
                    _scan_where_syms(Path("<probe>"), src, src.splitlines())]
        if got_syms != expected_syms:
            fail(f"reader mask — {name}: expected {expected_syms}, got {got_syms}")
        elif verbose:
            sys.stderr.write(f"where-sym self-test PASS: reader mask — {name}\n")

    failures += _run_where_sym_baseline_self_tests(verbose=verbose)
    return failures


def _run_where_sym_baseline_self_tests(verbose: bool = False) -> int:
    """The RATCHET, whose two keys move in opposite directions.

    Exercised directly rather than through the CLI so both directions can be
    asserted without writing a file. The sibling ratchet's `--write-baseline`
    shipped rewriting every floor to the current count, so the repair command
    its own header advertised silently BLESSED regressions; these cases are
    what stop that being rediscovered here.
    """
    failures = 0

    def fail(msg: str) -> None:
        nonlocal failures
        failures += 1
        sys.stderr.write(f"where-sym baseline self-test FAIL: {msg}\n")

    good = '{:min-where-syms 10\n :sites {"rf/a" 3 "rf.b/c" 0}}'
    try:
        parsed = parse_where_sym_baseline(good)
    except BaselineError as exc:
        parsed = None
        fail(f"a well-formed baseline was rejected: {exc}")
    if parsed is not None and (parsed.min_where_syms != 10
                               or parsed.sites != {"rf/a": 3, "rf.b/c": 0}):
        fail(f"a well-formed baseline parsed wrong: {parsed}")

    # Strict: anything the reader does not consume is an ERROR. A baseline
    # half-read is a floor half-applied, which fails toward admitting a
    # regression.
    for text, why in (
        ('{:sites {"rf/a" 1}}', "no :min-where-syms"),
        ('{:min-where-syms 1}', "no :sites"),
        ('{:min-where-syms 1 :sites {"rf/a" 1} :extra 2}', "unrecognised key"),
        ('{:min-where-syms 1 :sites {rf/a 1}}', "non-string site key"),
        ('{:min-where-syms 1 :sites {"rf/a" "one"}}', "non-integer floor"),
        ('{:min-where-syms "ten" :sites {"rf/a" 1}}', "non-integer :min-where-syms"),
        ('{:min-where-syms 1 :sites ["rf/a" 1]}', ":sites is not a map"),
    ):
        try:
            parse_where_sym_baseline(text)
        except BaselineError:
            continue
        fail(f"a malformed baseline was ACCEPTED ({why}): {text}")

    previous = WhereSymBaseline(100, {"rf/a": 3, "rf/b": 2, "rf/gone": 1})

    # A site floor is an ALLOWANCE: it may only FALL.
    plan = plan_where_sym_baseline({"rf/a": 1, "rf/b": 2}, 200, previous)
    if plan.refusals:
        fail(f"a downward move was refused: {plan.refusals}")
    if plan.baseline.sites != {"rf/a": 1, "rf/b": 2}:
        fail(f"a downward move wrote {plan.baseline.sites}")
    if not any("rf/gone" in n for n in plan.notes):
        fail("a symbol that has cleared entirely was not reported as droppable")

    # A proposed INCREASE refuses the WHOLE write — never a partial file that
    # matches neither the tree nor the previous baseline.
    plan = plan_where_sym_baseline({"rf/a": 9, "rf/b": 1}, 200, previous)
    if not plan.refusals:
        fail("raising a site floor was allowed — the gate would go green by "
             "moving the goalposts")
    elif "rf/a" not in plan.refusals[0]:
        fail(f"the refusal does not name the offending symbol: {plan.refusals}")

    # A genuinely NEW symbol has to start somewhere, and that is the one path
    # by which debt enters the file — so it is called out rather than silent.
    plan = plan_where_sym_baseline({"rf/a": 3, "rf/b": 2, "rf/new": 1}, 200, previous)
    if plan.refusals or plan.baseline.sites.get("rf/new") != 1:
        fail(f"a new symbol was not recorded at its observed count: {plan}")
    if not any("NEW" in n and "rf/new" in n for n in plan.notes):
        fail("a new symbol entered the baseline silently")

    # `:min-where-syms` IS THE INVERSE. Lowering it loosens the gate, so a
    # shrinking population is held rather than written down.
    plan = plan_where_sym_baseline({}, 50, previous)
    if plan.baseline.min_where_syms != 100:
        fail(
            f":min-where-syms fell to {plan.baseline.min_where_syms}. It is the "
            "anti-blindness guard, not an allowance — applying the site rule to "
            "it gives away the protection that stops a blind scan reporting a "
            "clean sweep."
        )
    plan = plan_where_sym_baseline({}, 1000, previous)
    if plan.baseline.min_where_syms != 900:
        fail(f":min-where-syms did not rise with the corpus: {plan.baseline}")

    # ---- grading -----------------------------------------------------------
    here = Path("<probe>")

    def finding(symbol: str, line: int = 1) -> Finding:
        return Finding(here, line, "where-sym-unresolvable", "", symbol)

    baseline = WhereSymBaseline(10, {"rf/known": 2})

    # A NEW dead door has an implicit floor of ZERO and reds immediately.
    verdict = grade_where_syms([finding("rf/brand-new")], 50, baseline)
    if not verdict.violations or "rf/brand-new" not in verdict.violations[0]:
        fail(f"a new dead door did not red: {verdict.violations}")

    # A baselined symbol AT its floor is clean; BELOW it is a ratchet-down note.
    verdict = grade_where_syms(
        [finding("rf/known", 1), finding("rf/known", 2)], 50, baseline
    )
    if verdict.violations:
        fail(f"a symbol at its floor red: {verdict.violations}")
    verdict = grade_where_syms([finding("rf/known")], 50, baseline)
    if verdict.violations or not verdict.notes:
        fail(f"a symbol below its floor was not reported as lowerable: {verdict}")
    verdict = grade_where_syms([], 50, baseline)
    if not any("drop the entry" in n for n in verdict.notes):
        fail("a cleared symbol was not reported as droppable")

    # ANTI-BLINDNESS: a population below the floor is UNUSABLE, not clean.
    verdict = grade_where_syms([], 3, baseline)
    if not verdict.unusable:
        fail(
            "a where-sym population far below `:min-where-syms` reported CLEAN. "
            "That is this gate's own worst failure: a search that has stopped "
            "matching answers `nothing here` in the same words as a clean tree."
        )
    if verbose and not failures:
        sys.stderr.write(
            "where-sym self-test PASS: baseline parses strictly, site floors "
            "fall only, :min-where-syms rises only, a new dead door reds, and a "
            "collapsed population is unusable rather than green\n"
        )
    return failures


# --------------------------------------------------------------------------
# CLI-level self-tests (rf2-un9fk) — the scan-ROOT validation, which the
# fixture cases above cannot reach (they call `scan()` directly, bypassing
# argument handling entirely).
# --------------------------------------------------------------------------
#
# The fixture cases prove the DETECTOR. These prove the gate RUNS OVER WHAT IT
# SAYS IT DOES. A root that resolves to an existing regular file used to pass
# validation, scan zero files, and print the success verdict — a gate reporting
# green for work it never did. `main()` is exercised end-to-end (parse → validate
# → scan → verdict) so the exit code IS the assertion, matching how CI reads
# this script.

_CLI_NEGATIVE_FIXTURE_DIR = "scripts/_test_fixtures/check_thrown_error_messages/negative"
_CLI_WHERE_SYM_NEGATIVE_DIR = "scripts/_test_fixtures/check_thrown_error_messages/wheresym"


def _run_cli_self_tests(verbose: bool = False) -> int:
    """Exercise `main()`'s scan-root validation. Returns the failure count."""
    import contextlib
    import io

    cases: list[tuple[str, list[str], int]] = [
        # (name, argv, expected exit code)
        ("valid directory scans and greens",
         ["--scan-dir", _CLI_NEGATIVE_FIXTURE_DIR], 0),
        ("missing path is rejected",
         ["--scan-dir", "no/such/tree"], 2),
        ("existing FILE is rejected (rf2-un9fk)",
         ["--scan-dir", "README.md"], 2),
        ("mixed valid dir + file fails the WHOLE invocation",
         ["--scan-dir", _CLI_NEGATIVE_FIXTURE_DIR, "--scan-dir", "README.md"], 2),
        # An AD-HOC surface must not be graded against the where-sym baseline.
        # Both halves of that rule — the site floors and `:min-where-syms` —
        # describe the ROSTERED surface, so a narrow `--scan-dir` would trip the
        # anti-blindness floor on a corpus that is small for a legitimate
        # reason. This case pins the skip; without it, `--scan-dir` exits 2 on
        # every fixture directory in this tree.
        ("an ad-hoc --scan-dir skips the where-sym rule rather than misgrading it",
         ["--scan-dir", _CLI_WHERE_SYM_NEGATIVE_DIR], 0),
    ]

    failures = 0
    for name, argv, expected in cases:
        buf = io.StringIO()
        with contextlib.redirect_stderr(buf):
            got = main(argv)
        if got != expected:
            sys.stderr.write(
                f"cli self-test FAIL: {name} — expected rc={expected}, got {got}\n"
                f"  argv: {argv}\n  stderr: {buf.getvalue().strip()!r}\n"
            )
            failures += 1
            continue
        # A rejection must name the offending path and must not be a traceback.
        if expected == 2:
            err = buf.getvalue()
            # Compare on the BASENAME: the diagnostic prints the resolved
            # absolute path, whose separators are host-dependent (`\` on
            # Windows), so matching the supplied relative spelling verbatim
            # would fail on one platform and pass on the other.
            bad_path = Path(argv[-1]).name
            if bad_path not in err or "Traceback" in err:
                sys.stderr.write(
                    f"cli self-test FAIL: {name} — the rc=2 diagnostic must name "
                    f"{bad_path!r} without a traceback; got {err.strip()!r}\n"
                )
                failures += 1
                continue
        if verbose:
            sys.stderr.write(f"cli self-test PASS: {name} (rc={got})\n")
    return failures


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
