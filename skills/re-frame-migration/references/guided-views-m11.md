# guided-views — M-11: subscribing plain fns under a frame

The **M-11 sweep** — converting plain `(defn …)` Reagent views that `subscribe` / `dispatch` in their render into frame-resolving components. This is typically the **largest single conversion** in a migration, so it is its own leaf: the decision table, the single-frame case, the `defn` ‒→ `reg-view` execution recipe (Form-1 / Form-2 / Form-3 and the `defmulti` shape), the `reg-view*` **capture-once** contract, and the async listener / timer / error-handler class.

This is the **canonical migration recipe** for the Form-3 routes and the capture-once retarget invariance; the adopter-facing counterpart is [`FORM-3.md`](../../../implementation/adapters/reagent-slim/FORM-3.md). The other Type B handler / state walkthroughs (M-3, M-5, M-10, M-12, M-13, M-14, M-15, M-15b, M-34, M-42) stay in the sibling leaf [`guided-handlers-state.md`](guided-handlers-state.md).

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

The decision and single-frame sweep above answer *which* components convert and *how big* the pass is; this is the **execution mechanics** — the per-component rewrite, form by form. For a single/default-frame app the disposition is overwhelmingly "convert," so the bulk of M-11 is one mechanical-but-large `defn` → `reg-view` sweep across the view layer (a single real app commonly carries dozens of subscribing components across its view files — one field migration converted 30+ across 22 files, the largest single rewrite of that migration). The three form-by-form recipes below are the reference for that pass.

**Before you sweep — add re-frame2's clj-kondo config, or you cannot assert the conversion is "lint clean."** clj-kondo does not macroexpand, so it cannot read `reg-view` (a `defn`-shape macro that auto-injects `dispatch` / `subscribe`) unaided. re-frame2 carries the config that teaches it at its own repo root — `.clj-kondo/config.edn` plus the macro hook it points at, `.clj-kondo/hooks/re_frame/core.clj`. **Copy the hook file** into the consuming project's own `.clj-kondo/` (keep the `hooks/re_frame/` sub-path), then **add the two entries to your existing `config.edn`** rather than copying re-frame2's wholesale — that file is repo-specific (a `:config-paths` pointing at re-frame2's in-tree Hicasso export, `re-frame.story/*` `:lint-as` rows, and an `:output {:exclude-files …}` block for its own directories), so copied verbatim it warns about a missing config path on every lint. The two entries are the `:hooks {:analyze-call {re-frame.core/reg-view hooks.re-frame.core/reg-view}}` wiring (add the `re-frame.core/with-frame` / `with-new-frame` entries too if the app uses those macros) and the `re-frame.core/reg-view` exclusion from `:unresolved-var`; the hook rewrites `(reg-view sym [args] body)` to a `(defn sym [args] (let [dispatch … subscribe …] body))`, so clj-kondo resolves the view's own symbol and reads the injected ops as real locals. (`reg-view` is the lone hook case — the rest of the `reg-*` family ride a `:lint-as clojure.core/do` entry in the same file.)

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

Form-1 + an explicit setup event is the preferred shape over Form-2, but a migration preserves behaviour — convert the closure as-is and leave the Form-2 → Form-1 modernisation to the author.

**Form-3 (`reagent.core/create-class` — lifecycle).** The `reg-view` macro **rejects** a `create-class` body at macroexpand (Form-3 is not defn-shape; the error points the author at `reg-view*`), so you cannot wrap the class in `reg-view` and have it pick up `:contextType` the way a function component does. Two routes, both spec-supported:

1. **Extract the subscribing content into a `reg-view` child (preferred).** Keep the class for its lifecycle, but move the part that derefs subs / dispatches into a small `reg-view`-registered child the class renders. The child carries its own `:contextType` and reads the frame; the class manages its DOM/lifecycle and subscribes nothing.
2. **Register the outer fn through `reg-view*` and capture the frame's *non-reactive* ops *once, in that outer callable*, before you build the class.** Per `spec/API.md`'s `reg-view*` row — which names Reagent Form-3 (`create-class`) as one of its uses — the class ships through `re-frame.core/reg-view*` (the plain-fn surface — no auto-def, no auto-inject, no compile check). The `reg-view*` outer callable runs under the live resolver scope — the one compiler-visible site in a Form-3 where a reactive read still resolves a frame — so bind the frame ops the **lifecycle hooks** need from `(rf/capture-frame)` **there**, before constructing `create-class`, and close that locked handle over the render fn and every lifecycle hook. Destructure `{:keys [dispatch frame]}` (add `dispatch-sync` if you fire synchronously) — the **non-reactive** ops plus the captured `:frame` handle — for the hooks; for **ordinary** lifecycle work do **not** destructure `subscribe` (the next paragraph explains why a reactive read belongs in render, not a hook, and why a hook that does a one-shot read or an imperative teardown still needs the captured `:frame`). The **one** documented exception is the genuinely imperative long-lived subscription below — the rare case that truly needs a live reactive read *outside* render — which **does** destructure both `:frame` **and** `:subscribe`; it is the sole lifecycle use of the captured `subscribe` op, and it balances every acquire with a matching release (the worked example follows the two bullets). Do **not** move the capture into a lifecycle callback (`:component-did-mount` / `:component-will-unmount` / `:component-did-update`): those fire after the resolver scope has unwound, so a `(rf/capture-frame)` inside one re-raises `:rf.error/no-frame-context`. The handle taken in the outer callable, by contrast, is a locked value that survives into the lifecycle callbacks' async boundary — which is exactly why the render fn and the callbacks share it rather than each re-capturing.

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

The dominant real Form-3 (charts, maps, editors: per-instance JS state + lifecycle + props) wraps the class in an **outer fn** that closes over per-mount `r/atom`s + props and *returns* the `create-class`. `reg-view*`'s render-fn argument is **any callable** — `spec/API.md` types it `(reg-view* id render-fn)` with no compile check on the body — and that outer fn is a callable, so you register **it** — `(reg-view* ::id my-outer-fn)`, optionally `(def my-view (rf/view ::id))` for a hiccup handle. The per-mount state stays in the closure exactly as the v1 source had it; capture the frame **once in the outer callable** — the live resolver site — as in route 2 above, never inside a lifecycle hook. A behaviour-preserving migration **keeps the outer-fn closure** — do not rewrite it into a Reagent `get-initial-state`/`reagent-state` shape.

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
