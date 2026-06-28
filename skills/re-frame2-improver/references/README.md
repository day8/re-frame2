# references/

Anti-pattern catalogue for `re-frame2-improver`. Each leaf is one anti-pattern with: detection rule, symptom example, canonical re-frame2 idiom, suggested rewrite, and a cross-link to the matching leaf under `skills/re-frame2/patterns/` or the relevant `spec/` document.

## Scope

Six anti-patterns resident. The catalogue is narrow — grow it only as new anti-patterns surface across 3+ real review sessions (same discipline as [`re-frame2-pair-retro/references/known-frictions.md`](../../re-frame2-pair-retro/references/known-frictions.md)).

## Catalogue

| # | Leaf | Anti-pattern | Canonical idiom (cross-link) |
|---|---|---|---|
| 1 | [`manual-retry-loops.md`](manual-retry-loops.md) | Hand-rolled HTTP retry — `setTimeout` + counters + manual back-off in handlers | Managed HTTP — [`skills/re-frame2/patterns/managed-http.md`](../../re-frame2/patterns/managed-http.md), [`spec/014-HTTPRequests.md`](../../../spec/014-HTTPRequests.md) |
| 2 | [`boolean-discriminator-subs.md`](boolean-discriminator-subs.md) | 3+ boolean subs (`:*/loading?`, `:*/error?`, `:*/loaded?`) on one path acting as a hand-rolled FSM | Tags query layer — [`skills/re-frame2/references/state-machines/tags.md`](../../re-frame2/references/state-machines/tags.md), [`spec/005-StateMachines.md`](../../../spec/005-StateMachines.md) |
| 3 | [`manual-loading-flags.md`](manual-loading-flags.md) | `(assoc db :*/loading? true)` paired with `dissoc` across multiple terminator handlers | Nine States — [`skills/re-frame2/patterns/nine-states.md`](../../re-frame2/patterns/nine-states.md), [`spec/Pattern-NineStates.md`](../../../spec/Pattern-NineStates.md) |
| 4 | [`schemaless-events.md`](schemaless-events.md) | Boundary handler ingests untrusted payload without production boundary validation — an always-on gate (the `:rf.schema/at-boundary` interceptor ref in metadata `:interceptors`, Managed HTTP `:decode`, or equivalent always-on Malli validator). Dev-only `:schema` / `reg-app-schema` are not sufficient (both elided when `goog.DEBUG` is false). | Schemas at boundaries — [`skills/re-frame2/references/fundamentals/schemas.md`](../../re-frame2/references/fundamentals/schemas.md), [`spec/010-Schemas.md`](../../../spec/010-Schemas.md) |
| 5 | [`imperative-effects.md`](imperative-effects.md) | Direct JS / DOM interop inside `reg-event-*` bodies — both effectful *writes* (`localStorage.setItem`, DOM mutation, inline `dispatch`, timers) and impure *reads* (`Date.now`, `Math.random`, `localStorage.getItem`, sub reads) | Writes → data-only fx via `reg-fx` ([`fx.md`](../../re-frame2/references/fundamentals/fx.md), [`spec/Conventions.md`](../../../spec/Conventions.md)); impure reads fork on *durability*: a host fact that decides a **durable** write (time, generated id, a localStorage value that becomes durable state) folds a recorded fact (declared `:rf/time-ms` / event payload / a **recordable** `reg-cofx`); a **diagnostic / host-transient** read (dev log, perf span) may use an ordinary ambient value-returning `reg-cofx` declared via `:rf.cofx/requires` ([`cofx.md`](../../re-frame2/references/fundamentals/cofx.md); there is no `inject-cofx`) |
| 6 | [`view-side-hook-state.md`](view-side-hook-state.md) | `reagent/atom` (or `useState`) inside a view holding state read by sibling components | Move to `app-db` + `reg-sub` — [`skills/re-frame2/references/fundamentals/subs.md`](../../re-frame2/references/fundamentals/subs.md), [`spec/Principles.md`](../../../spec/Principles.md) |

## Per-leaf format (locked)

Each leaf carries the same five sections:

- **Detection rules** — Greppable signals and structural cues for spotting the anti-pattern in `.cljs` / `.cljc` source.
- **Why it's an anti-pattern** — 2-3 paragraphs on the underlying issue (what invariant breaks, what downstream cost is incurred).
- **The canonical fix** — Cross-reference to the `skills/re-frame2/patterns/` leaf or `spec/` document that documents the idiomatic alternative.
- **Worked example** — Before-and-after CLJS snippets (~10 lines each side).
- **Edge cases** — When the anti-pattern is actually fine (avoids over-eager false-positives during review).

## Growth procedure

When a new anti-pattern surfaces across 3+ review sessions, add it as a new leaf and a new row above (same organic growth as [`re-frame2-pair-retro/references/known-frictions.md`](../../re-frame2-pair-retro/references/known-frictions.md)). Two deferred "bonus" candidates — view renders only the happy state with no error/loading branches; effect handlers writing to a foreign frame's `app-db` — are documented in [`../spec/design.md` §Deferred catalogue candidates](../spec/design.md), held back until they surface in real reviews.

## Shared retro protocol

- [`../../shared/retro-protocol.md`](../../shared/retro-protocol.md) — seven-step diagnosis-first workflow, evidence-citation discipline, layer-routing rules, opt-in issue-filing protocol. Shared into `skills/shared/`; consumed by both this skill and [`re-frame2-pair-retro`](../../re-frame2-pair-retro). The SKILL.md loads it; per-leaf detection rules below assume the protocol is already in scope.

## Cross-references

- [`../SKILL.md`](../SKILL.md) — the skill's top-level entry; describes when this catalogue is consulted.
- [`skills/re-frame2/patterns/`](../../re-frame2/patterns) — the canonical-idiom leaves each anti-pattern routes to.
- [`../spec/design.md`](../spec/design.md) — the design rationale (catalogue shape, the five-section leaf format, the shared-protocol extraction, deferred candidates).
