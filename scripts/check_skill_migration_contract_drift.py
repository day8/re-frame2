#!/usr/bin/env python3
"""M-11 / M-13 contract-drift guard for the re-frame-migration skill (rf2-h9yfsm).

The re-frame-migration skill teaches an agent the v1->v2 breaking-change rules.
Two of those rules describe *current-framework contracts* that earlier skill
prose got backwards — a senior review (rf2-h9yfsm) found the top-level SKILL.md,
the breaking-changes leaf, and the migration README still carrying the
superseded claims while the spec / runtime / tests say the opposite. Because the
skill is the agent's source of truth for "what breaks", a stale contract claim
produces a wrong migration: the agent reassures the author that a plain Reagent
fn is fine inside a provider (it raises `:rf.error/no-frame-context`), or it
points the author at a frame-level `:on-error` recovery policy that does not
exist (it was removed). This guard makes both re-introductions a build failure.

Two contract facts, each pinned against the shipped spec:

  * **M-11 — plain Reagent fns DO NOT inherit a `frame-provider`'s frame.** Under
    Reagent a plain `(defn …)` fn carries no `:contextType` wiring, so it cannot
    read the surrounding provider's frame; a bare ambient `subscribe` / `dispatch`
    in one raises `:rf.error/no-frame-context` (EP-0002 — no `:rf/default`
    fall-through; the old warn-once is superseded). A `reg-view`-registered view
    DOES read the provider frame. (spec/002-Frames.md §Reading the frame from
    React context / §Decision table; spec/004-Views.md §Plain Reagent fns; the
    cross_spec_dom and frame_provider_context_dom adapter tests.) The stale claim
    this gate kills: "plain Reagent fns work / are fine inside any established
    frame scope" / "a plain fn inside a frame-provider inherits that frame
    ambiently".

  * **M-13 — there is NO frame-level `:on-error` recovery policy.** `reg-event-
    error-handler` is dropped and has no app-steering recovery replacement;
    recovery is framework-owned (the typed per-category default). The per-frame
    `:on-error` recovery policy that earlier drafts named was REMOVED (spec/002-
    Frames.md §make-frame config grammar; spec/API.md §Error-emit; spec/009-
    Instrumentation.md §`:on-error` recovery policy — REMOVED). Error
    observability is `register-error-listener!` (always-on) / `register-listener!`
    (dev-only). The stale claim this gate kills: "`reg-event-error-handler` moved
    / moves to a frame-level (per-frame) `:on-error` (recovery) policy".

  * **M-11 (Leave-as-is variant) — a component does NOT auto-pin to
    `:rf/default`.** A frame-dependent plain fn left unchanged under a non-default
    provider does NOT silently pin / fall through / route to `:rf/default` — under
    EP-0002 there is no `:rf/default` floor, so a frame-scoped op raises
    `:rf.error/no-frame-context`. To intentionally target `:rf/default` the
    component must scope or pass that frame explicitly. The stale claim this gate
    kills: a component "pins to `:rf/default` regardless of where it renders" /
    "falls through to `:rf/default`". (The earlier M-11 rule above keyed on a
    "plain fn" subject + an inherit-verb on one line and so missed this distinct
    "pins to `:rf/default`" wording — hence the dedicated narrow rule.)

  * **Form-3 lifecycle frame targeting — hooks have no ambient frame.** A stock
    Reagent Form-3 lifecycle callback runs after the registered render scope has
    unwound. A one-shot hook read therefore uses `(rf/subscribe-once query-v
    {:frame frame})`, and teardown uses frame-first `(rf/unsubscribe frame
    query-v)`. The stale affirmative recipe this gate kills is a lifecycle hook
    that recommends the bare one-argument form for either operation.

    Unlike the rules above this one is NOT a line rule — a bare call is legal
    wherever a real resolver scope exists, so the rule is only as good as the
    context it reads (`form3_context_problems`). It reads balanced call forms
    (vector queries and calls split over lines are ordinary in guidance, and
    arity is what separates the bare form from the frame-qualified one), bounds
    lifecycle context to the enclosing Markdown section, and applies BEFORE /
    AFTER example exemptions per call. Those bounds are the rule: without them
    it either misses realistic recipes (rf2-vxgfnd.94.19) or rejects legal
    ambient calls and lets an AFTER recipe hide behind a BEFORE example
    (rf2-vxgfnd.94.20).

  * **Form-3 capture-once retarget invariance — a POSITIVE-presence + cross-owner
    alignment check (rf2-aalo4n).** The reagent-slim FORM-3.md is the adopter-
    facing owner of the Form-3 capture-once recipe; guided-handlers-state.md §M-11
    is the canonical migration recipe. FORM-3.md recommends capturing the frame
    once in the outer `reg-view*` callable, but that handle is a LOCKED value that
    never re-resolves (`make-capture-frame` closes the captured frame over every
    op — core.cljc), so capture-once is safe ONLY while the mount's provider frame
    is invariant: a *surviving* instance retargeted from provider A to provider B
    keeps sending to the stale A. The adopter owner must state the invariant (the
    A→B stale case, a supported remedy — a frame-derived React `key` remount or the
    registered `reg-view` child, and a canonical-recipe pointer), and the canonical
    recipe must still carry the aligned invariance, so the two owners cannot drift
    apart. Unlike the M-11/M-13 line rules (which KILL stale claims), this asserts
    a thing must be PRESENT — the same shape as the M-1 anchor pins
    (`form3_capture_once_retarget_problems`).

  * **Boot-smoke Pair partition mismatch (rf2-j538f7.33) — an app-db-only Pair
    read aimed at a runtime-db path.** The boot smoke-test (references/runtime-
    smoke-test.md) must read the two runtime partitions with the right tool.
    `get-path` is a `get-in` against the frame's **app-db**, and `snapshot`'s
    `path:` argument slices only the app-db slice — neither can reach the
    runtime-db partition (`:rf.db/runtime`, children under `:rf.runtime/*`). A
    machine snapshot at `[:rf.runtime/machines :snapshots …]` therefore comes back
    `:path-not-found` on a HEALTHY machine, so a smoke that reads it with
    `get-path {…}` / `snapshot {path: …}` produces a false "machine never started"
    verdict (or, if repaired to the machines slice without the privacy contract, a
    `:rf/redacted`/summary result that also cannot prove presence). The canonical
    machine read is `read-sub [:rf/machine <id>]` or the runtime partition of
    `frame-state-value`; the gated `snapshot {include ["machines"] …}` slice is
    the multi-machine alternative. This guard (Rule 4) makes the app-db-only-read-
    at-a-runtime-db-path COMMAND shape a build failure. The stale claim it kills:
    "confirm the boot machine via `get-path`/`snapshot {path:}` on
    `[:rf.runtime/machines :snapshots]`" / "`snapshot {path: [:rf.db/runtime]}`".

All of these rules are written to fire ONLY on the stale ASSERTION / bad-command
shape, never on the corrected wording that states the negation — and never on the
unrelated, still-live `:on-error` *surfaces* (a machine `:spawn :on-error`
transition, a route `:on-error` lifecycle event), which are NOT frame-level
recovery policies. The skill legitimately mentions all of those in their corrected
forms — including the bare-prose "`get-path` reads app-db …" warning, which Rule 4
passes because it keys on the braced COMMAND shape, not a tool mention.

A fourth, structurally different guard (rf2-3fc89f.35) rides the same gate:

  * **M-1 classifier — the executable off-contract-namespace sweep must exempt
    every public destination and flag the private internals.** The skill ships a
    two-stage `rg` broad-scan + invert-filter (auto-call-site-rewrites.md) whose
    survivors it calls M-1 sites. An earlier filter omitted `adapter` and `spec`,
    so `re-frame.adapter.reagent` (the skill's OWN M-38/M-40 boot destination) and
    the M-54-preserved `re-frame.spec` survived and were wrongly flagged — the
    skill diagnosing its own required import as off-contract. This guard extracts
    the leaf's own invert-filter and runs it over representative requires, plus
    pins the exceptions-definition anchor, the breaking-changes / inventory links
    to it, the kickoff `ls-tree`/`rg` allowance, and the M-22 Type-A label. See
    `m1_classifier_problems()` / `m1_anchor_problems()`.

Scan surface: the user-facing migration-skill leaves (SKILL.md + references/*.md)
PLUS the migration corpus the skill treats as source of truth
(migration/from-re-frame-v1/README.md) and the two Reagent adapter README entry
points that migration guidance links to, since the review found the same drift
across those user-facing surfaces. The skill's `spec/` re-authoring meta-docs
are out of scope (not loaded during normal operation).

Exit code:
    0  no drift detected
    1  drift detected (printed line-by-line; GitHub-Actions ::error:: under CI)
    2  invocation / setup error

Usage:
    python scripts/check_skill_migration_contract_drift.py
    python scripts/check_skill_migration_contract_drift.py --verbose
    python scripts/check_skill_migration_contract_drift.py --ci
    python scripts/check_skill_migration_contract_drift.py --self-test

rf2-h9yfsm.
"""

from __future__ import annotations

import argparse
import bisect
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

SKILL_DIR = REPO_ROOT / "skills" / "re-frame-migration"
MIGRATION_MD = REPO_ROOT / "migration" / "from-re-frame-v1" / "README.md"
ADAPTER_READMES = (
    REPO_ROOT / "implementation" / "adapters" / "reagent" / "README.md",
    REPO_ROOT / "implementation" / "adapters" / "reagent-slim" / "README.md",
)
# The reagent-slim Form-3 adopter owner (rf2-aalo4n). FORM-3.md is the
# adopter-facing companion to the canonical migration recipe (guided-handlers-
# state.md §M-11); it carries the same lifecycle-frame contract the READMEs do,
# so it belongs on the same stale-claim scan surface, and its capture-once
# retarget invariance is pinned by `form3_capture_once_retarget_problems`.
FORM3_MD = REPO_ROOT / "implementation" / "adapters" / "reagent-slim" / "FORM-3.md"


def _scanned_files() -> list[Path]:
    """User-facing migration-skill leaves (SKILL.md + references/*.md), globbed so
    a new reference leaf is covered automatically, plus the migration corpus the
    skill treats as source of truth, the two Reagent adapter READMEs, and the
    reagent-slim Form-3 adopter owner (FORM-3.md — rf2-aalo4n). The skill's spec/
    meta-docs are excluded (re-authoring material, not loaded during normal
    operation)."""
    files = [SKILL_DIR / "SKILL.md"]
    files.extend(sorted((SKILL_DIR / "references").glob("*.md")))
    files.append(MIGRATION_MD)
    files.extend(ADAPTER_READMES)
    files.append(FORM3_MD)
    return files


# --- Rule 1: M-11 — plain Reagent fns "inherit" / "work inside" a frame scope.
#
# Fires when a line couples a PLAIN (non-reg-view) Reagent fn to a frame-provider
# / established-frame SCOPE with an INHERIT-shaped verb (inherit / work inside /
# fine / pick up / read the provider frame), UNLESS the same line carries a
# NEGATION cue — the corrected wording always says the plain fn CANNOT / does NOT
# read the frame, or names the loud error. We require all three signals (plain-fn
# subject, frame scope, inherit verb) so the rule never fires on the corrected
# "plain fns cannot read the surrounding frame-provider's frame" sentence.
PLAIN_FN_RE = re.compile(
    r"plain[- ](?:reagent[- ])?fn|plain[- ]reagent|non-`?reg-view`?\s+(?:reagent\s+)?fn",
    re.IGNORECASE,
)
FRAME_SCOPE_RE = re.compile(
    r"frame[- ]provider|frame scope|established frame|provider(?:'s)? frame"
    r"|surrounding (?:provider|frame)|inside (?:any|the|a)\s+.*frame",
    re.IGNORECASE,
)
INHERIT_VERB_RE = re.compile(
    r"inherit|work(?:s)? (?:in|inside)|works? fine|are fine|is fine|— fine"
    r"|pick(?:s)? up the (?:surrounding|provider)|read(?:s)? the (?:surrounding|provider)"
    r"|resolve(?:s)? (?:to|correctly)|target(?:s)? that frame|carry the frame",
    re.IGNORECASE,
)
# Negation cues — the corrected wording. Any of these on the line clears Rule 1:
# the line is stating the (true) limitation, not the (false) inheritance. These
# are deliberately plain-fn-SPECIFIC: a bare "fails loudly" / "raise" is NOT a
# negation cue because a stale line can carry it about an *escaping callback*
# ("…work inside any frame scope; only a callback … now fails loudly") while
# still asserting the false plain-fn inheritance. The real corrected wording
# always names the contextType limitation, the no-frame-context error, the
# reg-view contrast, or the warn-once supersession.
M11_NEGATION_RE = re.compile(
    r"cannot read|can't read|cannot inherit|can't inherit|do(?:es)? not (?:inherit|read)"
    r"|don't (?:inherit|read)|no\s+`?:contexttype`?|lacks?\s+(?:the\s+)?`?:contexttype`?"
    r"|no-frame-context"
    r"|superseded|retired warn|reg-view`?-registered (?:view )?does|does read the provider",
    re.IGNORECASE,
)

# --- Rule 3: M-11 — a component "pins to" / "falls through to" `:rf/default`.
#
# A distinct stale shape from Rule 1: the false claim that a component (left
# as-is under a provider) PINS / DEFAULTS / FALLS THROUGH to `:rf/default`
# "regardless of where it renders". This is the M-11 "Leave as-is" footgun — a
# frame-dependent plain fn left unchanged does NOT silently route to
# `:rf/default`; under EP-0002 there is no `:rf/default` floor, so a
# frame-scoped op raises `:rf.error/no-frame-context`. Rule 1 misses this shape
# because it requires a "plain fn" subject + an inherit-verb on the same line,
# and the stale "pins to :rf/default regardless" wording carries neither.
#
# Fires ONLY on the narrow IMPLICIT-RESOLUTION stale shape: a subject said to
# PIN / FALL THROUGH / silently ROUTE to `:rf/default` automatically. The verb
# set is intentionally tight — `pin(s/ned)`, `fall(s) through`, `(silently)
# route` — so the rule never fires on the MANY legitimate `:rf/default` mentions
# the corpus carries: scoping interceptors `to :rf/default`, naming `:rf/default`
# as the like-for-like replacement for non-frame-addressed v1 code, the optional
# `:frame` default, or the `:re-frame/default` -> `:rf/default` rewrite table.
# UNLESS the line carries a negation cue — the corrected wording always denies
# the floor or frames the target as EXPLICITLY scoped.
M11_RFDEFAULT_PIN_RE = re.compile(
    r"(?:pin(?:s|ned)?|fall(?:s)?\s+through|fall-through|silently\s+routes?)"
    r"[^.\n]*?`?:rf/default`?",
    re.IGNORECASE,
)
# Negation cues — the corrected wording. Any clears Rule 3: the line denies the
# floor, or marks `:rf/default` as something you must scope/pass EXPLICITLY.
M11_RFDEFAULT_NEGATION_RE = re.compile(
    r"no\s+`?:rf/default`?\s+(?:floor|tier|fall-through|fall\s+through)"
    r"|does not (?:silently )?(?:pin|route|fall)|do not describe"
    r"|no-frame-context|scope or pass|pass that frame|intentionally target",
    re.IGNORECASE,
)

# --- Rule 2: M-13 — reg-event-error-handler "moved to frame-level :on-error".
#
# Fires when a line asserts that reg-event-error-handler MOVED / MOVES to a
# FRAME-LEVEL (per-frame) `:on-error` (recovery) policy, UNLESS the line marks
# that policy removed / dropped / nonexistent / framework-owned. Scoped to the
# FRAME-LEVEL recovery-policy claim so it never fires on the still-live machine
# `:spawn :on-error` transition or route `:on-error` lifecycle event (neither is
# a frame-level recovery policy), nor on the corrected "no frame-level :on-error
# recovery policy" wording.
M13_ERROR_HANDLER_RE = re.compile(r"reg-event-error-handler")
M13_MOVED_TO_ONERROR_RE = re.compile(
    r"mov(?:ed|es) to (?:a )?(?:frame-level|per-frame)|"
    r"(?:frame-level|per-frame)\s+`?:on-error`?",
    re.IGNORECASE,
)
# Negation cues — the corrected wording. Any of these on the line clears Rule 2.
M13_NEGATION_RE = re.compile(
    r"removed|dropped|no app-steering|no\s+(?:app-steering\s+)?(?:frame-level\s+)?`?:on-error`?"
    r"|no(?:t)?\s+a\s+(?:frame-level\s+)?recovery policy|framework-owned|typed per-category"
    r"|has no v2 equivalent|no v2 (?:equivalent|policy)|was REMOVED|is gone|never read",
    re.IGNORECASE,
)

# --- Rule 4: Pair partition mismatch (rf2-j538f7.33) — an app-db-only Pair read
# COMMAND (`get-path {…}`, or `snapshot {… path: …}`) pointed at a runtime-db
# path (`:rf.db/runtime` or `:rf.runtime/*`). Pair's `get-path` is a `get-in`
# against the frame's **app-db** snapshot, and `snapshot`'s `path:` argument
# slices only the app-db slice — neither can reach the runtime-db partition, so a
# machine snapshot at `[:rf.runtime/machines :snapshots …]` comes back
# `:path-not-found` on a HEALTHY machine (a false "never started" verdict that
# can drive an unnecessary M-40/O-16 boot-machine rewrite). The canonical machine
# read is `read-sub [:rf/machine <id>]` or the runtime partition of
# `frame-state-value`; the gated `snapshot {include ["machines"] …}` slice is the
# multi-machine alternative (needs `--allow-sensitive-reads` + a per-call
# `include-sensitive: true`, else it returns `:rf/redacted` / a summary, both of
# which are INCONCLUSIVE, never absence). The rule keys on the braced COMMAND
# shape aimed at a runtime path — never on a bare prose mention of the tool
# (`get-path reads app-db …`), so the corrected warning wording passes; a
# same-line negation cue also clears it as a second safety net.
PAIR_APPDB_READ_CMD_RE = re.compile(
    r"get-path\s*\{|snapshot\s*\{[^}]*\bpath\s*:",
    re.IGNORECASE,
)
RUNTIME_DB_PATH_RE = re.compile(r":rf\.db/runtime|:rf\.runtime/")
# Negation cues — the corrected wording states the app-db-only LIMITATION. Any
# clears Rule 4. `\**` absorbs a markdown-bold `**app-db**`.
PAIR_PARTITION_NEGATION_RE = re.compile(
    r"reads?\s+(?:only\s+)?\**app-db|app-db[- ]only|will\s+not\s+(?:find|reach)"
    r"|won't\s+(?:find|reach)|cannot\s+(?:read|reach|find)|can't\s+(?:read|reach|find)"
    r"|does\s+not\s+(?:read|reach|select)|neither\s+can\s+reach|:?path-not-found",
    re.IGNORECASE,
)

# --- Rule 5: stock-Reagent Form-3 lifecycle advice must target the captured
# frame explicitly. The one-argument forms are valid while a real resolver scope
# exists, so this rule requires bounded lifecycle/hook context and clears
# corrected negative examples ("a bare call throws").
FORM3_LIFECYCLE_RE = re.compile(
    r"form-3|component-did-mount|component-did-update|component-will-unmount"
    r"|lifecycle",
    re.IGNORECASE,
)
# The bare form is an ARITY fact, not a spelling (rf2-vxgfnd.94.19).
# `subscribe-once` carries the frame as a `{:frame frame}` opts map and
# `unsubscribe` is frame-first, so a ONE-argument call is exactly the bare form
# lifecycle guidance must never recommend; two-or-more is frame-qualified and
# legal. Reading that needs balanced-form scanning rather than a token match:
# real guidance writes vector queries (`[:todos/all :active]`) and splits calls
# over lines, and a same-line whitespace-free-token pattern misses both — which
# let realistic unsafe recipes through the gate.
#
# This is deliberately NOT a Clojure parser. It matches a known head symbol,
# walks brackets to the closing paren inside a bounded window, and counts
# top-level arguments; it understands strings and character literals only as far
# as it takes to keep the bracket count honest. An excerpt that does not close
# inside the window is skipped, never guessed at.
FORM3_CALL_HEAD_RE = re.compile(
    r"\((?:rf/)?(?:subscribe-once|unsubscribe)(?![\w.$/-])",
    re.IGNORECASE,
)
FORM3_CALL_SCAN_LIMIT = 600  # chars — a lifecycle call form never runs longer
FORM3_BARE_ARITY = 1
_OPENERS = "([{"
_CLOSERS = ")]}"


def _read_form(text: str, start: int, body_start: int) -> tuple[int, int, int] | None:
    """Read the balanced form at `text[start] == '('`.

    `body_start` is the index just past the head symbol. Returns
    `(start, end, arity)` — `end` just past the closing paren, `arity` the count
    of top-level argument forms — or None if the form does not close within
    `FORM3_CALL_SCAN_LIMIT`.
    """
    limit = min(len(text), start + FORM3_CALL_SCAN_LIMIT)
    depth = 0
    arity = 0
    in_arg = False
    i = start
    while i < limit:
        ch = text[i]
        counts = depth == 1 and i >= body_start and not in_arg

        if ch == '"':  # string literal — opaque to the bracket counter
            if counts:
                arity += 1
                in_arg = True
            i += 1
            while i < limit and text[i] != '"':
                i += 2 if text[i] == "\\" else 1
            i += 1
            continue
        if ch == "\\":  # character literal — `\(` must not move the counter
            if counts:
                arity += 1
                in_arg = True
            i += 2
            continue
        if ch in _OPENERS:
            if counts:
                arity += 1
                in_arg = True
            depth += 1
            i += 1
            continue
        if ch in _CLOSERS:
            depth -= 1
            i += 1
            if depth == 0:
                return (start, i, arity)
            if depth == 1:
                in_arg = False
            continue
        if ch.isspace() or ch == ",":
            if depth == 1:
                in_arg = False
            i += 1
            continue
        if counts:  # ordinary atom character
            arity += 1
            in_arg = True
        i += 1
    return None


def _form3_call_sites(text: str) -> list[tuple[int, int, int]]:
    """`(start, end, arity)` for every balanced subscribe-once/unsubscribe form."""
    sites = []
    for m in FORM3_CALL_HEAD_RE.finditer(text):
        site = _read_form(text, m.start(), m.end())
        if site is not None:
            sites.append(site)
    return sites
FORM3_BARE_LIFECYCLE_NEGATION_RE = re.compile(
    r"\bbare\b|raise(?:s|d)?|throw(?:s|n)?|no-frame-context|do not|don't"
    r"|must not|never|invalid|wrong|omit(?:s|ted)?|instead",
    re.IGNORECASE,
)
# Example-polarity markers (rf2-vxgfnd.94.20). A BEFORE / negative-example
# marker exempts the calls that FOLLOW it; an AFTER / corrected marker ends that
# exemption. Both are matched by POSITION, not per block, so a historical BEFORE
# example cannot hide an affirmative recipe sitting beside it in the same fence.
FORM3_NEGATIVE_EXAMPLE_RE = re.compile(
    r"\bBEFORE\b|(?i:bad example|negative example|anti-pattern|do not copy)",
)
FORM3_POSITIVE_EXAMPLE_RE = re.compile(
    r"\bAFTER\b|(?i:recommended|good example|do this instead"
    r"|correct(?:ed)?\s+(?:example|recipe|form|shape|version))",
)
FORM3_SENTENCE_BOUNDARY_RE = re.compile(r"[.!?](?:[*_`]+)?\s+")

# Structural Markdown boundaries (rf2-vxgfnd.94.20). Lifecycle context must not
# leak past a heading or thematic break, else a `## Form-3 lifecycle` section
# makes every legal ambient call in later sections a false failure.
ATX_HEADING_RE = re.compile(r"^ {0,3}#{1,6}(?:\s|$)")
THEMATIC_BREAK_RE = re.compile(r"^ {0,3}(?:-{3,}|\*{3,}|_{3,})\s*$")
FENCE_RE = re.compile(r"^ {0,3}(`{3,}|~{3,})")

FORM3_BARE_LIFECYCLE_PROBLEM = (
    "FORM3-BARE-LIFECYCLE: a Form-3 lifecycle callback has no ambient "
    "frame after render scope unwinds. Capture the frame once in the "
    "registered outer callable; use `(rf/subscribe-once query-v "
    "{:frame frame})` for a hook one-shot read and frame-first "
    "`(rf/unsubscribe frame query-v)` for teardown. Do not recommend "
    "the bare one-argument forms in lifecycle guidance."
)


def _sentence_around(line: str, start: int, end: int) -> str:
    """The sentence of `line` containing the span `start`..`end`."""
    sentence_start = 0
    sentence_end = len(line)
    for boundary in FORM3_SENTENCE_BOUNDARY_RE.finditer(line):
        if boundary.end() <= start:
            sentence_start = boundary.end()
        elif boundary.start() >= end:
            sentence_end = boundary.end()
            break
    return line[sentence_start:sentence_end]


def _block_bare_calls(lines: list[str]) -> list[tuple[int, str, int]]:
    """`(line_offset, excerpt, block_offset)` for unnegated bare calls in a block.

    Scans the joined block so a call split across lines is still read as a
    single balanced form. Negation stays anchored to the sentence on the call's
    own line: a neighbouring paragraph explaining that a bare call throws must
    not bless an affirmative recipe further down. `block_offset` lets the caller
    apply example-polarity exemptions per call rather than per block.
    """
    text = "\n".join(lines)
    starts: list[int] = []
    pos = 0
    for line in lines:
        starts.append(pos)
        pos += len(line) + 1

    found: list[tuple[int, str, int]] = []
    for start, end, arity in _form3_call_sites(text):
        if arity != FORM3_BARE_ARITY:
            continue
        idx = bisect.bisect_right(starts, start) - 1
        line = lines[idx]
        col = start - starts[idx]
        sentence = _sentence_around(line, col, min(end - starts[idx], len(line)))
        if FORM3_BARE_LIFECYCLE_NEGATION_RE.search(sentence):
            continue
        found.append((idx, line.strip(), start))
    return found


def _slurp(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def line_problems(line: str) -> list[str]:
    """Return drift labels for a single line (empty when clean)."""
    problems: list[str] = []

    # Rule 1 — M-11 plain-fn-inherits-frame stale claim.
    if (
        PLAIN_FN_RE.search(line)
        and FRAME_SCOPE_RE.search(line)
        and INHERIT_VERB_RE.search(line)
        and not M11_NEGATION_RE.search(line)
    ):
        problems.append(
            "M11-PLAIN-FN-INHERITS: a plain (non-`reg-view`) Reagent fn does NOT "
            "inherit / work inside a `frame-provider`'s frame — it carries no "
            "`:contextType`, so a bare ambient `subscribe`/`dispatch` raises "
            "`:rf.error/no-frame-context` (EP-0002; spec/002-Frames.md, "
            "spec/004-Views.md). Only a `reg-view`-registered view reads the "
            "provider frame. State the limitation, not the (false) inheritance."
        )

    # Rule 3 — M-11 component "pins to" / "falls through to" `:rf/default`.
    if (
        M11_RFDEFAULT_PIN_RE.search(line)
        and not M11_RFDEFAULT_NEGATION_RE.search(line)
    ):
        problems.append(
            "M11-RFDEFAULT-PIN: a component left as-is under a frame-provider "
            "does NOT pin / fall through / silently route to `:rf/default` "
            "(EP-0002 — there is no `:rf/default` floor; a frame-scoped op "
            "raises `:rf.error/no-frame-context`). To intentionally target "
            "`:rf/default`, the component must scope or pass that frame "
            "explicitly. State the limitation, not the (false) auto-default."
        )

    # Rule 2 — M-13 reg-event-error-handler-moved-to-frame-level-:on-error claim.
    if (
        M13_ERROR_HANDLER_RE.search(line)
        and M13_MOVED_TO_ONERROR_RE.search(line)
        and not M13_NEGATION_RE.search(line)
    ):
        problems.append(
            "M13-FRAME-ONERROR: `reg-event-error-handler` did NOT move to a "
            "frame-level / per-frame `:on-error` recovery policy — that policy "
            "was REMOVED (rf2-hiqtk8). There is no app-steering error-recovery "
            "policy; recovery is framework-owned (typed per-category default). "
            "Observability is `register-error-listener!` (always-on) / "
            "`register-listener!` (dev-only). (The machine `:spawn :on-error` "
            "transition and route `:on-error` lifecycle event are unrelated "
            "live surfaces, NOT frame-level recovery policies.)"
        )

    # Rule 4 — Pair app-db-only read command aimed at a runtime-db path.
    if (
        PAIR_APPDB_READ_CMD_RE.search(line)
        and RUNTIME_DB_PATH_RE.search(line)
        and not PAIR_PARTITION_NEGATION_RE.search(line)
    ):
        problems.append(
            "PAIR-APPDB-RUNTIME-MISMATCH: a Pair `get-path {…}` / `snapshot "
            "{path: …}` read is app-db-only (get-path is a `get-in` against "
            "app-db; snapshot's `path:` slices only the app-db slice) and CANNOT "
            "reach a runtime-db path (`:rf.db/runtime` / `:rf.runtime/*`) — it "
            "returns `:path-not-found` on a HEALTHY machine, a false "
            "\"never started\" verdict. Read runtime-db machine snapshots via the "
            "canonical `read-sub {sub: \"[:rf/machine <id>]\"}` or the runtime "
            "partition of `frame-state-value`; for a multi-machine sweep use the "
            "gated `snapshot {include: [\"machines\"], modes: {\"machines\":"
            "\"full\"}}` slice (needs `--allow-sensitive-reads` + per-call "
            "`include-sensitive: true`; a `:rf/redacted`/summary result is "
            "INCONCLUSIVE, never absence). (rf2-j538f7.33.)"
        )

    # Rule 5 is NOT here: it needs Markdown context (section bounds + example
    # polarity) that a single line cannot supply, and a line-local copy would
    # miss split-line calls while double-reporting same-line ones. It lives in
    # `form3_context_problems`, which `find_drift` runs over the whole text.

    return problems


def _markdown_sections(text: str) -> list[list[tuple[int, list[str]]]]:
    """Split `text` into heading-delimited sections of non-blank blocks.

    Rule 5 needs bounded context because user-facing prose and fenced examples
    routinely place `:component-did-mount` and its call on adjacent lines. Two
    structures bound it:

    * A **block** (blank-line separated) is the smallest useful unit, so
      unrelated paragraphs are not treated as one global Form-3 context. A
      fenced code block is atomic — a blank line inside a fence does not split
      it, so a hook body stays a single unit.
    * A **section** ends at a Markdown heading or thematic break. Lifecycle
      context must not cross one: a `## Form-3 lifecycle` section does not make
      a legal ambient call three headings later illegal (rf2-vxgfnd.94.20). The
      heading line itself opens the new section, so a `## Form-3 lifecycle`
      heading still establishes context for the prose beneath it.
    """
    sections: list[list[tuple[int, list[str]]]] = []
    section: list[tuple[int, list[str]]] = []
    current: list[str] = []
    start = 1
    fence: str | None = None

    def end_block() -> None:
        nonlocal current
        if current:
            section.append((start, current))
            current = []

    def end_section() -> None:
        nonlocal section
        end_block()
        if section:
            sections.append(section)
            section = []

    for lineno, line in enumerate(text.splitlines(), start=1):
        fence_m = FENCE_RE.match(line)

        if fence is not None:  # inside a fence: everything is content
            current.append(line)
            if fence_m and fence_m.group(1)[0] == fence:
                fence = None
            continue

        if fence_m:
            if not current:
                start = lineno
            current.append(line)
            fence = fence_m.group(1)[0]
            continue

        if ATX_HEADING_RE.match(line) or THEMATIC_BREAK_RE.match(line):
            end_section()
            if ATX_HEADING_RE.match(line):
                start = lineno
                current = [line]
                end_block()
            continue

        if line.strip():
            if not current:
                start = lineno
            current.append(line)
        else:
            end_block()

    end_section()
    return sections


def _example_polarity_events(block: str) -> list[tuple[int, bool]]:
    """`(offset, exempt?)` for every example-polarity marker in `block`."""
    events = [(m.start(), True) for m in FORM3_NEGATIVE_EXAMPLE_RE.finditer(block)]
    events += [(m.start(), False) for m in FORM3_POSITIVE_EXAMPLE_RE.finditer(block)]
    events.sort()
    return events


def _polarity_at(events: list[tuple[int, bool]], pos: int, carried: bool) -> bool:
    """Exemption state at `pos`: the last marker at or before it, else `carried`."""
    state = carried
    for offset, value in events:
        if offset > pos:
            break
        state = value
    return state


def form3_context_problems(text: str) -> list[tuple[int, str, str]]:
    """Find Rule-5 drift inside bounded, structurally-delimited context.

    Bare one-argument forms are valid wherever a real ambient render scope
    exists, so this is deliberately not a file-wide search. Three bounds apply
    (rf2-vxgfnd.94.20):

    * **Structural.** Lifecycle context never crosses a Markdown heading or
      thematic break. Within a section, a call is dirty only when its own block
      or one of the three immediately preceding blocks establishes Form-3 /
      lifecycle context. Without the section bound a single `## Form-3
      lifecycle` heading poisons every later legal ambient call.
    * **Exemption.** A BEFORE / negative-example marker exempts the calls that
      FOLLOW it, up to the next AFTER / corrected marker or the end of the
      section. Applying it per block instead let an affirmative AFTER recipe
      hide beside a historical BEFORE example in the same fence.
    * **Negation.** Stays call-line local: a nearby paragraph explaining that a
      bare call throws must not bless a later affirmative recipe.

    This is the single reporting path for Rule 5 — `line_problems` deliberately
    does not carry it, so the same-line case gets the same bounds as every other
    (a `BEFORE` label must exempt a same-line call too).
    """
    found: list[tuple[int, str, str]] = []
    for section in _markdown_sections(text):
        recent: list[str] = []
        exempt = False
        for start, lines in section:
            block = "\n".join(lines)
            context = "\n\n".join([*recent[-3:], block])
            events = _example_polarity_events(block)
            if FORM3_LIFECYCLE_RE.search(context):
                for offset, excerpt, pos in _block_bare_calls(lines):
                    if _polarity_at(events, pos, exempt):
                        continue  # a BEFORE / negative example — historical
                    found.append(
                        (start + offset, FORM3_BARE_LIFECYCLE_PROBLEM, excerpt)
                    )
            exempt = _polarity_at(events, len(block), exempt)
            recent.append(block)
    return found


# ---------------------------------------------------------------------------
# M-1 public-namespace classifier + kickoff / slicing anchors (rf2-3fc89f.35).
#
# A second, structurally distinct defect class the M-11/M-13 line-scanner above
# cannot see: the migration skill's *executable* M-1 inventory — the two-stage
# `rg` broad-scan + invert-filter in auto-call-site-rewrites.md — must EXEMPT
# every documented public destination (re-frame.core, the
# re-frame.adapter.<substrate> tier that M-38 rewrites into and M-40 boot
# requires, the per-feature artefacts, re-frame.interop, and the M-54-preserved
# re-frame.spec) while still FLAGGING the private internals (re-frame.db /
# .utils / .router / .subs / .registrar / .loggers). An invert-filter that drops
# `adapter` / `spec` diagnoses the skill's OWN boot namespace as off-contract and
# can direct a routine migration to remove its required import, then fail
# compile/boot (rf2-3fc89f.35).
#
# This guard runs the skill's OWN documented invert-filter — extracted verbatim
# from the leaf, not a hand-kept copy — against representative require lines and
# asserts the classification, so re-dropping `adapter`/`spec` is a build failure.
# It also pins the prose anchors the fix introduced (the single exceptions
# definition + its inbound links, the kickoff `ls-tree`/`rg` allowance, the M-22
# Type-A classification) so they cannot silently rot back.
# ---------------------------------------------------------------------------

AUTO_CALL_SITE_MD = SKILL_DIR / "references" / "auto-call-site-rewrites.md"
BREAKING_CHANGES_MD = SKILL_DIR / "references" / "breaking-changes.md"
INVENTORY_PLAN_MD = SKILL_DIR / "references" / "inventory-and-plan.md"
KICKOFF_MD = SKILL_DIR / "references" / "kickoff-prompt.md"

M1_EXCEPTIONS_HEADING = "#### The M-1 public-surface exceptions"
M1_EXCEPTIONS_ANCHOR = (
    "auto-call-site-rewrites.md#the-m-1-public-surface-exceptions"
)

# The M-1 scan MUST flag these (private / off-contract internals — they survive
# the invert-filter).
M1_FLAG_NSES = [
    "re-frame.db", "re-frame.utils", "re-frame.router", "re-frame.subs",
    "re-frame.events", "re-frame.registrar", "re-frame.loggers",
    "re-frame.interceptor", "re-frame.fx", "re-frame.cofx",
    "re-frame.std-interceptors",
]
# The M-1 scan MUST NOT flag these (the public-surface exceptions — the
# invert-filter removes them).
M1_EXEMPT_NSES = [
    "re-frame.core",
    "re-frame.adapter.reagent", "re-frame.adapter.uix", "re-frame.adapter.helix",
    "re-frame.spec", "re-frame.interop",
    "re-frame.schemas", "re-frame.machines", "re-frame.routing", "re-frame.flows",
    "re-frame.http", "re-frame.http.managed", "re-frame.http.test-support",
    "re-frame.ssr", "re-frame.epoch", "re-frame.test-support",
]

# Extract the documented rg patterns. The broad-scan line ends `' . \` (space-dot);
# the invert line is the unique `rg -v '…re-frame…'`. Anchoring the broad-scan on
# the trailing ` .` skips the prose look-around counter-example further down.
_M1_BROAD_RE = re.compile(r"rg -n '([^']*re-frame[^']*)' \.")
_M1_INVERT_RE = re.compile(r"rg -v '([^']*re-frame[^']*)'")


def _require_line(ns: str) -> str:
    return f"  (:require [{ns} :as x])"


def _extract_m1_patterns(text: str):
    """Compile the leaf's documented broad-scan + invert-filter as Python regexes.
    Returns (broad, invert, error-or-None)."""
    mb = _M1_BROAD_RE.search(text)
    mv = _M1_INVERT_RE.search(text)
    if not mb or not mv:
        return None, None, (
            "could not locate the documented M-1 two-stage `rg` broad-scan / "
            "invert-filter in auto-call-site-rewrites.md — the executable M-1 "
            "inventory shape drifted; a migration can no longer run it."
        )
    try:
        return re.compile(mb.group(1)), re.compile(mv.group(1)), None
    except re.error as exc:  # pragma: no cover - defends against a mangled edit
        return None, None, f"documented M-1 rg pattern is not a valid regex: {exc}"


def _classify(line: str, broad: re.Pattern, invert: re.Pattern) -> str:
    """Mirror the documented two-stage sweep: a re-frame.* require that survives
    the invert-filter is an M-1 site ('flag'); one the filter removes is 'exempt'."""
    if not broad.search(line):
        return "not-a-require"
    return "exempt" if invert.search(line) else "flag"


def m1_classifier_problems() -> list[str]:
    """Run the skill's own invert-filter over representative requires."""
    if not AUTO_CALL_SITE_MD.is_file():
        return [f"SETUP: M-1 leaf missing: {AUTO_CALL_SITE_MD.relative_to(REPO_ROOT)}"]
    broad, invert, err = _extract_m1_patterns(_slurp(AUTO_CALL_SITE_MD))
    if err:
        return [f"M1-CLASSIFIER-SETUP: {err}"]
    problems: list[str] = []
    for ns in M1_FLAG_NSES:
        if _classify(_require_line(ns), broad, invert) != "flag":
            problems.append(
                f"M1-FALSE-NEGATIVE: the documented M-1 invert-filter EXEMPTS the "
                f"private/off-contract `{ns}` — it must survive the filter and be "
                f"flagged as an M-1 site."
            )
    for ns in M1_EXEMPT_NSES:
        if _classify(_require_line(ns), broad, invert) != "exempt":
            problems.append(
                f"M1-FALSE-POSITIVE: the documented M-1 invert-filter FLAGS the "
                f"public-surface `{ns}` — the skill would tell a migration its own "
                f"required destination is off-contract. Add it to the invert-filter "
                f"alternation AND the exceptions list (rf2-3fc89f.35)."
            )
    return problems


def m1_anchor_problems() -> list[str]:
    """Pin the prose anchors the rf2-3fc89f.35 fix introduced."""
    problems: list[str] = []

    # 1. The single canonical exceptions definition + its linkable anchor, naming
    #    the three surfaces the pre-fix incomplete subset dropped.
    if AUTO_CALL_SITE_MD.is_file():
        acs = _slurp(AUTO_CALL_SITE_MD)
        if M1_EXCEPTIONS_HEADING not in acs:
            problems.append(
                "M1-ANCHOR-MISSING: auto-call-site-rewrites.md no longer defines "
                f"the `{M1_EXCEPTIONS_HEADING}` section the other leaves link to."
            )
        for token in (
            "re-frame.adapter", "re-frame.spec", "re-frame.interop", "re-frame.core",
        ):
            if token not in acs:
                problems.append(
                    f"M1-EXCEPTIONS-INCOMPLETE: the M-1 leaf no longer names "
                    f"`{token}` as a public-surface exception."
                )
    else:
        problems.append("SETUP: auto-call-site-rewrites.md missing.")

    # 2. breaking-changes + inventory-and-plan link to the definition rather than
    #    restating an incomplete subset.
    for path in (BREAKING_CHANGES_MD, INVENTORY_PLAN_MD):
        if path.is_file() and M1_EXCEPTIONS_ANCHOR not in _slurp(path):
            problems.append(
                f"M1-NO-LINK-TO-DEFINITION: {path.name} does not link to "
                f"`{M1_EXCEPTIONS_ANCHOR}` — it likely restates an incomplete "
                f"public-surface subset instead of the single definition."
            )

    # 3. Kickoff prompt: the required read-only `ls-tree` + `rg` are permitted, the
    #    stale "only commands you run yourself" line is gone, and M-22 is not Type B.
    if KICKOFF_MD.is_file():
        k = _slurp(KICKOFF_MD)
        if "ls-tree" not in k:
            problems.append(
                "KICKOFF-NO-LSTREE: kickoff-prompt.md omits the `ls-tree` "
                "structural-identity check SKILL.md cardinal rule 5 requires."
            )
        if re.search(r"`rg`", k) is None:
            problems.append(
                "KICKOFF-NO-RG: kickoff-prompt.md no longer notes that the session "
                "runs the codebase `rg` inventories itself."
            )
        if re.search(r"only command[s]?\b[^.\n]*\byourself", k, re.IGNORECASE):
            problems.append(
                "KICKOFF-ONLY-COMMANDS: kickoff-prompt.md still says the provenance "
                "checks are the ONLY commands the session runs — that forbids the "
                "required read-only `rg`/`ls-tree` reads (contradicts cardinal rule 5)."
            )
        if re.search(r"M-22[^.\n]*Type\s*B", k, re.IGNORECASE):
            problems.append(
                "KICKOFF-M22-TYPEB: kickoff-prompt.md labels M-22 as Type B — M-22 "
                "(reg-view keyword-shape rewrite) is Type A / mechanical "
                "(MIGRATION.md; breaking-changes.md)."
            )
    else:
        problems.append("SETUP: kickoff-prompt.md missing.")

    return problems


# ---------------------------------------------------------------------------
# Form-3 capture-once retarget invariance (rf2-aalo4n).
#
# The reagent-slim FORM-3.md is the ADOPTER-facing owner of the Form-3
# capture-once recipe; guided-handlers-state.md §M-11 is the CANONICAL migration
# recipe. FORM-3.md §4 recommends capturing the frame-aware bundle *once in the
# outer `reg-view*` callable* (`(rf/capture-frame)`), but that handle is a LOCKED
# value: the frame is captured at mount and never re-resolves (implementation:
# `make-capture-frame` closes the captured frame over every op — core.cljc). So
# capture-once is safe ONLY while the mount's provider frame is invariant. A
# *surviving* instance retargeted from provider A to provider B keeps sending to
# the stale A, because React keeps it mounted and the outer callable does not
# re-run. The canonical recipe records this A→B footgun and its two frame-safe
# remedies (a frame-derived React `key` remount, or the registered `reg-view`
# child route); PR #5922 shipped the FORM-3.md capture-once advice WITHOUT the
# invariance, so the two owners could drift. This guard makes the adopter owner
# carry the invariant and keeps it aligned with the canonical recipe
# (rf2-aalo4n; historical rf2-vxgfnd.272/.288).
#
# Unlike the M-11/M-13 line rules above (which KILL stale claims), this is a
# POSITIVE-presence + cross-owner alignment check — the same shape as
# `m1_anchor_problems`: the invariant is a thing that must be PRESENT, and a
# future edit must not delete it from one owner while the other still carries it.
# ---------------------------------------------------------------------------

# capture-once / locked-value framing — the subject the invariance attaches to.
FORM3_CAPTURE_ONCE_RE = re.compile(
    r"capture-once|captur(?:e|ed|ing)\s+(?:the\s+frame\s+)?once"
    r"|locked\s+(?:value|handle)|locks?\s+to\s+(?:the\s+)?one\s+frame",
    re.IGNORECASE,
)
# The A→B retarget footgun — the SAME shape both owners must carry. Tolerant of
# spelling: "provider A to provider B" / "provider A to B" / "A→B" / "A -> B".
FORM3_RETARGET_RE = re.compile(
    r"provider\s+A\s+to\s+(?:provider\s+)?B|(?<![\w])A\s*(?:→|->)\s*B",
    re.IGNORECASE,
)
# At least one supported remedy: a frame-derived React key remount, or the
# registered `reg-view` child route (route 1).
FORM3_REMEDY_RE = re.compile(
    r"frame-derived\s+(?:react\s+)?`?key`?|`?key`?[^.\n]*frame[- ]id"
    r"|remount|reg-view`?\s+child|route\s+1",
    re.IGNORECASE,
)
# The pointer back to the canonical migration recipe.
FORM3_RECIPE_POINTER_RE = re.compile(r"guided-handlers-state\.md", re.IGNORECASE)


def _form3_capture_once_problems(f3_text: str, g_text: str | None) -> list[str]:
    """Pure presence + cross-owner alignment check over the two owners' text.

    Kept text-pure (no disk read) so the self-test can exercise it against
    fixtures and live mutations, mirroring the M-1 classifier / live-corpus teeth.
    `g_text` is the canonical guided-handlers-state.md text; None skips the
    cross-owner alignment leg (a SETUP problem is reported separately)."""
    problems: list[str] = []
    if not FORM3_CAPTURE_ONCE_RE.search(f3_text):
        problems.append(
            "FORM3-CAPTURE-ONCE-MISSING: FORM-3.md no longer frames the "
            "outer-callable `(rf/capture-frame)` as capture-once / a locked handle "
            "— the retarget invariance has no subject to attach to (rf2-aalo4n)."
        )
    if not FORM3_RETARGET_RE.search(f3_text):
        problems.append(
            "FORM3-RETARGET-MISSING: FORM-3.md omits the capture-once retarget "
            "invariance — it must state that a *surviving* instance retargeted "
            "from provider A to provider B keeps sending render/lifecycle actions "
            "to the stale A (the locked handle does not re-resolve; the outer "
            "callable does not re-run). (rf2-aalo4n; canonical: "
            "guided-handlers-state.md §M-11.)"
        )
    if not FORM3_REMEDY_RE.search(f3_text):
        problems.append(
            "FORM3-REMEDY-MISSING: FORM-3.md states the provider A→B stale case "
            "but points at no supported remedy — name the frame-derived React "
            "`key` remount or the registered `reg-view` child (route 1). Do NOT "
            "reach for a mutable / re-pointable capture (rf2-aalo4n)."
        )
    if not FORM3_RECIPE_POINTER_RE.search(f3_text):
        problems.append(
            "FORM3-RECIPE-POINTER-MISSING: FORM-3.md does not point at the "
            "canonical migration recipe (guided-handlers-state.md §M-11) for the "
            "full capture-once retarget routes (rf2-aalo4n)."
        )
    if g_text is not None and not (
        FORM3_RETARGET_RE.search(g_text) and FORM3_CAPTURE_ONCE_RE.search(g_text)
    ):
        problems.append(
            "FORM3-CANONICAL-DRIFT: guided-handlers-state.md no longer carries the "
            "capture-once provider A→B retarget invariance that FORM-3.md mirrors "
            "— the adopter owner and the canonical recipe have drifted "
            "(rf2-aalo4n). Re-align both owners, or re-point this guard's anchors."
        )
    return problems


def form3_capture_once_retarget_problems() -> list[str]:
    """Pin the Form-3 capture-once retarget invariance in BOTH owners (rf2-aalo4n).

    The adopter owner (FORM-3.md) must state when capture-once is safe, the
    provider A→B stale-bundle case, at least one supported remedy, and a pointer
    to the canonical recipe. The canonical recipe (guided-handlers-state.md §M-11)
    must still carry the aligned invariance, so the two owners cannot drift
    apart."""
    if not FORM3_MD.is_file():
        return [
            f"SETUP: Form-3 adopter owner missing: {FORM3_MD.relative_to(REPO_ROOT)}"
        ]
    g_text = _slurp(GUIDED_HANDLERS_MD) if GUIDED_HANDLERS_MD.is_file() else None
    problems = _form3_capture_once_problems(_slurp(FORM3_MD), g_text)
    if g_text is None:
        problems.append(
            f"SETUP: canonical recipe missing: "
            f"{GUIDED_HANDLERS_MD.relative_to(REPO_ROOT)} — the Form-3 capture-once "
            "cross-owner alignment leg cannot run."
        )
    return problems


def find_drift(files: list[Path]) -> tuple[list[str], int]:
    problems: list[str] = []
    lines_checked = 0
    for path in files:
        if not path.is_file():
            problems.append(
                f"SETUP: expected migration-skill / corpus file missing: "
                f"{path.relative_to(REPO_ROOT)} — the guard's file list drifted "
                "from the layout; update _scanned_files()."
            )
            continue
        text = _slurp(path)
        for lineno, line in enumerate(text.splitlines(), start=1):
            lines_checked += 1
            for label in line_problems(line):
                rel = path.relative_to(REPO_ROOT)
                problems.append(f"{rel}:{lineno}: {label}\n    {line.strip()}")
        for lineno, label, excerpt in form3_context_problems(text):
            rel = path.relative_to(REPO_ROOT)
            problems.append(f"{rel}:{lineno}: {label}\n    {excerpt}")
    return problems, lines_checked


def run(*, verbose: bool, ci: bool) -> int:
    if not SKILL_DIR.is_dir():
        sys.stderr.write(
            f"error: re-frame-migration skill not found at {SKILL_DIR}\n"
        )
        return 2
    if not MIGRATION_MD.is_file():
        sys.stderr.write(f"error: MIGRATION.md not found at {MIGRATION_MD}\n")
        return 2

    files = _scanned_files()
    problems, lines_checked = find_drift(files)
    problems.extend(m1_classifier_problems())
    problems.extend(m1_anchor_problems())
    problems.extend(form3_capture_once_retarget_problems())

    if verbose:
        print(
            f"migration contract-drift guard: scanned {len(files)} files "
            f"({lines_checked} lines) + ran the M-1 classifier over "
            f"{len(M1_FLAG_NSES) + len(M1_EXEMPT_NSES)} representative requires "
            "+ pinned the Form-3 capture-once retarget invariance in both owners."
        )

    if not problems:
        if verbose:
            print(
                "contract-drift: no plain-fn-inherits-frame (M-11), "
                "moved-to-frame-level-:on-error (M-13), boot-smoke Pair "
                "partition-mismatch (Rule 4), Form-3 bare-lifecycle targeting "
                "(Rule 5), Form-3 capture-once retarget-invariance drift "
                "(rf2-aalo4n), or M-1 classifier / kickoff-anchor drift found."
            )
        return 0

    err_prefix = "::error::" if ci else ""
    for p in problems:
        print(f"{err_prefix}{p}")
    print(
        f"\ncontract-drift: {len(problems)} drift issue(s) — the migration skill "
        "is the agent's source of truth for what breaks; align M-11 (plain fns "
        "DON'T inherit the provider frame), M-13 (no frame-level `:on-error` "
        "recovery policy), the boot-smoke Pair partition contract (app-db-only "
        "`get-path`/`snapshot {path:}` CANNOT read runtime-db; use "
        "`read-sub [:rf/machine <id>]`), and the M-1 classifier (public "
        "destinations exempt, private internals flagged) / kickoff anchors, plus "
        "Form-3 lifecycle explicit-frame targeting and the Form-3 capture-once "
        "retarget invariance (FORM-3.md + guided-handlers-state.md §M-11 aligned "
        "— rf2-aalo4n), to the shipped contract."
    )
    return 1


# ---------------------------------------------------------------------------
# Self-test — exercises the line classifier against in-memory fixtures so the
# guard itself can't silently rot. Mirrors the --self-test convention in the
# sibling check_skill_*.py guards. The FAIL fixtures are the EXACT pre-fix
# stale-claim shapes the rf2-h9yfsm review found; the PASS fixtures are the
# corrected wording (and the unrelated live `:on-error` surfaces).
# ---------------------------------------------------------------------------

GUIDED_HANDLERS_MD = SKILL_DIR / "references" / "guided-handlers-state.md"

# Live-corpus mutation teeth (rf2-vxgfnd.94.15). Each entry mutates the frame-
# qualified form the skill actually teaches into the bare form the contract
# forbids. The guard MUST notice every one of them.
LIVE_FORM3_MUTATIONS = (
    (
        "one-shot lifecycle read drops its `{:frame frame}` opts",
        "(rf/subscribe-once query-v {:frame frame})",
        "(rf/subscribe-once query-v)",
    ),
    (
        "lifecycle teardown drops its frame-first argument",
        "(rf/unsubscribe frame query-v)",
        "(rf/unsubscribe query-v)",
    ),
)


def _rule5_dirty(text: str) -> bool:
    """True when `text` trips Rule 5 (`form3_context_problems` is its one path)."""
    return bool(form3_context_problems(text))


def _live_corpus_mutation_problems() -> list[str]:
    """Run Rule 5 against MUTATIONS OF THE SHIPPED GUIDANCE, not hand-written prose.

    The fixtures above are authored to match the corpus, which means they can
    drift away from it silently: re-author the real recipe into a shape the
    scanner cannot read and every fixture still passes while the live scan goes
    vacuous. These teeth close that gap from the other side — they take the
    actual guided-handlers-state.md lifecycle recipe, break it in the exact way
    a careless edit would, and require the guard to catch it. If the recipe is
    re-authored, the anchors below stop matching and this reports STALE rather
    than quietly proving nothing.
    """
    if not GUIDED_HANDLERS_MD.is_file():
        return [
            f"SETUP: {GUIDED_HANDLERS_MD.name} missing — the live Form-3 "
            "mutation teeth cannot run."
        ]
    text = _slurp(GUIDED_HANDLERS_MD)
    if _rule5_dirty(text):
        return [
            "LIVE-CORPUS-DIRTY: the shipped guided-handlers-state.md lifecycle "
            "guidance already trips Rule 5, so the mutation teeth below cannot "
            "prove the guard has bite. Fix the guidance (or the rule) first."
        ]
    problems: list[str] = []
    for label, qualified, bare in LIVE_FORM3_MUTATIONS:
        if qualified not in text:
            problems.append(
                f"LIVE-MUTATION-STALE: guided-handlers-state.md no longer "
                f"contains `{qualified}`, so the mutation teeth for '{label}' "
                f"are vacuous — the Form-3 recipe was re-authored. Re-point "
                f"LIVE_FORM3_MUTATIONS at the current recipe."
            )
            continue
        if not _rule5_dirty(text.replace(qualified, bare)):
            problems.append(
                f"LIVE-MUTATION-UNDETECTED: mutating the LIVE Form-3 recipe so "
                f"the {label} does not trip Rule 5. The guard is blind to the "
                f"shape the corpus actually ships (rf2-vxgfnd.94.15)."
            )
    return problems


def _self_test() -> int:
    failures = 0

    def expect(line: str, *, dirty: bool, label: str) -> None:
        nonlocal failures
        got = bool(line_problems(line))
        if got != dirty:
            print(
                f"SELF-TEST FAIL ({label}): expected dirty={dirty}, got "
                f"{got} for: {line!r}"
            )
            failures += 1

    def expect_text(text: str, *, dirty: bool, label: str) -> None:
        nonlocal failures
        got = bool(form3_context_problems(text))
        if got != dirty:
            print(
                f"SELF-TEST FAIL ({label}): expected dirty={dirty}, got "
                f"{got} for multiline text: {text!r}"
            )
            failures += 1

    # FAIL fixtures — the exact pre-fix stale claims (SKILL.md:156,
    # breaking-changes.md M-11/M-13 rows, README:1734/512 shapes).
    expect(
        "Plain Reagent fns work in v2 inside any established frame scope; only a "
        "callback that escapes the render scope now fails loudly.",
        dirty=True, label="A1 plain fns work inside any established frame scope",
    )
    expect(
        "Under EP-0002 a plain fn rendered inside a frame-provider inherits that "
        "frame ambiently — fine.",
        dirty=True, label="A2 plain fn inside frame-provider inherits — fine",
    )
    expect(
        "Plain fns lack the routing wiring; their subscribe calls still route to "
        ":rf/default, but reg-view'd components pick up the surrounding provider.",
        dirty=True, label="A3 plain fns route + (no negation)",
    )
    expect(
        "| `(rf/reg-event-error-handler ...)` | M-13 | B | Moved to frame-level "
        "`:on-error` (per-frame policy) or `register-listener!`. |",
        dirty=True, label="A4 reg-event-error-handler moved to frame-level :on-error",
    )
    expect(
        "reg-event-error-handler moves to a per-frame :on-error recovery policy.",
        dirty=True, label="A5 reg-event-error-handler moves to per-frame :on-error",
    )
    # A6/A7 — the exact pre-fix M-11 "Leave as-is" stale claim (guided-handlers-
    # state.md:90 shape) + the README fall-through variant.
    expect(
        "Leave as-is. The author accepts that the component pins to `:rf/default` "
        "regardless of where it renders.",
        dirty=True, label="A6 component pins to :rf/default regardless",
    )
    expect(
        "A plain fn with no enclosing provider just falls through to :rf/default.",
        dirty=True, label="A7 plain fn falls through to :rf/default",
    )

    # PASS fixtures — the corrected wording must NOT flag.
    expect(
        "A plain (non-`reg-view`) Reagent fn carries no `:contextType` wiring, so "
        "it cannot read the surrounding `frame-provider`'s frame; a bare ambient "
        "subscribe raises `:rf.error/no-frame-context`.",
        dirty=False, label="B1 plain fn cannot read provider frame (correct)",
    )
    expect(
        "Plain Reagent fns do not inherit the frame-provider under Reagent; "
        "registered views do.",
        dirty=False, label="B2 plain fns do NOT inherit (correct)",
    )
    expect(
        "A `reg-view`-registered view DOES read the provider frame (it attaches "
        "`:contextType`); a plain fn under a frame-provider raises no-frame-context.",
        dirty=False, label="B3 reg-view reads provider + plain raises (correct)",
    )
    expect(
        "`reg-event-error-handler` is dropped; there is no app-steering frame-level "
        "`:on-error` recovery policy — recovery is framework-owned.",
        dirty=False, label="B4 error-handler dropped, no :on-error policy (correct)",
    )
    expect(
        "reg-event-error-handler did NOT move to a frame-level `:on-error` policy — "
        "it was REMOVED (rf2-hiqtk8).",
        dirty=False, label="B5 explicitly denies the move (correct)",
    )
    # Unrelated, still-live `:on-error` surfaces — must NOT flag (no
    # reg-event-error-handler subject; not a frame-level recovery policy).
    expect(
        "`:spawn :on-error` is a first-class transition the parent takes when a "
        "spawned child fails (XState v5 invoke onError parity).",
        dirty=False, label="B6 machine :spawn :on-error (live, unrelated)",
    )
    expect(
        "A route may declare `:on-error` — an event the runtime dispatches if an "
        "`:on-match` event errors.",
        dirty=False, label="B7 route :on-error lifecycle event (live, unrelated)",
    )
    expect(
        "A frame-provider scopes a frame to a subtree; reg-view descendants "
        "resolve to it at render time.",
        dirty=False, label="B8 reg-view descendants resolve (no plain-fn subject)",
    )
    # B9/B10/B11 — the corrected M-11 "Leave as-is" wording (guided-handlers-
    # state.md:90 post-fix) and explicit-target phrasing must NOT flag.
    expect(
        "Do not describe this as pinning to `:rf/default`: there is no "
        "`:rf/default` fall-through (EP-0002), so a frame-dependent plain fn "
        "raises `:rf.error/no-frame-context` — it does not silently route to a "
        "default. To intentionally target `:rf/default`, scope or pass that "
        "frame explicitly.",
        dirty=False, label="B9 corrected M-11 leave-as-is wording (no :rf/default pin)",
    )
    expect(
        "There is no `:rf/default` floor; the read tier returns nil and the op "
        "fails loudly.",
        dirty=False, label="B10 no :rf/default floor (correct)",
    )
    expect(
        "To target `:rf/default`, scope it explicitly with `with-frame` or an "
        "explicit `{:frame :rf/default}` opt.",
        dirty=False, label="B11 explicit :rf/default target (correct)",
    )

    # --- Rule 4 fixtures (rf2-j538f7.33) ---------------------------------------
    # FAIL fixtures — the exact pre-fix bad boot-smoke commands: an app-db-only
    # Pair read pointed at a runtime-db path.
    expect(
        'confirm boot machines have a live snapshot via `get-path {path: '
        '"[:rf.runtime/machines :snapshots]"}` in runtime-db',
        dirty=True, label="C1 get-path {path: [:rf.runtime/machines ...]}",
    )
    expect(
        'drill: `snapshot {path: "[:rf.db/runtime]"}` (the runtime-db partition).',
        dirty=True, label="C2 snapshot {path: [:rf.db/runtime]}",
    )
    expect(
        'read the boot machine via `get-path {path: "[:rf.runtime/machines '
        ':snapshots :app/boot]"}`.',
        dirty=True, label="C3 get-path at a specific runtime machine snapshot",
    )
    # PASS fixtures — the corrected partition-aware recipe and the negated warning.
    expect(
        'the canonical `read-sub {sub: "[:rf/machine :app/boot]"}` reads the '
        'runtime-db machine snapshot at `[:rf.runtime/machines :snapshots]`.',
        dirty=False, label="D1 canonical read-sub machine read (correct)",
    )
    expect(
        '`get-path` reads app-db and will NOT find `[:rf.runtime/machines '
        ':snapshots]` — use the `[:rf/machine <id>]` sub.',
        dirty=False, label="D2 get-path-reads-app-db warning (correct)",
    )
    expect(
        "`snapshot`'s `path:` selector slices only app-db — neither can reach "
        "`[:rf.runtime/machines :snapshots]`; use the gated machines slice.",
        dirty=False, label="D3 snapshot-path-is-app-db-only warning (correct)",
    )
    expect(
        'raw-nREPL: `(:rf.db/runtime (rf/frame-state-value :app/main))` — the '
        'runtime-db partition (snapshots at `[:rf.runtime/machines :snapshots]`).',
        dirty=False, label="D4 raw-nREPL runtime partition eval (correct)",
    )
    expect(
        'the gated `snapshot {include: ["machines"], modes: {"machines": "full"}}` '
        "slice sweeps every `[:rf.runtime/machines :snapshots]` entry.",
        dirty=False, label="D5 gated snapshot machines slice (correct, no path:)",
    )
    expect(
        'a `get-path {path: "[:session :ready?]"}` read of an app-db seed slot.',
        dirty=False, label="D6 get-path at an app-db slot (correct)",
    )

    # --- Rule 5 fixtures — stock-Reagent Form-3 lifecycle targeting ------------
    expect_text(
        "In :component-did-mount read `(rf/subscribe-once query-v)` and in "
        ":component-will-unmount call `(rf/unsubscribe query-v)`.",
        dirty=True, label="E1 Form-3 lifecycle recommends bare read/teardown",
    )
    expect_text(
        "In a Form-3 hook use `(rf/subscribe-once query-v {:frame frame})`; "
        "teardown is `(rf/unsubscribe frame query-v)`.",
        dirty=False, label="E2 Form-3 lifecycle targets captured frame",
    )
    expect_text(
        "A bare `(rf/subscribe-once query-v)` in a lifecycle hook throws "
        "`:rf.error/no-frame-context`.",
        dirty=False, label="E3 negative bare lifecycle example",
    )
    expect_text(
        "**Form-3 lifecycle.** Capture the frame in the outer callable.\n\n"
        "- **One-shot current value at mount:** "
        "`(rf/subscribe-once query-v)` retains no handle.",
        dirty=True, label="E4 multiline Form-3 paragraph catches bare one-shot",
    )
    expect_text(
        "**Form-3 lifecycle.** A hook has no ambient frame.\n\n"
        "```clojure\n:component-did-mount\n"
        "(fn [_]\n  (rf/subscribe-once query-v))\n```",
        dirty=True, label="E5 multiline hook code catches bare one-shot",
    )
    expect_text(
        "**Form-3 lifecycle.** Capture the frame in the outer callable.\n\n"
        "- **One-shot current value at mount:** "
        "`(rf/subscribe-once query-v {:frame frame})` retains no handle.",
        dirty=False, label="E6 multiline explicit-frame recipe is clean",
    )
    expect_text(
        "**Form-3 lifecycle — negative example.** Do not copy this.\n\n"
        "```clojure\n:component-did-mount\n"
        "(fn [_]\n  (rf/subscribe-once query-v))\n```",
        dirty=False, label="E7 explicit negative multiline example is clean",
    )
    expect_text(
        "**Form-3 lifecycle — BEFORE.** Do not copy this.\n\n"
        "`(rf/subscribe-once old-query)`\n\n"
        "**Recommended Form-3 lifecycle.** Use the current recipe.\n\n"
        "`(rf/subscribe-once query-v)`",
        dirty=True,
        label="E8 older negative example cannot bless a later positive recipe",
    )
    expect_text(
        "**Form-3 lifecycle.** Acquire in `:component-did-mount`. Pair it "
        "with `(rf/unsubscribe query-v)` in "
        "`:component-will-unmount`. A teardown that omits the frame throws "
        "`:rf.error/no-frame-context`.",
        dirty=True,
        label="E9 later warning cannot bless earlier bare teardown",
    )

    # --- Rule 5 realistic call shapes (rf2-vxgfnd.94.19) ------------------------
    # The pre-fix pattern only saw a one-argument call whose argument was a
    # single whitespace-free token on the call's own line. Guidance does not
    # look like that: queries are vectors and calls wrap. Each F-case below
    # passed the pre-fix gate while teaching an unsafe recipe.
    expect_text(
        "In `:component-did-mount` read `(rf/subscribe-once [:todos/all :active])`.",
        dirty=True, label="F1 same-line vector query arg is bare",
    )
    expect_text(
        "**Form-3 lifecycle.** A hook has no ambient frame.\n\n"
        "```clojure\n:component-did-mount\n(fn [_]\n"
        "  (rf/subscribe-once [:todos/all :active]))\n```",
        dirty=True, label="F2 vector query arg inside a lifecycle hook",
    )
    expect_text(
        "**Form-3 lifecycle.** A hook has no ambient frame.\n\n"
        "```clojure\n:component-did-mount\n(fn [_]\n"
        "  (rf/subscribe-once\n    query-v))\n```",
        dirty=True, label="F3 call split across lines",
    )
    expect_text(
        "**Form-3 lifecycle.** Teardown must name the frame.\n\n"
        "```clojure\n:component-will-unmount\n(fn [_]\n"
        "  (rf/unsubscribe\n    [:todos/all :active]))\n```",
        dirty=True, label="F4 split-line teardown with a vector query",
    )
    expect_text(
        "**Form-3 lifecycle.** Seed the chart at mount.\n\n"
        "```clojure\n:component-did-mount\n(fn [_]\n"
        "  (rf/subscribe-once (build-query-v id)))\n```",
        dirty=True, label="F5 list-expression query arg is still one argument",
    )
    # Frame-qualified equivalents of every shape above MUST pass — the opts map
    # / frame-first argument is what makes the call legal, and arity is how the
    # scanner sees it.
    expect_text(
        "**Form-3 lifecycle.** A hook has no ambient frame.\n\n"
        "```clojure\n:component-did-mount\n(fn [_]\n"
        "  (rf/subscribe-once [:todos/all :active] {:frame frame}))\n```",
        dirty=False, label="G1 vector query + explicit frame opts is clean",
    )
    expect_text(
        "**Form-3 lifecycle.** A hook has no ambient frame.\n\n"
        "```clojure\n:component-did-mount\n(fn [_]\n"
        "  (rf/subscribe-once\n    [:todos/all :active]\n    {:frame frame}))\n```",
        dirty=False, label="G2 split-line call with explicit frame opts is clean",
    )
    expect_text(
        "**Form-3 lifecycle.** Teardown is frame-first.\n\n"
        "```clojure\n:component-will-unmount\n(fn [_]\n"
        "  (rf/unsubscribe\n    frame\n    [:todos/all :active]))\n```",
        dirty=False, label="G3 split-line frame-first teardown is clean",
    )
    # Adversarial parser cases — the scanner must not mis-count arity.
    expect_text(
        "In `:component-did-mount` call `(rf/subscribe-once [:msg \"a b c\"])`.",
        dirty=True, label="G4 string with spaces inside a vector is one argument",
    )
    expect_text(
        "In `:component-did-mount` call "
        "`(rf/subscribe-once [:msg \"}{)(\"] {:frame frame})`.",
        dirty=False, label="G5 brackets inside a string do not break the counter",
    )
    expect_text(
        "A lifecycle hook may call `(rf/subscribe-once-ish query-v)` — a "
        "different fn entirely.",
        dirty=False, label="G6 head-symbol prefix match is not a subscribe-once call",
    )
    expect_text(
        "A lifecycle note mentioning `(rf/unsubscribe-all frame)` is unrelated.",
        dirty=False, label="G7 unsubscribe-all is not unsubscribe",
    )
    expect_text(
        "An unbalanced `:component-did-mount` excerpt `(rf/subscribe-once query-v` "
        "is never guessed at.",
        dirty=False, label="G8 unbalanced excerpt is skipped, not assumed bare",
    )
    # The arity rule must not become a global ban: a bare one-argument call is
    # CORRECT wherever a real resolver scope exists. Only lifecycle context
    # makes it drift. (These mirror the shapes the live corpus ships at
    # breaking-changes.md O-6 and README O-13/O-14.)
    expect_text(
        "Drop the type checks; use `(rf/subscribe-once [:todos/all :active])` if "
        "you need the value outside a reactive context.",
        dirty=False, label="G9 bare vector call with no lifecycle context is legal",
    )
    expect_text(
        "Outside of views (event handlers, fx, REPL) the substrate-agnostic "
        "`(rf/subscribe [:foo])` and `(rf/subscribe-once [:foo])` still work.",
        dirty=False, label="G10 ambient bare call in the O-13 shape is legal",
    )

    # --- Rule 5 structural bounds (rf2-vxgfnd.94.20) ----------------------------
    # H-cases: a heading ENDS lifecycle context. Without this a single Form-3
    # section falsely rejects every legal ambient call below it.
    expect_text(
        "## Form-3 lifecycle\n\n"
        "A hook has no ambient frame; use `(rf/subscribe-once query-v "
        "{:frame frame})`.\n\n"
        "## Ambient reads in a registered view\n\n"
        "A `reg-view` body runs under a live resolver scope, so "
        "`(rf/subscribe-once query-v)` resolves against the provider frame.",
        dirty=False, label="H1 heading ends lifecycle context; ambient call legal",
    )
    expect_text(
        "## Form-3 lifecycle\n\n"
        "Capture the frame in the outer callable.\n\n"
        "---\n\n"
        "In an ordinary registered view, `(rf/subscribe-once [:todos/all])` "
        "reads the provider frame.",
        dirty=False, label="H2 thematic break ends lifecycle context",
    )
    expect_text(
        "## Form-3 lifecycle\n\n"
        "A hook has no ambient frame.\n\n"
        "Seed the library with `(rf/subscribe-once query-v)` at mount.",
        dirty=True, label="H3 context still carries WITHIN its own section",
    )
    expect_text(
        "### Form-3 lifecycle\n\n"
        "Capture the frame in the outer callable.\n\n"
        "```clojure\n:component-did-mount\n(fn [_]\n"
        "  (rf/subscribe-once query-v))\n```",
        dirty=True, label="H4 heading itself establishes context for its section",
    )
    # I-cases: BEFORE/AFTER polarity is per call, not per block. The pre-fix
    # block-wide exemption let an affirmative AFTER recipe hide beside a
    # historical BEFORE example in the same fence.
    expect_text(
        "**Form-3 lifecycle.** Capture the frame in the outer callable.\n\n"
        "```clojure\n"
        ";; BEFORE (v1) — no frame to name\n"
        "(rf/subscribe-once old-query)\n"
        ";; AFTER (v2) — this is the recipe to copy\n"
        "(rf/subscribe-once query-v)\n"
        "```",
        dirty=True,
        label="I1 BEFORE cannot hide an AFTER recipe in the same fence",
    )
    expect_text(
        "**Form-3 lifecycle.** Capture the frame in the outer callable.\n\n"
        "```clojure\n"
        ";; BEFORE (v1) — no frame to name\n"
        "(rf/subscribe-once old-query)\n"
        ";; AFTER (v2) — this is the recipe to copy\n"
        "(rf/subscribe-once query-v {:frame frame})\n"
        "```",
        dirty=False,
        label="I2 BEFORE exempt + frame-qualified AFTER is wholly clean",
    )
    expect_text(
        "**Form-3 lifecycle — BEFORE.** Do not copy this.\n\n"
        "```clojure\n:component-did-mount\n(fn [_]\n"
        "  (rf/subscribe-once [:todos/all :active]))\n```",
        dirty=False,
        label="I3 BEFORE label exempts the fence that follows it",
    )
    expect_text(
        "## Form-3 lifecycle — BEFORE\n\n"
        "`(rf/subscribe-once old-query)`\n\n"
        "## Form-3 lifecycle — the current recipe\n\n"
        "`(rf/subscribe-once query-v)`",
        dirty=True,
        label="I4 exemption does not survive a heading into the next section",
    )
    expect_text(
        "**Form-3 lifecycle.** BEFORE: `(rf/subscribe-once old-query)` was the "
        "v1 shape.",
        dirty=False,
        label="I5 same-line BEFORE label exempts the call after it",
    )
    # J-cases: fences are atomic, so a blank line inside a hook body neither
    # splits the block nor loses the surrounding lifecycle context.
    expect_text(
        "**Form-3 lifecycle.** Capture the frame in the outer callable.\n\n"
        "```clojure\n"
        ":component-did-mount\n"
        "(fn [_]\n"
        "  (do-some-setup!)\n"
        "\n"
        "  ;; a blank line above must not split this fence\n"
        "  (rf/subscribe-once query-v))\n"
        "```",
        dirty=True, label="J1 blank line inside a fence keeps the block atomic",
    )
    expect_text(
        "## Form-3 lifecycle\n\n"
        "A hook has no ambient frame.\n\n"
        "```markdown\n"
        "## An example heading, quoted inside a fence\n"
        "```\n\n"
        "Seed the library with `(rf/subscribe-once query-v)` at mount.",
        dirty=True,
        label="J2 a heading inside a fence does not reset context",
    )

    # --- M-1 classifier fixtures (rf2-3fc89f.35) --------------------------------
    # Exercise the classifier logic against the CORRECTED invert-filter (adapters
    # + spec exempt, privates flagged) and the PRE-FIX buggy filter (missing
    # adapter/spec) — the latter proving the guard detects the regression.
    good_broad = re.compile(r"\[\s*re-frame\.[a-z-]+")
    good_invert = re.compile(
        r"\[\s*re-frame\.(adapter|core|interop|schemas|machines|routing|flows|"
        r"http|ssr|epoch|test-support|spec)\b"
    )
    bad_invert = re.compile(  # the exact pre-fix filter — no `adapter`, no `spec`
        r"\[\s*re-frame\.(core|interop|schemas|machines|routing|flows|"
        r"http|http-managed|http-test-support|ssr|epoch|test-support)\b"
    )

    def m1_expect(ns: str, pattern: re.Pattern, want: str, label: str) -> None:
        nonlocal failures
        got = _classify(_require_line(ns), good_broad, pattern)
        if got != want:
            print(f"SELF-TEST FAIL ({label}): {ns} classified {got!r}, want {want!r}")
            failures += 1

    # Corrected filter: public destinations exempt (incl. the M-38/M-40 adapters
    # + the M-54 spec ns), private internals flagged (incl. the router/routing +
    # interop/interceptor near-miss edges).
    for ns in (
        "re-frame.core", "re-frame.adapter.reagent", "re-frame.adapter.uix",
        "re-frame.adapter.helix", "re-frame.spec", "re-frame.interop",
        "re-frame.routing", "re-frame.http.managed", "re-frame.test-support",
    ):
        m1_expect(ns, good_invert, "exempt", f"M1-good-exempt {ns}")
    for ns in (
        "re-frame.db", "re-frame.utils", "re-frame.router", "re-frame.registrar",
        "re-frame.loggers", "re-frame.interceptor", "re-frame.std-interceptors",
    ):
        m1_expect(ns, good_invert, "flag", f"M1-good-flag {ns}")
    # Pre-fix buggy filter MUST misclassify the adapters + spec as flagged — this
    # is the exact rf2-3fc89f.35 false-positive the corrected filter removes.
    for ns in ("re-frame.adapter.reagent", "re-frame.adapter.uix", "re-frame.spec"):
        m1_expect(ns, bad_invert, "flag", f"M1-regression-detected {ns}")

    # --- Live-corpus mutation teeth (rf2-vxgfnd.94.15) --------------------------
    for problem in _live_corpus_mutation_problems():
        print(f"SELF-TEST FAIL (live-corpus mutation): {problem}")
        failures += 1

    # --- Form-3 capture-once retarget invariance (rf2-aalo4n) -------------------
    # Fixtures for the pure presence + cross-owner alignment helper. A complete
    # adopter owner carries: capture-once framing, the provider A→B stale case, a
    # supported remedy, and the canonical-recipe pointer; a canonical owner
    # carrying the aligned invariance clears the drift leg.
    K_OWNER = (
        "Capture-once is a locked handle. It goes stale if a surviving instance "
        "is retargeted from provider A to provider B — the outer callable does "
        "not re-run, so the locked handle keeps sending to the stale A, never B. "
        "Remedy: a frame-derived React `key` remount, or the registered "
        "`reg-view` child (route 1). See `guided-handlers-state.md` §M-11."
    )
    K_CANON = (
        "Capture-once is locked to one frame; if a surviving instance is "
        "retargeted from provider A to provider B the locked handle keeps sending "
        "to the stale A. Force a remount with a frame-derived React key, or use "
        "route 1."
    )

    def expect_form3(f3: str, g: str | None, *, dirty: bool, label: str) -> None:
        nonlocal failures
        got = bool(_form3_capture_once_problems(f3, g))
        if got != dirty:
            print(
                f"SELF-TEST FAIL ({label}): expected dirty={dirty}, got {got}."
            )
            failures += 1

    expect_form3(K_OWNER, K_CANON, dirty=False, label="K1 complete owner + aligned canonical is clean")
    expect_form3(
        K_OWNER.replace("retargeted from provider A to provider B", "kept on one frame"),
        K_CANON, dirty=True, label="K2 owner without the A→B retarget case is dirty",
    )
    expect_form3(
        "It goes stale if a surviving instance is retargeted from provider A to "
        "provider B. Remedy: a frame-derived React `key` remount, or the "
        "registered `reg-view` child (route 1). See `guided-handlers-state.md` "
        "§M-11.",
        K_CANON, dirty=True, label="K3 owner without capture-once framing is dirty",
    )
    expect_form3(
        K_OWNER.replace(
            "a frame-derived React `key` remount, or the registered "
            "`reg-view` child (route 1)", "some other approach"),
        K_CANON, dirty=True, label="K4 owner naming no supported remedy is dirty",
    )
    expect_form3(
        K_OWNER.replace("See `guided-handlers-state.md` §M-11.", "See below."),
        K_CANON, dirty=True, label="K5 owner without the canonical pointer is dirty",
    )
    expect_form3(
        K_OWNER,
        K_CANON.replace("retargeted from provider A to provider B", "kept on one frame"),
        dirty=True, label="K6 canonical drifting off the A→B invariance is dirty",
    )

    # Live-corpus teeth: the SHIPPED owners must both carry the invariant, and a
    # mutation that drops the A→B sentence from either owner must be caught — so a
    # careless re-author of the real docs cannot make this guard go vacuous.
    if not FORM3_MD.is_file():
        print(f"SELF-TEST FAIL (form3 live): {FORM3_MD.name} missing.")
        failures += 1
    elif not GUIDED_HANDLERS_MD.is_file():
        print(f"SELF-TEST FAIL (form3 live): {GUIDED_HANDLERS_MD.name} missing.")
        failures += 1
    else:
        f3_live = _slurp(FORM3_MD)
        g_live = _slurp(GUIDED_HANDLERS_MD)
        live = _form3_capture_once_problems(f3_live, g_live)
        if live:
            print(
                "SELF-TEST FAIL (form3 live clean): the shipped Form-3 owners "
                "already trip the capture-once retarget guard:"
            )
            for p in live:
                print(f"  {p}")
            failures += 1
        f3_broken = FORM3_RETARGET_RE.sub("under one steady frame", f3_live)
        if not _form3_capture_once_problems(f3_broken, g_live):
            print(
                "SELF-TEST FAIL (form3 owner mutation): dropping the provider A→B "
                "retarget sentence from FORM-3.md did not trip the guard — it is "
                "blind to the shape the adopter owner actually ships."
            )
            failures += 1
        g_broken = FORM3_RETARGET_RE.sub("under one steady frame", g_live)
        if not _form3_capture_once_problems(f3_live, g_broken):
            print(
                "SELF-TEST FAIL (form3 canonical mutation): dropping the provider "
                "A→B retarget sentence from guided-handlers-state.md did not trip "
                "the cross-owner drift leg."
            )
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
