# references/

Anti-pattern catalogue for `re-frame2-improver`. Each leaf is one anti-pattern with detection rule, symptom example, canonical re-frame2 idiom, suggested rewrite, and a cross-link to the matching leaf under `skills/re-frame2/patterns/` or the relevant `spec/` document.

> **Maintainer index, not a runtime read.** A review routes from [`../SKILL.md` §Routing](../SKILL.md#routing--load-only-the-leaves-whose-signals-appear) straight to the matching leaves — one level deep, per the family's §Leaf size discipline rule in `skills/README.md` — "no SKILL → A → B chains" (monorepo only; not shipped in the package). This page is the human catalogue index: what each leaf covers, the locked leaf format, and where the design rationale lives.

## Catalogue

| # | Leaf | Anti-pattern | Canonical idiom |
|---|---|---|---|
| 1 | [`manual-retry-loops.md`](manual-retry-loops.md) | Hand-rolled HTTP retry — `setTimeout` + counters + back-off in handlers | Managed HTTP — [`managed-http.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/patterns/managed-http.md), [`spec/014`](https://github.com/day8/re-frame2/blob/main/spec/014-HTTPRequests.md) |
| 2 | [`boolean-discriminator-subs.md`](boolean-discriminator-subs.md) | 3+ boolean `?`-subs on one path acting as a hand-rolled FSM | One selector sub over the existing status slice — [`remote-data.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/patterns/remote-data.md); tags query layer once a machine is warranted — [`tags.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/references/state-machines/tags.md), [`spec/005`](https://github.com/day8/re-frame2/blob/main/spec/005-StateMachines.md) |
| 3 | [`manual-loading-flags.md`](manual-loading-flags.md) | `(assoc db :*/loading? true)` + `dissoc` across terminator handlers | RemoteData `:status` slice — [`remote-data.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/patterns/remote-data.md); Nine States once the lifecycle passes one axis — [`nine-states.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/patterns/nine-states.md), [`spec/Pattern-NineStates`](https://github.com/day8/re-frame2/blob/main/spec/Pattern-NineStates.md) |
| 4 | [`schemaless-events.md`](schemaless-events.md) | Boundary handler ingests untrusted payload with no always-on production validator (dev-only `:schema` / `reg-app-schema` don't count) | Schemas at boundaries — [`schemas.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/references/fundamentals/schemas.md), [`spec/010`](https://github.com/day8/re-frame2/blob/main/spec/010-Schemas.md) |
| 5 | [`imperative-effects.md`](imperative-effects.md) | Direct JS / DOM interop in a `reg-event` body — effectful writes AND impure reads | Writes → data-only fx ([`fx.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/references/fundamentals/fx.md)); reads → recorded fact or ambient cofx ([`cofx.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/references/fundamentals/cofx.md)) |
| 6 | [`view-side-hook-state.md`](view-side-hook-state.md) | `reagent/atom` / `useState` holding non-render-local state | `app-db` + `reg-sub` — [`subs.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/references/fundamentals/subs.md), [`spec/Principles`](https://github.com/day8/re-frame2/blob/main/spec/Principles.md) |

## Per-leaf format

Each leaf carries 5 sections — Detection rules / Why it's an anti-pattern / The canonical fix / Worked example / Edge cases — locked in [`../spec/design.md` §L5](../spec/design.md) (`schemaless-events.md` adds a sixth additive "Regression example"). The catalogue grows only when an anti-pattern surfaces across 3+ real reviews; the growth procedure and deferred candidates live in [`../spec/design.md`](../spec/design.md) — not shipped in the package; reach it from a monorepo clone.

## Cross-references

- [`../SKILL.md`](../SKILL.md) — the skill's top-level entry; describes when this catalogue is consulted.
- [`skills/re-frame2/patterns/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2/patterns) — the canonical-idiom leaves each anti-pattern routes to.
- [`../spec/design.md`](../spec/design.md) — the design rationale (catalogue shape, 5-section leaf format, correction contract, growth procedure, deferred candidates). Not shipped in the package; reach it from a monorepo clone.
