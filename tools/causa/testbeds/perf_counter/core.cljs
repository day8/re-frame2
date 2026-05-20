(ns perf-counter.core
  "Perf-instrumented counter testbed (rf2-p8f2s).

   This is the tool-owned (Causa-family) variant of the counter — same
   dataflow as the tutorial `examples/reagent/counter/`, but with the
   `:initialise` handler INTENTIONALLY widened from the canonical
   `reg-event-db` shape to `reg-event-fx` so the perf-instrumented
   bundle (`:examples/counter-perf` — see implementation/shadow-cljs.edn)
   exercises every perf bucket on a single dispatch:

     - event handler — :counter/initialise itself
     - sub recompute — :count
     - fx walk       — :counter/log
     - render        — counter-buttons

   The paired browser smoke at `tools/causa/testbeds/perf_counter/spec.cjs`
   was DELETED in Wave 4 (rf2-e3j8l). The migrated assertions live as
   pure CLJS at
   `implementation/core/test/re_frame/performance_emit_nightly_test.cljs`
   and run NIGHTLY ONLY via the `:node-test-perf-nightly` shadow-cljs
   build (`npm run test:cljs-perf-emit-nightly`). The testbed itself
   (core.cljs + index.html) stays as the Causa-displayable showcase —
   a live perf-on counter for hands-on inspection at
   `examples/counter-perf` under `npx shadow-cljs watch`. The fx-bucket
   entry (`rf:fx:counter/log`) is still produced by the `:fx` walk
   attached to the initialise dispatch.

   This non-canonical shape lives HERE — in the perf testbed — so the
   canonical tutorial example (`examples/reagent/counter/core.cljs`)
   stays as the idiomatic `reg-event-db` shape every other example
   uses for its initialiser."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core    :as rf]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; -- Events / subs (handler registry is app-global) --------------------------

;; The fx that puts the perf bundle's `rf:fx:counter/log` measure entry
;; on the User-Timing stream. Records its calls into a process-local
;; atom so the spec can inspect them if needed.

(defonce counter-log (atom []))

(rf/reg-fx :counter/log
  (fn [_ctx args]
    (swap! counter-log conj args)))

;; ----------------------------------------------------------------------------
;; DELIBERATE PERF-BUCKET EXCEPTION — perf testbed owns this non-canonical shape.
;;
;; The canonical re-frame2 :initialise shape is `reg-event-db` returning a
;; plain seeded db — no `:fx`. This handler is INTENTIONALLY widened to
;; `reg-event-fx` with both `:db` and `:fx` so the perf-instrumented
;; bundle exercises every perf bucket on a single init dispatch. The
;; browser smoke at tools/causa/testbeds/perf_counter/spec.cjs asserts a
;; `rf:fx:counter/log` performance measure entry exists; the only way to
;; produce that on init without a user click is to attach the `:fx` walk
;; to the initialise dispatch itself.
;;
;; Per Spec 002 §`:fx` ordering: :db commits first, then :fx walks in
;; source order.
;;
;; READER NOTE — do NOT propagate this `:db + :fx` mix to your own
;; :initialise handlers. The idiomatic shape is:
;;
;;     (rf/reg-event-db :app/initialise
;;       (fn [_ _] {:count 5}))                            ;; just the seed db
;;
;; The widening here exists ONLY so the perf-instrumented build has a
;; non-empty :fx walk to measure on init. The canonical tutorial counter
;; (`examples/reagent/counter/core.cljs`) and every other example in the
;; repo uses plain `reg-event-db` for its initialiser.
;; ----------------------------------------------------------------------------

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
  (rf/init! reagent-adapter/adapter)
  (rf/dispatch-sync [:counter/initialise])
  (rdc/render root [counter-app]))
