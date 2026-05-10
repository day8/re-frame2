(ns counter.core
  "A minimal counter against the re-frame2 API.

   Demonstrates: `reg-event-db`, `reg-sub`, `reg-view` with frame-bound
   `dispatch`/`subscribe` injection.

   NOTE on frame-provider: an earlier shape of this example wrapped
   `:counter-buttons` in `[rf/frame-provider {:frame :counter} ...]`
   so the subtree resolved its current frame to `:counter` rather
   than `:rf/default`. The kebab-vs-camelCase bug behind rf2-kdwc
   (Reagent recognises the class-static field as `:contextType`, not
   `:context-type`) was fixed under rf2-25aq alongside the Reagent v2
   bump. The example still uses the default frame to keep the smoke
   test focused; the frame-provider variant is exercised by
   examples/reagent/login and the cross-spec test suite."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core    :as rf]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.views-macros :refer [reg-view]]))

;; -- Events / subs (handler registry is app-global) --------------------------

;; A user-registered fx so the perf-instrumented variant of this
;; example (out/examples/counter-perf — see shadow-cljs.edn and rf2-du3i)
;; exercises the `rf:fx:<id>` perf bucket too. The default counter spec
;; doesn't observe any user fx; this fx fires on init and records its
;; calls into a process-local atom so the regular counter spec is
;; unaffected. The perf-on counter spec
;; (examples/reagent/counter/counter-perf.spec.cjs) reads
;; `performance.getEntriesByType('measure')` and asserts an entry under
;; `rf:fx:counter/log` is present.

(defonce counter-log (atom []))

(rf/reg-fx :counter/log
  (fn [_ctx args]
    (swap! counter-log conj args)))

;; reg-event-fx so the handler can return both `:db` and a `:fx` walk.
;; Mixing :db and :fx in one handler makes the example exercise every
;; perf bucket (event handler, sub recompute, fx walk, render) on a
;; single dispatch — which is what the counter-perf browser smoke
;; observes. Per Spec 002 §`:fx` ordering: :db commits first, then :fx
;; walks in source order.
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
;; The `:counter-buttons` view holds the UI. Per Spec 004 §reg-view, the
;; defn-shape macro auto-injects `dispatch` and `subscribe` as lexical
;; bindings; both resolve at render time to the frame in scope (the
;; default frame here, see the namespace docstring for the
;; frame-provider variant parked behind rf2-kdwc).
;;
;; reg-view auto-defs a Var named after the supplied symbol and
;; registers the view under (keyword *ns* sym).

(reg-view counter-buttons []
  [:div
   [:button {:on-click #(dispatch [:counter/dec])} "-"]
   [:span {:style {:margin "0 1em"}} @(subscribe [:count])]
   [:button {:on-click #(dispatch [:counter/inc])} "+"]])

;; The root `counter-app` view renders `counter-buttons` against the
;; default frame. Initialisation runs in `run` via `dispatch-sync`.

(reg-view counter-app []
  [counter-buttons])

;; -- Mount -------------------------------------------------------------------

(defonce root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  ;; rf2-84po: requiring re-frame.adapter.reagent (above) registered
  ;; the Reagent adapter as the default at ns-load time, so no-arg
  ;; init! resolves through the registry.
  (rf/init!)
  (rf/dispatch-sync [:counter/initialise])
  (rdc/render root [counter-app]))
