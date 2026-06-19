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
    Frames.md §reg-frame metadata grammar; spec/API.md §Error-emit; spec/009-
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

All three rules are written to fire ONLY on the stale ASSERTION shape, never on the
corrected wording that states the negation — and never on the unrelated,
still-live `:on-error` *surfaces* (a machine `:spawn :on-error` transition, a
route `:on-error` lifecycle event), which are NOT frame-level recovery policies.
The skill legitimately mentions all of those in their corrected forms.

Scan surface: the user-facing migration-skill leaves (SKILL.md + references/*.md)
PLUS the migration corpus the skill treats as source of truth
(migration/from-re-frame-v1/README.md), since the review found the same drift in
both. The skill's `spec/` re-authoring meta-docs are out of scope (not loaded
during normal operation).

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


def _scanned_files() -> list[Path]:
    """User-facing migration-skill leaves (SKILL.md + references/*.md), globbed so
    a new reference leaf is covered automatically, plus the migration corpus the
    skill treats as source of truth. The skill's spec/ meta-docs are excluded
    (re-authoring material, not loaded during normal operation)."""
    files = [SKILL_DIR / "SKILL.md"]
    files.extend(sorted((SKILL_DIR / "references").glob("*.md")))
    files.append(MIGRATION_MD)
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

    if verbose:
        print(
            f"migration M-11/M-13 contract-drift guard: scanned {len(files)} "
            f"files ({lines_checked} lines)."
        )

    if not problems:
        if verbose:
            print(
                "contract-drift: no plain-fn-inherits-frame (M-11) or "
                "moved-to-frame-level-:on-error (M-13) drift found."
            )
        return 0

    err_prefix = "::error::" if ci else ""
    for p in problems:
        print(f"{err_prefix}{p}")
    print(
        f"\ncontract-drift: {len(problems)} drift issue(s) — the migration skill "
        "is the agent's source of truth for what breaks; align M-11 (plain fns "
        "DON'T inherit the provider frame) and M-13 (no frame-level `:on-error` "
        "recovery policy) to the shipped spec / runtime contract."
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
