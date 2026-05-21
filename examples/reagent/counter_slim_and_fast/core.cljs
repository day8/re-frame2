(ns counter-slim-and-fast.core
  "A minimal counter mounted on the day8/reagent-slim rewrite. Binds the
   S3-008 contract from reagent-slim's
   IMPL-SPEC §1.4 + §1.8 + §6 + §12.

   Same six-domino dataflow as `examples/reagent/counter`, but every
   user-facing Reagent import points at `reagent2.*` instead of stock
   `reagent.*`, and `(rf/init!)` is called with the slim adapter Var
   `re-frame.adapter.reagent-slim/adapter`.

   What this fixture proves (the Reagent Slim bundle-isolation contract):

     - The advanced-compiled bundle for this example contains NO
       `reagent.impl.*` symbols. The slim rewrite has its own
       `reagent2.impl.*` substrate; the bridge's `reagent.impl.*`
       internals must be entirely absent.
     - The bundle contains NO `react-dom/server` symbols. The slim's
       `reagent2.dom.server/render-to-static-markup` is pure-CLJS
       (per IMPL-SPEC §8.7), so the bundle has no compiled-in path
       to `react-dom/server`.
     - The stock-Reagent counter bundle (`examples/counter`) keeps
       both groups of symbols, demonstrating that the assertion logic
       actually detects them.

   See `implementation/scripts/check-reagent-slim-bundle-isolation.cjs` for the
   bundle-isolation grep that enforces both invariants in CI.

   NOTE on frame-provider: the namespace docstring on
   `counter.core` describes a frame-provider variant of this example
   wired in `examples/reagent/login`. The slim variant stays on the
   default frame for the same reason — it keeps the smoke test
   focused on the substrate swap (stock → slim) rather than the
   frame-provider feature."
  (:require [reagent2.dom.client                :as rdc]
            [reagent2.dom.server                :as rds]
            [re-frame.core                      :as rf]
            [re-frame.views]
            [re-frame.adapter.reagent-slim      :as reagent-slim-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; -- Events / subs (handler registry is app-global) --------------------------

(defonce counter-log (atom []))

(rf/reg-fx :counter/log
  (fn [_ctx args]
    (swap! counter-log conj args)))

(rf/reg-event-fx :counter/initialise
  (fn [_ctx _event]
    {:db {:count 5}
     :fx [[:counter/log :initialised]]}))

(rf/reg-event-db :counter/inc
  (fn [db _event] (update db :count inc)))

(rf/reg-event-db :counter/dec
  (fn [db _event] (update db :count dec)))

(rf/reg-sub :count
  (fn [db _query] (:count db)))

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
   [:span {:style {:margin "0 1em"} :data-testid "counter-value"} @(subscribe [:count])]
   [:button {:on-click #(dispatch [:counter/inc])} "+"]])

(reg-view counter-app []
  [counter-buttons])

;; -- Mount -------------------------------------------------------------------

(defonce root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
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
  ;; The result is retained in `counter-log` so the closure compiler
  ;; can't DCE the call (a no-op `js/console.log` would be elided).
  (swap! counter-log conj [:pre-rendered (rds/render-to-static-markup [counter-app])])
  (rdc/render root [counter-app]))
