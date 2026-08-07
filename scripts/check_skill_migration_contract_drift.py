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

  * **Form-3 captured-`subscribe` acquisition — the exceptional imperative
    subscription (rf2-v84zn).** The ONE Form-3 that holds a live reactive
    subscription outside render captures the frame once in the outer callable and
    MUST both destructure `subscribe` from that bundle
    (`{:keys [frame subscribe]} (rf/capture-frame)`) AND acquire its reaction
    through that captured local inside `:component-did-mount`
    (`(let [reaction (subscribe query-v)] …)`) — never the ambient `rf/subscribe`,
    whose ambient frame lookup a lifecycle hook (render scope already unwound)
    cannot satisfy, so it raises `:rf.error/no-frame-context`. Rule 5 above (bare
    arity) covers `subscribe-once` / `unsubscribe`; it never required the acquire
    to route through the captured op, so swapping `(subscribe query-v)` for
    `(rf/subscribe query-v)` at the acquire site left the guard green. This narrow
    Rule-5b check pins that acquire + its matching destructuring in the ONE
    canonical fenced example — identified by the once-capture, the mount hook, and
    the frame-first teardown that marks the long-lived-reaction branch, so it never
    fires on the dispatch-only route-2 examples, the `capture-frame` rename recipe,
    or explanatory prose. See `form3_captured_subscribe_problems`.

  * **Form-3 reactive OWNERSHIP — the exceptional imperative subscription must
    activate what it acquires (rf2-ynved; defect shape rf2-8cnxg).** Rules 5 and
    5b police where the reaction comes from; neither asks whether anything
    ACTIVATES it. Under the stock-Reagent adapter a subscription is a bare
    `reagent.ratom/Reaction` built without `:auto-run`, and a Reaction learns its
    sources only through `deref-capture` — so a deref taken in
    `:component-did-mount` runs the body raw, leaves `watching` nil, and puts the
    node in no watcher set. A bare `add-watch` on it is a TRAP: registered, never
    fires, widget fed once at mount and deaf thereafter. That shape shipped in the
    copy-pasteable recipe. The rule requires, on the same one canonical fenced
    example, no `add-watch`, a per-mount `r/track!` owner, and a matching
    `r/dispose!` at unmount. See `form3_reactive_owner_problems`.

  * **Form-3 capture-once retarget invariance — a RELATIONSHIP + POLARITY +
    cross-owner check (rf2-aalo4n, rf2-gjrlz).** The reagent-slim FORM-3.md is the
    adopter-facing owner of the Form-3 capture-once recipe; guided-handlers-
    state.md §M-11 is the canonical migration recipe. FORM-3.md recommends
    capturing the frame once in the outer `reg-view*` callable, but that handle is
    a LOCKED value that never re-resolves (`make-capture-frame` closes the captured
    frame over every op — core.cljc), so capture-once is safe ONLY while the
    mount's provider frame is invariant: a *surviving* instance retargeted from
    provider A to provider B keeps sending to the stale A. Each owner must state
    the invariant with SEMANTIC TEETH, not just the vocabulary: the capture-once
    framing, the A→B retarget, and the stale-A consequence must be tied together
    in ONE paragraph (scattered tokens do not count), the adopter owner's paragraph
    must carry a supported remedy — a frame-derived React `key` remount or the
    registered `reg-view` child — and the canonical-recipe pointer, and NEITHER
    owner may assert the OPPOSITE polarity (capture-once auto-retargets /
    re-resolves / follows automatically, never goes stale, or needs no remount),
    even when every positive vocabulary token still appears elsewhere. The
    corrected adaptive-remedy prose (route 1 "follows A→B with no remount") is
    exempt — it is the fix, not the footgun. This started as a POSITIVE-presence
    census (rf2-aalo4n) that a reversal with scattered vocabulary slipped past;
    rf2-gjrlz gave it the relationship + polarity teeth. See
    `form3_capture_once_retarget_problems` / `_retarget_invariance_problems`.

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

  * **M-0 publication-route lock (rf2-snjn5) — the migration guide teaches the
    author-supplied pinned consumption route, never an invented "latest".** The
    event that flips install prose is OFF-REPO (an operator release decision),
    so no code change reds it naturally, and the guide has lied in both
    polarities. Three narrow assertions over MIGRATION_MD only: no `"<latest>"`
    version placeholder in a dependency coordinate; no leave-the-dep-alone /
    wait-for-a-release stop instruction (the migration is fully doable — a
    first release is not a precondition, per the skill's setup.md); and the
    M-0 SECTION ITSELF (its heading up to the next `### M-N.` rule heading)
    links the canonical deps-versions.md §Choosing the coordinate recipe so one
    publication-state decision governs the whole guide. The delegation check is
    section-scoped because the seven artefact rules M-27..M-33 carry the same
    anchor by design — a whole-file substring test stays green with M-0's own
    link removed (the rf2-snjn5 merged-PR audit's acceptance seam). Same class
    as the setup skill's Lock 9. Retire deliberately at a real first publish.
    See `m0_publication_route_problems`.

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

# --- Shared lexical pass (rf2-6m5qb) ----------------------------------------
# A small Markdown-aware Clojure classifier is the shared basis for BOTH
# call-head discovery (a `(rf/subscribe-once …)` inside a string or a `;`
# comment is NOT a call, so `(log/debug "(rf/subscribe-once query-v)")` must not
# be flagged) AND balanced-form arity reading (a string / char literal is one
# opaque argument; a `;`-to-EOL comment is neither an argument nor bracket
# structure, so a bare multiline call with a comment stays arity 1 instead of
# being inflated past the bare threshold).
#
# Crucially the Clojure lexis (string / char / comment) is honoured ONLY inside
# code contexts — fenced code blocks and inline-code (backtick) spans. In
# Markdown prose a `;` is ordinary punctuation and a `"` an ordinary quote, so
# prose is left CODE: a call head in prose inline code is still discoverable, and
# a prose semicolon does not hide the rest of a line. It is deliberately NOT a
# Clojure reader — only enough to keep call-head discovery and bracket/arity
# counting honest at the string / char / comment boundaries.
_LEX_CODE = 0     # brackets, symbols, whitespace, and all Markdown prose
_LEX_STRING = 1   # inside a "…" string literal (both quotes included)
_LEX_CHAR = 2     # a \x character literal (backslash + its target char)
_LEX_COMMENT = 3  # a ; comment, to end of line (the ; through the last non-\n)


def _clj_kind(text: str) -> bytearray:
    """Pure Clojure lexical classification of a CODE region (no Markdown).

    A single pass; each region is consumed whole so lexical contexts cannot nest
    wrongly (a `;` inside a string is string, a `"` inside a comment is comment,
    a `\\;` char literal does not open a comment). Callers pass only text already
    known to be code (a fenced line or an inline-code span)."""
    n = len(text)
    kind = bytearray(n)  # defaults to _LEX_CODE (0)
    i = 0
    while i < n:
        ch = text[i]
        if ch == '"':  # string literal — opaque, honours \" escapes
            kind[i] = _LEX_STRING
            i += 1
            while i < n and text[i] != '"':
                if text[i] == "\\" and i + 1 < n:
                    kind[i] = _LEX_STRING
                    kind[i + 1] = _LEX_STRING
                    i += 2
                    continue
                kind[i] = _LEX_STRING
                i += 1
            if i < n:  # the closing quote
                kind[i] = _LEX_STRING
                i += 1
            continue
        if ch == "\\":  # character literal — backslash + its target char
            kind[i] = _LEX_CHAR
            if i + 1 < n:
                kind[i + 1] = _LEX_CHAR
            i += 2
            continue
        if ch == ";":  # comment to end of line
            while i < n and text[i] != "\n":
                kind[i] = _LEX_COMMENT
                i += 1
            continue
        i += 1  # ordinary code (brackets / symbols / whitespace / newline)
    return kind


def _lex_scan(text: str) -> bytearray:
    """Markdown-aware classification: Clojure lexing only inside code contexts.

    A fenced code block and each inline-code (backtick) span is lexed with
    `_clj_kind`; everything else (Markdown prose, the fence/backtick delimiters)
    stays `_LEX_CODE`. So a string inside a fenced example is opaque, but a `;`
    or `"` in an English sentence is not mistaken for a comment/string that would
    swallow a following call head (rf2-6m5qb)."""
    n = len(text)
    kind = bytearray(n)  # prose + delimiters default to _LEX_CODE (0)
    in_fence: str | None = None
    pos = 0
    for line in text.split("\n"):
        ll = len(line)
        fence_m = FENCE_RE.match(line)
        if in_fence is not None:  # inside a fenced block: whole line is code
            kind[pos:pos + ll] = _clj_kind(line)
            if fence_m and fence_m.group(1)[0] == in_fence:
                in_fence = None
        elif fence_m:  # the opening fence line — delimiters stay code
            in_fence = fence_m.group(1)[0]
        else:  # Markdown prose: lex only the inline-code span interiors
            for m in INLINE_CODE_RE.finditer(line):
                s, e = m.start(), m.end()
                kind[pos + s:pos + e] = _clj_kind(line[s:e])
        pos += ll + 1  # + 1 for the newline that split() removed
    return kind


def _read_form(
    text: str, start: int, body_start: int, kind: bytearray
) -> tuple[int, int, int] | None:
    """Read the balanced form at `text[start] == '('`.

    `body_start` is the index just past the head symbol; `kind` is the shared
    `_lex_scan` classification of `text`. Returns `(start, end, arity)` — `end`
    just past the closing paren, `arity` the count of top-level argument forms —
    or None if the form does not close within `FORM3_CALL_SCAN_LIMIT`. Strings
    and char literals are opaque single arguments; `;` comments are skipped
    entirely so their words and any stray brackets can neither inflate arity nor
    corrupt the bracket depth (rf2-6m5qb).
    """
    limit = min(len(text), start + FORM3_CALL_SCAN_LIMIT)
    depth = 0
    arity = 0
    in_arg = False
    i = start
    while i < limit:
        k = kind[i]
        counts = depth == 1 and i >= body_start and not in_arg

        if k == _LEX_STRING or k == _LEX_CHAR:  # opaque literal — one argument
            if counts:
                arity += 1
                in_arg = True
            i += 1
            while i < limit and kind[i] == k:
                i += 1
            continue
        if k == _LEX_COMMENT:  # `;`-to-EOL — not an argument, not structure
            if depth == 1:
                in_arg = False
            i += 1
            while i < limit and kind[i] == _LEX_COMMENT:
                i += 1
            continue

        ch = text[i]
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
    """`(start, end, arity)` for every balanced subscribe-once/unsubscribe form.

    Call heads are discovered over the shared `_lex_scan` mask so a call-shaped
    token inside a string / char literal / comment is skipped, not read as a
    call (rf2-6m5qb)."""
    kind = _lex_scan(text)
    sites = []
    for m in FORM3_CALL_HEAD_RE.finditer(text):
        if kind[m.start()] != _LEX_CODE:  # head inside a string / comment / char
            continue
        site = _read_form(text, m.start(), m.end(), kind)
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
# exemption. Both are matched by POSITION, so a historical BEFORE example cannot
# hide an affirmative recipe sitting beside it in the same fence. The markers are
# read only over Markdown prose and `;` comment text (`_polarity_scan_text`), so
# a `(log/debug "BEFORE")` string cannot forge an exemption (rf2-6m5qb).
#
# `not recommended` / `n't recommended` is NEGATIVE prose — the author is naming
# an anti-pattern — so it must exempt the call it owns, NOT be read as an
# affirmative `recommended` marker. The positive `recommended` therefore carries
# a `not `/`n't ` negative-lookbehind, and the negated forms live in the negative
# set (rf2-6m5qb).
FORM3_NEGATIVE_EXAMPLE_RE = re.compile(
    r"\bBEFORE\b|(?i:bad example|negative example|anti-pattern|do not copy"
    r"|(?:not|n't) recommended)",
)
FORM3_POSITIVE_EXAMPLE_RE = re.compile(
    r"\bAFTER\b|(?i:good example|do this instead"
    r"|correct(?:ed)?\s+(?:example|recipe|form|shape|version)"
    r"|(?<!not )(?<!n't )recommended)",
)
FORM3_SENTENCE_BOUNDARY_RE = re.compile(r"[.!?](?:[*_`]+)?\s+")

# Structural Markdown boundaries (rf2-vxgfnd.94.20). Lifecycle context must not
# leak past a heading or thematic break, else a `## Form-3 lifecycle` section
# makes every legal ambient call in later sections a false failure. ATX (`#…`),
# Setext (a paragraph underlined by `===`/`---`), and CommonMark thematic breaks
# — including the spaced `* * *` / `- - -` / `_ _ _` forms — all bound context
# (rf2-6m5qb).
ATX_HEADING_RE = re.compile(r"^ {0,3}#{1,6}(?:\s|$)")
THEMATIC_BREAK_RE = re.compile(
    r"^ {0,3}(?:(?:-[ \t]*){3,}|(?:\*[ \t]*){3,}|(?:_[ \t]*){3,})$"
)
# A Setext underline: a run of `=` (H1) or `-` (H2), no internal spaces. Only a
# heading when a paragraph line directly precedes it — see `_markdown_sections`.
SETEXT_UNDERLINE_RE = re.compile(r"^ {0,3}(?:=+|-+)[ \t]*$")
FENCE_RE = re.compile(r"^ {0,3}(`{3,}|~{3,})")

# Inline-code spans in Markdown prose (single/multi backtick). Their interior is
# code, so an example-polarity label is not read from it (`_polarity_scan_text`).
INLINE_CODE_RE = re.compile(r"`+[^`]*`+")

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
      heading still establishes context for the prose beneath it. Headings are
      recognised in all three CommonMark shapes — ATX (`#…`), Setext (a
      paragraph underlined by `===`/`---`), and spaced/compact thematic breaks —
      so a Setext-headed lifecycle section is still bounded and a later Setext
      heading still ends the prior context (rf2-6m5qb).
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

        # Setext heading: an `=`/`-` underline directly under a (non-fence)
        # paragraph turns that paragraph into a heading (H1/H2). Checked before
        # the thematic-break branch because a `---` under a paragraph is a Setext
        # H2 underline, NOT a thematic break (CommonMark). The accumulated
        # paragraph opens the new section, exactly like an ATX heading; the
        # underline itself is consumed.
        if (
            SETEXT_UNDERLINE_RE.match(line)
            and current
            and not FENCE_RE.match(current[0])
        ):
            heading_lines = current
            current = []          # keep the heading out of the ending section
            end_section()
            current = heading_lines  # `start` already points at the heading text
            end_block()
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


def _polarity_scan_text(block: str) -> str:
    """Blank the regions where an example-polarity label must NOT be read.

    A `BEFORE` / `AFTER` / negative-example / `not recommended` label is honoured
    only in Markdown prose (outside a fenced code block, outside an inline-code
    span) and in Clojure `;` comment text (the shape the corpus uses inside a
    fence, e.g. `;; BEFORE (v1)`). Fenced code tokens, string/char-literal
    contents, and inline-code spans are blanked to spaces — offsets preserved, so
    the marker positions still line up with call positions — so that a
    `(log/debug "BEFORE")` cannot forge an exemption (rf2-6m5qb).
    """
    kept: list[str] = []
    in_fence: str | None = None
    for line in block.split("\n"):
        fence_m = FENCE_RE.match(line)
        if in_fence is not None:  # inside a fenced code block
            if fence_m and fence_m.group(1)[0] == in_fence:
                in_fence = None
            # keep only `;` comment text; blank code + string contents
            kind = _clj_kind(line)
            kept.append(
                "".join(
                    ch if kind[i] == _LEX_COMMENT else " "
                    for i, ch in enumerate(line)
                )
            )
            continue
        if fence_m:  # the opening fence line — code, blank it
            in_fence = fence_m.group(1)[0]
            kept.append(" " * len(line))
            continue
        # Markdown prose — keep it, but blank inline-code span interiors.
        kept.append(
            INLINE_CODE_RE.sub(lambda m: " " * (m.end() - m.start()), line)
        )
    return "\n".join(kept)


def _example_polarity_events(block: str) -> list[tuple[int, bool]]:
    """`(offset, exempt?)` for every example-polarity marker in `block`.

    Markers are read only over the prose / comment text of the block
    (`_polarity_scan_text` blanks fenced code, strings, and inline-code spans),
    so a label inside a code string cannot forge one (rf2-6m5qb). Offsets are
    preserved by the blanking, so they still align with call positions."""
    scan = _polarity_scan_text(block)
    events = [(m.start(), True) for m in FORM3_NEGATIVE_EXAMPLE_RE.finditer(scan)]
    events += [(m.start(), False) for m in FORM3_POSITIVE_EXAMPLE_RE.finditer(scan)]
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
      FOLLOW it, within its own block and the one concrete example block it
      introduces, up to the next AFTER / corrected marker. It owns only that
      concrete example: an unmarked block does NOT propagate an inherited
      exemption further, so a historical BEFORE cannot bless an ordinary later
      affirmative recipe in the same section (rf2-6m5qb closes the indefinite
      carry). Applying it per block instead let an affirmative AFTER recipe hide
      beside a historical BEFORE example in the same fence.
    * **Negation.** Stays call-line local: a nearby paragraph explaining that a
      bare call throws must not bless a later affirmative recipe.

    This is the single reporting path for Rule 5 — `line_problems` deliberately
    does not carry it, so the same-line case gets the same bounds as every other
    (a `BEFORE` label must exempt a same-line call too).
    """
    found: list[tuple[int, str, str]] = []
    for section in _markdown_sections(text):
        recent: list[str] = []
        carried = False  # exemption inherited from the immediately preceding block
        for start, lines in section:
            block = "\n".join(lines)
            context = "\n\n".join([*recent[-3:], block])
            events = _example_polarity_events(block)
            if FORM3_LIFECYCLE_RE.search(context):
                for offset, excerpt, pos in _block_bare_calls(lines):
                    if _polarity_at(events, pos, carried):
                        continue  # a BEFORE / negative example — historical
                    found.append(
                        (start + offset, FORM3_BARE_LIFECYCLE_PROBLEM, excerpt)
                    )
            # A block's polarity carries forward exactly one block — to the
            # concrete example a trailing BEFORE/negative label introduces — and
            # then lapses. An unmarked block resets the carry, so the exemption
            # cannot leak across the whole section (rf2-6m5qb).
            carried = events[-1][1] if events else False
            recent.append(block)
    return found


# ---------------------------------------------------------------------------
# Rule 5b — Form-3 captured-`subscribe` acquisition (rf2-v84zn).
#
# The exceptional imperative-subscription Form-3 is the ONE Form-3 that holds a
# live reactive subscription OUTSIDE render (a JS widget re-fed from a hook as a
# sub's value changes). It captures the frame ONCE in the `reg-view*` outer
# callable and MUST both (1) destructure `subscribe` from that bundle
# (`{:keys [frame subscribe]} (rf/capture-frame)`) and (2) ACQUIRE its reaction
# through that captured local inside `:component-did-mount`
# (`(let [reaction (subscribe query-v)] …)`). The ambient `rf/subscribe` is wrong
# there: a lifecycle hook fires after the render/resolver scope has unwound, so an
# ambient `subscribe` finds no frame and raises `:rf.error/no-frame-context`
# (the same unwind that re-raises a fresh `(rf/capture-frame)` in a hook).
#
# Rule 5 (`form3_context_problems`) only reads `subscribe-once` / `unsubscribe`
# bare arity; it never required the acquire to route through the captured op, so
# swapping `(subscribe query-v)` for `(rf/subscribe query-v)` at the acquire site
# left BOTH the baseline and the mutated scan empty — a false green after the
# central ownership rule regressed (rf2-v84zn).
#
# The check keys on the ONE canonical fenced example so it never fires on the
# dispatch-only route-2 examples (they destructure `{:keys [dispatch]}` and never
# `unsubscribe`), the M-68 `capture-frame` rename recipe (no lifecycle hook), the
# route-1 outer/inner pattern, or explanatory prose (it scans fenced Clojure code
# only, with strings / `;` comments blanked). This is deliberately NOT a Clojure
# parser and does NOT touch Rule 5 or the capture-once retarget teeth (rf2-gjrlz).
# ---------------------------------------------------------------------------

# The once-captured frame-op destructuring that binds `subscribe`:
# `{:keys [… subscribe …]} (rf/capture-frame)`. The `subscribe` local is the
# matching destructuring the acquire must route through.
FORM3_CAPTURE_SUBSCRIBE_DESTRUCTURE_RE = re.compile(
    r"\{:keys\s+\[[^\]]*\bsubscribe\b[^\]]*\]\}\s*"
    r"\((?:rf/|re-frame\.core/)?capture-frame\b"
)
# The `capture-frame` call — the once-capture landmark of the outer callable.
FORM3_CAPTURE_FRAME_CALL_RE = re.compile(r"\((?:rf/|re-frame\.core/)?capture-frame\b")
# The frame-first `unsubscribe` CALL — the teardown that marks the long-lived
# reaction branch. Used only as an identifier landmark; Rule 5 owns its arity.
FORM3_UNSUBSCRIBE_CALL_RE = re.compile(r"\((?:rf/|re-frame\.core/)unsubscribe\b")
# An AMBIENT `subscribe` acquire — the regression: `(rf/subscribe …)` /
# `(re-frame.core/subscribe …)`. The `(?![-\w])` excludes `subscribe-once`.
FORM3_AMBIENT_SUBSCRIBE_RE = re.compile(
    r"\((?:rf/|re-frame\.core/)subscribe(?![-\w])"
)
# A CAPTURED (bare) `subscribe` acquire — `(subscribe …)`, the destructured
# local. The leading `(` sits immediately before `subscribe`, so a namespaced
# `(rf/subscribe …)` (a `/` precedes `subscribe`) never matches this.
FORM3_CAPTURED_SUBSCRIBE_RE = re.compile(r"\(subscribe(?![-\w])")
FORM3_DIDMOUNT_RE = re.compile(r":component-did-mount\b")

FORM3_AMBIENT_SUBSCRIBE_PROBLEM = (
    "FORM3-AMBIENT-SUBSCRIBE-ACQUIRE: the exceptional imperative-subscription "
    "Form-3 acquires its long-lived reaction through the AMBIENT `rf/subscribe`. A "
    "`:component-did-mount` hook fires after the render/resolver scope has unwound, "
    "so an ambient `subscribe` finds no frame and raises "
    "`:rf.error/no-frame-context`. Acquire through the `subscribe` destructured "
    "ONCE from `(rf/capture-frame)` in the outer callable — "
    "`(let [reaction (subscribe query-v)] …)` — never `(rf/subscribe …)`. "
    "(rf2-v84zn.)"
)
FORM3_MISSING_ACQUIRE_PROBLEM = (
    "FORM3-CAPTURED-SUBSCRIBE-UNUSED: the exceptional imperative-subscription "
    "Form-3 destructures `subscribe` from `(rf/capture-frame)` but never acquires "
    "its reaction through that captured local in `:component-did-mount`. The one "
    "lifecycle use of the captured `subscribe` is the acquire — "
    "`(let [reaction (subscribe query-v)] …)`. (rf2-v84zn.)"
)
FORM3_MISSING_DESTRUCTURE_PROBLEM = (
    "FORM3-CAPTURE-DESTRUCTURE-MISSING: the exceptional imperative-subscription "
    "Form-3 (it captures the frame once and tears down with frame-first "
    "`(rf/unsubscribe frame query-v)`) must destructure `subscribe` from the "
    "once-captured frame ops — `{:keys [frame subscribe]} (rf/capture-frame)` — and "
    "acquire its reaction through that captured local in `:component-did-mount`. "
    "(rf2-v84zn.)"
)


def _fenced_code_blocks(text: str) -> list[tuple[int, str]]:
    """`(start_lineno, block_text)` for each fenced code block's INTERIOR.

    `start_lineno` is the file line number (1-based) of the block's first
    interior line, so a match offset inside `block_text` maps back to a file
    line. Fence recognition matches `_lex_scan` / `_markdown_sections` (a run of
    ``` or ~~~), so an inner fence of the other delimiter cannot close a block."""
    blocks: list[tuple[int, str]] = []
    in_fence: str | None = None
    buf: list[str] = []
    buf_start = 0
    for lineno, line in enumerate(text.splitlines(), start=1):
        fence_m = FENCE_RE.match(line)
        if in_fence is not None:
            if fence_m and fence_m.group(1)[0] == in_fence:
                blocks.append((buf_start, "\n".join(buf)))
                in_fence = None
                buf = []
            else:
                buf.append(line)
        elif fence_m:
            in_fence = fence_m.group(1)[0]
            buf = []
            buf_start = lineno + 1  # first interior line
    return blocks


def _code_masked(block_text: str) -> str:
    """`block_text` with string / char-literal / `;` comment characters blanked to
    spaces (offsets preserved). A fenced block is wholly code, so `_clj_kind`
    classifies it directly; blanking non-code keeps a call-shaped token or a
    landmark word inside a string / comment from being read as real code
    (rf2-6m5qb shares the same lexical basis)."""
    kind = _clj_kind(block_text)
    return "".join(
        ch if kind[i] == _LEX_CODE else " " for i, ch in enumerate(block_text)
    )


def _block_line_at(block_text: str, offset: int, buf_start: int) -> tuple[int, str]:
    """`(file_lineno, stripped_source_line)` for `block_text[offset]`."""
    lineno = buf_start + block_text.count("\n", 0, offset)
    line_start = block_text.rfind("\n", 0, offset) + 1
    line_end = block_text.find("\n", offset)
    if line_end == -1:
        line_end = len(block_text)
    return lineno, block_text[line_start:line_end].strip()


def form3_captured_subscribe_problems(text: str) -> list[tuple[int, str, str]]:
    """Rule 5b — the exceptional imperative-subscription Form-3 must destructure
    `subscribe` from the once-captured frame ops AND acquire its reaction through
    that captured local in `:component-did-mount` (rf2-v84zn).

    Returns `(lineno, label, excerpt)`, matching `form3_context_problems` so
    `find_drift` formats it identically. The example is identified by three
    structural landmarks read over fenced Clojure code (strings / comments
    blanked): the outer-callable `(rf/capture-frame)`, the `:component-did-mount`
    hook, and the frame-first `(rf/unsubscribe …)` teardown that marks the
    long-lived-reaction branch. None of the three is the thing verified, so the
    check catches BOTH a dropped `subscribe` destructuring and an acquire swapped
    to the ambient `rf/subscribe`."""
    found: list[tuple[int, str, str]] = []
    for buf_start, block in _fenced_code_blocks(text):
        code = _code_masked(block)
        if not (
            FORM3_CAPTURE_FRAME_CALL_RE.search(code)
            and FORM3_DIDMOUNT_RE.search(code)
            and FORM3_UNSUBSCRIBE_CALL_RE.search(code)
        ):
            continue  # not the exceptional imperative-subscription Form-3
        # (1) matching destructuring — `subscribe` from the once-captured ops.
        if not FORM3_CAPTURE_SUBSCRIBE_DESTRUCTURE_RE.search(code):
            m = FORM3_CAPTURE_FRAME_CALL_RE.search(code)
            lineno, excerpt = _block_line_at(block, m.start(), buf_start)
            found.append((lineno, FORM3_MISSING_DESTRUCTURE_PROBLEM, excerpt))
            continue
        # (2) acquire through the captured local, never the ambient op.
        ambient = FORM3_AMBIENT_SUBSCRIBE_RE.search(code)
        if ambient:
            lineno, excerpt = _block_line_at(block, ambient.start(), buf_start)
            found.append((lineno, FORM3_AMBIENT_SUBSCRIBE_PROBLEM, excerpt))
        elif not FORM3_CAPTURED_SUBSCRIBE_RE.search(code):
            m = FORM3_CAPTURE_SUBSCRIBE_DESTRUCTURE_RE.search(code)
            lineno, excerpt = _block_line_at(block, m.start(), buf_start)
            found.append((lineno, FORM3_MISSING_ACQUIRE_PROBLEM, excerpt))
    return found


# ---------------------------------------------------------------------------
# Rule 5c — the exceptional imperative Form-3 must OWN its subscription
# (rf2-ynved; the defect shape is rf2-8cnxg).
#
# Rules 5 and 5b police WHERE the reaction comes from (the captured frame, the
# captured `subscribe`). Neither asks the question that actually decides whether
# the recipe works: does anything ACTIVATE the acquired reaction?
#
# Under the stock-Reagent adapter a subscription IS a bare `reagent.ratom/
# Reaction`, built deliberately without `:auto-run`, and a Reaction learns its
# sources only through `deref-capture`. A deref taken in `:component-did-mount`
# runs outside `*ratom-context*`, so it computes the body raw and leaves
# `watching` nil — the node is in nobody's watcher set and can never be told the
# value moved. An `add-watch` on it is therefore a TRAP, not an observer: the
# watch is registered, fires never, and the widget is fed once at mount and deaf
# for the rest of its life. That shape shipped in the copy-pasteable recipe and
# is what rf2-ynved repaired.
#
# The repair is a per-mount reactive OWNER — `(r/track! …)` — created in the
# same hook: its eager first run is both the seed and the `deref-capture`, and
# `r/dispose!` in `:component-will-unmount` stops it before the cache slot is
# released. So this rule requires, on the ONE canonical fenced example (same
# three landmarks Rule 5b keys on):
#
#   1. no `add-watch` — the trap must not come back;
#   2. a `track!` / `run!` owner;
#   3. a matching `dispose!` for that owner.
#
# Like Rule 5b this is deliberately NOT a Clojure parser: it scans fenced
# Clojure code with strings / `;` comments blanked, and it matches the ops as
# SYMBOLS rather than call heads, because the shipped teardown reaches
# `r/dispose!` through `(some-> @!driver r/dispose!)` — a threading macro, where
# the op is not in head position.
# ---------------------------------------------------------------------------

# The reactive owner: `r/track!` (or the `ratom/run!` spelling our own fixtures
# use). Matched as a symbol so a threading / higher-order use still counts.
FORM3_REACTIVE_OWNER_RE = re.compile(
    r"\b(?:r|reagent\.core|ratom|reagent\.ratom)/(?:track!|run!)(?![-\w])"
)
# The owner's teardown. `(some-> @!driver r/dispose!)` puts it out of head
# position, so this too is a symbol match.
FORM3_OWNER_DISPOSE_RE = re.compile(
    r"\b(?:r|reagent\.core|ratom|reagent\.ratom)/dispose!(?![-\w])"
)
# The trap: an `add-watch` standing in for an owner.
FORM3_ADD_WATCH_RE = re.compile(r"\(add-watch(?![-\w])")

FORM3_ADD_WATCH_PROBLEM = (
    "FORM3-IMPERATIVE-ADD-WATCH: the exceptional imperative-subscription Form-3 "
    "observes its acquired reaction with `add-watch`. Under the stock-Reagent "
    "adapter a subscription is a `reagent.ratom/Reaction` built without "
    "`:auto-run`, and a Reaction learns its sources only through `deref-capture`; "
    "a deref taken in a lifecycle hook runs the body raw and leaves `watching` "
    "nil, so the node is in no watcher set and the watch CANNOT fire — the widget "
    "is fed once at mount and deaf thereafter. Own the reaction with a per-mount "
    "`(r/track! (fn [] … @reaction))` in `:component-did-mount` instead; its eager "
    "first run is both the seed and the deref-capture. (rf2-ynved / rf2-8cnxg.)"
)
FORM3_OWNER_MISSING_PROBLEM = (
    "FORM3-REACTIVE-OWNER-MISSING: the exceptional imperative-subscription Form-3 "
    "acquires a long-lived reaction but gives it no reactive owner. A cached "
    "subscription with no live consumer is dormant on this adapter — it never "
    "re-runs, so nothing downstream of it ever moves. Create a per-mount "
    "`(r/track! (fn [] … @reaction))` in `:component-did-mount`. (rf2-ynved.)"
)
FORM3_OWNER_DISPOSE_MISSING_PROBLEM = (
    "FORM3-OWNER-DISPOSE-MISSING: the exceptional imperative-subscription Form-3 "
    "creates a per-mount reactive owner but never disposes it. "
    "`:component-will-unmount` must `r/dispose!` the tracker BEFORE "
    "`(rf/unsubscribe frame query-v)`, so the owner is gone before the cache slot "
    "is released and no feed runs against a destroyed widget. (rf2-ynved.)"
)


def form3_reactive_owner_problems(text: str) -> list[tuple[int, str, str]]:
    """Rule 5c — the exceptional imperative-subscription Form-3 must OWN its
    acquired reaction with a per-mount `r/track!`, dispose that owner at unmount,
    and never fall back to a bare `add-watch` (rf2-ynved).

    Returns `(lineno, label, excerpt)`, matching the sibling Form-3 rules so
    `find_drift` formats it identically. Scoped by the same three structural
    landmarks Rule 5b uses — the outer-callable `(rf/capture-frame)`, the
    `:component-did-mount` hook, and the frame-first `(rf/unsubscribe …)` teardown
    — so it cannot fire on the dispatch-only route-2 examples, the route-1
    outer/inner pattern, or explanatory prose."""
    found: list[tuple[int, str, str]] = []
    for buf_start, block in _fenced_code_blocks(text):
        code = _code_masked(block)
        if not (
            FORM3_CAPTURE_FRAME_CALL_RE.search(code)
            and FORM3_DIDMOUNT_RE.search(code)
            and FORM3_UNSUBSCRIBE_CALL_RE.search(code)
        ):
            continue  # not the exceptional imperative-subscription Form-3
        watch = FORM3_ADD_WATCH_RE.search(code)
        if watch:
            lineno, excerpt = _block_line_at(block, watch.start(), buf_start)
            found.append((lineno, FORM3_ADD_WATCH_PROBLEM, excerpt))
        owner = FORM3_REACTIVE_OWNER_RE.search(code)
        if not owner:
            m = FORM3_DIDMOUNT_RE.search(code)
            lineno, excerpt = _block_line_at(block, m.start(), buf_start)
            found.append((lineno, FORM3_OWNER_MISSING_PROBLEM, excerpt))
        elif not FORM3_OWNER_DISPOSE_RE.search(code):
            lineno, excerpt = _block_line_at(block, owner.start(), buf_start)
            found.append((lineno, FORM3_OWNER_DISPOSE_MISSING_PROBLEM, excerpt))
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
# This carries BOTH shapes. Like `m1_anchor_problems` it asserts a thing must be
# PRESENT (a future edit must not delete the invariant from one owner while the
# other still carries it); like the M-11/M-13 line rules it KILLS a stale claim —
# here the OPPOSITE polarity (capture-once auto-adapts to a provider change). A
# pure token census (the original rf2-aalo4n shape) let a reversal through as long
# as the positive vocabulary appeared *somewhere*, so rf2-gjrlz added the
# relationship + polarity teeth in `_retarget_invariance_problems`: the invariant
# must be stated in ONE paragraph (framing + A→B + stale-A consequence), and no
# capture-once paragraph may assert the reversal.
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

# --- Semantic teeth (rf2-gjrlz) --------------------------------------------
# The four presence checks above prove the *vocabulary* is somewhere in the
# document; they do NOT prove the tokens state the locked-handle RELATIONSHIP,
# and they accept the OPPOSITE polarity. So a document that scatters "provider A
# to B", "remount", and the pointer across unrelated fragments, then asserts the
# reversal ("capture-once automatically follows any provider change; it never
# goes stale"), passed as clean. These two regexes give the check teeth: the
# capture-once statement must state the stale-A CONSEQUENCE, and it must not
# assert the reversal.
#
# The stale-A RELATIONSHIP the capture-once statement must carry: the locked
# handle stays on the stale A and does NOT follow B. Markdown-bold tolerant
# (`stale **A**`, `never **B**`).
FORM3_STALE_RELATION_RE = re.compile(
    r"stale\s*\**A\b|to\s+(?:the\s+)?\**stale|go(?:es|ing)?\s+\**stale"
    r"|went\s+\**stale|stays?\s+on\s+\**A\b|stuck\s+on\s+\**A\b|sticks?\s+to\s+\**A\b"
    r"|keeps?\s+(?:sending|targeting|pointing|firing|resolving)"
    r"|does\s+not\s+follow|doesn'?t\s+follow|never\s+\**B\b|not\s+\**B\b",
    re.IGNORECASE,
)
# The semantic REVERSAL — the FALSE claim that capture-once auto-adapts to a
# provider change: it re-resolves/retargets/follows automatically, never goes
# stale, or needs no remount. Any of these inside a capture-once paragraph
# (outside an adaptive-remedy sentence, below) is flagged, EVEN when every
# positive vocabulary token still appears elsewhere in the document.
#
# The affirmative auto-adaptation forms are deliberately paired with an
# auto/dynamic qualifier or a to-B target, so the CORRECTED negations do NOT
# match: "never re-resolves", "not a live re-resolver", and "It goes stale" all
# stay clean (the qualifier "live" is intentionally excluded — the corpus uses
# "live re-resolver"/"live resolver" positively about the reg-view child).
FORM3_REVERSAL_RE = re.compile(
    # (a) auto-qualifier + adaptation verb
    r"(?:automatically|auto-?|always|dynamically)\s*"
    r"(?:re-?resolv\w*|re-?target\w*|retarget\w*|re-?point\w*|repoint\w*"
    r"|follow\w*|adapt\w*|track\w*|switch\w*|updat\w*)"
    # (b) adaptation verb + auto/dynamic or an explicit to-B / new-provider target
    r"|(?:re-?resolv\w*|re-?target\w*|retarget\w*|re-?point\w*|repoint\w*"
    r"|follow\w*|adapt\w*|track\w*|switch\w*)\s+"
    r"(?:automatically|dynamically|to\s+(?:provider\s+)?\**B\b"
    r"|to\s+the\s+(?:new|current|other)\b|any\s+provider|the\s+new\s+provider)"
    # (c) never-goes-stale / stays-fresh
    r"|never\s+(?:ever\s+)?(?:go(?:es|ing)?\s+)?\**stale|never\s+\**stale"
    r"|(?:does\s?n['o]?t|doesn'?t|do\s+not|don'?t|won'?t|will\s+not|cannot|can'?t"
    r"|no\s+longer)\s+(?:ever\s+)?go(?:es)?\s+\**stale|stays?\s+fresh|immune\s+to\s+stal"
    # (d) no-remount-needed
    r"|no\s+(?:need\s+(?:to|for)\s+(?:a\s+)?)?re-?mount(?:ing)?\b"
    r"|needs?\s+no\s+re-?mount|without\s+(?:a\s+|any\s+|ever\s+)?re-?mount(?:ing)?"
    r"|re-?mount(?:ing)?\s+is\s+(?:not\s+needed|unnecessary|never\s+needed)",
    re.IGNORECASE,
)
# Sentences that legitimately describe an ADAPTIVE remedy — the route-1 reg-view
# child (reads its frame from React context every render, so it follows A→B), or
# the frame-derived-`key` remount (a frame change remounts and the capture
# re-locks to B). A reversal-shaped phrase inside such a sentence is the
# corrected remedy prose, not a false claim, so these sentences are exempt from
# the reversal scan — e.g. "…so it follows A→B with no remount" (rf2-gjrlz).
FORM3_ADAPTIVE_REMEDY_RE = re.compile(
    r"reg-view`?\s+child|route\s*1|react\s+context|reads?\s+(?:its|the)\s+frame"
    r"|:context-?type|context-reading|on\s+(?:every|each)\s+render"
    r"|re-?locks?\s+to|force\s+a\s+remount|remounts?\s+the\s+component"
    r"|frame\s+change\s+(?:then\s+)?(?:changes|remounts)",
    re.IGNORECASE,
)


def _capture_once_paragraphs(text: str) -> list[str]:
    """Blank-line-delimited blocks that carry the capture-once / locked-handle
    framing. A minimal split — deliberately NOT a Markdown parser (rf2-gjrlz);
    it just bounds the capture-once statement to its own paragraph so scattered
    vocabulary elsewhere cannot satisfy the invariant, and so a reversal is read
    against its own subject."""
    return [p for p in re.split(r"\n[ \t]*\n", text) if FORM3_CAPTURE_ONCE_RE.search(p)]


def _reversal_sentences(paragraph: str) -> list[str]:
    """The reversal-asserting sentences of a capture-once paragraph, minus the
    ones describing an adaptive remedy (route 1 / key remount) — those carry a
    reversal-shaped phrase legitimately (rf2-gjrlz)."""
    hits: list[str] = []
    for sentence in FORM3_SENTENCE_BOUNDARY_RE.split(paragraph):
        if FORM3_REVERSAL_RE.search(sentence) and not FORM3_ADAPTIVE_REMEDY_RE.search(
            sentence
        ):
            hits.append(" ".join(sentence.split()))
    return hits


def _retarget_invariance_problems(text: str, owner: str, *, adopter: bool) -> list[str]:
    """Relationship + polarity teeth for one owner's capture-once statement
    (rf2-gjrlz).

    Beyond the vocabulary being present, the capture-once/locked-handle framing,
    the A→B retarget, and the stale-A consequence must sit in ONE paragraph
    (scattered tokens cannot satisfy it), and no capture-once paragraph may
    ASSERT the reversal (auto-retargets / never goes stale / no remount needed),
    even if every positive token still appears elsewhere. `adopter=True` (the
    FORM-3 owner) additionally requires an in-paragraph supported remedy and the
    canonical-recipe pointer; the canonical recipe is the pointer target, so it
    passes adopter=False."""
    problems: list[str] = []
    capture_paras = _capture_once_paragraphs(text)
    owning = [p for p in capture_paras if FORM3_RETARGET_RE.search(p)]

    # Polarity — no capture-once paragraph may assert the reversal.
    reversals = [s for p in capture_paras for s in _reversal_sentences(p)]
    if reversals:
        sample = reversals[0]
        problems.append(
            f"FORM3-POLARITY-REVERSED: {owner} asserts capture-once auto-adapts to "
            "a provider change (it re-resolves/retargets/follows automatically, "
            "never goes stale, or needs no remount) — the OPPOSITE of the "
            "locked-handle invariant. `(rf/capture-frame)` locks to the mount's "
            "frame and never re-resolves; a surviving A→B retarget keeps sending to "
            f'the stale A. Offending: "{sample[:140]}". State the invariant and its '
            "frame-safe remedy (a frame-derived React `key` remount, or the "
            "registered `reg-view` child) — not the (false) auto-adaptation "
            "(rf2-gjrlz)."
        )

    # Relationship — capture-once, the A→B retarget, and the stale-A consequence
    # must be tied together in one paragraph.
    if not capture_paras:
        problems.append(
            f"FORM3-CAPTURE-ONCE-MISSING: {owner} no longer frames the "
            "outer-callable `(rf/capture-frame)` as capture-once / a locked handle "
            "— the retarget invariance has no subject to attach to (rf2-aalo4n)."
        )
    elif not owning:
        problems.append(
            f"FORM3-RETARGET-MISSING: {owner} does not tie the capture-once "
            "statement to a *surviving* provider A→B retarget — the capture-once "
            "framing and the A→B case are in different paragraphs, or the A→B case "
            "is absent. State, in the capture-once paragraph, that a surviving "
            "instance retargeted from provider A to provider B keeps sending "
            "render/lifecycle actions to the stale A (the locked handle does not "
            "re-resolve; the outer callable does not re-run). (rf2-aalo4n; "
            "canonical: guided-handlers-state.md §M-11.)"
        )
    elif not any(FORM3_STALE_RELATION_RE.search(p) for p in owning):
        problems.append(
            f"FORM3-STALE-RELATION-MISSING: {owner} names the provider A→B retarget "
            "beside the capture-once framing but never states the CONSEQUENCE — the "
            "locked handle stays on the stale A and does not follow B. The A→B "
            "token alone does not carry the invariant (rf2-gjrlz)."
        )

    if adopter and owning:
        if not any(FORM3_REMEDY_RE.search(p) for p in owning):
            problems.append(
                f"FORM3-REMEDY-MISSING: {owner} states the provider A→B stale case "
                "but its owning paragraph points at no supported remedy — name the "
                "frame-derived React `key` remount or the registered `reg-view` "
                "child (route 1). Do NOT reach for a mutable / re-pointable capture "
                "(rf2-aalo4n)."
            )
        if not any(FORM3_RECIPE_POINTER_RE.search(p) for p in owning):
            problems.append(
                f"FORM3-RECIPE-POINTER-MISSING: {owner}'s capture-once paragraph "
                "does not point at the canonical migration recipe "
                "(guided-handlers-state.md §M-11) for the full retarget routes "
                "(rf2-aalo4n)."
            )
    return problems


def _form3_capture_once_problems(f3_text: str, g_text: str | None) -> list[str]:
    """Relationship + polarity check over the two owners' text (rf2-aalo4n +
    rf2-gjrlz).

    Kept text-pure (no disk read) so the self-test can exercise it against
    fixtures and live mutations, mirroring the M-1 classifier / live-corpus teeth.
    The adopter owner (`f3_text`) must tie capture-once → surviving A→B retarget →
    stays-on-stale-A in one paragraph, carry an in-paragraph remedy + the
    canonical-recipe pointer, and never assert the reversal. `g_text` is the
    canonical guided-handlers-state.md text, held to the same relationship +
    polarity teeth (minus the pointer, which is its own target); None skips the
    cross-owner leg (a SETUP problem is reported separately)."""
    problems = _retarget_invariance_problems(f3_text, "FORM-3.md", adopter=True)
    if g_text is not None:
        problems.extend(
            _retarget_invariance_problems(
                g_text, "guided-handlers-state.md", adopter=False
            )
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


# ---------------------------------------------------------------------------
# Rule 6 — M-0 publication-route lock (rf2-snjn5).
#
# The migration guide's M-0 (and the seven artefact rules that inherit it) is
# install documentation, and the event that flips its truth is OFF-REPO — an
# operator release decision — so no code change ever reds stale route prose
# naturally. It has now lied in both polarities: with nothing published, the
# guide taught `{:mvn/version "<latest>"}` ("look it up — Clojars / Maven
# Central"), a coordinate that fails resolution, and M-0's pre-publication
# branch ordered the author to leave the dep alone and apply no other
# migration rules until a release lands — a stop-ALL-work dead end that
# directly contradicts the skill this guide fronts for (references/setup.md:
# the migration is fully doable; a first release is NOT a precondition; never
# invent a version — the author supplies the pin/route). Three narrow
# assertions over MIGRATION_MD, the same publication-state drift class as the
# setup skill's Lock 9 (skills/re-frame2-setup/tests/setup_drift_test.clj):
#
#   * M0-LATEST-MAVEN — no `"<latest>"` version placeholder in a dependency
#     coordinate (the deps.edn `:mvn/version "<latest>"` map form and the Lein
#     `[... "<latest>"]` vector form both carry the quoted token). The one
#     labelled if/when-published `:mvn/version` sentence stays legal: it names
#     no version at all.
#   * M0-STOP-AND-WAIT — no leave-the-dep-alone / hold-everything-for-a-release
#     instruction. Narrow by design: an honestly-labelled per-build-tool option
#     (the Leiningen paragraph pausing ITS OWN dep edit pending publication)
#     carries neither shape and stays legal.
#   * M0-RECIPE-DELEGATION — the M-0 SECTION (its rule heading up to the next
#     `### M-N.` rule heading) links the canonical coordinate recipe
#     (re-frame2-setup references/deps-versions.md §Choosing the coordinate),
#     so ONE publication-state decision governs the whole guide instead of
#     eight independently drifting copies. Scoped to the M-0 section because
#     the seven artefact rules M-27..M-33 carry the same anchor by design: a
#     whole-file substring check stayed green with only M-0's occurrence
#     removed — the deciding rule silently un-delegated while its inheritors
#     still pointed at the recipe (the rf2-snjn5 merged-PR audit's acceptance
#     seam). The `<latest>` and stop-and-wait assertions stay whole-file: those
#     shapes are illegal anywhere in the guide.
#
# Retire this lock deliberately at a real first publish — it pins the
# pre-publish polarity.
# ---------------------------------------------------------------------------

M0_LATEST_PLACEHOLDER_RE = re.compile(r'"<latest>"')
M0_STOP_AND_WAIT_RE = re.compile(
    r"leave\s+the\s+dep(?:endency)?\s+alone"
    r"|do\s+not\s+apply\s+any\s+other\s+migration\s+rules"
    r"|(?:stop|wait)[^.\n]{0,80}?(?:until|once)\s+a\s+release\s+lands",
    re.IGNORECASE,
)
# A prefix of the canonical anchor, so a future anchor shortening
# (…#choosing-the-coordinate) still satisfies it while a dropped delegation
# does not.
M0_DELEGATION_ANCHOR = "deps-versions.md#choosing-the-coordinate"
# The M-0 section: the `### M-0.` rule heading up to the next `### M-N.` rule
# heading (M-1 today). Bounded by rule headings — not by "any heading" — so a
# future subsection inside M-0 cannot truncate the span, and matched at any
# ATX level so a heading-depth reshuffle does not blind the lock. A rule
# heading is the COMPLETE `M-N.` id — whitespace or end-of-line must follow
# the terminal dot — so a numbered subsection (`#### M-0.1 …`, whose dot is a
# decimal point, not a terminator) neither ends the span early nor stands in
# for a missing parent heading (the rf2-snjn5 #7296 audit's two polarities).
M0_RULE_HEADING_RE = re.compile(r"^ {0,3}#{1,6} +M-0\.(?=[ \t]|$)", re.MULTILINE)
M_RULE_HEADING_RE = re.compile(r"^ {0,3}#{1,6} +M-\d+\.(?=[ \t]|$)", re.MULTILINE)


def _m0_section_span(text: str) -> tuple[int, int] | None:
    """`(start, end)` offsets of the M-0 section, or None when `text` carries
    no M-0 rule heading (the delegation assertion then has nothing to scope to
    and must report)."""
    start_m = M0_RULE_HEADING_RE.search(text)
    if start_m is None:
        return None
    end_m = M_RULE_HEADING_RE.search(text, start_m.end())
    return (start_m.start(), end_m.start() if end_m else len(text))

M0_LATEST_PROBLEM = (
    "M0-LATEST-MAVEN: the migration guide carries a `\"<latest>\"` version "
    "placeholder in a dependency coordinate. Nothing is published, so every "
    "such coordinate fails resolution — and \"latest\" is never a pin the "
    "guide may invent (the author supplies the pin). Teach the "
    "author-supplied route chosen at M-0 and delegate the recipe to "
    "deps-versions.md §Choosing the coordinate. (rf2-snjn5.)"
)
M0_STOP_PROBLEM = (
    "M0-STOP-AND-WAIT: the migration guide orders the author to leave the dep "
    "alone / hold the migration for a release. A first release is NOT a "
    "precondition — the author chooses a consumption route (pinned `:git/sha`; "
    "`:local/root` for local dev only), records it in the migration report, "
    "and the migration CONTINUES (skills/re-frame-migration/references/"
    "setup.md §Discovering the current VERSION). (rf2-snjn5.)"
)
M0_DELEGATION_PROBLEM = (
    "M0-RECIPE-DELEGATION-MISSING: the M-0 section no longer links the "
    "canonical coordinate recipe (re-frame2-setup references/deps-versions.md "
    "§Choosing the coordinate). The route decision is made ONCE, at M-0, and "
    "every later artefact rule inherits it — the M-27..M-33 copies delegating "
    "is not enough when the deciding rule itself does not; restated per-rule "
    "recipes are how eight copies drifted independently. (rf2-snjn5.)"
)


def _m0_publication_route_problems(text: str) -> list[tuple[int, str, str]]:
    """Rule-6 drift in the migration guide's text. `(lineno, label, excerpt)`;
    the section-scoped delegation check reports lineno 0 with an empty
    excerpt. Text-pure so the self-test can exercise it against fixtures."""
    problems: list[tuple[int, str, str]] = []
    for lineno, line in enumerate(text.splitlines(), start=1):
        if M0_LATEST_PLACEHOLDER_RE.search(line):
            problems.append((lineno, M0_LATEST_PROBLEM, line.strip()))
        if M0_STOP_AND_WAIT_RE.search(line):
            problems.append((lineno, M0_STOP_PROBLEM, line.strip()))
    span = _m0_section_span(text)
    if span is None or M0_DELEGATION_ANCHOR not in text[span[0]:span[1]]:
        problems.append((0, M0_DELEGATION_PROBLEM, ""))
    return problems


def m0_publication_route_problems() -> list[str]:
    """Run the Rule-6 M-0 publication-route lock over MIGRATION_MD only —
    the corrected setup.md wording legitimately QUOTES the banned instruction
    in negated form ("Do not leave the dep alone"), so the skill leaves are
    deliberately out of this rule's scan surface."""
    if not MIGRATION_MD.is_file():
        return [
            f"SETUP: migration corpus missing: {MIGRATION_MD.relative_to(REPO_ROOT)}"
            " — the M-0 publication-route lock cannot run."
        ]
    rel = MIGRATION_MD.relative_to(REPO_ROOT)
    out: list[str] = []
    for lineno, label, excerpt in _m0_publication_route_problems(_slurp(MIGRATION_MD)):
        if lineno:
            out.append(f"{rel}:{lineno}: {label}\n    {excerpt}")
        else:
            out.append(f"{rel}: {label}")
    return out


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
        for lineno, label, excerpt in form3_captured_subscribe_problems(text):
            rel = path.relative_to(REPO_ROOT)
            problems.append(f"{rel}:{lineno}: {label}\n    {excerpt}")
        for lineno, label, excerpt in form3_reactive_owner_problems(text):
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
    problems.extend(m0_publication_route_problems())

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
                "(Rule 5), Form-3 captured-subscribe acquisition (Rule 5b — "
                "rf2-v84zn), Form-3 reactive-owner ownership (Rule 5c — "
                "rf2-ynved), Form-3 capture-once retarget-invariance drift "
                "(rf2-aalo4n), M-0 publication-route drift (Rule 6 — "
                "rf2-snjn5), or M-1 classifier / kickoff-anchor drift found."
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
        "Form-3 lifecycle explicit-frame targeting, the exceptional imperative "
        "Form-3's per-mount `r/track!` OWNER (a bare `add-watch` on a ratom-family "
        "subscription can never fire — rf2-ynved), the Form-3 capture-once "
        "retarget invariance (FORM-3.md + guided-handlers-state.md §M-11 aligned "
        "— rf2-aalo4n), and the M-0 publication-route lock (author-supplied "
        "pinned route, no `\"<latest>\"`, no stop-and-wait — rf2-snjn5), to the "
        "shipped contract."
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


# The captured acquire the exceptional Form-3 actually ships, and the ambient
# regression the bead reproduced (rf2-v84zn). Kept as anchors so the live teeth
# report STALE rather than going vacuous if the recipe is re-authored.
LIVE_CAPTURED_ACQUIRE = "(let [reaction (subscribe query-v)]"
LIVE_AMBIENT_ACQUIRE = "(let [reaction (rf/subscribe query-v)]"


def _live_captured_subscribe_problems() -> list[str]:
    """Run Rule 5b against a MUTATION OF THE SHIPPED guided-handlers-state.md, not
    hand-written prose (rf2-v84zn). The shipped exceptional Form-3 must be clean,
    and swapping its captured `(subscribe query-v)` acquire for the ambient
    `(rf/subscribe query-v)` — the exact false-green the bead reproduced — must
    trip the guard. If the recipe is re-authored so the anchor no longer matches,
    this reports STALE instead of quietly proving nothing."""
    if not GUIDED_HANDLERS_MD.is_file():
        return [
            f"SETUP: {GUIDED_HANDLERS_MD.name} missing — the captured-subscribe "
            "live teeth cannot run."
        ]
    text = _slurp(GUIDED_HANDLERS_MD)
    if form3_captured_subscribe_problems(text):
        return [
            "LIVE-CAPTURED-SUBSCRIBE-DIRTY: the shipped guided-handlers-state.md "
            "exceptional Form-3 already trips Rule 5b, so the mutation tooth below "
            "cannot prove the guard has bite. Fix the guidance (or the rule) first."
        ]
    if LIVE_CAPTURED_ACQUIRE not in text:
        return [
            "LIVE-CAPTURED-SUBSCRIBE-STALE: guided-handlers-state.md no longer "
            f"contains the captured acquire `{LIVE_CAPTURED_ACQUIRE}`, so the Rule "
            "5b mutation tooth is vacuous — the exceptional Form-3 recipe was "
            "re-authored. Re-point LIVE_CAPTURED_ACQUIRE at the current recipe."
        ]
    mutated = text.replace(LIVE_CAPTURED_ACQUIRE, LIVE_AMBIENT_ACQUIRE)
    if not form3_captured_subscribe_problems(mutated):
        return [
            "LIVE-CAPTURED-SUBSCRIBE-UNDETECTED: swapping the LIVE captured acquire "
            "for the ambient `rf/subscribe` did not trip Rule 5b — the guard is "
            "blind to the false-green the bead reproduced (rf2-v84zn)."
        ]
    return []


# Rule 5c live teeth (rf2-ynved). Each entry breaks the SHIPPED recipe's
# ownership in one of the three ways the repair rules out: delete the owner,
# swap it back for the `add-watch` trap, or leave the owner undisposed. Anchors
# are kept short so a re-authored recipe reports STALE rather than going vacuous.
LIVE_TRACK_OWNER = "(r/track!"
LIVE_OWNER_DISPOSE = "r/dispose!"
LIVE_OWNER_MUTATIONS = (
    (
        "the per-mount reactive owner is deleted",
        LIVE_TRACK_OWNER,
        "(identity",
    ),
    (
        "the owner is swapped back for the `add-watch` trap",
        LIVE_TRACK_OWNER,
        "(add-watch reaction ::feed",
    ),
    (
        "the owner is never disposed at unmount",
        LIVE_OWNER_DISPOSE,
        "identity",
    ),
)


def _live_reactive_owner_problems() -> list[str]:
    """Run Rule 5c against MUTATIONS OF THE SHIPPED guided-handlers-state.md
    (rf2-ynved). The shipped exceptional Form-3 must own its acquired reaction
    with a per-mount `r/track!` and dispose it at unmount; deleting that owner,
    restoring the `add-watch` trap, or dropping the dispose must each trip the
    guard against the LIVE text. A re-authored recipe reports STALE instead of
    quietly proving nothing."""
    if not GUIDED_HANDLERS_MD.is_file():
        return [
            f"SETUP: {GUIDED_HANDLERS_MD.name} missing — the reactive-owner live "
            "teeth cannot run."
        ]
    text = _slurp(GUIDED_HANDLERS_MD)
    if form3_reactive_owner_problems(text):
        return [
            "LIVE-REACTIVE-OWNER-DIRTY: the shipped guided-handlers-state.md "
            "exceptional Form-3 already trips Rule 5c, so the mutation teeth below "
            "cannot prove the guard has bite. Fix the guidance (or the rule) first."
        ]
    problems: list[str] = []
    for label, present, broken in LIVE_OWNER_MUTATIONS:
        if present not in text:
            problems.append(
                f"LIVE-REACTIVE-OWNER-STALE: guided-handlers-state.md no longer "
                f"contains `{present}`, so the Rule 5c tooth for '{label}' is "
                f"vacuous — the exceptional Form-3 recipe was re-authored. "
                f"Re-point LIVE_OWNER_MUTATIONS at the current recipe."
            )
            continue
        if not form3_reactive_owner_problems(text.replace(present, broken)):
            problems.append(
                f"LIVE-REACTIVE-OWNER-UNDETECTED: mutating the LIVE recipe so "
                f"{label} did not trip Rule 5c. The guard is blind to the shape "
                f"that shipped the rf2-8cnxg defect to users (rf2-ynved)."
            )
    return problems


def _live_m0_delegation_problems() -> list[str]:
    """Run Rule 6's delegation assertion against a MUTATION OF THE SHIPPED
    migration guide, not hand-written fixtures (rf2-snjn5). The landed guide
    carries the delegation anchor eight times — once in M-0 and once in each
    artefact rule M-27..M-33 — which made a whole-file substring check the
    audit's acceptance seam: removing only M-0's occurrence left seven copies
    and a green gate. This tooth removes exactly that one occurrence and
    requires M0-RECIPE-DELEGATION to red. If the guide is re-authored so the
    anchors move, it reports STALE rather than quietly proving nothing."""
    if not MIGRATION_MD.is_file():
        return [
            f"SETUP: {MIGRATION_MD.name} missing — the M-0 delegation mutation "
            "tooth cannot run."
        ]
    text = _slurp(MIGRATION_MD)
    if _m0_publication_route_problems(text):
        return [
            "LIVE-M0-DIRTY: the shipped migration guide already trips Rule 6, "
            "so the M-0 delegation mutation tooth cannot prove the guard has "
            "bite. Fix the guide (or the rule) first."
        ]
    span = _m0_section_span(text)  # non-None: the guide just scanned clean
    start, end = span if span else (0, 0)
    mutated = (
        text[:start]
        + text[start:end].replace(M0_DELEGATION_ANCHOR, "deps-versions.md")
        + text[end:]
    )
    if M0_DELEGATION_ANCHOR not in mutated:
        return [
            "LIVE-M0-STALE: removing the M-0 section's delegation anchor left "
            "no copy anywhere else in the guide — the M-27..M-33 artefact-rule "
            "copies were re-authored, so this tooth no longer proves the "
            "section scoping (a whole-file check would red here too). Re-point "
            "the tooth at the current guide."
        ]
    got = _m0_publication_route_problems(mutated)
    if not any(label == M0_DELEGATION_PROBLEM for _, label, _ in got):
        return [
            "LIVE-M0-UNDETECTED: removing ONLY the M-0 section's delegation "
            "anchor (the seven M-27..M-33 copies intact) did not trip "
            "M0-RECIPE-DELEGATION — the acceptance seam the rf2-snjn5 "
            "merged-PR audit reproduced is open."
        ]
    return []


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

    def expect_captured(text: str, *, dirty: bool, label: str) -> None:
        nonlocal failures
        got = bool(form3_captured_subscribe_problems(text))
        if got != dirty:
            print(
                f"SELF-TEST FAIL ({label}): expected dirty={dirty}, got "
                f"{got} for multiline text: {text!r}"
            )
            failures += 1

    def expect_owner(text: str, *, dirty: bool, label: str) -> None:
        nonlocal failures
        got = bool(form3_reactive_owner_problems(text))
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

    # === rf2-6m5qb: lexical / polarity / Markdown-boundary gaps ================
    # Each pair is the EXACT pre-fix repro: a construct that previously BYPASSED
    # the guard now trips it (dirty=True), and a construct that previously
    # FALSE-FIRED now passes (dirty=False).

    # --- Gap 1: Clojure lexical context (LEX) ----------------------------------
    # LEX-1: a bare multiline call whose sole argument is followed by a `;`
    # comment. Pre-fix `_read_form` counted the comment words as arguments
    # (arity ~6), so the call slipped past the bare threshold. It is arity 1.
    expect_text(
        "**Form-3 lifecycle.** A hook has no ambient frame.\n\n"
        "```clojure\n:component-did-mount\n(fn [_]\n"
        "  (rf/subscribe-once\n"
        "    query-v  ; the live query vector to read once\n"
        "    ))\n```",
        dirty=True, label="LEX-1 bare call + trailing comment stays arity-1 (was bypass)",
    )
    # LEX-2: a comment BEFORE the sole argument must also not inflate arity.
    expect_text(
        "**Form-3 lifecycle.** A hook has no ambient frame.\n\n"
        "```clojure\n:component-did-mount\n(fn [_]\n"
        "  (rf/subscribe-once  ; read the current value once\n"
        "    query-v))\n```",
        dirty=True, label="LEX-2 comment before the sole argument stays arity-1",
    )
    # LEX-3: a `;`-comment or stray brackets inside the form must not corrupt the
    # bracket depth — the frame-qualified call is arity 2 and legal.
    expect_text(
        "**Form-3 lifecycle.** A hook has no ambient frame.\n\n"
        "```clojure\n:component-did-mount\n(fn [_]\n"
        "  (rf/subscribe-once\n"
        "    query-v  ; note: a stray ) ] } in a comment is inert\n"
        "    {:frame frame}))\n```",
        dirty=False, label="LEX-3 comment brackets do not corrupt depth; arity-2 clean",
    )
    # LEX-4: a call-shaped token inside a STRING is not a call. Pre-fix
    # `_form3_call_sites` matched the head inside the log string and flagged it;
    # the real call is frame-qualified, so the block is wholly clean.
    expect_text(
        "**Form-3 lifecycle.** Log the query for debugging.\n\n"
        "```clojure\n:component-did-mount\n(fn [_]\n"
        '  (log/debug "calling (rf/subscribe-once query-v) now")\n'
        "  (rf/subscribe-once query-v {:frame frame}))\n```",
        dirty=False, label="LEX-4 call-shaped text inside a string is clean (was false-fire)",
    )
    # LEX-5: a call-shaped token inside a `;` comment is not a call either.
    expect_text(
        "**Form-3 lifecycle.** A hook has no ambient frame.\n\n"
        "```clojure\n:component-did-mount\n(fn [_]\n"
        "  ;; avoid the bare (rf/subscribe-once query-v) here\n"
        "  (rf/subscribe-once query-v {:frame frame}))\n```",
        dirty=False, label="LEX-5 call-shaped text inside a comment is clean",
    )
    # LEX-6: a prose semicolon must not hide a following bare call — the Clojure
    # lexis is scoped to code contexts, not English punctuation.
    expect_text(
        "**Form-3 lifecycle.** A hook has no ambient frame; so read the current "
        "value with `(rf/subscribe-once query-v)` at mount.",
        dirty=True, label="LEX-6 prose ';' does not mask a following inline-code call",
    )
    # LEX-7/8: a character literal is one opaque argument, and a bracket-shaped
    # char (`\\]`) must not be read as a closing paren that miscounts arity.
    expect_text(
        "**Form-3 lifecycle.** A hook has no ambient frame.\n\n"
        "```clojure\n(rf/subscribe-once \\])\n```",
        dirty=True, label="LEX-7 bracket-shaped char literal is one arg (bare)",
    )
    expect_text(
        "**Form-3 lifecycle.** A hook has no ambient frame.\n\n"
        "```clojure\n(rf/subscribe-once \\x {:frame frame})\n```",
        dirty=False, label="LEX-8 char literal + frame opts is arity-2 clean",
    )

    # --- Gap 2: example polarity (POL) -----------------------------------------
    # POL-1: a historical BEFORE example must not bless a later UNLABELLED
    # affirmative recipe. Pre-fix the exemption carried across every block until
    # a positive marker, so the second recipe slipped through.
    expect_text(
        "**Form-3 lifecycle — BEFORE.** The old v1 shape.\n\n"
        "`(rf/subscribe-once old-query)`\n\n"
        "Seed the chart at mount with `(rf/subscribe-once query-v)`.",
        dirty=True, label="POL-1 carried BEFORE cannot bless a later recipe (was bypass)",
    )
    # POL-2: the BEFORE still owns the ONE concrete example it introduces — that
    # example stays exempt (the fix scopes, it does not disable, the exemption).
    expect_text(
        "**Form-3 lifecycle — BEFORE.** The old v1 shape.\n\n"
        "`(rf/subscribe-once old-query)`",
        dirty=False, label="POL-2 BEFORE still exempts the example it owns",
    )
    # POL-3: a `BEFORE` inside a code STRING is not a polarity label, so it cannot
    # exempt a following unsafe call. Pre-fix the string `"BEFORE"` forged one.
    expect_text(
        "**Form-3 lifecycle.** A hook has no ambient frame.\n\n"
        "```clojure\n:component-did-mount\n(fn [_]\n"
        '  (log/debug "BEFORE")\n'
        "  (rf/subscribe-once query-v))\n```",
        dirty=True, label="POL-3 string 'BEFORE' does not forge an exemption (was bypass)",
    )
    # POL-4: `;; BEFORE` / `;; AFTER` COMMENT labels are still honoured per call —
    # the BEFORE example is exempt, the AFTER recipe is not.
    expect_text(
        "**Form-3 lifecycle.** Capture the frame in the outer callable.\n\n"
        "```clojure\n"
        ";; BEFORE (v1) — no frame to name\n"
        "(rf/subscribe-once old-query)\n"
        ";; AFTER (v2) — the recipe to copy\n"
        "(rf/subscribe-once query-v)\n"
        "```",
        dirty=True, label="POL-4 comment BEFORE/AFTER labels still work per call",
    )
    # POL-5: ordinary negative prose ("Not recommended: …") is a negative label,
    # so it exempts the anti-pattern it names. Pre-fix the `recommended` substring
    # read as a POSITIVE marker and falsely flagged the call.
    expect_text(
        "**Form-3 lifecycle.** Not recommended: seed with "
        "`(rf/subscribe-once query-v)`.",
        dirty=False, label="POL-5 'Not recommended:' is negative prose (was false-fire)",
    )
    # POL-6: a plain `recommended` recipe is still POSITIVE — a bare call it
    # presents is flagged.
    expect_text(
        "**Form-3 lifecycle.** The recommended shape is "
        "`(rf/subscribe-once query-v)` at mount.",
        dirty=True, label="POL-6 plain 'recommended' recipe is still positive/dirty",
    )

    # --- Gap 3: Markdown structural boundaries (MD) ----------------------------
    # MD-1: a Setext H2 heading (`text` underlined by `---`) bounds lifecycle
    # context like an ATX `##`. Pre-fix the `---` read as a thematic break that
    # split the heading text away from its section, so the bare call went unseen.
    expect_text(
        "Form-3 lifecycle\n---\n\n"
        "A hook has no ambient frame; seed with `(rf/subscribe-once query-v)`.",
        dirty=True, label="MD-1 Setext-H2 lifecycle section flags a bare call (was bypass)",
    )
    # MD-2: a Setext H1 heading (`text` underlined by `===`) ENDS the prior
    # lifecycle context, so a later ambient call is legal. Pre-fix `===` was
    # unrecognised and the lifecycle context leaked into the ambient section.
    expect_text(
        "## Form-3 lifecycle\n\n"
        "Capture the frame in the outer callable.\n\n"
        "Ambient reads in a registered view\n"
        "==================================\n\n"
        "In a registered view `(rf/subscribe-once query-v)` reads the "
        "provider frame.",
        dirty=False, label="MD-2 Setext-H1 ends lifecycle context (was false-fire)",
    )
    # MD-3: the Setext heading itself establishes context for its own section, so
    # a bare call beneath a Setext-headed lifecycle section is still dirty.
    expect_text(
        "Form-3 lifecycle\n"
        "================\n\n"
        "A hook has no ambient frame. Seed with `(rf/subscribe-once query-v)`.",
        dirty=True, label="MD-3 Setext-H1 heading establishes context for its section",
    )
    # MD-4: a CommonMark SPACED thematic break (`* * *`) resets bounded context
    # like a compact `---`. Pre-fix only the compact forms matched.
    expect_text(
        "## Form-3 lifecycle\n\n"
        "Capture the frame in the outer callable.\n\n"
        "* * *\n\n"
        "In an ordinary registered view, `(rf/subscribe-once [:todos/all])` "
        "reads the provider frame.",
        dirty=False, label="MD-4 spaced thematic break '* * *' resets context (was false-fire)",
    )
    # MD-5: a spaced `- - -` thematic break resets context too.
    expect_text(
        "## Form-3 lifecycle\n\n"
        "Capture the frame in the outer callable.\n\n"
        "- - -\n\n"
        "In an ordinary registered view, `(rf/subscribe-once [:todos/all])` "
        "reads the provider frame.",
        dirty=False, label="MD-5 spaced thematic break '- - -' resets context",
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

    # --- Rule 5b fixtures — captured-subscribe acquisition (rf2-v84zn) ----------
    # A structurally faithful copy of the ONE canonical exceptional Form-3: the
    # frame captured once (destructuring `subscribe`), the mount-hook acquire
    # through that captured local, and the frame-first teardown. Mutations derive
    # from it via `.replace`, mirroring the K-cases.
    CANON = (
        "**The exceptional imperative-subscription Form-3.**\n\n"
        "```clojure\n"
        "(re-frame.core/reg-view* ::live-gauge\n"
        "  (fn [gauge-id]\n"
        "    (let [{:keys [frame subscribe]} (rf/capture-frame)  ; captured ONCE\n"
        "          query-v [:gauge/reading gauge-id]\n"
        "          !driver (r/atom nil)]\n"
        "      (reagent.core/create-class\n"
        "        {:reagent-render (fn [_] [:div.gauge])\n"
        "         :component-did-mount\n"
        "         (fn [this]\n"
        "           (let [reaction (subscribe query-v)]           ; ACQUIRE\n"
        "             (reset! !driver                             ; OWN\n"
        "               (r/track! (fn [] (feed-gauge! @reaction))))))\n"
        "         :component-will-unmount\n"
        "         (fn [_]\n"
        "           (some-> @!driver r/dispose!)\n"
        "           (rf/unsubscribe frame query-v))}))))\n"  # RELEASE — frame-first
        "```"
    )
    # The mount hook as CANON ships it — the anchor mutations replace.
    CANON_MOUNT = (
        "         (fn [this]\n"
        "           (let [reaction (subscribe query-v)]           ; ACQUIRE\n"
        "             (reset! !driver                             ; OWN\n"
        "               (r/track! (fn [] (feed-gauge! @reaction))))))\n"
    )
    expect_captured(CANON, dirty=False, label="VC1 canonical captured acquire is clean")
    # VC2 — the exact bead repro: the captured acquire swapped for ambient.
    expect_captured(
        CANON.replace("(let [reaction (subscribe query-v)]",
                      "(let [reaction (rf/subscribe query-v)]"),
        dirty=True, label="VC2 ambient rf/subscribe acquire is flagged (was false-green)",
    )
    # VC3 — matching destructuring dropped (subscribe no longer bound from capture).
    expect_captured(
        CANON.replace("{:keys [frame subscribe]}", "{:keys [frame]}"),
        dirty=True, label="VC3 dropped subscribe destructuring is flagged",
    )
    # VC4 — destructures subscribe but never acquires through it in did-mount.
    expect_captured(
        CANON.replace(
            CANON_MOUNT,
            "         (fn [this]\n"
            "           (reset! !widget (mk-gauge! this)))\n",
        ),
        dirty=True, label="VC4 destructured subscribe never used to acquire is flagged",
    )
    # VC5 — the fully-namespaced ambient acquire is caught too.
    expect_captured(
        CANON.replace("(let [reaction (subscribe query-v)]",
                      "(let [reaction (re-frame.core/subscribe query-v)]"),
        dirty=True, label="VC5 re-frame.core/subscribe ambient acquire is flagged",
    )
    # VC6 — out of scope: the dispatch-only route-2 example destructures only
    # `dispatch` and never `unsubscribe`, so it is not the exceptional Form-3.
    expect_captured(
        "**Route 2 — capture the frame in the outer callable.**\n\n"
        "```clojure\n"
        "(re-frame.core/reg-view* ::chart\n"
        "  (fn [series]\n"
        "    (let [{:keys [dispatch]} (rf/capture-frame)\n"
        "          !inst (r/atom nil)]\n"
        "      (reagent.core/create-class\n"
        "        {:reagent-render (fn [series] [:div.chart])\n"
        "         :component-did-mount    (fn [this] (dispatch [:chart/mounted]))\n"
        "         :component-will-unmount (fn [_] (dispatch [:chart/unmounted]))}))))\n"
        "```",
        dirty=False, label="VC6 dispatch-only route-2 example is not flagged",
    )
    # VC7 — out of scope: the M-68 `capture-frame` rename recipe destructures
    # subscribe but has no lifecycle hook and no teardown.
    expect_captured(
        "```clojure\n"
        ";; after\n"
        "(let [{:keys [dispatch subscribe frame]} (rf/capture-frame)\n"
        "      db (rf/app-db-value frame)]\n"
        "  ...)\n"
        "```",
        dirty=False, label="VC7 capture-frame rename recipe (no lifecycle) is not flagged",
    )
    # VC8 — out of scope: prose carrying every landmark in inline code, but no
    # fenced block to scan.
    expect_captured(
        "When a hook must read a sub, acquire it in `:component-did-mount` through "
        "the captured `subscribe`, capture once via `(rf/capture-frame)`, pair with "
        "`(rf/unsubscribe frame query-v)`, and never a bare `(rf/subscribe query-v)`.",
        dirty=False, label="VC8 explanatory prose (no fence) is not flagged",
    )
    # VC9 — Rule 5 territory: a bare subscribe-once lifecycle block with no capture
    # and no unsubscribe CALL is not the exceptional Form-3 (Rule 5 owns it).
    expect_captured(
        "**Form-3 lifecycle.**\n\n"
        "```clojure\n"
        ":component-did-mount\n"
        "(fn [_] (rf/subscribe-once query-v {:frame frame}))\n"
        "```",
        dirty=False, label="VC9 Rule-5 subscribe-once block is not a Rule-5b target",
    )
    # VC10 — lexical: an ambient acquire spelled inside a `;` comment must NOT flag
    # (the real acquire is the captured local). Proves strings/comments are blanked.
    expect_captured(
        CANON.replace(
            "           (let [reaction (subscribe query-v)]           ; ACQUIRE\n",
            "           ;; never write (rf/subscribe query-v) in a hook\n"
            "           (let [reaction (subscribe query-v)]\n",
        ),
        dirty=False, label="VC10 ambient token inside a comment does not flag",
    )

    # Rule 5b live-corpus teeth: the shipped exceptional Form-3 must be clean, and
    # the bead's exact acquire swap must trip the guard against the LIVE recipe.
    for problem in _live_captured_subscribe_problems():
        print(f"SELF-TEST FAIL (captured-subscribe live): {problem}")
        failures += 1

    # --- Rule 5c fixtures — reactive ownership (rf2-ynved) ----------------------
    # The same CANON, now read for the question that decides whether the recipe
    # WORKS: does anything activate the reaction it acquires?
    expect_owner(CANON, dirty=False, label="VO1 canonical track!-owner recipe is clean")
    # VO2 — the shipped pre-fix shape: seed deref + add-watch, no owner. This is
    # the recipe as it stood before rf2-ynved; it must not be able to come back.
    PRE_FIX_MOUNT = (
        "         (fn [this]\n"
        "           (let [reaction (subscribe query-v)]           ; ACQUIRE\n"
        "             (feed-gauge! @reaction)\n"
        "             (add-watch reaction watch-key\n"
        "               (fn [_ _ _ v] (feed-gauge! v)))))\n"
    )
    PRE_FIX = CANON.replace(CANON_MOUNT, PRE_FIX_MOUNT).replace(
        "           (some-> @!driver r/dispose!)\n",
        "           (remove-watch @!reaction watch-key)\n",
    )
    expect_owner(
        PRE_FIX,
        dirty=True,
        label="VO2 the pre-fix seed-deref + add-watch recipe is flagged (rf2-ynved)",
    )
    # VO3 — the trap alone: an add-watch bolted onto the owned recipe.
    expect_owner(
        CANON.replace(
            "             (reset! !driver                             ; OWN\n",
            "             (add-watch reaction ::feed (fn [_ _ _ v] (feed-gauge! v)))\n"
            "             (reset! !driver                             ; OWN\n",
        ),
        dirty=True, label="VO3 add-watch beside the owner is still flagged",
    )
    # VO4 — owner dropped, everything else intact: the acquired reaction is
    # dormant, so the widget is fed once and never again.
    expect_owner(
        CANON.replace(
            "             (reset! !driver                             ; OWN\n"
            "               (r/track! (fn [] (feed-gauge! @reaction))))))\n",
            "             (feed-gauge! @reaction)))\n",
        ),
        dirty=True, label="VO4 acquire with no reactive owner is flagged",
    )
    # VO5 — owner created but never disposed: the tracker outlives the mount and
    # keeps feeding a destroyed widget.
    expect_owner(
        CANON.replace("           (some-> @!driver r/dispose!)\n", ""),
        dirty=True, label="VO5 undisposed owner is flagged",
    )
    # VO6 — the `ratom/run!` spelling our own fixtures use is an owner too.
    expect_owner(
        CANON.replace("(r/track! (fn [] (feed-gauge! @reaction))))))",
                      "(ratom/run! (feed-gauge! @reaction)))))")
             .replace("(some-> @!driver r/dispose!)",
                      "(ratom/dispose! @!driver)"),
        dirty=False, label="VO6 ratom/run! + ratom/dispose! spelling is clean",
    )
    # VO7 — out of scope: the dispatch-only route-2 example holds no subscription,
    # so it needs no owner (it is VC6, re-read by Rule 5c).
    expect_owner(
        "**Route 2 — capture the frame in the outer callable.**\n\n"
        "```clojure\n"
        "(re-frame.core/reg-view* ::chart\n"
        "  (fn [series]\n"
        "    (let [{:keys [dispatch]} (rf/capture-frame)\n"
        "          !inst (r/atom nil)]\n"
        "      (reagent.core/create-class\n"
        "        {:reagent-render (fn [series] [:div.chart])\n"
        "         :component-did-mount    (fn [this] (dispatch [:chart/mounted]))\n"
        "         :component-will-unmount (fn [_] (dispatch [:chart/unmounted]))}))))\n"
        "```",
        dirty=False, label="VO7 dispatch-only route-2 example is not flagged",
    )
    # VO8 — lexical: an `add-watch` named inside a `;` comment (the recipe
    # explaining the trap in situ) is prose, not code.
    expect_owner(
        CANON.replace(
            "             (reset! !driver                             ; OWN\n",
            "             ;; NOT (add-watch reaction ::feed …) — it could never fire\n"
            "             (reset! !driver                             ; OWN\n",
        ),
        dirty=False, label="VO8 add-watch inside a comment does not flag",
    )

    # Rule 5c live-corpus teeth: the shipped recipe must own its subscription, and
    # deleting that owner from the LIVE text must trip the guard.
    for problem in _live_reactive_owner_problems():
        print(f"SELF-TEST FAIL (reactive-owner live): {problem}")
        failures += 1

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

    # Semantic-teeth fixtures (rf2-gjrlz). Vocabulary presence is not enough: the
    # OPPOSITE polarity must fail even when every positive token survives, and
    # tokens scattered across unrelated paragraphs must not satisfy the invariant.
    #
    # K7 is the exact repro: an adopter owner that ASSERTS the reversal
    # ("automatically follows … never goes stale") with the A→B / remount /
    # pointer tokens sprinkled into unrelated paragraphs. The pre-teeth guard
    # returned [] here.
    K7_REVERSED_SCATTERED = (
        "Capture-once automatically follows any provider change; it never goes "
        "stale.\n\n"
        "Elsewhere, an instance can be retargeted from provider A to provider B.\n\n"
        "You might force a remount with a frame-derived React `key`.\n\n"
        "See `guided-handlers-state.md` §M-11 for more."
    )
    expect_form3(
        K7_REVERSED_SCATTERED, K_CANON, dirty=True,
        label="K7 adopter reversal + scattered vocabulary is dirty",
    )
    # K8: every positive token tied in ONE paragraph, but with a "no need to
    # remount" / "never goes stale" reversal spliced in — polarity teeth must
    # still fail it (the reversal survives beside the correct relationship).
    K8_REVERSED_TIED = (
        "Capture-once is a locked handle; there is no need to remount even if a "
        "surviving instance is retargeted from provider A to provider B, and it "
        "never goes stale — it keeps sending to the stale A. Remedy: a "
        "frame-derived React `key` remount, or the registered `reg-view` child "
        "(route 1). See `guided-handlers-state.md` §M-11."
    )
    expect_form3(
        K8_REVERSED_TIED, K_CANON, dirty=True,
        label="K8 adopter reversal with every positive token present is dirty",
    )
    # K9: the canonical owner reversed (repro's second half) — the cross-owner
    # leg must catch it, not just the adopter.
    K9_CANON_REVERSED = (
        "Capture-once automatically retargets and re-resolves to the new "
        "provider.\n\n"
        "For example, an instance may be retargeted from provider A to provider B."
    )
    expect_form3(
        K_OWNER, K9_CANON_REVERSED, dirty=True,
        label="K9 canonical reversal is dirty",
    )
    # K10: the corrected adaptive-remedy prose (route 1 follows A→B "with no
    # remount") must NOT be read as a reversal — it is the fix, not the footgun.
    K10_ADAPTIVE_REMEDY_OK = (
        "Capture-once is a locked handle. It goes stale if a surviving instance is "
        "retargeted from provider A to provider B — the locked handle keeps "
        "sending to the stale A, never B. Or use the registered `reg-view` child "
        "(route 1), which reads its frame from React context on every render and "
        "so follows A→B with no remount and no re-capture. See "
        "`guided-handlers-state.md` §M-11."
    )
    expect_form3(
        K10_ADAPTIVE_REMEDY_OK, K_CANON, dirty=False,
        label="K10 route-1 adaptive-remedy prose is clean",
    )

    # --- Rule 6 fixtures — M-0 publication-route lock (rf2-snjn5) ---------------
    # The FAIL fixtures are the exact pre-fix shapes (M-0 :69-77 / :81 and the
    # seven artefact-rule `<latest>` recipes); the PASS fixture is the corrected
    # author-supplied-route wording, including the two sentences that must stay
    # legal: the labelled if/when-published `:mvn/version` destination and the
    # honestly-scoped Leiningen pause option. The delegation assertion is
    # scoped to the M-0 section, so the fixtures carry the guide's real rule-
    # heading structure (`### M-0.` … `### M-1.`) — appended dirty lines land
    # after the M-1 heading, proving the `<latest>` / stop-and-wait assertions
    # stay whole-file — and M0-7 reproduces the merged-PR audit's acceptance
    # seam: the anchor present only in a later artefact rule.
    def expect_m0(text: str, *, dirty: bool, label: str) -> None:
        nonlocal failures
        got = bool(_m0_publication_route_problems(text))
        if got != dirty:
            print(
                f"SELF-TEST FAIL ({label}): expected dirty={dirty}, got {got} "
                f"for M-0 route text: {text!r}"
            )
            failures += 1

    M0_CLEAN = (
        "### M-0. Bump the dependency coordinate to `day8/re-frame2`\n"
        'day8/re-frame2 {:git/url "https://github.com/day8/re-frame2.git" '
        ':git/sha "<SHA>" :deps/root "implementation/core"}\n'
        "The recipe is maintained in one place, the setup skill's "
        "[`deps-versions.md` §Choosing the coordinate](../../skills/"
        "re-frame2-setup/references/deps-versions.md"
        "#choosing-the-coordinate-publication-state-decides-the-shape).\n"
        "Choose and record the route, then continue the migration.\n"
        "If and when re-frame2 artefacts are published to a Maven registry, "
        'published coordinates take a `{:mvn/version "…"}` shape instead.\n'
        "Leiningen may instead pause this build tool's dep edit pending "
        "publication.\n"
        "### M-1. Private namespace access\n"
        "Every rule below assumes the coord chosen at M-0 is in place."
    )
    expect_m0(M0_CLEAN, dirty=False, label="M0-1 corrected route text is clean")
    expect_m0(
        M0_CLEAN + '\nday8/re-frame2 {:mvn/version "<latest>"}',
        dirty=True, label="M0-2 mvn <latest> map coord is dirty",
    )
    expect_m0(
        M0_CLEAN + '\n[day8/re-frame2 "<latest>"]',
        dirty=True, label="M0-3 Lein <latest> vector coord is dirty",
    )
    expect_m0(
        M0_CLEAN + "\nleave the dep alone, do not apply any other migration "
        "rules, and flag the situation in the migration report",
        dirty=True, label="M0-4 leave-the-dep-alone stop instruction is dirty",
    )
    expect_m0(
        M0_CLEAN + "\nthe author should wait until a release lands, then "
        "re-run the migration",
        dirty=True, label="M0-5 wait-for-a-release instruction is dirty",
    )
    expect_m0(
        M0_CLEAN.replace(
            "deps-versions.md"
            "#choosing-the-coordinate-publication-state-decides-the-shape",
            "deps-versions.md",
        ),
        dirty=True, label="M0-6 dropped delegation anchor is dirty",
    )
    # M0-7 — the rf2-snjn5 merged-PR audit's acceptance seam: the anchor
    # removed from the M-0 section while a later artefact rule (the M-27..M-33
    # shape) still carries it. A whole-file substring check stays green here;
    # the section-scoped assertion must red.
    expect_m0(
        M0_CLEAN.replace(
            "deps-versions.md"
            "#choosing-the-coordinate-publication-state-decides-the-shape",
            "deps-versions.md",
        )
        + "\nAdd `day8/re-frame2-schemas` at the coordinate kind and pin "
        "chosen at M-0; the per-artefact paths live in the setup skill's "
        "[`deps-versions.md` §Choosing the coordinate](../../skills/"
        "re-frame2-setup/references/deps-versions.md"
        "#choosing-the-coordinate-publication-state-decides-the-shape).",
        dirty=True,
        label="M0-7 anchor only in a later artefact rule is dirty",
    )
    # M0-8 — no M-0 rule heading at all: the delegation assertion has nothing
    # to scope to, so the lock must report rather than silently pass.
    expect_m0(
        M0_CLEAN.replace("### M-0. Bump", "Bump"),
        dirty=True,
        label="M0-8 missing M-0 heading is dirty",
    )
    # M0-9 — polarity 1 of the rf2-snjn5 #7296 audit: a numbered subsection
    # (`#### M-0.1 …`) inside M-0, before the delegation anchor. Its dot is a
    # decimal point, not a rule terminator, so the span must run on to
    # `### M-1.` and scan clean — not truncate at the child and false-red the
    # delegation while the anchor sits inside M-0.
    expect_m0(
        M0_CLEAN.replace(
            "The recipe is maintained in one place",
            "#### M-0.1 Choosing the route\n"
            "The recipe is maintained in one place",
        ),
        dirty=False,
        label="M0-9 numbered subsection before the anchor is clean",
    )
    # M0-10 — polarity 2: the parent `### M-0.` heading removed, an orphan
    # `#### M-0.1` child left carrying the anchor. The child id is not an M-0
    # rule heading, so the lock must report the missing heading (as in M0-8)
    # rather than silently scoping the section to the orphan.
    expect_m0(
        M0_CLEAN.replace(
            "### M-0. Bump the dependency coordinate to `day8/re-frame2`",
            "#### M-0.1 Choosing the route",
        ),
        dirty=True,
        label="M0-10 orphan M-0.1 child with the parent heading removed is dirty",
    )

    # Live red-proof teeth: the lock must have BITE against the exact pre-fix
    # M-0 text — the `<latest>` recipe and the stop instruction verbatim from
    # the guide as it stood before rf2-snjn5.
    PRE_FIX_M0 = (
        M0_CLEAN
        + '\nday8/re-frame2         {:mvn/version "<latest>"}\n'
        + "**If no released v2 version is available yet** (pre-publication): "
        "leave the dep alone, do not apply any other migration rules, and flag "
        "the situation in the migration report — the user must update the "
        "coord manually once a release lands, then re-run the migration."
    )
    if len(_m0_publication_route_problems(PRE_FIX_M0)) < 2:
        print(
            "SELF-TEST FAIL (M0-red-proof): the exact pre-fix M-0 text does "
            "not trip both the <latest> and stop-and-wait assertions."
        )
        failures += 1

    # Live-corpus M-0 delegation tooth (rf2-snjn5): remove ONLY the M-0
    # section's anchor from the SHIPPED guide — the seven M-27..M-33 copies
    # stay intact — and require M0-RECIPE-DELEGATION to red. This is the
    # merged-PR audit's acceptance seam, proven against the landed corpus
    # rather than a fixture that could drift away from it.
    for problem in _live_m0_delegation_problems():
        print(f"SELF-TEST FAIL (M0 delegation live): {problem}")
        failures += 1

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

        # rf2-gjrlz: mutate each live owner to the WRONG POLARITY while keeping
        # ALL of the A/B / remount / pointer vocabulary intact — a splice adjacent
        # to a real capture-once token, so it lands inside a capture-once
        # paragraph regardless of the doc's exact wording. The pre-teeth guard
        # (vocabulary-presence only) was blind to this; the polarity teeth must
        # now catch it. This proves the guard has semantic teeth, not just a
        # token census.
        def _splice_reversal(t: str) -> str:
            return FORM3_CAPTURE_ONCE_RE.sub(
                lambda m: m.group(0)
                + " automatically re-resolves and never goes stale;",
                t,
                count=1,
            )

        f3_reversed = _splice_reversal(f3_live)
        if f3_reversed == f3_live:
            print(
                "SELF-TEST FAIL (form3 owner reversal setup): no capture-once "
                "anchor in FORM-3.md to splice a polarity reversal onto."
            )
            failures += 1
        elif not _form3_capture_once_problems(f3_reversed, g_live):
            print(
                "SELF-TEST FAIL (form3 owner reversal): a capture-once auto-adapt "
                "reversal spliced into FORM-3.md (A/B/remount/pointer intact) "
                "passed the guard — the polarity teeth are blunt."
            )
            failures += 1

        g_reversed = _splice_reversal(g_live)
        if g_reversed == g_live:
            print(
                "SELF-TEST FAIL (canonical reversal setup): no capture-once anchor "
                "in guided-handlers-state.md to splice a polarity reversal onto."
            )
            failures += 1
        elif not _form3_capture_once_problems(f3_live, g_reversed):
            print(
                "SELF-TEST FAIL (canonical reversal): a capture-once auto-adapt "
                "reversal spliced into guided-handlers-state.md (A/B/remount/"
                "pointer intact) passed the cross-owner leg."
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
