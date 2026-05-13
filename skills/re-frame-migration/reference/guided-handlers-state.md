# guided-handlers-state

Type B walkthroughs covering event handlers, registration shape, view-under-frame routing, render-count test re-baselining, error handlers, routing fallbacks, and top-level db seeding. Each section gives the **identification** (how to find the call sites), the **risk explanation** (what to tell the author), and the **decision shape** (what the author must choose between). The agent identifies and explains; the author decides; the agent then applies.

For interceptor- / subscription- / payload- / observer-shaped Type B rewrites, see [`guided-interceptors-subs.md`](guided-interceptors-subs.md). For Type A patterns, see [`auto-call-site-rewrites.md`](auto-call-site-rewrites.md) and [`auto-cross-cutting.md`](auto-cross-cutting.md). For full rule rationale, see [`MIGRATION.md`](../../../spec/MIGRATION.md).

## Contents

- M-3 — run-to-completion drain
- M-5 — Var-aliased `reg-*`
- M-10 — reserved-namespace collision
- M-11 — plain Reagent fns under non-default frames
- M-12 — render-count test re-baseline
- M-13 — `reg-event-error-handler`
- M-14 — `:rf.route/not-found` requirement (only if adopting Spec 012)
- M-15 — top-level `app-db` seeding

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

## Anti-pattern: silent rewrites

The Type B rules exist because the rewrite **cannot** be inferred from the call site alone. If you find yourself wanting to "just rewrite" one of these without asking — stop. The whole point of Type B is that asking is cheaper than rolling back a wrong rewrite.

The only Type B item the agent can apply without asking is when the author has pre-authorised a specific decision shape upfront (e.g. "for every plain Reagent fn under a non-default frame, just convert to `reg-view`; flag the rest"). Bank those pre-authorisations in the report so the author can audit.
