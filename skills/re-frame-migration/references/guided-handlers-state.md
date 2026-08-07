# guided-handlers-state

Type B walkthroughs covering event handlers, registration shape, view-under-frame routing, render-count test re-baselining, error handlers, routing fallbacks, top-level db seeding, the retired `:rf/runtime` app-db root (now a hard error), machine spawn-id tracking, and the React-19-removed Reagent surfaces. Each section gives the **identification** (how to find the call sites), the **risk explanation** (what to tell the author), and the **decision shape** (what the author must choose between). The agent identifies and explains; the author decides; the agent then applies.

For interceptor- / subscription- / payload- / observer-shaped Type B rewrites, see [`guided-interceptors-subs.md`](guided-interceptors-subs.md). For Type A patterns, see [`auto-call-site-rewrites.md`](auto-call-site-rewrites.md) and [`auto-cross-cutting.md`](auto-cross-cutting.md). For full rule rationale, see [`MIGRATION.md`](../../../migration/from-re-frame-v1/README.md).

## Contents

- M-3 — run-to-completion drain
- M-5 — Var-aliased `reg-*`
- M-10 — reserved-namespace collision
- M-11 — plain Reagent fns under any frame (including the default/single frame) — incl. the async listener / timer / error-handler class
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

1. Find every React-context frame boundary — the SCOPE-only `(rf/frame-provider {:frame <id>} …)` and the ENSURE `(rf/frame-root {:id <id>} …)` — **regardless of which frame it establishes, the root/default boundary included**. (Two components, one verb each — *roots ensure; providers scope* — so match both component names when sweeping.) A single-frame app has exactly **one** such boundary at its root — walk it too. The footgun is not confined to non-default frames; under EP-0002 a plain fn can't read **any** provider's frame from context.
2. Walk the hiccup subtree under each such provider. List every Var-referenced function (or anonymous lambda) that is **not** registered via `rf/reg-view`. Cross-reference the `(rf/registrations :view)` registry.

**Risk**: a plain Reagent function rendered inside a `frame-provider` can't read the provider's frame (no `:contextType`), so its internal `(subscribe ...)` / `(dispatch ...)` calls carry no frame and raise `:rf.error/no-frame-context` (EP-0002 — no silent `:rf/default` routing; the old one-time warning is superseded by this loud error). The failure surfaces at runtime when the component renders.

**Decision shape** (per component-under-frame pair):

1. **Convert to `reg-view`**. Replace `(defn my-view [args] ...)` with `(rf/reg-view ^{:doc "..."} my-view [args] ...)`. The view picks up the surrounding frame correctly. Recommended.
2. **Carry the frame explicitly**. Keeping it a plain fn means carrying its frame *in*, since it cannot read the provider's from context (that is the very premise above). A **bare** no-arg `(rf/capture-frame)` in the body does **not** fix it — with no readable scope it repeats the same ambient lookup and re-raises `:rf.error/no-frame-context`. Carry the target instead: `(rf/capture-frame frame-id)` locked to a named frame, a `{:frame <id>}` opt on each `dispatch` / `subscribe`, or a frame api captured in a frame-aware ancestor and threaded down as a prop — then route `(dispatch [...])` / `(subscribe [...])` through it (the captured handle, unlike the ambient binding, survives into any async callback the body sets up). A no-arg capture succeeds wherever the standard resolver currently yields a frame — a registered view's render and a synchronous `with-frame` around the operation are the examples that matter here, but a handler, an effect, a test binding, or an SSR render resolves one just as legitimately; the whitelist is the live resolver, not that short pair of shapes. The unregistered plain fn under a provider is simply the case with no resolvable scope at all.
3. **Leave as-is** — *only* when the component never calls `rf/subscribe` / `rf/dispatch` (a pure presentational fn with no frame-scoped reads), **or** when it establishes / carries a frame explicitly inside its own body (a `with-frame`, an explicit `{:frame <id>}` op opt, or a captured `(rf/capture-frame)`). Do **not** describe this as pinning to `:rf/default`: there is no `:rf/default` fall-through (EP-0002), so a frame-dependent plain fn left under any provider — the default/root included — raises `:rf.error/no-frame-context` at render; it does not silently route to a default. To *intentionally* target `:rf/default` (e.g. a "global" UI primitive), the component must scope or pass that frame explicitly like any other; document why.

### The single-frame case — a forced whole-tree sweep, not the opt-in O-2

The footgun is most visible in a multi-frame app, but it is **not multi-frame-specific**. Under EP-0002 there is no `:rf/default` fall-through: a plain fn carries no `:contextType`, so it cannot read **any** provider's frame from React context — the default/root provider included — and `with-frame`'s dynamic-var tier does not cross React's render boundary. So a **single-frame app** whose whole view tree is plain bare-`subscribe`/`dispatch` fns under one root `frame-provider` has **every** such fn throw `:rf.error/no-frame-context` the moment it renders.

There is **no ambient-default shortcut**, and there is **no "one root `reg-view` fixes the plain descendants"**: each plain fn is its own React component, so converting the root view does nothing for the unregistered fns below it — every frame-dependent fn must carry its own `:contextType`. The fix takes the same two shapes as the decision above, applied to the **whole tree**:

- **Convert every frame-dependent plain fn to `reg-view`** — each then carries its own `:contextType` and resolves the root frame — **or**
- **Carry the frame explicitly per fn** — a targeted `(rf/capture-frame frame-id)`, a `{:frame <id>}` opt, or a frame api threaded down from a frame-aware ancestor. (A **bare** no-arg `(rf/capture-frame)` in the plain fn re-raises, same as the bare `subscribe`: with no `:contextType` it has no readable scope to capture.)

For a bare-`dispatch`/`subscribe` plain-fn tree this conversion is **FORCED, not optional** — it is the boots-or-not floor (M-11), not the opt-in modernisation O-2. O-2 is a *clean* view that already works being made multi-frame-ready; here the app **does not boot** until the tree is converted (or each fn captures a frame). Name the extent honestly: it is a **whole-tree sweep**, potentially every view file in the app. **Size it in the Phase-0a inventory** — count the unregistered frame-dependent plain fns up front — rather than discovering the extent when boot throws `:rf.error/no-frame-context`.

### Executing the conversion: subscribing views from defn to reg-view

The decision and single-frame sweep above answer *which* components convert and *how big* the pass is; this is the **execution mechanics** — the per-component rewrite, form by form. For a single/default-frame app the disposition is overwhelmingly "convert," so the bulk of M-11 is one mechanical-but-large `defn` → `reg-view` sweep across the view layer (a single real app commonly carries dozens of subscribing components across its view files — one field migration converted 30+ across 22 files, the largest single rewrite of that migration). Verify each form against [`spec/004D-Freehand-Compiled-Grammar.md` §Form-1, Form-2, Form-3](../../../spec/004D-Freehand-Compiled-Grammar.md#removed-forms--normative-absences) before applying.

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

The spec prefers Form-1 + an explicit setup event over Form-2 (see [`spec/004D-Freehand-Compiled-Grammar.md` §Form-2](../../../spec/004D-Freehand-Compiled-Grammar.md#removed-forms--normative-absences)), but a migration preserves behaviour — convert the closure as-is and leave the Form-2 → Form-1 modernisation to the author.

**Form-3 (`reagent.core/create-class` — lifecycle).** The `reg-view` macro **rejects** a `create-class` body at macroexpand (Form-3 is not defn-shape; the error points the author at `reg-view*`), so you cannot wrap the class in `reg-view` and have it pick up `:contextType` the way a function component does. Two routes, both spec-supported:

1. **Extract the subscribing content into a `reg-view` child (preferred).** Keep the class for its lifecycle, but move the part that derefs subs / dispatches into a small `reg-view`-registered child the class renders. The child carries its own `:contextType` and reads the frame; the class manages its DOM/lifecycle and subscribes nothing.
2. **Register the outer fn through `reg-view*` and capture the frame's *non-reactive* ops *once, in that outer callable*, before you build the class.** Per [`spec/004D-Freehand-Compiled-Grammar.md` §Form-3](../../../spec/004D-Freehand-Compiled-Grammar.md#removed-forms--normative-absences), the class ships through `re-frame.core/reg-view*` (the plain-fn surface — no auto-inject). The `reg-view*` outer callable runs under the live resolver scope — the one compiler-visible site in a Form-3 where a reactive read still resolves a frame — so bind the frame ops the **lifecycle hooks** need from `(rf/capture-frame)` **there**, before constructing `create-class`, and close that locked handle over the render fn and every lifecycle hook. Destructure `{:keys [dispatch frame]}` (add `dispatch-sync` if you fire synchronously) — the **non-reactive** ops plus the captured `:frame` handle — for the hooks; for **ordinary** lifecycle work do **not** destructure `subscribe` (the next paragraph explains why a reactive read belongs in render, not a hook, and why a hook that does a one-shot read or an imperative teardown still needs the captured `:frame`). The **one** documented exception is the genuinely imperative long-lived subscription below — the rare case that truly needs a live reactive read *outside* render — which **does** destructure both `:frame` **and** `:subscribe`; it is the sole lifecycle use of the captured `subscribe` op, and it balances every acquire with a matching release (the worked example follows the two bullets). Do **not** move the capture into a lifecycle callback (`:component-did-mount` / `:component-will-unmount` / `:component-did-update`): those fire after the resolver scope has unwound, so a `(rf/capture-frame)` inside one re-raises `:rf.error/no-frame-context`. The handle taken in the outer callable, by contrast, is a locked value that survives into the lifecycle callbacks' async boundary — which is exactly why the render fn and the callbacks share it rather than each re-capturing.

   **Reactive reads stay in the reactive context — never `@(subscribe …)` in a lifecycle hook.** The captured bundle *does* carry `:subscribe`, but its lane is different from `:dispatch`'s. `dispatch` is a fire-and-forget frame-targeted send — safe to close over any callback. `subscribe` returns a **reactive handle**, and a lifecycle body has **no reactive owner**: dereferencing the captured `:subscribe` inside `:component-did-mount` / `:component-did-update` neither establishes render ownership nor tears itself down. Prefer the registered Form-1 outer: it reads the sub reactively and passes the plain value inward as a prop. If a Form-3 renderer exceptionally retains the captured reaction itself, bind it with `r/with-let` inside `:reagent-render`, deref it there, and release it from `with-let`'s `finally`. Do **not** release a render-owned reaction from the class's `:component-will-unmount`: stock Reagent preserves its render owner across React StrictMode's transient will-unmount/did-mount replay, and the user hook would otherwise dispose the reaction the preserved owner still watches.

   **When a hook genuinely must read or tear down a sub, pass the captured `:frame` — a bare call throws.** A lifecycle hook fires *after* the resolver scope has unwound (the same unwind that makes a fresh `(rf/capture-frame)` inside a hook re-raise), so a **bare** `(rf/subscribe-once query-v)` or `(rf/unsubscribe query-v)` there finds no ambient frame and raises `:rf.error/no-frame-context` just as loudly. Reach for the `:frame` you captured in the outer callable and target it explicitly:

   - **One-shot *current* value at mount** (e.g. seeding a JS lib): `(rf/subscribe-once query-v {:frame frame})` reads against the captured frame and retains no handle, so it cannot leak.
   - **A genuinely imperative long-lived subscription** (the rare case that truly needs a live reactive read outside render): acquire it in `:component-did-mount` through the captured `subscribe` op, **give it a reactive owner in the same hook** — a per-mount `(r/track! …)` that derefs it — and pair the two with `(r/dispose! …)` **then** `(rf/unsubscribe frame query-v)` in `:component-will-unmount`. The owner is not optional garnish — without it the acquired reaction is never activated, and the widget is fed once at mount and deaf thereafter (the mechanism is spelled out under the worked example below, and a bare `add-watch` does **not** substitute for it). React StrictMode replays acquire/release as did-mount → will-unmount → did-mount, so each release has a matching acquisition. A teardown that omits the frame throws `:rf.error/no-frame-context` (or, against an already-stale scope, silently fails to decrement and retains the cached reference), leaking the sub-cache slot for the frame's life. This is not ordinary Form-3 code.

   Mind the deliberate call-shape split (per [`spec/006-ReactiveSubstrate.md` §`unsubscribe`](../../../spec/006-ReactiveSubstrate.md#unsubscribe-query-v--nil--unsubscribe-frame-id-query-v--nil)): `subscribe-once` takes the frame as a `{:frame frame}` opts map, mirroring `subscribe`; `unsubscribe` is **frame-first** — `(unsubscribe frame query-v)`, with no opts form — because it is pure teardown, never a hot in-view call reached for by muscle-memory. Keep the frame explicit on both.

**The exceptional imperative-subscription Form-3 — copy-pasteable.** The two bullets above are prose; this is the one complete worked form of route 2's rare imperative branch, and the **only** Form-3 that destructures the captured `:subscribe` for lifecycle use. Reach for it **only** when a live reactive read genuinely must live *outside* render — here a JS widget re-fed imperatively as a sub's value changes, driven from a hook rather than React's render commit; the ordinary reactive value belongs in the registered Form-1 outer and arrives as a prop (route 1 / decision-shape 1), and a Form-3 renderer that can do its own deref belongs in `r/with-let` inside `:reagent-render`. The shape: capture **once** in the outer callable and destructure both `:frame` **and** `:subscribe`; retain the `query-v` (closed over from the outer callable) for teardown; **acquire** the subscription in `:component-did-mount` through the captured `subscribe` op; **own** it there with a per-mount `(r/track! …)` — the tracker is the mount's reactive owner, and its eager first run both seeds the widget and puts the shared reaction on the push path; and **release** in `:component-will-unmount` by disposing the tracker first, then `(rf/unsubscribe frame query-v)`. React StrictMode replays that pair as did-mount → will-unmount → did-mount, so every acquire has a matching release and the ref-count balances on replay as well as on a real unmount. The recipe is **stock-Reagent only** — it uses `reagent.core/track!`, which `reagent2.core` (the reagent-slim adapter) deliberately does not ship. This mirrors the stock-Reagent summary in `implementation/adapters/reagent/README.md` point 4.

```clojure
;; The EXCEPTIONAL imperative-subscription Form-3 (route 2's rare branch): a live
;; reactive read held OUTSIDE render, re-feeding an imperative JS widget as the
;; sub's value changes. This is the sole lifecycle use of the captured `subscribe`.
(re-frame.core/reg-view* ::live-gauge
  (fn [gauge-id]
    (let [{:keys [frame subscribe]} (rf/capture-frame)  ; captured ONCE — the live resolver site
          query-v   [:gauge/reading gauge-id]           ; retained query — closed over by both hooks
          !reaction (r/atom nil)                        ; per-MOUNT reaction handle
          !driver   (r/atom nil)                        ; per-MOUNT reactive OWNER (the tracker)
          !widget   (r/atom nil)]                       ; per-MOUNT JS instance
      (reagent.core/create-class
        {:reagent-render (fn [_] [:div.gauge])
         :component-did-mount
         (fn [this]
           (reset! !widget (mk-gauge! this))
           (let [reaction (subscribe query-v)]          ; ACQUIRE via captured subscribe (ref-count +1)
             (reset! !reaction reaction)
             (reset! !driver                            ; OWN it — this mount's reactive owner.
               (r/track!                                ; track! runs the body EAGERLY once: that
                 (fn []                                 ; first run is the seed AND the deref-capture
                   (feed-gauge! @!widget @reaction))))))  ; that puts the reaction on the push path
         :component-will-unmount
         (fn [_]
           (some-> @!driver r/dispose!)                 ; STOP the owner first — no feed after teardown
           (rf/unsubscribe frame query-v)               ; RELEASE — frame-first; balances the acquire
           (destroy-gauge! @!widget)
           (reset! !driver nil)
           (reset! !reaction nil)
           (reset! !widget nil))}))))
```

The acquire (`(subscribe query-v)`, ref-count +1) and the release (`(rf/unsubscribe frame query-v)`, ref-count −1) are the only two touches of the cache slot; every did-mount runs the acquire and every will-unmount runs the release, so StrictMode's did-mount → will-unmount → did-mount replay leaves the slot balanced.

**The tracker is what makes the widget live — this is the whole point of the recipe.** Under the stock-Reagent adapter a subscription *is* a bare `reagent.ratom/Reaction`, built deliberately **without** `:auto-run`, and a Reaction learns its sources only through `deref-capture`. Deref it from a lifecycle hook — outside any reactive context — and it takes the non-reactive branch: it computes the body raw, leaves `watching` nil, and so never enters `app-db`'s watcher set. Nothing can then tell it the value moved. That is why the obvious-looking `add-watch` on the acquired reaction is a **trap** rather than a shortcut: the watch is registered against a node that can never fire, so the widget is fed once at mount and is deaf for the rest of its life, silently and with no error. `(r/track! …)` is the fix because it *is* a reactive owner: its eager first run supplies the `deref-capture` (and doubles as the seed, so there is no separate plain deref to forget), and every later change re-runs it. **The re-feed arrives on a Reagent flush**, not as an inline callback fired from inside the `app-db` write: the tracker is queued like any other reaction and rides the same batched drain a mounted view does. (The Reagent adapter's commit path performs that flush itself, so under `dispatch-sync` the widget is already up to date when the call returns; do not build on that — write the widget as though the feed lands on the following commit, because that is the contract.)

**One tracker per mount, and no watch keys at all.** Equal `(frame, query-v)` subscriptions share **one** cached reaction — the slot the ref-count above tracks — so two mounts of the same gauge observe the same node. Because each mount creates its own tracker, the two observers are independent by construction: no shared callback registry, nothing to key, nothing for a sibling to clobber, and disposing one leaves the other running. Do the teardown in that order — **`r/dispose!` the tracker first, then `unsubscribe`** — so the owner is gone before the cache slot is released and no feed can run against a destroyed widget. A teardown that **omits** the frame — a bare `(rf/unsubscribe query-v)` — throws `:rf.error/no-frame-context` (or, against an already-stale scope, silently fails to decrement and retains the cached reference), leaking the sub-cache slot for the frame's life. Everything else — the ordinary reactive value, and a reaction the *renderer itself* retains (`r/with-let` inside `:reagent-render`, released from its `finally`, per the paragraph above) — stays out of the hooks.

**What you register through `reg-view*` is the *outer closure fn*, not a bare singleton `(create-class …)`.** The singleton form — `(reg-view* ::id (reagent.core/create-class {…}))` — is valid **unchanged only when the class is frame-independent**: it subscribes and dispatches nothing frame-scoped (pure DOM/lifecycle over its props), or it targets a fixed frame explicitly on every op (`{:frame …}`). A bare singleton has **no per-mount outer callable**, so it has **no live resolver site** at which to capture a frame — a `(rf/capture-frame)` placed in its `:reagent-render` or any lifecycle hook repeats the ambient lookup and re-raises `:rf.error/no-frame-context`, exactly like a bare plain fn under a provider. A **frame-dependent** singleton is therefore under-specified as written and must be rewritten to acquire a capture site: give it the per-mount outer factory (route 2) so the class is constructed under the live resolver scope, or extract its frame-touching content into a `reg-view` child (route 1). Concretely:

```clojure
;; BEFORE — a frame-dependent singleton: no outer callable, so the class body
;; has no scope to read the provider's frame; the dispatch re-raises
;; :rf.error/no-frame-context at mount.
(re-frame.core/reg-view* ::ticker
  (reagent.core/create-class
    {:reagent-render      (fn [] [:div.ticker])
     :component-did-mount  (fn [_] (rf/dispatch [:ticker/started]))}))   ; no frame in scope

;; AFTER (route 2) — wrap in a per-mount outer callable; capture the frame there.
(re-frame.core/reg-view* ::ticker
  (fn []
    (let [{:keys [dispatch]} (rf/capture-frame)]                         ; live resolver site
      (reagent.core/create-class
        {:reagent-render      (fn [] [:div.ticker])
         :component-did-mount  (fn [_] (dispatch [:ticker/started]))}))))
```

The dominant real Form-3 (charts, maps, editors: per-instance JS state + lifecycle + props) wraps the class in an **outer fn** that closes over per-mount `r/atom`s + props and *returns* the `create-class`. `reg-view*`'s render-fn argument is **any callable** (per [`spec/004D-Freehand-Compiled-Grammar.md` §`reg-view*`](../../../spec/004D-Freehand-Compiled-Grammar.md#removed-forms--normative-absences): "a plain function … the body can be any callable"), and that outer fn is a callable, so you register **it** — `(reg-view* ::id my-outer-fn)`, optionally `(def my-view (rf/view ::id))` for a hiccup handle. The per-mount state stays in the closure exactly as the v1 source had it; capture the frame **once in the outer callable** — the live resolver site — as in route 2 above, never inside a lifecycle hook. A behaviour-preserving migration **keeps the outer-fn closure** — do not rewrite it into a Reagent `get-initial-state`/`reagent-state` shape.

```clojure
;; v1 Form-3 — the dominant shape: an OUTER fn closes over per-mount instance
;; state + props and RETURNS the class.
(defn chart [series]
  (let [!inst (r/atom nil)]                          ; per-MOUNT state — one atom per instance
    (reagent.core/create-class
      {:reagent-render         (fn [series] [:div.chart])
       :component-did-mount    (fn [this] (reset! !inst (mk-chart! this series)))
       :component-will-unmount (fn [_] (destroy! @!inst))})))

;; v2 — register the OUTER fn through reg-view* (any callable). The per-mount
;; !inst and props stay in the closure; capture the frame ONCE here in the outer
;; callable — the live resolver site — before building the class, then close that
;; one locked handle over the render fn AND the lifecycle hooks.
(re-frame.core/reg-view* ::chart
  (fn [series]
    (let [{:keys [dispatch]} (rf/capture-frame)      ; captured ONCE, in the live outer callable
          !inst (r/atom nil)]                        ; still per-MOUNT — unchanged
      (reagent.core/create-class
        {:reagent-render
         (fn [series]
           [:div.chart {:on-click #(dispatch [:chart/clicked])}])  ; closes over the locked handle
         ;; the lifecycle hooks fire AFTER the resolver scope has unwound — they
         ;; reach the frame ONLY through the outer-captured `dispatch`, never a
         ;; fresh (rf/capture-frame) of their own (that would re-raise
         ;; :rf.error/no-frame-context). The locked handle survives the boundary.
         :component-did-mount    (fn [this]
                                   (reset! !inst (mk-chart! this series))
                                   (dispatch [:chart/mounted]))
         :component-will-unmount (fn [_]
                                   (destroy! @!inst)
                                   (dispatch [:chart/unmounted]))}))))
;; optional hiccup handle for call sites — [chart series]:
(def chart (rf/view ::chart))
```

**Capture-once is locked to *one* frame — the mount must not be retargetable, or use route 1.** The route-2 handle is captured when the outer callable runs, which is **once per mount**; it is a locked value, not a live re-resolver. That is correct as long as the instance stays under the same frame for its whole life. It breaks if a *surviving* Form-3 instance can be **retargeted from provider A to provider B** — the same React subtree re-parented under a different `frame-provider`, or a provider whose `:frame` prop changes without unmounting the child. React keeps the instance mounted, so the outer callable does **not** re-run, and the locked handle keeps sending render/lifecycle actions to the stale **A** — never **B**. Two frame-safe choices, no mutable-capture machinery:

- **Force a remount when the frame can change** — give the component a React `key` derived from the frame id (`^{:key (str frame-id)} [chart series]`). A frame change then changes the key, React remounts, the outer callable re-runs, and the capture re-locks to **B**. Use this when the capture-once route is otherwise the right fit and retargeting is rare.
- **Use route 1 (the registered `reg-view` child)** — the child reads its frame from React context on **every** render via its `:contextType`, so it follows A→B with no remount and no re-capture. Prefer this when the provider genuinely changes under a long-lived instance.

Do **not** reach for a re-pointable bundle or a mutable frame handle to "fix" this — capture-once is a locked value by design; frame-invariance (or the context-reading child) is the mechanism.

**Do not hoist the per-mount `r/atom` to module scope to fit a bare-singleton example.** A module-level atom is created once and *shared across every mount*, silently fusing all instances' state — a per-instance-state bug that compiles clean and surfaces only when a second instance mounts. The per-mount atom must stay inside the outer fn so each mount gets its own; that is exactly why you register the outer fn rather than a singleton `(create-class …)` value.

**A fourth body shape — `defmulti` / `defmethod`-dispatched views.** A large app commonly renders a polymorphic view as a `defmulti` plus one `defmethod` per variant, each method returning subscribing hiccup. This shape maps to none of Form-1/2/3, and (like Form-3) it cannot be `reg-view`-wrapped: a `defmethod` body is a list, not an args vector, so the macro rejects it at macroexpand the same way it rejects a `create-class` list. The multimethod is also invoked in function position, so a subscribing method body runs inline with no React boundary and throws `:rf.error/no-frame-context` when it derefs a sub or dispatches. The fix is exactly the Form-3 route-1 recipe, named for the `defmethod` shape: **extract each method's subscribing hiccup into its own `reg-view` child, and reduce each `defmethod` to a thin dispatcher that returns `[child-view props]` in *hiccup* position** — so the child mounts as a component carrying its own `:contextType` and resolves the frame. The `defmethod` itself then subscribes nothing; it only selects and returns the child in brackets. (The `defmulti` declaration is unchanged.)

**Scope — convert subscribers only; do not over-convert.** The conversion is keyed on *frame-dependence*, not on being a view. Convert a component **iff** it derefs a sub or dispatches in its render (the calls that raise `:rf.error/no-frame-context`). **Leave as `defn`** every non-subscribing helper and every pure value-taking presentational component (one that renders only its args) — these depend on no frame, so converting them is churn. This is option 3 of the decision table applied at conversion time.

**Compile-silent — only the boot-smoke catches a miss.** A `defn` view is valid ClojureScript: the unconverted component compiles **0 errors / 0 warnings** and is invisible in the build log. The gap surfaces only at runtime, when the component first renders and throws `:rf.error/no-frame-context`. Because it throws on *render* (not at ns-load), it is precisely the silent class the Phase-4 [boot smoke-test](runtime-smoke-test.md) exists to catch: boot the dev build, exercise each first-screen surface, and watch the console for `:rf.error/no-frame-context`. Treat the sweep as unverified until a clean boot renders the screens — which is why Phase-0a **sizes** it (see the single-frame case above) rather than discovering the extent one render-crash at a time.

**After converting — fix any function-position call site of a converted view.** A `reg-view`'s frame wiring rides the component's `:contextType`, which the substrate attaches **only when it mounts the view as a component** — i.e. when the view appears as the head of a hiccup vector, `[my-view props]`. A v1 codebase that called the old plain render fn in **function position**, `(my-view props)`, still compiles after the conversion but bypasses the mount: no component is minted, no `:contextType` attaches, and the converted body's injected `subscribe` / `dispatch` raise `:rf.error/no-frame-context` when they run. So add one identify/verify step to the sweep: after converting the view symbols, grep each converted symbol used in call position — `(my-view …)` — and rewrite it to hiccup, `[my-view …]`. (The bracket-head lookup form `[(rf/view :my-view) …]` is also hiccup and mounts normally; only the **bare** `(my-view …)` call is the footgun.)

> **Not M-11 — an in-handler `@(rf/subscribe …)` deref.** M-11 is the no-frame-context footgun for a *view* (or an escaped callback) that has no surrounding scope to read a sub from. An `@(rf/subscribe …)` deref **inside a `reg-event` handler** is a different shape: the handler already holds app-db as the `:db` coeffect, so recompute the value from `:db` — it needs no `reg-view` / `(rf/capture-frame)` / `with-frame`. Triage it by source, not by context: see [`causal-world-inputs.md` §source triage](causal-world-inputs.md#triage-in-handler-reads-by-source).

### The async listener timer and error-handler class

M-11 above operationalises the no-frame-context footgun for **views**; M-40 ([`auto-cross-cutting.md` §Boot-sequence invariant](auto-cross-cutting.md#boot-sequence-invariant--init-must-run-before-the-first-dispatch-and-the-first-render)) operationalises it for the **synchronous boot kick**. Both are instances of one general rule:

> **Under EP-0002, ANY bare `rf/dispatch` / `rf/subscribe` reachable OUTSIDE a render AND OUTSIDE an explicit frame scope (a `with-frame` body or a captured `(rf/capture-frame)`) raises `:rf.error/no-frame-context`.** Frame identity is carried, not found; there is no ambient `:rf/default` the runtime synthesises in its place.

The instance the skill did not yet name is the **async / non-render class** — code that registers a callback *outside any view* and bare-dispatches when it **later fires**:

- a module-level `(.addEventListener js/window "popstate" …)` / `"hashchange"` routing listener;
- a `js/setTimeout` / `js/setInterval` / `js/requestAnimationFrame` timer;
- a `js/window` `'error'` / `.-onerror` global error handler;
- a third-party JS-lib lifecycle callback (a map's `moveend`, a chart's `onClick`, a socket's `onmessage`).

Each is registered outside any `frame-provider` and fires **after** boot, so there is no render around the callback body to read a frame from — its bare `dispatch` / `subscribe` raises `:rf.error/no-frame-context` at first fire.

**CRUCIAL — the remedy differs from the M-40 boot kick.** The boot kick is fixed by a `with-frame` *around the synchronous boot dispatch*. That does **not** carry here: a `with-frame` (or a `frame-provider`) established at **registration time** is a dynamic binding that is **already unwound by the time the async callback fires** — wrapping the `addEventListener` / `setTimeout` call itself in `with-frame` leaves the callback *body* running under no scope, and it still raises `:rf.error/no-frame-context`. Re-establish the frame **inside the callback**, two ways:

1. **Wrap the dispatch in `(rf/with-frame app-frame …)` at FIRE time** — inside the callback body, not around its registration.
2. **Close the callback over a `(rf/capture-frame)` taken where a frame IS in scope** — capture the frame api under an existing scope, then call its `:dispatch` / `:subscribe` from the callback. The captured handle, unlike the ambient dynamic binding, **survives the async boundary** (the property M-11 decision-shape 2 above relies on).

```clojure
;; v1 — a module-level routing listener bare-dispatches. In v1 the global
;; app-db ratom + ambient registry made this fine.
(defn init-router! []
  (.addEventListener js/window "popstate"
    (fn [_] (rf/dispatch [:route/changed (-> js/window .-location .-pathname)]))))

;; v2 WRONG — with-frame around REGISTRATION does not reach the callback. By the
;; time popstate fires the dynamic binding is gone, so the bare dispatch in the
;; listener body raises :rf.error/no-frame-context.
(defn init-router! []
  (rf/with-frame app-frame                              ; unwound before the listener fires
    (.addEventListener js/window "popstate"
      (fn [_] (rf/dispatch [:route/changed (-> js/window .-location .-pathname)])))))

;; v2 RIGHT (1) — re-establish the frame INSIDE the callback, at fire time.
(defn init-router! []
  (.addEventListener js/window "popstate"
    (fn [_]
      (rf/with-frame app-frame
        (rf/dispatch [:route/changed (-> js/window .-location .-pathname)])))))

;; v2 RIGHT (2) — capture the frame api where a frame IS in scope, then close
;; the callback over it; the captured dispatch survives async.
(rf/with-frame app-frame
  (let [{:keys [dispatch]} (rf/capture-frame)]          ; captured under the scope
    (.addEventListener js/window "popstate"
      (fn [_] (dispatch [:route/changed (-> js/window .-location .-pathname)])))))
```

The same two remedies cover `setTimeout` / `setInterval` / `requestAnimationFrame` callbacks, a `js/window` `'error'` handler, and any JS-lib lifecycle callback that dispatches. **Phase-0a grep these** (`addEventListener`, `set(Timeout|Interval)`, `requestAnimationFrame`, `onerror` / `'error'`, JS-lib callbacks that bare-dispatch — see [`inventory-and-plan.md`](inventory-and-plan.md)); and the boot smoke-test must **exercise** each one (fire the listener / let the timer elapse / raise the error), because the throw lands only at first fire, not at boot — a boot-without-firing scan misses it ([`runtime-smoke-test.md`](runtime-smoke-test.md)).

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
3. **`force-update-all` (Type B — ask first; slim only)**: had no documented use beyond global-rebuild scripts. Flag every site and ask the maintainer whether it can be removed entirely; if not, file a GitHub issue (per the shared [`issue-filing.md`](../../shared/issue-filing.md) recipe) rather than inventing a replacement. (Available unchanged on the bridge.)

Apply the render mount-path half mechanically (in the adapter's namespace); flag the `dom-node` / `force-update-all` half and wait for the author. Full rationale + the removed-surface list: [`MIGRATION.md` §M-42](../../../migration/from-re-frame-v1/README.md#m-42-react-19-removed-reagent-surfaces-are-absent-under-day8reagent-slim-compile-time-unresolved-var) — note its heading and shim list predate the slim change that made these surfaces **absent** (compile-time unresolved-var) rather than throw-on-call.

---

## Anti-pattern: silent rewrites

The Type B rules exist because the rewrite **cannot** be inferred from the call site alone. If you find yourself wanting to "just rewrite" one of these without asking — stop. The whole point of Type B is that asking is cheaper than rolling back a wrong rewrite.

The only Type B item the agent can apply without asking is when the author has pre-authorised a specific decision shape upfront (e.g. "for every plain Reagent fn under a non-default frame, just convert to `reg-view`; flag the rest"). Bank those pre-authorisations in the report so the author can audit.
