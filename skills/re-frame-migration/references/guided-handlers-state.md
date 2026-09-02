# guided-handlers-state

Type B walkthroughs covering event handlers, registration shape, render-count test re-baselining, error handlers, routing fallbacks, top-level db seeding, the retired `:rf/runtime` app-db root (now a hard error), machine spawn-id tracking, and the React-19-removed Reagent surfaces. Each section gives the **identification** (how to find the call sites), the **risk explanation** (what to tell the author), and the **decision shape** (what the author must choose between). The agent identifies and explains; the author decides; the agent then applies.

The **M-11** view-under-frame sweep (subscribing plain `(defn …)` Reagent views → `reg-view`) is its own leaf: [`guided-views-m11.md`](guided-views-m11.md). For interceptor- / subscription- / payload- / observer-shaped Type B rewrites, see [`guided-interceptors-subs.md`](guided-interceptors-subs.md). For Type A patterns, see [`auto-call-site-rewrites.md`](auto-call-site-rewrites.md) and [`auto-cross-cutting.md`](auto-cross-cutting.md). For full rule rationale, see [`MIGRATION.md`](../../../migration/from-re-frame-v1/README.md).

## Contents

- M-3 — run-to-completion drain
- M-5 — Var-aliased `reg-*`
- M-10 — reserved-namespace collision
- M-11 — plain Reagent fns under any frame (including the default/single frame) — incl. the async listener / timer / error-handler class — **moved to its own leaf: [`guided-views-m11.md`](guided-views-m11.md)**
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
4. **No restructuring needed**: keep the dispatch — the run-to-completion behaviour is strictly better for this site. It still rides the Type-A **M-8** fold into `:fx [[:dispatch …]]` like every other top-level `:dispatch`; the effect-map top level is a closed set of seven keys, so leaving the key literally as-is refuses the whole event pre-commit (`:rf.error/effect-map-shape`, always-on) — the `:db` write goes with it. Nothing beyond the M-8 fold changes here.

Present every call site with its file:line and the four options; collect the author's choice; apply.

---

## M-5 — Var-aliased `reg-*`

**Type A for `apply` of a `reg-*` symbol** (mechanical — rewrite to direct invocation or to a wrapper macro of the author's own); **Type B for Var-aliasing** (`(def my-reg rf/reg-event)`) — when the alias is invoked dynamically the rewrite depends on understanding the call sites, so it is presented, not applied. One section, two dispositions: classify each hit before you touch it.

**Apply M-73 first.** `reg-event-db` / `reg-event-fx` are removed and `reg-event-ctx` is demoted (EP-0018); they survive only as façade stubs that raise `:rf.error/reg-event-db-removed` (and `-fx-removed` / `-ctx-removed`) at registration, aborting the rest of the namespace load. So run the **M-73** collapse to `reg-event` first — this macro rule then applies to the surviving `reg-event` / `reg-sub` / `reg-fx` / `reg-cofx`.

**Identify**:

```clojure
(def my-reg rf/reg-event)         ; capturing the Var as a value — Type B
(apply rf/reg-event [:id handler]) ; apply over a macro — Type A
(map #(apply rf/reg-event %&) ...) ; same shape inside higher-order code — Type A
```

**Risk**: `reg-*` are macros in v2; they can't be Var-aliased or `apply`d. The code fails at compile time. The fix shape depends on whether the higher-order use was essential (e.g. registering a generated list of handlers) or accidental (capturing the Var "just because").

**Decision shape**:

1. **Refactor to direct invocation**. The author has a list of `[id handler]` pairs; replace `(apply rf/reg-event pair)` with a macro of their own that expands to a sequence of direct `reg-event` calls.
2. **Use the functional surface** (where it exists). Exactly two plain-fn partners ship, and they *can* be Var-aliased: `rf/reg-view*` (on the `re-frame.core` façade) and `re-frame.machines/reg-machine*` (in its owning namespace — it is **not** a façade export, so require `[re-frame.machines :as rf.machines]` and call `rf.machines/reg-machine*`). For `reg-event` / `reg-sub` / `reg-fx` / `reg-cofx`, no such partner ships today. If the author truly needs the functional form, **file a GitHub issue against `day8/re-frame2`** rather than working around.

---

## M-10 — Reserved-namespace collision

**Identify**: every `(reg-* :rf/...)` or `(reg-* :rf.<area>/...)` registration.

**Risk**: `:rf/*` and its sub-namespaces are reserved for framework-owned ids. User registrations under reserved keys silently shadow framework extension points (or get overwritten by them on hot-reload), break tooling discoverability, and lose stability.

**Decision shape** (per call site):

1. **Rename to a feature prefix**. Pick the project's own top-level namespace (e.g. `:cart/...`, `:auth/...`). This is the default move.
2. **Intentional override** of a documented framework extension point. Confirm with the author that this is deliberate; leave the registration in place; note it in the report.
3. **Decline**. The author accepts the runtime warning. Rare; document the reasoning in the report.

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

1. **Seed via `:initial-events`**. To seed a literal app-db, use the standard `[:rf/set-db {…}]` event: `(rf/make-frame {:id :rf/default :initial-events [[:rf/set-db initial-state]]})`. To run a boot event instead, point `:initial-events` at it: `(rf/make-frame {:id :rf/default :initial-events [[:app/seed initial-state]]})` plus the `[:app/seed initial]` event handler that writes the seed into `app-db`. (`:initial-events` is a **vector of event vectors** — list multiple steps directly, in order, e.g. `[[:rf/set-db initial] [:app/boot]]`; no `:fx`-fan-out workaround is needed.)
2. **Move the seed to test fixtures only** if the seed is test-specific. Seed the test frame the same way — via `:initial-events` — never a top-level `app-db` poke: `(rf/with-new-frame [f (rf/make-frame {:initial-events [[:test/seed initial]]})] ...)`.

Present the seed value and the proposed rewrite; confirm with the author; apply both the M-1 require-removal and the M-15 `:initial-events` rewrite together.

---

## M-15b — Wholesale `app-db` replace + the retired `:rf/runtime` root

**Identify**: any event handler that returns a *wholesale* `app-db` value — `(reg-event-db :initialize-db (fn [_ _] fresh-db))`, `{:db fresh-db}` from `:bootstrap` / `:app/reset` / a logout-to-clean-state event — where `fresh-db` is built from scratch rather than threaded from the incoming `db`. Two sub-shapes carry the migration risk: (a) a v1-shaped `fresh-db` that **carries a `:rf/runtime` key** (a hand-rolled runtime stash, or one threaded forward by an older v2-preview rewrite), and (b) any handler that **explicitly writes `:rf/runtime`** into its `:db` return (the old "preserve the runtime" stopgap). The tell for both: the returned `:db` map contains a top-level `:rf/runtime`.

**Risk**: framework runtime no longer lives in `app-db` at all — it sits in a **separate partition, the runtime-db** (`:rf.db/runtime`, subsystem children under `:rf.runtime/*`). A handler's `:db` return replaces **only** the app-db partition and cannot reach it, so the v1-era "wholesale `{:db fresh-map}` boot silently wipes the runtime" footgun is **structurally gone**: nothing to preserve, nothing to clobber, and a boot machine's snapshot survives any app-db replace. (The two-partition contract and the reserved runtime-db key list live in the corpus section linked below — don't re-derive them here.)

The retired app-db root `:rf/runtime` is now a **hard error**. A `:db` value carrying a top-level `:rf/runtime` key throws `:rf.error/legacy-runtime-root` (the always-on post-commit guard `re-frame.events/reject-legacy-runtime-root!`, per [Conventions §The legacy `:rf/runtime` root](../../../spec/Conventions.md#the-legacy-rfruntime-root-hard-error-in-final-form)). The error is loud and immediate (not a silent runtime hang, not the dev-only advisory). So the migration concern flips: the rewrite is not "preserve the runtime across the replace" — it is "**strip the `:rf/runtime` key**; the runtime is no longer your responsibility to thread."

**Decision shape** (per wholesale-replace handler):

1. **Strip any `:rf/runtime` key from the fresh db (always).** A wholesale reset is now safe by construction — `{:db fresh-db}` replaces app-db and leaves machines / routing / SSR untouched in runtime-db. Just ensure `fresh-db` carries **no** `:rf/runtime` key (it would hard-error). If a v1-shaped `fresh-db` or an older preview rewrite still stashes runtime state there, drop it:

   ```clojure
   (rf/reg-event :initialize-db
     (fn [_ _] {:db fresh-db}))   ; fresh-db carries NO :rf/runtime — the runtime-db partition is left alone
   ```

2. **Genuinely need to write runtime state? Use the `:rf.db/runtime` effect, never an app-db key.** Framework/extension code that must seed or replace runtime-db emits the reserved `:rf.db/runtime` effect, keeping application data under `:db`. Outside a handler, the one facade mutator is `re-frame.epoch`'s `replace-frame-state!` — `(replace-frame-state! frame-id {:rf.db/runtime v})` is the runtime-only injection. (`re-frame.frame/replace-runtime-db!` is **not** on the facade; the earlier four-mutator family collapsed to this one fn, so a call site that requires it is an M-1 off-contract-namespace hit.) App code rarely needs this — boot machines install their own snapshots when they start.

The old `:rf.warning/runtime-state-dropped` containment warning is **retired** — there is no clobber to warn about. Its replacement is the structural `:rf.error/legacy-runtime-root` hard error above, which fires in every build (dev and production) the moment a handler returns a `:rf/runtime`-bearing `:db`.

Present the categorisation and the proposed rewrite; confirm with the author; apply. Full rationale and the canonical before→after: [`MIGRATION.md` §M-15b](../../../migration/from-re-frame-v1/README.md#m-15b-a-full-app-db-replace-boot--initialise-event-is-safe--but-strip-any-retired-rfruntime-key-now-a-hard-error). The end-to-end boot recipe: [`spec/Pattern-Boot.md` §Worked example — the singleton boot machine](../../../spec/Pattern-Boot.md#worked-example--the-singleton-boot-machine).

---

## M-34 — Spawn-id tracking moved to runtime-owned slot

**Identify**: machine specs (Spec 005) that declare a declarative `:spawn` (or hand-emit `[:rf.machine/destroy ...]` from a machine action). Two sub-shapes carry the risk:

1. Specs that declared `:spawn` **without** an `:on-spawn` callback — pre-fix these silently leaked the spawned actor on state-exit (the runtime had no recorded id to destroy).
2. Tests or `:exit` action bodies that **asserted on the old behaviour**: a stale `[:rf.runtime/machines :snapshots <id>]` entry surviving after exit, or that read the spawned id back out of the parent's `[:data :pending]` slot.

**Risk**: the runtime now tracks each spawn-id at the reserved runtime-db slot `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]` instead of reading it from the parent's `:data`. `:on-spawn` becomes purely advisory — apps that omitted it now correctly destroy the child on exit. The **public API is unchanged** — the `:on-spawn` callback signature is the unified context map `(fn [{:keys [data id]}] …)` every machine callback receives (its return is **advisory and dropped**, so `:on-spawn` is not an id-recording mechanism — the runtime records the spawn-id at the reserved runtime-db slot itself), and the destroy fx's keyword form `[:rf.machine/destroy actor-id]` still works. The hazard is silent for code/tests that depended on the old leak or the old `:data`-slot read: those need triage, not a rewrite.

**Decision shape** (per hit site):

1. **`:spawn` without `:on-spawn`, no test dependency**: no rewrite — the spec is now correct-by-default under the runtime-owned registry. Note it in the report.
2. **Test asserts a stale snapshot / leak after exit**: the assertion is now wrong (the actor is correctly destroyed). The author decides whether the test should assert the new correct teardown or whether the spec genuinely wanted the actor to survive (rare — usually means a `:system-id` named machine, not a transient spawn).
3. **`:exit` body reads `(:pending data)` to address the child**: still works (user `:data` is user territory) — leave as-is, but confirm the author still wants the id recorded in `:data` for their own bookkeeping rather than relying on the runtime slot.

Present the categorisation per site; confirm with the author; only then apply. Full rationale: [`MIGRATION.md` §M-34](../../../migration/from-re-frame-v1/README.md#m-34-spawn-id-tracking-moved-from-data-pending-to-runtime-owned-rfruntimemachines-spawned-).

---

## M-42 — React-19-removed Reagent surfaces (bridge *and* slim)

**Trigger**: fires on **both** Reagent paths, because the render call site breaks on the React-19 floor both adapters target. On the **bridge**, stock Reagent 2.x still ships the `reagent.dom/render` Var — but React 19 removed `react-dom/render` underneath it, so the Var warns and no-ops at runtime; the call site must be rewritten to `reagent.dom.client/create-root` + `render`. On **slim**, the legacy `reagent.dom` render surface is absent entirely (the render API lives at `reagent2.dom.client`), so the same call site fails at **compile** time with an unresolved-var error. Either way the render call site needs a createRoot+render **rewrite**; only the **target namespace** differs. The *other* legacy symbols (`dom-node`, `force-update-all`, `unmount-component-at-node`) are **absent on slim** (a compile-time unresolved-var, not a runtime shim throw) but **unchanged on the bridge** (stock Reagent has not removed them — only the React-DOM `render`/`createRoot` floor moved).

> **Do not read "apps on the bridge are unaffected" (MIGRATION.md §M-42) as "the bridge needs no render change."** That sentence is about the legacy Vars still *existing* on the bridge (stock Reagent 2.x has not removed them). But the **render call site still needs the createRoot rewrite on the bridge too**: the `reagent.dom/render` Var survives, yet React 19 removed the `react-dom/render` it delegated to, so it warns and no-ops at runtime. The bridge just targets a different namespace (`reagent.dom.client`) than slim.

**The adapter-keyed render-namespace table** (the render rewrite is the same shape — `create-root` + `render` around the same `container` — only the namespace changes):

| Adapter the app boots | Render namespace (createRoot + render) | Coord |
|---|---|---|
| **classic bridge** (stock Reagent 2.x + `re-frame.adapter.reagent`) | `reagent.dom.client` | `day8/re-frame2-reagent` |
| **slim rewrite** (`re-frame.adapter.reagent-slim`) | `reagent2.dom.client` | `day8/reagent-slim` |

```clojure
;; v1 (both paths) — reagent.dom/render no-ops under React 19 (bridge) / is absent (slim)
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
3. **`force-update-all` (Type B — ask first; slim only)**: had no documented use beyond global-rebuild scripts. Flag every site and ask the maintainer whether it can be removed entirely; if not, file a GitHub issue (per the [`issue-filing.md`](issue-filing.md) recipe) rather than inventing a replacement. (Available unchanged on the bridge.)

Apply the render mount-path half mechanically (in the adapter's namespace); flag the `dom-node` / `force-update-all` half and wait for the author. Full rationale + the removed-surface list: [`MIGRATION.md` §M-42](../../../migration/from-re-frame-v1/README.md#m-42-react-19-removed-reagent-surfaces-are-absent-under-day8reagent-slim-compile-time-unresolved-var) — note its heading and shim list predate the slim change that made these surfaces **absent** (compile-time unresolved-var) rather than throw-on-call.

---

## Anti-pattern: silent rewrites

The Type B rules exist because the rewrite **cannot** be inferred from the call site alone. If you find yourself wanting to "just rewrite" one of these without asking — stop. The whole point of Type B is that asking is cheaper than rolling back a wrong rewrite.

The only Type B item the agent can apply without asking is when the author has pre-authorised a specific decision shape upfront (e.g. "for every plain Reagent fn under a non-default frame, just convert to `reg-view`; flag the rest"). Bank those pre-authorisations in the report so the author can audit.
