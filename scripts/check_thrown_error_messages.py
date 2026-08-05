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

Exit code:
    0  no bare-keyword thrown-error message in framework source
    1  at least one (results printed file:line)
    2  invocation / setup error

Dependency-light — Python stdlib only.
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
_STR_ERR_VAR_RE = re.compile(
    r"\(\s*ex-info\s+\(\s*str\s+(error-kw|error-id|error-keyword|err-kw|err-id)\s*\)"
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


def _split_first_arg(body: str) -> tuple[str, str]:
    """Split a balanced `ex-info` BODY (text after `ex-info` and its
    whitespace, up to but excluding the closing paren) into its FIRST argument
    (the message form) and the remainder (the ex-data + any extra).

    Returns `(first_arg, rest)` with both stripped. Whitespace/quote/bracket
    aware so a `(str …)` / map / nested form is taken as one unit. A `;`
    comment cannot appear (the caller passes comment-masked text)."""
    body = body.lstrip()
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
            depth -= 1
            i += 1
            continue
        if c.isspace():
            if started and depth == 0:
                break
            i += 1
            continue
        started = True
        i += 1
    return body[:i].strip(), body[i:].strip()


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
    rest = body.strip()
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


def _scan_text(path: Path, text: str) -> list[Finding]:
    masked = "\n".join(_mask_comments(text))
    raw_lines = text.splitlines()

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


def scan(scan_root: Path, include_tests: bool = False) -> list[Finding]:
    findings: list[Finding] = []
    for path in _iter_source_files(scan_root, include_tests):
        text = path.read_text(encoding="utf-8", errors="replace")
        findings.extend(_scan_text(path, text))
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
            "`[:rf.<ns>/<id>]` token."
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

    findings: list[Finding] = []
    for root in scan_roots:
        findings.extend(scan(root, include_tests=args.include_tests))
    if findings:
        _report(findings, repo_root)
        return 1
    if args.verbose:
        sys.stderr.write(
            "no bare-keyword thrown-error messages in framework source.\n"
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


def _run_self_tests(verbose: bool = False) -> int:
    cases: list[tuple[str, int]] = [
        # (fixture-file relative to fixture-root, expected finding count)
        # --- positives: each bare-keyword message shape must FIRE ---
        ("positive/literal_keyword_string.cljc",     1),
        ("positive/literal_keyword_multiline.cljc",  1),
        ("positive/str_literal_keyword.cljc",        1),
        ("positive/str_error_kw_var.cljc",           1),
        ("positive/str_error_id_var.cljc",           1),
        ("positive/str_id_of_payload.cljc",          1),
        # --- positives for the WIDENED builder-bypass rule (rf2-krrv87) ---
        ("positive/bypass_str_concat_no_token.cljc",     1),
        ("positive/bypass_plain_string_no_token.cljc",   1),
        # --- positives for the let-binding resolution (rf2-u3otj): resolving a
        #     local must not become a way to PASS. A bound form with no token,
        #     an unresolvable parameter, the computed discriminator, a sibling
        #     (non-enclosing) scope, an inner binding that shadows a
        #     token-bearing outer one, and a destructured name all still fire.
        ("positive/bypass_let_bound_no_token.cljc",      6),
        # --- positives for the SCOPE PROOF (rf2-u3otj / the #7045 and #7064
        #     audits): a binder between the conformant outer `let` and the
        #     `ex-info` shadows the name, and every binder family must still
        #     fire — the #7045 nested-`fn`-parameter witness first, the #7064
        #     `fn` SELF-REFERENCE NAME (single- and multi-arity) last.
        ("positive/bypass_let_bound_shadowed.cljc",      11),
        # --- negatives: every conformant counterpart must stay GREEN ---
        ("negative/human_message_builder.cljc",      0),
        ("negative/throw_error_bang.cljc",           0),
        ("negative/str_concat_human_text.cljc",      0),
        ("negative/keyword_as_reason_value.cljc",    0),
        ("negative/keyword_in_exdata.cljc",          0),
        ("negative/keyword_in_comment.cljc",         0),
        ("negative/keyword_in_docstring.cljc",       0),
        ("negative/inline_human_token_slim.cljc",    0),
        # --- negatives for the WIDENED builder-bypass rule (rf2-krrv87) ---
        ("negative/bypass_inline_token_str_concat.cljc", 0),
        ("negative/bypass_human_message_with_id.cljc",   0),
        ("negative/bypass_marker_exempt.cljc",           0),
        ("negative/bypass_no_error_id.cljc",             0),
        # --- negative for the let-binding resolution (rf2-u3otj): a conformant
        #     message bound one hop from the `ex-info` must stay GREEN.
        ("negative/bypass_let_bound_token.cljc",         0),
        # --- negative for the SCOPE PROOF: crossing ordinary control flow (the
        #     `when` guard `re-frame.story/configure!` writes, an if/do/cond
        #     chain, a nested `let` binding another name, an `fn` self-named
        #     something else, a reader conditional) introduces nothing, so the
        #     resolution still holds.
        ("negative/bypass_let_bound_guarded.cljc",       0),
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
        got = len(scan(path, include_tests=True))
        if got == expected:
            if verbose:
                sys.stderr.write(f"self-test PASS: {fixture} (findings={got})\n")
        else:
            sys.stderr.write(
                f"self-test FAIL: {fixture} expected findings={expected}, "
                f"got {got}\n"
            )
            failures += 1

    failures += _run_cli_self_tests(verbose=verbose)

    if failures:
        sys.stderr.write(f"\n{failures} self-test failure(s).\n")
        return 1
    if verbose:
        sys.stderr.write(f"all {len(cases)} fixture self-tests passed.\n")
    return 0


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
