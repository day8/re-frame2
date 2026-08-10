# `re-frame2-xray` skill — eval harness

This directory holds the evaluation fixtures for the `re-frame2-xray` tour
skill (`skills/re-frame2-xray/SKILL.md` and its reference leaves). The
fixtures gate two things at once:

1. **Trigger accuracy** — the skill fires on Xray launch / tab-routing /
   chrome prompts (positives) and stays quiet on adjacent surfaces
   (negatives). This is the `skill-creator` description-optimisation
   signal.
2. **Answer quality** — for the highest-drift Xray facts, the skill's
   answer names the *currently shipped* control and rejects stale
   guidance. Trigger-only evals can prove the skill fired; they cannot
   prove the answer is still correct as the Xray UI churns. The
   `expectations[]` layer is the guardrail that closes that gap.

## Two-layer fixture shape

A single `evals.json` holds the eval list. Per Anthropic's `skill-creator`
schema ([SKILL.md](https://github.com/anthropics/skills/blob/main/skills/skill-creator/SKILL.md),
[schemas.md](https://github.com/anthropics/skills/blob/main/skills/skill-creator/references/schemas.md)),
each entry has an `id`, a kebab-case `name`, and a `prompt`. On top of that
base, this harness uses two layers:

- **Layer 1 — trigger (every entry).** `should_trigger` (`true` /
  `false`) drives the description-optimisation loop's train/held-out
  scoring. Negatives also carry a `rationale` naming the sibling skill
  that *should* own the prompt.
- **Layer 2 — answer quality (high-drift positives only).** The positives
  that carry volatile UI facts also carry:
  - `expected_output` — a human-readable description of a correct answer
    (not parsed; makes manual review fast).
  - `expectations[]` — objectively gradeable statements. Each is checked
    against the run's answer; this is the pass/fail signal. They mix
    **positive** assertions (name the right control) with **negative**
    assertions (reject the stale one).

`schema_version` is `"2"` (the `"1"` fixture was trigger-only). Bump it
when the eval shape changes in a way that breaks readers.

## Coverage

30 evals, covering Xray's trigger surface and answer quality: **22 positives**
(skill should fire) and **8 negatives** (skill should stay quiet). **14
positives** carry the Layer-2 answer-quality `expectations[]`; they target the
prompts whose answer drifts fastest as the Xray UI moves:

| ID | Name | Layer 2? | What the answer-quality assertions pin |
|---:|---|:---:|---|
| 1 | `launch-default` | yes | True-inline (preload + `[data-rf-xray-host]`) is the canonical launch; overlay is NOT the default. |
| 2 | `launch-popout` | yes | `(xray/popout!)` with call-parens; the visible `⛶` button is the canonical chrome path; no wired pop-out hotkey. |
| 27 | `launch-popout-button` | yes | YES there is a visible `⛶` pop-out button — not programmatic-only, not an invented right-click path. |
| 3 | `launch-programmatic` | yes | `init!` installs but does NOT open; a mount verb is still required; runs after `rf/init!`. |
| 8 | `panel-route-machine-canvas` | yes | Full topology → Static Machines tab, not the event-driven Dynamic Machine tab; no standalone Machines-Canvas tab. |
| 11 | `panel-route-schema` | yes | Schema violations → Epoch inline + L2 pink-wash; registry → Static Schemas; no Issues tab, no Dynamic Schemas tab. |
| 12 | `panel-route-hydration` | yes | No standalone Hydration tab; mismatches → Epoch inline + issues-ribbon signal. |
| 21 | `chrome-rewind` | yes | Passive inspect (live frame unmoved) vs explicit `Reset` button (`restore-epoch!` reinstalls the whole frame-state — both app-db and runtime-db — not just `:db-after`); `r`/`R`/`*` are NOT wired. |
| 23 | `chrome-palette` | yes | Palette source kinds + representative command verbs; `Cmd/Ctrl+K` is wired (not struck). |
| 24 | `launch-overlay` | yes | `open-overlay!` is the supported FALLBACK for no-layout-host; floats above `document.body`; not the primary path. |
| 26 | `config-init-vs-settings` | yes | Settings popup wins over the `init!` boot default; merge order `defaults < configure! < Settings`; density is NOT a popup control. |
| 28 | `panel-route-frames` | yes | The which-image-loaded-which-frame / how-a-frame-resolves question → the Dynamic **Frames** tab (internal id `:module-view`, EP-0023 image→frame lens, no realm/app/module browse dimension); Frames is SHIPPED, not absent, not Static, not the same as Graph; no `mount-module-view!` (L4-only). |
| 29 | `tab-inventory-count` | yes | The full ordered **9-tab** Dynamic list incl. Frames (count is 9, not 8); correct `:order`; no removed tab (Issues / Event / Chrome A11y / Machines-Canvas). |
| 30 | `graph-projection-vs-static-mode` | yes | The Graph tab's registration-derived view is its OWN per-panel projection toggle (Declared/Realized; shipped-labelled static/live), NOT the L1 Static mode pill; Graph is a Dynamic tab, so Static mode does not show it at all. |
| 4, 5, 6, 7, 9, 10, 22, 25 | `launch-hotkey` … `config-init-boot` | no | Trigger-only positives (lower drift; covered by the body's quick-reference). |
| 13–20 | `neg-*` | no | Negatives — adjacent surfaces (drive→pair, implement→spec, author→re-frame2, setup, migration, implementor, vocab-only). |

The Layer-2 set is exactly the high-drift list the bead called for:
launch-default, launch-overlay, launch-popout-button, launch-programmatic,
config-init-vs-settings, chrome-rewind, chrome-palette,
panel-route-machine-canvas, panel-route-schema, panel-route-hydration —
plus launch-popout (the paired programmatic counterpart to the button
prompt), the tab-inventory pair **panel-route-frames** +
**tab-inventory-count** that pin the 9-tab Dynamic surface (incl. Frames)
so a future drop / misroute of Frames, or a revert to 8 tabs, fails the
answer-quality layer, and **graph-projection-vs-static-mode** that pins the
Graph tab's per-panel projection toggle (Declared/Realized) as distinct from
the L1 Static mode pill, so the overloaded `static`/`mode` vocabulary cannot
re-route a user to the wrong control.

## How to run

There is **no scripted runner** in-tree for these — the answer-quality
layer needs a live Claude session, so the harness is manual, following the
skill-creator workflow ([SKILL.md §"Running and evaluating test
cases"](https://github.com/anthropics/skills/blob/main/skills/skill-creator/SKILL.md)).
The short version:

1. **Trigger layer (every entry).** Run the description-optimisation loop /
   the skill-creator scorer over `evals.json` to confirm the positives fire
   and the negatives stay quiet. `should_trigger` is the only field that
   layer reads.
2. **Answer-quality layer (the Layer-2 entries).** For each entry that
   carries `expectations[]`, spawn a fresh Claude session with
   `skills/re-frame2-xray/SKILL.md` loaded, send the `prompt`, and capture
   the answer + the tool-call transcript (which leaves it read).
3. **Grade.** Check each `expectations[]` statement against the captured
   answer — pass / fail with one-line evidence. Most are grep-able for the
   load-bearing token (e.g. `Reset`, `open-overlay!`, `Static`, the `⛶`
   button); the negative assertions need a short read to confirm the stale
   control is *absent*.
4. **Act on failures.** A failing answer-quality assertion almost always
   means the **skill drifted behind the shipped Xray UI**, not that the
   assertion is wrong — re-verify against the current
   `tools/xray/src/.../` + `tools/xray/spec/*`, fix the skill body or the
   matching reference leaf, and re-run. Only weaken an assertion if the
   *product* contract genuinely changed (then update both the skill and the
   assertion in the same PR).

The harness is intentionally tool-agnostic — `evals.json` is just data, so
any runner that respects the schema works.

## Keeping evals + skill in sync

Whenever an Xray UI change lands that touches one of the high-drift facts
(a launch verb, a chrome control, a tab route), update the skill body / the
matching reference leaf **and** the corresponding `expectations[]` in the
same PR. The Layer-2 assertions are the contract that the tour skill keeps
describing the *shipped* Xray, not a stale snapshot — they are only as good
as the discipline of updating them alongside the product.

> Note: the repo's `scripts/check_skill_eval_docs.py` drift gate covers this
> harness (it is multi-target — `re-frame2`, `re-frame2-improver`, and
> `re-frame2-xray`). For this README it asserts the total-count sentence ("…
> evals, covering …"), set-equality between the Layer-2 coverage table above
> and the `expectations[]` evals in `evals.json`, and the per-`should_trigger`
> positive/negative tally — all read from the single count sentence at the top
> of §Coverage, so state each count there exactly once. The trigger-only
> positives and the negatives are listed in collapsed multi-id rows the gate
> does not parse, so keep those rows in step with `evals.json` by hand when you
> add or rename such an entry.

## Keeping the tab inventory in sync (the source of truth)

The tour skill's Dynamic tab inventory (the `e a v t m r s g u h` set across
`SKILL.md`, `README.md`, `references/panels.md`,
`references/shared-components.md`, `package.json`, and
`.claude-plugin/plugin.json`) is checked against the live Xray registry by
`scripts/check_skill_xray_tab_inventory_drift.py` — a structural drift gate
that parses the shipped `reg-l4-tab!` metadata and **fails if any Dynamic
tab's `:id` / `:label` / `:mnem` / `:order` diverges from the skill + eval
inventory** (it also fails a retired label — e.g. the old "Modules" — leaking
back into user-facing prose). Run it whenever you touch the inventory. When
an Xray tab is added, removed, or reordered, re-verify the skill against
the **single source of truth** in this order:

1. **`tools/xray/src/day8/re_frame2_xray/focus.cljc` — `valid-panels`.**
   This `#{…}` set MIRRORS the live `panel-registry/tab-ids-for-mode
   :dynamic` registry (a cross-check test, `registry_cljs_test.cljs`,
   fails the Xray build if they drift) and is the JVM-runnable, single
   authoritative count. Today it is
   `#{:epoch :app-db :views :trace :machines :routing :resources
   :derivation-graph :module-view}` — **9 tabs.** The internal ids map to
   display labels: `:views`→Views, `:routing`→Routes,
   `:derivation-graph`→Graph, `:module-view`→Frames.
2. **The per-panel `reg-l4-tab!` calls** under
   `tools/xray/src/day8/re_frame2_xray/panels/*.cljs` — confirm each tab's
   `:label`, `:mnem`, and `:order` (e.g. `module_view.cljs` →
   `{:id :module-view :label "Frames" :mnem "u" :order 9}`). An L4-only
   tab (Graph, Frames) registers via `reg-l4-tab!` but is **not** in
   `panel-enum` (it has no standalone `mount-*!` facade).
3. **`tools/xray/spec/API.md`** (the §Public surfaces table + §Panel
   reg-views) — the normative published surface, which also enumerates
   which tabs are standalone-mountable vs L4-only registry tabs.

A quick CLI cross-check (count the skill's claimed tabs vs the source):

```bash
# The authoritative set (one id per shipped Dynamic L4 tab):
grep -A2 'def valid-panels' tools/xray/src/day8/re_frame2_xray/focus.cljc

# Where the skill states the count — keep every hit at the same number:
grep -rnE '[0-9]+ Dynamic|[0-9]+ lenses|[0-9]+ tabs|e a v t m r s g' \
  skills/re-frame2-xray/
```

If the source moves and the skill doesn't,
`scripts/check_skill_xray_tab_inventory_drift.py` fails on the structural
`:id` / `:label` / `:mnem` / `:order` axis (in CI, without a live model), and
the Layer-2 `tab-inventory-count` + `panel-route-frames` evals catch answer
drift on the next answer-quality run. Update the skill body, the matching
reference leaf, and the corresponding `expectations[]` in the same PR (per
§Keeping evals + skill in sync above).
