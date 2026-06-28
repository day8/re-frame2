# Decision tree — slice, region, or top-level machine?

> **Audience:** authors writing re-frame2 ClojureScript application code.
> **Use when:** you have feature-state to model and need to choose its shape — a key in `app-db` driven by `reg-event`, a region inside an existing `reg-machine`, or a brand-new top-level `reg-machine`.

re-frame2 gives you three places to put state. The right choice usually falls out of one or two questions; this tree walks them in priority order. The four state-machine tells are listed first because if any of them holds, the choice is settled.

> **Mental-model shortcut:** if you can already picture the feature as an xstate machine — distinct states, an `on` transition table, guards gating transitions, maybe an `invoke`d child — that's a strong signal the answer here is **machine**, not slice. xstate is the widely-known FSM model and a good intuition pump for "is this even a machine?" The four tells below formalise that instinct; once you've decided it *is* a machine, [`../references/state-machines/reg-machine.md`](../references/state-machines/reg-machine.md) carries the full xstate→re-frame2 translation key (including where re-frame2 renames or omits xstate slots).

## The three shapes

| Shape | What it is | When it fits |
|---|---|---|
| **Slice** | a key (or sub-tree) inside `app-db`, written by `reg-event` and read by `reg-sub`. No FSM grammar. | A field, a list, a flag, a counter — values that change but whose *legal transitions* don't need enforcement. |
| **Region** | one axis inside an existing `reg-machine` declared with `:type :parallel` and `:regions {...}`. Each region runs its own state-tree. | A sub-concern that is *part of* a larger feature's lifecycle — e.g. a form's submission status inside a screen's load-then-edit lifecycle. |
| **Top-level machine** | a free-standing `reg-machine`. Its snapshot lives at `[:rf.runtime/machines :snapshots <id>]` in the runtime-db partition (framework state, not app-db). | A feature whose answer to "what can happen next?" depends on the current mode, and which is not a sub-concern of another feature. |

## Step 0 — three sanity questions before the tree

Before walking the tree, rule out the cases where there is no state shape to choose.

1. **Is this a pure derivation?** If the value is a function of other values already in `app-db`, default to a `reg-sub`, not a slice — no state shape needed. *But* if the derived value is **part of the application's state** — it must survive SSR hydration / time-travel revert / `app-db` serialisation, OR be read by downstream event handlers, machine actions, schemas, or other derivations as plain `app-db` data — it is a **flow** (Spec 013), not a sub. Flows are rare; default to a sub unless one of those reasons forces the value into `app-db`. See [`../references/fundamentals/flows.md`](../references/fundamentals/flows.md) §Flow vs subscription.
2. **Is this a host-fact input, not state?** A host fact read at dispatch time (current time, a generated id, a random choice) is an input, not a slice. **Which kind depends on where the value lands (EP-0017):** if it decides a **durable** write (app-db, runtime-db, a machine snapshot, a resource, a ledger row, a hydration payload), it must be a **recorded fact** — `:rf.cofx/requires [:rf/time-ms]` for durable time, the **event payload** for a caller-pinned id, or (slice B) a **recordable** generator cofx whose captured value replays — never an ambient host read at the write site (it re-rolls on replay). If purely **diagnostic / host-transient** (a dev log, perf span, a side-table deciding no durable write), an ambient cofx is fine. (Avoid a framework-looking `:rf/now` — `:rf/*` is reserved; durable time is the built-in `:rf/time-ms`.) See [`../references/fundamentals/cofx.md`](../references/fundamentals/cofx.md) §The two grades.
3. **Is this a machine's `:data`?** Per [`spec/005-StateMachines.md` §Naming](../../../spec/005-StateMachines.md), each machine carries its own private `:data` map distinct from `app-db`. Extended state local to a machine lives in `:data`, not in a slice.

If none of those apply, walk Step 1.

## Step 1 — the four machine-tells

If any of these is true, use a state machine. They are listed in dominance order — the first one that fires settles the question.

### Tell 1 — multi-step async with phase-distinct transitions

Does the feature pass through ≥3 phases each with their own allowed-next transitions, and do those phases each spawn async work that the next phase depends on?

Examples:

- A WebSocket connection cycling `:disconnected → :connecting → :authenticating → :connected → :reconnecting → :failed`. Each phase has distinct entry actions (open socket / send auth / subscribe to topics / wait on backoff) and distinct allowed events.
- An app boot that reads config, then authenticates, then loads profile, then resolves the route. Each step depends on the previous; failure semantics are phase-specific.
- A login flow whose `:submitting` state spawns an HTTP `:spawn` whose success transitions to `:authed` and whose failure transitions to `:incorrect` with a retry counter.

If yes → **machine**. A slice can't enforce "you can't fire `:auth-ok` while in `:disconnected`"; a machine's transition table does.

### Tell 2 — cancellation cascade matters

When state moves on, must in-flight work on the prior state be implicitly cancelled (or its replies silently suppressed)?

Examples:

- A search-as-you-type whose previous request's reply must be discarded when a newer keystroke launched another request.
- A WebSocket reconnect-backoff timer that must not fire after the user manually re-connected.
- A long-running batch job whose mid-flight chunks must stop processing when the user navigates away.

If yes → **machine**. Machine snapshots advance `:rf/after-epoch` on every state entry; in-flight `:after` timers and `:spawn` replies carry the captured epoch and are dropped on mismatch (per [`spec/Pattern-StaleDetection.md`](../../../spec/Pattern-StaleDetection.md) and [`spec/005-StateMachines.md` §Epoch-based stale detection](../../../spec/005-StateMachines.md)). A slice cannot express the cancellation cascade without re-implementing the epoch idiom by hand — at which point you have built a machine in disguise.

### Tell 3 — terminal-state matters

Does the feature have a state from which no further transitions are legal — `:archived`, `:locked-out`, `:cancelled`, `:done` — and does the UI need to render that state distinctly from "active but idle"?

Examples:

- An auth flow with a `:locked-out` state after N failed attempts; the UI must refuse to render the login form.
- An invoice machine where `:paid` is terminal — no further edits allowed, even by the framework.
- A read-only archived document whose `:archived` state forbids every editing event.

If yes → **machine**. The `:tags` projection on a machine snapshot (`:auth/locked-out`, `:document/archived`) lets every view query the terminal status with a single sub; a slice with an `:status :archived` keyword has to be re-checked at every event handler.

### Tell 4 — orthogonal axes

Does the feature have ≥2 independent state axes that evolve concurrently — say, data-lifecycle (loading/loaded/error) *and* form-validity (neutral/correct/incorrect) *and* mode (active/done)?

Examples:

- The NineStates pattern is the canonical case: three orthogonal axes (data / form / mode) modelled as three parallel regions.
- A page that shows live WebSocket updates *and* lets the user edit a draft *and* tracks whether the page is in read-only review mode.

If yes → **machine with `:type :parallel` and `:regions`**. Three axes of three states each shrink from 27 flat states to 9 states across 3 regions. Modelling orthogonal axes as a single flat enum is the AND-state explosion; the machine grammar is the answer.

If none of the four tells fire, the feature is **slice-shaped**. Default to slice unless one of the four holds.

## Step 2 — if it's a machine, is it top-level or a region?

This question only applies after Step 1 picked machine.

Ask: *is this lifecycle a sub-concern of a larger feature's lifecycle, or is it a feature on its own?*

- **Region** — the lifecycle is one axis of a larger feature. A form's submission status (`:idle → :submitting → :submitted | :error`) inside a screen's load-then-edit lifecycle. A page's "data" axis inside a NineStates page. The form is a region of the screen; not its own top-level machine.
- **Top-level machine** — the lifecycle has its own identity and is shared across features. The auth machine. The WebSocket connection. The boot machine. The router. These are not "part of" another machine; they are sibling concerns.

Rule of thumb: if removing the lifecycle would gut the parent feature, it is a region. If removing the lifecycle would simply remove a sibling concern, it is top-level.

## Step 3 — if it's a slice, where does it live in `app-db`?

This question only applies after Step 1 picked slice.

- **Pattern-slice?** If the slice is the canonical shape of a named pattern (RemoteData's 5-key slice; Forms' 7-key slice), use the pattern's canonical path convention. Pattern leaves name the slot.
- **Feature-prefix?** Otherwise, the slice belongs at `[:<feature-prefix> ...]` per [`spec/Conventions.md` §Feature-modularity prefix convention](../../../spec/Conventions.md). Pick a feature keyword for the app's namespace; never start with `:rf/` (reserved).
- **Schema?** Register a schema for the slice via `reg-app-schema` only if the slice crosses a trust boundary (incoming HTTP payload, persisted state on restore). Don't schema-fence every internal key (per SKILL.md cardinal rule 4).

## Step 4 — the four tells, restated as a worked checklist

Run through the four tells in order and answer yes/no for the feature at hand. The first yes settles the question.

1. **Multi-step async with phase-distinct transitions?** → machine.
2. **Cancellation cascade matters?** → machine.
3. **Terminal-state matters?** → machine.
4. **Orthogonal axes?** → parallel-region machine.
5. None of the above? → slice.

If you're between slice and machine and none of the four tells clearly fire, the prompt's *language* is the tie-breaker:

- "Transitions", "modes", "phases", "lifecycle", "can't happen during X", "while connecting", "after submit" → machine.
- "Field", "value", "filter", "counter", "flag", "current selection", "this list" → slice.

## Step 5 — verify against the implementation

Implementation is ground truth (SKILL.md §1). After picking a shape, cross-check against a reference declaration that uses the same shape — match it rather than re-deriving:

- A machine? — match a reference machine using state + tags + (where relevant) parallel regions or managed-HTTP. (Repo: `examples/core/login/` for machine + tags + managed-HTTP, `examples/capabilities/machines/state_machine_walkthrough/` for the chapter as runnable code, `examples/patterns/nine_states/` for parallel regions.)
- A slice? — match a reference slice. (Repo: `examples/core/counter/` for the smallest slice, `examples/core/todomvc/` for a list-of-items slice with editing and filters, the 7GUIs cluster.)

If a reference contradicts the leaf, **the reference wins** — and if it's the spec disagreeing with `implementation/**`, report the divergence upstream as a `day8/re-frame2` GitHub issue.

## Cross-references

- [`pick-a-pattern.md`](pick-a-pattern.md) — pattern choice (orthogonal to state-shape choice).
- [`../references/state-machines/reg-machine.md`](../references/state-machines/reg-machine.md) — how to author `reg-machine` (states / initial / guards / actions).
- [`../references/state-machines/regions.md`](../references/state-machines/regions.md) — single-region and `:type :parallel` regions.
- [`../references/state-machines/tags.md`](../references/state-machines/tags.md) — `:tags` on states + `machine-has-tag?` query.
- [`../references/state-machines/spawn.md`](../references/state-machines/spawn.md) — `:spawn` and `:spawn-all` for child machines.
- [`../references/state-machines/history.md`](../references/state-machines/history.md) — `:type :history` re-entry (shallow / deep / default).
- [`../references/state-machines/cancellation.md`](../references/state-machines/cancellation.md) — the actor-destroy cascade.
- [`../references/fundamentals/events.md`](../references/fundamentals/events.md) — `reg-event` for slice-shaped state.
- [`../references/fundamentals/schemas.md`](../references/fundamentals/schemas.md) — `reg-app-schema` at boundaries.
- [`../examples-map.md`](../examples-map.md) — example-app index for shape-verification.

---

*Derived from EP — State machines (005) and the slice-vs-machine reasoning in `spec/Pattern-RemoteData.md` / `spec/Pattern-Forms.md` / `spec/Pattern-NineStates.md` @ main `89bd9c3`.*
