(ns re-frame.adapter.reagent-slim
  "The day8/reagent-slim adapter — emits the substrate map for
  `re-frame.substrate.adapter` (6 required + 3 optional + 1 lifecycle
  fn). Shape-compatible with the bridge adapter `re-frame.adapter.reagent`;
  internals route through the `reagent2.*` rewrite of Reagent (no
  stock-Reagent dep).

      (require '[re-frame.adapter.reagent-slim :as reagent-slim])
      (rf/init! reagent-slim/adapter)

  See IMPL-SPEC.md §2.1 (adapter map contract), §9 (late-bind hook table),
  §13.1 (artefact-publication shape) and DESIGN-RATIONALE.md."
  (:require [reagent2.core             :as r]
            [reagent2.ratom            :as ratom]
            [reagent2.dom.client       :as rdc]
            [reagent2.impl.template    :as template]
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
;; as a flat set of bare fns so this adapter's `reagent2.*` requires stay
;; confined to this ns and the spine never names a reactive-atom ns.
;; deps.edn carries zero direct `reagent.*` requires (the slim framing: a
;; re-implementation, not a thin wrapper).
;;
;; rf2-0u5em6: the spine assembles its internal ratom-ops map from these
;; bare fns. This adapter passes ~7 bare fns (flat config keys, mirroring
;; `make-react-spine`'s bare hook fns) instead of a hand-shaped keyword
;; map — earlier this adapter and the bridge one each built a structurally-
;; identical 7-key `:ratom-ops` map differing only by ns-alias, a "keep two
;; maps in lockstep" hazard the flat-bare-fn shape removes.

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
     :r-atom        (fn [v] (r/atom v))
     :make-reaction (fn [thunk] (ratom/make-reaction thunk))
     :create-root   (fn [mount-point] (rdc/create-root mount-point))
     :render-root   (fn [root tree] (rdc/render root tree))
     :hydrate-root  (fn [mount-point tree] (rdc/hydrate-root mount-point tree))
     :unmount-root  (fn [root] (rdc/unmount root))
     ;; rf2-40a84 / rf2-0bz5ah — synchronous render-commit
     ;; op. Runs `f` (which may mutate a ratom / dispatch),
     ;; then drains the rea-queue + forceUpdates every dirty
     ;; component via `reagent2.impl.batching/flush!`, INSIDE
     ;; a `react-dom/flushSync` boundary so the forced
     ;; re-renders COMMIT TO THE DOM synchronously before the
     ;; op returns. Under React 19 `createRoot` a bare
     ;; `forceUpdate` from outside React's batching is
     ;; auto-batched (scheduled, not committed), so the
     ;; boundary is load-bearing — see
     ;; `reagent2.dom.client/flush-render!` for the full
     ;; rationale + the empirical proof
     ;; (`reagent_slim_flush_render_dom_cljs_test`). The
     ;; commit is NOT microtask/rAF-deferred and fires even
     ;; in a backgrounded / headless tab. (Distinct from
     ;; `reagent2.dom.client/flush-views!`, the goog.DEBUG-
     ;; gated act()-composing TEST primitive.)
     :flush-render! rdc/flush-render!}))

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
  Reagent adapter under an injected `reagent2.*` bare-fn set); the adapter
  map + the nine-call ratom-family `route-hook!` table + the chained
  SSR-emitter install are assembled by `spine/make-ratom-adapter`
  (rf2-ee38b.1, also shared with the bridge adapter under an injected
  `reagent2.*` bare-fn hook set). `register-context-provider` is passed in
  (NOT spine-built) because it is the Reagent-component-shaped frame-
  provider from `re-frame.views`, distinct from the React-hook spine's
  hook-shaped one — keeping the core spine free of a spine→views edge.
  CRITICAL: bundle isolation is preserved — the hook ops are injected
  `reagent2.*` impls (flat bare-fn config keys, rf2-0u5em6), so the spine
  names no reactive-atom ns (the `test:reagent-slim:bundle-isolation` gate
  by construction). Per-hook rationale lives at `spine/make-ratom-adapter`."
  (spine/make-ratom-adapter
    spine-fns
    {:kind :rf.adapter/reagent-slim
     ;; The frame-keyword arg is ignored — `build-frame-provider` is
     ;; 0-arity (rf2-4y60); the returned component takes the frame keyword
     ;; at render time. Per IMPL-SPEC §9.4: views.cljs continues to back
     ;; the frame-provider; the rewrite doesn't replace it.
     :register-context-provider (fn [_frame-keyword] (views/build-frame-provider))
     ;; Injected reagent2.* ops for the ratom-family late-bind hooks (flat
     ;; bare-fn config keys, rf2-0u5em6 — the spine assembles the route-hook
     ;; table from them). Wiring reagent2.* impls is load-bearing (rf2-s36l):
     ;; without this seam the first (interop/add-on-dispose! ...) under the
     ;; slim adapter threw because reagent2.ratom/Reaction does NOT reify
     ;; stock Reagent's IDisposable. rf2-jicu2: the dual-IDisposable dispatch
     ;; (re-frame-owned first, then reagent2's) is handled inside
     ;; make-ratom-adapter — this adapter supplies the substrate-side
     ;; `disposable?` predicate + `add-on-dispose!` / `dispose!` impls.
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

;; ---- warn-once cache reset wiring (rf2-qy6cl) -----------------------------
;;
;; The slim hiccup interpreter carries a second warn-once cache beyond the
;; spine's source-coord/non-DOM-root one: `reagent2.impl.template`'s
;; `warned-keyword-prop` defonce (the §7.2 D2 keyword-on-non-HTML-prop
;; notice). The source-coord cache is already chained into
;; `:adapter/clear-warn-once-caches!` by `make-ratom-adapter` (rf2-4edk);
;; the keyword-prop cache was left behind. Chain its clear-step here too —
;; via the same `spine/install-clear-warn-once-step!` helper — so
;; `make-reset-runtime-fixture` re-arms BOTH slim caches between tests and
;; a sibling test cannot silently swallow a later same-pair warning. This
;; lives in the adapter ns (not in reagent2.impl.template) to keep the
;; vendored `reagent2.*` tree free of any `re-frame.*` edge — bundle
;; isolation by construction.
;;
;; rf2-z79p8: enrol with an explicit label but NO arm/armed? probes — the
;; cache atom is `^:private` in reagent2.impl.template and reaching into it
;; from here would breach the same bundle-isolation rule. The empirical
;; governance assertion skips probe-less entries; the source-enumeration
;; assertion and the dedicated template_keyword_prop re-arm test cover this
;; cache.
(spine/install-clear-warn-once-step! template/clear-warned-keyword-prop!
                                     {:label :reagent-slim/warned-keyword-prop})
