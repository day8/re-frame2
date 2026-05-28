(ns re-frame.adapter.reagent
  "The Reagent adapter — browser default. Implements the substrate
  contract; see `re-frame.substrate.adapter` for the contract itself
  (canonical home: `core/src/re_frame/substrate/adapter.cljc`) and
  Spec 006 §CLJS reference for the per-slot semantics."
  (:require [reagent.core :as r]
            [reagent.ratom :as ratom]
            [reagent.dom.client :as rdc]
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
;; INJECTED here so the spine never `:require`s a reactive-atom ns: this
;; adapter passes its stock `reagent.*` impls; the slim adapter passes
;; `reagent2.*`. (Load-bearing for reagent-slim bundle isolation — the
;; slim adapter's deps.edn carries zero direct `reagent.*` requires.)

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
     :ratom-ops         {:r/atom              (fn [v] (r/atom v))
                         :ratom/make-reaction (fn [thunk] (ratom/make-reaction thunk))
                         :rdc/create-root     (fn [mp] (rdc/create-root mp))
                         :rdc/render          (fn [root tree] (rdc/render root tree))
                         :rdc/hydrate-root    (fn [mp tree] (rdc/hydrate-root mp tree))
                         :rdc/unmount         (fn [root] (rdc/unmount root))}}))

(def set-hiccup-emitter!
  "Install the hiccup → HTML fn used by render-to-string. Last call wins.
  Per rf2-uo7v / IMPL-SPEC §2.1: published through the late-bind hook
  `:reagent/set-hiccup-emitter!` so the SSR seam at re-frame.ssr
  resolves it at load time without a static :require."
  (:set-hiccup-emitter! spine-fns))

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
  spine free of a spine→views dependency edge. The `:hook-ops` are
  injected stock-`reagent.*` impls so the spine never names a reactive-
  atom ns (bundle isolation, identical to `make-ratom-spine`'s
  `:ratom-ops` contract). Per-hook rationale lives at
  `spine/make-ratom-adapter`."
  (spine/make-ratom-adapter
    spine-fns
    {:kind :rf.adapter/reagent
     ;; The frame-keyword arg is ignored — `build-frame-provider` is
     ;; 0-arity (rf2-4y60); the returned component takes the frame keyword
     ;; at render time. The arg stays in the substrate signature per
     ;; Spec 006 §Frame-provider via React context.
     :register-context-provider (fn [_frame-keyword] (views/build-frame-provider))
     ;; Injected stock-Reagent ops for the ratom-family late-bind hooks.
     ;; rf2-jicu2: the dual-IDisposable dispatch (re-frame-owned first,
     ;; then the substrate's) is handled inside make-ratom-adapter — this
     ;; adapter supplies only the substrate-side `disposable?` predicate +
     ;; `add-on-dispose!` / `dispose!` impls.
     :hook-ops {:current-frame     views/current-frame
                :current-component r/current-component
                :atom              r/atom
                :ratom?            (fn [x] (satisfies? ratom/IReactiveAtom x))
                :make-reaction     ratom/make-reaction
                :disposable?       (fn [a] (satisfies? ratom/IDisposable a))
                :add-on-dispose!   ratom/add-on-dispose!
                :dispose!          ratom/dispose!
                :reactive?         ratom/reactive?
                :after-render      r/after-render}}))
