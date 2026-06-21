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

  * HOT-RELOAD-LIFECYCLE drift (finding 2). The correct shadow-cljs `:browser`
    lifecycle is: the module `:init-fn` is wired as BOTH the startup entry and the
    default after-load hook, so it re-runs after EACH hot reload — no separate
    `^:dev/after-load` hook is needed, and the explicit `dispatch-sync` seed in
    `init` is the per-reload reset boundary. This matches the generator template's
    README §Hot reload (CI-guarded by `tools/template/.../template_test.clj`) and
    the implementation. `references/shadow-cljs.md` / `references/entry-namespace.md`
    had previously taught the OPPOSITE (Camp B): that `:init-fn` is a "one-time
    startup hook" that does NOT re-run on a bare code reload, recommending a
    separate `^:dev/after-load render!` hook. That framing is wrong. This guard
    fails if the retired one-time-startup / "add an `^:dev/after-load` hook to
    re-render" framing reappears.

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

SETUP_REFS = REPO_ROOT / "skills" / "re-frame2-setup" / "references"
FIRST_COUNTER = SETUP_REFS / "first-counter.md"
ENTRY_NAMESPACE = SETUP_REFS / "entry-namespace.md"
SHADOW_CLJS = SETUP_REFS / "shadow-cljs.md"

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

# HOT-RELOAD-LIFECYCLE retired framing (finding 2). The CORRECT shadow-cljs
# `:browser` lifecycle is: the module `:init-fn` re-runs after each hot reload (it is
# both the startup entry and the default after-load hook). The RETIRED (Camp B)
# framing asserted the opposite — that `:init-fn` is a "one-time startup hook" that
# does NOT re-run on a bare code reload, and recommended adding a separate
# `^:dev/after-load` render hook to get hot reload. This guard fails if that retired
# framing reappears. Patterns are case-insensitive, whitespace-tolerant, and matched
# per-line so an honest mention can't trip a neighbouring line.
HOT_RELOAD_DRIFT = [
    # "the :init-fn is a one-time startup hook" (the load-bearing wrong claim).
    re.compile(r":?init-fn[^.\n]{0,40}\bone-?time\b[^.\n]{0,30}startup", re.IGNORECASE),
    re.compile(r"\bone-?time\b[^.\n]{0,30}startup[^.\n]{0,40}:?init-fn", re.IGNORECASE),
    # ":init-fn is ... NOT the hot-reload hook".
    re.compile(r":?init-fn[^.\n]{0,40}\bnot\b[^.\n]{0,20}(?:the\s+)?hot[\s-]?reload[^.\n]{0,10}hook", re.IGNORECASE),
    # "does not re-run / re-invoke :init-fn / init [on/after a reload]". The
    # trailing `\b` pins `:init` / `:init-fn` as a COMPLETE token so the pattern
    # does not falsely fire on the longer, unrelated `:initial-events` keyword
    # (the EP-0027 seed-event id) — a sentence like "the surgical update does
    # not rerun any `:initial-events`" is correct re-frame2 semantics, not the
    # retired one-time-startup `:init-fn` claim this guard catches. Without the
    # boundary the `:?init(?:-fn)?` prefix matched the `:init` of
    # `:initial-events` and produced a false HOT-RELOAD-LIFECYCLE drift.
    re.compile(r"does\s+\*?\*?not\*?\*?[^.\n]{0,30}re-?(?:run|invoke)s?[^.\n]{0,30}:?init(?:-fn)?\b", re.IGNORECASE),
    re.compile(r"does\s+\*?\*?not\*?\*?[^.\n]{0,30}re-?(?:run|invoke)s?\s+(?:the\s+)?:?init\b", re.IGNORECASE),
]

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


def find_hot_reload_drift(*texts_with_names: tuple[str, str]) -> list[str]:
    """HOT-RELOAD-LIFECYCLE check. Returns drift messages (empty == clean)."""
    problems: list[str] = []
    for name, text in ((n, t) for t, n in [(t, n) for n, t in texts_with_names]):
        for lineno, line in enumerate(text.splitlines(), start=1):
            for pat in HOT_RELOAD_DRIFT:
                if pat.search(line):
                    problems.append(
                        f"HOT-RELOAD-LIFECYCLE: {name}:{lineno} teaches the retired "
                        "Camp B framing — that :init-fn is a one-time startup hook "
                        "that does NOT re-run on a hot reload (and that you must add a "
                        "separate ^:dev/after-load hook). That is the wrong shadow-cljs "
                        "lifecycle. For the :browser target the module :init-fn IS "
                        "re-invoked after each hot reload by default (it is wired as "
                        "both the startup entry and the default after-load hook), so no "
                        "^:dev/after-load hook is needed and the dispatch-sync seed in "
                        "init is the per-reload reset boundary. Rewrite the sentence to "
                        f"match the template README §Hot reload: {line.strip()!r}"
                    )
                    break
    return problems


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


def run(*, verbose: bool, ci: bool) -> int:
    for p in (FIRST_COUNTER, ENTRY_NAMESPACE, SHADOW_CLJS, TEMPLATE_EVENTS, TEMPLATE_SUBS):
        if not p.is_file():
            sys.stderr.write(f"error: required file not found at {p}\n")
            return 2

    first_counter_text = _slurp(FIRST_COUNTER)
    entry_namespace_text = _slurp(ENTRY_NAMESPACE)
    shadow_text = _slurp(SHADOW_CLJS)

    problems = find_counter_drift(
        first_counter_text,
        entry_namespace_text,
        _slurp(TEMPLATE_EVENTS),
        _slurp(TEMPLATE_SUBS),
    )
    problems += find_hot_reload_drift(
        (str(SHADOW_CLJS.relative_to(REPO_ROOT)), shadow_text),
        (str(ENTRY_NAMESPACE.relative_to(REPO_ROOT)), entry_namespace_text),
    )
    problems += find_adapter_key_drift(
        str(ENTRY_NAMESPACE.relative_to(REPO_ROOT)), entry_namespace_text
    )

    if verbose:
        print(
            "setup-counter guard: checked counter-keyword containment "
            "(first-counter.md + entry-namespace.md + template), hot-reload "
            "lifecycle wording (shadow-cljs.md + entry-namespace.md), and adapter-key "
            "vocabulary (entry-namespace.md)."
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
        "(uix-adapter/use-subscribe [:counter/value])\n"
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

    # Case D — hot-reload clean: the CORRECT (Camp A) framing.
    good_shadow = (
        "The `:browser` target re-runs the module `:init-fn` after each hot "
        "reload, so no `:after-load` hook is needed; the dispatch-sync seed in "
        "init is the per-reload reset boundary.\n"
    )
    probs = find_hot_reload_drift(("shadow-cljs.md", good_shadow))
    if probs:
        print(f"SELF-TEST FAIL (D hot-reload clean): unexpected {probs}")
        failures += 1

    # Case E — the retired Camp B drift (the one-time-startup framing).
    drift_shadow = (
        "`:init-fn` is a one-time startup hook — it is NOT the hot-reload hook. "
        "A plain code reload does not re-run `:init-fn`; add a `^:dev/after-load` "
        "hook for an explicit re-render.\n"
    )
    probs = find_hot_reload_drift(("shadow-cljs.md", drift_shadow))
    if not any("HOT-RELOAD-LIFECYCLE" in p for p in probs):
        print(f"SELF-TEST FAIL (E hot-reload drift): expected drift, got {probs}")
        failures += 1

    # Case E2 — `:initial-events` must NOT trip the retired-framing patterns
    # (regression pin for the EP-0027 false positive). "the surgical update …
    # does not rerun any `:initial-events`" is correct re-frame2 semantics: the
    # `:?init(?:-fn)?` prefix must match `:init` / `:init-fn` only as a complete
    # token, never as the `:init` prefix of the longer `:initial-events` keyword.
    initial_events_clean = (
        "This explicit `dispatch-sync` is the reset boundary: it re-seeds the "
        "demo state on every hot reload (the surgical update in step 2 does not "
        "rerun any `:initial-events`). Some apps instead seed lazily.\n"
    )
    probs = find_hot_reload_drift(("entry-namespace.md", initial_events_clean))
    if probs:
        print(f"SELF-TEST FAIL (E2 :initial-events false positive): unexpected {probs}")
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
