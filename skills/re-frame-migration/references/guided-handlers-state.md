# guided-handlers-state

Type B walkthroughs covering event handlers, registration shape, view-under-frame routing, render-count test re-baselining, error handlers, routing fallbacks, top-level db seeding, the retired `:rf/runtime` app-db root (now a hard error), machine spawn-id tracking, and the React-19-removed Reagent surfaces. Each section gives the **identification** (how to find the call sites), the **risk explanation** (what to tell the author), and the **decision shape** (what the author must choose between). The agent identifies and explains; the author decides; the agent then applies.

For interceptor- / subscription- / payload- / observer-shaped Type B rewrites, see [`guided-interceptors-subs.md`](guided-interceptors-subs.md). For Type A patterns, see [`auto-call-site-rewrites.md`](auto-call-site-rewrites.md) and [`auto-cross-cutting.md`](auto-cross-cutting.md). For full rule rationale, see [`MIGRATION.md`](../../../migration/from-re-frame-v1/README.md).

## Contents

- M-3 — run-to-completion drain
- M-5 — Var-aliased `reg-*`
- M-10 — reserved-namespace collision
- M-11 — plain Reagent fns under any frame (including the default/single frame)
- M-12 — render-count test re-baseline
- M-13 — `reg-event-error-handler`
- M-14 — `:rf.route/not-found` requirement (only if adopting Spec 012)
- M-15 — top-level `app-db` seeding
- M-15b — wholesale `app-db` replace + the retired `:rf/runtime` root
- M-34 — spawn-id tracking moved to runtime-owned slot
- M-42 — React-19-removed Reagent surfaces (`dom-node` / `force-update-all` half)

---

## M-3 — Run-to-completion drain

**Identify**:

- Every `:dispatch` effect inside an event handler that is paired with a `:db` write the handler also returns (the v1 pattern was "render the intermediate `:db` state, then run the dispatched event on a later tick").
- Every test that asserts on router-queue contents after `(rf/dispatch ...)`.
- Every animation chain that uses `:dispatch` to pace frames.

**Risk**: v2's drain runs to completion. The intermediate render between `:db` write and dispatched event no longer happens. Animation pacing via `:dispatch` is broken. Queue-peek tests see an empty queue.

**Decision shape** (per call site):

1. **Intermediate render is required** (e.g. spinner-flash-before-work): restructure so the visible state is its own event; the work runs on a separate `:dispatch-later {:ms 0}`.
2. **Animation pacing**: convert to `:dispatch-later` with the frame interval, or move to `requestAnimationFrame` via a registered fx.
3. **Queue-peek test**: rewrite the assertion to check resulting `app-db` state or observed effects, not queue contents.
4. **Mechanical rewrite is fine**: leave the `:dispatch` as-is; the run-to-completion behaviour is strictly better for this site.

Present every call site with its file:line and the four options; collect the author's choice; apply.

---

## M-5 — Var-aliased `reg-*`

**Identify**:

```clojure
(def my-reg rf/reg-event-db)         ; capturing the Var as a value
(apply rf/reg-event-db [:id handler]) ; apply over a macro
(map #(apply rf/reg-event-db %&) ...) ; same shape inside higher-order code
```

**Risk**: `reg-*` are macros in v2; they can't be Var-aliased or `apply`d. The code fails at compile time. The fix shape depends on whether the higher-order use was essential (e.g. registering a generated list of handlers) or accidental (capturing the Var "just because").

**Decision shape**:

1. **Refactor to direct invocation**. The author has a list of `[id handler]` pairs; replace `(apply rf/reg-event-db pair)` with a macro of their own that expands to a sequence of direct `reg-event-db` calls.
2. **Use the functional surface** (where it exists). re-frame2 may expose `reg-machine*` / `reg-view*` partners — plain-fn surfaces that *can* be Var-aliased. For `reg-event-*` / `reg-sub` / `reg-fx` / `reg-cofx`, no such partner ships today. If the author truly needs the functional form, **file a GitHub issue against `day8/re-frame2`** rather than working around.

---

## M-10 — Reserved-namespace collision

**Identify**: every `(reg-* :rf/...)` or `(reg-* :rf.<area>/...)` registration.

**Risk**: `:rf/*` and its sub-namespaces are reserved for framework-owned ids. User registrations under reserved keys silently shadow framework extension points (or get overwritten by them on hot-reload), break tooling discoverability, and lose stability.

**Decision shape** (per call site):

1. **Rename to a feature prefix**. Pick the project's own top-level namespace (e.g. `:cart/...`, `:auth/...`). This is the default move.
2. **Intentional override** of a documented framework extension point. Confirm with the author that this is deliberate; leave the registration in place; note it in the report.
3. **Decline**. The author accepts the runtime warning. Rare; document the reasoning in the report.

---

## M-11 — Plain Reagent fns under any frame, including the default/single frame

**Identify**:

1. Find every React-context `(rf/frame-provider …)` — its `{:frame <id>}` SCOPE-only shape or its `{:id <id> …}` ENSURE shape — **regardless of which frame it establishes, the root/default provider included**. (The merged `frame-provider` dispatches on the prop map, so match both shapes when sweeping.) A single-frame app has exactly **one** such provider at its root — walk it too. The footgun is not confined to non-default frames; under EP-0002 a plain fn can't read **any** provider's frame from context.
2. Walk the hiccup subtree under each such provider. List every Var-referenced function (or anonymous lambda) that is **not** registered via `rf/reg-view`. Cross-reference the `(rf/registrations :view)` registry.

**Risk**: a plain Reagent function rendered inside a `frame-provider` can't read the provider's frame (no `:contextType`), so its internal `(subscribe ...)` / `(dispatch ...)` calls carry no frame and raise `:rf.error/no-frame-context` (EP-0002 — no silent `:rf/default` routing; the old one-time warning is superseded by this loud error). The failure surfaces at runtime when the component renders.

**Decision shape** (per component-under-frame pair):

1. **Convert to `reg-view`**. Replace `(defn my-view [args] ...)` with `(rf/reg-view ^{:doc "..."} my-view [args] ...)`. The view picks up the surrounding frame correctly. Recommended.
2. **Hold a `(rf/capture-frame)`**. Inside the plain fn body, capture the frame api once and route through it: `(let [{:keys [dispatch subscribe]} (rf/capture-frame)] ...)`, then call `(dispatch [...])` / `(subscribe [...])` instead of the bare `rf/dispatch` / `rf/subscribe`. The frame api captures the surrounding frame at render time and (unlike the ambient binding) survives into any async callback the body sets up.
3. **Leave as-is** — *only* when the component never calls `rf/subscribe` / `rf/dispatch` (a pure presentational fn with no frame-scoped reads), **or** when it establishes / carries a frame explicitly inside its own body (a `with-frame`, an explicit `{:frame <id>}` op opt, or a captured `(rf/capture-frame)`). Do **not** describe this as pinning to `:rf/default`: there is no `:rf/default` fall-through (EP-0002), so a frame-dependent plain fn left under any provider — the default/root included — raises `:rf.error/no-frame-context` at render; it does not silently route to a default. To *intentionally* target `:rf/default` (e.g. a "global" UI primitive), the component must scope or pass that frame explicitly like any other; document why.

### The single-frame case — a forced whole-tree sweep, not the opt-in O-2

The footgun is most visible in a multi-frame app, but it is **not multi-frame-specific**. Under EP-0002 there is no `:rf/default` fall-through: a plain fn carries no `:contextType`, so it cannot read **any** provider's frame from React context — the default/root provider included — and `with-frame`'s dynamic-var tier does not cross React's render boundary. So a **single-frame app** whose whole view tree is plain bare-`subscribe`/`dispatch` fns under one root `frame-provider` has **every** such fn throw `:rf.error/no-frame-context` the moment it renders.

There is **no ambient-default shortcut**, and there is **no "one root `reg-view` fixes the plain descendants"**: each plain fn is its own React component, so converting the root view does nothing for the unregistered fns below it — every frame-dependent fn must carry its own `:contextType`. The fix takes the same two shapes as the decision above, applied to the **whole tree**:

- **Convert every frame-dependent plain fn to `reg-view`** — each then carries its own `:contextType` and resolves the root frame — **or**
- **Hold a `(rf/capture-frame)` per fn**, routing its `dispatch` / `subscribe` through the captured frame api.

For a bare-`dispatch`/`subscribe` plain-fn tree this conversion is **FORCED, not optional** — it is the boots-or-not floor (M-11), not the opt-in modernisation O-2. O-2 is a *clean* view that already works being made multi-frame-ready; here the app **does not boot** until the tree is converted (or each fn captures a frame). Name the extent honestly: it is a **whole-tree sweep**, potentially every view file in the app. **Size it in the Phase-0a inventory** — count the unregistered frame-dependent plain fns up front — rather than discovering the extent when boot throws `:rf.error/no-frame-context`.

### Executing the conversion: subscribing views from defn to reg-view

The decision and single-frame sweep above answer *which* components convert and *how big* the pass is; this is the **execution mechanics** — the per-component rewrite, form by form. For a single/default-frame app the disposition is overwhelmingly "convert," so the bulk of M-11 is one mechanical-but-large `defn` → `reg-view` sweep across the view layer (a single real app commonly carries dozens of subscribing components across its view files — one field migration converted 30+ across 22 files, the largest single rewrite of that migration). Verify each form against [`spec/004-Views.md` §Form-1, Form-2, Form-3](../../../spec/004-Views.md#form-1-form-2-form-3-components) before applying.

**Before you sweep — add re-frame2's clj-kondo config, or you cannot assert the conversion is "lint clean."** clj-kondo does not macroexpand, so it cannot read `reg-view` (a `defn`-shape macro that auto-injects `dispatch` / `subscribe`) unaided. re-frame2 ships the config that teaches it as two files at its repo root — `.clj-kondo/config.edn` plus the macro hook it points at, `.clj-kondo/hooks/re_frame/core.clj`. Copy both into the consuming project's own `.clj-kondo/` (keep the `hooks/re_frame/` sub-path) before converting. `config.edn` wires the hook via `:hooks {:analyze-call {re-frame.core/reg-view hooks.re-frame.core/reg-view}}` and excludes `re-frame.core/reg-view` from `:unresolved-var`; the hook rewrites `(reg-view sym [args] body)` to a `(defn sym [args] (let [dispatch … subscribe …] body))`, so clj-kondo resolves the view's own symbol and reads the injected ops as real locals. (`reg-view` is the lone hook case — the rest of the `reg-*` family ride a `:lint-as clojure.core/do` entry in the same file.)

Without that config the converted views lint dirty with two **false positives** — name them up front so an agent does not chase either as a real break:

- **`Unresolved symbol` on the view's own name** — `(reg-view account-summary [label] …)` reads `account-summary` as undefined, because unaided clj-kondo does not know `reg-view` introduces a `defn`-like binding. The hook clears it.
- **`subscribe` / `dispatch` `:refer` "referred but never used"** — a converted ns needs no `:refer [subscribe dispatch]` (the bare `dispatch` / `subscribe` are the macro-injected frame-bound locals, per the require note below); a leftover refer carried over from the v1 source is genuinely redundant, so clj-kondo flags it — drop the refer, the view is not broken.

**The require — usually nothing to add.** A subscribing view ns already requires `re-frame.core` (it calls `subscribe` / `dispatch`), and `reg-view` is reached the same way, as `rf/reg-view`. `re-frame.core` self-requires its own macros (`:require-macros [re-frame.core … :refer [reg-view …]]`), so the single M-1 import — `(:require [re-frame.core :as rf])` — already carries the `reg-view` macro with **no** separate `:refer-macros [reg-view]`. The explicit-refer idiom (`[re-frame.core :as rf :refer-macros [reg-view]]`) is only needed by a ns that subscribes yet somehow omits the `re-frame.core` require — rare, since subscribing already requires it.

**Form-1 (canonical render fn) — the 80% case.** A render fn becomes a `reg-view` of the same symbol and arg-vector; the body is unchanged except that any hand-rolled frame-capture in it is dropped — `dispatch` / `subscribe` arrive as auto-injected, frame-aware lexical locals (`reg-view` auto-defs the symbol and auto-derives the id from `(keyword *ns* sym)`).

```clojure
;; before — subscribes in render but unregistered: throws :rf.error/no-frame-context
(defn account-summary [label]
  [:div label ": " @(rf/subscribe [:account/balance])])

;; after — registered, frame-aware (dispatch / subscribe injected)
(rf/reg-view account-summary [label]
  [:div label ": " @(subscribe [:account/balance])])
```

**Form-2 (closure — setup work in an outer fn).** `reg-view` supports the inner render fn: a body that *returns a fn* keeps that shape. The injected `dispatch` / `subscribe` locals are visible to **both** the outer (once-per-mount) body and the inner render fn through ordinary Clojure lexical closure, so the closure converts intact.

```clojure
;; before
(defn counter-with-init [label]
  (rf/dispatch [:counter/init label])
  (fn [label]
    [:button {:on-click #(rf/dispatch [:counter/inc])}
     (str label ": " @(rf/subscribe [:counter/value]))]))

;; after — same closure, injected ops
(rf/reg-view counter-with-init [label]
  (dispatch [:counter/init label])
  (fn [label]
    [:button {:on-click #(dispatch [:counter/inc])}
     (str label ": " @(subscribe [:counter/value]))]))
```

The spec prefers Form-1 + an explicit setup event over Form-2 (see [`spec/004-Views.md` §Form-2](../../../spec/004-Views.md#form-2-closure--supported-prefer-form-1--explicit-setup-event)), but a migration preserves behaviour — convert the closure as-is and leave the Form-2 → Form-1 modernisation to the author.

**Form-3 (`reagent.core/create-class` — lifecycle).** The `reg-view` macro **rejects** a `create-class` body at macroexpand (Form-3 is not defn-shape; the error points the author at `reg-view*`), so you cannot wrap the class in `reg-view` and have it pick up `:contextType` the way a function component does. Two routes, both spec-supported:

1. **Extract the subscribing content into a `reg-view` child (preferred).** Keep the class for its lifecycle, but move the part that derefs subs / dispatches into a small `reg-view`-registered child the class renders. The child carries its own `:contextType` and reads the frame; the class manages its DOM/lifecycle and subscribes nothing.
2. **Register the class through `reg-view*` and capture the frame explicitly.** Per [`spec/004-Views.md` §Form-3](../../../spec/004-Views.md#form-3-class--out-of-scope-for-the-macro), the class ships through `re-frame.core/reg-view*` (the plain-fn surface — no auto-inject), and inside its render / lifecycle hooks you bind `{:keys [dispatch subscribe]}` from `(rf/capture-frame)` and route through those ops. The captured handle locks to the render frame and survives the lifecycle callbacks' async boundary.

**Scope — convert subscribers only; do not over-convert.** The conversion is keyed on *frame-dependence*, not on being a view. Convert a component **iff** it derefs a sub or dispatches in its render (the calls that raise `:rf.error/no-frame-context`). **Leave as `defn`** every non-subscribing helper and every pure value-taking presentational component (one that renders only its args) — these depend on no frame, so converting them is churn. This is option 3 of the decision table applied at conversion time.

**Compile-silent — only the boot-smoke catches a miss.** A `defn` view is valid ClojureScript: the unconverted component compiles **0 errors / 0 warnings** and is invisible in the build log. The gap surfaces only at runtime, when the component first renders and throws `:rf.error/no-frame-context`. Because it throws on *render* (not at ns-load), it is precisely the silent class the Phase-4 [boot smoke-test](runtime-smoke-test.md) exists to catch: boot the dev build, exercise each first-screen surface, and watch the console for `:rf.error/no-frame-context`. Treat the sweep as unverified until a clean boot renders the screens — which is why Phase-0a **sizes** it (see the single-frame case above) rather than discovering the extent one render-crash at a time.

**After converting — fix any function-position call site of a converted view.** A `reg-view`'s frame wiring rides the component's `:contextType`, which the substrate attaches **only when it mounts the view as a component** — i.e. when the view appears as the head of a hiccup vector, `[my-view props]`. A v1 codebase that called the old plain render fn in **function position**, `(my-view props)`, still compiles after the conversion but bypasses the mount: no component is minted, no `:contextType` attaches, and the converted body's injected `subscribe` / `dispatch` raise `:rf.error/no-frame-context` when they run. So add one identify/verify step to the sweep: after converting the view symbols, grep each converted symbol used in call position — `(my-view …)` — and rewrite it to hiccup, `[my-view …]`. (The bracket-head lookup form `[(rf/view :my-view) …]` is also hiccup and mounts normally; only the **bare** `(my-view …)` call is the footgun.)

> **Not M-11 — an in-handler `@(rf/subscribe …)` deref.** M-11 is the no-frame-context footgun for a *view* (or an escaped callback) that has no surrounding scope to read a sub from. An `@(rf/subscribe …)` deref **inside a `reg-event` handler** is a different shape: the handler already holds app-db as the `:db` coeffect, so recompute the value from `:db` — it needs no `reg-view` / `(rf/capture-frame)` / `with-frame`. Triage it by source, not by context: see [`causal-world-inputs.md` §source triage](causal-world-inputs.md#triage-in-handler-reads-by-source).

---

## M-12 — Render-count test re-baseline

**Identify**: tests asserting on exact render counts: `(is (= 3 @render-count))`, `(is (= n (count @render-events)))`, etc.

**Risk**: v2's sub-cache invalidation is tighter. Counts shift — usually fewer renders, occasionally more at boundaries where the new cache is more granular. Behaviour is correct; the assertion is stale.

**Decision shape**: re-baseline. Run the tests; record the new counts; update the expected values. Optionally rewrite the assertion to look at *behaviour* (final state, externally-observable side effects) rather than render counts.

No mechanical rewrite — the author updates the expected numbers.

---

## M-13 — `reg-event-error-handler`

**Identify**: every `(rf/reg-event-error-handler ...)` call site.

**Risk**: v1's process-wide error-handler is gone, and there is **no app-steering error-recovery policy** in v2 — recovery is framework-owned (the typed per-category default). The right replacement depends on the role the handler played:

- If it was **recovery-steering** ("when an event handler throws in this frame, swallow / substitute / route to this recovery"), it has **no v2 equivalent** — drop it. Rely on the framework's typed per-category default; move any genuine recovery for *expected* failures to the source (managed-HTTP `:retry`, optional-read fallback). To re-run a failed event, dispatch a fresh one.
- If it was a **process-wide observer** (audit logging, metrics, Sentry forwarding), it moves to a listener filtering on `:op-type :error`. Pick the surface by **stream** on the one `register-listener!` verb: `(register-listener! :trace …)` is **dev-only** (production-elided); for **always-on production** error egress (Sentry / Honeybadger / Datadog) use `(register-listener! :errors …)` — see [`error-events.md` §Production elision](error-events.md#production-elision--what-elides-and-what-stays-always-on).

A v1 codebase that stacked multiple handlers (e.g. one for recovery, one for logging) drops the recovery half and moves the logging half to a listener.

**Decision shape**:

1. Read the handler body. If it modifies state, swallows, or substitutes a result, that's recovery-steering — drop it; there is no v2 policy slot. Move any genuine recovery to the source.
2. If it logs / reports / metrics, that's a listener — `(register-listener! :errors …)` if it must run in production, `(register-listener! :trace …)` if dev-only.
3. If it does both, split the body — drop the recovery-steering, move observation to the appropriate listener surface.

Present the categorisation; confirm with the author; apply.

**Writing the new trace listener**: the closed set of `:operation` keywords — and the `:op-type` / `:tags` shape for filtering listeners — lives in [`spec/009-Instrumentation.md` §Error event catalogue](../../../spec/009-Instrumentation.md#error-event-catalogue). See [`error-events.md`](error-events.md) for the pointer and the prefix-family reference. Do not infer category names from the v1 code or comments — the catalogue at Spec 009 is authoritative.

---

## M-14 — `:rf.route/not-found` requirement

**Trigger**: only fires if the author is adopting Spec 012's routing surface (i.e. they're applying O-8 or had `reg-route` calls in v1). If they're keeping a third-party router (reitit, secretary, bidi-only), M-14 doesn't apply.

**Identify**: codebase calls `reg-route` but does not register `:rf.route/not-found`.

**Risk**: unknown URLs arrive without a fallback. The runtime emits a warning trace; in production this can be silent. Tooling and SSR rely on `:rf.route/not-found` existing.

**Decision shape**: add the registration. Two pieces:

1. The route: `(rf/reg-route :rf.route/not-found {:params [:map [:rest :string]]} "/*rest")`.
2. A view registered under `:rf.route/not-found` (a basic 404 page; author writes the content).

If the author declines, document the warning in the report.

---

## M-15 — Top-level `app-db` seeding

**Identify**: top-level `(reset! re-frame.db/app-db ...)` or `(swap! re-frame.db/app-db ...)` calls in namespace bodies (run at load time, not inside a function).

**Risk**: M-1 forbids the private-namespace require. But the seeding can't just be deleted — `app-db` no longer starts as a top-level mutable atom in v2; it lives inside the default frame's record and is initialised by the frame's `:initial-events`.

**Decision shape**:

1. **Seed via `:initial-events`**. To seed a literal app-db, use the standard `[:rf/set-db {…}]` event: `(rf/reg-frame :rf/default {:initial-events [[:rf/set-db initial-state]]})`. To run a boot event instead, point `:initial-events` at it: `(rf/reg-frame :rf/default {:initial-events [[:app/seed initial-state]]})` plus the `[:app/seed initial]` event handler that writes the seed into `app-db`. (`:initial-events` is a **vector of event vectors** — list multiple steps directly, in order, e.g. `[[:rf/set-db initial] [:app/boot]]`; no `:fx`-fan-out workaround is needed.)
2. **Move the seed to test fixtures only** if the seed is test-specific. Seed the test frame the same way — via `:initial-events` — never a top-level `app-db` poke: `(rf/with-new-frame [f (rf/make-frame {:initial-events [[:test/seed initial]]})] ...)`.

Present the seed value and the proposed rewrite; confirm with the author; apply both the M-1 require-removal and the M-15 `:initial-events` rewrite together.

---

## M-15b — Wholesale `app-db` replace + the retired `:rf/runtime` root

**Identify**: any event handler that returns a *wholesale* `app-db` value — `(reg-event-db :initialize-db (fn [_ _] fresh-db))`, `{:db fresh-db}` from `:bootstrap` / `:app/reset` / a logout-to-clean-state event — where `fresh-db` is built from scratch rather than threaded from the incoming `db`. Two sub-shapes carry the migration risk: (a) a v1-shaped `fresh-db` that **carries a `:rf/runtime` key** (a hand-rolled runtime stash, or one threaded forward by an older v2-preview rewrite), and (b) any handler that **explicitly writes `:rf/runtime`** into its `:db` return (the old "preserve the runtime" stopgap). The tell for both: the returned `:db` map contains a top-level `:rf/runtime`.

**Risk**: in re-frame2 framework runtime no longer lives in `app-db` at all. It sits in a **separate partition — the runtime-db** — addressed by the reserved `:rf.db/runtime` coeffect/effect, with subsystem children under `:rf.runtime/*`: machine snapshots at `[:rf.runtime/machines :snapshots <id>]`, the current route at `[:rf.runtime/routing :current]`, plus elision and SSR state. A handler's `:db` return replaces **only** the app-db partition and **cannot touch the runtime-db** — so the v1-era "wholesale `{:db fresh-map}` boot silently wipes the runtime" footgun is **structurally gone**. There is nothing to preserve and nothing to clobber: a boot machine's snapshot rides in runtime-db, untouched by any app-db replace.

The retired app-db root `:rf/runtime` is now a **hard error**. A `:db` value carrying a top-level `:rf/runtime` key throws `:rf.error/legacy-runtime-root` (the always-on post-commit guard `re-frame.events/reject-legacy-runtime-root!`, per [Conventions §The legacy `:rf/runtime` root](../../../spec/Conventions.md#the-legacy-rfruntime-root-hard-error-in-final-form)). The error is loud and immediate (not a silent runtime hang, not the dev-only advisory). So the migration concern flips: the rewrite is not "preserve the runtime across the replace" — it is "**strip the `:rf/runtime` key**; the runtime is no longer your responsibility to thread."

**Decision shape** (per wholesale-replace handler):

1. **Strip any `:rf/runtime` key from the fresh db (always).** A wholesale reset is now safe by construction — `{:db fresh-db}` replaces app-db and leaves machines / routing / SSR untouched in runtime-db. Just ensure `fresh-db` carries **no** `:rf/runtime` key (it would hard-error). If a v1-shaped `fresh-db` or an older preview rewrite still stashes runtime state there, drop it:

   ```clojure
   (rf/reg-event :initialize-db
     (fn [_ _] {:db fresh-db}))   ; fresh-db carries NO :rf/runtime — the runtime-db partition is left alone
   ```

2. **Genuinely need to write runtime state? Use the `:rf.db/runtime` effect, never an app-db key.** Framework/extension code that must seed or replace runtime-db emits the reserved `:rf.db/runtime` effect (or one of the `replace-runtime-db!` / `replace-frame-state!` mutators), keeping application data under `:db`. App code rarely needs this — boot machines install their own snapshots when they start.

The old `:rf.warning/runtime-state-dropped` containment warning is **retired** — there is no clobber to warn about. Its replacement is the structural `:rf.error/legacy-runtime-root` hard error above, which fires in every build (dev and production) the moment a handler returns a `:rf/runtime`-bearing `:db`.

Present the categorisation and the proposed rewrite; confirm with the author; apply. Full rationale and the canonical before→after: [`MIGRATION.md` §M-15b](../../../migration/from-re-frame-v1/README.md#m-15b-a-full-app-db-replace-boot--initialise-event-is-safe--but-strip-any-retired-rfruntime-key-now-a-hard-error). The end-to-end boot recipe: [`spec/Pattern-Boot.md` §Worked example — the singleton boot machine](../../../spec/Pattern-Boot.md#worked-example--the-singleton-boot-machine).

---

## M-34 — Spawn-id tracking moved to runtime-owned slot

**Identify**: machine specs (Spec 005) that declare a declarative `:spawn` (or hand-emit `[:rf.machine/destroy ...]` from a machine action). Two sub-shapes carry the risk:

1. Specs that declared `:spawn` **without** an `:on-spawn` callback — pre-fix these silently leaked the spawned actor on state-exit (the runtime had no recorded id to destroy).
2. Tests or `:exit` action bodies that **asserted on the old behaviour**: a stale `[:rf.runtime/machines :snapshots <id>]` entry surviving after exit, or that read the spawned id back out of the parent's `[:data :pending]` slot.

**Risk**: the runtime now tracks each spawn-id at the reserved runtime-db slot `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]` instead of reading it from the parent's `:data`. `:on-spawn` becomes purely advisory — apps that omitted it now correctly destroy the child on exit. The **public API is unchanged** (`:on-spawn` signature `(fn [data spawned-id] new-data)` is identical), and the destroy fx's keyword form `[:rf.machine/destroy actor-id]` still works. The hazard is silent for code/tests that depended on the old leak or the old `:data`-slot read: those need triage, not a rewrite.

**Decision shape** (per hit site):

1. **`:spawn` without `:on-spawn`, no test dependency**: no rewrite — the spec is now correct-by-default under the runtime-owned registry. Note it in the report.
2. **Test asserts a stale snapshot / leak after exit**: the assertion is now wrong (the actor is correctly destroyed). The author decides whether the test should assert the new correct teardown or whether the spec genuinely wanted the actor to survive (rare — usually means a `:system-id` named machine, not a transient spawn).
3. **`:exit` body reads `(:pending data)` to address the child**: still works (user `:data` is user territory) — leave as-is, but confirm the author still wants the id recorded in `:data` for their own bookkeeping rather than relying on the runtime slot.

Present the categorisation per site; confirm with the author; only then apply. Full rationale: [`MIGRATION.md` §M-34](../../../migration/from-re-frame-v1/README.md#m-34-spawn-id-tracking-moved-from-data-pending-to-runtime-owned-rfruntimemachines-spawned-).

---

## M-42 — React-19-removed Reagent surfaces (bridge *and* slim)

**Trigger**: fires on **both** Reagent paths, because `reagent.dom/render` is removed in **Reagent 2.x itself** — and the classic bridge runs on Reagent 2.x (per the Phase-0 floor gate). The render call site needs a createRoot+render **rewrite** regardless of which adapter the app boots; only the **target namespace** differs. The slim rewrite additionally ships the *other* legacy symbols (`dom-node`, `force-update-all`, `unmount-component-at-node`) as throw-on-call shims; on the bridge those four non-render Vars are **unchanged** (stock Reagent has not removed them — only the React-DOM `render`/`createRoot` floor moved).

> **Do not read "apps on the bridge are unaffected" (MIGRATION.md §M-42) as "the bridge needs no render change."** That sentence is about the *non-render* legacy Vars (`dom-node` etc.) staying available on the bridge. The **render call site still needs the createRoot rewrite on the bridge too**, because `reagent.dom/render` is gone in Reagent 2.x — the bridge just targets a different namespace than slim.

**The adapter-keyed render-namespace table** (the render rewrite is the same shape — `create-root` + `render` around the same `container` — only the namespace changes):

| Adapter the app boots | Render namespace (createRoot + render) | Coord |
|---|---|---|
| **classic bridge** (stock Reagent 2.x + `re-frame.adapter.reagent`) | `reagent.dom.client` | `day8/re-frame2-reagent` |
| **slim rewrite** (`re-frame.adapter.reagent-slim`) | `reagent2.dom.client` | `day8/reagent-slim` |

```clojure
;; v1 (both paths) — reagent.dom/render is gone in Reagent 2.x
(reagent.dom/render [app] (.getElementById js/document "app"))

;; bridge — create-root + render via reagent.dom.client
(defonce root (rdc/create-root (.getElementById js/document "app"))) ; [reagent.dom.client :as rdc]
(rdc/render root [app])

;; slim — identical shape, reagent2.dom.client target
(defonce root (rdc/create-root (.getElementById js/document "app"))) ; [reagent2.dom.client :as rdc]
(rdc/render root [app])
```

Pick the row by the **adapter artefact M-0 committed** — that disambiguates the namespace without inspecting the substrate source.

**Identify**: grep for call sites of `render` (`reagent.dom/render`, `reagent.core/render`), plus — *on slim only* — the other removed symbols: `unmount-component-at-node`, `dom-node`, `force-update-all`, plus the `reagent.dom.server` surface per the MIGRATION.md list.

**Risk + decision shape — split by symbol**:

1. **`render` / `unmount-component-at-node` (Type A — mechanical, *both* adapters for `render`)**: rewrite to a `create-root` + `render` / `unmount` pair around the same `container`, in the adapter-appropriate namespace from the table above. Apply once the caller's `container` reference is identified — this half rides the normal Type A sweep with the sweep-level announcement (Cardinal rule 4). (`unmount-component-at-node` is only *removed* on slim; on the bridge it remains available, but the surrounding render rewrite usually makes the `create-root`-returned root's `unmount` the natural target anyway.)
2. **`dom-node` (Type B — ask first; slim only)**: `findDOMNode` returned the underlying DOM node for a mounted component; the canonical React-19 replacement captures the node via `:ref` at the call site **of the parent**, not at the consumer. There is **no static-analysable rewrite** — the agent flags every `dom-node` site and the author supplies the parent ref ownership. (Available unchanged on the bridge.)
3. **`force-update-all` (Type B — ask first; slim only)**: had no documented use beyond global-rebuild scripts. Flag every site and ask the maintainer whether it can be removed entirely; if not, file a GitHub issue (per the shared [`issue-filing.md`](../../shared/issue-filing.md) recipe) rather than inventing a replacement. (Available unchanged on the bridge.)

Apply the render mount-path half mechanically (in the adapter's namespace); flag the `dom-node` / `force-update-all` half and wait for the author. Full rationale + the throw-on-call shim list: [`MIGRATION.md` §M-42](../../../migration/from-re-frame-v1/README.md#m-42-react-19-removed-reagent-surfaces-ship-as-throw-on-call-shims-under-day8reagent-slim).

---

## Anti-pattern: silent rewrites

The Type B rules exist because the rewrite **cannot** be inferred from the call site alone. If you find yourself wanting to "just rewrite" one of these without asking — stop. The whole point of Type B is that asking is cheaper than rolling back a wrong rewrite.

The only Type B item the agent can apply without asking is when the author has pre-authorised a specific decision shape upfront (e.g. "for every plain Reagent fn under a non-default frame, just convert to `reg-view`; flag the rest"). Bank those pre-authorisations in the report so the author can audit.
