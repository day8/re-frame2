(ns re-frame.adapter.reagent-slim
  "The day8/reagent-slim adapter — emits the 9-key substrate map for
  `re-frame.substrate.adapter`. Shape-compatible with the bridge
  adapter `re-frame.adapter.reagent`; internals route through the
  `reagent2.*` rewrite of Reagent (no stock-Reagent dep).

      (require '[re-frame.adapter.reagent-slim :as reagent-slim])
      (rf/init! reagent-slim/adapter)

  See IMPL-SPEC.md §2.1 (9-key map contract), §9 (late-bind hook table),
  §13.1 (artefact-publication shape) and DESIGN-RATIONALE.md."
  (:require [reagent2.core             :as r]
            [reagent2.ratom            :as ratom]
            [reagent2.dom.client       :as rdc]
            [reagent2.impl.batching    :as batching]
            [re-frame.substrate.spine   :as spine]
            [re-frame.views            :as views]))

;; ---- shared ratom-spine wiring --------------------------------------------
;;
;; The container quartet, the React-root renderer, the dispose body and
;; the SSR emitter helpers are the SAME shape as the bridge adapter
;; `re-frame.adapter.reagent`, modulo the reactive-atom impl (`reagent2.*`
;; here, stock `reagent.*` there). They live in
;; `re-frame.substrate.spine/make-ratom-spine` (rf2-rzex9) — one
;; implementation, two adapters, zero drift, mirroring the React-hook
;; `make-react-spine` that backs UIx/Helix.
;;
;; CRITICAL — bundle isolation (IMPL-SPEC §1.8 / the
;; `test:reagent-slim:bundle-isolation` gate). The spine MUST NOT
;; `:require` stock `reagent.*`; the reactive-atom ops are INJECTED here
;; so this adapter's `reagent2.*` requires stay confined to this ns and
;; the spine never names a reactive-atom ns. deps.edn carries zero direct
;; `reagent.*` requires (the slim framing: a re-implementation, not a
;; thin wrapper).

(def ^:private spine-fns
  (spine/make-ratom-spine
    {;; Substrate-scoped gensym prefix (rf2-l4dmr) so a cross-substrate
     ;; test bundle / log / inspector can attribute a watch to the slim
     ;; adapter rather than confusing it with a stock-Reagent watch.
     :gensym-prefix-sub "rf-reagent-slim-sub-"
     ;; Each op is a thin call-through lambda rather than the bare Var
     ;; value so the spine resolves the namespaced fn at CALL time. This
     ;; keeps the `with-redefs [rdc/create-root …]` test-observability the
     ;; slim render / dispose-drain pins rely on (capturing the bare Var
     ;; value at load time would freeze the original impls past any
     ;; `with-redefs` rebind). Runtime behaviour is identical.
     :ratom-ops         {:r/atom              (fn [v] (r/atom v))
                         :ratom/make-reaction (fn [thunk] (ratom/make-reaction thunk))
                         :rdc/create-root     (fn [mount-point] (rdc/create-root mount-point))
                         :rdc/render          (fn [root tree] (rdc/render root tree))
                         :rdc/hydrate-root    (fn [mount-point tree] (rdc/hydrate-root mount-point tree))
                         :rdc/unmount         (fn [root] (rdc/unmount root))
                         ;; rf2-40a84 — synchronous render-flush op. Run `f`
                         ;; (which may mutate a ratom / dispatch), then
                         ;; `reagent2.impl.batching/flush!` SYNCHRONOUSLY drains
                         ;; the rea-queue + forceUpdates every dirty component —
                         ;; the rewrite's microtask scheduler is bypassed, so
                         ;; the commit is NOT microtask/rAF-deferred and fires
                         ;; even in a backgrounded / headless tab. (Distinct
                         ;; from `reagent2.dom.client/flush-views!`, the
                         ;; goog.DEBUG-gated act()-composing TEST primitive.)
                         :rdc/flush-render!   (fn [f] (f) (batching/flush!))}}))

(def set-hiccup-emitter!
  "Install the hiccup → HTML fn used by render-to-string. Last call wins.
  Per rf2-uo7v / IMPL-SPEC §2.1: published through the late-bind hook
  `:reagent/set-hiccup-emitter!` so the SSR seam at re-frame.ssr
  resolves it at load time without a static :require."
  (:set-hiccup-emitter! spine-fns))

(def flush-views!
  "Flush pending slim renders synchronously. Wraps React's act() —
  intended for test code only. Calls (act (fn [] (batching/flush!)));
  with `f`, runs `f` then the synchronous render drain inside act.
  Returns nil. No-op when act() is unreachable in the current React build.

  Per rf2-3yij Decision 6 / rf2-b6nm5: the canonical test-flush hook,
  surfaced identically (same name, same ADAPTER-ns location, same
  nil-return shape) across all four substrates — Reagent, reagent-slim,
  UIx, Helix — so a test suite ports across substrates touching only the
  init! Var. This is the canonical convergence: previously the only slim
  flush-views! lived in the SUBSTRATE ns `reagent2.dom.client` and RETURNED
  A PROMISE (the goog.DEBUG-gated microtask→act→microtask Suspense-ordering
  primitive, IMPL-SPEC §4.6), which diverged in both location and return
  type. That substrate-level primitive remains for Suspense-deterministic
  callers; this adapter-ns Var is the cross-substrate canonical surface."
  (:flush-views! spine-fns))

(def adapter
  "The reagent-slim adapter map. Pass to `(rf/init! ...)` to install:

      (require '[re-frame.adapter.reagent-slim :as reagent-slim])
      (rf/init! reagent-slim/adapter)

  Drop-in shape-compatible with `re-frame.adapter.reagent/adapter` per
  IMPL-SPEC §2.1 — the only difference is the substrate, not the keys.
  Per Spec 006 §CLJS reference + rf2-agql: there is no default-adapter
  registry; adapter wiring is explicit at the call site.

  The container quartet, renderer, render-to-string and dispose body
  come from `spine/make-ratom-spine` (rf2-rzex9, shared with the bridge
  Reagent adapter under an injected `reagent2.*` op set); the adapter
  map + the nine-call ratom-family `route-hook!` table + the chained
  SSR-emitter install are assembled by `spine/make-ratom-adapter`
  (rf2-ee38b.1, also shared with the bridge adapter under an injected
  `reagent2.*` hook-ops set). `register-context-provider` is passed in
  (NOT spine-built) because it is the Reagent-component-shaped frame-
  provider from `re-frame.views`, distinct from the React-hook spine's
  hook-shaped one — keeping the core spine free of a spine→views edge.
  CRITICAL: bundle isolation is preserved — the `:hook-ops` are injected
  `reagent2.*` impls, so the spine names no reactive-atom ns (the
  `test:reagent-slim:bundle-isolation` gate by construction). Per-hook
  rationale lives at `spine/make-ratom-adapter`."
  (spine/make-ratom-adapter
    spine-fns
    {:kind :rf.adapter/reagent-slim
     ;; The frame-keyword arg is ignored — `build-frame-provider` is
     ;; 0-arity (rf2-4y60); the returned component takes the frame keyword
     ;; at render time. Per IMPL-SPEC §9.4: views.cljs continues to back
     ;; the frame-provider; the rewrite doesn't replace it.
     :register-context-provider (fn [_frame-keyword] (views/build-frame-provider))
     ;; Injected reagent2.* ops for the ratom-family late-bind hooks.
     ;; Wiring reagent2.* impls is load-bearing (rf2-s36l): without this
     ;; seam the first (interop/add-on-dispose! ...) under the slim
     ;; adapter threw because reagent2.ratom/Reaction does NOT reify stock
     ;; Reagent's IDisposable. rf2-jicu2: the dual-IDisposable dispatch
     ;; (re-frame-owned first, then reagent2's) is handled inside
     ;; make-ratom-adapter — this adapter supplies the substrate-side
     ;; `disposable?` predicate + `add-on-dispose!` / `dispose!` impls.
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
