# references/

Anti-pattern catalogue for `re-frame2-improver`. Each leaf is one anti-pattern with detection rule, symptom example, canonical re-frame2 idiom, suggested rewrite, and a cross-link to the matching leaf under `skills/re-frame2/patterns/` or the relevant `spec/` document.

## Catalogue

| # | Leaf | Anti-pattern | Canonical idiom |
|---|---|---|---|
| 1 | [`manual-retry-loops.md`](manual-retry-loops.md) | Hand-rolled HTTP retry — `setTimeout` + counters + back-off in handlers | Managed HTTP — [`managed-http.md`](../../re-frame2/patterns/managed-http.md), [`spec/014`](../../../spec/014-HTTPRequests.md) |
| 2 | [`boolean-discriminator-subs.md`](boolean-discriminator-subs.md) | 3+ boolean `?`-subs on one path acting as a hand-rolled FSM | Tags query layer — [`tags.md`](../../re-frame2/references/state-machines/tags.md), [`spec/005`](../../../spec/005-StateMachines.md) |
| 3 | [`manual-loading-flags.md`](manual-loading-flags.md) | `(assoc db :*/loading? true)` + `dissoc` across terminator handlers | Nine States — [`nine-states.md`](../../re-frame2/patterns/nine-states.md), [`spec/Pattern-NineStates`](../../../spec/Pattern-NineStates.md) |
| 4 | [`schemaless-events.md`](schemaless-events.md) | Boundary handler ingests untrusted payload with no always-on production validator (dev-only `:schema` / `reg-app-schema` don't count) | Schemas at boundaries — [`schemas.md`](../../re-frame2/references/fundamentals/schemas.md), [`spec/010`](../../../spec/010-Schemas.md) |
| 5 | [`imperative-effects.md`](imperative-effects.md) | Direct JS / DOM interop in a `reg-event` body — effectful writes AND impure reads | Writes → data-only fx ([`fx.md`](../../re-frame2/references/fundamentals/fx.md)); reads → recorded fact or ambient cofx ([`cofx.md`](../../re-frame2/references/fundamentals/cofx.md)) |
| 6 | [`view-side-hook-state.md`](view-side-hook-state.md) | `reagent/atom` / `useState` holding non-render-local state | `app-db` + `reg-sub` — [`subs.md`](../../re-frame2/references/fundamentals/subs.md), [`spec/Principles`](../../../spec/Principles.md) |

## Routing — load only the leaves whose signals appear

A typical trigger is a short pasted snippet. Consult the signals below and open **only** the leaves whose signals plausibly match the in-scope code (usually 1–3, not all six); each leaf carries the full detection rules. When one leaf matches, load its co-occurring leaf too.

| Leaf | Load when the source shows | Co-occurs with |
|---|---|---|
| `manual-retry-loops.md` | `setTimeout` + `dispatch` together; a `:*/retries` / `:*/attempts` counter; inline `Math.pow` back-off; a failure branch re-dispatching the originating id | `imperative-effects.md` (HTTP write) |
| `boolean-discriminator-subs.md` | 3+ `?`-suffixed subs on one `app-db` path; a view `cond` over multiple sub derefs | `manual-loading-flags.md` |
| `manual-loading-flags.md` | `(assoc db :*/loading? true)` paired with `dissoc`; `:*/loading?` / `:*/saving?` / `:*/in-flight?` keys | `boolean-discriminator-subs.md` |
| `schemaless-events.md` | handler reads `:rf/reply` / `:body` / `:data`, or `js/localStorage` / `location.search` / `postMessage`; boundary event ids `:*/loaded` / `:*/received` / `:*/rehydrated` | — |
| `imperative-effects.md` | `.setItem` / DOM `set!` / `js/setTimeout` / inline `rf/dispatch`; `js/Date.now` / `Math.random` / `.getItem`; `@(rf/subscribe …)` in a handler body | `manual-retry-loops.md` (HTTP write) |
| `view-side-hook-state.md` | `(r/atom …)` / `reagent/atom` at a view or namespace top; `use-state` / `useReducer`; an event handler derefing a view-ns atom | — |

**Consolidate co-occurring findings that share one refactor.** When two leaves match the same code and resolve to the *same* canonical shape — most often `manual-loading-flags.md` + `boolean-discriminator-subs.md` on one screen, both replaced by the *same* Nine States / tags machine, or an HTTP-shaped `imperative-effects.md` write that collapses into the `manual-retry-loops.md` Managed-HTTP fix — name each diagnosis but fold their rewrites into **one** consolidated fix. Independent rewrites for the same machine contradict each other.

## Per-leaf format

Each leaf carries five sections — Detection rules / Why it's an anti-pattern / The canonical fix / Worked example / Edge cases — locked in [`../spec/design.md` §L5](../spec/design.md) (`schemaless-events.md` adds a sixth additive "Regression example"). The catalogue grows only when an anti-pattern surfaces across 3+ real reviews; the growth procedure and deferred candidates live in [`../spec/design.md`](../spec/design.md).

## Shared retro protocol

- [`../../shared/retro-protocol.md`](../../shared/retro-protocol.md) — seven-step diagnosis-first workflow, evidence-citation discipline, layer-routing rules, opt-in issue-filing protocol. The SKILL.md loads it; per-leaf detection rules assume it is already in scope.

## Cross-references

- [`../SKILL.md`](../SKILL.md) — the skill's top-level entry; describes when this catalogue is consulted.
- [`skills/re-frame2/patterns/`](../../re-frame2/patterns) — the canonical-idiom leaves each anti-pattern routes to.
- [`../spec/design.md`](../spec/design.md) — the design rationale (catalogue shape, five-section leaf format, shared-protocol extraction, growth procedure, deferred candidates).
