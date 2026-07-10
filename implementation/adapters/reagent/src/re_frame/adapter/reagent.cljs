(ns re-frame.adapter.reagent
  "The Reagent adapter — browser default. Implements the substrate
  contract; see `re-frame.substrate.adapter` for the contract itself
  (canonical home: `core/src/re_frame/substrate/adapter.cljc`) and
  Spec 006 §CLJS reference for the per-slot semantics."
  (:require [reagent.core :as r]
            [reagent.ratom :as ratom]
            [reagent.dom.client :as rdc]
            [re-frame.adapter.context :as adapter-context]
            [re-frame.adapter.resource-lease :as resource-lease]
            [re-frame.substrate.spine :as spine]
            [re-frame.views :as views]))

;; ---- shared ratom-spine wiring --------------------------------------------
;;
;; The container quartet, the React-root renderer, the dispose body and
;; the SSR emitter helpers are the SAME shape as reagent-slim, modulo the
;; reactive-atom impl (stock `reagent.*` here, `reagent2.*` there). They
;; live in `re-frame.substrate.spine/make-ratom-spine` (rf2-rzex9) — one
;; implementation, two adapters, zero drift, mirroring the React-hook
;; `make-react-spine` that backs UIx/Helix. The reactive-atom ops are
;; INJECTED here as a flat set of bare fns so the spine never `:require`s a
;; reactive-atom ns: this adapter passes its stock `reagent.*` impls; the
;; slim adapter passes `reagent2.*`. (Load-bearing for reagent-slim bundle
;; isolation — the slim adapter's deps.edn carries zero direct `reagent.*`
;; requires.)
;;
;; rf2-0u5em6: the spine assembles its internal ratom-ops map from these
;; bare fns. Each adapter passes ~7 bare fns (flat config keys, mirroring
;; `make-react-spine`'s bare hook fns) instead of a hand-shaped keyword
;; map — earlier this adapter and the slim one each built a structurally-
;; identical 7-key `:ratom-ops` map differing only by ns-alias, a "keep two
;; maps in lockstep" hazard the flat-bare-fn shape removes.

(def ^:private spine-fns
  (spine/make-ratom-spine
    {;; Substrate-scoped gensym prefix (rf2-l4dmr) so a cross-substrate
     ;; test bundle / log / inspector can attribute a watch to the
     ;; Reagent adapter rather than confusing it with a slim watch.
     :gensym-prefix-sub "rf-reagent-sub-"
     ;; Each op is a thin call-through lambda rather than the bare Var
     ;; value so the spine resolves the namespaced fn at CALL time. This
     ;; keeps the `with-redefs [rdc/create-root …]` test-observability the
     ;; adapter-render / dispose-drain pins rely on (capturing the bare
     ;; Var value at load time would freeze the original impls past any
     ;; `with-redefs` rebind). Runtime behaviour is identical.
     :r-atom        (fn [v] (r/atom v))
     :make-reaction (fn [thunk] (ratom/make-reaction thunk))
     :create-root   (fn [mount-point] (rdc/create-root mount-point))
     :render-root   (fn [root tree] (rdc/render root tree))
     :hydrate-root  (fn [mount-point tree] (rdc/hydrate-root mount-point tree))
     :unmount-root  (fn [root] (rdc/unmount root))
     ;; rf2-40a84 — synchronous render-flush op. Run `f`
     ;; (which may mutate a ratom / dispatch), then
     ;; `reagent.core/flush` drains Reagent's render queue
     ;; SYNCHRONOUSLY — bypassing the rAF-scheduled
     ;; `next-tick` drain — and (on React 19) commits the
     ;; forced component re-renders to the DOM via
     ;; react-dom/flushSync. NOT rAF-scheduled ⇒ fires even
     ;; in a backgrounded / headless tab.
     :flush-render! (fn [f] (f) (r/flush))}))

(def set-hiccup-emitter!
  "Install the hiccup → HTML fn used by render-to-string. Last call wins.
  Per rf2-uo7v / IMPL-SPEC §2.1: published through the late-bind hook
  `:reagent/set-hiccup-emitter!` so the SSR seam at re-frame.ssr
  resolves it at load time without a static :require."
  (:set-hiccup-emitter! spine-fns))

(def flush-views!
  "Flush pending Reagent renders synchronously. Wraps React's act() —
  intended for test code only. Calls (act (fn [] (reagent.core/flush)));
  with `f`, runs `f` then the synchronous render drain inside act. Returns
  nil. When act() is unreachable in the current React build it degrades to
  a plain synchronous flush (still runs `f` and drains the render queue),
  so a `:node-test` runner with no real React render path still flushes.

  Per rf2-3yij Decision 6 / rf2-b6nm5: the canonical test-flush hook,
  surfaced identically (same name, same adapter-ns location, same
  nil-return shape) across all four substrates — Reagent, reagent-slim,
  UIx, Helix — so a test suite ports across substrates touching only the
  init! Var. Previously stock Reagent surfaced no adapter-level flush-views!
  and tests reached for reagent.core/flush or react/act directly."
  (:flush-views! spine-fns))

;; ---- resource-lease mount-lifecycle helper (rf2-cxozh4, EP-0020 §Open Issue #1) --
;;
;; The lease's IDENTITY (owner mint) + LIFECYCLE semantics live ONCE in
;; `re-frame.adapter.resource-lease` (rf2-qdkt8y) — shared with reagent-slim and
;; the React-hook `use-resource-lease` (UIx / Helix), so a mixed-adapter process
;; cannot collide two families' lease owners. This adapter supplies ONLY its
;; stock-`reagent.*` substrate ops + the stock-Reagent context-type wiring
;; (create-class copies a `:context-type` map key to the static `contextType`).

(def with-resource-lease
  "Reagent component that takes a resource liveness LEASE for its mounted
  lifetime (rf2-cxozh4, EP-0020 §Open Issue #1) — the Reagent (Form-3)
  counterpart of the UIx / Helix `use-resource-lease` hook. On mount it
  dispatches `:rf.resource/ensure` with an app-minted `[:lease …]` owner; on
  unmount it releases that lease via `:rf.resource/release-owner`. Use it so
  a view declaratively OWNS a polled / cached resource for as long as it is
  mounted — the mount-lifecycle half of resource ownership that otherwise
  needs a hand-wired Form-3.

  Call it as a Reagent component with the resource descriptor and a body
  thunk (a 0-arg fn returning hiccup — the children rendered while the lease
  is held):

      [reagent/with-resource-lease
       {:resource :my/feed :scope :rf.scope/global :params {:page 0}}
       (fn [] [feed-view])]

  Or with opts (a map between the descriptor and the body thunk):

      [reagent/with-resource-lease
       {:resource :my/feed :scope … :params …}
       {:cause :dashboard-widget :frame :some-frame}
       (fn [] [feed-view])]

  `descriptor` is the resource-instance identity `{:resource :scope
  :params}` (the ensure payload's read keys, Spec 016 §Events). `opts`:
    :cause  — recorded on the ensure (observability; free-form data value).
              Defaults to `[:lease :mount]`.
    :frame  — pin the lease to an explicit frame id, bypassing ambient
              frame-provider / dynamic-var resolution.

  A single Form-3 `create-class` (NOT a Form-2 returning one — that would
  mint a fresh component type per render and remount). It declares
  `:context-type` = the shared frame-context (via
  `re-frame.adapter.context/frame-context`) so the instance receives the
  surrounding `frame-provider`'s frame — the same wiring `reg-view` uses.

  Frame resolution + timing (EP-0002 tier precedence): the lease frame is
  resolved in `:reagent-render` via `resolve-lease-frame` — the explicit
  `:frame` opt, else the canonical `frame/require-current-frame!` reader
  (dynamic-var `*current-frame*` FIRST, then the React-context tier, else the
  loud `:rf.error/no-frame-context`). Resolving at RENDER time (not in a
  commit-phase lifecycle method) keeps the dynamic-var tier able to win and
  mirrors the React-hook twin's render-time resolution. The resolved frame +
  descriptor + cause are stashed on the instance so the commit-phase methods
  act on the render-time target even after the render scope unwinds.

  Re-lease on change: a `:component-did-update` diffs the render-time
  `[frame descriptor cause]` against the currently-held lease and, on any
  change, RELEASES the old lease (into its old frame) then ENSURES the new
  target — mirroring the twin's `deps-key`-keyed effect. So a descriptor
  whose `:params` change across re-renders releases the old resource and
  ensures the new one WITHOUT waiting for unmount; a value-equal descriptor
  holds ONE lease with no churn. (React does not re-run
  `:component-did-mount` on a props change, so re-leasing must live here.)

  Idempotency: the lease owner is minted ONCE per instance (in `did-mount`)
  and reused across re-leases, so a hot-reload re-mount settles to exactly
  one held lease (ensure re-attaches the same lease; Spec 016 §Active
  owners). Under SSR (`render-to-string`) lifecycle methods do not run, so
  the acquire/release is a natural no-op — the lease is a client-lifetime
  concern.

  The lease identity + lifecycle live once in
  `re-frame.adapter.resource-lease/make-resource-lease-component` (rf2-qdkt8y);
  this Var supplies only stock Reagent's `current-component` reader + the
  stock-Reagent context-type wiring (create-class copies a `:context-type` map
  key to the static `contextType`, read via the `:adapter/current-frame` hook
  during frame resolution)."
  (resource-lease/make-resource-lease-component
    {:current-component r/current-component
     ;; Reagent's create-class copies `:context-type` (camelCased to the React
     ;; static `contextType`) onto the class, so the in-flight component's
     ;; context carries the enclosing frame-provider's value — the class-
     ;; component parity of the reg-view `{:contextType frame-context}` wiring.
     :build-class (fn [class-map]
                    (r/create-class
                      (assoc class-map :context-type adapter-context/frame-context)))}))

(def adapter
  "The Reagent adapter map. Pass to `(rf/init! ...)` to install:

      (require '[re-frame.adapter.reagent :as reagent])
      (rf/init! reagent/adapter)

  See Spec 006 §CLJS reference: Reagent as default adapter for the
  bridging pseudocode. Per rf2-agql there is no default-adapter
  registry — adapter wiring is explicit at the call site.

  The container quartet, renderer, render-to-string and dispose body
  come from `spine/make-ratom-spine` (rf2-rzex9, shared with
  reagent-slim); the adapter map + the nine-call ratom-family
  `route-hook!` table + the chained SSR-emitter install are assembled by
  `spine/make-ratom-adapter` (rf2-ee38b.1, also shared with reagent-slim).
  `register-context-provider` is passed in (NOT spine-built) because it
  is the Reagent-component-shaped frame-provider from `re-frame.views`,
  distinct from the React-hook spine's hook-shaped one — keeping the core
  spine free of a spine→views dependency edge. That provider keeps
  children as trailing-positional hiccup (`[provider frame-kw & children]`)
  — Reagent's idiomatic shape and the cross-substrate baseline the UIx /
  Helix `$`-trailing-children providers were aligned toward (rf2-7kii2,
  which moved only those React-hook adapters; the Reagent shape is
  unchanged).

  The hook ops are injected stock-`reagent.*` impls (flat bare-fn config
  keys, rf2-0u5em6) so the spine never names a reactive-atom ns (bundle
  isolation, identical to `make-ratom-spine`'s bare-fn contract). Per-hook
  rationale lives at `spine/make-ratom-adapter`."
  (spine/make-ratom-adapter
    spine-fns
    {:kind :rf.adapter/reagent
     ;; The frame-keyword arg is ignored — `build-frame-provider` is
     ;; 0-arity (rf2-4y60); the returned component takes the frame keyword
     ;; at render time. The arg stays in the substrate signature per
     ;; Spec 006 §Frame-provider via React context.
     :register-context-provider (fn [_frame-keyword] (views/build-frame-provider))
     ;; Injected stock-Reagent ops for the ratom-family late-bind hooks
     ;; (flat bare-fn config keys, rf2-0u5em6 — the spine assembles the
     ;; route-hook table from them). rf2-jicu2: the dual-IDisposable
     ;; dispatch (re-frame-owned first, then the substrate's) is handled
     ;; inside make-ratom-adapter — this adapter supplies only the
     ;; substrate-side `disposable?` predicate + `add-on-dispose!` /
     ;; `dispose!` impls.
     :current-frame     views/current-frame
     :current-component r/current-component
     :atom              r/atom
     :ratom?            (fn [x] (satisfies? ratom/IReactiveAtom x))
     :make-reaction     ratom/make-reaction
     :disposable?       (fn [a] (satisfies? ratom/IDisposable a))
     :add-on-dispose!   ratom/add-on-dispose!
     :dispose!          ratom/dispose!
     :reactive?         ratom/reactive?
     :after-render      r/after-render}))
