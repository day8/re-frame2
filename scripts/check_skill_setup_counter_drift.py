#!/usr/bin/env python3
"""Setup-skill drift guard: counter-keyword consistency + hot-reload lifecycle (rf2-fd1mf3).

The `re-frame2-setup` skill walks an author through a first mounted counter. Its
success criterion is concrete — a counter whose button changes the displayed
number — so the event/sub ids the skill tells the author to INSTALL must match the
ids its substrate view snippets DISPATCH/SUBSCRIBE. Two prior drifts broke that:

  * COUNTER-KEYWORD drift (finding 1). The Reagent leaf
    (`references/first-counter.md`) registered `:counter/inc` / `:counter/dec` and
    a bare `:count` sub, while the UIx/Helix snippets in
    `references/entry-namespace.md` dispatched `:counter/increment` and subscribed
    `[:counter/value]` (the generator-template vocabulary). An agent hand-wiring a
    UIx/Helix project by combining the two leaves got a counter that renders `nil`
    (`:rf.error/no-such-sub`) and whose button targets an unregistered handler
    (`:rf.error/no-such-handler`) — exactly the failure the skill exists to
    prevent. This guard pins ONE shared vocabulary across the setup leaves and the
    generator template's `_shared/events.cljs` + `_shared/subs.cljs`.

  * HOT-RELOAD-LIFECYCLE drift (finding 2, INVERTED by rf2-ms6r8). This arm was
    installed pointing the wrong way, on an unmeasured premise, and now points
    the right way. MEASURED under rf2-r0kk7 (PR #8400) on shadow-cljs 3.4.10,
    against a real deps-new scaffold driven by a live `shadow-cljs watch` and a
    browser: a `:browser` build whose only entry point is a module `:init-fn`
    does **not** re-render after a hot reload. shadow loads the new code (the
    console logs `load JS … views.cljs`), logs

        shadow-cljs: reloading code but no :after-load hooks are configured!

    and the page keeps painting the OLD view — measured unchanged for a full 90
    seconds. shadow calls the module `:init-fn` ONCE, when the bundle loads; a
    reload loads the new code and then calls the build's `^:dev/after-load`
    hooks. With a separate `^:dev/after-load` re-render hook the identical edit
    landed in 1033 ms (Reagent) / 513 ms (UIx) with app-db intact.

    So the CORRECT lifecycle — the one `tools/template`,
    `docs/core/how-to/boot-and-mount-an-app.md` and every `examples/` entry ns
    teach — is the two-function split: the one-time boot ceremony stays in
    `init`, and a separate `^:dev/after-load` hook re-renders into the retained
    React root. The RETIRED framing, which this guard now fails on, is the
    mirror image: that `:init-fn` re-runs after each hot reload, that it is the
    "default after-load hook", that `init` IS the re-render path, or that "no
    separate `^:dev/after-load` hook" is needed.

    The arm is INVERTED rather than retired, so the protection is kept and
    merely pointed the right way: the same three leaves are still pinned, and a
    page that teaches the false claim still fails the build. It additionally
    asserts the AFFIRMATIVE half — every leaf that teaches the boot lifecycle
    must NAME `^:dev/after-load` — which the pre-inversion arm never checked in
    either direction (its patterns matched only the negated forms, so a bare
    "add a `^:dev/after-load` hook" recommendation passed it silently). That
    mirrors the sibling gate PR #8400 landed over the generator template
    (`hot-reload-prose-is-accurate-test`).

The COUNTER-KEYWORD check is a CONTAINMENT assertion, not a literal-text diff:

  * Every event id DISPATCHED in the entry-namespace.md UIx/Helix snippets must be
    REGISTERED (via `reg-event-db` / `reg-event-fx`) in first-counter.md AND in the
    generator template's `_shared/events.cljs`.
  * Every sub id SUBSCRIBED (`subscribe` / `use-subscribe`) in those snippets must
    be REGISTERED (via `reg-sub`) in first-counter.md AND in the template's
    `_shared/subs.cljs`.

We assert the snippet's *consumed* ids are a subset of the *installed* ids (in
both the skill leaf and the template), because the Reagent leaf may register a
superset (e.g. an extra `:counter/decrement` if the author wants a `-` button)
without breaking the "copy these together and it works" contract. The reverse —
a snippet dispatching an id nobody registers — is the drift that strands the
author with a no-op button.

Two further checks keep the sole-canonical-source contract (rf2-qzrkek):

  * CANONICAL-SOURCE. The copy-complete Reagent `core.cljs` (a fenced
    `clojure` block that requires `re-frame.adapter.reagent` and mounts via
    the adapter's client root, a `/render!` call) must exist in
    EXACTLY ONE place — first-counter.md — and
    NOT in entry-namespace.md, which now explains the boot lifecycle keyed to
    it (retaining the UIx/Helix substrate deltas) instead of duplicating a
    second runnable skeleton.

  * MALLI-REQUIRE. The schemas artefact self-wires its Malli adapter, so a
    setup app requires ONLY `re-frame.schemas`. A separate
    `re-frame.schemas.malli` require — a literal require vector, or checklist
    prose demanding one — is drift, flagged on any line naming it UNLESS the
    line explicitly says the separate require is not needed.

Exit code:
    0  no drift detected
    1  drift detected (printed line-by-line; GitHub-Actions ::error:: under CI)
    2  invocation / setup error

Usage:
    python scripts/check_skill_setup_counter_drift.py
    python scripts/check_skill_setup_counter_drift.py --verbose
    python scripts/check_skill_setup_counter_drift.py --ci          # tighter output
                                                                    #   (auto under
                                                                    #   GITHUB_ACTIONS)
    python scripts/check_skill_setup_counter_drift.py --self-test   # built-in
                                                                    #   pass/fail
                                                                    #   fixtures

rf2-fd1mf3.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

# Force UTF-8 on output streams — the corpus carries → / em-dash etc. and the
# default Windows console codec (cp1252) would crash on them (rf2 is maintained
# on Windows; the gate also runs on Linux CI).
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8")  # type: ignore[union-attr]
    except (AttributeError, ValueError):  # pragma: no cover - non-TextIO stream
        pass

SETUP_ROOT = REPO_ROOT / "skills" / "re-frame2-setup"
SETUP_REFS = SETUP_ROOT / "references"
FIRST_COUNTER = SETUP_REFS / "first-counter.md"
ENTRY_NAMESPACE = SETUP_REFS / "entry-namespace.md"
SHADOW_CLJS = SETUP_REFS / "shadow-cljs.md"
SKILL_MD = SETUP_ROOT / "SKILL.md"

TEMPLATE_ROOT = (
    REPO_ROOT
    / "tools"
    / "template"
    / "resources"
    / "day8"
    / "re_frame2_template"
    / "_shared"
)
TEMPLATE_EVENTS = TEMPLATE_ROOT / "events.cljs"
TEMPLATE_SUBS = TEMPLATE_ROOT / "subs.cljs"

# A counter event/sub keyword is the namespaced `:counter/<name>` family. We scope
# the scan to that family so unrelated keywords (`:rf/default`, `:style`, …) never
# enter the comparison.
COUNTER_KW = re.compile(r":counter/[a-z][a-z0-9-]*")

# Registration call sites — the events/subs the skill tells the author to INSTALL.
# Post-EP-0018 the one public form is `reg-event`; the retired `reg-event-db` /
# `-fx` / `-ctx` spellings are matched too so the guard stays correct while the
# generator template (a separate EP-0018 slice) still carries the old spelling
# during the additive window. The optional suffix is non-capturing so the
# captured group is always the `:counter/<name>` id, whichever spelling is used.
REG_EVENT = re.compile(r"reg-event(?:-(?:db|fx|ctx))?\s+(:counter/[a-z0-9-]+)")
REG_SUB = re.compile(r"reg-sub\s+(:counter/[a-z0-9-]+)")

# Consumption call sites — the events/subs the view snippets DISPATCH / SUBSCRIBE.
DISPATCH = re.compile(r"dispatch\s+\[\s*(:counter/[a-z0-9-]+)")
SUBSCRIBE = re.compile(r"(?:use-subscribe|subscribe)\s+\[\s*(:counter/[a-z0-9-]+)")

# HOT-RELOAD-LIFECYCLE retired framing (finding 2, INVERTED by rf2-ms6r8). The
# CORRECT shadow-cljs `:browser` lifecycle — measured under rf2-r0kk7 / PR #8400 on
# shadow-cljs 3.4.10 — is that the module `:init-fn` is called ONCE when the bundle
# loads and is NOT re-run by a hot reload; a reload loads the new code and then calls
# the build's `^:dev/after-load` hooks, so the entry ns carries one. The RETIRED
# framing is the mirror image: that `:init-fn` re-runs after each reload, that it is
# the "default after-load hook", that `init` IS the re-render path, or that "no
# separate `^:dev/after-load` hook" is needed. This guard fails if that retired
# framing reappears. Patterns are case-insensitive, whitespace-tolerant, and matched
# per-line so an honest mention can't trip a neighbouring line.
#
# These first patterns are unambiguous: no correct sentence contains them, whatever
# the surrounding polarity.
HOT_RELOAD_DRIFT = [
    # "no separate `^:dev/after-load` hook" / "no `:after-load` hook needed" — the
    # actionable harm, because it tells the author NOT to write the hook that is
    # the only thing making a reload repaint.
    #
    # The qualifier ("separate", or a trailing needed/necessary/required) is what
    # keeps this off shadow-cljs's OWN diagnostic, which the corrected prose quotes
    # verbatim in all three leaves: "reloading code but no :after-load hooks are
    # configured!". A bare `no … after-load … hook` matches that sentence too, and
    # would red the very pages that teach the fix.
    re.compile(r"\bno\s+separate\b[^.\n]{0,25}(?:\^\s*)?:?(?:dev/)?after-?load\b[^.\n]{0,25}\bhook", re.IGNORECASE),
    re.compile(r"\bno\b[^.\n]{0,25}(?:\^\s*)?:?(?:dev/)?after-?load\b[^.\n]{0,25}\bhooks?\b[^.\n]{0,25}\b(?:needed|necessary|required)\b", re.IGNORECASE),
    re.compile(r"(?:\^\s*)?:?(?:dev/)?after-?load\b[^.\n]{0,15}\bhooks?\b[^.\n]{0,20}\b(?:not needed|unnecessary|isn'?t needed|is not needed)", re.IGNORECASE),
    # ":init-fn is ... the default after-load hook" (incl. "both the startup entry
    # and the default after-load hook").
    re.compile(r":?init(?:-fn)?\b[^.\n]{0,60}\bdefault\b[^.\n]{0,25}after-?load\b[^.\n]{0,10}hook", re.IGNORECASE),
    # "init IS the re-render path" / "init is the hot-reload hook".
    re.compile(r":?init(?:-fn)?\b[^.\n]{0,25}\bis\b[^.\n]{0,20}(?:the\s+)?(?:re-?render\s+path|hot[\s-]?reload\s+hook)", re.IGNORECASE),
    # "the dispatch-sync seed ... per-reload reset boundary" / "re-seeds ... on every
    # hot reload". Same false claim in different words: with `init` not re-running,
    # nothing in it re-seeds, and the `:initial-events` seed runs ONCE at frame
    # creation (`frame-root` REUSES a live frame without re-seeding).
    re.compile(r"\bper-?reload\b[^.\n]{0,20}reset\s+boundary", re.IGNORECASE),
    re.compile(r"re-?seeds?\b[^.\n]{0,40}\b(?:each|every)\b[^.\n]{0,15}hot[\s-]?reload", re.IGNORECASE),
]

# The affirmative "`:init-fn` re-runs after each hot reload" claim. This one needs
# polarity, because the CORRECT sentence is the same words negated ("shadow does NOT
# re-run the module `:init-fn` after a reload"). We match the affirmative shape and
# then reject the hit if the clause leading into it carries a negation — see
# `_rerun_is_negated`.
#
# The trailing `\b` on `:init` pins it as a COMPLETE token so the patterns do not
# fire on the longer, unrelated `:initial-events` keyword (the EP-0027 seed-event
# id). Without the boundary the `:?init(?:-fn)?` prefix matches the `:init` of
# `:initial-events` and produces a false HOT-RELOAD-LIFECYCLE drift.
HOT_RELOAD_RERUN = [
    re.compile(r":?init(?:-fn)?\b[^.\n]{0,50}\bre-?(?:runs?|invokes?|invoked)\b[^.\n]{0,50}(?:hot[\s-]?)?(?:reload|rebuild|save)", re.IGNORECASE),
    re.compile(r"\bre-?(?:runs?|invokes?|invoked)\b[^.\n]{0,50}:?init(?:-fn)?\b[^.\n]{0,50}(?:hot[\s-]?)?(?:reload|rebuild|save)", re.IGNORECASE),
]

# A negation anywhere in the clause running into the match flips its meaning, so the
# hit is not drift. We look at the text preceding the match on the same line, back to
# the previous sentence boundary (or 80 chars, whichever is nearer), plus the matched
# span itself — long enough to catch "shadow ... does **not** re-run", short enough
# that a negation in an unrelated earlier sentence cannot launder a false claim.
RERUN_NEGATION = re.compile(r"\*{0,2}\b(?:not|never|n't|cannot|can't|won'?t)\b\*{0,2}", re.IGNORECASE)

# The AFFIRMATIVE half: a leaf that teaches the boot lifecycle must NAME the hook.
# Catching the false claim is not enough on its own — prose can be silently stripped
# of the wrong sentence and still leave the author with no hook and no hot reload.
AFTER_LOAD_HOOK = re.compile(r"(?:\^\s*)?:dev/after-?load", re.IGNORECASE)

# ADAPTER-KEY retired wording (finding 3). The current Spec 006 adapter contract
# names `:make-state-container` and `:subscribe-container`. The retired wording
# listed bare `state-container` and bare `subscribe` as adapter-map keys. We flag a
# backtick-wrapped bare `state-container` (no `make-` prefix, no `-container`
# suffix, no leading colon) — the `:make-state-container` / `:subscribe-container`
# names never match this. (`subscribe` is intentionally NOT flagged on its own: the
# leaf legitimately documents the auto-injected `subscribe` local and the
# `:subscribe-container` adapter key; the load-bearing retired token is the bare
# `state-container` adapter-key name, which has no current meaning.)
ADAPTER_KEY_DRIFT = re.compile(r"(?<![:`\w-])`state-container`")

# CANONICAL-SOURCE (rf2-qzrkek). A copy-complete Reagent `core.cljs` is a fenced
# ```clojure block that both requires `re-frame.adapter.reagent` AND mounts
# via the adapter's client root with a `/render!` call (rf2-k5r9t) — the
# NAMESPACE is matched, never the require-alias, which is the caller's
# choice and has moved once already (rf2-qtmt). It
# must live in EXACTLY ONE place — first-counter.md — so the setup skill has a
# single copy-complete entry source. entry-namespace.md explains the boot
# lifecycle keyed to it (retaining the UIx/Helix substrate deltas, which mount
# via uix-dom / react-dom, not the Reagent client root) rather than carrying a
# duplicate Reagent skeleton.
FENCED_CLOJURE = re.compile(r"(?s)```clojure\r?\n(.*?)```")

# MALLI-REQUIRE (rf2-qzrkek). Requiring `re-frame.schemas` alone wires Malli —
# the schemas artefact self-requires its Malli adapter. A separate
# `re-frame.schemas.malli` require is wrong. We flag any line NAMING the ns
# unless that same line marks it as unnecessary (the corpus documents the ns
# only to say "no separate require is needed"). The negation cue is regex-based
# and whitespace/markdown-tolerant so `**no** separate` still reads as clean.
MALLI_NS = re.compile(r"re-frame\.schemas\.malli")
MALLI_OK_CONTEXT = re.compile(
    r"(?i)(no\W+separate|self-\W?(?:require|wire)|not\W+needed|isn'?t\W+needed"
    r"|is\W+not\W+needed|without|don'?t\W+need|does\W+not\W+need|never\W+need)"
)


def _slurp(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def _reg_ids(text: str, pattern: re.Pattern[str]) -> set[str]:
    return set(pattern.findall(text))


def find_counter_drift(
    first_counter_text: str,
    entry_namespace_text: str,
    template_events_text: str,
    template_subs_text: str,
) -> list[str]:
    """COUNTER-KEYWORD containment check. Returns drift messages (empty == clean)."""
    problems: list[str] = []

    installed_events = _reg_ids(first_counter_text, REG_EVENT)
    installed_subs = _reg_ids(first_counter_text, REG_SUB)
    template_events = _reg_ids(template_events_text, REG_EVENT)
    template_subs = _reg_ids(template_subs_text, REG_SUB)

    dispatched = _reg_ids(entry_namespace_text, DISPATCH)
    subscribed = _reg_ids(entry_namespace_text, SUBSCRIBE)

    # Every id the snippets consume must be registered by the Reagent leaf AND the
    # template — otherwise an author copying the snippet + the leaf gets a no-op.
    for ev in sorted(dispatched - installed_events):
        problems.append(
            f"COUNTER-KEYWORD: entry-namespace.md UIx/Helix snippet dispatches {ev}, "
            f"but first-counter.md registers no such reg-event-* "
            f"(installs: {sorted(installed_events) or 'none'}). Copying the snippet "
            "with the first-counter events leaves the button targeting an "
            "unregistered handler (:rf.error/no-such-handler, silent no-op)."
        )
    for ev in sorted(dispatched - template_events):
        problems.append(
            f"COUNTER-KEYWORD: entry-namespace.md UIx/Helix snippet dispatches {ev}, "
            f"but the generator template's _shared/events.cljs registers no such "
            f"event (registers: {sorted(template_events) or 'none'}). The setup "
            "skill claims the UIx/Helix snippets are verbatim copies of the template "
            "views — keep them in lockstep."
        )
    for sub in sorted(subscribed - installed_subs):
        problems.append(
            f"COUNTER-KEYWORD: entry-namespace.md UIx/Helix snippet subscribes {sub}, "
            f"but first-counter.md registers no such reg-sub "
            f"(installs: {sorted(installed_subs) or 'none'}). Copying the snippet "
            "with the first-counter subs renders nil (:rf.error/no-such-sub)."
        )
    for sub in sorted(subscribed - template_subs):
        problems.append(
            f"COUNTER-KEYWORD: entry-namespace.md UIx/Helix snippet subscribes {sub}, "
            f"but the generator template's _shared/subs.cljs registers no such sub "
            f"(registers: {sorted(template_subs) or 'none'})."
        )

    # Both substrate trees must actually carry a counter vocabulary — an empty
    # extract means a regex/anchor moved and the guard went vacuous.
    if not dispatched and not subscribed:
        problems.append(
            "COUNTER-KEYWORD setup: extracted no :counter/* dispatch or subscribe "
            "from entry-namespace.md — the UIx/Helix snippet anchors moved; update "
            "the guard."
        )
    if not installed_events or not installed_subs:
        problems.append(
            "COUNTER-KEYWORD setup: extracted no :counter/* reg-event-*/reg-sub from "
            "first-counter.md — the worked-example anchors moved; update the guard."
        )

    return problems


_LIFECYCLE_REMEDY = (
    "That is the wrong shadow-cljs lifecycle, and it costs the author their inner "
    "loop. MEASURED on shadow-cljs 3.4.10 (rf2-r0kk7 / PR #8400): for the :browser "
    "target shadow calls the module :init-fn ONCE, when the bundle loads, and does "
    "NOT re-run it after a hot reload — it loads the new code and calls the build's "
    "^:dev/after-load hooks. With none configured it logs 'reloading code but no "
    ":after-load hooks are configured!' and the page keeps painting the OLD view "
    "(measured unchanged for 90s). Teach the two-function split the generator "
    "template, docs/core/how-to/boot-and-mount-an-app.md and every examples/ entry "
    "ns already use: the one-time boot ceremony stays in `init`, and a separate "
    "`^:dev/after-load` hook re-renders into the retained React root."
)


def _rerun_is_negated(line: str, start: int, end: int) -> bool:
    """True when the clause running into a `re-runs :init-fn` hit negates it.

    The CORRECT sentence is the false one negated ("shadow does **not** re-run the
    module `:init-fn` after a reload"), so polarity is what separates them. We read
    back from the match to the previous sentence boundary — capped at 80 chars so a
    negation in an unrelated earlier clause cannot launder a false claim — and
    include the matched span itself.
    """
    window = line[max(0, start - 80):start]
    window = re.split(r"[.;:—]", window)[-1]
    return bool(RERUN_NEGATION.search(window + line[start:end]))


def find_hot_reload_drift(*texts_with_names: tuple[str, str]) -> list[str]:
    """HOT-RELOAD-LIFECYCLE check. Returns drift messages (empty == clean)."""
    problems: list[str] = []
    for name, text in texts_with_names:
        for lineno, line in enumerate(text.splitlines(), start=1):
            hit = next((p for p in HOT_RELOAD_DRIFT if p.search(line)), None)
            if hit is None:
                for pat in HOT_RELOAD_RERUN:
                    m = pat.search(line)
                    if m and not _rerun_is_negated(line, m.start(), m.end()):
                        hit = pat
                        break
            if hit is not None:
                problems.append(
                    f"HOT-RELOAD-LIFECYCLE: {name}:{lineno} teaches the retired "
                    "framing — that the module :init-fn re-runs after each hot "
                    "reload (that it is the 'default after-load hook', that `init` "
                    "IS the re-render path, or that no separate ^:dev/after-load "
                    f"hook is needed). {_LIFECYCLE_REMEDY} Offending line: "
                    f"{line.strip()!r}"
                )
    return problems


def find_missing_after_load_hook(*texts_with_names: tuple[str, str]) -> list[str]:
    """AFTER-LOAD-HOOK presence check. Returns drift messages (empty == clean).

    The affirmative half of the lifecycle contract. Deleting the false sentence
    without teaching the hook leaves the author exactly as stranded — a scaffold
    that compiles, reloads, and never repaints.
    """
    return [
        f"AFTER-LOAD-HOOK: {name} never names `^:dev/after-load`. This leaf teaches "
        "the boot lifecycle, so it must show the hook that makes a hot reload "
        f"repaint. {_LIFECYCLE_REMEDY}"
        for name, text in texts_with_names
        if not AFTER_LOAD_HOOK.search(text)
    ]


def find_adapter_key_drift(name: str, text: str) -> list[str]:
    """ADAPTER-KEY check (finding 3). Returns drift messages (empty == clean)."""
    problems: list[str] = []
    for lineno, line in enumerate(text.splitlines(), start=1):
        if ADAPTER_KEY_DRIFT.search(line):
            problems.append(
                f"ADAPTER-KEY: {name}:{lineno} names a bare `state-container` "
                "adapter key — the retired contract vocabulary. The current Spec 006 "
                "adapter contract uses `:make-state-container` (and "
                "`:subscribe-container`, not bare `subscribe`). Either avoid the "
                "low-level adapter-map internals (\"pass the exported adapter spec "
                "map; app authors do not construct it\") or list the current key "
                f"names accurately. Offending line: {line.strip()!r}"
            )
    return problems


def _reagent_core_blocks(text: str) -> list[str]:
    """Fenced `clojure` blocks that are a copy-complete Reagent core.cljs —
    they require `re-frame.adapter.reagent` AND mount through the adapter's
    client root with a `/render!` call (rf2-k5r9t; the entry ns no
    longer touches `reagent.dom.client` itself). UIx/Helix snippets
    (uix-dom / react-dom) never match: their `render-root` is not
    `/render!`, and they require a different adapter namespace.

    THE REQUIRE-ALIAS IS DELIBERATELY NOT PART OF THE MATCH (rf2-qtmt).
    This pinned the literal `reagent-adapter/render!` until the scaffold
    moved to the canonical `rf.adapter.reagent` dialect, at which point the
    detector matched NOTHING — and that fails OPEN on the second clause
    below, because a duplicate in entry-namespace.md is invisible to a
    detector that sees no blocks at all. Only the first clause is loud
    about it. The NAMESPACE is the stable fact; the alias is the caller's
    choice, and this file has no business knowing which one was picked.
    """
    return [
        block
        for block in FENCED_CLOJURE.findall(text)
        if "re-frame.adapter.reagent" in block and "/render!" in block
    ]


def find_canonical_source_drift(
    first_counter_text: str, entry_namespace_text: str
) -> list[str]:
    """CANONICAL-SOURCE check. Returns drift messages (empty == clean)."""
    problems: list[str] = []
    fc_blocks = _reagent_core_blocks(first_counter_text)
    en_blocks = _reagent_core_blocks(entry_namespace_text)

    if len(fc_blocks) != 1:
        problems.append(
            "CANONICAL-SOURCE: first-counter.md must carry EXACTLY ONE "
            "copy-complete Reagent core.cljs fenced block (requires "
            "`re-frame.adapter.reagent` and mounts via a `/render!` call) — the sole "
            f"copy-complete setup entry source; found {len(fc_blocks)}. An "
            "anchor moved or a duplicate crept in; the emitted-scaffold "
            "compile also extracts this one block."
        )
    if en_blocks:
        problems.append(
            f"CANONICAL-SOURCE: entry-namespace.md carries {len(en_blocks)} "
            "copy-complete Reagent core.cljs fenced block(s) "
            "(`re-frame.adapter.reagent` + `/render!`). The sole copy-complete "
            "core.cljs must live ONLY in first-counter.md; entry-namespace.md "
            "explains the boot lifecycle keyed to it — retain the UIx/Helix "
            "substrate deltas, but do not duplicate the Reagent skeleton "
            "(rf2-qzrkek)."
        )
    return problems


def find_malli_require_drift(name: str, text: str) -> list[str]:
    """MALLI-REQUIRE check. Returns drift messages (empty == clean)."""
    problems: list[str] = []
    for lineno, line in enumerate(text.splitlines(), start=1):
        if MALLI_NS.search(line) and not MALLI_OK_CONTEXT.search(line):
            problems.append(
                f"MALLI-REQUIRE: {name}:{lineno} names a SEPARATE "
                "`re-frame.schemas.malli` require without flagging it as "
                "unnecessary. The schemas artefact self-requires its Malli "
                "adapter, so requiring `re-frame.schemas` alone publishes the "
                "validate/explain hooks — a separate `re-frame.schemas.malli` "
                "require (or checklist prose demanding one) is wrong. Require "
                "only `re-frame.schemas`, or say the separate require is not "
                f"needed. Offending line: {line.strip()!r}"
            )
    return problems


def run(*, verbose: bool, ci: bool) -> int:
    for p in (FIRST_COUNTER, ENTRY_NAMESPACE, SHADOW_CLJS, SKILL_MD,
              TEMPLATE_EVENTS, TEMPLATE_SUBS):
        if not p.is_file():
            sys.stderr.write(f"error: required file not found at {p}\n")
            return 2

    first_counter_text = _slurp(FIRST_COUNTER)
    entry_namespace_text = _slurp(ENTRY_NAMESPACE)
    shadow_text = _slurp(SHADOW_CLJS)
    skill_text = _slurp(SKILL_MD)

    problems = find_counter_drift(
        first_counter_text,
        entry_namespace_text,
        _slurp(TEMPLATE_EVENTS),
        _slurp(TEMPLATE_SUBS),
    )
    # The hot-reload arm scans all four setup leaves. Before rf2-ms6r8 it read only
    # shadow-cljs.md + entry-namespace.md, which is why the same false claim sat
    # unguarded inside first-counter.md's copy-complete `core.cljs` — the single
    # most load-bearing site, because it is the file the author actually copies.
    lifecycle_leaves = (
        (str(SHADOW_CLJS.relative_to(REPO_ROOT)), shadow_text),
        (str(ENTRY_NAMESPACE.relative_to(REPO_ROOT)), entry_namespace_text),
        (str(FIRST_COUNTER.relative_to(REPO_ROOT)), first_counter_text),
        (str(SKILL_MD.relative_to(REPO_ROOT)), skill_text),
    )
    problems += find_hot_reload_drift(*lifecycle_leaves)
    # SKILL.md is scanned for the false claim but is NOT required to name the hook:
    # it is the router, and the lifecycle is the three reference leaves' territory.
    problems += find_missing_after_load_hook(*lifecycle_leaves[:3])
    problems += find_adapter_key_drift(
        str(ENTRY_NAMESPACE.relative_to(REPO_ROOT)), entry_namespace_text
    )
    problems += find_canonical_source_drift(first_counter_text, entry_namespace_text)
    for name, text in (
        (str(FIRST_COUNTER.relative_to(REPO_ROOT)), first_counter_text),
        (str(ENTRY_NAMESPACE.relative_to(REPO_ROOT)), entry_namespace_text),
        (str(SKILL_MD.relative_to(REPO_ROOT)), skill_text),
    ):
        problems += find_malli_require_drift(name, text)

    if verbose:
        print(
            "setup-counter guard: checked counter-keyword containment "
            "(first-counter.md + entry-namespace.md + template), hot-reload "
            "lifecycle wording (shadow-cljs.md + entry-namespace.md + "
            "first-counter.md + SKILL.md) and ^:dev/after-load presence in the "
            "three reference leaves, adapter-key "
            "vocabulary (entry-namespace.md), one-canonical-source "
            "(first-counter.md is the sole copy-complete core.cljs), and the "
            "schemas single-require contract (only re-frame.schemas; no separate "
            "re-frame.schemas.malli)."
        )

    if not problems:
        if verbose:
            print("setup-counter guard: no drift — vocabulary consistent, lifecycle correct.")
        return 0

    err_prefix = "::error::" if ci else ""
    for p in problems:
        print(f"{err_prefix}{p}")
    print(
        f"\nsetup-counter guard: {len(problems)} drift issue(s) — the setup skill's "
        "counter vocabulary must be internally consistent and the hot-reload "
        "lifecycle wording must be correct."
    )
    return 1


# ---------------------------------------------------------------------------
# Self-test — exercises both checks against in-memory fixtures so the guard
# itself can't silently rot.
# ---------------------------------------------------------------------------

def _self_test() -> int:
    failures = 0

    # first-counter.md now uses the one-form `reg-event` (EP-0018); the
    # generator template still carries the retired `reg-event-db` spelling during
    # the additive window. The guard must recognise BOTH spellings as a counter
    # registration so the containment check holds across the mixed corpus.
    good_first_counter = (
        "(rf/reg-event :counter/initialise (fn [_cofx _] {:db {:counter/value 0}}))\n"
        "(rf/reg-event :counter/increment (fn [{:keys [db]} _] {:db (update db :counter/value inc)}))\n"
        "(rf/reg-sub :counter/value (fn [db _] (:counter/value db)))\n"
    )
    good_template_events = (
        "(rf/reg-event-db\n  :counter/initialise (fn [_ _] {:counter/value 0}))\n"
        "(rf/reg-event-db\n  :counter/increment (fn [db _] (update db :counter/value inc)))\n"
    )
    good_template_subs = "(rf/reg-sub\n  :counter/value (fn [db _] (:counter/value db)))\n"
    good_entry = (
        "($ :button {:on-click #(dispatch [:counter/increment])} \"+1\")\n"
        "(rf.adapter.uix/use-subscribe [:counter/value])\n"
    )

    # Case A — clean: snippet ids are a subset of both the leaf and the template.
    probs = find_counter_drift(
        good_first_counter, good_entry, good_template_events, good_template_subs
    )
    if probs:
        print(f"SELF-TEST FAIL (A clean counter): unexpected {probs}")
        failures += 1

    # Case B — the rf2-fd1mf3 drift: Reagent leaf on `:count` + `:counter/inc`,
    # snippet on `:counter/increment` + `:counter/value`.
    drift_first_counter = (
        "(rf/reg-event-db :counter/initialise (fn [_ _] {:count 0}))\n"
        "(rf/reg-event-db :counter/inc (fn [db _] (update db :count inc)))\n"
        "(rf/reg-sub :count (fn [db _] (:count db)))\n"
    )
    probs = find_counter_drift(
        drift_first_counter, good_entry, good_template_events, good_template_subs
    )
    if not any("COUNTER-KEYWORD" in p and ":counter/increment" in p for p in probs):
        print(f"SELF-TEST FAIL (B counter drift): expected missing handler, got {probs}")
        failures += 1
    if not any("COUNTER-KEYWORD" in p and ":counter/value" in p for p in probs):
        print(f"SELF-TEST FAIL (B counter drift): expected missing sub, got {probs}")
        failures += 1

    # Case C — extra leaf registration (decrement) is fine: snippet ids still a
    # subset. No drift.
    extra_first_counter = good_first_counter + (
        "(rf/reg-event-db :counter/decrement (fn [db _] (update db :counter/value dec)))\n"
    )
    probs = find_counter_drift(
        extra_first_counter, good_entry, good_template_events, good_template_subs
    )
    if probs:
        print(f"SELF-TEST FAIL (C superset clean): unexpected {probs}")
        failures += 1

    # Case D — hot-reload clean: the CORRECT (measured) framing. Note it is the
    # retired claim's own words, NEGATED — which is exactly why the affirmative
    # `re-runs :init-fn` patterns must be polarity-aware.
    good_shadow = (
        "shadow calls the module `:init-fn` ONCE, when the bundle loads. It does "
        "NOT re-run it after a hot reload — a reload loads the new code and then "
        "calls the build's `^:dev/after-load` hooks, so `core.cljs` carries one.\n"
        "`^:dev/after-load` is shadow's cue to re-run `mount!` after each "
        "successful hot reload, re-rendering your edited views into the retained "
        "React root.\n"
    )
    probs = find_hot_reload_drift(("shadow-cljs.md", good_shadow))
    if probs:
        print(f"SELF-TEST FAIL (D hot-reload clean): unexpected {probs}")
        failures += 1
    probs = find_missing_after_load_hook(("shadow-cljs.md", good_shadow))
    if probs:
        print(f"SELF-TEST FAIL (D after-load presence): unexpected {probs}")
        failures += 1

    # Case E — the retired drift, in each of the shapes the setup skill actually
    # carried before rf2-ms6r8. Every one must be caught on its own line.
    for label, drift_shadow in [
        ("re-runs after each reload",
         "shadow-cljs's `:browser` target re-runs `:init-fn` (`init`) after "
         "**each** hot reload, so `init` IS the re-render path.\n"),
        ("default after-load hook",
         "For the `:browser` target the module `:init-fn` is both the startup "
         "entry and the default after-load hook.\n"),
        ("no separate hook",
         "A code reload re-invokes `init` — no separate `^:dev/after-load` "
         "hook.\n"),
        ("init re-runs every save",
         "`init` re-runs on **every** hot reload, so `rf/init!` runs again each "
         "save.\n"),
        # The subject is load-bearing and the pattern requires it. A subject-LESS
        # "re-invoked on each hot reload" is not decidable per-line: said of
        # `mount!` it is correct prose, said of `init` it is the false claim. The
        # template's own subject-less docstring shape (`_uix/core.cljs`, "Idempotent
        # — re-invoked on each hot reload") is gated where it lives, by PR #8400's
        # `entry-namespace-carries-after-load-hook-test`.
        ("init re-invoked on each reload",
         "`init` is idempotent — it is re-invoked on each hot reload.\n"),
        ("per-reload reset boundary",
         "The explicit `dispatch-sync` seed in `init` is the per-reload reset "
         "boundary.\n"),
        ("re-seeds every reload",
         "The `dispatch-sync` seed re-seeds this counter on every hot reload.\n"),
    ]:
        probs = find_hot_reload_drift(("shadow-cljs.md", drift_shadow))
        if not any("HOT-RELOAD-LIFECYCLE" in p for p in probs):
            print(f"SELF-TEST FAIL (E hot-reload drift / {label}): expected drift, got {probs}")
            failures += 1

    # Case E2b — shadow-cljs's OWN diagnostic must not trip the guard. All three
    # corrected leaves quote it verbatim to tell the author what a missing hook
    # looks like, so a bare `no … after-load … hook` pattern would red exactly the
    # pages that teach the fix. Pinned in both the quoted and the paraphrased form.
    for label, quoted_diagnostic in [
        ("verbatim",
         ";; \"reloading code but no :after-load hooks are configured!\" and the "
         "page keeps painting the OLD view.\n"),
        ("paraphrased",
         "With no hook configured shadow says so — `reloading code but no "
         "`:after-load` hooks are configured!` — and the page keeps painting the "
         "old view.\n"),
    ]:
        probs = find_hot_reload_drift(("first-counter.md", quoted_diagnostic))
        if probs:
            print(f"SELF-TEST FAIL (E2b shadow diagnostic / {label}): unexpected {probs}")
            failures += 1

    # Case E3 — the AFFIRMATIVE half. Prose stripped of the false sentence but
    # never taught the hook is the same stranded author, so it must still fail.
    silent_shadow = (
        "The `:browser` target compiles your edit and pushes the new module to "
        "the page. Hold the React root in a `defonce` so a save reuses it.\n"
    )
    probs = find_missing_after_load_hook(("shadow-cljs.md", silent_shadow))
    if not any("AFTER-LOAD-HOOK" in p for p in probs):
        print(f"SELF-TEST FAIL (E3 after-load absent): expected drift, got {probs}")
        failures += 1

    # Case E2 — `:initial-events` must NOT trip the retired-framing patterns
    # (regression pin for the EP-0027 false positive). "the surgical update …
    # does not rerun any `:initial-events`" is correct re-frame2 semantics: the
    # `:?init(?:-fn)?` token must match `:init` / `:init-fn` only as a COMPLETE
    # token, never as the `:init` prefix of the longer `:initial-events` keyword.
    # Both polarities are pinned, because the affirmative `re-runs` patterns
    # (HOT_RELOAD_RERUN) reach shapes the pre-inversion arm never matched.
    for label, initial_events_clean in [
        ("negated",
         "`frame-root` REUSES the live frame, so the surgical update in step 2 "
         "does not rerun any `:initial-events`.\n"),
        ("affirmative",
         "`frame-root` runs the `:initial-events` seed once, at frame creation; "
         "a browser refresh re-runs it because the reload creates a fresh "
         "frame.\n"),
    ]:
        probs = find_hot_reload_drift(("entry-namespace.md", initial_events_clean))
        if probs:
            print(f"SELF-TEST FAIL (E2 :initial-events false positive / {label}): "
                  f"unexpected {probs}")
            failures += 1

    # Case F — adapter-key clean: current names + the front-porch sentence.
    good_adapter = (
        "You pass the exported `adapter` var. The contract uses "
        "`:make-state-container` and `:subscribe-container`; views deref the "
        "auto-injected `subscribe` local.\n"
    )
    probs = find_adapter_key_drift("entry-namespace.md", good_adapter)
    if probs:
        print(f"SELF-TEST FAIL (F adapter clean): unexpected {probs}")
        failures += 1

    # Case G — the rf2-fd1mf3 finding-3 drift: bare `state-container` adapter key.
    drift_adapter = (
        "The adapter map carries the substrate's `state-container`, "
        "`read-container`, `replace-container!`, `subscribe`, `render`, and "
        "hot-reload hooks.\n"
    )
    probs = find_adapter_key_drift("entry-namespace.md", drift_adapter)
    if not any("ADAPTER-KEY" in p for p in probs):
        print(f"SELF-TEST FAIL (G adapter drift): expected drift, got {probs}")
        failures += 1

    # Case H — CANONICAL-SOURCE clean: the copy-complete Reagent core.cljs
    # lives ONLY in first-counter.md; entry-namespace.md's UIx snippet mounts
    # via uix-dom (its `render-root` is not `/render!`), so it is not a
    # Reagent core block.
    good_fc_block = (
        "```clojure\n"
        "(ns your-app.core\n"
        "  (:require [re-frame.adapter.reagent :as rf.adapter.reagent]))\n"
        "(defn ^:export init []\n"
        "  (rf.adapter.reagent/render! app-root [counter-app] el))\n"
        "```\n"
    )
    good_en_no_reagent = (
        "```clojure\n"
        "(ns your-app.core (:require [uix.dom :as uix-dom]))\n"
        "(uix-dom/render-root ($ views/counter-app) react-root)\n"
        "```\n"
    )
    probs = find_canonical_source_drift(good_fc_block, good_en_no_reagent)
    if probs:
        print(f"SELF-TEST FAIL (H canonical clean): unexpected {probs}")
        failures += 1

    # Case I — CANONICAL-SOURCE drift: entry-namespace.md carries a duplicate
    # copy-complete Reagent core.cljs skeleton (the rf2-qzrkek regression).
    probs = find_canonical_source_drift(good_fc_block, good_fc_block)
    if not any("CANONICAL-SOURCE" in p and "entry-namespace.md" in p for p in probs):
        print(f"SELF-TEST FAIL (I canonical drift): expected entry-namespace drift, got {probs}")
        failures += 1

    # Case J — MALLI-REQUIRE clean: the ns is named only to say it is NOT
    # needed. Both the plain and the markdown-emphasised (`**no** separate`)
    # negations must read as clean.
    for label, txt in [
        ("plain",
         "re-frame.schemas self-requires its Malli adapter — no separate "
         "re-frame.schemas.malli require is needed.\n"),
        ("md",
         "requires **only** `re-frame.schemas` (which self-wires its Malli "
         "adapter; **no** separate `re-frame.schemas.malli` require).\n"),
    ]:
        probs = find_malli_require_drift("first-counter.md", txt)
        if probs:
            print(f"SELF-TEST FAIL (J malli clean {label}): unexpected {probs}")
            failures += 1

    # Case K — MALLI-REQUIRE drift: checklist prose demanding a SEPARATE
    # re-frame.schemas.malli require (the rf2-qzrkek checklist drift), and a
    # literal require vector.
    probs = find_malli_require_drift(
        "SKILL.md",
        "Entry ns requires `re-frame.schemas` + `re-frame.schemas.malli` and "
        "attaches the app-db schema.\n",
    )
    if not any("MALLI-REQUIRE" in p for p in probs):
        print(f"SELF-TEST FAIL (K malli prose drift): expected drift, got {probs}")
        failures += 1
    probs = find_malli_require_drift(
        "first-counter.md",
        "  (:require [re-frame.schemas]\n            [re-frame.schemas.malli])\n",
    )
    if not any("MALLI-REQUIRE" in p for p in probs):
        print(f"SELF-TEST FAIL (K malli require drift): expected drift, got {probs}")
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
