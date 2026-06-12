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

27 evals — **19 positives** (skill should fire) and **8 negatives** (skill
should stay quiet). **11 positives** carry the Layer-2 answer-quality
`expectations[]`; they target the prompts whose answer drifts fastest as
the Xray UI moves:

| ID | Name | Layer 2? | What the answer-quality assertions pin |
|---:|---|:---:|---|
| 1 | `launch-default` | yes | True-inline (preload + `[data-rf-xray-host]`) is the canonical launch; overlay is NOT the default. |
| 2 | `launch-popout` | yes | `(xray/popout!)` with call-parens; the visible `⛶` button is the canonical chrome path; no wired pop-out hotkey. |
| 27 | `launch-popout-button` | yes | YES there is a visible `⛶` pop-out button — not programmatic-only, not an invented right-click path. |
| 3 | `launch-programmatic` | yes | `init!` installs but does NOT open; a mount verb is still required; runs after `rf/init!`. |
| 8 | `panel-route-machine-canvas` | yes | Full topology → Static Machines tab, not the event-driven Dynamic Machine tab; no standalone Machines-Canvas tab. |
| 11 | `panel-route-schema` | yes | Schema violations → Epoch inline + L2 pink-wash; registry → Static Schemas; no Issues tab, no Dynamic Schemas tab. |
| 12 | `panel-route-hydration` | yes | No standalone Hydration tab; mismatches → Epoch inline + issues-ribbon signal. |
| 21 | `chrome-rewind` | yes | Passive inspect (app-db unmoved) vs explicit `Reset` button (restore-epoch); `r`/`R`/`*` are NOT wired. |
| 23 | `chrome-palette` | yes | Palette source kinds + representative command verbs; `Cmd/Ctrl+K` is wired (not struck). |
| 24 | `launch-overlay` | yes | `open-overlay!` is the supported FALLBACK for no-layout-host; floats above `document.body`; not the primary path. |
| 26 | `config-init-vs-settings` | yes | Settings popup wins over the `init!` boot default; merge order `defaults < configure! < Settings`; density is NOT a popup control. |
| 4, 5, 6, 7, 9, 10, 22, 25 | `launch-hotkey` … `config-init-boot` | no | Trigger-only positives (lower drift; covered by the body's quick-reference). |
| 13–20 | `neg-*` | no | Negatives — adjacent surfaces (drive→pair, implement→spec, author→re-frame2, setup, migration, implementor, vocab-only). |

The Layer-2 set is exactly the high-drift list the bead called for:
launch-default, launch-overlay, launch-popout-button, launch-programmatic,
config-init-vs-settings, chrome-rewind, chrome-palette,
panel-route-machine-canvas, panel-route-schema, panel-route-hydration —
plus launch-popout (the paired programmatic counterpart to the button
prompt).

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

> Note: the repo's `scripts/check_skill_eval_docs.py` drift gate is wired to
> the `skills/re-frame2/` harness specifically; this README has no
> automated count/coverage gate, so the table above is maintained by hand —
> keep it in step with `evals.json` when you add or rename an entry.
