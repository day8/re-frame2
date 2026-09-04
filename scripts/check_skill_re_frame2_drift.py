#!/usr/bin/env python3
"""No-bead-id + verification-posture + launcher-canonical + machine-handler-
recipe + managed-http-recipe + form-action-CSRF drift guard for the re-frame2
(authoring) skill.

`spec/design.md` is the single normative source for the skill's locked
decisions (L1–L11), file structure, cardinal rules, and verification posture;
`spec/inputs.md` owns the canonical inputs and update procedure. The
`spec/authoring-prompt.md` launcher orchestrates a reauthoring pass by
*pointing at* those two files — it must not carry a second, drift-prone copy of
the tree, the rules, or the locks. This guard protects the regressions below,
each one the existing automated guards did not catch (the no-bead-id guard was
scoped to `skills/re-frame2-implementor` only):

  1. **Bead-id leaks in user-facing leaves.** Two leaves carried
     `EP-0008 (rf2-hhutya)` — an internal `bd` tracker id. The skill's own
     design locks this out (`spec/design.md` L10 — the normative source; the
     `spec/authoring-prompt.md` launcher references it but no longer restates
     it): `SKILL.md` + `references/` + `patterns/` + `decision-trees/`
     carry NO `rf2-XXXX` ids. Bead ids are monorepo-internal workflow noise; a
     consumer app author has no `bd` and no bead corpus, so a leaked id is
     unexplained context. Use public, stable evidence instead — an EP number, a
     spec section link, a fixture name, an API entry, or a fully-qualified
     public PR link. The skill's own `spec/` meta-docs MAY mention bead ids
     (authoring / internal context) and are out of scan scope; `evals/` is also
     out of scope (eval harness, not user-facing teaching).

  2. **Verification-posture drift.** `spec/design.md` L3 is the normative
     posture (the `spec/authoring-prompt.md` launcher and `skills/README.md`
     both reference it, they do not re-hold it): on an existing project the
     AGENT runs the project's own declared noninteractive compile / test / lint
     gate after it edits and reports the exact command and result; it hands the
     gate to the programmer only when the gate is interactive, needs a live
     runtime, does not exist, or the user said not to. Until 2026-08-31 this
     rule enforced the INVERSE — the Q14 lock, an authoring-only skill whose
     author ran every gate, with a `Bash(clojure -M:test)` grant and a "run the
     gate before declaring done" instruction as the drift shapes. Q14 was
     unlocked by the rf2-g9k0g surface review (the skill's own §1 goal said the
     output should compile and pass tests while the posture forbade the agent
     from finding out; the family baseline already trusts the explicit invoker
     with these commands), and the rule flipped in the same change. What it
     now refuses is a slide BACK to the hand-off: (2a) `SKILL.md`'s frontmatter
     must keep the routine gate-running wildcards the published-skill baseline
     blesses (`Bash(clojure *)` / `Bash(npm *)` / `Bash(shadow-cljs *)`); (2b)
     no user-facing leaf may tell the agent to hand a runnable gate to the
     author ("the author runs the tests", "gate named for the author").

  3. **Launcher regrowth.** `spec/authoring-prompt.md` is a launcher, not a
     second normative source. It MUST point at both canonical files
     (`design.md` AND `inputs.md`) so a reauthoring session reads the design
     before writing, and it MUST NOT regrow the copied file-structure tree or
     the copied locks/cardinal-rules block — the exact duplication that drifted
     (the launcher had over-generalised the runtime `*` suffixes and still
     named a default frame, both contradicting current design). This guard
     fails if the launcher stops citing a canonical file or grows a box-drawing
     tree / a "Locks to preserve verbatim" (or "Cardinal rules to bake in")
     block back.

  4. **The machine-registration footgun.** The state-machine leaf and the API
     cheatsheet taught the bare `(reg-event id meta (make-machine-handler spec))`
     route as if it were a normal way to author a machine. That direct path does
     NOT stamp the `:rf/machine?` / `:rf/machine` registration metadata or the
     per-element source coordinates that machine introspection, `(machine-meta
     id)`, visualisers, and Xray resolve through, and a `[:schemas :data]`-
     bearing spec throws `:rf.error/machine-schema-requires-reg-machine` on it.
     `reg-machine` is the sole normal application-authoring recipe — it already
     registers the machine AS an event handler and stamps that metadata. This
     guard rejects a POSITIVE fenced recipe that co-locates `reg-event` and
     `make-machine-handler`, while ALLOWING an inline (non-fenced) implementation
     warning that names the shape — so the retained advanced-note mention on
     `reg-machine.md` / `api-cheatsheet.md` stays legal, but a copy-pasteable
     footgun recipe cannot reappear in any user-facing leaf.

  5. **The retired Managed-HTTP reply contract.** The runtime retired the
     co-located reply default pre-alpha (`rf2-et4c1s` / PR #5449): every
     `:rf.http/managed` fx-form request must now address its reply with at
     least one of `:reply-to` / `:on-success` / `:on-failure`, and omitting all
     three throws `:rf.error/http-no-reply-target` at fx-call time — a
     targetless canonical recipe cannot even start a request. The skill's
     primary HTTP example had drifted to teach exactly that removed shape
     (`rf2-j538f7.35` / PR #5603 repaired the leaf prose). This guard makes the
     retired teaching a build failure so it cannot re-enter: (5a) a fenced
     fx-form `[:rf.http/managed …]` recipe that carries a `:request` but no
     reply target; (5b) reading the removed co-located key `(:rf/reply …)`; and
     (5c) bare `:work/id` asserted as a TRANSIENT reply field (whose spelling is
     `:rf.reply/work-id` — bare `:work/id` is the durable ledger / machine-
     `:data` / correlation identity). The machine-form `:spawn {:machine-id
     :rf.http/managed …}` (which dispatches back to its parent and needs no
     `:reply-to`), the `[:rf.http/managed-abort …]` dispatch, and legitimate
     durable-ledger `:work/id` prose are deliberately allowed.

  6. **UIx/Helix hooks stateful-component guidance, causally exact (rf2-gq9bg;
     follow-up to rf2-adm10).** The hooks adapters (UIx / Helix) bridge a
     stateful component with an ordinary `defui` / `defnc` plus the adapter's
     `use-subscribe` (read subs) and `use-frame` (carry the frame) hooks — NOT
     the Reagent `reg-view` macro, a render-time / bare no-arg `capture-frame`,
     or a `:contextType`. `use-frame` is `capture-frame` in hook position,
     reading the surrounding frame-provider / frame-root through React context;
     `reg-view*` on these adapters is optional registry addressing, never the
     frame wiring. The earlier guard was not causally exact: (6a) checked only
     WHOLE-FILE presence of `use-frame` / `use-subscribe`, so a token-only
     semantic reversal ("Do not use use-frame or use-subscribe; use reg-view
     instead.") passed; (6b) was a line rule suppressed by ANY broad negation
     cue, so "UIx is not special: reg-view* gives it :contextType." passed on
     the stray far "not" while the valid comparison "UIx/Helix differ from
     Reagent's :contextType mechanism." was falsely rejected. The rewrite is
     SENTENCE-scoped (a bounded recipe-sentence carve, NOT a general prose
     parser — a mixed Reagent+hooks line is checked per sentence): (6a) each
     authoritative leaf (`patterns/stateful-components.md`, `references/
     fundamentals/frames.md`, and — rf2-05kex — `references/fundamentals/
     views.md`, whose per-adapter recipe table had drifted while its own
     paragraph stayed lawful) MUST carry a COHERENT recipe sentence naming an
     ordinary `defui`/`defnc`, `use-subscribe`, AND `use-frame` together; (6b)
     no hooks-recipe sentence may carry a residue shape — a `:contextType`
     ATTRIBUTED to a hooks adapter (fires even under a stray far "not"; a
     Reagent-owned/comparative `:contextType` and a tightly-scoped "no
     `:contextType`" stay green), a token-only semantic reversal, a positive
     `reg-view` MACRO registration (bare, not `reg-view*`), or a bare no-arg
     `capture-frame` used as the frame-carry (the negated "cannot" warnings
     stay green). (6c — rf2-szw6c) A leaf-wide coherence floor cannot force a
     TABLE CELL right while the leaf's adjacent paragraph stays right, and it
     reaches only the leaves this skill globs. So the three concrete recipe
     blocks a programmer or a model actually copies are additionally anchored
     as BOUNDED BLOCKS, checked in place: the UIx ordinary-view row
     of the `references/fundamentals/views.md` per-adapter table, the
     `skills/re-frame2-setup/references/first-counter.md` "Reagent only"
     warning, and the canonical UIx hooks paragraph under the `spec/
     006-ReactiveSubstrate.md` adapter inventory. The last two live outside
     this skill entirely, so the block anchor is their ONLY protection — and
     deliberately so: only the named block is read, never the whole file (spec/
     006 lawfully carries bead ids and Reagent-owned `:contextType` prose that
     the leaf-wide rules would reject).

  7. **The form-action pattern's two fail-open shapes, in copyable code.** The
     skill's `patterns/form-action.md` shipped
     `(and server? (not= (:csrf-token form-params) active-token))` as its
     canonical CSRF arm — the exact shape `spec/Pattern-FormAction.md` lists as
     an anti-pattern, because it fails **open** on a request with no session
     (`active-token` is nil, a token-less POST supplies nil, nil = nil, and the
     rejection arm never fires). It also typed the draft, the event `:schema`
     and the handler's validation call with one token-requiring schema, which
     400s every hydrated-client submission in the release build. Rules 1-6 all
     exited 0 over both. Rule 7 checks the two invariants inside code fences, at
     BOTH ends of the projection — the skill leaves and the spec page.

  8. **The JVM `with-frame` thunk "function form" (rf2-jwmkq).** The testing
     leaf told JVM authors to use `(rf/with-frame frame-id (fn [] ...))` as a
     "function form". No such function exists: `with-frame` is the same
     body-splicing macro on JVM and CLJS, so the fn literal is a legal body
     expression that the macro evaluates and returns UNINVOKED — in a
     `use-fixtures` wrapper this silently skips every test body ("Ran 0 tests
     containing 0 assertions.", zero failures, exit 0). Rule 8 rejects a fn
     literal (or `#(...)` reader lambda) in `with-frame`'s DIRECT body-head
     position, scanned over the whole body — fenced and inline alike, because
     the original defect was an inline POSITIVE instruction a fence-only rule
     exits 0 over, and a lawful warning never needs the full compound shape
     (it quotes the bare fn literal, as the repaired leaf does). Ordinary fn
     literals nested deeper in the body (a `reg-sub` handler, an event fn)
     never match, and `with-new-frame` is a different head token, out of
     scope — Rule 9a is what covers it.

  9. **The canonical per-test frame recipe (rf2-u429).** The
     `references/fundamentals/frames.md` "Canonical mini-example" — the
     block a programmer or a model copies into a consumer test suite —
     carried TWO independent copy-oriented faults at once, and Rules 1-8
     exited 0 over both. (9a) It called `(with-new-frame ...)`
     UNQUALIFIED while every surrounding form used the `[re-frame.core
     :as rf]` alias and nothing was `:refer`-ed. The macro lives on
     `re-frame.core`, whose CLJS branch self-`:require-macros` it — so it
     is reachable as `rf/with-new-frame` and NOT bare, in any ordinary
     consumer namespace. That is the standing generic-consumer rule in
     miniature: the bare spelling resolves only where somebody explicitly
     referred it. It also fails far worse than a typo — ClojureScript
     grades an undeclared Var as a WARNING, so the build exits 0, the
     emitted call swallows the whole macro body into the undefined
     function's argument list, and the block throws a TypeError at run
     time having asserted nothing (the Rule 8 silent-skip class, reached
     through the other frame macro). (9b) Its assertion computed
     `:auth.login/state` from `(rf/app-db-value f)`. That sub derives
     from `[:rf/machine :auth.login/flow]`, a `reg-runtime-sub` whose
     source is RUNTIME-db, while `app-db-value` returns the app-db
     partition alone — so even with the macro repaired the input is
     unsatisfied and the sub computes `nil`, not `:authed`, with no error
     and no warning. `frame-state-value` is the coherent whole a mixed
     app/runtime graph needs. Rule 9 refuses both shapes inside CODE
     FENCES only (a labelled "not this" in prose, and the affordance
     table naming the bare macro, stay legal), leaves an app-db-only
     `compute-sub` alone (it is the smaller correct form), and (9c)
     additionally anchors the canonical block itself so the recipe cannot
     be reworded out of existence unnoticed.

WHAT THIS GUARD IS, AND IS NOT (read before trusting a green run):
    Rules 1-6 are *retrospective token patterns*. Each encodes one regression
    somebody already found, checked against a leaf's own text. Nothing in this
    script relates a `patterns/*.md` leaf to the `spec/Pattern-*.md` page it
    projects — there is no notion of an upstream here at all — so the general
    class "the spec page moved and the leaf did not" is invisible BY
    CONSTRUCTION, and a green run is not evidence that any leaf agrees with its
    source. A full fence-equivalence check is not buildable either: the leaves
    are deliberately abridged projections, not copies. What Rule 7 does instead
    is promote a pattern's SECURITY-shaped invariants out of prose and check
    them at both ends, so neither the leaf nor the spec can carry the
    anti-pattern in code a reader would copy, and a regression at either end
    fails the gate. That is a narrower claim than "skill and spec agree", and it
    is the claim this guard makes.

Scanned leaves (globbed, so a new leaf is covered automatically):
    SKILL.md, README.md, references/**/*.md, patterns/**/*.md,
    decision-trees/**/*.md.
Out of scope of the line-by-line scan (deliberately): spec/ (L10 permits bead
    ids; `design.md` carries the normative lock *statements*; `authoring-
    prompt.md` is the launcher, checked separately by Rule 3), evals/ (harness,
    not teaching). examples-map.md is in scope (it is a user-facing leaf).

Exit code:
    0  no drift detected
    1  drift detected (printed line-by-line; GitHub-Actions ::error:: under CI)
    2  invocation / setup error

Usage:
    python scripts/check_skill_re_frame2_drift.py
    python scripts/check_skill_re_frame2_drift.py --verbose
    python scripts/check_skill_re_frame2_drift.py --ci
    python scripts/check_skill_re_frame2_drift.py --self-test
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

# Force UTF-8 on output streams — the corpus carries em-dash / arrows etc. and
# the default Windows console codec (cp1252) would crash on them (rf2 is
# maintained on Windows; the gate also runs on Linux CI).
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8")  # type: ignore[union-attr]
    except (AttributeError, ValueError):  # pragma: no cover - non-TextIO stream
        pass

SKILL_DIR = REPO_ROOT / "skills" / "re-frame2"
# The launcher: a reauthoring wrapper that must POINT at the canonical files
# (design.md + inputs.md), never re-hold their tree / rules / locks (Rule 3).
AUTHORING_PROMPT = SKILL_DIR / "spec" / "authoring-prompt.md"


def scanned_files() -> list[Path]:
    """User-facing leaves: SKILL.md, README.md, examples-map.md, and every
    leaf under references/ / patterns/ / decision-trees/. Globbed (not
    hand-listed) so a newly-added leaf is covered automatically. The skill's
    spec/ meta-docs and evals/ harness are deliberately NOT included."""
    files: list[Path] = []
    for name in ("SKILL.md", "README.md", "examples-map.md"):
        p = SKILL_DIR / name
        if p.is_file():
            files.append(p)
    for sub in ("references", "patterns", "decision-trees"):
        files.extend(sorted((SKILL_DIR / sub).rglob("*.md")))
    return files


# --- Rule 1: no `rf2-XXXX` internal bead ids in the user-facing leaves (L10).
# The id shape is `rf2-` + alphanumerics (some carry a `.N` sub-task suffix,
# e.g. `rf2-d3fb7.1`). Word-boundary the prefix so it doesn't match inside a
# longer token.
BEADID_RE = re.compile(r"\brf2-[a-z0-9]+(?:\.[0-9]+)?\b")

# --- Rule 2: verification-posture drift (L3 — the agent runs the gate).
# 2a — the gate-running grants. `SKILL.md`'s allowed-tools front-matter must
#      carry each routine family the agent needs to run a project's declared
#      gate: the JVM alias (`clojure`), the npm script (`npm`), and the CLJS
#      build (`shadow-cljs`). Checked over the whole SKILL.md body, one
#      `- Bash(<family> …)` entry per family.
GATE_GRANT_FAMILIES = ("clojure", "npm", "shadow-cljs")


def _gate_grant_re(family: str) -> re.Pattern[str]:
    return re.compile(rf"^\s*-\s*Bash\(\s*{re.escape(family)}\s", re.MULTILINE)


# 2b — body prose that hands a RUNNABLE gate back to the author: the retired
#      Q14 wording ("the author runs the tests / suite / compiler / app",
#      "gate named for the author", "name it for the author", "hand off the
#      gate"). A hand-off for a gate that is interactive, needs a live runtime,
#      or does not exist is lawful and spelled differently ("hand off only
#      when …", "the gate the author should add"), so it does not match.
HANDOFF_RESIDUE_RE = re.compile(
    r"\bthe author runs the (?:tests?|suite|compiler|gates?|app)\b"
    r"|\bgate named for the author\b"
    r"|\bname (?:it|the gate) for the author\b"
    r"|\bhand(?:s|ed|ing)? off the gate\b",
    re.IGNORECASE,
)

# --- Rule 4: no positive bare `reg-event` + `make-machine-handler` recipe in a
#     fenced code block (the machine-registration footgun). `reg-machine` is the
#     sole normal application-authoring recipe; the bare direct path skips the
#     `:rf/machine?` / source-coord stamp and trips
#     `:rf.error/machine-schema-requires-reg-machine` on a `[:schemas :data]`
#     spec. Scanned per-FENCED-BLOCK (the two tokens land on different lines of
#     one block), so an inline single-backtick warning that names the shape in
#     prose — the retained advanced-note mention — is allowed; only a
#     copy-pasteable positive recipe is rejected.
FENCE_RE = re.compile(r"^\s*```")


def fenced_blocks(text: str):
    """Yield (opening-fence-lineno, block-body) for every fenced code block.

    Three rules read code fences rather than prose, because a fence is what a
    reader copies: an inline single-backtick mention of a bad shape in a
    labelled "not this" warning is legal teaching, and a fenced one is not.
    """
    in_block = False
    block_start = 0
    block_lines: list[str] = []
    for lineno, line in enumerate(text.splitlines(), start=1):
        if FENCE_RE.match(line):
            if not in_block:
                in_block, block_start, block_lines = True, lineno, []
            else:
                yield block_start, "\n".join(block_lines)
                in_block, block_lines = False, []
        elif in_block:
            block_lines.append(line)


REG_EVENT_TOKEN_RE = re.compile(r"\breg-event\b")
MAKE_MACHINE_HANDLER_TOKEN_RE = re.compile(r"\bmake-machine-handler\b")
MACHINE_HANDLER_MSG = (
    "MACHINE-HANDLER-FOOTGUN: this fenced recipe registers a machine via the "
    "bare `reg-event` + `make-machine-handler` route. That path does NOT stamp "
    "the :rf/machine? / :rf/machine registration metadata or the per-element "
    "source coordinates that machine introspection, (machine-meta id), "
    "visualisers, and Xray resolve through, and a [:schemas :data]-bearing spec "
    "throws :rf.error/machine-schema-requires-reg-machine on it. Author normal "
    "machines with `rf/reg-machine` — the sole normal application-authoring "
    "recipe; it already registers the machine AS an event handler. Demote "
    "make-machine-handler to an inline advanced-note mention, never a positive "
    "fenced recipe."
)


def machine_handler_recipe_problems(text: str) -> list[tuple[int, str]]:
    """Rule 4 — a fenced code block that co-locates `reg-event` and
    `make-machine-handler` is the machine-registration footgun (a positive
    recipe for the metadata-skipping direct path). Returns
    (opening-fence-lineno, message) tuples. Takes the whole file body so the
    self-test can pass a fenced fixture directly; an inline single-backtick
    mention in prose never enters a fenced block, so it is allowed."""
    problems: list[tuple[int, str]] = []
    for block_start, body in fenced_blocks(text):
        if (REG_EVENT_TOKEN_RE.search(body)
                and MAKE_MACHINE_HANDLER_TOKEN_RE.search(body)):
            problems.append((block_start, MACHINE_HANDLER_MSG))
    return problems


# --- Rule 5: no retired Managed-HTTP reply-contract teaching (rf2-723lgn,
#     follow-up to rf2-j538f7.35 / PR #5603).
#
# 5a — a targetless fx-form `:rf.http/managed` REQUEST recipe. The fx form
#      `:fx [[:rf.http/managed {:request …}]]` MUST address its reply with at
#      least one of :reply-to / :on-success / :on-failure; the runtime throws
#      :rf.error/http-no-reply-target at fx-call time otherwise, so a targetless
#      canonical recipe cannot even start a request (there is no co-located
#      default that routes the reply back to the originating event). Scanned
#      per-FENCED-BLOCK (the tokens span lines). Only the `[:rf.http/managed`
#      fx invocation head is matched — never `:machine-id :rf.http/managed` (the
#      machine-form spawn dispatches back to its parent and needs no reply key)
#      and never `[:rf.http/managed-abort …]` (a dispatch with no :request).
FX_MANAGED_RE = re.compile(r"\[:rf\.http/managed(?!-)\b")
HTTP_REQUEST_RE = re.compile(r":request\b")
HTTP_REPLY_TARGET_RE = re.compile(r":reply-to\b|:on-success\b|:on-failure\b")
MANAGED_HTTP_TARGETLESS_MSG = (
    "HTTP-TARGETLESS-RECIPE: this fenced fx-form `:rf.http/managed` recipe "
    "supplies a `:request` but names NO reply target. The current contract "
    "REQUIRES at least one of `:reply-to` / `:on-success` / `:on-failure`; "
    "omitting all three throws :rf.error/http-no-reply-target at fx-call time, "
    "so the recipe cannot start a request — there is no co-located default that "
    "routes the reply back to the originating event (retired pre-alpha, "
    "rf2-et4c1s / PR #5449). Add an explicit reply target. (The machine-form "
    "`:spawn {:machine-id :rf.http/managed …}` dispatches back to its parent "
    "and is the allowed exception — it is not matched here.)"
)

# 5b — reading the RETIRED co-located reply key `(:rf/reply …)`. The current
#      contract has no implicit reply routed back to the originating event, so a
#      handler that reads `:rf/reply` teaches the removed recipe. The negative
#      lookahead excludes the internal descriptor `:rf/reply-to`; the transient
#      `:rf.reply/work-id` is a different token and never matches.
RF_REPLY_READ_RE = re.compile(r":rf/reply(?![-\w])")

# 5c — bare `:work/id` asserted as a TRANSIENT reply field. The reply map /
#      payload / envelope spells the attempt identity `:rf.reply/work-id`; bare
#      `:work/id` is the durable ledger / machine-`:data` / correlation identity.
#      Fires only in a reply-map context carrying bare `:work/id` UNLESS the line
#      also spells `:rf.reply/work-id` or marks the id as the durable / ledger /
#      machine-`:data` / correlation / verification / abstract identity.
REPLYMAP_CTX_RE = re.compile(
    r"reply map|reply payload|reply envelope|transient reply", re.IGNORECASE
)
BARE_WORKID_RE = re.compile(r"`:work/id`")
WORKID_ALLOW_RE = re.compile(
    r":rf\.reply/work-id|ledger|durab|verification|abstract|:data|correlat",
    re.IGNORECASE,
)


def reply_contract_problems(line: str) -> list[str]:
    """Rule 5b/5c — retired Managed-HTTP reply-contract teaching on a single
    line: reading the removed `:rf/reply` co-located key, or spelling bare
    `:work/id` on a transient reply map. (5a is a cross-line fenced-block shape,
    scanned separately in `managed_http_recipe_problems`.)"""
    problems: list[str] = []
    if RF_REPLY_READ_RE.search(line):
        problems.append(
            "HTTP-REPLY-CO-LOCATED: `:rf/reply` is the RETIRED co-located reply "
            "key. The current Managed HTTP contract routes replies to an EXPLICIT "
            "target (`:reply-to` / `:on-success` / `:on-failure`), not back to "
            "the originating event, and omitting a target throws "
            ":rf.error/http-no-reply-target. Don't read `:rf/reply` — branch on "
            "`(:status reply)` in the reply handler (spec/Managed-Effects.md, "
            "patterns/managed-http.md)."
        )
    if (
        REPLYMAP_CTX_RE.search(line)
        and BARE_WORKID_RE.search(line)
        and not WORKID_ALLOW_RE.search(line)
    ):
        problems.append(
            "HTTP-REPLY-WORKID-SPELLING: the TRANSIENT reply map spells the "
            "attempt identity `:rf.reply/work-id`; bare `:work/id` is the durable "
            "ledger / machine-`:data` / correlation identity. Don't put bare "
            "`:work/id` on the reply map (one fact, two spellings across the "
            "record↔reply boundary — spec/Managed-Effects.md)."
        )
    return problems


def managed_http_recipe_problems(text: str) -> list[tuple[int, str]]:
    """Rule 5a — a fenced code block whose fx-form `[:rf.http/managed …]`
    invocation carries a `:request` args map but names no reply target is the
    retired targetless canonical recipe. Returns (opening-fence-lineno, message)
    tuples. Scanned per-FENCED-BLOCK (the tokens span lines of one block); the
    machine-form `:machine-id :rf.http/managed` spawn and the
    `[:rf.http/managed-abort …]` dispatch are deliberately not matched."""
    problems: list[tuple[int, str]] = []
    for block_start, body in fenced_blocks(text):
        if (
            FX_MANAGED_RE.search(body)
            and HTTP_REQUEST_RE.search(body)
            and not HTTP_REPLY_TARGET_RE.search(body)
        ):
            problems.append((block_start, MANAGED_HTTP_TARGETLESS_MSG))
    return problems


# --- Rule 6: UIx/Helix hooks stateful-component recipe guidance, causally exact
#     (rf2-gq9bg — follow-up to the rf2-adm10 repair).
#     The hooks adapters (UIx / Helix) bridge a stateful component with an
#     ordinary `defui` / `defnc` plus the adapter's `use-subscribe` (read subs)
#     and `use-frame` (carry the frame) hooks — NOT the Reagent `reg-view` macro,
#     a render-time / bare no-arg `capture-frame`, or a `:contextType`.
#     `use-frame` is `capture-frame` in hook position, reading the surrounding
#     frame-provider / frame-root through React context (which a bare no-arg
#     `capture-frame` in a plain hooks component cannot); `reg-view*` on these
#     adapters is optional registry addressing, never the frame wiring.
#
#     The previous guard was NOT causally exact. Rule 6a checked only WHOLE-FILE
#     presence of `use-frame` / `use-subscribe`, so a token-only semantic reversal
#     ("Do not use use-frame or use-subscribe; use reg-view instead.") passed —
#     both tokens are present. Rule 6b was a line rule that suppressed ANY line
#     carrying a broad negation cue, so "UIx is not special: reg-view* gives it
#     :contextType." passed on the stray far-away "not", while the valid
#     comparison "UIx/Helix differ from Reagent's :contextType mechanism." was
#     falsely REJECTED. This rewrite is SENTENCE-scoped (a bounded recipe-sentence
#     carve — NOT a general prose parser): a mixed Reagent+hooks physical line is
#     checked per sentence, so a `:contextType` attributed to the Reagent wrapper
#     never bleeds into the hooks sentence, and a far-away negation cannot mask a
#     real attribution.
#
#     6a — COHERENCE FLOOR. Each authoritative hooks-guidance leaf (patterns/
#          stateful-components.md, references/fundamentals/frames.md, and
#          references/fundamentals/views.md — the per-adapter recipe table leaf,
#          anchored under rf2-05kex after its table row drifted) MUST carry
#          at least one COHERENT recipe sentence naming an ordinary `defui` /
#          `defnc`, `use-subscribe`, AND `use-frame` together — proving the
#          relationship, not just the scattered presence of two tokens.
#     6b — no RESIDUE SHAPE in any hooks-recipe sentence (scanned leaf-wide; a
#          hooks-recipe sentence names a hooks adapter or one of the two hooks):
#            (i)   a `:contextType` positively ATTRIBUTED to a hooks adapter
#                  ("reg-view* gives it :contextType") — fires even under a stray
#                  far "not"; a Reagent-owned / comparative `:contextType`
#                  ("Reagent's :contextType", "differ from Reagent's :contextType")
#                  and an explicit tightly-scoped "no `:contextType`" stay green;
#            (ii)  a token-only semantic reversal ("do not use use-frame /
#                  use-subscribe", or "use reg-view instead");
#            (iii) a positive registration of the hooks component via the Reagent
#                  `reg-view` MACRO (bare `reg-view`, not the lawful `reg-view*`);
#            (iv)  a bare no-arg `capture-frame` used as the hooks frame-carry
#                  (`(:dispatch (rf/capture-frame))`, or a positive "carry … with
#                  a (rf/capture-frame)") — the negated warnings that bare no-arg
#                  capture-frame CANNOT read React context stay green.
HOOKS_LEAF_REQUIRED = (
    ("patterns", "stateful-components.md"),
    ("references", "fundamentals", "frames.md"),
    # rf2-05kex — the views leaf carries the per-adapter recipe table. Its table
    # row had drifted (an ordinary hooks view classified as `reg-view*` on a
    # `defui` / `defnc`) while its own paragraph taught the lawful ordinary
    # `defui`/`defnc` + `use-subscribe` + `use-frame` recipe — a contradiction the
    # coherence floor missed because it did not anchor this leaf. Anchor it.
    ("references", "fundamentals", "views.md"),
)
HOOKS_ADAPTER_RE = re.compile(r"\b(?:UIx|Helix)\b")
# A "hooks-recipe sentence" names a hooks adapter OR one of the two hooks — so a
# semantic reversal naming only `use-frame` / `use-subscribe` (no adapter) is in
# scope too.
HOOKS_CTX_RE = re.compile(r"\b(?:UIx|Helix)\b|\buse-frame\b|\buse-subscribe\b")
COMPONENT_FORM_RE = re.compile(r"\bdef(?:ui|nc)\b")
USE_SUBSCRIBE_RE = re.compile(r"\buse-subscribe\b")
USE_FRAME_RE = re.compile(r"\buse-frame\b")

# Bounded sentence carve: split a physical line on a sentence terminator (`.` or
# `)`) + whitespace + a sentence start (capital / markdown `*` / backtick / `(`).
# This isolates the hooks sentence from a co-located Reagent sentence on the same
# line. It is NOT a general prose parser — it only carves recipe sentences for
# the co-location checks below.
SENTENCE_SPLIT_RE = re.compile(r"(?<=[.)])\s+(?=[A-Z*`(])")


def _hooks_sentences(text: str):
    """Yield (lineno, sentence) for every sentence naming a hooks adapter or one
    of the two hooks (`use-frame` / `use-subscribe`)."""
    for lineno, line in enumerate(text.splitlines(), start=1):
        for sent in SENTENCE_SPLIT_RE.split(line):
            if HOOKS_CTX_RE.search(sent):
                yield lineno, sent


# 6b(i) — `:contextType` attributed to a hooks adapter.
#
# ATTRIBUTION SCOPE, not proximity (rf2-xlxrl). The retired
# CONTEXTTYPE_REAGENT_OWNED_RE suppressed the whole sentence whenever "Reagent"
# appeared within ~40 chars of the token (both directions, plus a comparison-cue
# arm). That is mere adjacency: it pardoned an unlawful attribution sitting next
# to a lawful Reagent mention ("`reg-view*` gives the component a
# `:contextType`; the `reg-view` macro stays Reagent-flavoured") while, at
# natural prose distances, failing to protect the corpus's own per-adapter
# house style. It is deleted. 6b(i) now carves the sentence into CLAUSES and
# asks who each clause attributes the token TO.
CONTEXTTYPE_RE = re.compile(r":contextType\b")
# Strong clause separators: `;`, em dash, and the contrast connectives. NOT `:`
# — a colon introduces an elaboration of the same subject, so splitting there
# would let "UIx is not special: reg-view* gives it :contextType." escape.
CLAUSE_SPLIT_RE = re.compile(
    r"\s*;\s*|\s*—\s*|,\s+(?:while|whereas|but)\b",
    re.IGNORECASE,
)
REAGENT_RE = re.compile(r"\bReagent\b")
# The one Reagent-ownership allowance that survives: the possessive / compound
# bound TIGHTLY to the token, so the owner is named at the token itself. This
# keeps "UIx components never get Reagent's `:contextType`." green, where the
# 8-char negation window below cannot reach.
REAGENT_OWNED_CONTEXTTYPE_RE = re.compile(
    r"\bReagent(?:'s|-only)\s*`?\s*:contextType",
    re.IGNORECASE,
)
# The clause explicitly says the hooks adapter has NO `:contextType` — the
# negation must TIGHTLY scope the token (a far-away "not special" does NOT
# suppress a real attribution).
CONTEXTTYPE_NEGATED_RE = re.compile(
    r"\b(?:no|not|never|without|needs?\s+no|has\s+no|have\s+no"
    r"|no\s+need\s+(?:for|of)?)\b[^.\n]{0,8}:contextType",
    re.IGNORECASE,
)
# A positive attribution verb governs the `:contextType` (the retired "reg-view*
# gives UIx a :contextType" teaching).
CONTEXTTYPE_ATTRIB_RE = re.compile(
    r"\b(?:gives?|giving|grants?|granting|provides?|providing"
    r"|attach(?:es|ing)?|gets?|getting|has|have|carr(?:y|ies|ying))\b"
    r"[^.\n]{0,24}:contextType",
    re.IGNORECASE,
)

# 6b(ii) — semantic reversal: steering hooks authors to the Reagent reg-view macro.
REVERSAL_DISCOURAGE_RE = re.compile(
    r"\b(?:do not|don'?t|never|avoid)\s+us(?:e|ing)\b"
    r"[^.\n]{0,40}\buse-(?:frame|subscribe)\b",
    re.IGNORECASE,
)
REVERSAL_STEER_RE = re.compile(
    r"\buse\b[^.\n]{0,12}\breg-view\b(?!\*)[^.\n]{0,15}\binstead\b"
    r"|\breg-view\b(?!\*)[^.\n]{0,20}\binstead\s+of\b"
    r"[^.\n]{0,24}\buse-(?:frame|subscribe)\b",
    re.IGNORECASE,
)

# 6b(iii) — positive registration via the Reagent `reg-view` MACRO (bare
# `reg-view`, not the lawful optional `reg-view*` plain-fn registry addressing).
REGVIEW_MACRO_POSITIVE_RE = re.compile(
    r"\b(?:register(?:ed|s|ing)?|wrap(?:ped|s|ping)?|via|through)\b"
    r"[^.\n]{0,20}\breg-view\b(?!\*)",
    re.IGNORECASE,
)
REGVIEW_MACRO_NEGATED_RE = re.compile(
    r"\b(?:no|not|never|without|needs?\s+no|drop|skip"
    r"|no\s+need\s+(?:for|of)?)\b[^.\n]{0,12}\breg-view\b(?!\*)",
    re.IGNORECASE,
)

# 6b(iv) — bare no-arg `capture-frame` as the hooks frame-carry. `capture` is
# deliberately NOT a carry verb below, so the legitimate Reagent Form-3 line
# "then capture `(rf/capture-frame)` from the body" stays green.
BARE_CAPTURE_OPS_RE = re.compile(
    r"\(:(?:dispatch|subscribe)\s+\(\s*(?:rf/)?capture-frame\s*\)\s*\)"
)
BARE_CAPTURE_CARRY_RE = re.compile(
    r"\b(?:carry|carries|carrying|hold|holding|dispatch(?:es|ing)?)\b"
    r"[^.\n]{0,40}\(\s*(?:rf/)?capture-frame\s*\)",
    re.IGNORECASE,
)
BARE_CAPTURE_NEGATED_RE = re.compile(
    r"\(\s*(?:rf/)?capture-frame\s*\)[^.\n]{0,40}"
    r"\b(?:cannot|can'?t|re-?raises?|is not a hook)\b"
    r"|\b(?:rather than|instead of)\b[^.\n]{0,30}"
    r"(?:render-time\s+)?`?\(?\s*(?:rf/)?capture-frame",
    re.IGNORECASE,
)

HOOKS_CONTEXTTYPE_MSG = (
    'HOOKS-CONTEXTTYPE: this clause attributes a Reagent `:contextType` to a '
    'hooks adapter (UIx / Helix) — "{clause}". `:contextType` is Reagent\'s '
    "class-component mechanism; the hooks adapters read the frame through the "
    "`use-subscribe` / `use-frame` hooks (React context in hook position) and "
    "have NO `:contextType`. `reg-view*` on these adapters is optional registry "
    "addressing, never a source of a `:contextType`. Either say the hooks "
    "adapter has *no* `:contextType`, or split the Reagent comparison into a "
    "separate clause (`;`, an em dash, or `, while` / `, whereas` / `, but`) so "
    "the `:contextType` sits in the clause that names Reagent."
)
HOOKS_CONTEXTTYPE_AMBIGUOUS_MSG = (
    "HOOKS-CONTEXTTYPE-OWNERLESS: this clause of a hooks-recipe sentence "
    'attributes a `:contextType` but names neither adapter — "{clause}". '
    "Inside a sentence that names UIx / Helix, an ownerless attribution reads "
    "as the hooks adapter's, and `:contextType` is Reagent-only. Name the owner "
    "in the clause (e.g. \"Reagent's `:contextType`\"), or split the sentence so "
    "the clause carries its own subject."
)
HOOKS_REVERSAL_MSG = (
    "HOOKS-REVERSAL: this hooks-recipe sentence steers UIx / Helix authors AWAY "
    "from the hook idiom — discouraging `use-frame` / `use-subscribe`, or "
    "sending them to the Reagent `reg-view` macro instead. On the hooks adapters "
    "an ordinary `defui` / `defnc` reads subs with `use-subscribe` and carries "
    "the frame with the `use-frame` hook; `reg-view` is Reagent-only."
)
HOOKS_REGVIEW_MSG = (
    "HOOKS-REGVIEW-MACRO: this hooks-recipe sentence registers the UIx / Helix "
    "component via the Reagent `reg-view` MACRO. `reg-view` (and its injected "
    "locals / `:contextType` wiring) is Reagent-only; a hooks component is an "
    "ordinary `defui` / `defnc` that reads the frame through `use-subscribe` / "
    "`use-frame`. `reg-view*` (the plain-fn surface) is optional registry "
    "addressing only — never the frame wiring."
)
HOOKS_CAPTURE_MSG = (
    "HOOKS-BARE-CAPTURE: this hooks-recipe sentence carries the frame with a "
    "bare no-arg `capture-frame` (e.g. `(:dispatch (rf/capture-frame))`). On the "
    "hooks adapters a no-arg `capture-frame` reads only the dynamic-var tier and "
    "raises under a context-only frame — carry the frame with the `use-frame` "
    "hook (capture-frame in hook position, which reads React context) instead."
)


def contexttype_attribution_problem(sentence: str) -> str | None:
    """Rule 6b(i) — WHO does this sentence attribute the `:contextType` to?

    Clause-scoped, not sentence-scoped and not proximity-suppressed: a sentence
    may lawfully hand `:contextType` to Reagent in one clause and the hooks
    spelling to UIx / Helix in the next, so each clause is judged on the subject
    it names. Only a sentence that mentions a hooks adapter is in scope at all.
    """
    if not HOOKS_ADAPTER_RE.search(sentence):
        return None
    for clause in CLAUSE_SPLIT_RE.split(sentence):
        # Only a positive attribution of the token is a candidate; an explicit
        # "no `:contextType`" and a Reagent-owned token are both lawful.
        if not (CONTEXTTYPE_RE.search(clause) and CONTEXTTYPE_ATTRIB_RE.search(clause)):
            continue
        if CONTEXTTYPE_NEGATED_RE.search(clause) or REAGENT_OWNED_CONTEXTTYPE_RE.search(clause):
            continue
        if HOOKS_ADAPTER_RE.search(clause):
            # The clause names the hooks adapter AND hands it the token.
            return HOOKS_CONTEXTTYPE_MSG.format(clause=clause.strip())
        if REAGENT_RE.search(clause):
            # A Reagent clause is not a hooks clause — 6b(i) never judges it.
            continue
        # Ownerless: no subject in the clause, but the sentence is about a hooks
        # adapter, so the attribution lands there by default. Ask for a rewrite.
        return HOOKS_CONTEXTTYPE_AMBIGUOUS_MSG.format(clause=clause.strip())
    return None


def hooks_sentence_problems(sentence: str) -> list[str]:
    """Rule 6b — the residue shapes a hooks-recipe sentence must not carry."""
    problems: list[str] = []

    # (i) :contextType attributed to a hooks adapter.
    contexttype_problem = contexttype_attribution_problem(sentence)
    if contexttype_problem:
        problems.append(contexttype_problem)

    # (ii) token-only semantic reversal.
    if REVERSAL_DISCOURAGE_RE.search(sentence) or REVERSAL_STEER_RE.search(sentence):
        problems.append(HOOKS_REVERSAL_MSG)

    # (iii) positive reg-view MACRO registration of the hooks component.
    if (
        HOOKS_ADAPTER_RE.search(sentence)
        and REGVIEW_MACRO_POSITIVE_RE.search(sentence)
        and not REGVIEW_MACRO_NEGATED_RE.search(sentence)
    ):
        problems.append(HOOKS_REGVIEW_MSG)

    # (iv) bare no-arg capture-frame as the hooks carry.
    if BARE_CAPTURE_OPS_RE.search(sentence) or (
        HOOKS_ADAPTER_RE.search(sentence)
        and BARE_CAPTURE_CARRY_RE.search(sentence)
        and not BARE_CAPTURE_NEGATED_RE.search(sentence)
    ):
        problems.append(HOOKS_CAPTURE_MSG)

    return problems


def _coherent_recipe_sentence(text: str) -> str | None:
    """The first sentence co-locating an ordinary `defui`/`defnc`, `use-subscribe`,
    and `use-frame` — the canonical UIx/Helix stateful recipe block."""
    for _lineno, sent in _hooks_sentences(text):
        if (
            HOOKS_ADAPTER_RE.search(sent)
            and COMPONENT_FORM_RE.search(sent)
            and USE_SUBSCRIBE_RE.search(sent)
            and USE_FRAME_RE.search(sent)
        ):
            return sent
    return None


def hooks_leaf_incoherent(text: str) -> bool:
    """Rule 6a — True when NO sentence in the leaf co-locates an ordinary
    `defui`/`defnc`, `use-subscribe`, AND `use-frame` (the coherent recipe went
    missing or was scattered across sentences)."""
    return _coherent_recipe_sentence(text) is None


# --- Rule 6c (rf2-szw6c): BOUNDED BLOCK anchors on the three concrete recipe
#     blocks. Rule 6a is a leaf-wide floor, so it is satisfied by ANY coherent
#     sentence in the leaf: the views leaf's per-adapter TABLE can regress to
#     the retired "required `reg-view*` on a `defui`/`defnc`" cell while its own
#     adjacent paragraph stays lawful and the floor still passes. And two of the
#     bounded authorities (the setup skill's greenfield warning, the Spec 006
#     paragraph) are not in `scanned_files()` at all, so no rule reached them.
#
#     Each anchor names ONE block by a stable lead-in and checks it in place:
#       * the block MUST itself be a coherent recipe (ordinary `defui`/`defnc` +
#         `use-subscribe` + `use-frame` in one sentence) — the same Rule 6a
#         predicate, applied at block scope rather than leaf scope; and
#       * no sentence of the block may carry a Rule 6b residue shape.
#     Only the anchored block is read — never the whole file. That is what makes
#     it safe to anchor `spec/006-ReactiveSubstrate.md`, which lawfully carries
#     bead ids (Rule 1) and Reagent-owned `:contextType` prose elsewhere.
HOOKS_ANCHORED_BLOCKS = (
    (
        ("skills", "re-frame2", "references", "fundamentals", "views.md"),
        "the per-adapter table's UIx ordinary-view row",
        re.compile(r"^\|\s*\*\*UIx\*\*\s*\|"),
    ),
    # (The per-adapter table's Helix row was retired with the S7 Helix removal
    # — only Helix left the roster; the UIx row above stays anchored.)
    (
        ("skills", "re-frame2-setup", "references", "first-counter.md"),
        "the 'Reagent only' greenfield warning",
        re.compile(r"^>\s*\*\*Reagent only\.\*\*"),
    ),
    (
        ("spec", "006-ReactiveSubstrate.md"),
        "the canonical UIx hooks paragraph under the adapter inventory",
        # Tolerates both the pre-S7 "The UIx and Helix rows ..." spelling and
        # the post-Helix-removal "The UIx row(s) ..." rewording.
        re.compile(r"^The UIx (?:and Helix )?rows?\b"),
    ),
)

# A block runs from its anchor line to the next Markdown block boundary: a blank
# line, a new table row (`|`), a heading, or a fence. Ordinary wrapped prose
# continues (its continuation lines start none of those), so the relationship
# checks tolerate Markdown wrapping; a sibling table row never bleeds in.
BLOCK_BREAK_RE = re.compile(r"^\s*$|^\s*\||^\s*#|^\s*```")


def anchored_block(text: str, anchor_re: re.Pattern[str]) -> tuple[int, str] | None:
    """The bounded block introduced by `anchor_re`: the anchor line plus its
    wrapped continuation lines, joined into one logical line. Returns
    (lineno, block) or None when the anchor is absent."""
    lines = text.splitlines()
    for idx, line in enumerate(lines):
        if anchor_re.search(line):
            block = [line]
            for cont in lines[idx + 1:]:
                if BLOCK_BREAK_RE.match(cont):
                    break
                block.append(cont.strip())
            return idx + 1, " ".join(block)
    return None


def anchored_block_problems(label: str, block: str) -> list[str]:
    """Rule 6c — the bounded block must itself hold the coherent hooks recipe
    and carry no Rule 6b residue shape."""
    problems: list[str] = []
    if _coherent_recipe_sentence(block) is None:
        problems.append(
            f"HOOKS-BLOCK-INCOHERENT: {label} no longer states the hooks recipe "
            "in place — an ordinary `defui` / `defnc` that reads subs with "
            "`use-subscribe` and carries the frame with `use-frame`. This block "
            "is a bounded authority: it is the concrete recipe a programmer or "
            "a model copies, so a correct paragraph elsewhere in the file does "
            "NOT cover it. `reg-view*` on these adapters is OPTIONAL registry "
            "addressing (see examples/substrates/uix/counter/core.cljs — a "
            "plain `defui` with `use-subscribe` + `use-frame` and no "
            "`reg-view*`); it is never required, and never the frame wiring."
        )
    for sent in SENTENCE_SPLIT_RE.split(block):
        if HOOKS_CTX_RE.search(sent):
            problems.extend(hooks_sentence_problems(sent))
    return problems


# --- Rule 7 (rf2-1iclq): the form-action pattern's two FAIL-OPEN shapes,
#     checked inside code fences at BOTH ends of the projection.
#
#     Rules 1-6 are retrospective token patterns over a leaf's own text, and
#     none of them relates a leaf to the spec page it projects — see "WHAT THIS
#     GUARD IS, AND IS NOT" in the module docstring. This rule does not close
#     that gap in general (it cannot; the leaves are abridged, not copies). It
#     closes it for the two shapes where being wrong is a security defect rather
#     than a teaching wobble, and it checks them on the spec page too, so the
#     source of truth cannot regress underneath the leaves either.
#
#     FENCED BLOCKS ONLY, and that is the semantics rather than a convenience:
#     both pages teach the wrong shape deliberately, in inline prose, as an
#     explicitly labelled "not this". A reader skimming for code to copy reads
#     fences — so a fence must show only the correct form.
#
#     7a — FAIL-OPEN CSRF COMPARE. `(not= submitted active-token)` fails OPEN on
#          a request with no session: `active-token` is nil, a token-less POST
#          supplies nil, nil = nil, and the rejection arm never fires. Stated
#          POSITIVELY rather than as a blacklist of bad spellings — any fenced
#          CSRF equality comparison must carry a separate presence limb on the
#          session token — so `(when-not (= …))` and `(if (= …))` are caught by
#          the same rule, not by a second regex someone has to think to add.
#     7b — THE TOKEN REQUIRED IN A FIELD SCHEMA. `[:csrf-token [:string …]]`
#          with no `{:optional true}` props map rejects every hydrated-client
#          submission in the RELEASE build (the handler's validation arm is
#          ordinary code and does not elide, unlike the `:schema` tripwire), and
#          makes the form slice's `:draft` unsatisfiable by construction, since
#          the failure arm must never write a credential into a slice the page
#          re-renders.
#
#     Beyond the globbed skill leaves, Rule 7 also reads the spec pattern page —
#     the projection's source. Bounded to whole files that carry CSRF fences, in
#     the spirit of the Rule 6c anchors.
CSRF_EXTRA_FILES = (("spec", "Pattern-FormAction.md"),)

CSRF_TOKEN_RE = re.compile(r"csrf", re.IGNORECASE)
# A comparison head, as a form start.
CSRF_COMPARE_HEAD_RE = re.compile(r"\(\s*(?:not=|=)\s")
# How far past the comparison head a `csrf` mention still counts as the subject
# of that comparison.
CSRF_COMPARE_WINDOW = 140
# The fail-CLOSED limb: the session token's PRESENCE, asserted separately from
# the equality. `(some? active-token)` is the worked spelling; `nil?` and `seq`
# are the same assertion written the other way round.
CSRF_PRESENCE_LIMB_RE = re.compile(r"\bsome\?|\bnil\?|\(\s*seq\s")

CSRF_FAIL_OPEN_MSG = (
    "CSRF-FAIL-OPEN: this fenced block compares a CSRF token for equality but "
    "carries no PRESENCE limb on the session token, so the rejection arm fails "
    "OPEN. `(not= (:csrf-token form-params) active-token)` does not fire on a "
    "request with no session: `active-token` is nil, an attacker's token-less "
    "POST supplies nil, and nil = nil waves it through. Both limbs must hold — "
    "`(not (and (some? active-token) (= (:csrf-token form-params) "
    "active-token)))` (spec/Pattern-FormAction.md §Anti-patterns, §Conformance "
    "checklist). A fence is what a reader copies, so the fail-closed form must "
    "be the only one shown here; keep the `not=` shape as a labelled 'not this' "
    "in inline prose, which this rule deliberately does not read."
)
CSRF_REQUIRED_IN_SCHEMA_MSG = (
    "CSRF-TOKEN-REQUIRED-IN-SCHEMA: this fenced block declares `:csrf-token` as "
    "a REQUIRED map entry (no `{:optional true}` props map). The hydrated client "
    "has no session token to send, so a token-requiring schema pointed at the "
    "handler's validation call rejects every client submission — and that arm is "
    "ordinary handler code, NOT the elided `:schema` tripwire, so the form 400s "
    "in the release build and never navigates. It also makes the form slice's "
    "`:draft` unsatisfiable, since the failure arm must never write a credential "
    "into a slice the page re-renders. Split the schemas: the editable FIELDS "
    "type the draft and the validation call; the token rides the event-args "
    "envelope as `[:csrf-token {:optional true :sensitive? true} …]` and is "
    "checked in the server-guarded CSRF arm (spec/Pattern-FormAction.md "
    "§The form schema and slice)."
)

# `[:csrf-token` followed by its optional props map. A required entry has no
# props map at all, or one that does not mark the entry `:optional`.
CSRF_SCHEMA_ENTRY_RE = re.compile(r"\[:csrf-token\b[ \t]*(\{[^}]*\})?")


def _code_only(block: str) -> str:
    """The block with string literals and `;` comments blanked to spaces —
    offsets preserved, so a match index still points into the original. Paren
    balance is only meaningful over code, and a fence's prose comments carry
    both parens and (deliberately) the anti-pattern's own spelling."""
    out = list(block)
    i, n = 0, len(block)
    while i < n:
        ch = block[i]
        if ch == '"':
            j = i + 1
            while j < n and block[j] != '"':
                j += 2 if block[j] == "\\" else 1
            for k in range(i, min(j + 1, n)):
                out[k] = " "
            i = j + 1
        elif ch == ";":
            j = block.find("\n", i)
            j = n if j == -1 else j
            for k in range(i, j):
                out[k] = " "
            i = j
        else:
            i += 1
    return "".join(out)


def _enclosing_form(code: str, idx: int) -> str:
    """The balanced form one level OUT from the form opening at `idx` — the
    rejection expression the comparison sits inside.

    Scoping the presence limb to THIS form rather than to the whole fence is
    what makes the rule honest. The worked handler's fence also contains
    `(some? explanation)` for the unrelated validation arm, so a block-wide
    search for a presence limb would have been satisfied by it and the guard
    would have exited 0 over the very defect it exists to catch — fail-open by
    construction, which is the shape of bug this rule was written for."""
    depth = 0
    start = None
    for i in range(idx - 1, -1, -1):
        c = code[i]
        if c == ")":
            depth += 1
        elif c == "(":
            if depth == 0:
                start = i
                break
            depth -= 1
    if start is None:
        return code
    depth = 0
    for i in range(start, len(code)):
        if code[i] == "(":
            depth += 1
        elif code[i] == ")":
            depth -= 1
            if depth == 0:
                return code[start:i + 1]
    return code[start:]


def csrf_fence_problems(text: str) -> list[tuple[int, str]]:
    """Rule 7 — the two fail-open form-action shapes, inside code fences.
    Returns (opening-fence-lineno, message) tuples."""
    problems: list[tuple[int, str]] = []
    for block_start, body in fenced_blocks(text):
        code = _code_only(body)
        # 7a — a CSRF equality comparison whose own rejection expression carries
        # no presence limb on the session token.
        for m in CSRF_COMPARE_HEAD_RE.finditer(code):
            window = code[m.start():m.start() + CSRF_COMPARE_WINDOW]
            if not CSRF_TOKEN_RE.search(window):
                continue
            if not CSRF_PRESENCE_LIMB_RE.search(_enclosing_form(code, m.start())):
                problems.append((block_start, CSRF_FAIL_OPEN_MSG))
                break
        # 7b — `:csrf-token` declared as a required map entry.
        for m in CSRF_SCHEMA_ENTRY_RE.finditer(code):
            if ":optional" not in (m.group(1) or ""):
                problems.append((block_start, CSRF_REQUIRED_IN_SCHEMA_MSG))
                break
    return problems


# --- Rule 8: the JVM `with-frame` thunk "function form" (rf2-jwmkq).
#     `rf/with-frame` is the same body-splicing macro on JVM and CLJS (there
#     is no function twin): it binds the frame and splices `~@body`, so a fn
#     literal supplied AS the body is evaluated and returned UNINVOKED. The
#     testing leaf taught exactly that shape to JVM authors, and a
#     `use-fixtures` wrapper written from it silently skipped every test body
#     — "Ran 0 tests containing 0 assertions.", zero failures, exit 0. The
#     regex matches a fn literal (or `#(...)` reader lambda) in `with-frame`'s
#     DIRECT body-head position only: ordinary fn literals nested deeper in
#     the body (a `reg-sub` handler, an event fn) sit behind a different head
#     token and never match, and `with-new-frame`'s binding-vector form is a
#     different macro name that never matches. Scanned over the WHOLE body —
#     fenced and inline alike — because the original defect was an inline
#     positive instruction ("On JVM use the ... function form"), which a
#     fence-only rule exits 0 over; a lawful warning quotes the bare fn
#     literal, never the full compound shape, so no negation carve is needed.
WITHFRAME_THUNK_RE = re.compile(
    r"\((?:rf/)?with-frame\s+[^\s()\[\]{}]+\s+(?:\(fn[\s(\[]|#\()"
)
WITHFRAME_THUNK_MSG = (
    "WITHFRAME-THUNK-RECIPE: a fn literal (or `#(...)` reader lambda) sits in "
    "`with-frame`'s direct body-head position — the retired JVM \"function "
    "form\" teaching. `rf/with-frame` is the same body-splicing MACRO on JVM "
    "and CLJS (there is no function twin): it evaluates the fn literal and "
    "returns it UNINVOKED, so a clojure.test fixture written this way "
    "silently skips every test body (\"Ran 0 tests containing 0 "
    "assertions.\", exit 0). Put the forms directly in the macro body; a "
    "fixture invokes `(test-fn)` INSIDE the binding: "
    "`(rf/with-frame :app/test (test-fn))`."
)


def withframe_thunk_problems(text: str) -> list[tuple[int, str]]:
    """Rule 8 — a fn literal in `with-frame`'s direct body-head position is
    the uninvoked-thunk footgun. Returns (lineno, message) tuples. Takes the
    whole file body (the shape spans lines inside a fence) and scans fenced
    and inline text alike — see the rule comment for why there is no
    negation/warning carve."""
    problems: list[tuple[int, str]] = []
    for m in WITHFRAME_THUNK_RE.finditer(text):
        lineno = text.count("\n", 0, m.start()) + 1
        problems.append((lineno, WITHFRAME_THUNK_MSG))
    return problems

# --- Rule 9: the canonical per-test frame recipe (rf2-u429). Two independent
#     copy-oriented faults in ONE fenced block, each of which a reader
#     reproduces by copying rather than by reasoning.
#
#     9a - AN UNQUALIFIED FRAME-SCOPING MACRO INSIDE A FENCE. `with-new-frame`
#          and `with-frame` are macros on `re-frame.core`. The namespace
#          self-`:require-macros` its own macros, so `(:require [re-frame.core
#          :as rf])` is the whole import a CLJS consumer needs - but that
#          `:refer` lands in `re-frame.core`, NOT in the consumer's namespace,
#          so the macro is reachable as `rf/with-new-frame` and NOT as a bare
#          `with-new-frame`. This is the generic-consumer failure the skill's
#          own standing rule exists to prevent: the bare spelling resolves only
#          where somebody has explicitly `:refer`-ed it.
#
#          It does NOT fail the way a typo usually does, which is why a fence
#          carrying it is worth a gate. ClojureScript grades an undeclared Var
#          as a WARNING: the build exits 0, and the emitted call swallows the
#          entire macro body into the undefined function's ARGUMENT LIST - so
#          the block dies with a `TypeError` at run time having asserted
#          nothing at all, and the binding vector's symbol never binds.
#          (Measured on a minimal consumer namespace aliasing only `rf`:
#          `rf/with-new-frame` compiled clean and ran 4/4 assertions; the bare
#          spelling compiled with one warning, exit 0, then threw at run time.)
#          Same silent-skip class as Rule 8's uninvoked thunk, reached through
#          the OTHER frame macro - which Rule 8 explicitly leaves out of scope.
#
#          FENCED BLOCKS ONLY, and deliberately: a leaf legitimately quotes the
#          bare spelling in prose as a labelled "not this" (frames.md does), and
#          the affordance TABLE names the bare macro as a name rather than as
#          code. A fence is what a reader copies. A block that establishes its
#          own `:refer` is left alone - that is a lawful, if unusual, import.
#
#     9b - AN APP-DB-ONLY READER HANDED TO `compute-sub` FOR A RUNTIME-BACKED
#          GRAPH. `app-db-value` returns the app-db partition ALONE. Machine
#          snapshots are durable RUNTIME-db state, and the framework subs that
#          expose them (`[:rf/machine ...]`, `[:rf.machine/has-tag? ...]`) are
#          registered with `reg-runtime-sub`, so `compute-sub`'s partition
#          selector finds nothing to satisfy that input and the sub computes
#          `nil` - no error, no warning, just a wrong value under a green
#          `assert`. The coherent whole is `frame-state-value`, which carries
#          both partitions in one snapshot.
#
#          Bounded to a fence that ALREADY names a framework runtime-db sub, so
#          the many legitimate app-db-only `compute-sub` examples elsewhere in
#          the corpus (`(rf/compute-sub [:item-sum] {:items [10 20 30]})`) are
#          untouched - that smaller form stays correct and stays taught.
#
#     9c - THE CANONICAL BLOCK ITSELF, as a bounded anchor. 9a and 9b refuse
#          the two bad shapes; they cannot notice the recipe being reworded out
#          of existence. The block under `## Canonical mini-example` is THE
#          copy-oriented recipe for a per-test frame, so it is additionally
#          required to state both correct spellings in place - the same
#          "bounded authority" treatment Rule 6c gives the hooks blocks.

FENCED_FRAME_MACRO_RE = re.compile(r"\((with-new-frame|with-frame)[\s\[]")
# A block that establishes its own refer is lawfully using the bare spelling.
FENCE_REFER_RE = re.compile(r":refer\b")

UNQUALIFIED_FRAME_MACRO_MSG = (
    "UNQUALIFIED-FRAME-MACRO: this fenced recipe calls `({name} ...)` "
    "unqualified. `{name}` is a macro on `re-frame.core`, and the namespace's "
    "self-`:require-macros` refers it into `re-frame.core` - NOT into the "
    "consumer's namespace - so under the `[re-frame.core :as rf]` convention "
    "every other form in these leaves uses, the bare symbol is an UNDECLARED "
    "VAR in any ordinary consumer namespace. ClojureScript grades that as a "
    "warning, not an error: the build exits 0, the emitted call swallows the "
    "whole macro body into the undefined function's argument list, and the "
    "block throws a TypeError at run time having asserted nothing - the Rule 8 "
    "silent-skip class reached through the other frame macro. Spell it "
    "`rf/{name}`. A labelled 'not this' mention in PROSE is legal and this rule "
    "does not read it; a fence is what a reader copies."
)

COMPUTE_SUB_TOKEN_RE = re.compile(r"\bcompute-sub\b")
APP_DB_VALUE_TOKEN_RE = re.compile(r"\bapp-db-value\b")
# The framework subs whose source partition is runtime-db (reg-runtime-sub).
RUNTIME_BACKED_SUB_RE = re.compile(r"\[:rf/machine\b|\[:rf\.machine/has-tag\?")
# How far past `compute-sub` its db-position argument can still be.
COMPUTE_SUB_ARG_WINDOW = 160

COMPUTE_SUB_PARTITION_MSG = (
    "COMPUTE-SUB-WRONG-PARTITION: this fenced recipe hands `app-db-value` to "
    "`compute-sub` in a block whose subscription graph reads a framework "
    "RUNTIME-db sub (`[:rf/machine ...]` / `[:rf.machine/has-tag? ...]`). "
    "`app-db-value` returns the app-db partition ALONE, so that input is never "
    "satisfied and the sub computes `nil` - silently, under whatever the "
    "example asserts. Machine snapshots are durable runtime-db state. Pass the "
    "coherent whole instead: `(rf/frame-state-value f)` carries both partitions "
    "in one snapshot and resolves a mixed app/runtime graph in one call. This "
    "rule is bounded to fences that already name a runtime-backed framework "
    "sub - an app-db-only `compute-sub` (every input a `:db` sub) is the "
    "smaller correct form and stays legal."
)


def unqualified_frame_macro_problems(text: str) -> list[tuple[int, str]]:
    """Rule 9a - a bare `with-new-frame` / `with-frame` head inside a code
    fence. Returns (lineno, message) tuples. Takes the whole file body so the
    self-test can pass a fenced fixture directly; prose mentions never enter a
    fenced block, so a labelled 'not this' stays legal."""
    problems: list[tuple[int, str]] = []
    for block_start, body in fenced_blocks(text):
        if FENCE_REFER_RE.search(body):
            continue
        for m in FENCED_FRAME_MACRO_RE.finditer(body):
            lineno = block_start + 1 + body.count("\n", 0, m.start())
            problems.append(
                (lineno, UNQUALIFIED_FRAME_MACRO_MSG.format(name=m.group(1)))
            )
    return problems


def compute_sub_partition_problems(text: str) -> list[tuple[int, str]]:
    """Rule 9b - `compute-sub` handed an `app-db-value` reader inside a fence
    that names a runtime-db-backed framework sub. Returns (lineno, message)
    tuples."""
    problems: list[tuple[int, str]] = []
    for block_start, body in fenced_blocks(text):
        if not RUNTIME_BACKED_SUB_RE.search(body):
            continue
        for m in COMPUTE_SUB_TOKEN_RE.finditer(body):
            window = body[m.end():m.end() + COMPUTE_SUB_ARG_WINDOW]
            if APP_DB_VALUE_TOKEN_RE.search(window):
                lineno = block_start + 1 + body.count("\n", 0, m.start())
                problems.append((lineno, COMPUTE_SUB_PARTITION_MSG))
    return problems


# Rule 9c anchor - the leaf and the heading whose following fence is THE
# canonical per-test frame recipe.
CANONICAL_FRAME_LEAF = (
    "skills", "re-frame2", "references", "fundamentals", "frames.md")
CANONICAL_BLOCK_HEADING_RE = re.compile(
    r"(?im)^#{2,3}\s+Canonical mini-example\s*$")
# What that block must state IN PLACE, each the repair of one rf2-u429 fault.
CANONICAL_BLOCK_REQUIRED = (
    ("rf/with-new-frame",
     "the frame macro alias-qualified (a bare `with-new-frame` is an "
     "undeclared var in a consumer namespace - Rule 9a)"),
    ("rf/frame-state-value",
     "the coherent frame-state handed to `compute-sub` (the assertion's sub "
     "reads a runtime-db-backed machine snapshot, which `app-db-value` cannot "
     "see - Rule 9b)"),
)


def canonical_frame_block(text: str) -> tuple[int, str] | None:
    """The first fenced block after the `## Canonical mini-example` heading:
    (opening-fence-lineno, block-body), or None when the heading is absent."""
    m = CANONICAL_BLOCK_HEADING_RE.search(text)
    if m is None:
        return None
    heading_lineno = text.count("\n", 0, m.start()) + 1
    for block_start, body in fenced_blocks(text):
        if block_start > heading_lineno:
            return block_start, body
    return None


def canonical_frame_block_problems(block: str) -> list[str]:
    """Rule 9c - the bounded canonical block must state both correct spellings
    in place. A correct paragraph elsewhere in the leaf does NOT cover it: this
    block is what a programmer or a model copies."""
    problems: list[str] = []
    for token, why in CANONICAL_BLOCK_REQUIRED:
        if token not in block:
            problems.append(
                "CANONICAL-FRAME-RECIPE-INCOMPLETE: the canonical per-test "
                f"frame block no longer shows `{token}` - {why}. This block is "
                "a bounded authority: it is the recipe copied verbatim into "
                "consumer test suites, so restore the spelling here rather "
                "than relying on the surrounding prose."
            )
    return problems



# --- Rule 3: launcher points at BOTH canonical files, without regrowing the
#     tree / locks sections. Operates on the whole authoring-prompt.md body
#     (not the line-by-line leaf scan). `design.md` / `inputs.md` are the
#     normative source; the launcher must cite each so a reauthoring session
#     reads them first, and must not paste a second copy of the material they
#     own.
DESIGN_REF_RE = re.compile(r"\bdesign\.md\b")
INPUTS_REF_RE = re.compile(r"\binputs\.md\b")
# A regrown file-structure tree: box-drawing branch glyphs (├── / └── / │) —
# the only reason they appear in this prose is a copied directory listing.
TREE_REGROWTH_RE = re.compile(r"[├└]──")
# A regrown normative block: the copied "Locks to preserve verbatim" or
# "Cardinal rules to bake in" heading/label the launcher used to carry.
LOCKS_REGROWTH_RE = re.compile(
    r"(?im)^\s*[>*#\s-]*\**\s*(?:locks to preserve verbatim"
    r"|cardinal rules to bake in)\b"
)


def launcher_problems(text: str) -> list[str]:
    """Rule 3 — the authoring-prompt.md launcher must point at both canonical
    files and must not regrow the tree / locks sections. Takes the raw body so
    the self-test can pass fixtures directly."""
    problems: list[str] = []
    if not DESIGN_REF_RE.search(text):
        problems.append(
            "LAUNCHER-CANONICAL: spec/authoring-prompt.md no longer points at "
            "spec/design.md — the normative source for the locked decisions "
            "(L1–L11), the file structure, and the cardinal rules. A "
            "reauthoring session must be told to read design.md first."
        )
    if not INPUTS_REF_RE.search(text):
        problems.append(
            "LAUNCHER-CANONICAL: spec/authoring-prompt.md no longer points at "
            "spec/inputs.md — the normative source for the canonical inputs and "
            "the §6 update procedure. Cite it in the ordered reads."
        )
    if TREE_REGROWTH_RE.search(text):
        problems.append(
            "LAUNCHER-REGROWTH: spec/authoring-prompt.md regrew the file-"
            "structure tree (box-drawing ├── / └── glyphs). The locked layout "
            "lives ONLY in design.md §5 — link it, do not paste a second copy "
            "(a duplicate is exactly what drifts)."
        )
    if LOCKS_REGROWTH_RE.search(text):
        problems.append(
            "LAUNCHER-REGROWTH: spec/authoring-prompt.md regrew a normative "
            "block (a 'Locks to preserve verbatim' / 'Cardinal rules to bake "
            "in' section). The L1–L11 locks and the cardinal rules live ONLY in "
            "design.md §3 / SKILL.md §Cardinal rules — reference them, don't "
            "restate a divergent copy."
        )
    return problems


def beadid_problems(line: str) -> list[str]:
    problems: list[str] = []
    for m in BEADID_RE.finditer(line):
        problems.append(
            f"BEAD-ID-LEAK: `{m.group(0)}` is an internal tracker id — "
            "user-facing leaves (SKILL.md / README.md / examples-map.md / "
            "references|patterns|decision-trees/**) carry NO `rf2-` ids "
            "(spec/design.md L10). Replace with public, stable evidence: an EP "
            "number, a spec section link, a fixture name, an API entry, or a "
            "fully-qualified public PR link. (bead ids stay only in the skill's "
            "spec/ meta-docs.)"
        )
    return problems


def posture_problems(line: str) -> list[str]:
    """Rule 2b — a line that hands a runnable gate back to the author."""
    problems: list[str] = []
    if HANDOFF_RESIDUE_RE.search(line):
        problems.append(
            "VERIFY-POSTURE-HANDOFF: this line hands a runnable gate back to the "
            "author — the retired Q14 posture. Per spec/design.md L3 the agent "
            "RUNS the project's declared noninteractive gate after it edits and "
            "reports the exact command and result; it hands off only a gate that "
            "is interactive, needs a live runtime (re-frame2-pair), does not "
            "exist, or that the user asked it not to run — and says which."
        )
    return problems


def gate_grant_problems(text: str) -> list[str]:
    """Rule 2a — SKILL.md's front-matter must keep every routine gate-running
    grant family (`- Bash(clojure …)`, `- Bash(npm …)`, `- Bash(shadow-cljs …)`).
    Takes the whole SKILL.md body so the self-test can pass fixtures directly."""
    problems: list[str] = []
    for family in GATE_GRANT_FAMILIES:
        if not _gate_grant_re(family).search(text):
            problems.append(
                f"VERIFY-POSTURE-GRANT: SKILL.md's allowed-tools no longer carries "
                f"a `Bash({family} *)` entry. Per spec/design.md L3 the agent runs "
                "the project's declared gate itself, which needs the routine "
                "wildcards skills/README.md §Published-skill allowed-tools "
                "baseline blesses (clojure / npm / npx / shadow-cljs / clj-kondo). "
                "Restore the grant; do not fall back to naming the gate for the "
                "author."
            )
    return problems


def find_drift(files: list[Path]) -> tuple[list[str], int]:
    problems: list[str] = []
    lines_checked = 0
    if not (SKILL_DIR / "SKILL.md").is_file():
        problems.append(
            "SETUP: skills/re-frame2/SKILL.md missing — the guard's scan anchor "
            "drifted from the skill layout."
        )
    else:
        # Rule 2a — the gate-running grants, checked over the whole front-matter.
        rel = (SKILL_DIR / "SKILL.md").relative_to(REPO_ROOT)
        for label in gate_grant_problems(_slurp(SKILL_DIR / "SKILL.md")):
            problems.append(f"{rel}: {label}")
    for path in files:
        if not path.is_file():
            continue
        rel = path.relative_to(REPO_ROOT)
        body = _slurp(path)
        for lineno, line in enumerate(body.splitlines(), start=1):
            lines_checked += 1
            for label in (beadid_problems(line) + posture_problems(line)
                          + reply_contract_problems(line)):
                problems.append(f"{rel}:{lineno}: {label}\n    {line.strip()}")
        # Rules 4 & 5a — the machine-registration footgun and the targetless
        # Managed-HTTP recipe are cross-line shapes (their tokens land on
        # different lines of one fenced block), so they are scanned per-block
        # rather than per-line.
        for start_lineno, label in machine_handler_recipe_problems(body):
            problems.append(f"{rel}:{start_lineno}: {label}")
        for start_lineno, label in managed_http_recipe_problems(body):
            problems.append(f"{rel}:{start_lineno}: {label}")
        # Rule 8 — the JVM with-frame thunk "function form", scanned over the
        # WHOLE body (fenced and inline alike — the original defect was an
        # inline positive instruction); lineno computed from the match offset.
        for lineno, label in withframe_thunk_problems(body):
            problems.append(f"{rel}:{lineno}: {label}")
        # Rules 9a & 9b - the canonical per-test frame recipe's two
        # copy-oriented faults, scanned per FENCED BLOCK: a bare
        # `with-new-frame` / `with-frame` head (an undeclared var in a
        # consumer namespace), and an `app-db-value` reader handed to
        # `compute-sub` for a runtime-db-backed graph. Prose mentions
        # never enter a fence, so a labelled "not this" stays legal.
        for lineno, label in unqualified_frame_macro_problems(body):
            problems.append(f"{rel}:{lineno}: {label}")
        for lineno, label in compute_sub_partition_problems(body):
            problems.append(f"{rel}:{lineno}: {label}")
        # Rule 7 — the form-action fail-open shapes, per fenced block.
        for start_lineno, label in csrf_fence_problems(body):
            problems.append(f"{rel}:{start_lineno}: {label}")
        # Rule 6b — hooks-recipe residue shapes, checked PER SENTENCE (a mixed
        # Reagent+hooks physical line is carved so a Reagent `:contextType` never
        # bleeds into the hooks sentence, and a far-away negation cannot mask a
        # real attribution). Scanned leaf-wide so a residue anywhere fails.
        for lineno, sent in _hooks_sentences(body):
            for label in hooks_sentence_problems(sent):
                problems.append(f"{rel}:{lineno}: {label}\n    {sent.strip()}")

    # Rule 6a — COHERENCE FLOOR. The authoritative hooks-guidance leaves must
    # each carry a single COHERENT recipe sentence (ordinary `defui`/`defnc` +
    # `use-subscribe` + `use-frame` together), proving the relationship — not just
    # the scattered presence of two tokens (which a semantic reversal that names
    # both would satisfy).
    for parts in HOOKS_LEAF_REQUIRED:
        leaf = SKILL_DIR.joinpath(*parts)
        rel = leaf.relative_to(REPO_ROOT)
        if not leaf.is_file():
            problems.append(
                f"{rel}: SETUP: authoritative UIx/Helix hooks-guidance leaf "
                "missing — the Rule 6a anchor drifted from the skill layout."
            )
            continue
        if hooks_leaf_incoherent(_slurp(leaf)):
            problems.append(
                f"{rel}: HOOKS-RECIPE-INCOHERENT: this authoritative UIx/Helix "
                "stateful-component leaf no longer carries a single coherent "
                "recipe sentence naming an ordinary `defui`/`defnc`, "
                "`use-subscribe`, AND `use-frame` together. The hooks adapters "
                "read subs with `use-subscribe` and carry the frame with the "
                "`use-frame` hook (the hook-position spelling of capture-frame) "
                "on an ordinary component — not the Reagent reg-view / "
                "render-time capture-frame idiom. Restore the coherent recipe."
            )

    # Rule 6c — BOUNDED BLOCK anchors. Each named block is read in place (never
    # the whole file), so the two authorities outside this skill — the setup
    # skill's greenfield warning and the Spec 006 paragraph — are covered
    # without importing the leaf-wide rules onto them.
    for parts, label, anchor_re in HOOKS_ANCHORED_BLOCKS:
        target = REPO_ROOT.joinpath(*parts)
        rel = "/".join(parts)
        if not target.is_file():
            problems.append(
                f"{rel}: SETUP: bounded hooks-recipe authority missing — the "
                f"Rule 6c anchor for {label} has no file."
            )
            continue
        found = anchored_block(_slurp(target), anchor_re)
        if found is None:
            problems.append(
                f"{rel}: HOOKS-BLOCK-ANCHOR-MISSING: {label} is a bounded "
                "hooks-recipe authority, and its anchor "
                f"(/{anchor_re.pattern}/) no longer matches any line. If the "
                "block legitimately moved or was reworded, re-point the anchor "
                "in HOOKS_ANCHORED_BLOCKS in the same change — don't leave the "
                "recipe unguarded."
            )
            continue
        lineno, block = found
        for text in anchored_block_problems(label, block):
            problems.append(f"{rel}:{lineno}: {text}")

    # Rule 7 (extra files) — the spec pattern page is the projection's SOURCE,
    # so the same fail-open shapes are refused there too. Whole-file fenced
    # scan; the page's own labelled "not this" prose is unfenced and unread.
    for parts in CSRF_EXTRA_FILES:
        target = REPO_ROOT.joinpath(*parts)
        rel = "/".join(parts)
        if not target.is_file():
            problems.append(
                f"{rel}: SETUP: the form-action CSRF authority is missing — "
                "Rule 7's spec-side anchor has no file. If the page moved, "
                "re-point CSRF_EXTRA_FILES in the same change."
            )
            continue
        for start_lineno, label in csrf_fence_problems(_slurp(target)):
            problems.append(f"{rel}:{start_lineno}: {label}")

    # Rule 9c - THE CANONICAL PER-TEST FRAME BLOCK, read in place. 9a and
    # 9b refuse the two bad shapes; neither notices the recipe being
    # reworded out of existence, which is what this anchor holds.
    canonical_leaf = REPO_ROOT.joinpath(*CANONICAL_FRAME_LEAF)
    rel = "/".join(CANONICAL_FRAME_LEAF)
    if not canonical_leaf.is_file():
        problems.append(
            f"{rel}: SETUP: the canonical per-test frame leaf is missing "
            "- Rule 9c's anchor has no file."
        )
    else:
        found = canonical_frame_block(_slurp(canonical_leaf))
        if found is None:
            problems.append(
                f"{rel}: CANONICAL-FRAME-BLOCK-ANCHOR-MISSING: no "
                "'Canonical mini-example' heading with a code fence after "
                "it. If the block legitimately moved or was renamed, "
                "re-point CANONICAL_BLOCK_HEADING_RE in the same change - "
                "don't leave the recipe unguarded."
            )
        else:
            lineno, block = found
            for text in canonical_frame_block_problems(block):
                problems.append(f"{rel}:{lineno}: {text}")

    # Rule 3 — the launcher (authoring-prompt.md) is checked as a whole body,
    # not line-by-line, and lives under spec/ (out of the leaf scan above).
    if not AUTHORING_PROMPT.is_file():
        problems.append(
            "SETUP: skills/re-frame2/spec/authoring-prompt.md missing — the "
            "launcher-canonical check (Rule 3) has no anchor."
        )
    else:
        rel = AUTHORING_PROMPT.relative_to(REPO_ROOT)
        for label in launcher_problems(_slurp(AUTHORING_PROMPT)):
            problems.append(f"{rel}: {label}")
    return problems, lines_checked


def _slurp(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def run(*, verbose: bool, ci: bool) -> int:
    if not SKILL_DIR.is_dir():
        sys.stderr.write(f"error: re-frame2 skill not found at {SKILL_DIR}\n")
        return 2

    files = scanned_files()
    problems, lines_checked = find_drift(files)

    if verbose:
        print(
            "re-frame2 no-bead-id + verify-posture + launcher-canonical + "
            "machine-handler-recipe + managed-http-recipe + uix-helix-hooks + "
            "form-action-csrf + withframe-thunk + canonical-frame-recipe "
            "guard: scanned "
            f"{len(files)} user-facing leaves ({lines_checked} lines), "
            f"{len(HOOKS_ANCHORED_BLOCKS)} bounded hooks-recipe blocks, "
            f"{len(CSRF_EXTRA_FILES)} spec-side CSRF authority file(s), plus "
            "the spec/authoring-prompt.md launcher."
        )

    if not problems:
        if verbose:
            print(
                "re-frame2-drift: no bead-id leaks, the gate-running grants are "
                "in the front-matter with no author-hand-off residue in the "
                "leaves, no bare reg-event + make-machine-handler recipe, "
                "no retired Managed-HTTP reply-contract teaching, the UIx/Helix "
                "hooks leaves each carry a coherent defui/defnc + use-subscribe + "
                "use-frame recipe with no residue shape (no :contextType "
                "attribution, semantic reversal, reg-view-macro registration, or "
                "bare no-arg capture-frame carry), the bounded recipe blocks "
                "(the views per-adapter UIx/Helix rows, the setup skill's "
                "'Reagent only' warning, and the Spec 006 UIx/Helix paragraph) "
                "each still state that recipe in place, no code fence carries "
                "a fail-open CSRF compare or a required `:csrf-token` field "
                "(leaves and spec page alike), no `with-frame` recipe parks a "
                "fn literal in the macro's body-head position (the JVM thunk "
                "\"function form\" — the macro would return it uninvoked and "
                "every test under it would silently skip), no fenced recipe "
                "calls a bare `with-new-frame` / `with-frame` (an undeclared "
                "var in a consumer namespace) or hands `app-db-value` to "
                "`compute-sub` for a runtime-db-backed graph, the canonical "
                "per-test frame block still states `rf/with-new-frame` + "
                "`rf/frame-state-value` in place, and the launcher points at "
                "design.md + inputs.md without regrowing the tree / locks. "
                "NOTE: this guard is a catalogue of known regressions, not a "
                "skill/spec equivalence check — see the module docstring."
            )
        return 0

    err_prefix = "::error::" if ci else ""
    for p in problems:
        print(f"{err_prefix}{p}")
    print(
        f"\nre-frame2-drift: {len(problems)} drift issue(s) — keep internal "
        "bead ids out of the user-facing leaves (L10), keep the run-the-gate "
        "posture (L3: the agent runs the project's declared gate and reports; "
        "the grants stay in the front-matter), author machines with reg-machine (not a "
        "bare reg-event + make-machine-handler recipe), address every Managed-"
        "HTTP reply with an explicit :reply-to/:on-success/:on-failure (no "
        "retired co-located `:rf/reply` default), keep each UIx/Helix hooks "
        "stateful-component leaf carrying a coherent defui/defnc + use-subscribe "
        "+ use-frame recipe (never a reg-view-macro / bare-capture-frame / "
        ":contextType-attribution / semantic-reversal residue), keep each "
        "bounded recipe block stating that recipe IN PLACE (a correct paragraph "
        "elsewhere in the file does not cover the table cell), keep every CSRF "
        "compare in a code fence failing CLOSED on both limbs with the token "
        "off the field schema, pin frames with the `with-frame` macro BODY — "
        "never a fn-literal thunk in its body-head position (the macro splices "
        "on JVM and CLJS alike and would return the thunk uninvoked; a fixture "
        "invokes `(test-fn)` inside the binding), alias-qualify the frame "
        "macros in every code fence (`rf/with-new-frame`; the bare symbol is "
        "an undeclared var in a consumer namespace, and CLJS grades that a "
        "warning - green build, TypeError at run time, nothing asserted) and "
        "compute a runtime-db-backed sub from `rf/frame-state-value`, never "
        "`rf/app-db-value` (which is the app-db partition alone, so the sub "
        "silently computes nil) - and keep the launcher "
        "pointing at the canonical design.md + inputs.md instead of re-holding "
        "the tree / locks."
    )
    return 1


# ---------------------------------------------------------------------------
# Self-test — exercises the line classifiers against in-memory fixtures so the
# guard itself can't silently rot. Mirrors the --self-test convention in the
# sibling check_skill_*.py guards.
# ---------------------------------------------------------------------------

def _self_test() -> int:
    failures = 0

    def expect(fn, line: str, *, dirty: bool, label: str) -> None:
        nonlocal failures
        got = bool(fn(line))
        if got != dirty:
            print(
                f"SELF-TEST FAIL ({label}): expected dirty={dirty}, got "
                f"{got} for: {line!r}"
            )
            failures += 1

    # --- Rule 1: bead-id leak. LEAK fixtures (the exact finding-1 shapes).
    expect(
        beadid_problems,
        "EP-0008 (rf2-hhutya) promoted the production-reachable SSR error categories.",
        dirty=True, label="A1 plain bead id beside an EP number",
    )
    expect(
        beadid_problems,
        "the dual-partition recompute trigger is a SILENT regression (rf2-d3fb7.1).",
        dirty=True, label="A2 bead id with .N sub-task suffix",
    )
    # CLEAN fixtures — the corrected public-evidence wording must NOT flag.
    expect(
        beadid_problems,
        "EP-0008 promoted the production-reachable SSR error categories.",
        dirty=False, label="B1 EP number alone, no bead id",
    )
    expect(
        beadid_problems,
        "the runtime records history per `spec/005-StateMachines.md` §History.",
        dirty=False, label="B2 spec section link, no bead id",
    )
    expect(
        beadid_problems,
        "a fully-qualified public PR link day8/re-frame2#2863 is fine.",
        dirty=False, label="B3 public PR ref is not a bead id",
    )

    # --- Rule 2a: the gate-running grants, over a front-matter body.
    #     `gate_grant_problems` takes the WHOLE SKILL.md body.
    grants = (
        "allowed-tools:\n  - Read\n  - Edit\n  - Bash(clojure *)\n"
        "  - Bash(npm *)\n  - Bash(npx *)\n  - Bash(shadow-cljs *)\n"
        "  - Bash(clj-kondo *)\n  - mcp__re-frame2-story-mcp__register-variant\n"
    )
    expect(
        gate_grant_problems, grants,
        dirty=False, label="C1 front-matter carries every gate-grant family",
    )
    expect(
        gate_grant_problems, grants.replace("  - Bash(clojure *)\n", ""),
        dirty=True, label="C2 clojure grant dropped",
    )
    expect(
        gate_grant_problems, grants.replace("  - Bash(shadow-cljs *)\n", ""),
        dirty=True, label="C3 shadow-cljs grant dropped",
    )
    expect(
        gate_grant_problems,
        "allowed-tools:\n  - Read\n  - Edit\n  - Write\n  - Grep\n  - Glob\n",
        dirty=True, label="C4 the retired Q14 front-matter (no Bash at all)",
    )
    # A grant for one family does not stand in for another: `npx` is not `npm`.
    expect(
        gate_grant_problems,
        grants.replace("  - Bash(npm *)\n", ""),
        dirty=True, label="C5 npm grant dropped (npx does not cover it)",
    )

    # --- Rule 2b: author-hand-off residue. DRIFT fixtures — the exact retired
    #     Q14 wording the leaves carried.
    expect(
        posture_problems,
        "This skill writes the code; the author runs the tests, the compiler, the app.",
        dirty=True, label="E1 author-runs-the-tests hand-off",
    )
    expect(
        posture_problems,
        "- **Gate named for the author** — the nearest relevant gate is named concretely so the author can run it.",
        dirty=True, label="E2 gate-named-for-the-author checklist line",
    )
    expect(
        posture_problems,
        "Pick the tightest match and name it for the author.",
        dirty=True, label="E3 name-it-for-the-author instruction",
    )
    expect(
        posture_problems,
        "The skill writes the test; the author runs the suite.",
        dirty=True, label="E4 author-runs-the-suite wording",
    )
    # CLEAN — the run-the-gate wording, and the lawful hand-off shapes.
    expect(
        posture_problems,
        "Run the nearest relevant gate before declaring done — exact command + result reported.",
        dirty=False, label="F1 run-the-gate instruction",
    )
    expect(
        posture_problems,
        "Hand off only when the gate is interactive / visual, needs a live runtime, does not exist, or the user said not to.",
        dirty=False, label="F2 bounded hand-off clause is lawful",
    )
    expect(
        posture_problems,
        "If no gate exists for the surface you touched, say so and describe the one the author should add.",
        dirty=False, label="F3 'the gate the author should add' is not a hand-off",
    )
    expect(
        posture_problems,
        "  - Bash(clojure *)",
        dirty=False, label="F4 a gate grant is not prose residue",
    )

    # --- Rule 3: launcher points at both canonical files without regrowing the
    # tree / locks. `launcher_problems` takes the WHOLE body, so these fixtures
    # are multi-line prose, not single lines.
    clean_launcher = (
        "Read spec/design.md (the normative locks + file structure) then "
        "spec/inputs.md (the canonical inputs). Write the skill to the layout "
        "locked in design.md §5; honour the L1-L11 locks in design.md §3."
    )
    expect(
        launcher_problems, clean_launcher,
        dirty=False, label="G1 launcher cites both canonical files, no regrowth",
    )
    expect(
        launcher_problems,
        "Read spec/design.md then write the skill.",  # no inputs.md ref
        dirty=True, label="G2 launcher drops the inputs.md pointer",
    )
    expect(
        launcher_problems,
        "Read spec/inputs.md then write the skill.",  # no design.md ref
        dirty=True, label="G3 launcher drops the design.md pointer",
    )
    expect(
        launcher_problems,
        clean_launcher + "\n```\nskills/re-frame2/\n├── SKILL.md\n```",
        dirty=True, label="G4 launcher regrew the file-structure tree",
    )
    expect(
        launcher_problems,
        clean_launcher + "\n\n> *Locks to preserve verbatim:*\n> *- L3 …*",
        dirty=True, label="G5 launcher regrew the locks block",
    )
    expect(
        launcher_problems,
        clean_launcher + "\n\n*Cardinal rules to bake in (these go in SKILL.md):*",
        dirty=True, label="G6 launcher regrew the cardinal-rules block",
    )

    # --- Rule 4: machine-registration footgun. `machine_handler_recipe_problems`
    # takes the WHOLE body (the two tokens span lines of one fenced block), so
    # these fixtures are multi-line prose with fences.
    dirty_recipe = (
        "Author the boot flow:\n\n"
        "```clojure\n"
        "(rf/reg-event :app/boot\n"
        "  (re-frame.machines/make-machine-handler\n"
        "    {:initial :configuring :states {}}))\n"
        "```\n"
    )
    expect(
        machine_handler_recipe_problems, dirty_recipe,
        dirty=True, label="H1 fenced reg-event wrapping make-machine-handler",
    )
    dirty_recipe_alias = (
        "```clojure\n"
        "(rf/reg-event :ws/connection\n"
        "  (machines/make-machine-handler\n"
        "    {:initial :disconnected}))\n"
        "```\n"
    )
    expect(
        machine_handler_recipe_problems, dirty_recipe_alias,
        dirty=True, label="H2 aliased machines/make-machine-handler recipe",
    )
    # CLEAN — the corrected reg-machine recipe.
    clean_recipe = (
        "Author it with reg-machine:\n\n"
        "```clojure\n"
        "(rf/reg-machine :app/boot\n"
        "  {:initial :configuring :states {}})\n"
        "(rf/dispatch [:app/boot [:rf.machine/start]])\n"
        "```\n"
    )
    expect(
        machine_handler_recipe_problems, clean_recipe,
        dirty=False, label="I1 fenced reg-machine recipe (no footgun)",
    )
    # CLEAN — inline (non-fenced) advanced-note warning that names the shape.
    clean_inline_warning = (
        "> **Advanced — make-machine-handler.** Registering it by hand, "
        "`(rf/reg-event id meta (make-machine-handler spec))`, does not stamp "
        "the :rf/machine? metadata, and a [:schemas :data] spec throws "
        ":rf.error/machine-schema-requires-reg-machine. Use reg-machine."
    )
    expect(
        machine_handler_recipe_problems, clean_inline_warning,
        dirty=False, label="I2 inline advanced-note warning prose is allowed",
    )
    # CLEAN — a fenced block with a plain reg-event and no machine handler.
    clean_simple = (
        "```clojure\n"
        "(rf/reg-event :app/init (fn [_ _] {:fx [[:dispatch [:config/load]]]}))\n"
        "```\n"
    )
    expect(
        machine_handler_recipe_problems, clean_simple,
        dirty=False, label="I3 fenced plain reg-event (no machine handler)",
    )

    # --- Rule 5a: targetless fx-form :rf.http/managed recipe (rf2-723lgn).
    #     `managed_http_recipe_problems` takes the WHOLE body (the tokens span
    #     lines of one fenced block), so these fixtures are multi-line prose
    #     with fences.
    dirty_targetless_http = (
        "Issue the request:\n\n"
        "```clojure\n"
        "(rf/reg-event :article/load\n"
        "  (fn [_ _]\n"
        "    {:fx [[:rf.http/managed {:request {:url \"/x\"} :decode S}]]}))\n"
        "```\n"
    )
    expect(
        managed_http_recipe_problems, dirty_targetless_http,
        dirty=True, label="J1 fx-form :rf.http/managed request with no reply target",
    )
    # CLEAN — the corrected fx recipe with an explicit reply target.
    clean_http_reply_to = (
        "```clojure\n"
        "{:fx [[:rf.http/managed {:request {:url \"/x\"} :reply-to [:article/replied]}]]}\n"
        "```\n"
    )
    expect(
        managed_http_recipe_problems, clean_http_reply_to,
        dirty=False, label="K1 fx-form request WITH :reply-to (explicit address)",
    )
    clean_http_split_sugar = (
        "```clojure\n"
        "{:fx [[:rf.http/managed {:request {:url \"/x\"}\n"
        "                         :on-success [:ok] :on-failure [:err]}]]}\n"
        "```\n"
    )
    expect(
        managed_http_recipe_problems, clean_http_split_sugar,
        dirty=False, label="K2 fx-form request WITH :on-success/:on-failure split sugar",
    )
    # CLEAN — the machine-form spawn dispatches back to its parent; no :reply-to.
    clean_http_machine_form = (
        "```clojure\n"
        "{:authenticating\n"
        " {:spawn {:machine-id :rf.http/managed :data {:request {:url \"/login\"}}}}}\n"
        "```\n"
    )
    expect(
        managed_http_recipe_problems, clean_http_machine_form,
        dirty=False, label="K3 machine-form :spawn of :rf.http/managed (no reply key needed)",
    )
    # CLEAN — the abort dispatch is not a request recipe.
    clean_http_abort = (
        "```clojure\n"
        "(rf/dispatch [:rf.http/managed-abort req-id])\n"
        "```\n"
    )
    expect(
        managed_http_recipe_problems, clean_http_abort,
        dirty=False, label="K4 :rf.http/managed-abort dispatch (no request)",
    )

    # --- Rule 5b: reading the retired co-located `:rf/reply` key. DRIFT fixture.
    expect(
        reply_contract_problems,
        "(rf/reg-event :article/load (fn [_ [_ msg]] (when-let [r (:rf/reply msg)] r)))",
        dirty=True, label="L1 reads the retired co-located :rf/reply key",
    )
    # CLEAN — the current explicit-target contract and the INTERNAL descriptor.
    expect(
        reply_contract_problems,
        "The request names `:reply-to [:article/replied]`; the handler branches on `(:status reply)`.",
        dirty=False, label="M1 explicit :reply-to target, no :rf/reply read",
    )
    expect(
        reply_contract_problems,
        "The public `:reply-to` normalizes to the internal `:rf/reply-to` descriptor.",
        dirty=False, label="M2 internal :rf/reply-to descriptor is not the retired :rf/reply",
    )

    # --- Rule 5c: bare :work/id on a transient reply map. DRIFT fixture.
    expect(
        reply_contract_problems,
        "The reply map exposes `:status`, `:value`, and `:work/id`.",
        dirty=True, label="N1 bare :work/id asserted on the transient reply map",
    )
    # CLEAN — the transient spelling, and legitimate durable-ledger prose.
    expect(
        reply_contract_problems,
        "The durable ledger row keeps bare `:work/id`; the transient reply map spells `:rf.reply/work-id`.",
        dirty=False, label="O1 reply map spells :rf.reply/work-id; ledger keeps bare :work/id",
    )
    expect(
        reply_contract_problems,
        "The reply envelope correlates a late completion by the child's `:work/id` for stale suppression.",
        dirty=False, label="O2 :work/id as the correlation identity is allowed",
    )
    expect(
        reply_contract_problems,
        "The durable work-ledger row is keyed by bare `:work/id`.",
        dirty=False, label="O3 durable-ledger :work/id prose (no reply-map context)",
    )

    # --- Rule 6 (rf2-gq9bg): causally-exact hooks-recipe guard. The three probes
    #     the previous whole-file / broad-negation guard mis-classified, plus
    #     legitimate corrected wording, then mutations against the REAL blocks.
    #
    #     S1/S2 were false NEGATIVES (accepted) and S3 a false POSITIVE (rejected)
    #     under the old guard; they are now classified correctly.
    expect(
        hooks_sentence_problems,
        "Do not use use-frame or use-subscribe; use reg-view instead.",
        dirty=True, label="S1 semantic reversal (both tokens present, still red)",
    )
    expect(
        hooks_sentence_problems,
        "UIx is not special: reg-view* gives it :contextType.",
        dirty=True, label="S2 :contextType attributed under a stray far 'not'",
    )
    expect(
        hooks_sentence_problems,
        "UIx/Helix differ from Reagent's :contextType mechanism.",
        dirty=False, label="S3 valid Reagent :contextType comparison stays green",
    )
    # CLEAN — the corrected wording the leaves actually use.
    expect(
        hooks_sentence_problems,
        "The hooks adapters (UIx / Helix) read the frame via `use-subscribe` / `use-frame` and need no `:contextType`.",
        dirty=False, label="S4 hooks adapters explicitly have no :contextType",
    )
    expect(
        hooks_sentence_problems,
        "A `reg-view`-wrapped Reagent component participates via `:contextType`.",
        dirty=False, label="S5 Reagent-only :contextType sentence (no hooks adapter)",
    )
    expect(
        hooks_sentence_problems,
        "On **Reagent** register it via `reg-view*`, then capture `(rf/capture-frame)` from the body; on UIx / Helix an ordinary `defui` / `defnc` reads subs with `use-subscribe` and carries the frame with `use-frame`.",
        dirty=False, label="S6 lawful mixed recipe (reg-view* + Reagent capture) stays green",
    )

    # --- Rule 6b(i) ATTRIBUTION SCOPE (rf2-xlxrl). The retired proximity
    #     suppression pardoned an unlawful attribution merely for sitting near
    #     the word "Reagent" (S7/S8 — the rf2-szw6c false negative, BOTH
    #     directions, green on main) while flagging the corpus's own per-adapter
    #     house style (S12 — red on main). Clause carving judges each clause by
    #     the subject IT names, so both defects invert.
    expect(
        hooks_sentence_problems,
        "On UIx / Helix, `reg-view*` gives the component a `:contextType`; the `reg-view` macro stays Reagent-flavoured.",
        dirty=True,
        label="S7 attribution hiding beside a lawful Reagent clause (szw6c false negative)",
    )
    expect(
        hooks_sentence_problems,
        "The macro stays Reagent-flavoured; UIx gets a `:contextType` too.",
        dirty=True,
        label="S8 same hole, Reagent clause first (szw6c false negative)",
    )
    # The ambiguity branch: a clause that attributes the token but names NEITHER
    # adapter, inside a sentence that names a hooks adapter. Load-bearing — the
    # Rule 6c block mutations below land in exactly this shape once carved.
    expect(
        hooks_sentence_problems,
        "UIx is not special; reg-view* gives it `:contextType`.",
        dirty=True,
        label="S9 ownerless attribution in a hooks sentence (ambiguity branch)",
    )
    # LAWFUL shapes the repaired scope must accept — each is a real prose idiom,
    # not a contrivance. S10 needs the token-adjacent possessive allowance (the
    # 8-char negation window cannot reach across "get Reagent's").
    expect(
        hooks_sentence_problems,
        "UIx components never get Reagent's `:contextType`.",
        dirty=False, label="S10 token-adjacent Reagent possessive stays green",
    )
    expect(
        hooks_sentence_problems,
        "Reagent's wrapper gives the component `:contextType`, while UIx uses `use-frame`.",
        dirty=False, label="S11 `, while` contrast clause stays green",
    )
    expect(
        hooks_sentence_problems,
        "On Reagent register it via `reg-view*`, which gives the class a `:contextType`; on UIx / Helix carry the frame with `use-frame`.",
        dirty=False,
        label="S12 per-adapter semicolon HOUSE STYLE (false positive on main) is green",
    )
    # A colon introduces an elaboration of the SAME subject, so it is not a
    # clause boundary — S2 above must stay red. Its period variant is the
    # ACCEPTED BOUNDARY: the sentence carve splits it and the attributing
    # sentence names no hooks adapter, so nothing evaluates it. Consistent and
    # statable; catching it would need a pronoun-resolving prose parser.
    expect(
        hooks_sentence_problems,
        "UIx is not special. reg-view* gives it `:contextType`.",
        dirty=True,
        label="S13 period + lowercase does not split the sentence — still caught",
    )

    # Mutations against the REAL guarded blocks (not free-floating strings):
    # each authoritative leaf's coherent recipe must be GREEN as shipped, its
    # coherence floor must be load-bearing, and each residue injected into the
    # real leaf text must turn it RED.
    def _leaf_scan(text: str) -> list[str]:
        return [p for _l, s in _hooks_sentences(text) for p in hooks_sentence_problems(s)]

    # The szw6c false negative must also be caught by the real leaf scan (the
    # sentence carve must not hand the attribution away before 6b(i) sees it).
    expect(
        _leaf_scan,
        "On UIx / Helix, `reg-view*` gives the component a `:contextType`; the `reg-view` macro stays Reagent-flavoured.",
        dirty=True, label="S14 szw6c false negative caught at leaf-scan scope",
    )
    # ACCEPTED BOUNDARY, pinned so a future reader knows it is a decision and
    # not an oversight: once the sentence carve splits at `. ` + capital, the
    # attributing sentence names no hooks adapter and 6b(i) never evaluates it.
    expect(
        _leaf_scan,
        "UIx is not special. Reg-view* gives it `:contextType`.",
        dirty=False,
        label="S15 ACCEPTED BOUNDARY: pronoun attribution in a split-off sentence",
    )

    residue_mutations = (
        ("reg-view-macro",
         "On UIx / Helix, register the component via `reg-view` to carry the frame."),
        ("bare-capture",
         "On UIx / Helix, dispatch via `(:dispatch (rf/capture-frame))` captured above the callback."),
        ("semantic-reversal",
         "On UIx / Helix, do not use use-frame or use-subscribe; use reg-view instead."),
        ("contexttype-attribution",
         "On UIx / Helix, `reg-view*` gives the component a `:contextType`."),
    )
    for parts in HOOKS_LEAF_REQUIRED:
        leaf = SKILL_DIR.joinpath(*parts)
        rel = "/".join(parts)
        if not leaf.is_file():
            print(f"SELF-TEST FAIL (T real leaf missing): {rel}")
            failures += 1
            continue
        text = _slurp(leaf)
        # The shipped leaf is legitimate wording — GREEN under the sentence scan.
        shipped = _leaf_scan(text)
        if shipped:
            print(f"SELF-TEST FAIL (T real leaf flagged): {rel}: {shipped}")
            failures += 1
        # The shipped leaf carries a coherent recipe block.
        if _coherent_recipe_sentence(text) is None:
            print(f"SELF-TEST FAIL (T no coherent recipe in real leaf): {rel}")
            failures += 1
        # Each residue injected into the real leaf turns it RED.
        for mut_label, residue in residue_mutations:
            mutated = text + "\n\n" + residue + "\n"
            if not _leaf_scan(mutated):
                print(f"SELF-TEST FAIL (T {mut_label} mutation not caught): {rel}")
                failures += 1
        # The coherence floor is load-bearing: strip use-frame → incoherent.
        if not hooks_leaf_incoherent(text.replace("use-frame", "the-frame")):
            print(f"SELF-TEST FAIL (T coherence floor not load-bearing): {rel}")
            failures += 1

    # --- Rule 6c (rf2-szw6c): the bounded block anchors. Every mutation below
    #     is a REAL-TEXT REPLACEMENT inside the shipped block — the drift shape
    #     an edit to that exact recipe would produce — not a free-floating bad
    #     sentence appended after it. Each must fail the block on its own.
    block_mutations = (
        # The retired "required `reg-view*`" recipe: the block names `reg-view*`
        # where the two hooks belong. It carries no Rule 6b residue (`reg-view*`
        # is the lawful spelling), so ONLY the block-scoped coherence floor
        # catches it — the regression this rule exists for.
        ("required-reg-view*",
         lambda b: b.replace("use-subscribe", "reg-view*")
                    .replace("use-frame", "reg-view*")),
        # The frame carried by a wrapped bare no-arg capture-frame.
        ("wrapped-bare-capture",
         lambda b: b.replace(
             "use-frame", "use-frame wrapped as (:dispatch (rf/capture-frame))")),
        # Semantic reversal steering the hooks author back to the macro.
        ("semantic-reversal",
         lambda b: b.replace(
             "use-frame",
             "use-frame (do not use use-frame or use-subscribe; "
             "use reg-view instead)")),
        # A `:contextType` attributed to the hooks adapter.
        ("hooks-contexttype-attribution",
         lambda b: b.replace(
             "use-frame", "use-frame, which gives the component a `:contextType`")),
    )
    for parts, label, anchor_re in HOOKS_ANCHORED_BLOCKS:
        target = REPO_ROOT.joinpath(*parts)
        rel = "/".join(parts)
        if not target.is_file():
            print(f"SELF-TEST FAIL (U anchored file missing): {rel}")
            failures += 1
            continue
        found = anchored_block(_slurp(target), anchor_re)
        if found is None:
            print(f"SELF-TEST FAIL (U anchor matches nothing): {rel} — {label}")
            failures += 1
            continue
        _lineno, block = found
        # The shipped block is the lawful recipe — GREEN.
        shipped = anchored_block_problems(label, block)
        if shipped:
            print(f"SELF-TEST FAIL (U shipped block flagged): {rel} — {label}: "
                  f"{shipped}")
            failures += 1
        # Each real-text mutation of the shipped block independently turns RED.
        for mut_label, mutate in block_mutations:
            mutated = mutate(block)
            if mutated == block:
                print(f"SELF-TEST FAIL (U {mut_label} mutation was a no-op): "
                      f"{rel} — {label}")
                failures += 1
                continue
            if not anchored_block_problems(label, mutated):
                print(f"SELF-TEST FAIL (U {mut_label} mutation not caught): "
                      f"{rel} — {label}")
                failures += 1

    # Lawful wording must stay GREEN at block scope too: optional registry
    # addressing, a Reagent-owned `:contextType` comparison, a tightly-scoped
    # negation, and a warning to avoid the Reagent macro on a hooks adapter.
    lawful_blocks = (
        ("V1 optional registry addressing",
         "| **UIx** | ordinary `defui`; subs via `use-subscribe`, frame via "
         "`use-frame`; `reg-view*` optional (registry addressing only) |"),
        ("V2 Reagent-owned :contextType comparison",
         "On UIx / Helix an ordinary `defui` / `defnc` reads subs with "
         "`use-subscribe` and carries the frame with `use-frame`, unlike a "
         "Reagent `reg-view` component's `:contextType` wiring."),
        ("V3 tightly-scoped negation",
         "On UIx / Helix an ordinary `defui` / `defnc` uses `use-subscribe` and "
         "`use-frame` and has no `:contextType`."),
        ("V4 warning to avoid the Reagent macro",
         "On UIx / Helix do not reach for `reg-view` — an ordinary `defui` / "
         "`defnc` reads subs with `use-subscribe` and carries the frame with "
         "`use-frame`."),
    )
    for lawful_label, lawful in lawful_blocks:
        got = anchored_block_problems("fixture", lawful)
        if got:
            print(f"SELF-TEST FAIL ({lawful_label}): expected green, got {got}")
            failures += 1

    # --- Rule 7 (rf2-1iclq): the form-action fail-open shapes in code fences.
    #     `csrf_fence_problems` takes the WHOLE body (a fence spans lines), so
    #     these fixtures carry their own fences.
    def _fence(*lines: str) -> str:
        return "```clojure\n" + "\n".join(lines) + "\n```\n"

    fail_open_compare = _fence(
        "(cond",
        "  (and server? (not= (:csrf-token form-params) active-token))",
        "  {:fx [[:rf.server/set-status 403]]})",
    )
    expect(
        csrf_fence_problems, fail_open_compare,
        dirty=True, label="W1 fenced `not=`-alone CSRF compare",
    )
    # The blacklist-free framing earns its keep here: a spelling nobody wrote a
    # regex for is caught by the same missing-presence-limb rule.
    fail_open_when_not = _fence(
        "(when-not (= (:csrf-token form-params) active-token)",
        "  {:fx [[:rf.server/set-status 403]]})",
    )
    expect(
        csrf_fence_problems, fail_open_when_not,
        dirty=True, label="W2 fenced `when-not (= …)` CSRF compare",
    )
    # CLEAN — the fail-closed form: presence limb AND equality.
    fail_closed_compare = _fence(
        "(cond",
        "  (and server? (not (and (some? active-token)",
        "                         (= (:csrf-token form-params) active-token))))",
        "  {:fx [[:rf.server/set-status 403]]})",
    )
    expect(
        csrf_fence_problems, fail_closed_compare,
        dirty=False, label="X1 fenced fail-closed CSRF compare (both limbs)",
    )
    # CLEAN — the labelled 'not this' in INLINE prose is legal teaching and this
    # rule must not read it. Both pages carry exactly this sentence.
    expect(
        csrf_fence_problems,
        "- **Comparing the tokens with `not=` alone.** "
        "`(not= (:csrf-token form-params) active-token)` fails **open** on a "
        "request with no session.",
        dirty=False, label="X2 unfenced 'not this' prose is not read",
    )
    # CLEAN — the view's hidden input names the field as a STRING, and a fence
    # that merely mentions the token performs no comparison.
    expect(
        csrf_fence_problems,
        _fence('[:input {:type "hidden" :name "csrf-token" :value csrf-token}]'),
        dirty=False, label="X3 hidden-input fence (no comparison, no schema)",
    )
    # 7b — the token as a REQUIRED map entry.
    expect(
        csrf_fence_problems,
        _fence("(def AddToCartForm",
               "  [:map",
               "   [:item-id [:string {:min 1}]]",
               "   [:csrf-token [:string {:min 1}]]])"),
        dirty=True, label="W3 fenced required `:csrf-token` field schema",
    )
    # CLEAN — the envelope shape: optional + sensitive, off the field schema.
    expect(
        csrf_fence_problems,
        _fence("(def AddToCartSubmission",
               "  (conj AddToCartFields",
               "        [:csrf-token {:optional true :sensitive? true} "
               "[:string {:min 1}]]))"),
        dirty=False, label="X4 fenced `{:optional true}` envelope entry",
    )

    # --- Rule 7 against the REAL corpus, with mutations. A guard that cannot be
    #     made to fail is worthless — that was the whole finding behind this
    #     rule (the shipped leaf carried the fail-open compare and every existing
    #     rule exited 0 over it). So: the shipped files must be green, and each
    #     reintroduction of the defect must be caught.
    for parts in (
        ("skills", "re-frame2", "patterns", "form-action.md"),
        ("spec", "Pattern-FormAction.md"),
    ):
        target = REPO_ROOT.joinpath(*parts)
        rel = "/".join(parts)
        if not target.is_file():
            print(f"SELF-TEST FAIL (Y real file missing): {rel}")
            failures += 1
            continue
        shipped = _slurp(target)
        if csrf_fence_problems(shipped):
            print(f"SELF-TEST FAIL (Y shipped file flagged): {rel}: "
                  f"{csrf_fence_problems(shipped)}")
            failures += 1
        mutations = (
            # The exact drift the shipped leaf carried before this rule existed.
            ("fail-open compare",
             "(and server? (not (and (some? active-token)",
             "(and server? (not= (:csrf-token form-params) active-token)) #_("),
            ("required token field",
             "[:csrf-token {:optional true :sensitive? true} [:string {:min 1}]]",
             "[:csrf-token [:string {:min 1}]]"),
        )
        for mut_label, old, new in mutations:
            if old not in shipped:
                print(f"SELF-TEST FAIL (Y {mut_label} mutation was a no-op): "
                      f"{rel}")
                failures += 1
                continue
            if not csrf_fence_problems(shipped.replace(old, new)):
                print(f"SELF-TEST FAIL (Y {mut_label} mutation not caught): "
                      f"{rel}")
                failures += 1

    # --- Rule 8: the JVM with-frame thunk "function form" (rf2-jwmkq).
    #     `withframe_thunk_problems` takes the WHOLE body (the shape spans
    #     lines inside a fence) and scans fenced and inline text alike — the
    #     original defect was an inline positive instruction.
    expect(
        withframe_thunk_problems,
        "On CLJS reach the macro via `rf/with-frame` after `(:require "
        "[re-frame.core :as rf])`. On JVM use the `(rf/with-frame frame-id "
        "(fn [] ...))` function form.",
        dirty=True, label="Z1 the exact shipped inline positive instruction",
    )
    expect(
        withframe_thunk_problems,
        "```clojure\n"
        "(use-fixtures :each\n"
        "  (fn [test-fn]\n"
        "    (rf/with-frame :app/test\n"
        "      (fn [] (test-fn)))))\n"
        "```\n",
        dirty=True, label="Z2 fenced fixture wrapping test-fn in a thunk",
    )
    expect(
        withframe_thunk_problems,
        "```clojure\n(rf/with-frame :app/test #(test-fn))\n```\n",
        dirty=True, label="Z3 reader-lambda thunk in body-head position",
    )
    expect(
        withframe_thunk_problems,
        "```clojure\n"
        "(use-fixtures :each\n"
        "  (fn [test-fn]\n"
        "    (rf/make-frame {:id :app/test})\n"
        "    (rf/with-frame :app/test\n"
        "      (test-fn))))\n"
        "```\n",
        dirty=False, label="Z4 corrected fixture — (test-fn) invoked in the body",
    )
    expect(
        withframe_thunk_problems,
        "```clojure\n"
        "(rf/with-frame :stories\n"
        "  (rf/dispatch-sync [:counter/inc])\n"
        "  (ts/assert-path-equals [:n] 1))\n"
        "```\n",
        dirty=False, label="Z5 plain multi-form macro body",
    )
    expect(
        withframe_thunk_problems,
        "```clojure\n"
        "(rf/with-frame :app/test\n"
        "  (rf/reg-sub :total (fn [db _] (:total db)))\n"
        "  (rf/dispatch-sync [:seed]))\n"
        "```\n",
        dirty=False, label="Z6 fn literal nested deeper in the body is ordinary",
    )
    expect(
        withframe_thunk_problems,
        "Never wrap the body in a `(fn [] ...)` thunk — the macro would "
        "return it uninvoked.",
        dirty=False, label="Z7 warning quoting the bare fn literal alone",
    )
    expect(
        withframe_thunk_problems,
        "```clojure\n"
        "(rf/with-new-frame [f (rf/make-frame {:id :stories})]\n"
        "  (is (= :stories (rf/current-frame-id))))\n"
        "```\n",
        dirty=False, label="Z8 with-new-frame binding-vector form out of scope",
    )

    # --- Rule 8 against the REAL corpus, with the reintroduction mutation:
    #     the shipped testing leaf must be green, and mutating ONLY its fixture
    #     back to the thunk form must be caught (rf2-jwmkq acceptance 4).
    target = REPO_ROOT.joinpath(
        "skills", "re-frame2", "references", "cross-cutting", "testing.md")
    if not target.is_file():
        print("SELF-TEST FAIL (Z real testing leaf missing): "
              "skills/re-frame2/references/cross-cutting/testing.md")
        failures += 1
    else:
        shipped = _slurp(target)
        if withframe_thunk_problems(shipped):
            print("SELF-TEST FAIL (Z shipped testing leaf flagged): "
                  f"{withframe_thunk_problems(shipped)}")
            failures += 1
        old = "(rf/with-frame :app/test\n      (test-fn))"
        new = "(rf/with-frame :app/test\n      (fn [] (test-fn)))"
        if old not in shipped:
            print("SELF-TEST FAIL (Z thunk mutation was a no-op): the shipped "
                  "fixture no longer carries the anchored (test-fn) body — "
                  "re-point the Rule 8 mutation anchor in the same change")
            failures += 1
        elif not withframe_thunk_problems(shipped.replace(old, new)):
            print("SELF-TEST FAIL (Z thunk mutation not caught): "
                  "skills/re-frame2/references/cross-cutting/testing.md")
            failures += 1

    # --- Rule 9a: an unqualified frame-scoping macro inside a fence.
    expect(
        unqualified_frame_macro_problems,
        "```clojure\n"
        "(with-new-frame [f (rf/make-frame {})]\n"
        "  (rf/dispatch-sync [:go] {:frame f}))\n"
        "```\n",
        dirty=True, label="AA1 the exact shipped bare with-new-frame head",
    )
    expect(
        unqualified_frame_macro_problems,
        "```clojure\n(with-frame :app/test\n  (rf/dispatch-sync [:go]))\n```\n",
        dirty=True, label="AA2 bare with-frame head in a fence",
    )
    expect(
        unqualified_frame_macro_problems,
        "```clojure\n"
        "(rf/with-new-frame [f (rf/make-frame {})]\n"
        "  (rf/dispatch-sync [:go] {:frame f}))\n"
        "```\n",
        dirty=False, label="AA3 alias-qualified head is the correct form",
    )
    expect(
        unqualified_frame_macro_problems,
        "so a bare `(with-new-frame ...)` is an undeclared var, not a "
        "shorthand. Write `rf/with-new-frame`.",
        dirty=False, label="AA4 labelled 'not this' mention in PROSE",
    )
    expect(
        unqualified_frame_macro_problems,
        "| Create + own + destroy a frame for a scope | `with-new-frame` |",
        dirty=False, label="AA5 affordance table naming the macro, unfenced",
    )
    expect(
        unqualified_frame_macro_problems,
        "```clojure\n"
        "(ns app.test\n"
        "  (:require [re-frame.core :as rf :refer [with-new-frame]]))\n"
        "(with-new-frame [f (rf/make-frame {})]\n"
        "  (rf/dispatch-sync [:go] {:frame f}))\n"
        "```\n",
        dirty=False, label="AA6 block establishing its own :refer is lawful",
    )

    # --- Rule 9b: an app-db-only reader for a runtime-db-backed graph.
    expect(
        compute_sub_partition_problems,
        "```clojure\n"
        "(rf/reg-sub :auth.login/state :<- [:rf/machine :auth.login/flow]\n"
        "  (fn [snapshot _] (:state snapshot)))\n"
        "(assert (= :authed (rf/compute-sub [:auth.login/state] "
        "(rf/app-db-value f))))\n"
        "```\n",
        dirty=True, label="AB1 the exact shipped app-db-value machine assertion",
    )
    expect(
        compute_sub_partition_problems,
        "```clojure\n"
        "(rf/reg-sub :auth.login/state :<- [:rf/machine :auth.login/flow]\n"
        "  (fn [snapshot _] (:state snapshot)))\n"
        "(assert (= :authed (rf/compute-sub [:auth.login/state] "
        "(rf/frame-state-value f))))\n"
        "```\n",
        dirty=False, label="AB2 frame-state-value is the correct partition",
    )
    expect(
        compute_sub_partition_problems,
        "```clojure\n(is (= 60 (rf/compute-sub [:item-sum] "
        "{:items [10 20 30]})))\n```\n",
        dirty=False, label="AB3 app-db-only compute-sub stays legal",
    )
    expect(
        compute_sub_partition_problems,
        "```clojure\n"
        "(is (= {:n 0} (rf/app-db-value f)))\n"
        "(is (= :loading (:state @(rf/subscribe [:rf/machine :loader]))))\n"
        "```\n",
        dirty=False,
        label="AB4 app-db-value read beside a machine sub, no compute-sub",
    )
    expect(
        compute_sub_partition_problems,
        "Read runtime-db with `(:rf.db/runtime (rf/frame-state-value id))` "
        "(not `app-db-value`) — or through `[:rf/machine id]` with "
        "`compute-sub`.",
        dirty=False, label="AB5 correct prose naming both tokens, unfenced",
    )

    # --- Rule 9c: the bounded canonical block must state both spellings.
    expect(
        canonical_frame_block_problems,
        "(rf/with-new-frame [f (rf/make-frame {})]\n"
        "  (assert (= :authed (rf/compute-sub [:auth.login/state] "
        "(rf/frame-state-value f)))))",
        dirty=False, label="AC1 canonical block carrying both correct spellings",
    )
    expect(
        canonical_frame_block_problems,
        "(with-new-frame [f (rf/make-frame {})]\n"
        "  (assert (= :authed (rf/compute-sub [:auth.login/state] "
        "(rf/frame-state-value f)))))",
        dirty=True, label="AC2 canonical block with the macro unqualified",
    )
    expect(
        canonical_frame_block_problems,
        "(rf/with-new-frame [f (rf/make-frame {})]\n"
        "  (assert (= :authed (rf/compute-sub [:auth.login/state] "
        "(rf/app-db-value f)))))",
        dirty=True, label="AC3 canonical block back on rf/app-db-value",
    )

    # --- Rule 9 against the REAL corpus, with both reintroduction
    #     mutations: the shipped frames leaf must be green, its canonical
    #     block must still be locatable, and restoring EITHER exact bad
    #     shape must be caught (rf2-u429 acceptance 4/5).
    frames_leaf = REPO_ROOT.joinpath(*CANONICAL_FRAME_LEAF)
    if not frames_leaf.is_file():
        print("SELF-TEST FAIL (AD real frames leaf missing): "
              + "/".join(CANONICAL_FRAME_LEAF))
        failures += 1
    else:
        shipped = _slurp(frames_leaf)
        if unqualified_frame_macro_problems(shipped):
            print("SELF-TEST FAIL (AD shipped frames leaf flagged by 9a): "
                  f"{unqualified_frame_macro_problems(shipped)}")
            failures += 1
        if compute_sub_partition_problems(shipped):
            print("SELF-TEST FAIL (AD shipped frames leaf flagged by 9b): "
                  f"{compute_sub_partition_problems(shipped)}")
            failures += 1
        found = canonical_frame_block(shipped)
        if found is None:
            print("SELF-TEST FAIL (AD canonical block anchor did not "
                  "resolve): re-point CANONICAL_BLOCK_HEADING_RE")
            failures += 1
        elif canonical_frame_block_problems(found[1]):
            print("SELF-TEST FAIL (AD shipped canonical block incomplete): "
                  f"{canonical_frame_block_problems(found[1])}")
            failures += 1
        # Mutation 1 — the macro back to its unqualified spelling.
        old = "(rf/with-new-frame [f (rf/make-frame"
        new = "(with-new-frame [f (rf/make-frame"
        if old not in shipped:
            print("SELF-TEST FAIL (AD 9a mutation was a no-op): the shipped "
                  "canonical block no longer carries the anchored "
                  "rf/with-new-frame head — re-point the mutation anchor "
                  "in the same change")
            failures += 1
        elif not unqualified_frame_macro_problems(shipped.replace(old, new)):
            print("SELF-TEST FAIL (AD 9a mutation not caught): "
                  + "/".join(CANONICAL_FRAME_LEAF))
            failures += 1
        # Mutation 2 — the assertion back to the app-db-only reader.
        old = "(rf/compute-sub [:auth.login/state] (rf/frame-state-value f))"
        new = "(rf/compute-sub [:auth.login/state] (rf/app-db-value f))"
        if old not in shipped:
            print("SELF-TEST FAIL (AD 9b mutation was a no-op): the shipped "
                  "canonical block no longer carries the anchored "
                  "frame-state-value assertion — re-point the mutation "
                  "anchor in the same change")
            failures += 1
        elif not compute_sub_partition_problems(shipped.replace(old, new)):
            print("SELF-TEST FAIL (AD 9b mutation not caught): "
                  + "/".join(CANONICAL_FRAME_LEAF))
            failures += 1

    if failures:
        print(f"self-test: {failures} failure(s).")
        return 1
    print("self-test: all cases passed.")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--verbose", action="store_true", help="print summary")
    parser.add_argument(
        "--ci",
        action="store_true",
        help="GitHub-Actions ::error:: prefix (auto on under GITHUB_ACTIONS)",
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="run built-in fixtures instead of scanning the repo",
    )
    args = parser.parse_args(argv)

    if args.self_test:
        return _self_test()

    ci = args.ci or bool(os.environ.get("GITHUB_ACTIONS"))
    return run(verbose=args.verbose, ci=ci)


if __name__ == "__main__":
    raise SystemExit(main())
