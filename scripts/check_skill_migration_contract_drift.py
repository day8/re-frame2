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

All of these line rules are written to fire ONLY on the stale ASSERTION / bad-command
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


def _scanned_files() -> list[Path]:
    """User-facing migration-skill leaves (SKILL.md + references/*.md), globbed so
    a new reference leaf is covered automatically, plus the migration corpus the
    skill treats as source of truth. The skill's spec/ meta-docs are excluded
    (re-authoring material, not loaded during normal operation)."""
    files = [SKILL_DIR / "SKILL.md"]
    files.extend(sorted((SKILL_DIR / "references").glob("*.md")))
    files.append(MIGRATION_MD)
    files.extend(ADAPTER_READMES)
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
# exists, so this rule deliberately requires lifecycle/hook wording on the SAME
# line and clears corrected negative examples ("a bare call throws").
FORM3_LIFECYCLE_RE = re.compile(
    r"form-3|component-did-mount|component-did-update|component-will-unmount"
    r"|lifecycle",
    re.IGNORECASE,
)
FORM3_BARE_LIFECYCLE_CALL_RE = re.compile(
    r"\((?:rf/)?(?:subscribe-once|unsubscribe)\s+[^\s()]+\)",
    re.IGNORECASE,
)
FORM3_BARE_LIFECYCLE_NEGATION_RE = re.compile(
    r"\bbare\b|raise(?:s|d)?|throw(?:s|n)?|no-frame-context|do not|don't"
    r"|must not|never|invalid|wrong|omit(?:s|ted)?|instead",
    re.IGNORECASE,
)


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

    # Rule 5 — affirmative Form-3 lifecycle advice using a bare frame-scoped
    # one-shot read or teardown after the resolver scope has unwound.
    if (
        FORM3_LIFECYCLE_RE.search(line)
        and FORM3_BARE_LIFECYCLE_CALL_RE.search(line)
        and not FORM3_BARE_LIFECYCLE_NEGATION_RE.search(line)
    ):
        problems.append(
            "FORM3-BARE-LIFECYCLE: a Form-3 lifecycle callback has no ambient "
            "frame after render scope unwinds. Capture the frame once in the "
            "registered outer callable; use `(rf/subscribe-once query-v "
            "{:frame frame})` for a hook one-shot read and frame-first "
            "`(rf/unsubscribe frame query-v)` for teardown. Do not recommend "
            "the bare one-argument forms in lifecycle guidance."
        )

    return problems


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
        for lineno, line in enumerate(_slurp(path).splitlines(), start=1):
            lines_checked += 1
            for label in line_problems(line):
                rel = path.relative_to(REPO_ROOT)
                problems.append(f"{rel}:{lineno}: {label}\n    {line.strip()}")
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

    if verbose:
        print(
            f"migration contract-drift guard: scanned {len(files)} files "
            f"({lines_checked} lines) + ran the M-1 classifier over "
            f"{len(M1_FLAG_NSES) + len(M1_EXEMPT_NSES)} representative requires."
        )

    if not problems:
        if verbose:
            print(
                "contract-drift: no plain-fn-inherits-frame (M-11), "
                "moved-to-frame-level-:on-error (M-13), boot-smoke Pair "
                "partition-mismatch (Rule 4), Form-3 bare-lifecycle targeting "
                "(Rule 5), or M-1 classifier / kickoff-anchor drift found."
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
        "Form-3 lifecycle explicit-frame targeting, to the shipped contract."
    )
    return 1


# ---------------------------------------------------------------------------
# Self-test — exercises the line classifier against in-memory fixtures so the
# guard itself can't silently rot. Mirrors the --self-test convention in the
# sibling check_skill_*.py guards. The FAIL fixtures are the EXACT pre-fix
# stale-claim shapes the rf2-h9yfsm review found; the PASS fixtures are the
# corrected wording (and the unrelated live `:on-error` surfaces).
# ---------------------------------------------------------------------------

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
    expect(
        "In :component-did-mount read `(rf/subscribe-once query-v)` and in "
        ":component-will-unmount call `(rf/unsubscribe query-v)`.",
        dirty=True, label="E1 Form-3 lifecycle recommends bare read/teardown",
    )
    expect(
        "In a Form-3 hook use `(rf/subscribe-once query-v {:frame frame})`; "
        "teardown is `(rf/unsubscribe frame query-v)`.",
        dirty=False, label="E2 Form-3 lifecycle targets captured frame",
    )
    expect(
        "A bare `(rf/subscribe-once query-v)` in a lifecycle hook throws "
        "`:rf.error/no-frame-context`.",
        dirty=False, label="E3 negative bare lifecycle example",
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
