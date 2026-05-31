(ns counter-slim-and-fast.core
  "A minimal counter mounted on the day8/reagent-slim rewrite. Binds the
   S3-008 + S3-005 bundle-isolation contract from reagent-slim's
   IMPL-SPEC §1.4 + §1.8 + §8.

   Same six-domino dataflow as `examples/reagent/counter`, but every
   user-facing Reagent import points at `reagent2.*` instead of stock
   `reagent.*`, and `(rf/init!)` is called with the slim adapter Var
   `re-frame.adapter.reagent-slim/adapter`.

   What this fixture proves (the Reagent Slim bundle-isolation contract):

     - Stock-Reagent impl isolation (S3-008): the advanced-compiled
       bundle for this example contains NO `reagent.impl.*` symbols.
       The slim rewrite has its own `reagent2.impl.*` substrate; the
       bridge's `reagent.impl.*` internals must be entirely absent.
     - Pure-CLJS SSR (S3-005): the bundle contains NO `react-dom/server`
       symbols. The slim's
       `reagent2.dom.server/render-to-static-markup` is pure-CLJS
       (per IMPL-SPEC §8.7), so the bundle has no compiled-in path
       to `react-dom/server`.
     - The stock-Reagent counter bundle (`examples/counter`) keeps
       both groups of symbols, demonstrating that the assertion logic
       actually detects them.

   See `implementation/scripts/check-reagent-slim-bundle-isolation.cjs` for the
   bundle-isolation grep that enforces both invariants in CI.

   NOTE on frames: this fixture stays on the default frame
   deliberately. Keeping it single-frame focuses the bundle-isolation
   check on the substrate swap (stock -> slim) and nothing else."
  (:require [reagent2.dom.client                :as rdc]
            [reagent2.dom.server                :as rds]
            [re-frame.core                      :as rf]
            [re-frame.views]
            [re-frame.adapter.reagent-slim      :as reagent-slim-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; -- Events / subs (handler registry is app-global) --------------------------

(rf/reg-event-db :counter/initialise
  (fn [_db _event] {:counter/value 5}))

(rf/reg-event-db :counter/inc
  (fn [db _event] (update db :counter/value inc)))

(rf/reg-event-db :counter/dec
  (fn [db _event] (update db :counter/value dec)))

(rf/reg-sub :counter/value
  (fn [db _query] (:counter/value db)))

;; -- Views -------------------------------------------------------------------
;;
;; reg-view is substrate-agnostic — the macro expands to a plain
;; React-component shape that consults the active adapter's
;; `register-context-provider` / `current-component` seams at render
;; time. With the slim adapter installed (see `run` below), the
;; rendered component reads frame state through
;; `reagent2.core/current-component` rather than stock Reagent's.

(reg-view counter-buttons []
  [:div
   [:button {:on-click #(dispatch [:counter/dec])} "-"]
   [:span {:style {:margin "0 1em"} :data-testid "counter-value"} @(subscribe [:counter/value])]
   [:button {:on-click #(dispatch [:counter/inc])} "+"]])

(reg-view counter-app []
  [counter-buttons])

;; -- Mount -------------------------------------------------------------------

;; Eager `(defonce root (create-root ..))` — intentionally mirrors its
;; behavioural twin `examples/reagent/counter`, not the lazy
;; `(defonce react-root (atom nil))` shape the larger examples use. This
;; is a release / bundle-isolation fixture (never co-loaded with the
;; browser-test build), so the eager form is the clearer, smaller shape
;; and the divergence is deliberate.
(defonce root
  (rdc/create-root (js/document.getElementById "app")))

(defn run []
  ;; Slim adapter — the difference from `examples/reagent/counter` is
  ;; right here. Same `rf/init!` signature; different adapter Var.
  (rf/init! reagent-slim-adapter/adapter)
  (rf/dispatch-sync [:counter/initialise])
  ;; Exercise the slim's pure-CLJS render-to-static-markup so the
  ;; bundle DOES compile in the SSR path. The bundle-isolation
  ;; contract (S3-008) is then non-vacuous: even with SSR pulled in,
  ;; the bundle still must NOT contain `react-dom/server` symbols,
  ;; because reagent2.dom.server is pure-CLJS (no
  ;; `["react-dom/server" :as ...]` import — see IMPL-SPEC §8).
  ;;
  ;; DCE protection: write the rendered string onto a host-global
  ;; property. The closure compiler treats writes to extern-shaped
  ;; properties on `globalThis` as side effects; the call survives
  ;; `:advanced`. (A no-op like `js/console.log` would be elided.)
  (set! (.-counterSlimPrerender js/globalThis)
        (rds/render-to-static-markup [counter-app]))
  (rdc/render root [counter-app]))
