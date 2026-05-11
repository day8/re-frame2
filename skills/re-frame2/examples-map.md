# Examples map — when to point at which `examples/reagent/<x>/`

> **Audience:** authors writing re-frame2 ClojureScript application code.
> **Use when:** a task lands on a pattern or a primitive whose shape needs to be cross-checked against a known-shipping worked example.

re-frame2's examples are the canonical authoring substrate — per SKILL.md cardinal rule 5, when an example exists for a pattern, **prefer the example's shape over a synthesised one**. The examples reflect the implementation as currently shipped; the spec/EP docs describe *why*, and the example describes *what*.

This file is a one-paragraph-per-example index. It names what each example demonstrates and when to point at it during an authoring task. It deliberately does **not** explain the example's internals — read the source for that. Each paragraph names the *patterns and primitives* the example exercises so a routing decision (e.g. "I want to verify the Pattern-NineStates shape") lands on the right directory in one hop.

The full catalogue (with maturity, build ids, and end-to-end Playwright coverage) lives at [`examples/README.md`](../../examples/README.md). The substrate policy (Reagent is canonical; UIx and Helix ship a smoke-pair) lives at [`spec/Conventions.md` §Adapter shipping convention](../../spec/Conventions.md).

## counter — `examples/reagent/counter/`

The smallest possible re-frame2 app. One `reg-event-db`, one `reg-sub`, one `reg-view` Var, an `:initial-fx` boot dispatch, and a single click. Point at this example when authoring the first event/sub/view of a greenfield feature, when verifying the canonical macro-shapes (`reg-event-db`, `reg-sub`, `reg-view` Form-1 with a Var reference), or when checking the minimum-viable `app-db` schema attachment. Exercises 002 Frames and 004 Views. The pedagogical "hello world" — its shape sets the bar for every other example.

## counter_slim_and_fast — `examples/reagent/counter_slim_and_fast/`

The counter dataflow mounted on the slim Reagent rewrite (`day8/reagent-slim`) — the `reagent2.*` substrate that excludes `reagent.impl.*` and `react-dom/server`. Same six-domino dataflow as `counter/`, but every Reagent import points at `reagent2.*` and `(rf/init!)` takes the slim adapter Var. Point at this example only when the task is about substrate-swap, the bundle-isolation contract (the `check-counter-slim-and-fast.cjs` script in CI), or proving that the slim adapter is API-shape-compatible with the stock Reagent adapter. Not a teaching example — it is the live bundle-comparison target for the slim epic.

## counter_with_stories — `examples/reagent/counter_with_stories/`

The canonical worked example for the Story epic (`tools/story/`, the `day8/re-frame2-story` artefact). The counter with seven `reg-*` Story macros wired end-to-end — `reg-tag`, `reg-mode`, `reg-decorator`, `reg-story-panel`, `reg-story`, `reg-variant`, `reg-workspace` — four variants exercising three of the seven canonical `:rf.assert/*` events plus the built-in `force-fx-stub` decorator. URL-hash-routed: `#/` renders the live counter; `#/stories` mounts the Story playground shell. Point at this example when authoring any Story-substrate code: tag definitions, modes, decorators, variants, workspaces, or the `:rf.assert/*` family. The Stage 8 worked example for the Story epic; exercises 007 Stories, 002 Frames, and 008 Testing.

## boot — `examples/reagent/boot/`

The canonical Pattern-Boot worked example — a four-state `:app/boot` machine (`:configuring → :loading-deps → :hydrating → :ready`, plus terminal `:failed`) that drives the initialisation graph. `:configuring` `:invoke`s one reusable `:boot/loader` child for `/config`; `:loading-deps` fans out THREE parallel `:boot/loader` children via `:invoke-all` for routes / flags / user; `:hydrating` applies the staged payloads to top-level app-db slices via one consolidated `:enter-hydrating` action and self-transitions to `:ready`. Point at this example when authoring a multi-step boot sequence, when verifying the canonical `:invoke` + `:invoke-all` composition, or when wiring boundary schemas for hydration payloads. Exercises Pattern-Boot, 005 StateMachines, and 010 Schemas. The single-purpose narrower instance lives at `examples/reagent/login/`.

## login — `examples/reagent/login/`

The single-feature scaffold: everything a typical login flow needs, in one file. Events + subs + views + a state machine + a managed-HTTP demo stub + a Malli machine-snapshot schema + a browserless headless test. Point at this example when authoring **any** feature that combines a state machine with HTTP, or when verifying the shapes of `:auth/busy`, `:auth/authenticated`, and other `:tags`-based view queries (the canonical replacement for boolean-discriminator subs). The canonical home of Pattern-Forms; also the canonical CP-5 / CP-6 / CP-8 worked example. Exercises 005 StateMachines, 014 HTTPRequests, 010 Schemas, and 008 Testing. If you only read one machine-based example, read this one.

## managed_http_counter — `examples/reagent/managed_http_counter/`

A compact Spec 014 demo — a counter where each button issues a `:rf.http/managed` request: success, 4xx failure, retry-recover (canned-stub), and abort. Includes a tiny `/api/` directory served as canned JSON so the example runs without a backend. Point at this example when verifying the canonical shape of an `:rf.http/managed` call, the eight-category `:rf.http/*` failure taxonomy, the retry-with-backoff configuration, the abort-token wiring, or the encode/decode pipeline. The compact, single-feature complement to RealWorld for Spec 014; the canonical Pattern-ManagedHTTP example. Exercises 014 HTTPRequests and Pattern-AsyncEffect.

## nine_states — `examples/reagent/nine_states/`

The nine canonical UI states (Nothing / Loading / Empty / One / Some / Too Many / Incorrect / Correct / Done) for a single domain. One parallel-region machine with three orthogonal regions (data / form / mode), state tags on every state, a render-priority selector sub, and a single `case` in the root view. Pedagogically exhaustive — exercises every machine grammar concept (parallel regions, tags, guards, actions, `:always`, `:after`) inside one focused example. Point at this example when authoring **any** parallel-region machine, or when designing a page that needs to render every legal lifecycle state distinctly. The canonical Pattern-NineStates example; the worked reference for parallel-region tagging and render-priority collapsing. Exercises Pattern-NineStates, Pattern-RemoteData, Pattern-Forms, and 005 StateMachines.

## realworld — `examples/reagent/realworld/`

The de-facto cross-framework benchmark — [RealWorld (Conduit)](https://github.com/gothinkster/realworld). The widest API-surface example in the repo: auth, feeds, routing, comments, editor, profile, favorites, settings, and SSR-hydration glue all sketched on the current API surface. Maturity is **worked scaffold** — it covers breadth, not depth. Point at this example when verifying how the conventions hold up across many features composed in one app (feature-prefix discipline, schema attachment at HTTP boundaries, route-driven data loads, the SSR `:rf/server-init` cofx). Not a teaching example — read individual files (`auth.cljs`, `articles.cljs`, `routing.cljs`) for the relevant cross-cutting shape. Exercises 014 HTTPRequests, 012 Routing, 005 StateMachines, 011 SSR, Pattern-RemoteData, and Pattern-Forms.

## routing — `examples/reagent/routing/`

The three-page worked example for Spec 012 — `reg-route`, `:rf.route/navigate`, anchor clicks via `:rf/url-requested`, route-not-found handling, and the `:can-leave?` guard. The CP-7 worked example. Point at this example when authoring routes, navigating between them, gating navigation with `:can-leave?`, or wiring an anchor's `href` to dispatch a navigation event instead of a browser-default page load. Exercises 012 Routing. Compact and single-purpose; the canonical home of the routing primitives.

## ssr — `examples/reagent/ssr/`

The CP-9 worked example for Spec 011 — minimal SSR + hydration walkthrough. JVM-runnable; the browser side hydrates against a baked `<script id="__rf_payload">` block in the static `index.html` (standing in for a real Clojure server in front). Point at this example when authoring server-rendered views, `:rf/server-init` cofxs, the hydration payload shape, or the SSR-vs-hydration parity check. Exercises 011 SSR and 004 Views. The smallest possible SSR demo — read it alongside `realworld/ssr.cljc` for the broader scaffold.

## state_machine_walkthrough — `examples/reagent/state_machine_walkthrough/`

The runnable companion to `docs/guide/05-state-machines.md` — the guide chapter's login flow rendered as live code, with smoke tests for every section. Browser layer drives the canonical lockout scenario (three failures → `:locked-out`). Point at this example when teaching the machine grammar from scratch, when verifying the chapter's worked shape against the live implementation, or when authoring an event-driven sequence of FSM transitions that needs to be testable from a browser spec. Exercises 005 StateMachines, 014 HTTPRequests, and 008 Testing. Pedagogical sibling of `login/` — same domain, different aim (`login/` is the "single-feature scaffold"; this is the chapter's walkthrough).

## todomvc — `examples/reagent/todomvc/`

The canonical cross-framework benchmark — persistence (localStorage), in-place editing, bulk actions (mark-all-done, clear-completed), remaining-count derivation, and hash-routing filters (`#/`, `#/active`, `#/completed`). Point at this example when verifying a slice-shaped feature with a list of items, a derivation-heavy subscription graph (the filtered list, the remaining count, the all-completed flag), an interceptor-based localStorage persistence pattern, or the integration of `reg-route` with a list-filtering view. Exercises 002 Frames, 004 Views, and 012 Routing. The classic shape benchmark; if a feature looks like "manage a list with filters", this is the shape reference.

## 7Guis — `examples/reagent/7Guis/`

A cluster of six small benchmark apps from the [7GUIs](https://eugenkiss.github.io/7guis/) suite — `temperature/`, `flight_booker/`, `timer/`, `crud/`, `circle_drawer/`, `cells/`. Each app is a focused stress on one shape: bidirectional derivations (`temperature`), form-validity-driven button enablement (`flight_booker`), `:dispatch-later` periodic ticks (`timer`), list-CRUD with selection-as-state (`crud`), undo/redo via a snapshot-on-write interceptor and modal-as-state (`circle_drawer`), and a full formula-graph subscription substrate with cycle detection (`cells`). Point at the 7GUIs cluster when picking the right shape for a small focused concern: a controlled input pair, a Book-button-enables-only-when-valid flow, a periodic-tick UI, list operations with selection, undo/redo, or formula-driven cell propagation. Exercises 004 Views, 002 Frames, 006 ReactiveSubstrate, and Pattern-Forms. See `examples/reagent/7Guis/README.md` for the cluster's own narrative.

## In-flight examples (pending)

Two examples are listed in `examples/README.md` as **pending** worked examples — they accompany pattern leaves that ship with inline mini-examples until their own `examples/reagent/<x>/` directories land:

- **`examples/reagent/websocket/` (pending)** — will become the canonical Pattern-WebSocket example: a state machine that owns the long-lived connection lifecycle (`:disconnected → :connecting → :authenticating → :connected → :reconnecting → :failed`), heartbeat, queued-sends-when-disconnected, re-auth on reconnect, and topic-subscription preservation across reconnects.
- **`examples/reagent/long_running_work/` (pending)** — will become the canonical Pattern-LongRunningWork example: a CPU-bound batch job (process N items in chunks of 100) running on the main thread via a state machine that yields between chunks with `:after 0`, reports progress through `:data`, and supports user-initiated cancel.

Until these ship, the pattern leaves themselves are the authoritative shape — `patterns/websocket.md`, `patterns/long-running-work.md`.

## Adapter smoke-pairs

Per [Spec 006 §Adapter shipping convention](../../spec/006-ReactiveSubstrate.md), the UIx and Helix substrates each ship a curated smoke-pair (counter + login) rather than a 1:1 mirror of the Reagent set. Point at `examples/uix/counter_uix/` and `examples/uix/login_uix/` (and the Helix counterparts at `examples/helix/`) only when authoring against UIx or Helix specifically. The dataflow is identical to the Reagent siblings; only the view layer differs (`defui` for UIx, `defnc` for Helix, plus the `use-subscribe` hook).

## How to use this map during an authoring task

1. Pick the primary pattern from [`decision-trees/pick-a-pattern.md`](./decision-trees/pick-a-pattern.md).
2. Pick the state shape from [`decision-trees/slice-or-machine.md`](./decision-trees/slice-or-machine.md).
3. Find the example above whose paragraph names the same pattern + shape combination.
4. Read the example's source — match its shape; do not re-derive (per SKILL.md cardinal rule 2).
5. If the example contradicts the pattern leaf, **the example wins** (per SKILL.md cardinal rule 1).

## Cross-references

- [`SKILL.md`](./SKILL.md) — router skill; cardinal rules; loading map.
- [`examples/README.md`](../../examples/README.md) — the full example catalogue with maturity, build ids, and end-to-end coverage.
- [`spec/Conventions.md` §Adapter shipping convention](../../spec/Conventions.md) — Reagent-canonical / UIx-Helix-smoke-pair policy.
- [`patterns/`](./patterns/) — pattern leaves; each names the worked example for its pattern.

---

*Derived from `examples/reagent/**` and `examples/README.md` @ main `89bd9c3`. Re-verify whenever a new worked example lands or an in-flight example merges (e.g. `boot/`, `websocket/`, `long_running_work/`).*
