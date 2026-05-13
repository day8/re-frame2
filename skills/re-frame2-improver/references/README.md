# references/

Anti-pattern catalogue for `re-frame2-improver`. Each leaf is one anti-pattern with: detection rule, symptom example, canonical re-frame2 idiom, suggested rewrite, and a cross-link to the matching leaf under `skills/re-frame2/patterns/` or the relevant `spec/` document.

## Status — populated (rf2-bquci)

Six launch anti-patterns are now resident. The catalogue is intentionally narrow — grow it as new anti-patterns surface across 3+ real review sessions (same growth discipline as `re-frame-pair-improver2/references/known-frictions.md`).

## Catalogue

| # | Leaf | Anti-pattern | Canonical idiom (cross-link) |
|---|---|---|---|
| 1 | [`manual-retry-loops.md`](manual-retry-loops.md) | Hand-rolled HTTP retry — `setTimeout` + counters + manual back-off in handlers | Managed HTTP — [`skills/re-frame2/patterns/managed-http.md`](../../re-frame2/patterns/managed-http.md), [`spec/014-HTTPRequests.md`](../../../spec/014-HTTPRequests.md) |
| 2 | [`boolean-discriminator-subs.md`](boolean-discriminator-subs.md) | 3+ boolean subs (`:*/loading?`, `:*/error?`, `:*/loaded?`) on one path acting as a hand-rolled FSM | Tags query layer — [`skills/re-frame2/reference/state-machines/tags.md`](../../re-frame2/reference/state-machines/tags.md), [`spec/005-StateMachines.md`](../../../spec/005-StateMachines.md) |
| 3 | [`manual-loading-flags.md`](manual-loading-flags.md) | `(assoc db :*/loading? true)` paired with `dissoc` across multiple terminator handlers | Nine States — [`skills/re-frame2/patterns/nine-states.md`](../../re-frame2/patterns/nine-states.md), [`spec/Pattern-NineStates.md`](../../../spec/Pattern-NineStates.md) |
| 4 | [`schemaless-events.md`](schemaless-events.md) | Boundary handler ingests untrusted payload without `:spec` or `reg-app-schema` for the destination path | Schemas at boundaries — [`skills/re-frame2/reference/fundamentals/schemas.md`](../../re-frame2/reference/fundamentals/schemas.md), [`spec/010-Schemas.md`](../../../spec/010-Schemas.md) |
| 5 | [`imperative-effects.md`](imperative-effects.md) | Direct `js/localStorage` / DOM / `js/console` calls inside `reg-event-*` bodies | Data-only fx via `reg-fx` — [`skills/re-frame2/reference/fundamentals/fx.md`](../../re-frame2/reference/fundamentals/fx.md), [`spec/Conventions.md`](../../../spec/Conventions.md) |
| 6 | [`view-side-hook-state.md`](view-side-hook-state.md) | `reagent/atom` (or `useState`) inside a view holding state read by sibling components | Move to `app-db` + `reg-sub` — [`skills/re-frame2/reference/fundamentals/subs.md`](../../re-frame2/reference/fundamentals/subs.md), [`spec/Principles.md`](../../../spec/Principles.md) |

## Per-leaf format (locked)

Each leaf carries the same five sections:

- **Detection rules** — Greppable signals and structural cues for spotting the anti-pattern in `.cljs` / `.cljc` source.
- **Why it's an anti-pattern** — 2-3 paragraphs on the underlying issue (what invariant breaks, what downstream cost is incurred).
- **The canonical fix** — Cross-reference to the `skills/re-frame2/patterns/` leaf or `spec/` document that documents the idiomatic alternative.
- **Worked example** — Before-and-after CLJS snippets (~10 lines each side).
- **Edge cases** — When the anti-pattern is actually fine (avoids over-eager false-positives during review).

## Growth procedure

When a new anti-pattern surfaces across 3+ review sessions, add it as a new leaf and a new row above. Mirrors how `re-frame-pair-improver2/references/known-frictions.md` grows organically. Anti-patterns flagged in `ai/findings/improver-architecture-20260513-1752.md` §Angle 2 as "bonus" candidates (view renders only happy state; effect handlers writing to foreign frames) are deferred until they surface in real reviews.

## Shared retro protocol (rf2-dhe9v)

- [`../../shared/retro-protocol.md`](../../shared/retro-protocol.md) — seven-step diagnosis-first workflow, evidence-citation discipline, layer-routing rules, opt-in bead protocol. Extracted from `re-frame-pair-retro2`; consumed by both this skill and `re-frame-pair-retro2`. The SKILL.md loads it; per-leaf detection rules below assume the protocol is already in scope.

## Cross-references

- `SKILL.md` — the skill's top-level entry; describes when this catalogue is consulted.
- `skills/re-frame2/patterns/` — the canonical-idiom leaves each anti-pattern routes to.
- `ai/findings/improver-architecture-20260513-1752.md` — the design rationale (rf2-zf7zd).
