# guided-checklist

Type B walkthroughs: the judgment-call rewrites. Each section gives the **identification** (how to find the call sites), the **risk explanation** (what to tell the author), and the **decision shape** (what the author must choose between). The agent identifies and explains; the author decides; the agent then applies.

For Type A patterns, see `reference/automated-transforms.md`. For full rule rationale, see [`MIGRATION.md`](../../../spec/MIGRATION.md).

## Contents

- M-3 — run-to-completion drain
- M-5 — Var-aliased `reg-*`
- M-10 — reserved-namespace collision
- M-11 — plain Reagent fns under non-default frames
- M-12 — render-count test re-baseline
- M-13 — `reg-event-error-handler`
- M-14 — `:rf.route/not-found` requirement (only if adopting Spec 012)
- M-15 — top-level `app-db` seeding
- M-17 — `reg-global-interceptor` in a multi-frame app
- M-18 — `reg-sub-raw` rewrite-path picking
- M-19 — opt-in map-payload migration (only if user asked)
- M-21 — `on-changes` / `enrich` / `after`
- M-23 — `:re-frame/lifecycle` annotation drop
- M-26 — `add-post-event-callback` / `remove-post-event-callback`

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
2. **Use the functional surface** (where it exists). re-frame2 may expose `reg-machine*` / `reg-view*` partners — plain-fn surfaces that *can* be Var-aliased. For `reg-event-*` / `reg-sub` / `reg-fx` / `reg-cofx`, no such partner ships today. If the author truly needs the functional form, **file a bead** rather than working around.

---

## M-10 — Reserved-namespace collision

**Identify**: every `(reg-* :rf/...)` or `(reg-* :rf.<area>/...)` registration.

**Risk**: `:rf/*` and its sub-namespaces are reserved for framework-owned ids. User registrations under reserved keys silently shadow framework extension points (or get overwritten by them on hot-reload), break tooling discoverability, and lose stability.

**Decision shape** (per call site):

1. **Rename to a feature prefix**. Pick the project's own top-level namespace (e.g. `:cart/...`, `:auth/...`). This is the default move.
2. **Intentional override** of a documented framework extension point. Confirm with the author that this is deliberate; leave the registration in place; note it in the report.
3. **Decline**. The author accepts the runtime warning. Rare; document the reasoning in the report.

---

## M-11 — Plain Reagent fns under non-default frames

**Identify**:

1. Find every `(rf/frame-provider {:frame <id>} ...)` whose `<id>` is **not** `:rf/default`.
2. Walk the hiccup subtree under each such provider. List every Var-referenced function (or anonymous lambda) that is **not** registered via `rf/reg-view`. Cross-reference the `(rf/handlers :view)` registry.

**Risk**: a plain Reagent function rendered inside a non-default frame's `frame-provider` silently routes its internal `(subscribe ...)` / `(dispatch ...)` calls to `:rf/default`. The runtime emits a one-time warning per `(component, frame)` pair, but the bug surfaces at runtime — by then the silent mis-route has been masking behaviour.

**Decision shape** (per component-under-frame pair):

1. **Convert to `reg-view`**. Replace `(defn my-view [args] ...)` with `(rf/reg-view ^{:doc "..."} my-view [args] ...)`. The view picks up the surrounding frame correctly. Recommended.
2. **Use `(rf/dispatcher)` / `(rf/subscriber)` render-time helpers**. Inside the plain fn body, capture the frame-bound dispatcher / subscriber and replace bare `dispatch` / `subscribe` calls.
3. **Leave as-is**. The author accepts that the component pins to `:rf/default` regardless of where it renders. Sometimes intentional (a "global" UI primitive); document why.

---

## M-12 — Render-count test re-baseline

**Identify**: tests asserting on exact render counts: `(is (= 3 @render-count))`, `(is (= n (count @render-events)))`, etc.

**Risk**: v2's sub-cache invalidation is tighter. Counts shift — usually fewer renders, occasionally more at boundaries where the new cache is more granular. Behaviour is correct; the assertion is stale.

**Decision shape**: re-baseline. Run the tests; record the new counts; update the expected values. Optionally rewrite the assertion to look at *behaviour* (final state, externally-observable side effects) rather than render counts.

No mechanical rewrite — the author updates the expected numbers.

---

## M-13 — `reg-event-error-handler`

**Identify**: every `(rf/reg-event-error-handler ...)` call site.

**Risk**: v1's process-wide error-handler is gone. The right replacement depends on the role the handler played:

- If it was **per-frame ergonomic policy** ("when an event handler throws in this frame, route to this recovery"), it moves into the frame-level `:on-error` slot on `reg-frame` metadata.
- If it was a **process-wide observer** (audit logging, metrics, Sentry forwarding), it moves to `register-trace-cb!` filtering on `:op-type :error`.

A v1 codebase that stacked multiple handlers (e.g. one for recovery, one for logging) needs both rewrites at once.

**Decision shape**:

1. Read the handler body. If it modifies state or dispatches recovery events, that's `:on-error` policy.
2. If it only logs / reports / metrics, that's a trace listener.
3. If it does both, split the body — recovery to `:on-error`, observation to `register-trace-cb!`.

Present the categorisation; confirm with the author; apply.

---

## M-14 — `:rf.route/not-found` requirement

**Trigger**: only fires if the author is adopting Spec 012's routing surface (i.e. they're applying O-8 or had `reg-route` calls in v1). If they're keeping a third-party router (reitit, secretary, bidi-only), M-14 doesn't apply.

**Identify**: codebase calls `reg-route` but does not register `:rf.route/not-found`.

**Risk**: unknown URLs arrive without a fallback. The runtime emits a warning trace; in production this can be silent. Tooling and SSR rely on `:rf.route/not-found` existing.

**Decision shape**: add the registration. Two pieces:

1. The route: `(rf/reg-route :rf.route/not-found {:path "/*rest" :params [:map [:rest :string]]})`.
2. A view registered under `:rf.route/not-found` (a basic 404 page; author writes the content).

If the author declines, document the warning in the report.

---

## M-15 — Top-level `app-db` seeding

**Identify**: top-level `(reset! re-frame.db/app-db ...)` or `(swap! re-frame.db/app-db ...)` calls in namespace bodies (run at load time, not inside a function).

**Risk**: M-1 forbids the private-namespace require. But the seeding can't just be deleted — `app-db` no longer starts as a top-level mutable atom in v2; it lives inside the default frame's record and is initialised by the frame's `:on-create` cascade.

**Decision shape**:

1. **Author the `:on-create` event**. `(rf/reg-frame :rf/default {:on-create [[:app/seed initial-state]]})` plus the `[:app/seed initial]` event handler that writes the seed into `app-db`.
2. **Move the seed to test fixtures only** if the seed is test-specific. (`with-frame` in `re-frame.test-support` accepts an `:initial-db` opt.)

Present the seed value and the proposed rewrite; confirm with the author; apply both the M-1 require-removal and the M-15 `:on-create` rewrite together.

---

## M-17 — `reg-global-interceptor` in a multi-frame app

**Trigger**: only Type B when the codebase has more than one frame. Single-frame codebases hit the Type A rewrite (move to `:rf/default` `:interceptors`).

**Identify**: every `(rf/reg-global-interceptor ...)` AND the codebase has any non-default `reg-frame`.

**Risk**: "global" meant "every frame" in v1 because there was only one frame. In v2 the right scope depends on intent:

- If the interceptor was meant to **apply to every frame** (genuinely cross-frame behaviour modification): replicate in each `reg-frame` `:interceptors` vector. Usually an architectural smell.
- If the interceptor was **observer-shaped** (audit, telemetry, schema-validation-via-trace): wrong tool; convert to `register-trace-cb!`.
- If "global" really meant **"the default frame's events"** (a common single-frame habit that shouldn't apply to story/test/SSR frames): scope to `:rf/default` `:interceptors` only.

`clear-global-interceptor` has no v2 replacement: re-register the frame with an updated `:interceptors` vector.

**Decision shape** (per interceptor):

1. Read the interceptor body. Modifies behaviour? Observes only? Both?
2. Present the three rewrite paths with a recommendation based on the body.
3. Author confirms; apply.

---

## M-18 — `reg-sub-raw` rewrite-path picking

**Identify**: every `(rf/reg-sub-raw :id ...)`.

**Risk**: `reg-sub-raw` is gone. The substrate has explicit replacements for each legitimate use; some patterns are anti-patterns that v2 deliberately removes (subs with side effects, subs that hold state outside `app-db`).

**Decision shape** — read the raw body and pick:

1. **Body reads only `app-db`**: convert to `reg-sub`. Most call sites hit here. Mechanical when the body is straightforward.
2. **Body subscribes to a non-app-db reactive source** (WebSocket, timer, external pub/sub): convert to a registered fx that dispatches events; the sub reads `app-db`. See Pattern-AsyncEffect.
3. **Body manages reaction lifecycle** (explicit track/dispose, on-mount/on-dispose hooks): convert to a state machine. The machine has entry/exit/data lifecycle; the snapshot lives in `app-db`.
4. **Body has side effects** (writes to `app-db`, fires `dispatch`, mutates external state): anti-pattern. Move the side effect into an event handler; the sub reads the resulting `app-db` state. **Flag as a code-quality finding** alongside the rewrite.

Present the categorisation per call site with the proposed rewrite; the author confirms before each is applied.

---

## M-19 — Opt-in map-payload migration

**Trigger**: **only if the author has explicitly asked for opt-in modernisations.** Never as part of a routine v1→v2 migration.

**Identify**: every multi-positional dispatch / subscribe call. The trigger is intent (the author chooses per-event-id when to migrate); the rewrite is mechanical given good information.

**Risk**: rewriting one side (dispatch site) without the other (registration destructure) breaks the runtime. Rewrites must be atomic per event-id.

**Decision shape** (per event-id):

1. Find the registration for the id. Read the handler's positional destructure: `[_ [_ email password]]` → parameter names `email`, `password`.
2. Walk every dispatch / subscribe call site for the id.
3. Propose the rewrite: `(rf/dispatch [:user/login email password])` → `(rf/dispatch [:user/login {:email email :password password}])`; registration's destructure changes to `[_ [_ {:keys [email password]}]]`.
4. **Flag rather than guess** when:
   - The handler's destructure is anonymous (`[_ event]` with no inner shape) — agent can't infer names.
   - The dispatch is built dynamically (`(rf/dispatch (cons :user/login args))`).
   - Mixed-arity dispatches for the same id (some 2-arg, some 3-arg).
   - Trivial-arity (`[:counter/inc]`) and single-arg (`[:user-by-id 42]`) — do **not** migrate; they stay as-is.

`unwrap` users are pre-canonical at the call site; only the destructure may need a cleanup.

`trim-v` users: drop `trim-v` from the interceptor list and rewrite the destructure to skip the id slot manually.

---

## M-21 — `on-changes` / `enrich` / `after`

**Identify**: each of these three interceptors in any registration's interceptor list. Apply the M-21 mechanical drops for `debug` / `trim-v` first (in `automated-transforms.md`); these three are Type B.

### `on-changes`

**Risk**: the v1 interceptor is gone. v2 ships flows as the registered, toggleable replacement.

**Decision shape**: rewrite `(rf/on-changes f out-path & in-paths)` as a flow:

```clojure
(rf/reg-flow
  {:id     <picked-id>
   :inputs <in-paths>
   :output f
   :path   <out-path>})
```

The author picks the flow's `:id`; the agent suggests `:legacy/<original-event-id>` as a starting point. Also: add `day8/re-frame2-flows` dep + `(:require [re-frame.flows])`.

### `enrich`

**Risk**: ran an arbitrary fn `:after` the handler; could modify `db`. Three replacement paths:

1. **Computing derived state** → Spec 013 flow. Same rewrite as `on-changes`.
2. **Post-handler validation** → registered `:spec` per Spec 010 (Malli schema on the registration's metadata map).
3. **Imperative escape hatch** → custom `->interceptor` with the original body.

Read the `enrich` body and propose the path; author confirms.

### `after`

**Risk**: ran an arbitrary fn `:after` for side effects. Three replacement paths:

1. **Pure side effect, event-shaped** (analytics, logging, telemetry): canonical replacement is a registered fx returned from the handler: `:fx [[:analytics/track ...]]`.
2. **Must run for every event of a kind**: user-defined `(rf/->interceptor :id :my-thing :after (fn [ctx] ...))`. Named, addressable, queryable.
3. **Vendor-from-v1**: copy `re-frame.std-interceptors/after` into the project as a 7-line utility. Acceptable if the codebase uses it widely as convention.

Read the body and propose; author confirms.

---

## M-23 — `:re-frame/lifecycle` annotation drop

**Identify**: `:re-frame/lifecycle` keys in `reg-sub` metadata (pre-v1 alpha-namespace usage). The mechanical alpha → core rewrite drops these. Type B comes in if the annotation was non-default (`:no-cache`, `:forever`, `:reactive`).

**Risk**: the v2 sub-cache uses a single algorithm — deferred ref-counting with grace-period. The four v1 lifecycle policies don't exist; specific edge cases that genuinely needed `:no-cache` or `:forever` are uncovered.

**Decision shape**:

1. Drop the annotation; the default policy almost always covers the case.
2. If the author confirms a real need for the non-default policy (the call site explanation matters), **file a follow-up bead** naming the use case. Don't invent a v2 API. Surface to Mike.

---

## M-26 — `add-post-event-callback` / `remove-post-event-callback`

**Identify**: every `(rf/add-post-event-callback ...)` / `(rf/remove-post-event-callback ...)`.

**Risk**: the v1 per-frame post-event hook is subsumed by the trace listener API in most cases, but if the callback was behaviour-modifying (rare; should have been a frame-level interceptor), the trace listener is the wrong tool.

**Decision shape**:

1. **Observer-shaped callback**: convert to `(rf/register-trace-cb! key cb)`. Trace listeners see every dispatched event; filter on `:operation` for the equivalent.
2. **Behaviour-modifying callback**: convert to a frame-level interceptor declared in `reg-frame` metadata.

Read the callback body; categorise; propose; author confirms.

---

## Anti-pattern: silent rewrites

The Type B rules exist because the rewrite **cannot** be inferred from the call site alone. If you find yourself wanting to "just rewrite" one of these without asking — stop. The whole point of Type B is that asking is cheaper than rolling back a wrong rewrite.

The only Type B item the agent can apply without asking is when the author has pre-authorised a specific decision shape upfront (e.g. "for every `reg-sub-raw` that only reads `app-db`, just rewrite to `reg-sub`; flag the rest"). Bank those pre-authorisations in the report so the author can audit them.
